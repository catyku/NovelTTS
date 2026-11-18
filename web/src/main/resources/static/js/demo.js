/**
 * 範例內容的載入。
 *
 * 說明頁上的「試試看」區塊用它做兩件事：
 *   1. 顯示範例長什麼樣（章節、角色、音色、可以試聽）
 *   2. 一鍵把範例匯入訪客自己的瀏覽器，讓他不用自己打字就能跑完整流程
 *
 * 範例資料放在 /demo/demo.json（靜態資源），音檔在 /demo/audio/。
 * 這些都是先前用真實 OpenRouter 產生的，內容本身沒有敏感資訊。
 */
(function () {
  'use strict';

  const DEMO_URL = '/demo/demo.json';
  const DEMO_AUDIO = '/demo/audio/';

  let cache = null;

  /** 讀取範例資料（第二次呼叫會走快取）。 */
  async function load() {
    if (cache) return cache;
    const resp = await fetch(DEMO_URL);
    if (!resp.ok) throw new Error('無法載入範例資料（HTTP ' + resp.status + '）');
    cache = await resp.json();
    return cache;
  }

  /** 範例音檔的 URL。 */
  function audioUrl(entry) {
    return DEMO_AUDIO + entry.file;
  }

  /**
   * 把範例匯入使用者的瀏覽器資料庫。
   *
   * <p>音檔是從靜態資源抓回來再存進 IndexedDB —— 與實際流程產生的路徑相同，
   * 所以匯入後「小說」「角色」「段落與音檔」「群組」頁都能正常顯示與試聽。
   *
   * @param {function} onProgress (done, total, label)
   * @returns {Promise<object>} 匯入統計
   */
  async function importAll(onProgress) {
    const DB = window.NovelTtsDB;
    if (!DB) throw new Error('資料層尚未載入');

    const demo = await load();
    const report = { novels: 0, audio: 0, group: false };

    await DB.groups.save(demo.group);
    report.group = true;

    for (const novel of demo.novels) {
      await DB.novels.save(novel);
      report.novels++;
    }

    const entries = demo.audio || [];
    let done = 0;
    for (const entry of entries) {
      const resp = await fetch(audioUrl(entry));
      if (!resp.ok) throw new Error('無法取得範例音檔：' + entry.file);
      const blob = await resp.blob();
      const meta = Object.assign({}, entry.meta || {});
      if (meta.durationMs === undefined) {
        meta.durationMs = await measureDuration(blob);
      }
      await DB.audio.save(entry.novelId, entry.kind, entry.refId, blob, meta);
      report.audio++;
      done++;
      if (onProgress) onProgress(done, entries.length, entry.file);
    }
    return report;
  }

  /** 用 Web Audio API 量測音檔長度（毫秒），失敗回傳 null。 */
  async function measureDuration(blob) {
    const Ctx = window.AudioContext || window.webkitAudioContext;
    if (!Ctx) return null;
    let ctx = null;
    try {
      ctx = new Ctx();
      const buf = await ctx.decodeAudioData(await blob.arrayBuffer());
      return Math.round(buf.duration * 1000);
    } catch (e) {
      return null;
    } finally {
      if (ctx) {
        try { await ctx.close(); } catch (e) { /* 忽略關閉錯誤 */ }
      }
    }
  }

  /** 移除所有範例內容（只刪 demo_* 開頭的，不動使用者自己的資料）。 */
  async function removeAll() {
    const DB = window.NovelTtsDB;
    const demo = await load();
    const ids = demo.novels.map((n) => n.id);
    for (const id of ids) {
      await DB.novels.remove(id);
    }
    await DB.groups.remove(demo.group.id);
    const rows = await DB.store.getAll('audio');
    for (const r of rows) {
      if (ids.includes(r.novelId)) {
        await DB.store.remove('audio', r.id);
      }
    }
  }

  /** 使用者是否已經有範例內容。 */
  async function hasImported() {
    const DB = window.NovelTtsDB;
    const demo = await load();
    const rows = await DB.store.getAll('audio');
    return rows.some((r) => demo.novels.some((n) => n.id === r.novelId));
  }

  window.NovelTtsDemo = {
    load: load,
    audioUrl: audioUrl,
    importAll: importAll,
    removeAll: removeAll,
    hasImported: hasImported,
    measureDuration: measureDuration,
  };
})();
