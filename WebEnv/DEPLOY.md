# 實際部署環境

本目錄提供兩套 compose：

| 檔案 | 用途 | 容器名 | Volume |
|---|---|---|---|
| `docker-compose.yaml` | 本機開發，快速重啟 | `noveltts-pg` / `noveltts-app` | `noveltts_pgdata` / `noveltts_storage` |
| `docker-compose.prod.yaml` | 正式對外服務 | `noveltts-pg-prod` / `noveltts-app-prod` | `noveltts_pgdata_prod` / `noveltts_storage_prod` |

兩者資料完全隔離，container name、volume、network 都不同，可以同時跑。

## 為什麼必須用 `-f` 指定

`podman-compose`（1.3.0）會掃描目錄下**所有**存在的 compose 檔並依序合併
（`COMPOSE_DEFAULT_LS` 順序：`compose.yaml` → `compose.yml` → `podman-compose.yaml`
→ `podman-compose.yml` → `docker-compose.yml` → `docker-compose.yaml` → …）。

合併的結果是 service 定義互相覆蓋，行為不可預測。因此部署時務必顯式指定：

```bash
cd WebEnv
podman-compose -f docker-compose.prod.yaml --env-file .env.prod up -d
```

`docker-compose.prod.yaml` 不在 podman-compose 的預設檔名清單裡，所以不會被自動載入。

---

## 部署步驟

### 1. 準備環境檔

```bash
cd WebEnv
cp .env.example.prod .env.prod
```

填入兩個必填值：

```bash
# 主金鑰（必須固定保存並備份）
openssl rand -base64 32
# → 貼到 .env.prod 的 NOVELTTS_MASTER_KEY

# DB 密碼（不要用預設值）
openssl rand -base64 24
# → 貼到 .env.prod 的 POSTGRES_PASSWORD
```

確認 `.env.prod` 不會被提交：

```bash
git check-ignore -v WebEnv/.env.prod   # 應有輸出
```

### 2. 建置應用映像

```bash
cd ..   # 專案根目錄
podman build -t noveltts-app:local -f Dockerfile .
```

### 3. 啟動

```bash
cd WebEnv
podman-compose -f docker-compose.prod.yaml --env-file .env.prod up -d
```

### 4. 確認健康

```bash
podman ps | grep prod
# 兩個都應該顯示 (healthy)
```

### 5. 首次設定（必要）

全新資料庫**不會**自動建立 AI Provider 與模型設定。開啟管理介面
`https://<你的網域>/settings` 設定：

1. **Provider**
   - `base_url`：`https://openrouter.ai/api/v1`
   - `api_key`：你的 OpenRouter API Key（以 AES-GCM 加密落庫）
2. **分析模型**：`purpose = ANALYSIS`，`model_id = google/gemini-3-flash-preview`
3. **TTS 模型**：`purpose = TTS`，`model_id = google/gemini-3.8-flash-tts`
4. 在設定頁選好旁白音色與全域語氣

### 6. 首次登入

種子帳號為 `admin` / `admin`。**首次登入會被強制導向變更密碼頁**，
且在改密完成前，其他頁面與 API 全部被擋下。

這是刻意設計 —— 公開部署若沿用預設密碼，等同沒有密碼。

---

## 部署版與開發版的差異

### 資源

| | 開發版 | 部署版 |
|---|---|---|
| master key / DB 密碼 | 有預設值 | **強制必填**，缺值直接拒絕啟動 |
| 根檔案系統 | 可寫 | `read_only: true`（只有 `/app/storage` 與 `/tmp` 可寫） |
| tmp | 主機 tmp | tmpfs 256MB（ffmpeg 合併音檔需要 temp file） |
| JVM heap | 預設 | `-Xmx1200m` |
| postgres 記憶體 | 預設 | `shared_buffers=256MB` |
| log | 10m × 5 | 20m × 10 |
| 提權防護 | — | `no-new-privileges:true` |

### 記憶體限制為何被註解掉

這台主機的 rootless Podman **不支援 memory limit**。cgroup v2 的 delegated
subtree 只委派了 `cpuset` / `cpu` / `io` / `pids`，沒有 `memory` controller：

```bash
$ cat /sys/fs/cgroup/cgroup.controllers
cpuset cpu io pids
```

設定 memory limit 會讓容器直接啟動失敗：

```
crun: opening file `memory.max` for writing: No such file or directory
```

所以記憶體上限改由 JVM `-Xmx` 與 postgres `shared_buffers` 控制。
在有完整 cgroup 委派的主機（Docker、或已設定 memory controller 的 rootless）
才建議取消 `deploy.resources.limits.memory` 的註解。

---

## 對外服務

應用與資料庫都只綁 `127.0.0.1`：

```
127.0.0.1:8090   ← app
127.0.0.1:5432   ← postgres
```

**對外入口一律走 Cloudflare Tunnel**，不要把 port 改成 `"8090:8090"` ——
那會讓服務繞過 TLS 與 Cloudflare 直接暴露。

Tunnel 由 systemd user service 管理（`~/.config/systemd/user/cloudflared-noveltts.service`），
已啟用 `loginctl enable-linger eric`，開機自動啟動、崩潰自動重啟。

---

## 維護

```bash
# 檢視日誌
podman logs -f noveltts-app-prod

# 重啟（會套用新的 .env.prod）
podman-compose -f docker-compose.prod.yaml --env-file .env.prod up -d

# 停止
podman-compose -f docker-compose.prod.yaml --env-file .env.prod down

# ⚠️ down -v 會刪除資料 volume
# podman-compose -f docker-compose.prod.yaml --env-file .env.prod down -v
```

### 備份

必須備份兩樣東西，缺一不可：

1. **資料庫 volume** `noveltts_pgdata_prod`
2. **主金鑰**（`.env.prod` 的 `NOVELTTS_MASTER_KEY`）

主金鑰遺失 = 已加密的 OpenRouter API Key 永久無法解密，必須重新輸入。
音檔 volume `noveltts_storage_prod` 可視需求備份（可從音檔重新合成）。

---

## 常見問題

### 容器起不來，log 顯示 `crun: opening file memory.max`

cgroup 未委派 memory controller。見上方「記憶體限制為何被註解掉」。

### 啟動失敗：`Circular placeholder reference 'SERVER_SESSION_COOKIE_SECURE'`

`application.yml` 已是 `secure: ${SERVER_SESSION_COOKIE_SECURE:true}`，
若 compose 再傳同名環境變數就會自我引用。**不要在 compose 傳這個變數**。
本機以 http 直連除錯時請直接改 `application.yml`。

### 登入後 API 全部 403

兩個可能：

1. 尚未完成強制改密 → 先到 `/do-change-password` 改完密碼
2. 用 http 直連但 session cookie 帶 `Secure` 旗標 → 正式部署走 https 沒有此問題；
   本機 http 測試需改 `application.yml` 的 `server.servlet.session.cookie.secure`

### 分析失敗：`尚未設定 AI Provider`

全新資料庫沒有 provider 與模型設定。見上方「首次設定」步驟 5。

### 分析失敗：`Connection refused`

Provider 的 `base_url` 指向的位址連不上。若指向本機 mock 容器，
注意 mock 必須與 app 在**同一個 podman network**（用容器名而非
`host.containers.internal`），否則 bridge 網路無法互通。
