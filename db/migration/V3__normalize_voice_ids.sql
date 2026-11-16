-- ============================================================
-- V3__normalize_voice_ids.sql
-- 修正 tts_voice 的 id：既有資料因 ON CONFLICT DO NOTHING 留下 id 空洞
-- （例如 Charon 拿到 id=5 而 Aoede 拿到 id=2，順序錯亂），
-- 使 id 不可預測，前端與測試難以硬編碼。
--
-- tts_voice.id 是 generated always as identity，不能直接 UPDATE 主鍵，
-- 因此改用「刪除後重新插入」的方式重新編號。
-- ============================================================

-- 1. 先解除外鍵參照（character.tts_voice_id），並記住原本的對應關係
CREATE TEMP TABLE voice_remap AS
SELECT c.id AS character_id, v.voice_id
FROM character c
JOIN tts_voice v ON v.id = c.tts_voice_id;

UPDATE character SET tts_voice_id = NULL;

-- 2. 依固定順序重新編號：刪除後以明確 id 重新插入
DELETE FROM tts_voice;

INSERT INTO tts_voice (id, provider, model, voice_id, display_name, gender, language, style_desc, enabled, sort_order)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 'openrouter', 'google/gemini-3.8-flash-tts', 'Kore',   '穩健女性', 'female', 'zh-TW', '沉穩、有敘事感，適合旁白', TRUE, 10),
    (2, 'openrouter', 'google/gemini-3.8-flash-tts', 'Aoede',  '清亮女性', 'female', 'zh-TW', '清亮、少女聲',         TRUE, 20),
    (3, 'openrouter', 'google/gemini-3.8-flash-tts', 'Leda',   '優雅女性', 'female', 'zh-TW', '優雅、成熟',           TRUE, 30),
    (4, 'openrouter', 'google/gemini-3.8-flash-tts', 'Puck',   '活力男性', 'male',   'zh-TW', '輕快、年輕',           TRUE, 40),
    (5, 'openrouter', 'google/gemini-3.8-flash-tts', 'Charon', '低沉男性', 'male',   'zh-TW', '低沉、威嚴',           TRUE, 50),
    (6, 'openrouter', 'google/gemini-3.8-flash-tts', 'Fenrir', '粗獷男性', 'male',   'zh-TW', '豪爽、爽朗',           TRUE, 60);

-- 3. 重設序列，避免後續 INSERT 主鍵衝突
SELECT setval(pg_get_serial_sequence('tts_voice', 'id'),
              (SELECT COALESCE(MAX(id), 0) FROM tts_voice));

-- 4. 依 voice_id 文字回填角色的音色關聯
UPDATE character c
SET tts_voice_id = v.id
FROM voice_remap r
JOIN tts_voice v ON v.voice_id = r.voice_id
WHERE c.id = r.character_id;
