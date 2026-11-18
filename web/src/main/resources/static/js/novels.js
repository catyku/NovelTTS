/* 小說列表（訪客模式：資料在瀏覽器 IndexedDB） */

/** 目前頁面的小說清單（由 IndexedDB 載入）。 */
let NOVELS = [];
/** 群組清單（供匯入時指定所屬群組）。 */
let GROUPS = [];

/** 群組下拉：供匯入時指定所屬群組 */
async function loadGroupOptions() {
  const select = document.getElementById('groupId');
  if (!select) return;
  GROUPS = await window.NovelTtsDB.groups.list();
  select.innerHTML = '';
  select.appendChild(el('option', { value: '', text: '（獨立小說）' }));
  for (const g of GROUPS) {
    select.appendChild(el('option', { value: String(g.id), text: g.title }));
  }
}

/** 取得群組標題。 */
function groupTitleOf(novel) {
  if (!novel.groupId) return null;
  const g = GROUPS.find((x) => String(x.id) === String(novel.groupId));
  return g ? g.title : null;
}

async function loadNovels() {
  const tbody = document.getElementById('novelRows');
  tbody.innerHTML = '';
  NOVELS = await window.NovelTtsDB.novels.list();
  if (!NOVELS.length) {
    tbody.appendChild(el('tr', {}, el('td', { colspan: '7', class: 'muted', text: '尚無小說' })));
    return;
  }
  for (const n of NOVELS) {
    const stats = window.NovelTtsPipeline.novelStats(n);
    const groupTitle = groupTitleOf(n);
    tbody.appendChild(el('tr', {},
      el('td', {}, el('a', { href: '/novels/' + n.id, text: n.title }),
        groupTitle ? el('div', { class: 'muted', text: groupTitle +
          (n.groupOrder ? '（第 ' + n.groupOrder + ' 集）' : '') }) : null),
      el('td', { text: n.author || '-' }),
      el('td', {}, el('span', { class: 'badge ' + (n.status || 'DRAFT'), text: n.status || 'DRAFT' })),
      el('td', { text: String(stats.chapters) }),
      el('td', { text: String(stats.chars) }),
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
  await window.NovelTtsDB.novels.remove(id);
  await loadNovels();
}

/* ---- 分析／合成 pipeline（實作在 pipeline.js，這裡提供頁面層的入口） ---- */

/**
 * 分析 pipeline：切章 → 逐章呼叫閘道分析 → 建立／更新角色 → 套用群組共用音色。
 *
 * @param {object} novel 小說物件（會就地更新並存回 IndexedDB）
 * @param {function} onProgress (pct, label)
 */
async function analyzeNovel(novel, onProgress) {
  return window.NovelTtsPipeline.analyzeNovel(novel, onProgress);
}

/**
 * 語音合成 pipeline：逐段呼叫閘道合成 → 音檔存進 IndexedDB → 合併本章。
 */
async function synthesizeChapter(novel, chapter, onlyPending, onProgress) {
  return window.NovelTtsPipeline.synthesizeChapter(novel, chapter, onlyPending, onProgress);
}

/** 建立小說：切章後存入 IndexedDB。 */
async function createNovel() {
  const msg = document.getElementById('createMsg');
  const title = document.getElementById('title').value.trim();
  const author = document.getElementById('author').value.trim();
  const content = document.getElementById('content').value;
  if (!content.trim()) { msg.textContent = '請貼上小說內容'; return; }
  const groupVal = document.getElementById('groupId').value;
  const orderVal = document.getElementById('groupOrder').value;

  msg.textContent = '建立中…';
  try {
    const settings = await window.NovelTtsDB.settings.get();
    const chapters = window.NovelTtsLogic.splitChapters(content, settings.chapterMaxChars || 3000)
      .map((c) => ({
        id: uid('ch'),
        index: c.index,
        title: c.title || ('第 ' + c.index + ' 章'),
        content: c.content,
        charCount: c.content.length,
        status: 'NEW',
        segments: [],
      }));

    let groupOrder = orderVal ? parseInt(orderVal, 10) : null;
    if (groupVal && groupOrder === null) {
      // 留空則自動接續該群組的集數順序
      const siblings = NOVELS.filter((n) => String(n.groupId) === String(groupVal));
      const maxOrder = siblings.reduce((m, n) => Math.max(m, n.groupOrder || 0), 0);
      groupOrder = maxOrder + 1;
    }

    const novel = {
      id: uid('novel'),
      title: title || '未命名小說',
      author: author,
      content: content,
      groupId: groupVal ? groupVal : null,
      groupOrder: groupOrder,
      chapters: chapters,
      characters: [],
      status: 'NEW',
    };
    await window.NovelTtsDB.novels.save(novel);
    msg.textContent = '已建立，共 ' + chapters.length + ' 章';
    document.getElementById('content').value = '';
    document.getElementById('title').value = '';
    document.getElementById('author').value = '';
    document.getElementById('groupOrder').value = '';
    await loadNovels();
  } catch (e) {
    msg.textContent = '失敗: ' + e.message;
  }
}

document.getElementById('createBtn').addEventListener('click', createNovel);

// 從檔案匯入內文
const fileBtn = document.getElementById('pickFileBtn');
if (fileBtn) {
  fileBtn.addEventListener('click', async () => {
    const picked = await pickTextFile('.txt,.md,text/plain');
    if (!picked) return;
    const area = document.getElementById('content');
    area.value = picked.text;
    const titleInput = document.getElementById('title');
    if (!titleInput.value.trim()) {
      titleInput.value = picked.name.replace(/\.[^.]+$/, '');
    }
    document.getElementById('createMsg').textContent = '已讀入 ' + picked.name;
  });
}

(async () => {
  window.NovelTtsPipeline.showGuestModeNotice('createMsg');
  await loadGroupOptions();
  await loadNovels();
})();
