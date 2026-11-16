/* 設定頁 */
async function loadProviders() {
  const box = document.getElementById('providerList');
  box.innerHTML = '';
  const providers = await api('/api/settings/provider');
  if (!providers.length) { box.appendChild(el('div', { class: 'muted', text: '尚未設定 Provider' })); return; }
  const table = el('table', {},
    el('thead', {}, el('tr', {}, el('th', { text: '名稱' }), el('th', { text: 'Base URL' }),
      el('th', { text: 'API Key' }), el('th', { text: '啟用' }), el('th', { text: '預設' }), el('th', { text: '' }))),
    el('tbody', {}, ...providers.map(p => el('tr', {},
      el('td', { text: p.name }),
      el('td', { text: p.baseUrl }),
      el('td', { text: p.maskedApiKey || '(未設定)' }),
      el('td', { text: p.enabled ? '是' : '否' }),
      el('td', { text: p.isDefault ? '是' : '否' }),
      el('td', {}, el('button', { class: 'btn btn-sm', text: '載入',
        onclick: () => loadProvider(p) }))
    )))
  );
  box.appendChild(table);
}

function loadProvider(p) {
  document.getElementById('pName').value = p.name;
  document.getElementById('pBaseUrl').value = p.baseUrl;
  document.getElementById('pTimeout').value = p.timeoutSeconds;
  document.getElementById('pHeaders').value = JSON.stringify(p.extraHeaders || {}, null, 2);
  document.getElementById('pEnabled').checked = p.enabled;
  document.getElementById('pDefault').checked = p.isDefault;
  document.getElementById('pApiKey').value = '';
  document.getElementById('providerMsg').textContent = '已載入「' + p.name + '」，儲存後生效';
}

async function loadModels() {
  const purpose = document.getElementById('mPurpose').value;
  const box = document.getElementById('modelList');
  box.innerHTML = '';
  const models = await api('/api/settings/models?purpose=' + purpose);
  if (!models.length) { box.appendChild(el('div', { class: 'muted', text: '尚未設定此用途的模型' })); return; }
  box.appendChild(el('table', {},
    el('thead', {}, el('tr', {}, el('th', { text: '模型 ID' }), el('th', { text: '備援模型' }),
      el('th', { text: 'Temperature' }),
      el('th', { text: 'Max Tokens' }), el('th', { text: '啟用' }), el('th', { text: '預設' }), el('th', { text: '' }))),
    el('tbody', {}, ...models.map(m => el('tr', {},
      el('td', { text: m.modelId }),
      el('td', { text: m.fallbackModelId || '-' }),
      el('td', { text: m.temperature === null ? '-' : String(m.temperature) }),
      el('td', { text: m.maxTokens === null ? '-' : String(m.maxTokens) }),
      el('td', { text: m.enabled ? '是' : '否' }),
      el('td', { text: m.isDefault ? '是' : '否' }),
      el('td', {}, el('button', { class: 'btn btn-sm', text: '載入', onclick: () => loadModel(m) }))
    )))
  ));
}

function loadModel(m) {
  document.getElementById('mModelId').value = m.modelId;
  document.getElementById('mFallbackModelId').value = m.fallbackModelId || '';
  document.getElementById('mTemperature').value = m.temperature === null ? '' : m.temperature;
  document.getElementById('mMaxTokens').value = m.maxTokens === null ? '' : m.maxTokens;
  document.getElementById('mParams').value = JSON.stringify(m.params || {}, null, 2);
  document.getElementById('mEnabled').checked = m.enabled;
  document.getElementById('mDefault').checked = m.isDefault;
  document.getElementById('modelMsg').textContent = '已載入模型設定，儲存後生效';
}

async function loadVoices() {
  const box = document.getElementById('voiceList');
  box.innerHTML = '';
  const voices = await api('/api/settings/voices');
  if (!voices.length) { box.appendChild(el('div', { class: 'muted', text: '尚未建立音色' })); return; }
  box.appendChild(el('table', {},
    el('thead', {}, el('tr', {}, el('th', { text: '顯示名' }), el('th', { text: 'voice_id' }),
      el('th', { text: '模型' }), el('th', { text: '性別' }), el('th', { text: '風格' }), el('th', { text: '' }))),
    el('tbody', {}, ...voices.map(v => el('tr', {},
      el('td', { text: v.displayName }),
      el('td', { text: v.voiceId }),
      el('td', { class: 'muted', text: v.model }),
      el('td', { text: v.gender || '-' }),
      el('td', { text: v.styleDesc || '-' }),
      el('td', {}, el('button', { class: 'btn btn-sm btn-danger', text: '刪除',
        onclick: async () => { await api('/api/settings/voices/' + v.id, { method: 'DELETE' }); loadVoices(); } }))
    )))
  ));
}

async function loadGeneral() {
  const g = await api('/api/settings/general');
  document.getElementById('gGlobalInstructions').value = g.globalInstructions || '';
  document.getElementById('gNarratorVoice').value = g.narratorVoiceId || '';
  document.getElementById('gDefaultVoice').value = g.defaultVoiceId || '';
  document.getElementById('gChapterMaxChars').value = g.chapterMaxChars;
  document.getElementById('gTtsConcurrency').value = g.ttsConcurrency;
  document.getElementById('gTtsGroupMaxChars').value = g.ttsGroupMaxChars;
  document.getElementById('gAutoAssignVoice').checked = !!g.autoAssignVoice;
}

document.getElementById('saveProvider').addEventListener('click', async () => {
  const msg = document.getElementById('providerMsg');
  let headers = {};
  try { headers = JSON.parse(document.getElementById('pHeaders').value || '{}'); }
  catch (e) { msg.textContent = '額外 Header 不是合法 JSON'; return; }
  try {
    await api('/api/settings/provider', {
      method: 'POST',
      body: JSON.stringify({
        provider: 'openrouter',
        name: document.getElementById('pName').value,
        baseUrl: document.getElementById('pBaseUrl').value,
        apiKey: document.getElementById('pApiKey').value,
        extraHeaders: headers,
        timeoutSeconds: parseInt(document.getElementById('pTimeout').value, 10),
        enabled: document.getElementById('pEnabled').checked,
        isDefault: document.getElementById('pDefault').checked
      })
    });
    msg.textContent = '已儲存';
    loadProviders();
  } catch (e) { msg.textContent = '失敗: ' + e.message; }
});

document.getElementById('testProvider').addEventListener('click', async () => {
  const msg = document.getElementById('providerMsg');
  msg.textContent = '測試中…';
  const providers = await api('/api/settings/provider');
  if (!providers.length) { msg.textContent = '請先儲存 Provider'; return; }
  try {
    const result = await api('/api/settings/provider/' + providers[0].id + '/test', { method: 'POST' });
    msg.textContent = result.ok ? result.message : result.message;
  } catch (e) { msg.textContent = '失敗: ' + e.message; }
});

document.getElementById('saveModel').addEventListener('click', async () => {
  const msg = document.getElementById('modelMsg');
  let params = {};
  try { params = JSON.parse(document.getElementById('mParams').value || '{}'); }
  catch (e) { msg.textContent = '參數不是合法 JSON'; return; }
  const providers = await api('/api/settings/provider');
  if (!providers.length) { msg.textContent = '請先設定 Provider'; return; }
  const providerId = providers.find(p => p.isDefault) ? providers.find(p => p.isDefault).id : providers[0].id;
  try {
    await api('/api/settings/models', {
      method: 'POST',
      body: JSON.stringify({
        providerConfigId: providerId,
        purpose: document.getElementById('mPurpose').value,
        modelId: document.getElementById('mModelId').value,
        fallbackModelId: document.getElementById('mFallbackModelId').value.trim() || null,
        temperature: parseFloat(document.getElementById('mTemperature').value),
        maxTokens: parseInt(document.getElementById('mMaxTokens').value, 10),
        params: params,
        enabled: document.getElementById('mEnabled').checked,
        isDefault: document.getElementById('mDefault').checked
      })
    });
    msg.textContent = '已儲存';
    loadModels();
  } catch (e) { msg.textContent = '失敗: ' + e.message; }
});

document.getElementById('mPurpose').addEventListener('change', loadModels);

document.getElementById('saveVoice').addEventListener('click', async () => {
  const msg = document.getElementById('voiceMsg');
  try {
    await api('/api/settings/voices', {
      method: 'POST',
      body: JSON.stringify({
        provider: 'openrouter',
        model: document.getElementById('vModel').value,
        voiceId: document.getElementById('vVoiceId').value,
        displayName: document.getElementById('vDisplayName').value,
        gender: document.getElementById('vGender').value,
        language: document.getElementById('vLanguage').value,
        styleDesc: document.getElementById('vStyleDesc').value,
        sortOrder: parseInt(document.getElementById('vSortOrder').value, 10)
      })
    });
    msg.textContent = '已新增';
    loadVoices();
  } catch (e) { msg.textContent = '失敗: ' + e.message; }
});

document.getElementById('syncVoices').addEventListener('click', async () => {
  const msg = document.getElementById('voiceMsg');
  msg.textContent = '同步中…';
  try {
    const result = await api('/api/settings/voices/sync', {
      method: 'POST',
      body: JSON.stringify({ model: document.getElementById('vModel').value })
    });
    msg.textContent = '已同步 ' + result.count + ' 個音色';
    loadVoices();
  } catch (e) { msg.textContent = '失敗: ' + e.message; }
});

document.getElementById('saveGeneral').addEventListener('click', async () => {
  const msg = document.getElementById('generalMsg');
  try {
    await api('/api/settings/general', {
      method: 'PUT',
      body: JSON.stringify({
        globalInstructions: document.getElementById('gGlobalInstructions').value,
        narratorVoiceId: document.getElementById('gNarratorVoice').value,
        defaultVoiceId: document.getElementById('gDefaultVoice').value,
        autoAssignVoice: document.getElementById('gAutoAssignVoice').checked,
        chapterMaxChars: parseInt(document.getElementById('gChapterMaxChars').value, 10),
        ttsConcurrency: parseInt(document.getElementById('gTtsConcurrency').value, 10),
        ttsGroupMaxChars: parseInt(document.getElementById('gTtsGroupMaxChars').value, 10)
      })
    });
    msg.textContent = '已儲存';
  } catch (e) { msg.textContent = '失敗: ' + e.message; }
});

loadProviders();
loadModels();
loadVoices();
loadGeneral();
