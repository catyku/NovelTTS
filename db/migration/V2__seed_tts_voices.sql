-- ============================================================
-- V2__seed_tts_voices.sql
-- 種子音色（OpenRouter Gemini TTS 常見 voice_id）
-- ============================================================

INSERT INTO tts_voice (provider, model, voice_id, display_name, gender, language, style_desc, enabled, sort_order) VALUES
    ('openrouter', 'google/gemini-3.8-flash-tts', 'Kore',   '穩健女性', 'female', 'zh-TW', '沉穩、有敘事感，適合旁白', TRUE, 10),
    ('openrouter', 'google/gemini-3.8-flash-tts', 'Aoede',  '清亮女性', 'female', 'zh-TW', '清亮、少女聲',         TRUE, 20),
    ('openrouter', 'google/gemini-3.8-flash-tts', 'Leda',   '優雅女性', 'female', 'zh-TW', '優雅、成熟',           TRUE, 30),
    ('openrouter', 'google/gemini-3.8-flash-tts', 'Puck',   '活力男性', 'male',   'zh-TW', '輕快、年輕',           TRUE, 40),
    ('openrouter', 'google/gemini-3.8-flash-tts', 'Charon', '低沉男性', 'male',   'zh-TW', '低沉、威嚴',           TRUE, 50),
    ('openrouter', 'google/gemini-3.8-flash-tts', 'Fenrir', '粗獷男性', 'male',   'zh-TW', '豪爽、爽朗',           TRUE, 60)
ON CONFLICT (provider, model, voice_id) DO NOTHING;
