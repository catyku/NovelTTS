/* 小說列表 */
async function loadNovels() {
  const tbody = document.getElementById('novelRows');
  tbody.innerHTML = '';
  const novels = await api('/api/novels');
  if (!novels.length) {
    tbody.appendChild(el('tr', {}, el('td', { colspan: '7', class: 'muted', text: '尚無小說' })));
    return;
  }
  for (const n of novels) {
    tbody.appendChild(el('tr', {},
      el('td', {}, el('a', { href: '/novels/' + n.id, text: n.title })),
      el('td', { text: n.author || '-' }),
      el('td', {}, el('span', { class: 'badge ' + n.status, text: n.status })),
      el('td', { text: String(n.totalChapters) }),
      el('td', { text: String(n.totalChars) }),
      el('td', { text: new Date(n.createdAt).toLocaleString('zh-TW') }),
      el('td', {},
        el('a', { class: 'btn btn-sm', href: '/novels/' + n.id + '/characters', text: '角色' }),
        ' ',
        el('a', { class: 'btn btn-sm', href: '/novels/' + n.id + '/segments', text: '段落' }),
        ' ',
        el('button', { class: 'btn btn-sm btn-danger', onclick: () => deleteNovel(n.id, n.title), text: '刪除' })
      )
    ));
  }
}

async function deleteNovel(id, title) {
  if (!confirm('確定刪除《' + title + '》？其章節、段落與音檔都會一併刪除。')) return;
  await api('/api/novels/' + id, { method: 'DELETE' });
  loadNovels();
}

document.getElementById('createBtn').addEventListener('click', async () => {
  const msg = document.getElementById('createMsg');
  const title = document.getElementById('title').value.trim();
  const author = document.getElementById('author').value.trim();
  const content = document.getElementById('content').value;
  if (!content.trim()) { msg.textContent = '請貼上小說內容'; return; }
  msg.textContent = '建立中…';
  try {
    const novel = await api('/api/novels', {
      method: 'POST',
      body: JSON.stringify({ title: title || '未命名小說', author: author, content: content })
    });
    msg.textContent = '已建立，共 ' + novel.totalChapters + ' 章';
    document.getElementById('content').value = '';
    document.getElementById('title').value = '';
    document.getElementById('author').value = '';
    loadNovels();
  } catch (e) {
    msg.textContent = '失敗: ' + e.message;
  }
});

loadNovels();
