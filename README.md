# novel-tts

小說 AI 分析 + TTS 語音合成系統。輸入小說純文字，AI 分解成「角色 × 段落」，
再呼叫 OpenRouter 的 TTS 模型逐段發聲，輸出每段音檔、章節合併檔與整本 ZIP。

## 技術棧

| 項目 | 版本／選擇 |
|---|---|
| Spring Boot | 4.1.2 |
| JDK | 25 |
| 資料庫 | PostgreSQL 17（Podman 容器） |
| ORM / 遷移 | Spring Data JPA + Flyway |
| AI | OpenRouter（分析 `/api/v1/chat/completions`、合成 `/api/v1/audio/speech`） |
| TTS 模型 | `google/gemini-3.8-flash-tts`（multi-speaker，輸出 PCM，ffmpeg 轉 MP3） |
| 前端 | Thymeleaf + Spring Security（Session-based） |

## 規格文件

實作前先看 `spec/`：

| 文件 | 內容 |
|---|---|
| [spec/00-專案規格總覽.md](spec/00-專案規格總覽.md) | 目標、架構、技術決策、開發順序 |
| [spec/01-資料庫schema.md](spec/01-資料庫schema.md) | 完整 DDL、欄位語意、索引 |
| [spec/02-AI-Prompt契約.md](spec/02-AI-Prompt契約.md) | Prompt 全文、JSON schema、TTS 請求格式、分組策略 |
| [spec/03-API與頁面清單.md](spec/03-API與頁面清單.md) | REST API、頁面、設定鍵 |

## 本機環境

### 容器：本機沒有 Docker daemon，用 Podman

```bash
# 先建應用程式映像（含 ffmpeg，TTS 回傳 PCM 必須轉 MP3）
podman build -t noveltts-app:local -f Dockerfile .

cd WebEnv

# 先複製環境變數並填入 master key
cp .env.example .env
echo "NOVELTTS_MASTER_KEY=$(cat ~/.noveltts-master-key)" >> .env

# 啟動 PostgreSQL + 應用程式（podman-compose 1.3.0 需要在 compose 檔所在目錄執行）
podman-compose up -d

# 查看狀態
podman ps

# 進入 psql
podman exec -it noveltts-pg psql -U noveltts -d noveltts

# 查看應用程式記錄
podman logs -f noveltts-app

# 停止
podman-compose down
```

> ⚠ `podman-compose up -d --force-recreate` 會重建容器並重跑 Flyway。
> 開發時請改用 `podman restart noveltts-app` 快速重啟，避免反覆重建 866MB 的映像。
>
> 兩種啟動方式擇一：
> - **容器模式**（`podman-compose up -d`）：app 跑在容器內，連容器網路的 postgres
> - **本機模式**：只啟 postgres，用 `java -jar web/target/web.jar` 在本機跑 app

> `WebEnv/docker-compose.yaml` 寫成標準格式，搬到有 Docker 的環境直接 `docker compose up -d` 即可。
> 本機一律用 `podman-compose`。

### 環境變數

| 變數 | 用途 | 預設 |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` | PostgreSQL 連線 | `127.0.0.1` / `5432` / `noveltts` |
| `DB_USER` / `DB_PASSWORD` | PostgreSQL 帳密 | `noveltts` / `noveltts_dev_pwd` |
| `NOVELTTS_MASTER_KEY` | **API Key 加密主金鑰（必填，base64 32 bytes）** | 無 |
| `NOVELTTS_STORAGE_ROOT` | 音檔根目錄 | `./storage` |
| `SERVER_PORT` | HTTP port | `8090`（8080 已被 nextcloud 容器占用） |
| `NOVELTTS_ADMIN_USERNAME` / `_PASSWORD` | 管理介面帳密 | `admin` / `admin` |

> ⚠ **主金鑰必須跨重啟固定**。主金鑰一變，已加密的 API Key 就無法解密（Tag mismatch）。
> 產生後請保存，例如：
> ```bash
> openssl rand -base64 32 > ~/.noveltts-master-key
> export NOVELTTS_MASTER_KEY=$(cat ~/.noveltts-master-key)
> ```

### 建置與執行

```bash
# 建置
mvn -pl app,web -am clean package

# 執行
java -jar web/target/web-*.jar
```

或開發模式：

```bash
mvn -pl web spring-boot:run
```

開啟 http://localhost:8080 ，以設定頁（`/settings`）填入 OpenRouter API Key 與模型。

### 介面截圖

| 頁面 | 截圖 |
|---|---|
| 儀表板 | `docs/screenshots/dashboard.png` |
| 小說列表 | `docs/screenshots/novels.png` |
| 小說詳情 | `docs/screenshots/novel-detail.png` |
| 段落與音檔 | `docs/screenshots/segments.png` |
| 設定 | `docs/screenshots/settings.png` |

### 音檔輸出

預設落在 `./storage/novel/{novelId}/`，可由 `NOVELTTS_STORAGE_ROOT` 覆寫。
輸出三種：`SEGMENT`（每組段落音檔）、`CHAPTER`（章節合併）、`BOOK`（整本合併），
以及整本 ZIP 匯出（`/api/novels/{id}/export`）。

### 測試

```bash
mvn test    # 33 個單元測試
```

### 端到端驗證（不需真實 API Key）

`tools/mock_openrouter.py` 是一個假的 OpenRouter 伺服器，會依 spec/02 的契約
回傳分析 JSON 與真實 PCM 音訊，用來驗證完整鏈路：

```bash
# 1) 啟動 mock（與 app 同一個 podman 網路）
podman run -d --name nt-mock --network webenv_default \
  -v $PWD/tools/mock_openrouter.py:/mock.py:ro \
  docker.io/library/python:3.13-alpine python /mock.py --port 18080

# 2) 把 provider 的 base_url 指向 mock
podman exec noveltts-pg psql -U noveltts -d noveltts \
  -c "update ai_provider_config set base_url='http://nt-mock:18080/api/v1';"

# 3) 走完整流程：小說 → 分析 → 指派音色 → 合成 → 章節合併 → 整本合併 → ZIP
```

驗證後記得把 base_url 改回 `https://openrouter.ai/api/v1`，
或直接在設定頁 `/settings` 填入真實 API Key。

涵蓋範圍：

| 測試類 | 驗證內容 |
|---|---|
| `ChapterSplitterTest` | 中/英文章節標題切分、無標題時依字數切分 |
| `SegmentParserTest` | AI 回應解析、markdown 圍欄、雜訊擷取、各種非法輸入的錯誤處理 |
| `TtsGroupBuilderTest` | 依「說話者」切 turn、字數上限分組、旁白音色、語氣合併 |
| `CryptoServiceTest` | API Key 加密解密、密文隨機性、換金鑰無法解密、遮蔽格式 |
| `AudioToolTest` | PCM→MP3 轉檔、時長量測、MP3 合併（需 ffmpeg） |
| `TtsRequestTest` | 送給 OpenRouter 的 TTS 請求 JSON 格式 |
| `ExportServiceTest` | ZIP 匯出的目錄結構與缺檔處理 |

### 已驗證的端到端結果

以 mock OpenRouter 實測完整鏈路：

```
小說 2 章 → 分析出 10 段落（旁白 4 / 對話 6）→ 自動建立 2 個角色
→ 指派音色（林晚→Kore、陳硯→Charon）→ 合成 10/10 段落 DONE
→ 每章 1 個音檔（multi-speaker 一次請求含多個 turn）
→ 章節合併檔 8s → 整本合併檔 16.064s → ZIP 5 檔案
```

## 資料庫遷移

Flyway 遷移檔在 `db/migration/`，由 `app` 模組執行：

```
db/migration/V1__init_schema.sql
```

本機開發時 `spring.flyway.locations` 指向該目錄；打包時複製進 jar 的 `classpath:db/migration`。


## 對外服務（Cloudflare Tunnel）

已建立 named tunnel `noveltts`，對外網址：

```
https://<your-domain>
```

### 啟動 tunnel（systemd user service）

已建立 `~/.config/systemd/user/cloudflared-noveltts.service`，用 podman 跑新版
cloudflared（2026.11.0），開機自動啟動、崩潰自動重啟。

```bash
systemctl --user status  cloudflared-noveltts.service
systemctl --user restart cloudflared-noveltts.service
systemctl --user disable --now cloudflared-noveltts.service
```

`loginctl enable-linger eric` 已啟用，登出後服務仍持續運作。

#### 為什麼用容器而不是系統套件

系統的 cloudflared 是 2026.5.0，實測 QUIC 連線會被遠端 edge 週期性踢掉：

```
ERR failed to accept incoming stream requests
    error="failed to accept QUIC stream: Application error 0x0 (remote)" connIndex=2
```

每 5 分鐘左右發生一次，cloudflared 會自動重連所以服務不中斷，但連線會反覆重建。
新版 2026.11.0 觀察 15 分鐘零 ERR，且連到不同的 edge IP。

注意 rootless podman 的容器 uid 映射：容器內非 root（uid 65532）讀不到 host 上
700 權限的 `~/.cloudflared`。解法是 `--user 0:0`（rootless 會把容器 root
映射到 host 的 eric）。

```bash
podman run --rm --network host --user 0:0 --name cf-noveltts \
  -v $HOME/.cloudflared:$HOME/.cloudflared:ro \
  -e HOME=/home/eric \
  -e TUNNEL_ORIGIN_CERT=$HOME/.cloudflared/cert.pem \
  docker.io/cloudflare/cloudflared:2026.11.0 tunnel \
  --config $HOME/.cloudflared/config-noveltts.yml --no-autoupdate run noveltts
```

`--network host` 是必要的：容器內的 `localhost` 指的是容器自己而非 host，
用 bridge 網路時 origin 必須改用 `host.containers.internal`。

設定檔 `~/.cloudflared/config-noveltts.yml`：

```yaml
tunnel: <tunnel-uuid>
credentials-file: $HOME/.cloudflared/<tunnel-uuid>.json

ingress:
  - hostname: <your-domain>
    service: http://localhost:8090
  - service: http_status:404
```

### 反向代理的 HTTPS 處理

經 tunnel 存取時，cloudflared 會送 `X-Forwarded-Proto: https`，但容器預設不信任這些標頭，
會導致 redirect 退回 `http://`（HTTPS 降級）與 session cookie 缺少 secure 旗標。

處理方式（見 `SecurityConfig` 與 `application.yml`）：

1. **明確註冊 `ForwardedHeaderFilter`** 並設為 `Ordered.HIGHEST_PRECEDENCE`。
   用單純 `@Bean Filter` 不夠 —— Boot 會以 `LOWEST_PRECEDENCE` 註冊，
   排在 Spring Security filter chain（order = -100）之後，
   導致 login redirect 產生當下 request 尚未包裝 forwarded 標頭。
2. `server.forwarded-headers-strategy: none` —— 已自行註冊 filter，避免與容器原生處理重複。
3. **session cookie 設定在 `server.servlet.session.cookie.*`**。
   Spring Boot 4 已把路徑從 `spring.servlet.session.cookie.*` 改到 `server.servlet.session.cookie.*`，
   寫錯前綴會靜默失效（HttpOnly 會套用但 Secure / SameSite 不會）。
4. `server.servlet.session.cookie.secure` 預設 true；本機以 http 直連時可設
   `SERVER_SESSION_COOKIE_SECURE=false`。

### 注意

- tunnel 是 named tunnel，重新啟動後網址不變（quick tunnel 每次都會換）。
- 應用仍只綁 `127.0.0.1:8090`，不直接對外開放。


## 管理帳號

帳號改由資料庫管理（`app_user` 表），密碼以 **BCrypt 雜湊**儲存，不保留明文。

- 初始帳號：`admin` / `admin`（V4 migration 種子資料）
- `must_change_password = true`，因此**首次登入會被強制導向變更密碼頁**，
  變更前無法存取其他頁面
- 變更密碼頁：`/account/password`，也可從各頁右上角「帳號」進入
- 新密碼至少 8 個字元，且不可與目前密碼相同
- `NOVELTTS_ADMIN_USERNAME` / `NOVELTTS_ADMIN_PASSWORD` 環境變數已移除

### 實作要點

**循環依賴**：`SecurityConfig` → `MustChangePasswordFilter` → `AccountService` →
`PasswordEncoder` → `SecurityConfig`。解法是把 `PasswordEncoder` bean 移到獨立的
`PasswordEncoderConfig` 類別。

**Filter 順序**：`MustChangePasswordFilter` 用
`.addFilterAfter(..., UsernamePasswordAuthenticationFilter.class)` 掛在 Security filter
chain 之後，才能讀到 `SecurityContext` 的登入身分。

**不要用 shell 傳 BCrypt 雜湊**：雜湊含 `$`，`psql -c "..."` 會被 shell 展開導致寫壞
（log 顯示 `UnmappableCharacterException`，登入時回報
`Encoded password does not look like BCrypt`）。改用 stdin 傳入：

```bash
podman exec -i noveltts-pg psql -U noveltts -d noveltts <<'SQLEOF'
UPDATE app_user SET password_hash = '$2a$10$...', must_change_password = TRUE WHERE username = 'admin';
SQLEOF
```


## 成本追蹤

`usage_log` 記錄每次 AI 呼叫的用量與費用，兩種來源的語意不同：

| 類型 | `estimated_cost` 來源 | 準確度 |
|---|---|---|
| 分析（TEXT） | OpenRouter 回應的 `usage.cost` | 實際扣款金額 |
| 合成（AUDIO） | `model_pricing` 定價 × 字元數 | 估算值 |

`/audio/speech` 端點只回傳 raw audio、不帶費用欄位，因此語音合成的費用是估算的。

**BYOK 路由注意**：`usage.cost` 在 BYOK 路由下只是 OpenRouter 的轉接手續費，
真實推論費在 `usage.cost_details.upstream_inference_cost`。兩者相加才是實際總支出
（`ChatUsage.totalSpend()`），只記 `cost` 會把花費記成 0。

### 模型定價同步

```bash
curl -X POST -H "X-CSRF-TOKEN: $TOKEN" https://<your-domain>/api/settings/pricing/sync
# → {"count":507}
```

定價來自 OpenRouter models API，快取於 `model_pricing` 表。
語音模型只出現在 `?output_modalities=speech` 的查詢結果中，因此同步時會分別抓兩次。

```bash
curl https://<your-domain>/api/settings/cost
# {"totalCostUsd":0.42,"textCostUsd":0.03,"audioCostUsd":0.39,"recordsWithCost":34,"totalRecords":34}
```

## 失敗重試與備援模型

**分析**（`AnalysisService.callWithRetry`）：429/5xx 指數退避重試（1s → 2s），
最多 3 次；其他 4xx 直接失敗不重試。第二次嘗試起會把前次的錯誤訊息帶進提示。

**合成**（`TtsService.synthesizeWithFallback`）：先以主要模型重試 3 次，
仍失敗則改用備援模型再試一次。備援模型在設定頁「備援模型 ID」欄位設定
（`ai_model_setting.fallback_model_id`），留空表示不啟用。

語音合成失敗會讓整個段落失敗，時間與金錢成本都高，因此備援模型的效益明顯高於分析端。
