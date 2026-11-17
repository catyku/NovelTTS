-- V7：小說群組（連載分集共用角色與旁白音色）
--
-- 使用情境：同一本小說分好幾十集慢慢出版，每集視為一個 novel 匯入。
-- 群組層級維護「共用角色」（跨集固定音色與語氣）與「旁白音色」，
-- 各集分析時自動比對角色名並套用，確保跨集音色一致。
--
-- 新增 2 張表 + novel 加一個可為 NULL 的 FK（既有資料不受影響）。

CREATE TABLE novel_group (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title                VARCHAR(255)   NOT NULL,
    author               VARCHAR(128),
    description          TEXT,
    -- 群組層級的旁白音色：所有集的旁白都用同一個音色與語氣。
    narrator_voice_id    BIGINT,
    narrator_instructions TEXT,
    -- 單集的字數上限設定（覆寫全域預設，NULL 表示用全域值）。
    tts_group_max_chars  INTEGER,
    status               VARCHAR(24)    NOT NULL DEFAULT 'ACTIVE',
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_novel_group_status CHECK (status IN ('ACTIVE','COMPLETED','ARCHIVED'))
);
COMMENT ON TABLE novel_group IS '小說群組：同一本連載小說的多集共用設定';
COMMENT ON COLUMN novel_group.narrator_voice_id IS '群組共用旁白音色，確保跨集旁白一致';
COMMENT ON COLUMN novel_group.tts_group_max_chars IS '單集 TTS 分組字數上限，NULL 表示用全域預設';

CREATE TABLE group_character (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id             BIGINT       NOT NULL,
    name                 VARCHAR(128) NOT NULL,
    aliases              TEXT[]       NOT NULL DEFAULT '{}',
    gender               VARCHAR(16),
    age_group            VARCHAR(32),
    personality          TEXT,
    description          TEXT,
    tts_voice_id         BIGINT,
    tts_instructions     TEXT,
    color_hex            VARCHAR(7)   DEFAULT '#4C6EF5',
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_group_character_group FOREIGN KEY (group_id)
        REFERENCES novel_group(id) ON DELETE CASCADE,
    CONSTRAINT uk_group_character_name UNIQUE (group_id, name)
);
COMMENT ON TABLE group_character IS '群組共用角色：跨集固定音色與語氣';
COMMENT ON COLUMN group_character.aliases IS '別名，比對各集分析出的角色名時一併使用';

CREATE INDEX idx_group_character_group ON group_character(group_id);

-- novel 加入群組（可為 NULL：單本小說不需要群組）
ALTER TABLE novel ADD COLUMN group_id BIGINT;
ALTER TABLE novel ADD CONSTRAINT fk_novel_group FOREIGN KEY (group_id)
    REFERENCES novel_group(id) ON DELETE SET NULL;
COMMENT ON COLUMN novel.group_id IS '所屬群組，NULL 表示獨立小說';
CREATE INDEX idx_novel_group ON novel(group_id);

-- tts_voice FK（tts_voice 表在 V1 建立）
ALTER TABLE novel_group ADD CONSTRAINT fk_novel_group_narrator_voice
    FOREIGN KEY (narrator_voice_id) REFERENCES tts_voice(id) ON DELETE SET NULL;
ALTER TABLE group_character ADD CONSTRAINT fk_group_character_voice
    FOREIGN KEY (tts_voice_id) REFERENCES tts_voice(id) ON DELETE SET NULL;

-- 群組的集號：同一群組內的排序（第一集、第二集…）
ALTER TABLE novel ADD COLUMN group_order INTEGER;
COMMENT ON COLUMN novel.group_order IS '群組內的集數順序';
