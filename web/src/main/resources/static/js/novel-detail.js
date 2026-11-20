/* 小說詳情（訪客模式：資料在瀏覽器 IndexedDB，AI 走無狀態閘道） */

const NOVEL_ID = (window.NovelTtsPipeline && window.NovelTtsPipeline.novelIdFromPath())
  || novelId();

/** 目前小說（從 IndexedDB 載入）。 */
let NOVEL = null;

/** 更新進度列。 */
function setProgress(pct, label) {
  const box = document.getElementById('jobBox');
  if (!box) return;
  box.innerHTML = '';
  box.appendChild(progressBox(pct, label));
}

/** 顯示訊息。 */
function setMessage(message, ok) {
  const box = document.getElementById('jobBox');
  if (!box) return;
  box.innerHTML = '';
  box.appendChild(el('div', { class: 'alert ' + (ok ? 'alert-success' : 'alert-danger'), text: message }));
}

/** 渲染小說標頭資訊。 */
function renderHeader() {
  const stats = window.NovelTtsPipeline.novelStats(NOVEL);
  const title = document.getElementById('novelTitle');
  if (title) title.textContent = NOVEL.title;
  const author = document.getElementById('novelAuthor');
  if (author) author.textContent = NOVEL.author || '未指定作者';
  const status = document.getElementById('novelStatus');
  if (status) {
    status.textContent = NOVEL.status || 'NEW';
    status.className = 'badge ' + (NOVEL.status || 'NEW');
  }
  const summary = document.getElementById('novelStats');
  if (summary) summary.textContent = stats.chapters + ' 章 / ' + stats.chars + ' 字';
  const charLink = document.getElementById('charactersLink');
  if (charLink) charLink.href = '/novels/' + NOVEL.id + '/characters';
  const segLink = document.getElementById('segmentsLink');
  if (segLink) segLink.href = '/novels/' + NOVEL.id + '/segments';
}

/** 渲染章節列表。 */
function renderChapters() {
  const tbody = document.getElementById('chapterRows');
  if (!tbody) return;
  tbody.innerHTML = '';
  const chapters = NOVEL.chapters || [];
  if (!chapters.length) {
    tbody.appendChild(el('tr', {}, el('td', { colspan: '6', class: 'muted', text: '尚未切分章節' })));
    return;
  }
  for (const ch of chapters) {
    // el() 收的是變數引數，不是陣列 —— 傳陣列會被當成單一 child 而壞掉。
    const statusCell = ch.status === 'FAILED'
      ? el('td', {},
        el('span', { class: 'badge FAILED', text: 'FAILED' }),
        el('div', { class: 'muted', text: ch.error || '分析失敗' }))
      : el('td', {}, el('span', { class: 'badge ' + (ch.status || 'NEW'), text: ch.status || 'NEW' }));
    tbody.appendChild(el('tr', {},
      el('td', { text: String(ch.index) }),
      el('td', { text: ch.title || ('第 ' + ch.index + ' 章') }),
      el('td', { text: String(ch.charCount || (ch.content || '').length) }),
      el('td', { text: String((ch.segments || []).length) }),
      statusCell,
      el('td', {}, el('a', {
        class: 'btn btn-sm',
        href: '/novels/' + NOVEL.id + '/segments?chapterId=' + encodeURIComponent(ch.id),
        text: '查看段落'
      }))
    ));
  }

  // 分析進度提示（續傳）
  const p = NOVEL.analysisProgress;
  const failedCount = (p && p.failed && p.failed.length) || 0;
  if (p && (p.done < p.total || failedCount)) {
    tbody.appendChild(el('tr', {}, el('td', {
      colspan: '6', class: 'muted',
      text: '分析進度 ' + p.done + '/' + p.total + ' 章'
        + (failedCount ? '（' + failedCount + ' 章失敗）' : '')
        + ' — 按「繼續 AI 分析」只會重跑失敗的章節，不會重跑已完成的。',
    })));
  }
}

/** 渲染音檔列表。 */
async function renderAudios() {
  const tbody = document.getElementById('audioRows');
  if (!tbody) return;
  window.NovelTtsPipeline.revokeAudioUrls();
  tbody.innerHTML = '';
  const rows = await window.NovelTtsDB.audio.listOfNovel(NOVEL.id);
  const KIND_LABEL = { SEGMENT: '段落', CHAPTER: '章節', NOVEL: '整本' };
  if (!rows.length) {
    tbody.appendChild(el('tr', {}, el('td', { colspan: '6', class: 'muted', text: '尚無音檔' })));
    return;
  }
  for (const row of rows) {
    const audio = await window.NovelTtsPipeline.createAudioElement(NOVEL.id, row.kind, row.refId);
    tbody.appendChild(el('tr', {},
      el('td', { text: KIND_LABEL[row.kind] || row.kind }),
      el('td', { text: row.fileName || (row.kind + '-' + row.refId) }),
      el('td', { text: row.durationMs != null ? fmtDuration(row.durationMs) : '-' }),
      el('td', { text: fmtSize(row.size) }),
      el('td', { text: row.voice || '-' }),
      el('td', {}, audio ? audio : el('span', { text: '-' }))
    ));
  }
}

/** 重新載入小說並重繪。 */
async function reload() {
  NOVEL = await window.NovelTtsDB.novels.get(NOVEL_ID);
  if (!NOVEL) {
    setMessage('找不到這本小說（可能已被刪除，或資料不在這個瀏覽器裡）。', false);
    return;
  }
  renderHeader();
  renderChapters();
  await renderAudios();
  if (typeof updateAnalyzeButton === 'function') updateAnalyzeButton();
}

/* ---- 分析／合成 pipeline（實作在 pipeline.js，這裡提供頁面層的入口） ---- */

/**
 * 分析 pipeline：切章 → 逐章呼叫閘道分析 → 建立／更新角色 → 套用群組共用音色。
 *
 * @param {object} novel 小說物件（會就地更新並存回 IndexedDB）
 * @param {function} onProgress (pct, label)
 */
async function analyzeNovel(novel, onProgress, opts) {
  return window.NovelTtsPipeline.analyzeNovel(novel, onProgress, opts);
}

/**
 * 語音合成 pipeline：逐段呼叫閘道合成 → 音檔存進 IndexedDB → 合併本章。
 */
async function synthesizeChapter(novel, chapter, onlyPending, onProgress) {
  return window.NovelTtsPipeline.synthesizeChapter(novel, chapter, onlyPending, onProgress);
}

/* 觸發 AI 分析（可續傳：失敗後再按一次會從中斷處繼續） */
document.getElementById('analyzeBtn').addEventListener('click', async () => {
  if (!requireApiKey()) return;
  if (!NOVEL) return;
  const btn = document.getElementById('analyzeBtn');
  btn.disabled = true;
  try {
    setProgress(0, '準備分析…');
    // 按鈕顯示「重新分析」時強制重跑全部章節；否則是續傳
    const force = btn.textContent.indexOf('重新分析') >= 0;
    await analyzeNovel(NOVEL, (pct, label) => setProgress(pct, label), { force: force });
    await reload();
    setMessage('分析完成：' + (NOVEL.chapters || []).length + ' 章 / '
      + (NOVEL.chapters || []).reduce((n, c) => n + (c.segments || []).length, 0) + ' 段', true);
  } catch (e) {
    // 續傳提示：已完成的章節都在，按一次繼續就好
    const done = (NOVEL.chapters || []).filter((c) => c.status === 'ANALYZED').length;
    setMessage('分析失敗: ' + e.message
      + (done > 0 ? '（已完成 ' + done + ' 章，再按一次「觸發 AI 分析」會從中斷處繼續）' : ''), false);
    await reload();
  } finally {
    btn.disabled = false;
    updateAnalyzeButton();
  }
});

/**
 * 依分析進度更新按鈕文字。
 *
 * <p>有已完成的章節時顯示「繼續 AI 分析」，讓使用者知道再按一次是接續而非重跑。
 */
function updateAnalyzeButton() {
  const btn = document.getElementById('analyzeBtn');
  if (!btn || !NOVEL) return;
  const chapters = NOVEL.chapters || [];
  const done = chapters.filter((c) => c.status === 'ANALYZED').length;
  const failed = chapters.filter((c) => c.status === 'FAILED').length;
  const total = (NOVEL.analysisProgress && NOVEL.analysisProgress.total) || chapters.length;
  if (!done && !failed) {
    btn.textContent = '觸發 AI 分析';
  } else if (failed) {
    btn.textContent = '繼續 AI 分析（' + done + '/' + total + '）';
  } else {
    // 全部成功 —— 按鈕回到原本的樣子
    btn.textContent = '重新分析';
  }
}

/* 觸發語音合成（整本） —— 交給伺服器背景併行處理 */
document.getElementById('synthesizeBtn').addEventListener('click', async () => {
  if (!requireApiKey()) return;
  if (!NOVEL) return;
  const btn = document.getElementById('synthesizeBtn');
  btn.disabled = true;
  try {
    const chapters = NOVEL.chapters || [];
    if (!chapters.length) throw new Error('尚未分析，請先執行 AI 分析');
    await window.NovelTtsPipeline.synthesizeNovel(
      NOVEL, true, (pct, label) => setProgress(pct, label));
    await reload();
    const done = (NOVEL.chapters || []).reduce((n, c) => n + (c.segments || []).length, 0);
    setMessage('語音合成完成，共 ' + done + ' 段 / ' + chapters.length + ' 章', true);
  } catch (e) {
    setMessage('語音合成失敗: ' + e.message
      + '（已完成的段落都保留，再按一次「觸發語音合成」會從中斷處繼續）', false);
    await reload();
  } finally {
    btn.disabled = false;
  }
});

/* 合併整本 */
document.getElementById('mergeBtn').addEventListener('click', async () => {
  if (!NOVEL) return;
  const btn = document.getElementById('mergeBtn');
  btn.disabled = true;
  try {
    setProgress(50, '合併整本音檔…');
    const blob = await window.NovelTtsPipeline.mergeNovelAudio(NOVEL);
    await renderAudios();
    setProgress(100, '合併完成：' + (NOVEL.title || 'novel') + '.mp3（' + fmtSize(blob.size) + '）');
    downloadBlob(blob, (NOVEL.title || 'novel') + '.mp3');
  } catch (e) {
    setMessage('合併失敗: ' + e.message, false);
  } finally {
    btn.disabled = false;
  }
});

/* 下載 ZIP */
const zipBtn = document.getElementById('zipBtn');
if (zipBtn) {
  zipBtn.addEventListener('click', async () => {
    if (!NOVEL) return;
    zipBtn.disabled = true;
    try {
      setProgress(50, '打包整本音檔…');
      const blob = await window.NovelTtsPipeline.zipNovel(NOVEL);
      setProgress(100, '打包完成（' + fmtSize(blob.size) + '）');
      downloadBlob(blob, (NOVEL.title || 'novel') + '.zip');
    } catch (e) {
      setMessage('打包失敗: ' + e.message, false);
    } finally {
      zipBtn.disabled = false;
    }
  });
}

(async () => {
  window.NovelTtsPipeline.showGuestModeNotice('jobBox');
  await reload();
})();
