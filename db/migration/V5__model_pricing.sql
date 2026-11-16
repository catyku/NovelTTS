-- ============================================================
-- V5__model_pricing.sql
-- 模型定價快取（供 usage_log.estimated_cost 估算）
--
-- 定價來源：OpenRouter models API (GET /api/v1/models)
-- 單位：USD / token（小數形式，如 0.0000005 = 每百萬 token 0.5 美元）
--
-- synced_at 記錄最後一次從 OpenRouter 同步的時間。
-- ============================================================

CREATE TABLE model_pricing (
    model_id             VARCHAR(128) PRIMARY KEY,
    display_name         VARCHAR(255),
    -- 每 token 輸入價格（USD）
    prompt_price         NUMERIC(20,12) NOT NULL DEFAULT 0,
    -- 每 token 輸出價格（USD）
    completion_price     NUMERIC(20,12) NOT NULL DEFAULT 0,
    -- 每 token 音訊價格（USD），TTS 模型使用
    audio_price          NUMERIC(20,12),
    -- 計價單位：TOKEN（一般模型）或 CHARACTER（TTS 以字元計價）
    pricing_unit         VARCHAR(16)    NOT NULL DEFAULT 'TOKEN',
    -- 是否為語音輸出模型
    supports_speech      BOOLEAN        NOT NULL DEFAULT FALSE,
    synced_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_model_pricing_unit CHECK (
        pricing_unit IN ('TOKEN','CHARACTER','SECOND')
    )
);

COMMENT ON TABLE  model_pricing IS 'OpenRouter 模型定價快取，供成本估算使用';
COMMENT ON COLUMN model_pricing.prompt_price IS '每 token 輸入價格（USD）';
COMMENT ON COLUMN model_pricing.pricing_unit IS 'TOKEN / CHARACTER / SECOND';

CREATE INDEX idx_model_pricing_speech ON model_pricing(supports_speech);
