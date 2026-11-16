# 03 — API 與頁面清單

Base URL：`http://localhost:8090`（8080 已被本機 nextcloud 容器占用）
認證：Spring Security Session-based（表單登入），CSRF 開啟。
所有 `/api/**` 與頁面皆需登入（除 `/login`）。

---

## 1. REST API

### 1.1 認證

| Method | Path | 說明 | Body | 回應 |
|---|---|---|---|---|
| GET | `/login` | 登入頁 | — | HTML |
| POST | `/do-login` | 表單登入（需 CSRF token） | `username`,`password` | 302 |
| POST | `/do-logout` | 登出 | — | 302 |
| GET | `/account/password` | 變更密碼頁 | — | HTML |
| POST | `/do-change-password` | 變更密碼 | `currentPassword`,`newPassword`,`confirmPassword` | HTML |

> 帳號改由資料庫管理（`app_user` 表，BCrypt 雜湊），不再使用設定檔中的明文密碼。
> 首次登入時 `must_change_password = true` 會強制導向 `/account/password`，
> 變更前無法存取其他頁面（`MustChangePasswordFilter` 攔截所有路徑）。
> 新密碼至少 8 個字元，且不可與目前密碼相同。

### 1.2 Provider / 模型 / 音色設定

| Method | Path | 說明 | Body / Query | 回應 |
|---|---|---|---|---|
| GET | `/api/settings/provider` | 取得目前 provider 設定（API Key 遮蔽） | — | `ProviderDto` |
| POST | `/api/settings/provider` | 新增/更新 provider | `ProviderForm` | `ProviderDto` |
| PUT | `/api/settings/provider/{id}` | 更新指定 provider | `ProviderForm` | `ProviderDto` |
| DELETE | `/api/settings/provider/{id}` | 刪除 provider | — | 204 |
| POST | `/api/settings/provider/{id}/test` | 測試連線（打 models API） | — | `{ok,message}` |
| GET | `/api/settings/models?purpose=ANALYSIS\|TTS` | 列出模型設定 | — | `[ModelSettingDto]` |
| POST | `/api/settings/models` | 新增/更新模型設定 | `ModelSettingForm` | `ModelSettingDto` |
| DELETE | `/api/settings/models/{id}` | 刪除模型設定 | — | 204 |
| GET | `/api/settings/voices` | 列出音色庫 | `?enabled=&model=` | `[VoiceDto]` |
| POST | `/api/settings/voices` | 新增音色 | `VoiceForm` | `VoiceDto` |
| PUT | `/api/settings/voices/{id}` | 更新音色 | `VoiceForm` | `VoiceDto` |
| DELETE | `/api/settings/voices/{id}` | 刪除音色 | — | 204 |
| POST | `/api/settings/voices/sync` | 從 OpenRouter 拉取音色清單 | `{model}` | `{synced: n}` |
| GET | `/api/settings/cost` | 成本統計（總費用、分類費用、覆蓋率） | — | `CostSummary` |
| POST | `/api/settings/pricing/sync` | 從 OpenRouter 同步模型定價 | — | `{count}` |
| GET | `/api/settings/general` | 取得一般設定（預設音色、全域語氣、章節字數上限…） | — | `GeneralSettingsDto` |
| PUT | `/api/settings/general` | 更新一般設定 | `GeneralSettingsDto` | `GeneralSettingsDto` |

**ProviderForm**
```json
{
  "id": null,
  "provider": "openrouter",
  "name": "OpenRouter 預設",
  "baseUrl": "https://openrouter.ai/api/v1",
  "apiKey": "sk-or-xxxx",
  "extraHeaders": { "HTTP-Referer": "http://localhost:8080", "X-Title": "novel-tts" },
  "timeoutSeconds": 120,
  "enabled": true,
  "isDefault": true
}
```
> `apiKey` 送出時可留空代表「不變更」；回應中一律遮蔽為 `sk-****abcd`。

### 1.3 小說與章節

| Method | Path | 說明 | Body / Query | 回應 |
|---|---|---|---|---|
| GET | `/api/novels` | 列表 | `?keyword=&status=` | `[NovelDto]` |
| GET | `/api/novels/{id}` | 詳情 | — | `NovelDto` |
| POST | `/api/novels` | 建立（貼上文字或上傳檔） | multipart 或 `{title,author,content}` | `NovelDto` |
| PUT | `/api/novels/{id}` | 更新標題/作者 | `NovelForm` | `NovelDto` |
| DELETE | `/api/novels/{id}` | 刪除（含磁碟音檔） | — | 204 |
| GET | `/api/novels/{id}/chapters` | 章節列表 | — | `[ChapterDto]` |
| POST | `/api/novels/{id}/chapters/split` | 重新切章 | `{maxChars:3000}` | `[ChapterDto]` |
| PUT | `/api/chapters/{id}` | 更新章節標題/內容 | `ChapterForm` | `ChapterDto` |
| DELETE | `/api/chapters/{id}` | 刪除章節 | — | 204 |
| POST | `/api/novels/{id}/analyze` | 觸發全本分析（非同步） | `?chapterIds=` | `JobDto` |
| GET | `/api/jobs/{id}` | 查詢 job 進度 | — | `JobDto` |
| GET | `/api/jobs?type=&status=` | job 列表 | — | `[JobDto]` |

**NovelDto**
```json
{
  "id": 1, "title": "我的小說", "author": "某某",
  "status": "READY", "totalChapters": 12, "totalChars": 84321,
  "createdAt": "2026-11-16T10:00:00+08:00"
}
```

**JobDto**
```json
{ "id": 5, "type": "ANALYZE", "refId": 1, "status": "RUNNING",
  "progress": 4, "total": 12, "message": "正在分析第 5 章", "errorMessage": null }
```

### 1.4 角色

| Method | Path | 說明 | Body | 回應 |
|---|---|---|---|---|
| GET | `/api/novels/{id}/characters` | 角色列表（含段落數、音色） | — | `[CharacterDto]` |
| GET | `/api/characters/{id}` | 角色詳情 | — | `CharacterDto` |
| PUT | `/api/characters/{id}` | 更新（暱稱/性格/音色/語氣指令/色碼） | `CharacterForm` | `CharacterDto` |
| POST | `/api/novels/{id}/characters/auto-voice` | 自動指派音色 | `?overwrite=false` | `{assigned:n}` |
| GET | `/api/characters/{id}/segments` | 該角色的所有段落 | — | `[SegmentDto]` |

**CharacterForm**
```json
{
  "name": "林小滿",
  "aliases": ["小滿", "丫頭"],
  "gender": "female",
  "ageGroup": "少女",
  "personality": "活潑直率",
  "description": "主角，勇於追夢的年輕女孩",
  "ttsVoiceId": 3,
  "ttsInstructions": "語氣明亮，帶著少女的爽朗",
  "colorHex": "#F76707"
}
```

### 1.5 段落

| Method | Path | 說明 | Body / Query | 回應 |
|---|---|---|---|---|
| GET | `/api/chapters/{id}/segments` | 章節段落列表 | — | `[SegmentDto]` |
| PUT | `/api/segments/{id}` | 更新文字/音色/語氣/角色 | `SegmentForm` | `SegmentDto` |
| DELETE | `/api/segments/{id}` | 刪除段落 | — | 204 |
| POST | `/api/segments/{id}/synthesize` | 單段重跑合成 | — | `SegmentDto` |
| POST | `/api/novels/{id}/synthesize` | 觸發全本合成（非同步） | `?onlyPending=true` | `JobDto` |

**SegmentDto**
```json
{
  "id": 120, "chapterId": 3, "seq": 5,
  "type": "DIALOGUE", "characterId": 2, "speakerName": "林小滿",
  "text": "你到底是誰？",
  "emotion": "驚", "styleHint": "急促",
  "instructions": "語速偏快，帶著警戒",
  "voiceId": "Charon", "ttsStatus": "DONE",
  "audioId": 88, "durationMs": 1450, "audioUrl": "/api/audio/88/file"
}
```

### 1.6 音檔

| Method | Path | 說明 | 回應 |
|---|---|---|---|
| GET | `/api/novels/{id}/audios` | 列出小說所有音檔 | `[AudioDto]` |
| GET | `/api/chapters/{id}/audios` | 章節音檔 | `[AudioDto]` |
| GET | `/api/audio/{id}/file` | 下載/串流音檔 | binary |
| DELETE | `/api/audio/{id}` | 刪除音檔（實體+紀錄） | 204 |
| POST | `/api/chapters/{id}/merge` | 合併章節音檔 | `AudioDto` |
| POST | `/api/novels/{id}/merge` | 合併整本音檔 | `AudioDto` |
| POST | `/api/novels/{id}/export` | 打包整本 ZIP | `JobDto` |
| GET | `/api/novels/{id}/export` | 下載整本 ZIP | binary |
| GET | `/api/novels/{id}/usage` | 成本統計 | `UsageDto` |

**AudioDto**
```json
{ "id": 88, "kind": "SEGMENT", "chapterId": 3, "segmentId": 120,
  "fileName": "ch03_seg005.mp3", "format": "mp3",
  "durationMs": 1450, "byteSize": 23210,
  "provider": "openrouter", "model": "google/gemini-3.8-flash-tts",
  "voiceId": "Charon", "generationId": "gen-xxx", "createdAt": "..." }
```

### 1.7 使用統計

**CostSummary**（`GET /api/settings/cost`）
```json
{
  "totalCostUsd": 0.42,
  "textCostUsd": 0.03,
  "audioCostUsd": 0.39,
  "recordsWithCost": 34,
  "totalRecords": 34
}
```

> 實作說明：原規格的 `UsageDto`（含 `totalCalls`、`byModel` 分組）尚未實作，
> 目前只提供總費用與計數。若需要分模型檢視，可查 `usage_log` 表。

費用語意區分：
- `textCostUsd`：OpenRouter 回報的**實際扣款**（`usage.cost` + BYOK 的上游推論費）
- `audioCostUsd`：**估算值**（模型定價 × 字元數），因 `/audio/speech` 不回報費用

---

## 2. Thymeleaf 頁面

| 路徑 | 頁面 | 主要內容 |
|---|---|---|
| `/login` | 登入 | 表單登入 |
| `/` | 儀表板 | 小說數量、待處理 job、最近合成狀態、成本概览 |
| `/novels` | 小說列表 | 搜尋、建立（貼上/上傳）、狀態、進入詳情 |
| `/novels/{id}` | 小說詳情 | 章節列表、切章按鈕、觸發分析/合成、下載 ZIP |
| `/novels/{id}/characters` | 角色音色 | 角色卡片、下拉選音色、語氣指令輸入、色碼、自動指派 |
| `/novels/{id}/segments?chapterId=` | 段落與音檔 | 章節切換、段落表格（文字/角色/音色/狀態）、單段重跑、播放、下載 |
| `/settings` | 設定 | Provider 表單、模型設定（分析/TTS，含備援模型）、音色庫管理、一般設定 |
| `/account/password` | 變更密碼 | 目前密碼、新密碼、確認新密碼 |

### 2.1 頁面交互要點
- 音色下拉選項由 `/api/settings/voices` 動態取得（**不可**在 HTML 寫死 `<option>`，
  與 primary-english 專案踩過的坑相同）
- 段落表格支援行內編輯文字與音色，變更後 `PUT /api/segments/{id}`
- 合成/分析進度以輪詢 `GET /api/jobs/{id}` 更新進度條（2 秒一次）
- 音檔直接以 `<audio controls src="/api/audio/{id}/file">` 播放

---

## 3. 設定鍵（application.yml）

```yaml
server:
  port: ${SERVER_PORT:8090}

spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/noveltts
    username: noveltts
    password: ${DB_PASSWORD:noveltts_dev_pwd}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate:
        default_schema: public
  flyway:
    enabled: true
    locations: classpath:db/migration

noveltts:
  # API Key 加密主金鑰（必填，base64 32 bytes）。
  # ⚠ 必須跨重啟固定，否則已加密的 API Key 無法解密（Tag mismatch）。
  master-key: ${NOVELTTS_MASTER_KEY:}
  # 音檔儲存根目錄
  storage-root: ${NOVELTTS_STORAGE_ROOT:./storage}
  # 章節切分最大字數
  chapter-max-chars: ${NOVELTTS_CHAPTER_MAX_CHARS:3000}
  # TTS 併發上限
  tts-concurrency: ${NOVELTTS_TTS_CONCURRENCY:3}
  # 單次 TTS group 字數上限
  tts-group-max-chars: ${NOVELTTS_TTS_GROUP_MAX_CHARS:3000}
```

環境變數：
| 變數 | 用途 |
|---|---|
| `DB_PASSWORD` | PostgreSQL 密碼 |
| `NOVELTTS_MASTER_KEY` | API Key 加密主金鑰（Base64 32 bytes） |
| `NOVELTTS_STORAGE_ROOT` | 音檔根目錄 |
| `NOVELTTS_TTS_CONCURRENCY` | TTS 併發數 |

---

## 4. 錯誤回應格式

```json
{ "error": "OpenRouter 呼叫失敗", "detail": "HTTP 429 rate limited", "traceId": "a1b2c3" }
```

| 狀態碼 | 情境 |
|---|---|
| 400 | 參數錯誤、未設定 provider 或模型 |
| 401 / 302 | 未登入 |
| 404 | 資源不存在 |
| 500 | 未預期錯誤（回傳 traceId 供查 log） |
| 502 | 上游 AI 服務失敗 |
