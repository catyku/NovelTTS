/* 小說群組：管理跨集共用的角色音色與旁白音色
 *
 * 訪客模式：群組與共用角色全部存在瀏覽器的 IndexedDB（window.NovelTtsDB），
 * server 端不保存任何使用者資料。這裡只讀取 server 的「共用音色庫」作為
 * 音色下拉選單的建議值來源。
 */

let GROUPS = [];
let VOICES = [];          // [{ value: voiceId, label: 顯示文字 }]
let NOVELS = [];          // 本機小說（用來計算群組成員與集數）
let currentGroupId = null;

/** 備援音色：server 音色庫讀不到時使用，避免頁面壞掉。 */
const FALLBACK_VOICE_IDS = [
  'Kore', 'Puck', 'Charon', 'Fenrir', 'Aoede', 'Leda', 'Orus', 'Zephyr',
];

/* 音色下拉選單。value 為空字串表示不指定（沿用全域設定）。 */
function voiceOptions(selectedValue, includeNone) {
  const opts = [];
  if (includeNone) {
    opts.push(el('option', { value: '', text: '（未指定）' }));
  }
  for (const v of VOICES) {
    const attrs = { value: v.value, text: v.label };
    if (selectedValue && v.value === selectedValue) {
      attrs.selected = 'selected';
    }
    opts.push(el('option', attrs));
  }
  return opts;
}

function fillSelect(select, options) {
  select.innerHTML = '';
  options.forEach(o => select.appendChild(o));
}

/**
 * 載入音色庫。
 * 優先用 server 的共用音色庫（GET /api/settings/voices，公開唯讀），
 * 失敗或為空時退回常見音色 + 設定值，避免頁面壞掉。
 */
async function loadVoiceLibrary() {
  try {
    const list = await api('/api/settings/voices');
    if (Array.isArray(list) && list.length) {
      VOICES = list.map(v => ({
        value: v.voiceId,
        label: (v.displayName || v.voiceId) + '（' + v.voiceId + '）',
      }));
      return;
    }
  } catch (e) {
    console.warn('無法取得共用音色庫，改用備援音色', e);
  }
  const settings = await window.NovelTtsDB.settings.get();
  const ids = new Set(FALLBACK_VOICE_IDS);
  if (settings.narratorVoice) ids.add(settings.narratorVoice);
  if (settings.defaultVoice) ids.add(settings.defaultVoice);
  VOICES = Array.from(ids).map(id => ({ value: id, label: id }));
}

/** 群組成員小說（以 novel.groupId 關聯，容忍其他欄位名稱）。 */
function memberNovelsOf(groupId) {
  return NOVELS.filter(n => {
    if (!n) return false;
    if (n.groupId !== undefined && n.groupId !== null) {
      return String(n.groupId) === String(groupId);
    }
    if (n.group && n.group.id) {
      return String(n.group.id) === String(groupId);
    }
    return false;
  });
}

/** 群組狀態（badge 文字）。 */
function groupStatus(g) {
  if (g.status) return g.status;
  return g.narratorVoice ? '進行中' : '草稿';
}

async function loadGroups() {
  const tbody = document.getElementById('groupRows');
  tbody.innerHTML = '';
  GROUPS = await window.NovelTtsDB.groups.list();
  NOVELS = await window.NovelTtsDB.novels.list();
  if (!GROUPS.length) {
    tbody.appendChild(el('tr', {}, el('td', { colspan: '7', class: 'muted', text: '尚無群組' })));
    return;
  }
  for (const g of GROUPS) {
    const charCount = (g.characters || []).length;
    tbody.appendChild(el('tr', {},
      el('td', {}, el('a', {
        href: '#', text: g.title,
        onclick: (e) => { e.preventDefault(); openGroup(g.id); }
      })),
      el('td', { text: g.author || '-' }),
      el('td', { text: g.narratorVoice || '（用全域設定）' }),
      el('td', { text: String(charCount) }),
      el('td', { text: String(memberNovelsOf(g.id).length) }),
      el('td', {}, el('span', { class: 'badge', text: groupStatus(g) })),
      el('td', {},
        el('button', {
          class: 'btn btn-sm btn-danger', text: '刪除',
          onclick: () => deleteGroup(g.id, g.title)
        })
      )
    ));
  }
}

async function openGroup(id) {
  currentGroupId = id;
  const group = await window.NovelTtsDB.groups.get(id);
  if (!group) {
    showError(document.getElementById('saveMsg'), '找不到這個群組');
    return;
  }
  document.getElementById('detailPanel').style.display = '';
  document.getElementById('detailTitle').textContent = '群組：' + group.title;

  fillSelect(document.getElementById('dNarratorVoice'),
    voiceOptions(group.narratorVoice, true));
  document.getElementById('dNarratorInstructions').value = group.narratorInstructions || '';

  fillSelect(document.getElementById('cVoice'), voiceOptions(null, true));
  await loadCharacters(id);
  await loadMemberNovels(id);
}

/** 共用角色（存在群組物件的 characters 陣列中）。 */
async function loadCharacters(groupId) {
  const tbody = document.getElementById('charRows');
  tbody.innerHTML = '';
  const group = await window.NovelTtsDB.groups.get(groupId);
  const chars = (group && group.characters) || [];
  if (!chars.length) {
    tbody.appendChild(el('tr', {}, el('td', { colspan: '5', class: 'muted', text: '尚無共用角色' })));
    return;
  }
  for (const c of chars) {
    const aliasText = normalizeAliases(c.aliases).join(', ');
    tbody.appendChild(el('tr', {},
      el('td', {}, el('span', { class: 'voice-chip', style: 'background:' + (c.colorHex || '#4C6EF5') }),
        el('span', { text: c.name })),
      el('td', { text: aliasText || '-' }),
      el('td', { text: c.ttsVoice || '（未指定）' }),
      el('td', { text: c.ttsInstructions || '-' }),
      el('td', {},
        el('button', {
          class: 'btn btn-sm btn-danger', text: '刪除',
          onclick: () => deleteCharacter(c.id, c.name)
        })
      )
    ));
  }
}

/** 成員小說清單。 */
async function loadMemberNovels(groupId) {
  const tbody = document.getElementById('memberRows');
  if (!tbody) return;
  tbody.innerHTML = '';
  const list = memberNovelsOf(groupId);
  if (!list.length) {
    tbody.appendChild(el('tr', {}, el('td', { colspan: '3', class: 'muted', text: '尚無成員小說' })));
    return;
  }
  for (const n of list) {
    tbody.appendChild(el('tr', {},
      el('td', { text: n.title || '（未命名）' }),
      el('td', { text: n.author || '-' }),
      el('td', {}, el('span', {
        class: 'badge', text: n.groupOrder ? '第 ' + n.groupOrder + ' 集' : '未排序'
      }))
    ));
  }
}

/** 別名正規化：容許陣列或逗號分隔字串。 */
function normalizeAliases(aliases) {
  if (!aliases) return [];
  if (Array.isArray(aliases)) {
    return aliases.map(s => String(s).trim()).filter(s => s.length);
  }
  return String(aliases).split(',').map(s => s.trim()).filter(s => s.length);
}

async function deleteGroup(id, title) {
  if (!confirm('確定刪除群組「' + title + '」？群組內的各集會保留，但不再共用角色設定。')) return;
  await window.NovelTtsDB.groups.remove(id);
  if (currentGroupId === id) {
    currentGroupId = null;
    document.getElementById('detailPanel').style.display = 'none';
  }
  loadGroups();
}

async function deleteCharacter(id, name) {
  if (!confirm('確定刪除共用角色「' + name + '」？')) return;
  const group = await window.NovelTtsDB.groups.get(currentGroupId);
  if (!group) return;
  group.characters = (group.characters || []).filter(c => c.id !== id);
  await window.NovelTtsDB.groups.save(group);
  await loadCharacters(currentGroupId);
  loadGroups();
}

document.getElementById('createGroupBtn').addEventListener('click', async () => {
  const msg = document.getElementById('createMsg');
  const title = document.getElementById('gTitle').value.trim();
  if (!title) { msg.textContent = '請填寫群組名稱'; return; }
  msg.textContent = '建立中…';
  try {
    await window.NovelTtsDB.groups.save({
      id: uid('grp'),
      title: title,
      author: document.getElementById('gAuthor').value.trim(),
      description: document.getElementById('gDesc').value,
      narratorVoice: '',
      narratorInstructions: '',
      characters: [],
    });
    msg.textContent = '已建立';
    document.getElementById('gTitle').value = '';
    document.getElementById('gAuthor').value = '';
    document.getElementById('gDesc').value = '';
    loadGroups();
  } catch (e) {
    msg.textContent = '失敗: ' + e.message;
  }
});

document.getElementById('saveGroupBtn').addEventListener('click', async () => {
  const msg = document.getElementById('saveMsg');
  if (!currentGroupId) { msg.textContent = '請先選擇群組'; return; }
  const group = await window.NovelTtsDB.groups.get(currentGroupId);
  if (!group) { msg.textContent = '失敗: 找不到群組'; return; }
  const voiceVal = document.getElementById('dNarratorVoice').value;
  msg.textContent = '儲存中…';
  try {
    group.narratorVoice = voiceVal || '';
    group.narratorInstructions = document.getElementById('dNarratorInstructions').value;
    await window.NovelTtsDB.groups.save(group);
    msg.textContent = '已儲存';
    loadGroups();
  } catch (e) {
    msg.textContent = '失敗: ' + e.message;
  }
});

document.getElementById('addCharBtn').addEventListener('click', async () => {
  const msg = document.getElementById('charMsg');
  if (!currentGroupId) { msg.textContent = '請先選擇群組'; return; }
  const name = document.getElementById('cName').value.trim();
  if (!name) { msg.textContent = '請填寫角色名'; return; }
  const aliases = document.getElementById('cAliases').value
    .split(',').map(s => s.trim()).filter(s => s.length);
  const voiceVal = document.getElementById('cVoice').value;
  msg.textContent = '新增中…';
  try {
    const group = await window.NovelTtsDB.groups.get(currentGroupId);
    if (!group) { msg.textContent = '失敗: 找不到群組'; return; }
    group.characters = group.characters || [];
    group.characters.push({
      id: uid('chr'),
      name: name,
      aliases: aliases,
      ttsVoice: voiceVal || '',
      ttsInstructions: document.getElementById('cInstructions').value,
    });
    await window.NovelTtsDB.groups.save(group);
    msg.textContent = '已新增';
    document.getElementById('cName').value = '';
    document.getElementById('cAliases').value = '';
    document.getElementById('cInstructions').value = '';
    await loadCharacters(currentGroupId);
    loadGroups();
  } catch (e) {
    msg.textContent = '失敗: ' + e.message;
  }
});

(async () => {
  await loadVoiceLibrary();
  await loadGroups();
})();
