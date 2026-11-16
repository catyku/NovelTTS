/* 小說詳情 */
const NOVEL_ID = novelId();

async function loadSegmentCounts() {
  const chapters = await api('/api/novels/' + NOVEL_ID + '/chapters');
  for (const ch of chapters) {
    const cell = document.querySelector('.seg-count[data-chapter-id="' + ch.id + '"]');
    if (cell) cell.textContent = String(ch.segmentCount);
  }
}

document.getElementById('analyzeBtn').addEventListener('click', async () => {
  const box = document.getElementById('jobBox');
  box.innerHTML = '分析中…';
  try {
    const job = await api('/api/novels/' + NOVEL_ID + '/analyze', { method: 'POST' });
    pollJob(job.id, box);
  } catch (e) {
    box.innerHTML = '';
    box.appendChild(el('div', { class: 'alert alert-danger', text: e.message }));
  }
});

document.getElementById('synthesizeBtn').addEventListener('click', async () => {
  const box = document.getElementById('jobBox');
  box.innerHTML = '合成中…';
  try {
    const job = await api('/api/novels/' + NOVEL_ID + '/synthesize?onlyPending=true', { method: 'POST' });
    pollJob(job.id, box);
  } catch (e) {
    box.innerHTML = '';
    box.appendChild(el('div', { class: 'alert alert-danger', text: e.message }));
  }
});

document.getElementById('mergeBtn').addEventListener('click', async () => {
  const box = document.getElementById('jobBox');
  try {
    const audio = await api('/api/novels/' + NOVEL_ID + '/merge', { method: 'POST' });
    box.innerHTML = '';
    box.appendChild(el('div', { class: 'alert alert-success',
      text: '合併完成：' + audio.fileName + '（' + fmtDuration(audio.durationMs) + '）' }));
  } catch (e) {
    box.innerHTML = '';
    box.appendChild(el('div', { class: 'alert alert-danger', text: e.message }));
  }
});

loadSegmentCounts();
