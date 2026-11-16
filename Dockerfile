# 應用程式執行映像：JDK 25 JRE + ffmpeg
# ffmpeg 是必要的：TTS 回傳 PCM，必須轉成 MP3 才能在瀏覽器播放與合併。
#
# 多階段建置：
#   build 階段用 JDK 編譯 healthcheck 工具（JRE 不含 jdk.compiler，
#   無法用 java 的 source 模式直接執行 .java）
#   runtime 階段只放 JRE + ffmpeg，維持映像小

# ---------- build 階段：編譯 healthcheck ----------
FROM docker.io/library/eclipse-temurin:25-jdk AS healthcheck-build

COPY tools/HealthCheck.java /tmp/healthcheck/HealthCheck.java
RUN javac -d /tmp/healthcheck-classes /tmp/healthcheck/HealthCheck.java \
    && mkdir -p /opt/healthcheck \
    && cp /tmp/healthcheck-classes/*.class /opt/healthcheck/


# ---------- runtime 階段 ----------
FROM docker.io/library/eclipse-temurin:25-jre

# ffmpeg + ffprobe：PCM→MP3 轉檔與時長量測
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

# 以非 root 執行
RUN useradd --create-home --shell /bin/bash appuser \
    && mkdir -p /app/storage \
    && chown -R appuser:appuser /app

# named volume 掛載到 /app/storage 時，會沿用映像內該目錄的擁有者與權限
USER appuser
WORKDIR /app

ENV TZ=Asia/Taipei \
    SERVER_PORT=8090 \
    NOVELTTS_STORAGE_ROOT=/app/storage

COPY --chown=appuser:appuser web/target/web.jar /app/web.jar

# healthcheck 工具（從 build 階段帶入的 class 檔）
COPY --from=healthcheck-build --chown=appuser:appuser /opt/healthcheck /opt/healthcheck

EXPOSE 8090

# healthcheck 用 Java 本身（容器內沒有 curl/wget，且 /dev/tcp 是 bash 專屬，
# Docker healthcheck 預設用 sh，會出現 "cannot create /dev/tcp/..."）。
# 直接打 actuator health endpoint。
HEALTHCHECK --interval=15s --timeout=20s --retries=5 --start-period=60s \
    CMD ["java", "-cp", "/opt/healthcheck", "HealthCheck"]

ENTRYPOINT ["java", "-jar", "/app/web.jar"]
