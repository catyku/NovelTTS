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
