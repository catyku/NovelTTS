/**
 * 閘道 API client：呼叫 server 的無狀態 AI 端點。
 *
 * API Key 從 localStorage 取出，以 X-OpenRouter-Key 標頭帶上。
 * 伺服器只在該次請求中使用它，不寫入資料庫、不落盤、不記錄到 log。
 */
(function (global) {
  'use strict';

  /**
   * 閘道 API 的 base 路徑。
   *
   * 預設同源（相對路徑）。若前端與 API 不同源（例如靜態頁面放在 CDN、
   * 或本機除錯時指向另一個 port），可呼叫 setApiBase() 改成絕對 URL。
   */
  let API_BASE = '/api/gateway';

  /** 設定閘道 API 的 base URL（用於跨源部署或本機測試）。 */
  function setApiBase(base) {
    API_BASE = (base || '/api/gateway').replace(/\/$/, '');
  }

  /** 產生完整 URL。 */
  function url(path) {
    if (/^https?:\/\//.test(API_BASE)) {
      return API_BASE + path;
    }
    return API_BASE + path;
  }

  /** 建立帶有 API Key 的請求。 */
  function buildHeaders(extra) {
    const headers = Object.assign({}, extra || {});
    const key = window.NovelTtsDB.credentials.get();
    if (key) {
      headers['X-OpenRouter-Key'] = key;
    }
    const baseUrl = window.NovelTtsDB.credentials.getBaseUrl();
    if (baseUrl) {
      headers['X-OpenRouter-Base-Url'] = baseUrl;
    }
    return headers;
  }

  /** 讀取錯誤訊息。 */
  async function readError(response) {
    const text = await response.text();
    try {
      const json = JSON.parse(text);
      return json.message || json.error || json.detail || text;
    } catch (e) {
      return text || `HTTP ${response.status}`;
    }
  }

  const gateway = {
    /**
     * 分析章節內容 → 段落與角色。
     *
     * @returns {Promise<{segments: Array}>}
     */
    async analyze(opts) {
      const settings = await window.NovelTtsDB.settings.get();
      const body = {
        model: opts.model || settings.analysisModel,
        fallbackModelId: opts.fallbackModelId !== undefined
          ? opts.fallbackModelId
          : settings.fallbackAnalysisModel,
        chapterTitle: opts.chapterTitle || null,
        chapterContent: opts.chapterContent,
        temperature: opts.temperature !== undefined ? opts.temperature : settings.temperature,
        maxTokens: opts.maxTokens !== undefined ? opts.maxTokens : settings.maxTokens,
        extraParams: opts.extraParams || {},
      };
      const response = await fetch(url('/analyze'), {
        method: 'POST',
        headers: buildHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(body),
      });
      if (!response.ok) {
        throw new Error('分析失敗：' + (await readError(response)));
      }
      return response.json();
    },

    /**
     * 語音合成 → MP3 Blob。
     *
     * @param {Array<{text,voice,instructions}>} turns
     * @returns {Promise<Blob>}
     */
    async synthesize(turns, opts) {
      const settings = await window.NovelTtsDB.settings.get();
      const body = {
        model: (opts && opts.model) || settings.ttsModel,
        fallbackModelId: (opts && opts.fallbackModelId !== undefined)
          ? opts.fallbackModelId
          : settings.fallbackTtsModel,
        turns: turns,
        globalInstructions: (opts && opts.globalInstructions !== undefined)
          ? opts.globalInstructions
          : settings.globalInstructions,
      };
      const response = await fetch(url('/synthesize'), {
        method: 'POST',
        headers: buildHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(body),
      });
      if (!response.ok) {
        throw new Error('語音合成失敗：' + (await readError(response)));
      }
      return response.blob();
    },

    /** 列出可用語音模型。 */
    async models() {
      const response = await fetch(url('/models'), { headers: buildHeaders() });
      if (!response.ok) {
        throw new Error('取得模型清單失敗：' + (await readError(response)));
      }
      return response.json();
    },

    /**
     * 合併多個 MP3 → 合併後的 MP3 Blob。
     *
     * 合併需要 ffmpeg，搬到瀏覽器要 30MB 的 wasm，因此放在 server 端做。
     * server 是無狀態的：收到位元組、合併、回傳，不保留任何檔案。
     *
     * @param {Array<{blob: Blob, name: string}>} items
     */
    async merge(items) {
      if (!items || !items.length) {
        throw new Error('沒有可合併的音檔');
      }
      const form = new FormData();
      items.forEach((it, i) => {
        form.append('files', it.blob, it.name || ('part' + i + '.mp3'));
      });
      const response = await fetch(url('/merge'), {
        method: 'POST',
        headers: buildHeaders(),
        body: form,
      });
      if (!response.ok) {
        throw new Error('合併失敗：' + (await readError(response)));
      }
      return response.blob();
    },

    /**
     * 把多個檔案打包成 ZIP → ZIP Blob。
     *
     * @param {Array<{blob: Blob, name: string}>} items
     * @param {string} archiveName ZIP 檔名（不含副檔名）
     */
    async zip(items, archiveName) {
      if (!items || !items.length) {
        throw new Error('沒有可打包的檔案');
      }
      const form = new FormData();
      items.forEach((it, i) => {
        form.append('files', it.blob, it.name || ('file' + i));
      });
      if (archiveName) {
        form.append('name', archiveName);
      }
      const response = await fetch(url('/zip'), {
        method: 'POST',
        headers: buildHeaders(),
        body: form,
      });
      if (!response.ok) {
        throw new Error('打包失敗：' + (await readError(response)));
      }
      return response.blob();
    },

    /** 測試 provider 連線。 */
    async test() {
      const response = await fetch(url('/test'), {
        method: 'POST',
        headers: buildHeaders({ 'Content-Type': 'application/json' }),
        body: '{}',
      });
      if (!response.ok) {
        throw new Error('連線測試失敗：' + (await readError(response)));
      }
      return response.json();
    },
  };

  gateway.setApiBase = setApiBase;
  gateway.getApiBase = () => API_BASE;
  window.NovelTtsGateway = gateway;
})(window);
