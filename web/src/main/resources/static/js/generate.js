/**
 * AI 生成小說頁面。
 *
 * 流程：
 *   1. 使用者填想法、章節數、每章字數範圍
 *   2.（選用）產生大綱 —— 有大綱時每章都帶著它，維持全書一致性
 *   3. 逐章生成 —— 每章呼叫一次閘道，帶上一章結尾維持連貫
 *   4. 結果可下載 TXT，或匯入成小說直接進語音合成流程
 *   5. 每次生成都存進 IndexedDB 的 generations store，可查看歷史與刪除
 *
 * 資料全部留在瀏覽器：伺服器只做無狀態的 AI 運算。
 */
(function () {
  'use strict';

  const DB = window.NovelTtsDB;
  const GW = window.NovelTtsGateway;

  const el = {
    idea: document.getElementById('idea'),
    chapterCount: document.getElementById('chapterCount'),
    minChars: document.getElementById('minChars'),
    maxChars: document.getElementById('maxChars'),
    extra: document.getElementById('extra'),
    outlineBtn: document.getElementById('outlineBtn'),
    generateBtn: document.getElementById('generateBtn'),
    stopBtn: document.getElementById('stopBtn'),
    genMsg: document.getElementById('genMsg'),
    genProgress: document.getElementById('genProgress'),
    genBar: document.getElementById('genBar'),
    genProgressText: document.getElementById('genProgressText'),
    outlinePanel: document.getElementById('outlinePanel'),
    outlineText: document.getElementById('outlineText'),
    regenOutlineBtn: document.getElementById('regenOutlineBtn'),
    resultPanel: document.getElementById('resultPanel'),
    resultSummary: document.getElementById('resultSummary'),
    chapterRows: document.getElementById('chapterRows'),
    downloadTxtBtn: document.getElementById('downloadTxtBtn'),
    toNovelBtn: document.getElementById('toNovelBtn'),
    resultMsg: document.getElementById('resultMsg'),
    historyRows: document.getElementById('historyRows'),
  };

  /** 目前這一輪的狀態。 */
  let state = {
    outline: '',
    chapters: [],
    recordId: null,
    running: false,
    stopped: false,
  };

  // ---- 小工具 ----

  function fmtTime(ts) {
    if (!ts) return '-';
    const d = new Date(ts);
    const p = (n) => String(n).padStart(2, '0');
    return d.getFullYear() + '/' + p(d.getMonth() + 1) + '/' + p(d.getDate())
      + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
  }

  function setMsg(text, isError) {
    el.genMsg.textContent = text || '';
    el.genMsg.style.color = isError ? 'var(--danger, #c0392b)' : '';
  }

  function setResultMsg(text, isError) {
    el.resultMsg.textContent = text || '';
    el.resultMsg.style.color = isError ? 'var(--danger, #c0392b)' : '';
  }

  function showProgress(show) {
    el.genProgress.style.display = show ? '' : 'none';
    if (show) {
      el.genBar.style.width = '0%';
      el.genProgressText.textContent = '';
    }
  }

  function setProgress(done, total, label) {
    el.genBar.style.width = Math.round((done / total) * 100) + '%';
    el.genProgressText.textContent = label;
  }

  /** 讀取表單值並驗證。 */
  function readForm() {
    const idea = (el.idea.value || '').trim();
    const chapterCount = parseInt(el.chapterCount.value, 10) || 1;
    const minChars = parseInt(el.minChars.value, 10) || 1000;
    const maxChars = parseInt(el.maxChars.value, 10) || 2000;
    const extra = (el.extra.value || '').trim();

    if (!idea) throw new Error('請先填寫你的想法');
    if (chapterCount < 1) throw new Error('章節數至少為 1');
    if (chapterCount > 50) throw new Error('章節數上限為 50');
    if (minChars < 100) throw new Error('每章最少字數至少為 100');
    if (maxChars < minChars) throw new Error('每章最多字數不能小於最少字數');

    return { idea, chapterCount, minChars, maxChars, extra };
  }

  /** 檢查有沒有設定 API Key。 */
  function requireKey() {
    if (!DB.credentials.has()) {
      throw new Error('尚未設定 OpenRouter API Key，請先到「設定」頁填寫。');
    }
  }

  // ---- 大綱 ----

  async function generateOutline() {
    requireKey();
    const form = readForm();
    el.outlineBtn.disabled = true;
    setMsg('正在產生大綱…');
    try {
      const outline = await GW.generateOutline({
        idea: form.idea,
        chapterCount: form.chapterCount,
        minChars: form.minChars,
        maxChars: form.maxChars,
      });
      state.outline = outline;
      el.outlineText.textContent = outline;
      el.outlinePanel.style.display = '';
      setMsg('大綱已產生');
    } catch (e) {
      setMsg(e.message, true);
    } finally {
      el.outlineBtn.disabled = false;
    }
  }

  // ---- 逐章生成 ----

  /**
   * 從想法推一個作品標題。
   *
   * <p>取第一句（到逗號、句號、換行為止）再截短 —— 直接用整段想法當標題
   * 會讓歷史清單與下載檔名都很難讀。
   */
  function deriveTitle(idea) {
    const first = (idea || '').split(/[，。,.;；\n]/)[0].trim();
    const t = first || (idea || '').trim();
    return t.slice(0, 24) || '未命名';
  }

  /** 取上一章結尾作為銜接脈絡（最後 N 字）。 */
  function previousEnding(chapterText, size) {
    if (!chapterText) return '';
    const trimmed = chapterText.trim();
    if (trimmed.length <= size) return trimmed;
    return '……' + trimmed.slice(trimmed.length - size);
  }

  async function generateAll() {
    requireKey();
    let form;
    try {
      form = readForm();
    } catch (e) {
      setMsg(e.message, true);
      return;
    }

    state.running = true;
    state.stopped = false;
    state.chapters = [];
    state.outline = state.outline || '';

    el.generateBtn.disabled = true;
    el.outlineBtn.disabled = true;
    el.stopBtn.style.display = '';
    showProgress(true);

    const record = {
      id: DB.generations.newId(),
      title: deriveTitle(form.idea),
      idea: form.idea,
      chapterCount: form.chapterCount,
      minChars: form.minChars,
      maxChars: form.maxChars,
      extra: form.extra,
      outline: state.outline,
      chapters: [],
      status: 'RUNNING',
      createdAt: Date.now(),
    };
    state.recordId = record.id;
    state.recordTitle = record.title;

    let done = 0;
    try {
      for (let n = 1; n <= form.chapterCount; n++) {
        if (state.stopped) break;
        setProgress(done, form.chapterCount, `正在生成第 ${n} / ${form.chapterCount} 章…`);
        const prev = state.chapters.length
          ? state.chapters[state.chapters.length - 1]
          : null;
        const res = await GW.generateChapter({
          idea: form.idea,
          chapterNumber: n,
          chapterCount: form.chapterCount,
          minChars: form.minChars,
          maxChars: form.maxChars,
          outline: state.outline || null,
          previousEnding: previousEnding(prev ? prev.content : '', 400),
          extra: form.extra || null,
        });
        const content = (res.content || '').trim();
        // 章節標題：優先從大綱抓，抓不到就用「第 N 章」
        const title = extractTitle(state.outline, n) || `第 ${n} 章`;
        state.chapters.push({
          index: n,
          title: title,
          content: content,
          charCount: content.length,
        });
        done = n;

        // 每章完成就存檔，中途停掉也留得住已完成的內容
        record.chapters = state.chapters.slice();
        record.status = state.stopped ? 'STOPPED' : 'RUNNING';
        await DB.generations.save(record);
      }

      record.chapters = state.chapters.slice();
      record.status = state.stopped ? 'STOPPED'
        : (done === form.chapterCount ? 'DONE' : 'ERROR');
      record.finishedAt = Date.now();
      await DB.generations.save(record);

      if (state.stopped) {
        setMsg(`已停止（完成 ${done} / ${form.chapterCount} 章），已完成的內容已儲存`);
      } else {
        setMsg(`生成完成：${done} 章`);
      }
      renderResults();
    } catch (e) {
      record.chapters = state.chapters.slice();
      record.status = 'ERROR';
      record.error = e.message;
      await DB.generations.save(record);
      setMsg(e.message, true);
      renderResults();
    } finally {
      state.running = false;
      el.generateBtn.disabled = false;
      el.outlineBtn.disabled = false;
      el.stopBtn.style.display = 'none';
      showProgress(false);
      await renderHistory();
    }
  }

  /** 從大綱裡抓第 N 章的標題。 */
  function extractTitle(outline, n) {
    if (!outline) return '';
    const patterns = [
      new RegExp('^\\s*第\\s*' + n + '\\s*章[\\s:：、.．]*(.+)$', 'm'),
      new RegExp('^\\s*Chapter\\s*' + n + '\\b[\\s:：.\\-]*(.+)$', 'im'),
    ];
    for (const re of patterns) {
      const m = outline.match(re);
      if (m && m[1]) {
        return ('第 ' + n + '章 ' + m[1]).trim().slice(0, 60);
      }
    }
    return '';
  }

  // ---- 結果 ----

  function renderResults() {
    const chapters = state.chapters;
    if (!chapters.length) {
      el.resultPanel.style.display = 'none';
      return;
    }
    el.resultPanel.style.display = '';
    const totalChars = chapters.reduce((s, c) => s + c.charCount, 0);
    el.resultSummary.textContent =
      `${chapters.length} 章 · 共 ${totalChars.toLocaleString()} 字 · 平均每章 ${Math.round(totalChars / chapters.length).toLocaleString()} 字`;

    el.chapterRows.innerHTML = '';
    for (const c of chapters) {
      const tr = document.createElement('tr');
      const preview = c.content.length > 60 ? c.content.slice(0, 60) + '…' : c.content;
      tr.innerHTML = `<td>${c.index}</td><td></td><td>${c.charCount.toLocaleString()}</td><td class="muted"></td><td></td>`;
      tr.children[1].textContent = c.title;
      tr.children[3].textContent = preview;
      const td = tr.children[4];
      const btn = document.createElement('button');
      btn.className = 'btn btn-sm';
      btn.textContent = '複製';
      btn.addEventListener('click', () => {
        navigator.clipboard.writeText(c.content)
          .then(() => { btn.textContent = '已複製'; setTimeout(() => { btn.textContent = '複製'; }, 1500); })
          .catch(() => { btn.textContent = '複製失敗'; });
      });
      td.appendChild(btn);
      el.chapterRows.appendChild(tr);
    }
  }

  /** 把所有章節組成純文字。 */
  function buildTxt() {
    const lines = [];
    for (const c of state.chapters) {
      lines.push(c.title);
      lines.push('');
      lines.push(c.content);
      lines.push('');
      lines.push('');
    }
    return lines.join('\n');
  }

  function downloadTxt() {
    if (!state.chapters.length) {
      setResultMsg('還沒有可下載的內容', true);
      return;
    }
    const blob = new Blob([buildTxt()], { type: 'text/plain;charset=utf-8' });
    // 檔名用作品名稱（想法的前幾個字），不是第一章的標題 ——
    // 章節標題通常很長，而且會隨大綱改變，不適合當檔名。
    const title = state.recordTitle
      || (state.chapters[0] && state.chapters[0].title)
      || '小說';
    const name = title.replace(/[\\/:*?"<>|]/g, '_').slice(0, 40).trim();
    downloadBlob(blob, (name || '小說') + '.txt');
    setResultMsg('已下載 TXT');
  }

  /**
   * 匯入成小說 —— 直接進語音生成流程。
   *
   * <p>把小說存進 IndexedDB，章節結構用已生成的章節，
   * 狀態設為 IMPORTED（還沒分析），使用者到詳情頁按「觸發 AI 分析」即可。
   */
  async function toNovel() {
    if (!state.chapters.length) {
      setResultMsg('還沒有可匯入的內容', true);
      return;
    }
    const title = state.recordTitle
      || (state.chapters[0] && state.chapters[0].title)
      || 'AI 生成小說';
    const novel = {
      id: 'novel_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 8),
      title: title,
      author: 'AI 生成',
      content: state.chapters.map((c) => c.title + '\n\n' + c.content).join('\n\n'),
      status: 'IMPORTED',
      chapters: state.chapters.map((c) => ({
        id: 'ch_' + c.index + '_' + Math.random().toString(36).slice(2, 8),
        index: c.index,
        title: c.title,
        content: c.content,
        segments: [],
      })),
      characters: [],
      createdAt: Date.now(),
    };
    await DB.novels.save(novel);
    setResultMsg('已匯入，正在前往小說詳情頁…');
    window.location.href = '/novels/' + novel.id;
  }

  // ---- 歷史 ----

  async function renderHistory() {
    const rows = await DB.generations.list();
    el.historyRows.innerHTML = '';
    if (!rows.length) {
      const tr = document.createElement('tr');
      const td = document.createElement('td');
      td.colSpan = 6;
      td.className = 'muted';
      td.textContent = '尚無生成記錄';
      tr.appendChild(td);
      el.historyRows.appendChild(tr);
      return;
    }
    for (const r of rows) {
      const totalChars = (r.chapters || []).reduce((s, c) => s + (c.charCount || 0), 0);
      const tr = document.createElement('tr');
      tr.innerHTML = '<td></td><td class="muted"></td><td></td><td></td><td></td><td></td>';
      tr.children[0].textContent = r.title || '（無標題）';
      tr.children[1].textContent = (r.idea || '').slice(0, 40) + ((r.idea || '').length > 40 ? '…' : '');
      tr.children[2].textContent = `${(r.chapters || []).length} / ${r.chapterCount}`;
      tr.children[3].textContent = totalChars.toLocaleString();
      tr.children[4].textContent = fmtTime(r.createdAt);

      const td = tr.children[5];
      const viewBtn = document.createElement('button');
      viewBtn.className = 'btn btn-sm';
      viewBtn.textContent = '查看';
      viewBtn.addEventListener('click', () => loadRecord(r));

      const delBtn = document.createElement('button');
      delBtn.className = 'btn btn-sm';
      delBtn.textContent = '刪除';
      delBtn.style.marginLeft = '6px';
      delBtn.addEventListener('click', async () => {
        if (!window.confirm(`確定要刪除「${r.title || '（無標題）'}」的生成記錄嗎？`)) return;
        await DB.generations.remove(r.id);
        if (state.recordId === r.id) {
          state.chapters = [];
          state.outline = '';
          state.recordId = null;
          el.resultPanel.style.display = 'none';
          el.outlinePanel.style.display = 'none';
        }
        await renderHistory();
      });

      td.appendChild(viewBtn);
      td.appendChild(delBtn);
      el.historyRows.appendChild(tr);
    }
  }

  /** 把某筆歷史載入畫面（可繼續下載或匯入）。 */
  function loadRecord(r) {
    state.chapters = (r.chapters || []).slice();
    state.outline = r.outline || '';
    state.recordId = r.id;
    state.recordTitle = r.title;
    el.outlineText.textContent = state.outline;
    el.outlinePanel.style.display = state.outline ? '' : 'none';
    renderResults();
    if (state.chapters.length) {
      setResultMsg(`已載入「${r.title || '（無標題）'}」（${state.chapters.length} 章）`);
      el.resultPanel.scrollIntoView({ behavior: 'smooth', block: 'start' });
    } else {
      setResultMsg('這筆記錄沒有章節內容', true);
    }
  }

  // ---- 綁定 ----

  el.outlineBtn.addEventListener('click', generateOutline);
  el.regenOutlineBtn.addEventListener('click', generateOutline);
  el.generateBtn.addEventListener('click', generateAll);
  el.stopBtn.addEventListener('click', () => {
    state.stopped = true;
    setMsg('將在目前這一章完成後停止…');
  });
  el.downloadTxtBtn.addEventListener('click', downloadTxt);
  el.toNovelBtn.addEventListener('click', toNovel);

  renderHistory();
})();
