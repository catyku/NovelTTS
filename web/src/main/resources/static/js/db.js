/**
 * 瀏覽器端資料層：IndexedDB。
 *
 * 設計前提（訪客模式）：
 * - 小說內容、章節、角色、段落、音檔、設定全部存在使用者的瀏覽器裡
 * - 伺服器只做無狀態 AI 運算，不保存任何使用者資料
 * - OpenRouter API Key 存在 localStorage，呼叫閘道時以標頭帶上
 *
 * 資料表：
 * - novels     小說（內含章節、角色、段落的巢狀結構）
 * - groups     小說群組（跨集共用角色音色）
 * - audio      音檔（Blob），key = "<novelId>:<kind>:<refId>"
 * - settings   使用者設定（單筆）
 */
(function (global) {
  'use strict';

  const DB_NAME = 'novel-tts';
  const DB_VERSION = 1;
  const STORES = ['novels', 'groups', 'audio', 'settings'];

  let dbPromise = null;

  /** 開啟（必要時升級）資料庫。 */
  function open() {
    if (dbPromise) return dbPromise;
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = (e) => {
        const db = req.result;
        STORES.forEach((name) => {
          if (!db.objectStoreNames.contains(name)) {
            db.createObjectStore(name, { keyPath: 'id' });
          }
        });
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    return dbPromise;
  }

  /** 交易輔助函式。 */
  async function tx(storeName, mode, fn) {
    const db = await open();
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(storeName, mode);
      const store = transaction.objectStore(storeName);
      let result;
      try {
        result = fn(store);
      } catch (e) {
        reject(e);
        return;
      }
      transaction.oncomplete = () => resolve(result && result.__req ? result.__req.result : result);
      transaction.onerror = () => reject(transaction.error);
      transaction.onabort = () => reject(transaction.error);
    });
  }

  /** 包裝 IDBRequest 以取得結果。 */
  function wrap(request) {
    return { __req: request };
  }

  const store = {
    /** 取得全部資料。 */
    async getAll(name) {
      return tx(name, 'readonly', (s) => wrap(s.getAll()));
    },

    /** 依 key 取得單筆。 */
    async get(name, id) {
      return tx(name, 'readonly', (s) => wrap(s.get(id)));
    },

    /** 寫入（新增或覆蓋）。 */
    async put(name, value) {
      return tx(name, 'readwrite', (s) => wrap(s.put(value)));
    },

    /** 批次寫入。 */
    async putAll(name, values) {
      if (!values || !values.length) return;
      return tx(name, 'readwrite', (s) => {
        values.forEach((v) => s.put(v));
      });
    },

    /** 刪除。 */
    async remove(name, id) {
      return tx(name, 'readwrite', (s) => wrap(s.delete(id)));
    },

    /** 清空整個 store。 */
    async clear(name) {
      return tx(name, 'readwrite', (s) => wrap(s.clear()));
    },

    /** 估算佔用空間。 */
    async estimate() {
      if (navigator.storage && navigator.storage.estimate) {
        return navigator.storage.estimate();
      }
      return null;
    },
  };

  // ---- 使用者設定 ----

  const SETTINGS_KEY = 'user';

  const settings = {
    /** 讀取設定（不存在時回傳預設值）。 */
    async get() {
      const row = await store.get('settings', SETTINGS_KEY);
      return Object.assign({
        // API Key 不放這裡，見 credentials
        provider: 'openrouter',
        baseUrl: 'https://openrouter.ai/api/v1',
        analysisModel: 'google/gemini-3-flash-preview',
        ttsModel: 'google/gemini-3.8-flash-tts',
        fallbackAnalysisModel: '',
        fallbackTtsModel: '',
        temperature: 0.2,
        maxTokens: 8000,
        narratorVoice: 'Kore',
        defaultVoice: 'Kore',
        globalInstructions: '小說有聲書風格，發音清晰自然',
        chapterMaxChars: 3000,
        ttsGroupMaxChars: 3000,
      }, row || {});
    },

    /** 寫入設定。 */
    async save(patch) {
      const current = await settings.get();
      const next = Object.assign({}, current, patch);
      next.id = SETTINGS_KEY;
      await store.put('settings', next);
      return next;
    },
  };

  // ---- 憑證（API Key）----
  //
  // 單獨放在 localStorage 而非 IndexedDB：IndexedDB 與頁面同源可被 JS 讀取，
  // localStorage 也是，但它至少不會隨著「匯出/清除資料」一起被誤當成一般資料。
  // 兩者安全性相同，關鍵是都不送給伺服器。

  const KEY_STORAGE = 'noveltts.openrouterKey';
  const BASE_URL_STORAGE = 'noveltts.baseUrl';

  const credentials = {
    /** 讀取 API Key。 */
    get() {
      try {
        return localStorage.getItem(KEY_STORAGE) || '';
      } catch (e) {
        return '';
      }
    },

    /** 寫入 API Key。 */
    set(key) {
      try {
        if (key) {
          localStorage.setItem(KEY_STORAGE, key);
        } else {
          localStorage.removeItem(KEY_STORAGE);
        }
      } catch (e) {
        console.warn('無法寫入 localStorage', e);
      }
    },

    /** 讀取 base URL。 */
    getBaseUrl() {
      try {
        return localStorage.getItem(BASE_URL_STORAGE) || 'https://openrouter.ai/api/v1';
      } catch (e) {
        return 'https://openrouter.ai/api/v1';
      }
    },

    setBaseUrl(url) {
      try {
        localStorage.setItem(BASE_URL_STORAGE, url);
      } catch (e) {
        console.warn('無法寫入 localStorage', e);
      }
    },

    /** 遮蔽後的 key，供畫面顯示。 */
    masked() {
      const key = credentials.get();
      if (!key || key.length <= 8) return '（未設定）';
      return key.substring(0, 3) + '****' + key.substring(key.length - 4);
    },

    /** 是否已設定。 */
    has() {
      return !!credentials.get();
    },
  };

  // ---- 領域操作 ----

  const novels = {
    async list() {
      const rows = await store.getAll('novels');
      return rows.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
    },
    async get(id) {
      return store.get('novels', id);
    },
    async save(novel) {
      novel.updatedAt = Date.now();
      if (!novel.createdAt) novel.createdAt = novel.updatedAt;
      await store.put('novels', novel);
      return novel;
    },
    async remove(id) {
      // 連帶刪除該小說的音檔
      const keys = await audio.keysOfNovel(id);
      for (const k of keys) {
        await store.remove('audio', k);
      }
      await store.remove('novels', id);
    },
  };

  const groups = {
    async list() {
      const rows = await store.getAll('groups');
      return rows.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
    },
    async get(id) {
      return store.get('groups', id);
    },
    async save(group) {
      group.updatedAt = Date.now();
      if (!group.createdAt) group.createdAt = group.updatedAt;
      await store.put('groups', group);
      return group;
    },
    async remove(id) {
      await store.remove('groups', id);
    },
  };

  const audio = {
    /** 音檔的 key 格式：<novelId>:<kind>:<refId> */
    key(novelId, kind, refId) {
      return `${novelId}:${kind}:${refId}`;
    },

    /** 儲存音檔 Blob。 */
    async save(novelId, kind, refId, blob, meta) {
      const key = audio.key(novelId, kind, refId);
      await store.put('audio', Object.assign({
        id: key,
        novelId: String(novelId),
        kind: kind,
        refId: String(refId),
        blob: blob,
        size: blob ? blob.size : 0,
        createdAt: Date.now(),
      }, meta || {}));
      return key;
    },

    /** 取得音檔。 */
    async get(novelId, kind, refId) {
      return store.get('audio', audio.key(novelId, kind, refId));
    },

    /** 取得某小說的所有音檔 key。 */
    async keysOfNovel(novelId) {
      const rows = await store.getAll('audio');
      return rows.filter((r) => r.novelId === String(novelId)).map((r) => r.id);
    },

    /** 列出某小說的音檔。 */
    async listOfNovel(novelId) {
      const rows = await store.getAll('audio');
      return rows.filter((r) => r.novelId === String(novelId));
    },

    async remove(novelId, kind, refId) {
      await store.remove('audio', audio.key(novelId, kind, refId));
    },

    /** 取得可播放的 object URL（呼叫端負責 revoke）。 */
    async url(novelId, kind, refId) {
      const row = await audio.get(novelId, kind, refId);
      if (!row || !row.blob) return null;
      return URL.createObjectURL(row.blob);
    },
  };

  global.NovelTtsDB = {
    store: store,
    settings: settings,
    credentials: credentials,
    novels: novels,
    groups: groups,
    audio: audio,
    /** 匯出全部資料（不含 API Key）。 */
    async exportAll() {
      return {
        version: DB_VERSION,
        exportedAt: new Date().toISOString(),
        novels: await store.getAll('novels'),
        groups: await store.getAll('groups'),
        settings: await settings.get(),
      };
    },
    /** 匯出成可下載的 JSON（不含音檔與 API Key）。 */
    async exportJson() {
      const data = await global.NovelTtsDB.exportAll();
      return new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    },
    /** 清空所有資料。 */
    async clearAll() {
      for (const name of STORES) {
        await store.clear(name);
      }
    },
  };
})(window);
