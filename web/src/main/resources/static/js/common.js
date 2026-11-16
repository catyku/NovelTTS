/* 共用工具 */
function csrfToken() {
  const meta = document.querySelector('meta[name="_csrf_token"]');
  return meta ? meta.getAttribute('content') : '';
}

function csrfHeaderName() {
  const meta = document.querySelector('meta[name="_csrf_header"]');
  return meta ? meta.getAttribute('content') : 'X-CSRF-TOKEN';
}

function novelId() {
  const meta = document.querySelector('meta[name="_novel_id"]');
  return meta ? meta.getAttribute('content') : null;
}

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
      : (body && body.error) ? body.error : ('HTTP ' + resp.status);
    throw new Error(msg);
  }
  return body;
}

function el(tag, attrs, ...children) {
  const node = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (k === 'class') node.className = v;
      else if (k === 'text') node.textContent = v;
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

function pollJob(jobId, box) {
  const timer = setInterval(async () => {
    try {
      const job = await api('/api/jobs/' + jobId);
      const pct = job.total > 0 ? Math.round(job.progress * 100 / job.total) : 0;
      box.innerHTML = '';
      box.appendChild(el('div', { class: 'progress' }, el('span', { style: 'width:' + pct + '%' })));
      box.appendChild(el('div', { class: 'muted',
        text: job.status + ' — ' + (job.message || '') + ' (' + job.progress + '/' + job.total + ')' }));
      if (job.status === 'SUCCESS' || job.status === 'FAILED') {
        clearInterval(timer);
        if (job.status === 'FAILED') {
          box.appendChild(el('div', { class: 'alert alert-danger', text: job.errorMessage || '失敗' }));
        } else {
          location.reload();
        }
      }
    } catch (e) {
      clearInterval(timer);
      box.appendChild(el('div', { class: 'alert alert-danger', text: e.message }));
    }
  }, 2000);
}
