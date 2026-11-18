/* 角色音色（訪客模式：資料在瀏覽器 IndexedDB） */

const NOVEL_ID = (window.NovelTtsPipeline && window.NovelTtsPipeline.novelIdFromPath())
  || novelId();

/** 目前小說。 */
let NOVEL = null;

/** 重新載入小說。 */
async function loadNovel() {
  NOVEL = await window.NovelTtsDB.novels.get(NOVEL_ID);
  return NOVEL;
}

/** 渲染角色列表。 */
function renderCharacters() {
  const tbody = document.getElementById('charRows');
  if (!tbody) return;
  tbody.innerHTML = '';
  const characters = (NOVEL && NOVEL.characters) || [];
  const countEl = document.getElementById('charCount');
  if (countEl) countEl.textContent = characters.length + ' 個角色';
  const titleEl = document.getElementById('novelTitle');
  if (titleEl && NOVEL) {
    titleEl.textContent = NOVEL.title;
    titleEl.href = '/novels/' + NOVEL.id;
  }

  if (!characters.length) {
    tbody.appendChild(el('tr', {}, el('td', { colspan: '9', class: 'muted', text: '尚未分析出角色' })));
    return;
  }
  for (const c of characters) {
    const voiceSelect = el('select', { class: 'voice-select', 'data-character-id': c.id });
    voiceSelect.appendChild(el('option', { value: '', text: '（未指定）' }));
    window.NovelTtsPipeline.voiceOptions(c.ttsVoice || '', false).forEach((o) => voiceSelect.appendChild(o));

    tbody.appendChild(el('tr', {},
      el('td', {},
        el('span', { class: 'voice-chip', style: 'background:' + (c.colorHex || '#4C6EF5') }),
        el('strong', { text: c.name }),
        el('div', { class: 'muted', text: c.description || '' })
      ),
      el('td', { class: 'muted', text: (c.aliases || []).join(', ') || '-' }),
      el('td', { text: c.gender || '-' }),
      el('td', { text: c.ageGroup || '-' }),
      el('td', { text: c.personality || '-' }),
      el('td', {}, voiceSelect),
      el('td', {}, el('input', {
        type: 'text', class: 'instr-input', value: c.ttsInstructions || '',
        'data-character-id': c.id, placeholder: '例如：語氣明亮',
      })),
      el('td', { text: String(c.segmentCount || 0) }),
      el('td', {}, el('button', { class: 'btn btn-sm save-char', 'data-character-id': c.id, text: '儲存' }))
    ));
  }
}

/** 儲存單一角色的音色與語氣。 */
async function saveCharacter(id) {
  const select = document.querySelector('.voice-select[data-character-id="' + id + '"]');
  const instr = document.querySelector('.instr-input[data-character-id="' + id + '"]');
  if (!NOVEL || !select || !instr) return;
  const c = (NOVEL.characters || []).find((x) => x.id === id);
  if (!c) return;
  try {
    c.ttsVoice = select.value || '';
    c.ttsInstructions = instr.value;
    await window.NovelTtsDB.novels.save(NOVEL);
    instr.style.borderColor = '#2f9e44';
    setTimeout(() => { instr.style.borderColor = ''; }, 800);
  } catch (e) {
    alert('儲存失敗: ' + e.message);
  }
}

document.getElementById('charRows').addEventListener('click', (e) => {
  const btn = e.target.closest('.save-char');
  if (!btn) return;
  saveCharacter(btn.dataset.characterId);
});

/* 自動指派音色 */
document.getElementById('autoVoiceBtn').addEventListener('click', async () => {
  const msg = document.getElementById('autoMsg');
  if (!NOVEL) { msg.textContent = '找不到小說'; return; }
  msg.textContent = '指派中…';
  try {
    await window.NovelTtsPipeline.loadVoices();
    const count = await window.NovelTtsPipeline.autoAssignVoices(NOVEL, true);
    renderCharacters();
    msg.textContent = '已指派 ' + count + ' 個角色';
  } catch (e) {
    msg.textContent = '失敗: ' + e.message;
  }
});

(async () => {
  window.NovelTtsPipeline.showGuestModeNotice('autoMsg');
  await window.NovelTtsPipeline.loadVoices();
  await loadNovel();
  renderCharacters();
})();
