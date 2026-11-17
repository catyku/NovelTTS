/* 小說群組：管理跨集共用的角色音色與旁白音色 */

let GROUPS = [];
let VOICES = [];
let currentGroupId = null;

/* 音色下拉選單。null 表示不指定（沿用全域設定）。 */
function voiceOptions(selectedId, includeNone) {
  const opts = [];
  if (includeNone) {
    opts.push(el('option', { value: '', text: '（未指定）' }));
  }
  for (const v of VOICES) {
    const attrs = { value: String(v.id), text: v.displayName + '（' + v.voiceId + '）' };
    if (selectedId != null && v.id === selectedId) {
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

async function loadVoices() {
  VOICES = await api('/api/settings/voices');
}

async function loadGroups() {
  const tbody = document.getElementById('groupRows');
  tbody.innerHTML = '';
  GROUPS = await api('/api/groups');
  if (!GROUPS.length) {
    tbody.appendChild(el('tr', {}, el('td', { colspan: '7', class: 'muted', text: '尚無群組' })));
    return;
  }
  for (const g of GROUPS) {
    tbody.appendChild(el('tr', {},
      el('td', {}, el('a', {
        href: '#', text: g.title,
        onclick: (e) => { e.preventDefault(); openGroup(g.id); }
      })),
      el('td', { text: g.author || '-' }),
      el('td', { text: g.narratorVoice || '（用全域設定）' }),
      el('td', { text: String(g.characterCount) }),
      el('td', { text: String(g.novelCount) }),
      el('td', {}, el('span', { class: 'badge', text: g.status })),
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
  const group = await api('/api/groups/' + id);
  document.getElementById('detailPanel').style.display = '';
  document.getElementById('detailTitle').textContent = '群組：' + group.title;

  fillSelect(document.getElementById('dNarratorVoice'),
    voiceOptions(group.narratorVoiceId, true));
  document.getElementById('dNarratorInstructions').value = group.narratorInstructions || '';

  fillSelect(document.getElementById('cVoice'), voiceOptions(null, true));
  await loadCharacters(id);
}

async function loadCharacters(groupId) {
  const tbody = document.getElementById('charRows');
  tbody.innerHTML = '';
  const chars = await api('/api/groups/' + groupId + '/characters');
  if (!chars.length) {
    tbody.appendChild(el('tr', {}, el('td', { colspan: '5', class: 'muted', text: '尚無共用角色' })));
    return;
  }
  for (const c of chars) {
    const aliasText = (c.aliases || []).join(', ');
    tbody.appendChild(el('tr', {},
      el('td', {}, el('span', { class: 'voice-chip', style: 'background:' + (c.colorHex || '#4C6EF5') }),
        el('span', { text: c.name })),
      el('td', { text: aliasText || '-' }),
      el('td', { text: c.voiceId || '（未指定）' }),
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

async function deleteGroup(id, title) {
  if (!confirm('確定刪除群組「' + title + '」？群組內的各集會保留，但不再共用角色設定。')) return;
  await api('/api/groups/' + id, { method: 'DELETE' });
  if (currentGroupId === id) {
    currentGroupId = null;
    document.getElementById('detailPanel').style.display = 'none';
  }
  loadGroups();
}

async function deleteCharacter(id, name) {
  if (!confirm('確定刪除共用角色「' + name + '」？')) return;
  await api('/api/groups/' + currentGroupId + '/characters/' + id, { method: 'DELETE' });
  loadCharacters(currentGroupId);
  loadGroups();
}

document.getElementById('createGroupBtn').addEventListener('click', async () => {
  const msg = document.getElementById('createMsg');
  const title = document.getElementById('gTitle').value.trim();
  if (!title) { msg.textContent = '請填寫群組名稱'; return; }
  msg.textContent = '建立中…';
  try {
    await api('/api/groups', {
      method: 'POST',
      body: JSON.stringify({
        title: title,
        author: document.getElementById('gAuthor').value.trim(),
        description: document.getElementById('gDesc').value
      })
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
  const voiceVal = document.getElementById('dNarratorVoice').value;
  msg.textContent = '儲存中…';
  try {
    await api('/api/groups/' + currentGroupId, {
      method: 'PUT',
      body: JSON.stringify({
        narratorVoiceId: voiceVal ? parseInt(voiceVal, 10) : null,
        narratorInstructions: document.getElementById('dNarratorInstructions').value
      })
    });
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
    await api('/api/groups/' + currentGroupId + '/characters', {
      method: 'POST',
      body: JSON.stringify({
        name: name,
        aliases: aliases,
        ttsVoiceId: voiceVal ? parseInt(voiceVal, 10) : null,
        ttsInstructions: document.getElementById('cInstructions').value
      })
    });
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
  await loadVoices();
  await loadGroups();
})();
