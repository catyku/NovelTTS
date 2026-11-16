# 01 — PostgreSQL Schema 規格

資料庫：PostgreSQL 17.12
遷移檔：`db/migration/V1__init_schema.sql`（Flyway）
遷移原則：**所有 DDL 只寫在 Flyway 腳本裡**，JPA `ddl-auto=validate`（不做自動建表）。

## 0. 全域慣例

- 主鍵：`BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`（PG 10+ 專屬，不需 sequence 物件）
- 時間戳：`TIMESTAMPTZ NOT NULL DEFAULT now()`
- 長文字：`TEXT`（不設長度上限）
- 半結構化：`JSONB`（`params`、`extra_headers`、`style` 這類可變欄位）
- 陣列：`TEXT[]`（角色別名）
- 布林：`BOOLEAN NOT NULL DEFAULT ...`
- 金額/費用：`NUMERIC(12,6)`（不用 float，避免誤差）
- 排序 NULL 處理：PostgreSQL 原生支援 `NULLS LAST`，**不需要** Law 專案在 MariaDB 用的 `ISNULL(col) ASC` workaround
- 字串統一 `VARCHAR(n)`，中文長度以字元計

## 1. 實體關聯圖

```text
novel 1 ────< chapter 1 ────< dialogue_segment >──── 0..1 character
  │                │                    │
  │                │                    └── 0..1 audio_file
  │                └───────────── 0..1 audio_file (章節合併檔)
  ├──< character >──── 0..1 tts_voice
  └──< audio_file

ai_provider_config 1 ────< ai_model_setting
job_task (獨立，ref_id 泛指)
usage_log (獨立，job_id 關聯)
```

## 2. 完整 DDL

```sql
-- ============================================================
-- V1__init_schema.sql
-- novel-tts : 小說 AI 分析 + TTS 語音合成
-- ============================================================

-- ---------- 1. 小說本體 ----------
CREATE TABLE novel (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title           VARCHAR(255)   NOT NULL,
    author          VARCHAR(128),
    source_filename VARCHAR(512),
    source_text     TEXT,
    language        VARCHAR(16)    NOT NULL DEFAULT 'zh-TW',
    status          VARCHAR(24)    NOT NULL DEFAULT 'PENDING',
    total_chapters  INTEGER        NOT NULL DEFAULT 0,
    total_chars     INTEGER        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_novel_status CHECK (
        status IN ('PENDING','ANALYZING','READY','SYNTHESIZING','SYNTHESIZED','EXPORTED','FAILED')
    )
);
COMMENT ON TABLE  novel IS '小說本體';
COMMENT ON COLUMN novel.source_text IS '上傳的原始全文';
COMMENT ON COLUMN novel.status IS 'PENDING→ANALYZING→READY→SYNTHESIZING→SYNTHESIZED→EXPORTED';

-- ---------- 2. 章節 ----------
CREATE TABLE chapter (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    novel_id      BIGINT       NOT NULL,
    chapter_index INTEGER      NOT NULL,
    title         VARCHAR(255),
    content       TEXT         NOT NULL,
    char_count    INTEGER      NOT NULL DEFAULT 0,
    status        VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_chapter_novel FOREIGN KEY (novel_id) REFERENCES novel(id) ON DELETE CASCADE,
    CONSTRAINT ck_chapter_status CHECK (
        status IN ('PENDING','ANALYZING','ANALYZED','SYNTHESIZING','SYNTHESIZED','FAILED')
    ),
    CONSTRAINT uk_chapter_novel_index UNIQUE (novel_id, chapter_index)
);
CREATE INDEX idx_chapter_novel ON chapter(novel_id, chapter_index);

-- ---------- 3. 角色 / 人聲 ----------
CREATE TABLE character (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    novel_id         BIGINT       NOT NULL,
    name             VARCHAR(128) NOT NULL,
    aliases          TEXT[]       NOT NULL DEFAULT '{}',
    gender           VARCHAR(16),
    age_group        VARCHAR(32),
    personality      TEXT,
    description      TEXT,
    tts_voice_id     BIGINT,
    tts_instructions TEXT,
    color_hex        VARCHAR(7)   DEFAULT '#4C6EF5',
    segment_count    INTEGER      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_character_novel FOREIGN KEY (novel_id) REFERENCES novel(id) ON DELETE CASCADE,
    CONSTRAINT uk_character_novel_name UNIQUE (novel_id, name)
);
CREATE INDEX idx_character_novel ON character(novel_id);
-- FK tts_voice_id 在 tts_voice 建立後補上（見下方 ALTER）

-- ---------- 4. 可用音色庫 ----------
CREATE TABLE tts_voice (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider     VARCHAR(32)  NOT NULL DEFAULT 'openrouter',
    model        VARCHAR(128) NOT NULL,
    voice_id     VARCHAR(64)  NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    gender       VARCHAR(16),
    language     VARCHAR(16),
    style_desc   TEXT,
    sample_url   VARCHAR(512),
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order   INTEGER      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_tts_voice UNIQUE (provider, model, voice_id)
);
CREATE INDEX idx_tts_voice_enabled ON tts_voice(enabled, sort_order);

ALTER TABLE character
    ADD CONSTRAINT fk_character_voice FOREIGN KEY (tts_voice_id) REFERENCES tts_voice(id) ON DELETE SET NULL;

-- ---------- 5. 分析後的段落（核心事實表） ----------
CREATE TABLE dialogue_segment (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    chapter_id     BIGINT       NOT NULL,
    seq            INTEGER      NOT NULL,
    type           VARCHAR(16)  NOT NULL,
    character_id   BIGINT,
    speaker_name   VARCHAR(64),
    text           TEXT         NOT NULL,
    emotion        VARCHAR(32),
    style_hint     VARCHAR(64),
    instructions   TEXT,
    voice_id       VARCHAR(64),
    tts_status     VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    audio_id       BIGINT,
    char_count     INTEGER      NOT NULL DEFAULT 0,
    error_message  TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_segment_chapter FOREIGN KEY (chapter_id) REFERENCES chapter(id) ON DELETE CASCADE,
    CONSTRAINT fk_segment_character FOREIGN KEY (character_id) REFERENCES character(id) ON DELETE SET NULL,
    CONSTRAINT ck_segment_type CHECK (type IN ('DIALOGUE','NARRATION')),
    CONSTRAINT ck_segment_tts_status CHECK (tts_status IN ('PENDING','DONE','FAILED','SKIPPED')),
    CONSTRAINT uk_segment_chapter_seq UNIQUE (chapter_id, seq)
);
CREATE INDEX idx_segment_chapter ON dialogue_segment(chapter_id, seq);
CREATE INDEX idx_segment_character ON dialogue_segment(character_id);
CREATE INDEX idx_segment_tts_status ON dialogue_segment(tts_status);

-- ---------- 6. 產出音檔 ----------
CREATE TABLE audio_file (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    novel_id      BIGINT       NOT NULL,
    chapter_id    BIGINT,
    segment_id    BIGINT,
    kind          VARCHAR(16)  NOT NULL DEFAULT 'SEGMENT',
    file_name     VARCHAR(255) NOT NULL,
    file_path     VARCHAR(512) NOT NULL,
    format        VARCHAR(8)   NOT NULL DEFAULT 'mp3',
    duration_ms   INTEGER,
    byte_size     BIGINT,
    provider      VARCHAR(32),
    model         VARCHAR(128),
    voice_id      VARCHAR(64),
    generation_id VARCHAR(128),
    char_count    INTEGER,
    status        VARCHAR(16)  NOT NULL DEFAULT 'READY',
    error_message TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_audio_novel FOREIGN KEY (novel_id) REFERENCES novel(id) ON DELETE CASCADE,
    CONSTRAINT fk_audio_chapter FOREIGN KEY (chapter_id) REFERENCES chapter(id) ON DELETE CASCADE,
    CONSTRAINT fk_audio_segment FOREIGN KEY (segment_id) REFERENCES dialogue_segment(id) ON DELETE CASCADE,
    CONSTRAINT ck_audio_kind CHECK (kind IN ('SEGMENT','CHAPTER','BOOK')),
    CONSTRAINT ck_audio_status CHECK (status IN ('PENDING','READY','FAILED'))
);
CREATE INDEX idx_audio_novel ON audio_file(novel_id, kind);
CREATE INDEX idx_audio_chapter ON audio_file(chapter_id);
-- dialogue_segment.audio_id 反向 FK 在 audio_file 建好後補上

ALTER TABLE dialogue_segment
    ADD CONSTRAINT fk_segment_audio FOREIGN KEY (audio_id) REFERENCES audio_file(id) ON DELETE SET NULL;

-- ---------- 7. AI Provider 設定 ----------
CREATE TABLE ai_provider_config (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider         VARCHAR(32)  NOT NULL DEFAULT 'openrouter',
    name             VARCHAR(128) NOT NULL,
    base_url         VARCHAR(255) NOT NULL DEFAULT 'https://openrouter.ai/api/v1',
    api_key_encrypted TEXT,
    extra_headers    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    timeout_seconds  INTEGER      NOT NULL DEFAULT 120,
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    is_default       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_provider_name UNIQUE (provider, name)
);
CREATE INDEX idx_provider_default ON ai_provider_config(provider, enabled, is_default);

-- ---------- 8. 模型與用途綁定 ----------
CREATE TABLE ai_model_setting (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider_config_id BIGINT        NOT NULL,
    purpose            VARCHAR(16)   NOT NULL,
    model_id           VARCHAR(128)  NOT NULL,
    temperature        NUMERIC(3,2),
    max_tokens         INTEGER,
    params             JSONB         NOT NULL DEFAULT '{}'::jsonb,
    enabled            BOOLEAN       NOT NULL DEFAULT TRUE,
    is_default         BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_model_provider FOREIGN KEY (provider_config_id) REFERENCES ai_provider_config(id) ON DELETE CASCADE,
    CONSTRAINT ck_model_purpose CHECK (purpose IN ('ANALYSIS','TTS')),
    CONSTRAINT uk_model_provider_purpose UNIQUE (provider_config_id, purpose, model_id)
);
CREATE INDEX idx_model_purpose ON ai_model_setting(purpose, enabled, is_default);

-- ---------- 9. 非同步作業追蹤 ----------
CREATE TABLE job_task (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type          VARCHAR(24)  NOT NULL,
    ref_id        BIGINT,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    progress      INTEGER      NOT NULL DEFAULT 0,
    total         INTEGER      NOT NULL DEFAULT 0,
    message       TEXT,
    error_message TEXT,
    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_job_type CHECK (type IN ('ANALYZE','SYNTHESIZE','EXPORT','IMPORT')),
    CONSTRAINT ck_job_status CHECK (status IN ('PENDING','RUNNING','SUCCESS','FAILED','CANCELED'))
);
CREATE INDEX idx_job_status ON job_task(status, created_at DESC);

-- ---------- 10. 成本追蹤 ----------
CREATE TABLE usage_log (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider          VARCHAR(32),
    model             VARCHAR(128),
    kind              VARCHAR(16)  NOT NULL DEFAULT 'TEXT',
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    char_count        INTEGER,
    duration_ms       INTEGER,
    estimated_cost    NUMERIC(12,6),
    job_id            BIGINT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_usage_job FOREIGN KEY (job_id) REFERENCES job_task(id) ON DELETE SET NULL,
    CONSTRAINT ck_usage_kind CHECK (kind IN ('TEXT','AUDIO'))
);
CREATE INDEX idx_usage_created ON usage_log(created_at DESC);
CREATE INDEX idx_usage_job ON usage_log(job_id);
```

## 2.1 實作後追加的表（V4–V6）

> 以下三張表是實作階段追加的，原始規格未涵蓋。

### app_user（V4）— 管理介面帳號

```sql
CREATE TABLE app_user (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username             VARCHAR(64)  NOT NULL,
    password_hash        VARCHAR(120) NOT NULL,   -- BCrypt 雜湊，非明文
    display_name         VARCHAR(128),
    role                 VARCHAR(32)  NOT NULL DEFAULT 'ADMIN',
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN      NOT NULL DEFAULT FALSE,
    last_login_at        TIMESTAMPTZ,
    password_changed_at  TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_app_user_username UNIQUE (username),
    CONSTRAINT ck_app_user_role CHECK (role IN ('ADMIN','VIEWER'))
);
```

種子帳號 `admin`（密碼 `admin`），`must_change_password = TRUE` ——
首次登入會被強制導向變更密碼頁，變更前無法存取其他頁面。
密碼以 BCrypt 雜湊儲存，設定檔與環境變數中不再保留明文。

### model_pricing（V5）— 模型定價快取

```sql
CREATE TABLE model_pricing (
    model_id         VARCHAR(128) PRIMARY KEY,
    display_name     VARCHAR(255),
    prompt_price     NUMERIC(20,12) NOT NULL DEFAULT 0,   -- USD / token
    completion_price NUMERIC(20,12) NOT NULL DEFAULT 0,
    audio_price      NUMERIC(20,12),
    pricing_unit     VARCHAR(16)    NOT NULL DEFAULT 'TOKEN',
    supports_speech  BOOLEAN        NOT NULL DEFAULT FALSE,
    synced_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_model_pricing_unit CHECK (pricing_unit IN ('TOKEN','CHARACTER','SECOND'))
);
CREATE INDEX idx_model_pricing_speech ON model_pricing(supports_speech);
```

定價來源為 OpenRouter models API，同步後快取於此表，
供 TTS 成本估算使用（`/audio/speech` 端點不回報費用）。

### ai_model_setting.fallback_model_id（V6）— 備援模型

```sql
ALTER TABLE ai_model_setting ADD COLUMN fallback_model_id VARCHAR(128);
```

主模型呼叫失敗（429/5xx）時改用此模型重試。留空表示不啟用備援。

## 3. 欄位語意補充

### 3.1 `novel.status` 狀態機
```
PENDING ──分析──▶ ANALYZING ──▶ READY ──合成──▶ SYNTHESIZING ──▶ SYNTHESIZED ──匯出──▶ EXPORTED
   │                 │                                                │
   └─────────────────┴──────────────── 失敗 ─────────────────────────┴──▶ FAILED
```

### 3.2 `dialogue_segment.type`
- `DIALOGUE` — 角色台詞，`character_id` 必須有值
- `NARRATION` — 旁白／敘述，`character_id` 為 NULL，但可另指定旁白音色（由設定頁指定「旁白音色」）

### 3.3 `instructions` 組裝規則
送給 TTS 的 `instructions` = `character.tts_instructions`（角色層，如「沉穩的老年男聲」）＋
`segment.instructions`（段落層，如「語速偏快，帶著笑意」），以 `，` 串接。
段落層若為空則只送角色層。

### 3.4 `audio_file.kind`
- `SEGMENT` — 單一 TTS 請求（可能含多角色）產出的音檔，對應多個 segment（同一 group 的 segment 共用一個 segment_id 指向第一筆，其餘以 `audio_id` 反指）
- `CHAPTER` — 章節合併檔（多個 SEGMENT 串接）
- `BOOK` — 整本合併檔

### 3.5 `voice_id` 兩處用途不同
- `tts_voice.voice_id` — 音色庫中的識別碼（如 `Kore`、`Puck`、`Charon`），送 OpenRouter 的 `voice` 欄位
- `dialogue_segment.voice_id` — 實際送出時使用的音色（可能已被使用者覆寫，不一定等於角色的 `tts_voice_id`）

## 4. 索引與效能考量

| 查詢情境 | 走的索引 |
|---|---|
| 列出某小說所有章節 | `idx_chapter_novel (novel_id, chapter_index)` |
| 列出某章所有段落（依 seq） | `idx_segment_chapter (chapter_id, seq)` |
| 找未合成段落 | `idx_segment_tts_status` |
| 列出小說所有音檔 | `idx_audio_novel (novel_id, kind)` |
| 找出目前預設模型 | `idx_provider_default`、`idx_model_purpose` |
| 成本報表 | `idx_usage_created` |

章節正文可能很大（數十萬字），熱門查詢只碰 `id / chapter_index / title / status`，
`chapter.content` 與 `novel.source_text` 不進索引（避免 TOAST 膨脹）。

## 5. JSONB 欄位使用

- `ai_provider_config.extra_headers`：送 OpenRouter 的自訂 header，例如 `{"HTTP-Referer":"...","X-Title":"novel-tts"}`
- `ai_model_setting.params`：供應商自訂參數，例如 `{"top_p":0.9,"presence_penalty":0.1}`
- 需要查詢時加 GIN 索引（目前無查詢需求，暫不建）

## 6. 種子資料（V1 一併寫入或另開 V2）

`tts_voice` 建議先以 Seed 方式寫入 OpenRouter Gemini TTS 常用音色：

| voice_id | 顯示名 | 語言 | 風格建議 |
|---|---|---|---|
| `Kore` | Kore（穩健女性） | zh-TW | 沉穩、敘事感 |
| `Puck` | Puck（活力男性） | zh-TW | 輕快、年輕 |
| `Charon` | Charon（低沉男性） | zh-TW | 低沉、威嚴 |
| `Fenrir` | Fenrir（粗獷男性） | zh-TW | 豪爽 |
| `Aoede` | Aoede（清亮女性） | zh-TW | 清亮、少女 |
| `Leda` | Leda（優雅女性） | zh-TW | 優雅、成熟 |

> 實際可用音色以 `GET https://openrouter.ai/api/v1/models?output_modalities=speech` 的
> `supported_voices` 為準，種子資料可於實作時用 API 拉取後寫入。

## 7. 資料保留與清理

- 刪除小說 → CASCADE 清除 chapter / character / dialogue_segment / audio_file（DB 檔案另行清理）
- 音檔實體檔案置於 `${NOVELTTS_STORAGE_ROOT}/novel/{novelId}/...`，
  刪除小說時由 Service 層同步刪除磁碟檔（DB cascade 不會動檔案系統）
