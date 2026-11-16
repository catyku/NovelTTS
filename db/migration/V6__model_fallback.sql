-- ============================================================
-- V6__model_fallback.sql
-- 模型備援（fallback）
--
-- 當主要模型呼叫失敗（429/5xx 或模型不可用）時，改用備援模型重試。
-- fallback_model_id 留空表示不啟用備援。
--
-- 適用於 TTS：語音合成失敗會讓整個段落失敗，成本與時間代價高，
-- 有備援模型可大幅提升成功率。
-- ============================================================

ALTER TABLE ai_model_setting
    ADD COLUMN fallback_model_id VARCHAR(128);

COMMENT ON COLUMN ai_model_setting.fallback_model_id IS
    '備援模型 id；主要模型失敗時改用此模型重試。留空表示不啟用備援';
