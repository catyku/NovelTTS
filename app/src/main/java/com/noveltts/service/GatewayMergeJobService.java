package com.noveltts.service;

import com.noveltts.ai.GatewayMergeJobStore;
import com.noveltts.tts.AudioTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jakarta.annotation.PreDestroy;

/**
 * 音檔合併的背景工作。
 *
 * <p>流程：
 *
 * <ol>
 *   <li>前端建立工作（{@code POST /api/gateway/merge-jobs}）</li>
 *   <li>前端分批上傳音檔（每批限制在 20MB 以內）</li>
 *   <li>前端呼叫 {@code POST /api/gateway/merge-jobs/{id}/start} 開始合併</li>
 *   <li>伺服器在背景跑 ffmpeg，前端輪詢進度</li>
 *   <li>前端取回結果（{@code GET /api/gateway/merge-jobs/{id}/result}）</li>
 *   <li>前端呼叫 {@code DELETE} 清除暫存檔</li>
 * </ol>
 */
@Service
public class GatewayMergeJobService {

    private static final Logger log = LoggerFactory.getLogger(GatewayMergeJobService.class);

    private final GatewayMergeJobStore store;
    private final AudioTool audioTool;

    /** 背景合併的執行緒池。合併要跑 ffmpeg，同時不宜太多。 */
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "gateway-merge");
        t.setDaemon(true);
        return t;
    });

    public GatewayMergeJobService(GatewayMergeJobStore store, AudioTool audioTool) {
        this.store = store;
        this.audioTool = audioTool;
    }

    /**
     * 開始合併。
     *
     * @param job 工作
     */
    public void start(GatewayMergeJobStore.MergeJob job) {
        executor.submit(() -> run(job));
    }

    /** 執行合併。 */
    private void run(GatewayMergeJobStore.MergeJob job) {
        job.status("MERGING");
        try {
            List<Path> inputs = job.partPaths();
            if (inputs.isEmpty()) {
                job.status("FAILED");
                job.message("沒有可合併的音檔");
                return;
            }
            job.message("合併 " + inputs.size() + " 個音檔…");

            Path output = job.dir().resolve("merged.mp3");
            if ("ZIP".equals(job.type())) {
                zip(inputs, job.partNames(), output);
            } else {
                audioTool.concatMp3(inputs, output);
            }

            job.result(output);
            job.status("DONE");
            job.message("合併完成，結果 " + (output.toFile().length() / 1024) + " KB");
        } catch (Exception e) {
            log.error("合併工作 {} 失敗", job.id(), e);
            job.status("FAILED");
            job.message(String.valueOf(e.getMessage()));
        }
    }

    /**
     * 打包成 ZIP。
     *
     * @param inputs 片段路徑
     * @param names 片段名稱
     * @param output 輸出路徑
     */
    private void zip(List<Path> inputs, List<String> names, Path output) {
        try (OutputStream out = Files.newOutputStream(output);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            for (int i = 0; i < inputs.size(); i++) {
                String name = i < names.size() ? names.get(i) : ("part" + i);
                zos.putNextEntry(new ZipEntry(sanitize(name)));
                Files.copy(inputs.get(i), zos);
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("打包 ZIP 失敗: " + e.getMessage(), e);
        }
    }

    /**
     * 清理檔名：只取檔名部分並去掉路徑穿越的字元。
     *
     * @param name 原始名稱
     * @return 安全的檔名
     */
    private String sanitize(String name) {
        String n = name == null ? "part" : name.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) {
            n = n.substring(slash + 1);
        }
        n = n.replace("..", "_");
        return n.isBlank() ? "part" : n;
    }

    /** 應用程式關閉時停止執行緒池。 */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
