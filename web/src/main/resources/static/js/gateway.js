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
      // 401：Key 缺失或無效 —— 直接引導使用者去設定頁
      if (response.status === 401 || json.error === 'missing_api_key') {
        return 'OpenRouter API Key 無效或尚未設定，請到「設定」頁填寫你的 Key。';
      }
      // detail 通常是真正的失敗原因（伺服器 exception 訊息），
      // 只顯示 error 會讓使用者只看到「處理失敗」這種無用的字眼。
      const reason = json.detail || json.message || json.error;
      if (json.error && json.detail && json.error !== json.detail) {
        return json.error + '：' + json.detail;
      }
      return reason || text;
    } catch (e) {
      // 不是 JSON —— 多半是反向代理（Cloudflare）回傳的錯誤頁。
      // 直接把整頁 HTML 丟給使用者等於沒有訊息，要翻譯成人話。
      return describeProxyError(response.status, text);
    }
  }

  /**
   * 把反向代理的錯誤頁翻譯成人話。
   *
   * <p>Cloudflare 524 = origin 100 秒沒回應；502/503 = origin 掛掉或重啟。
   * 這些都不是應用程式的問題，直接顯示 HTML 對使用者毫無意義。
   */
  function describeProxyError(status, text) {
    const isHtml = /<!DOCTYPE html|<html/i.test(text || '');
    if (status === 524) {
      return '伺服器處理超過 100 秒仍未回應（Cloudflare 524）。'
        + '通常是這一章太長或 AI 服務忙碌 —— 已經完成的章節都保留，'
        + '請按「繼續 AI 分析」重試這一章。';
    }
    if (status === 502 || status === 503) {
      return '伺服器暫時無法回應（HTTP ' + status + '），可能正在重啟。請稍後再試，'
        + '已完成的章節都保留。';
    }
    if (status === 504) {
      return 'AI 服務回應逾時（HTTP 504），可能是這一章太長或服務忙碌。'
        + '已完成的章節都保留，請按「繼續 AI 分析」重試這一章。';
    }
    if (isHtml) {
      return '伺服器回應格式異常（HTTP ' + status + '）。請稍後再試。';
    }
    return (text || ('HTTP ' + status)).slice(0, 500);
  }

  /** 單次 AI 請求的前端逾時（毫秒）。比伺服器端長一點，讓伺服器先回錯誤。 */
  const REQUEST_TIMEOUT_MS = 90_000;

  /**
   * 帶逾時的 fetch。
   *
   * <p>沒有逾時的話，網路半斷的連線會讓請求一直掛著，
   * 使用者只會看到畫面卡住、沒有任何提示。
   */
  async function fetchWithTimeout(path, init, timeoutMs) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs || REQUEST_TIMEOUT_MS);
    try {
      return await fetch(url(path), Object.assign({}, init, { signal: controller.signal }));
    } catch (e) {
      if (e && e.name === 'AbortError') {
        throw new Error('請求逾時（超過 ' + Math.round((timeoutMs || REQUEST_TIMEOUT_MS) / 1000)
          + ' 秒）。請檢查網路後再試 —— 已完成的章節都保留。');
      }
      throw e;
    } finally {
      clearTimeout(timer);
    }
  }

  const gateway = {
    /** 供測試：把代理的錯誤頁翻譯成人話。 */
    describeProxyError: describeProxyError,

    /**
     * 開始背景分析工作。
     *
     * <p>伺服器在背景逐章呼叫 AI，前端只需要輪詢進度。
     * 這解決了逐章同步呼叫的兩個問題：
     * Cloudflare 的 100 秒牆、以及閘道的每分鐘 30 次 rate limit。
     *
     * @param {object} opts {novelContent, chapterMaxChars, model, ...}
     * @returns {Promise<{jobId: string, total: number}>}
     */
    async startAnalyzeJob(opts) {
      const settings = await window.NovelTtsDB.settings.get();
      const body = {
        // 章節由前端切好再送進去 —— 續傳時「哪些章節要重跑」是前端決定的
        chapters: (opts.chapters || []).map((c) => ({
          index: c.index,
          title: c.title || '',
          content: c.content,
        })),
        model: opts.model || settings.analysisModel,
        fallbackModelId: opts.fallbackModelId !== undefined
          ? opts.fallbackModelId
          : settings.fallbackAnalysisModel,
        temperature: opts.temperature !== undefined ? opts.temperature : settings.temperature,
        maxTokens: opts.maxTokens !== undefined ? opts.maxTokens : settings.maxTokens,
      };
      const response = await fetchWithTimeout('/jobs/analyze', {
        method: 'POST',
        headers: buildHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(body),
      }, 30_000);
      if (!response.ok) {
        throw new Error('無法開始分析：' + (await readError(response)));
      }
      return response.json();
    },

    /**
     * 查詢背景工作的進度與結果。
     *
     * @param {string} jobId
     * @returns {Promise<object>} {status, done, total, results, failures, message}
     */
    async jobStatus(jobId) {
      const response = await fetchWithTimeout('/jobs/' + encodeURIComponent(jobId), {
        method: 'GET',
        headers: buildHeaders(),
      }, 20_000);
      if (!response.ok) {
        throw new Error('查詢進度失敗：' + (await readError(response)));
      }
      return response.json();
    },

    /**
     * 取消背景工作並清除其結果。
     *
     * @param {string} jobId
     */
    async cancelJob(jobId) {
      const response = await fetchWithTimeout('/jobs/' + encodeURIComponent(jobId), {
        method: 'DELETE',
        headers: buildHeaders(),
      }, 20_000);
      if (!response.ok) {
        throw new Error('取消失敗：' + (await readError(response)));
      }
      return response.json();
    },

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
      const response = await fetchWithTimeout('/analyze', {
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
      // TTS 合成比較久，給 100 秒（雲端代理的上限）再多一點也沒意義
      const response = await fetchWithTimeout('/synthesize', {
        method: 'POST',
        headers: buildHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(body),
      }, 100_000);
      if (!response.ok) {
        throw new Error('語音合成失敗：' + (await readError(response)));
      }
      return response.blob();
    },

    /**
     * 生成小說大綱。
     *
     * @param {object} opts {idea, chapterCount, minChars, maxChars}
     * @returns {Promise<string>} 大綱文字
     */
    async generateOutline(opts) {
      const settings = await window.NovelTtsDB.settings.get();
      const body = {
        model: opts.model || settings.analysisModel,
        fallbackModelId: opts.fallbackModelId !== undefined
          ? opts.fallbackModelId
          : settings.fallbackAnalysisModel,
        idea: opts.idea,
        chapterCount: opts.chapterCount,
        minChars: opts.minChars,
        maxChars: opts.maxChars,
        temperature: opts.temperature !== undefined ? opts.temperature : 0.8,
        extraParams: opts.extraParams || {},
      };
      const response = await fetchWithTimeout('/generate-outline', {
        method: 'POST',
        headers: buildHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(body),
      });
      if (!response.ok) {
        throw new Error('產生大綱失敗：' + (await readError(response)));
      }
      const json = await response.json();
      return json.outline || '';
    },

    /**
     * 生成單一章節正文。
     *
     * @param {object} opts {idea, chapterNumber, chapterCount, minChars, maxChars,
     *                       outline, previousEnding, extra}
     * @returns {Promise<{chapterNumber:number, content:string, charCount:number}>}
     */
    async generateChapter(opts) {
      const settings = await window.NovelTtsDB.settings.get();
      const body = {
        model: opts.model || settings.analysisModel,
        fallbackModelId: opts.fallbackModelId !== undefined
          ? opts.fallbackModelId
          : settings.fallbackAnalysisModel,
        idea: opts.idea,
        chapterNumber: opts.chapterNumber,
        chapterCount: opts.chapterCount,
        minChars: opts.minChars,
        maxChars: opts.maxChars,
        outline: opts.outline || null,
        previousEnding: opts.previousEnding || null,
        extra: opts.extra || null,
        temperature: opts.temperature !== undefined ? opts.temperature : 0.8,
        // 不傳 maxTokens：server 端會依字數上限自行換算成硬上限。
        // 傳全域設定的 maxTokens（預設 8000）會讓章節長度失控。
        extraParams: opts.extraParams || {},
      };
      const response = await fetchWithTimeout('/generate-chapter', {
        method: 'POST',
        headers: buildHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(body),
      });
      if (!response.ok) {
        throw new Error('生成章節失敗：' + (await readError(response)));
      }
      return response.json();
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
