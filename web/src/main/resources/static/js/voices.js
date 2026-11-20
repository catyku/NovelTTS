/**
 * 音色庫管理。
 *
 * 背景：
 *   共用音色庫原本是伺服器端管理員維護的固定 6 個音色，訪客只能讀不能改。
 *   但 OpenRouter 實際支援的音色遠多於此 —— Gemini TTS 就有 30 個，
 *   加上 elevenlabs / deepgram / microsoft / kokoro 等模型還有數百個。
 *
 * 做法：
 *   音色庫存使用者的瀏覽器（IndexedDB），可以從 OpenRouter 動態匯入、
 *   重新命名、加分類標籤、調整順序、啟用停用。
 *
 * 資料流：
 *   GET /api/gateway/models  →  每個模型的 supported_voices
 *         ↓ 匯入
 *   IndexedDB voices store    →  使用者篩選與編輯
 *         ↓
 *   角色指派 / 群組成員 / 全站預設值 的下拉選單
 */
(function () {
  'use strict';

  const DB = window.NovelTtsDB;
  const GW = window.NovelTtsGateway;

  /** 已匯入的模型清單快取，避免重複呼叫閘道。 */
  let MODELS_CACHE = null;

  /**
   * 模型的展示資訊。
   *
   * <p>OpenRouter 的 models 端點只給 id 與 name，其餘是推測值 ——
   * 沒有官方音色描述可用，所以性別等欄位留空讓使用者自行標註。
   */
  const MODEL_META = {
    'google/gemini-3.8-flash-tts': { label: 'Gemini 3.8 Flash TTS', family: 'gemini' },
    'google/gemini-3.8-flash-lite-tts': { label: 'Gemini 3.8 Flash Lite TTS', family: 'gemini' },
    'google/gemini-3.1-flash-tts-preview': { label: 'Gemini 3.1 Flash TTS', family: 'gemini' },
    'mistralai/voxtral-mini-tts-3-0': { label: 'Voxtral Mini TTS 3', family: 'mistral' },
    'mistralai/voxtral-mini-tts-2603': { label: 'Voxtral Mini TTS', family: 'mistral' },
    'elevenlabs/eleven-v3': { label: 'ElevenLabs v3', family: 'elevenlabs' },
    'elevenlabs/eleven-v4': { label: 'ElevenLabs v4', family: 'elevenlabs' },
    'elevenlabs/eleven-turbo-v2.5': { label: 'ElevenLabs Turbo v2.5', family: 'elevenlabs' },
    'elevenlabs/eleven-flash-v2.5': { label: 'ElevenLabs Flash v2.5', family: 'elevenlabs' },
    'microsoft/mai-voice-2.1': { label: 'MAI Voice 2.1', family: 'microsoft' },
    'microsoft/mai-voice-2.1-flash': { label: 'MAI Voice 2.1 Flash', family: 'microsoft' },
    'deepgram/aura-2': { label: 'Deepgram Aura 2', family: 'deepgram' },
    'deepgram/flux-tts': { label: 'Deepgram Flux', family: 'deepgram' },
    'hexgrad/kokoro-82m': { label: 'Kokoro 82M', family: 'kokoro' },
    'sesame/csm-1b': { label: 'Sesame CSM-1B', family: 'sesame' },
    'speechify/simba-3.5': { label: 'Speechify Simba 3.5', family: 'speechify' },
  };

  /** 家族的中文標籤。 */
  const FAMILY_LABELS = {
    gemini: 'Google Gemini',
    elevenlabs: 'ElevenLabs',
    mistral: 'Mistral',
    microsoft: 'Microsoft',
    deepgram: 'Deepgram',
    kokoro: 'Kokoro',
    sesame: 'Sesame',
    speechify: 'Speechify',
    other: '其他',
  };

  /**
   * 已知音色的中文暱稱。
   *
   * <p>OpenRouter 只回 voice_id（如 "Charon"），沒有中文描述。
   * 這份對照是依 Google 公開的音色風格整理，讓初次匯入時就有可讀的名稱；
   * 使用者可以在介面上改成自己喜歡的名稱。
   */
  const GEMINI_VOICE_NAMES = {
    Zephyr: '輕快女聲', Puck: '活潑男聲', Charon: '低沉男聲', Kore: '穩健女聲',
    Fenrir: '豪爽男聲', Leda: '優雅女聲', Orus: '沉著男聲', Aoede: '清亮女聲',
    Callirrhoe: '溫潤女聲', Autonoe: '明亮女聲', Enceladus: '磁性男聲',
    Iapetus: '厚重男聲', Umbriel: '柔和男聲', Algieba: '銀鈴女聲',
    Despina: '婉約女聲', Erinome: '知性女聲', Algenib: '空靈女聲',
    Rasalgethi: '高亢女聲', Laomedeia: '端莊女聲', Achernar: '清冷女聲',
    Alnilam: '沉鬱男聲', Schedar: '冷冽女聲', Gacrux: '幽微女聲',
    Pulcherrima: '清脆女聲', Achird: '友善男聲', Zubenelgenubi: '俏皮女聲',
    Vindemiatrix: '知性女聲', Sadachbia: '溫柔男聲', Sadaltager: '成熟男聲',
    Sulafat: '空靈女聲',
  };

  /** 各家族的預設分類。 */
  const DEFAULT_CATEGORY = {
    gemini: 'Gemini',
    elevenlabs: 'ElevenLabs',
    mistral: 'Mistral',
    microsoft: 'Microsoft',
    deepgram: 'Deepgram',
    kokoro: 'Kokoro',
    sesame: 'Sesame',
    speechify: 'Speechify',
    other: '其他',
  };

  /** 取得模型清單（帶快取）。 */
  async function fetchModels(force) {
    if (MODELS_CACHE && !force) return MODELS_CACHE;
    const raw = await GW.models();
    const list = Array.isArray(raw) ? raw : (raw.models || raw.data || []);
    MODELS_CACHE = list
      .filter((m) => m && m.id && Array.isArray(m.supported_voices) && m.supported_voices.length)
      .map((m) => ({
        id: m.id,
        name: m.name || m.id,
        voices: m.supported_voices,
        family: (MODEL_META[m.id] || {}).family || 'other',
        label: (MODEL_META[m.id] || {}).label || m.name || m.id,
      }));
    return MODELS_CACHE;
  }

  /** 產生預設顯示名。 */
  function defaultName(model, voiceId) {
    if (model.family === 'gemini' && GEMINI_VOICE_NAMES[voiceId]) {
      return GEMINI_VOICE_NAMES[voiceId];
    }
    return voiceId;
  }

  /**
   * 把某個模型的音色匯入本地音色庫。
   *
   * @param {string} modelId 模型 id
   * @param {object} opts
   * @param {boolean} opts.onlyNew 只匯入還沒有的（保留使用者的編輯）
   * @param {string} opts.category 覆寫分類
   * @returns {Promise<number>} 實際寫入的筆數
   */
  async function importModel(modelId, opts) {
    const options = opts || {};
    const models = await fetchModels();
    const model = models.find((m) => m.id === modelId);
    if (!model) throw new Error('找不到模型：' + modelId);

    const existing = await DB.voices.listByModel(modelId);
    const existingIds = new Set(existing.map((v) => v.voiceId));
    const maxOrder = existing.reduce((m, v) => Math.max(m, v.sortOrder || 0), 0);

    const rows = [];
    let order = maxOrder;
    for (const voiceId of model.voices) {
      if (options.onlyNew !== false && existingIds.has(voiceId)) continue;
      order += 10;
      rows.push({
        id: modelId + '::' + voiceId,
        model: modelId,
        modelLabel: model.label,
        voiceId: voiceId,
        displayName: defaultName(model, voiceId),
        category: options.category || DEFAULT_CATEGORY[model.family] || '其他',
        gender: '',
        styleDesc: '',
        enabled: true,
        sortOrder: order,
        source: 'openrouter',
        builtin: false,
      });
    }
    await DB.voices.saveMany(rows);
    return rows.length;
  }

  /** 匯入所有模型（沒有語音音色的模型會跳過）。 */
  async function importAll(opts) {
    const models = await fetchModels();
    let total = 0;
    for (const m of models) {
      total += await importModel(m.id, opts);
    }
    return { total: total, models: models.length };
  }

  /** 從伺服器的共用音色庫（種子）補進本地。 */
  async function seedFromServer() {
    try {
      const resp = await window.api('/api/settings/voices');
      if (!Array.isArray(resp) || !resp.length) return 0;
      const rows = resp.map((v) => ({
        id: (v.model || 'builtin') + '::' + v.voiceId,
        model: v.model || 'builtin',
        voiceId: v.voiceId,
        displayName: v.displayName || v.voiceId,
        category: v.styleDesc ? '精選' : '其他',
        gender: v.gender || '',
        styleDesc: v.styleDesc || '',
        enabled: true,
        sortOrder: v.sortOrder || 100,
        source: 'builtin',
        builtin: true,
      }));
      await DB.voices.saveMany(rows);
      return rows.length;
    } catch (e) {
      console.warn('無法取得伺服器種子音色', e);
      return 0;
    }
  }

  /** 取得下拉選單用的選項（只含啟用中的音色，依模型分組）。 */
  async function options() {
    const rows = await DB.voices.listEnabled();
    return rows.map((v) => ({
      value: v.voiceId,
      label: v.displayName + '（' + v.voiceId + '）',
      model: v.model,
      category: v.category,
    }));
  }

  /** 依模型分組（供管理介面顯示）。 */
  async function grouped() {
    const rows = await DB.voices.list();
    const groups = new Map();
    for (const v of rows) {
      const key = v.model || 'builtin';
      if (!groups.has(key)) {
        groups.set(key, { model: key, label: modelLabel(key), items: [] });
      }
      groups.get(key).items.push(v);
    }
    return Array.from(groups.values());
  }

  function modelLabel(modelId) {
    // pipeline.js 也有一份 MODEL_LABELS（角色頁不一定載入 voices.js），
    // 兩邊要一致，所以優先用 pipeline 的。
    if (window.NovelTtsPipeline && window.NovelTtsPipeline.modelLabel) {
      return window.NovelTtsPipeline.modelLabel(modelId);
    }
    return (MODEL_META[modelId] || {}).label || modelId;
  }

  window.NovelTtsVoices = {
    fetchModels: fetchModels,
    importModel: importModel,
    importAll: importAll,
    seedFromServer: seedFromServer,
    options: options,
    grouped: grouped,
    modelLabel: modelLabel,
    FAMILY_LABELS: FAMILY_LABELS,
    GEMINI_VOICE_NAMES: GEMINI_VOICE_NAMES,
    clearCache: function () { MODELS_CACHE = null; },
  };
})();
