/* 共用工具（訪客模式：資料在瀏覽器，AI 走無狀態閘道） */

/** 讀取 csrf token（僅舊的 server 端 API 需要）。 */
function csrfToken() {
  const meta = document.querySelector('meta[name="_csrf_token"]');
  return meta ? meta.getAttribute('content') : '';
}

function csrfHeaderName() {
  const meta = document.querySelector('meta[name="_csrf_header"]');
  return meta ? meta.getAttribute('content') : 'X-CSRF-TOKEN';
}

/** 目前小說的 id（由 template 的 meta 提供）。 */
function novelId() {
  const meta = document.querySelector('meta[name="_novel_id"]');
  return meta ? meta.getAttribute('content') : null;
}

/** 產生短 id。 */
function uid(prefix) {
  return (prefix || 'id') + '_' + Date.now().toString(36) + '_' +
    Math.random().toString(36).slice(2, 8);
}

/**
 * 呼叫舊的 server 端 API。
 *
 * 訪客模式下資料都在瀏覽器，這只用於少數仍由 server 提供的功能
 * （例如共用預設音色庫、模型定價）。
 */
async function api(url, options) {
  const opts = Object.assign({ credentials: 'same-origin' }, options || {});
  const headers = Object.assign({ 'Content-Type': 'application/json' }, opts.headers || {});
  const method = (opts.method || 'GET').toUpperCase();
  if (method !== 'GET' && method !== 'HEAD') {
    headers[csrfHeaderName()] = csrfToken();
  }
  opts.headers = headers;
  const resp = await fetch(url, opts);
  const text = await resp.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch (e) { body = text; }
  if (!resp.ok) {
    const msg = (body && body.detail) ? body.detail
      : (body && body.error) ? body.error
      : (body && body.message) ? body.message : ('HTTP ' + resp.status);
    throw new Error(msg);
  }
  return body;
}

/** 建立 DOM 元素。 */
function el(tag, attrs, ...children) {
  const node = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (k === 'class') node.className = v;
      else if (k === 'text') node.textContent = v;
      else if (k === 'html') node.innerHTML = v;
      else if (k.startsWith('on') && typeof v === 'function') node.addEventListener(k.slice(2), v);
      else if (v !== null && v !== undefined) node.setAttribute(k, v);
    }
  }
  children.forEach(c => {
    if (c === null || c === undefined) return;
    node.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
  });
  return node;
}

function fmtDuration(ms) {
  if (ms === null || ms === undefined) return '-';
  const s = Math.round(ms / 1000);
  return Math.floor(s / 60) + ' 分 ' + (s % 60) + ' 秒';
}

function fmtSize(bytes) {
  if (bytes === null || bytes === undefined) return '-';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return Math.round(bytes / 1024) + ' KB';
  return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}

/** 顯示錯誤訊息。 */
function showError(box, message) {
  if (!box) return;
  box.innerHTML = '';
  box.appendChild(el('div', { class: 'alert alert-danger', text: message }));
}

/** 顯示成功訊息。 */
function showOk(box, message) {
  if (!box) return;
  box.innerHTML = '';
  box.appendChild(el('div', { class: 'alert alert-success', text: message }));
}

/** 進度條。 */
function progressBox(pct, label) {
  const box = el('div', { class: 'progress' }, el('span', { style: 'width:' + pct + '%' }));
  const wrap = el('div', {}, box);
  if (label) {
    wrap.appendChild(el('div', { class: 'muted', text: label }));
  }
  return wrap;
}

/**
 * 觸發瀏覽器下載。
 */
function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  // 延遲 revoke，避免部分瀏覽器來不及開始下載
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}

/**
 * 讓使用者選一個文字檔。
 */
function pickTextFile(accept) {
  return new Promise((resolve) => {
    const input = document.createElement('input');
    input.type = 'file';
    if (accept) input.accept = accept;
    input.onchange = () => {
      const file = input.files && input.files[0];
      if (!file) { resolve(null); return; }
      const reader = new FileReader();
      reader.onload = () => resolve({ name: file.name, text: String(reader.result) });
      reader.onerror = () => resolve(null);
      reader.readAsText(file);
    };
    input.click();
  });
}

/**
 * 檢查是否已設定 OpenRouter API Key，未設定則導向設定頁。
 */
function requireApiKey() {
  if (window.NovelTtsDB.credentials.has()) return true;
  alert('請先在「設定」頁填寫你的 OpenRouter API Key。');
  location.href = '/settings';
  return false;
}
