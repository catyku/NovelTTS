/**
 * 「我的音色庫」管理介面（設定頁）。
 *
 * 功能：
 *   - 從 OpenRouter 動態匯入音色（單一模型 / 全部模型 / 網站精選）
 *   - 篩選：搜尋、模型、分類、只看已啟用
 *   - 編輯：重新命名、加分類、編輯風格描述、啟用停用、調整排序
 *   - 批次：套用分類、批次啟用／停用
 *   - 试听：用實際的 TTS 模型唸一句話，確認音色合不合用
 */
(function () {
  'use strict';

  const DB = window.NovelTtsDB;
  const V = window.NovelTtsVoices;
  const GW = window.NovelTtsGateway;

  const el = {
    model: document.getElementById('vModel'),
    category: document.getElementById('vCategory'),
    importOne: document.getElementById('vImportOne'),
    importAll: document.getElementById('vImportAll'),
    seed: document.getElementById('vSeed'),
    clear: document.getElementById('vClear'),
    msg: document.getElementById('vMsg'),
    search: document.getElementById('vSearch'),
    filterModel: document.getElementById('vFilterModel'),
    filterCategory: document.getElementById('vFilterCategory'),
    onlyEnabled: document.getElementById('vOnlyEnabled'),
    stats: document.getElementById('vStats'),
    table: document.getElementById('vTable'),
    bulkCategory: document.getElementById('vBulkCategory'),
    bulkApply: document.getElementById('vBulkApply'),
    bulkEnable: document.getElementById('vBulkEnable'),
    bulkDisable: document.getElementById('vBulkDisable'),
    pager: document.getElementById('vPager'),
  };

  /** 目前的篩選結果（供批次操作使用）。 */
  let filtered = [];

  function setMsg(text, isError) {
    el.msg.textContent = text || '';
    el.msg.style.color = isError ? 'var(--danger, #c0392b)' : '';
  }

  /** 產生元素。 */
  function make(tag, attrs, children) {
    const node = document.createElement(tag);
    if (attrs) {
      for (const [k, v] of Object.entries(attrs)) {
        if (k === 'text') node.textContent = v;
        else if (k === 'class') node.className = v;
        else if (k === 'html') node.innerHTML = v;
        else if (v !== null && v !== undefined) node.setAttribute(k, v);
      }
    }
    (children || []).forEach((c) => node.appendChild(c));
    return node;
  }

  /* ---------- 篩選 ---------- */

  function applyFilter() {
    const q = (el.search.value || '').trim().toLowerCase();
    const model = el.filterModel.value;
    const category = el.filterCategory.value;
    const onlyEnabled = el.onlyEnabled.checked;
    return filtered.filter((v) => {
      if (model && v.model !== model) return false;
      if (category && v.category !== category) return false;
      if (onlyEnabled && v.enabled === false) return false;
      if (q) {
        const hay = (v.displayName + ' ' + v.voiceId + ' ' + (v.styleDesc || '')).toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });
  }

  /* ---------- 渲染 ---------- */

  async function render() {
    const all = await DB.voices.list();
    filtered = all;

    // 篩選選單
    const models = Array.from(new Set(all.map((v) => v.model))).sort();
    const categories = Array.from(new Set(all.map((v) => v.category || '').filter(Boolean))).sort();
    fillFilter(el.filterModel, models, V.modelLabel, '全部');
    fillFilter(el.filterCategory, categories, (c) => c, '全部');

    const rows = applyFilter();
    const enabledCount = all.filter((v) => v.enabled !== false).length;
    el.stats.textContent = `共 ${all.length} 個音色，已啟用 ${enabledCount} 個，目前篩出 ${rows.length} 個`;

    renderTable(rows);
  }

  function fillFilter(select, values, labelFn, allText) {
    const current = select.value;
    select.innerHTML = '';
    select.appendChild(make('option', { value: '', text: allText }));
    for (const v of values) {
      select.appendChild(make('option', { value: v, text: labelFn(v) }));
    }
    if (values.includes(current)) select.value = current;
  }

  /** 每頁顯示的筆數。音色庫可能有數百筆，全渲染會拖慢頁面。 */
  const PAGE_SIZE = 100;

  /** 目前頁碼。 */
  let page = 0;

  function renderTable(rows) {
    el.table.innerHTML = '';
    if (!rows.length) {
      el.table.appendChild(make('div', { class: 'muted', text: '沒有符合條件的音色。先從上方匯入。' }));
      el.pager.innerHTML = '';
      return;
    }

    const pages = Math.ceil(rows.length / PAGE_SIZE);
    if (page >= pages) page = pages - 1;
    if (page < 0) page = 0;
    const slice = rows.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);

    const tbody = make('tbody');
    for (const v of slice) {
      tbody.appendChild(renderRow(v));
    }
    el.table.appendChild(make('table', {}, [
      make('thead', {}, [make('tr', {}, [
        make('th', { text: '顯示名' }),
        make('th', { text: 'voice_id' }),
        make('th', { text: '模型' }),
        make('th', { text: '分類' }),
        make('th', { text: '風格描述' }),
        make('th', { text: '啟用' }),
        make('th', { text: '排序' }),
        make('th', { text: '操作' }),
      ])]),
      tbody,
    ]));

    renderPager(pages, rows.length);
  }

  /** 分頁列。 */
  function renderPager(pages, total) {
    el.pager.innerHTML = '';
    if (pages <= 1) return;
    const prev = make('button', { class: 'btn btn-sm', text: '‹ 上一頁' });
    prev.disabled = page <= 0;
    prev.addEventListener('click', () => { page--; renderTable(applyFilter()); });
    const next = make('button', { class: 'btn btn-sm', text: '下一頁 ›' });
    next.disabled = page >= pages - 1;
    next.addEventListener('click', () => { page++; renderTable(applyFilter()); });
    el.pager.appendChild(prev);
    el.pager.appendChild(make('span', {
      class: 'muted',
      text: ` 第 ${page + 1} / ${pages} 頁（共 ${total} 個） `,
    }));
    el.pager.appendChild(next);
  }

  function renderRow(v) {
    const nameInput = make('input', { type: 'text', value: v.displayName, class: 'v-name-input' });
    nameInput.addEventListener('change', async () => {
      const val = nameInput.value.trim();
      if (!val) { nameInput.value = v.displayName; return; }
      await DB.voices.save(Object.assign({}, v, { displayName: val }));
      setMsg('已更新名稱');
      await render();
    });

    const catInput = make('input', { type: 'text', value: v.category || '', class: 'v-cat-input' });
    catInput.addEventListener('change', async () => {
      await DB.voices.save(Object.assign({}, v, { category: catInput.value.trim() }));
      setMsg('已更新分類');
      await render();
    });

    const styleInput = make('input', { type: 'text', value: v.styleDesc || '', class: 'v-style-input' });
    styleInput.addEventListener('change', async () => {
      await DB.voices.save(Object.assign({}, v, { styleDesc: styleInput.value.trim() }));
      setMsg('已更新風格描述');
    });

    const enabledBox = make('input', { type: 'checkbox' });
    enabledBox.checked = v.enabled !== false;
    enabledBox.addEventListener('change', async () => {
      await DB.voices.save(Object.assign({}, v, { enabled: enabledBox.checked }));
      setMsg(enabledBox.checked ? '已啟用' : '已停用');
      await render();
    });

    // 排序：上移／下移
    const upBtn = make('button', { class: 'btn btn-sm', text: '↑', title: '上移' });
    upBtn.addEventListener('click', () => move(v, -1));
    const downBtn = make('button', { class: 'btn btn-sm', text: '↓', title: '下移' });
    downBtn.addEventListener('click', () => move(v, 1));

    const delBtn = make('button', { class: 'btn btn-sm btn-danger', text: '刪除' });
    delBtn.addEventListener('click', async () => {
      if (!window.confirm(`確定要從音色庫移除「${v.displayName}」嗎？`)) return;
      await DB.voices.remove(v.id);
      setMsg('已移除');
      await render();
    });

    const previewBtn = make('button', { class: 'btn btn-sm', text: '試聽' });
    previewBtn.addEventListener('click', () => preview(v, previewBtn));

    return make('tr', {}, [
      make('td', {}, [nameInput]),
      make('td', { class: 'muted', text: v.voiceId }),
      make('td', { class: 'muted', text: V.modelLabel(v.model) }),
      make('td', {}, [catInput]),
      make('td', {}, [styleInput]),
      make('td', {}, [enabledBox]),
      make('td', {}, [make('span', { class: 'row-inline' }, [upBtn, downBtn])]),
      make('td', {}, [previewBtn, make('span', { text: ' ' }), delBtn]),
    ]);
  }

  /** 上下移動一筆（swap sortOrder）。 */
  async function move(v, delta) {
    const all = await DB.voices.list();
    const idx = all.findIndex((x) => x.id === v.id);
    const target = idx + delta;
    if (target < 0 || target >= all.length) return;
    const other = all[target];
    const tmp = v.sortOrder;
    await DB.voices.save(Object.assign({}, v, { sortOrder: other.sortOrder }));
    await DB.voices.save(Object.assign({}, other, { sortOrder: tmp }));
    await render();
  }

  /* ---------- 試聽 ---------- */

  /**
   * 用實際的 TTS 模型唸一句話。
   *
   * <p>音色必須搭配對的模型 —— voice_id 是模型內的概念，
   * 把 Gemini 的音色丟給 ElevenLabs 是不行的。
   */
  async function preview(v, btn) {
    const text = '你好，這是這個音色的試聽。窗外雨下得很急，他忽然想起一件事。';
    const original = btn.textContent;
    btn.disabled = true;
    btn.textContent = '試聽中…';
    try {
      const settings = await DB.settings.get();
      const model = v.model || settings.ttsModel || 'google/gemini-3.8-flash-tts';
      const blob = await GW.synthesize(
        [{ text: text, voice: v.voiceId, instructions: v.styleDesc || '' }],
        { model: model }
      );
      const url = URL.createObjectURL(blob);
      const audio = new Audio(url);
      audio.play().catch(() => { /* 自動播放被擋，忽略 */ });
      audio.addEventListener('ended', () => URL.revokeObjectURL(url));
    } catch (e) {
      setMsg('試聽失敗：' + (e.message || e), true);
    } finally {
      btn.disabled = false;
      btn.textContent = original;
    }
  }

  /* ---------- 匯入 ---------- */

  async function loadModelList() {
    try {
      const models = await V.fetchModels();
      el.model.innerHTML = '';
      if (!models.length) {
        el.model.appendChild(make('option', { value: '', text: '（沒有可用的語音模型）' }));
        return;
      }
      // 依家族分組顯示
      const families = new Map();
      for (const m of models) {
        if (!families.has(m.family)) families.set(m.family, []);
        families.get(m.family).push(m);
      }
      for (const [family, list] of families) {
        const group = make('optgroup', { label: V.FAMILY_LABELS[family] || family });
        for (const m of list) {
          group.appendChild(make('option', {
            value: m.id,
            text: `${m.label}（${m.voices.length} 個音色）`,
          }));
        }
        el.model.appendChild(group);
      }
    } catch (e) {
      el.model.innerHTML = '';
      el.model.appendChild(make('option', { value: '', text: '（無法取得模型清單，請先設定 API Key）' }));
    }
  }

  el.importOne.addEventListener('click', async () => {
    const modelId = el.model.value;
    if (!modelId) { setMsg('請先選一個模型', true); return; }
    el.importOne.disabled = true;
    setMsg('匯入中…');
    try {
      const n = await V.importModel(modelId, { category: el.category.value.trim() });
      setMsg(n > 0 ? `已匯入 ${n} 個音色` : '這個模型的音色都已經匯入了');
      await render();
    } catch (e) {
      setMsg(e.message || String(e), true);
    } finally {
      el.importOne.disabled = false;
    }
  });

  el.importAll.addEventListener('click', async () => {
    if (!window.confirm('匯入所有模型的全部音色會有數百筆，可能要一會兒。要繼續嗎？')) return;
    el.importAll.disabled = true;
    setMsg('匯入中…');
    try {
      const r = await V.importAll({ category: el.category.value.trim() });
      setMsg(`已匯入 ${r.total} 個音色（${r.models} 個模型）`);
      await render();
    } catch (e) {
      setMsg(e.message || String(e), true);
    } finally {
      el.importAll.disabled = false;
    }
  });

  el.seed.addEventListener('click', async () => {
    el.seed.disabled = true;
    try {
      const n = await V.seedFromServer();
      setMsg(n > 0 ? `已補入 ${n} 個精選音色` : '精選音色都已經在音色庫裡了');
      await render();
    } finally {
      el.seed.disabled = false;
    }
  });

  el.clear.addEventListener('click', async () => {
    if (!window.confirm('這會清空你所有的音色（含自訂名稱與分類），確定嗎？')) return;
    await DB.store.clear('voices');
    setMsg('音色庫已清空');
    await render();
  });

  /* ---------- 批次 ---------- */

  async function bulkApply(fn, label) {
    const rows = applyFilter();
    if (!rows.length) { setMsg('目前篩選結果是空的', true); return; }
    for (const v of rows) await DB.voices.save(fn(v));
    setMsg(`${label}：已處理 ${rows.length} 個音色`);
    await render();
  }

  el.bulkApply.addEventListener('click', () => {
    const cat = el.bulkCategory.value.trim();
    if (!cat) { setMsg('請先輸入分類名稱', true); return; }
    bulkApply((v) => Object.assign({}, v, { category: cat }), '套用分類');
  });
  el.bulkEnable.addEventListener('click', () => {
    bulkApply((v) => Object.assign({}, v, { enabled: true }), '批次啟用');
  });
  el.bulkDisable.addEventListener('click', () => {
    bulkApply((v) => Object.assign({}, v, { enabled: false }), '批次停用');
  });

  /* ---------- 綁定 ---------- */

  /** 只重繪表格並同步統計數字（篩選變動時用）。 */
  function refreshTableOnly() {
    page = 0;
    const rows = applyFilter();
    const all = filtered;
    const enabledCount = all.filter((v) => v.enabled !== false).length;
    el.stats.textContent =
      `共 ${all.length} 個音色，已啟用 ${enabledCount} 個，目前篩出 ${rows.length} 個`;
    renderTable(rows);
  }

  [el.search, el.filterModel, el.filterCategory, el.onlyEnabled].forEach((node) => {
    node.addEventListener('input', refreshTableOnly);
    node.addEventListener('change', refreshTableOnly);
  });

  // 首次載入：補入網站精選音色 + 載入模型清單
  (async () => {
    try {
      const n = await V.seedFromServer();
      if (n) setMsg(`已補入 ${n} 個網站精選音色`);
    } catch (e) { /* 忽略 */ }
    await render();
    await loadModelList();
  })();
})();
