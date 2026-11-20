/* 設定頁（訪客模式）
 *
 * - 使用者自帶 OpenRouter API Key：存在瀏覽器 localStorage（NovelTtsDB.credentials），
 *   呼叫無狀態 AI 閘道時以標頭帶上，伺服器不保存。
 * - 自己的模型與全域參數：存在瀏覽器 IndexedDB（NovelTtsDB.settings）。
 * - 全站預設值：從 server 的公開唯讀 API（/api/settings/voices、/api/settings/models）
 *   讀取，作為建議值來源，使用者可一鍵套用。
 * - 資料匯出／清除：全部在瀏覽器端完成。
 */

/** 備援音色：讀不到 server 音色庫時使用，避免頁面壞掉。 */
const FALLBACK_VOICE_IDS = [
  'Kore', 'Puck', 'Charon', 'Fenrir', 'Aoede', 'Leda', 'Orus', 'Zephyr',
];

/** 載入共用音色庫；失敗時退回常見音色。 */
async function loadVoiceLibrary() {
  // 優先用使用者自己的音色庫（IndexedDB）
  try {
    if (window.NovelTtsDB && window.NovelTtsDB.voices) {
      const mine = await window.NovelTtsDB.voices.listEnabled();
      if (mine.length) {
        return mine.map(v => ({
          value: v.voiceId,
          label: (v.displayName || v.voiceId) + '（' + v.voiceId + '）',
          displayName: v.displayName || v.voiceId,
          voiceId: v.voiceId,
          model: v.model || '',
          gender: v.gender || '',
          styleDesc: v.styleDesc || '',
        }));
      }
    }
  } catch (e) {
    console.warn('無法取得我的音色庫，改用全站音色庫', e);
  }
  try {
    const list = await api('/api/settings/voices');
    if (Array.isArray(list) && list.length) {
      return list.map(v => ({
        value: v.voiceId,
        label: (v.displayName || v.voiceId) + '（' + v.voiceId + '）',
        displayName: v.displayName || v.voiceId,
        voiceId: v.voiceId,
        model: v.model || '',
        gender: v.gender || '',
        styleDesc: v.styleDesc || '',
      }));
    }
  } catch (e) {
    console.warn('無法取得全站音色庫，改用備援音色', e);
  }
  const settings = await window.NovelTtsDB.settings.get();
  const ids = new Set(FALLBACK_VOICE_IDS);
  if (settings.narratorVoice) ids.add(settings.narratorVoice);
  if (settings.defaultVoice) ids.add(settings.defaultVoice);
  return Array.from(ids).map(id => ({
    value: id, label: id, displayName: id, voiceId: id, model: '', gender: '', styleDesc: '',
  }));
}

/** 填入音色下拉選單。 */
function fillVoiceSelect(select, options, selectedValue) {
  select.innerHTML = '';
  select.appendChild(el('option', { value: '', text: '（未指定）' }));
  for (const o of options) {
    const attrs = { value: o.value, text: o.label };
    if (selectedValue && o.value === selectedValue) {
      attrs.selected = 'selected';
    }
    select.appendChild(el('option', attrs));
  }
  // 目前的值不在清單中（例如使用者自己輸入的音色），補一個選項避免被清掉
  if (selectedValue && !options.some(o => o.value === selectedValue)) {
    select.appendChild(el('option', {
      value: selectedValue, text: selectedValue, selected: 'selected'
    }));
  }
}

/* ---------- 你的 OpenRouter API Key ---------- */

/** 顯示目前的 Key 狀態（遮蔽）。 */
function renderKeyStatus() {
  const box = document.getElementById('providerList');
  const cred = window.NovelTtsDB.credentials;
  box.innerHTML = '';
  const table = el('table', {},
    el('thead', {}, el('tr', {},
      el('th', { text: '項目' }), el('th', { text: '內容' }))),
    el('tbody', {},
      el('tr', {},
        el('td', { text: 'API Key' }),
        el('td', { text: cred.has() ? cred.masked() : '(未設定)' })),
      el('tr', {},
        el('td', { text: 'Base URL' }),
        el('td', { text: cred.getBaseUrl() })),
      el('tr', {},
        el('td', { text: '存放位置' }),
        el('td', { text: '你的瀏覽器 localStorage（不會送到伺服器儲存）' }))
    )
  );
  box.appendChild(table);
}

async function saveKey() {
  const msg = document.getElementById('providerMsg');
  const key = document.getElementById('pApiKey').value.trim();
  const baseUrl = document.getElementById('pBaseUrl').value.trim();
  if (!key) {
    msg.textContent = '請貼上你的 OpenRouter API Key';
    return;
  }
  try {
    window.NovelTtsDB.credentials.set(key);
    if (baseUrl) window.NovelTtsDB.credentials.setBaseUrl(baseUrl);
    document.getElementById('pApiKey').value = '';
    msg.textContent = '已儲存你的 API Key';
    renderKeyStatus();
  } catch (e) {
    msg.textContent = '失敗: ' + e.message;
  }
}

async function removeKey() {
  const msg = document.getElementById('providerMsg');
  if (!confirm('確定移除你的 OpenRouter API Key？之後的分析與語音合成需要重新填寫。')) return;
  window.NovelTtsDB.credentials.set('');
  document.getElementById('pApiKey').value = '';
  msg.textContent = '已移除 API Key';
  renderKeyStatus();
}

async function testKey() {
  const msg = document.getElementById('providerMsg');
  const cred = window.NovelTtsDB.credentials;
  if (!cred.has()) {
    msg.textContent = '請先儲存你的 API Key';
    return;
  }
  msg.textContent = '測試中…';
  try {
    const result = await window.NovelTtsGateway.test();
    msg.textContent = result && result.ok ? ('連線正常：' + (result.message || '')) :
      ('連線失敗：' + (result && result.message ? result.message : '未知錯誤'));
  } catch (e) {
    msg.textContent = '測試失敗: ' + e.message;
  }
}

/* ---------- 全站預設值 ---------- */

let VOICE_OPTIONS = [];

/** 顯示全站共用音色庫。 */
function renderDefaultVoices() {
  const box = document.getElementById('voiceList');
  box.innerHTML = '';
  if (!VOICE_OPTIONS.length) {
    box.appendChild(el('div', { class: 'muted', text: '無法取得全站音色庫，將使用備援音色' }));
    return;
  }
  box.appendChild(el('table', {},
    el('thead', {}, el('tr', {},
      el('th', { text: '顯示名' }), el('th', { text: 'voice_id' }),
      el('th', { text: '模型' }), el('th', { text: '性別' }), el('th', { text: '風格' }),
      el('th', { text: '套用' }))),
    el('tbody', {}, ...VOICE_OPTIONS.map(v => el('tr', {},
      el('td', { text: v.displayName }),
      el('td', { text: v.voiceId }),
      el('td', { class: 'muted', text: v.model || '-' }),
      el('td', { text: v.gender || '-' }),
      el('td', { text: v.styleDesc || '-' }),
      el('td', {},
        el('button', {
          class: 'btn btn-sm', text: '設為旁白',
          onclick: () => applyVoice(v.voiceId, 'gNarratorVoice')
        }),
        ' ',
        el('button', {
          class: 'btn btn-sm', text: '設為預設',
          onclick: () => applyVoice(v.voiceId, 'gDefaultVoice')
        })
      )
    )))
  ));
}

/** 套用音色到指定的設定欄位（並存進瀏覽器端設定）。 */
async function applyVoice(voiceId, targetId) {
  const select = document.getElementById(targetId);
  select.value = voiceId;
  const msg = document.getElementById('generalMsg');
  const patch = targetId === 'gNarratorVoice'
    ? { narratorVoice: voiceId }
    : { defaultVoice: voiceId };
  try {
    await window.NovelTtsDB.settings.save(patch);
    msg.textContent = '已套用音色 ' + voiceId + '（已儲存）';
  } catch (e) {
    msg.textContent = '失敗: ' + e.message;
  }
}

/** 顯示全站預設模型（依用途）。 */
async function loadDefaultModels() {
  const purpose = document.getElementById('mPurpose').value;
  const box = document.getElementById('modelList');
  box.innerHTML = '';
  let models = [];
  try {
    models = await api('/api/settings/models?purpose=' + purpose);
  } catch (e) {
    console.warn('無法取得全站模型預設值', e);
    box.appendChild(el('div', { class: 'muted', text: '無法取得全站模型預設值，請自行填寫模型 ID' }));
    return;
  }
  if (!models || !models.length) {
    box.appendChild(el('div', { class: 'muted', text: '全站尚未設定此用途的預設模型' }));
    return;
  }
  const isAnalysis = purpose === 'ANALYSIS';
  box.appendChild(el('table', {},
    el('thead', {}, el('tr', {},
      el('th', { text: '模型 ID' }), el('th', { text: '備援模型' }),
      el('th', { text: 'Temperature' }), el('th', { text: 'Max Tokens' }),
      el('th', { text: '預設' }), el('th', { text: '套用' }))),
    el('tbody', {}, ...models.map(m => el('tr', {},
      el('td', { text: m.modelId }),
      el('td', { text: m.fallbackModelId || '-' }),
      el('td', { text: m.temperature === null || m.temperature === undefined ? '-' : String(m.temperature) }),
      el('td', { text: m.maxTokens === null || m.maxTokens === undefined ? '-' : String(m.maxTokens) }),
      el('td', { text: m.isDefault ? '是' : '否' }),
      el('td', {}, el('button', {
        class: 'btn btn-sm', text: '套用',
        onclick: () => applyModel(m, isAnalysis)
      }))
    )))
  ));
}

/** 套用全站預設模型到自己的模型設定（並存進瀏覽器端設定）。 */
async function applyModel(m, isAnalysis) {
  const msg = document.getElementById('modelMsg');
  try {
    const patch = isAnalysis
      ? { analysisModel: m.modelId, fallbackAnalysisModel: m.fallbackModelId || '' }
      : { ttsModel: m.modelId, fallbackTtsModel: m.fallbackModelId || '' };
    if (m.temperature !== null && m.temperature !== undefined) {
      patch.temperature = m.temperature;
    }
    if (m.maxTokens !== null && m.maxTokens !== undefined) {
      patch.maxTokens = m.maxTokens;
    }
    await window.NovelTtsDB.settings.save(patch);
    if (isAnalysis) {
      document.getElementById('mModelId').value = m.modelId;
      document.getElementById('mFallbackModelId').value = m.fallbackModelId || '';
    } else {
      document.getElementById('mTtsModel').value = m.modelId;
      document.getElementById('mFallbackTtsModel').value = m.fallbackModelId || '';
    }
    msg.textContent = '已套用模型 ' + m.modelId + '（已儲存）';
  } catch (e) {
    msg.textContent = '失敗: ' + e.message;
  }
}

/* ---------- 你的模型與全域參數 ---------- */

/** 讀取瀏覽器端設定並填入表單。 */
async function loadUserSettings() {
  const s = await window.NovelTtsDB.settings.get();
  document.getElementById('mModelId').value = s.analysisModel || '';
  document.getElementById('mFallbackModelId').value = s.fallbackAnalysisModel || '';
  document.getElementById('mTtsModel').value = s.ttsModel || '';
  document.getElementById('mFallbackTtsModel').value = s.fallbackTtsModel || '';
  document.getElementById('mTemperature').value = s.temperature;
  document.getElementById('mMaxTokens').value = s.maxTokens;

  document.getElementById('gGlobalInstructions').value = s.globalInstructions || '';
  fillVoiceSelect(document.getElementById('gNarratorVoice'), VOICE_OPTIONS, s.narratorVoice);
  fillVoiceSelect(document.getElementById('gDefaultVoice'), VOICE_OPTIONS, s.defaultVoice);
  document.getElementById('gChapterMaxChars').value = s.chapterMaxChars;
  document.getElementById('gTtsGroupMaxChars').value = s.ttsGroupMaxChars;
}

/** 儲存你的模型設定。 */
async function saveModels() {
  const msg = document.getElementById('modelMsg');
  const patch = {
    analysisModel: document.getElementById('mModelId').value.trim(),
    fallbackAnalysisModel: document.getElementById('mFallbackModelId').value.trim(),
    ttsModel: document.getElementById('mTtsModel').value.trim(),
    fallbackTtsModel: document.getElementById('mFallbackTtsModel').value.trim(),
  };
  const temp = parseFloat(document.getElementById('mTemperature').value);
  if (!isNaN(temp)) patch.temperature = temp;
  const max = parseInt(document.getElementById('mMaxTokens').value, 10);
  if (!isNaN(max)) patch.maxTokens = max;
  try {
    await window.NovelTtsDB.settings.save(patch);
    msg.textContent = '已儲存你的模型設定';
  } catch (e) {
    msg.textContent = '失敗: ' + e.message;
  }
}

/** 儲存全域參數。 */
async function saveGeneral() {
  const msg = document.getElementById('generalMsg');
  const patch = {
    globalInstructions: document.getElementById('gGlobalInstructions').value,
    narratorVoice: document.getElementById('gNarratorVoice').value,
    defaultVoice: document.getElementById('gDefaultVoice').value,
  };
  const chapter = parseInt(document.getElementById('gChapterMaxChars').value, 10);
  if (!isNaN(chapter)) patch.chapterMaxChars = chapter;
  const group = parseInt(document.getElementById('gTtsGroupMaxChars').value, 10);
  if (!isNaN(group)) patch.ttsGroupMaxChars = group;
  try {
    await window.NovelTtsDB.settings.save(patch);
    msg.textContent = '已儲存';
  } catch (e) {
    msg.textContent = '失敗: ' + e.message;
  }
}

/* ---------- 資料管理 ---------- */

async function exportData() {
  const msg = document.getElementById('dataMsg');
  msg.textContent = '匯出中…';
  try {
    const blob = await window.NovelTtsDB.exportJson();
    const stamp = new Date().toISOString().slice(0, 10);
    downloadBlob(blob, 'novel-tts-data-' + stamp + '.json');
    msg.textContent = '已匯出 JSON（音檔不在匯出內容內）';
  } catch (e) {
    msg.textContent = '失敗: ' + e.message;
  }
}

async function clearData() {
  const msg = document.getElementById('dataMsg');
  if (!confirm('確定清除此瀏覽器的全部資料？\n\n包含：所有小說、章節、角色、段落、音檔、群組與設定。\n此動作無法復原。')) return;
  if (!confirm('再確認一次：真的要清除全部資料嗎？建議先「匯出我的資料」備份。')) return;
  try {
    await window.NovelTtsDB.clearAll();
    msg.textContent = '已清除全部資料';
    await loadUserSettings();
    renderKeyStatus();
  } catch (e) {
    msg.textContent = '失敗: ' + e.message;
  }
}

/* ---------- 事件綁定 ---------- */

document.getElementById('saveProvider').addEventListener('click', saveKey);
document.getElementById('removeKeyBtn').addEventListener('click', removeKey);
document.getElementById('testProvider').addEventListener('click', testKey);
document.getElementById('mPurpose').addEventListener('change', loadDefaultModels);
document.getElementById('saveModel').addEventListener('click', saveModels);
document.getElementById('saveGeneral').addEventListener('click', saveGeneral);
document.getElementById('exportData').addEventListener('click', exportData);
document.getElementById('clearData').addEventListener('click', clearData);

(async () => {
  VOICE_OPTIONS = await loadVoiceLibrary();
  renderDefaultVoices();
  renderKeyStatus();
  await loadUserSettings();
  await loadDefaultModels();
})();
