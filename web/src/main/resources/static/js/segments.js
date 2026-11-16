/* 段落與音檔 */
const NOVEL_ID = novelId();
const CHAPTER_ID = new URLSearchParams(window.location.search).get('chapterId');

async function saveSegment(id) {
  const text = document.querySelector('.seg-text[data-segment-id="' + id + '"]');
  const voice = document.querySelector('.seg-voice[data-segment-id="' + id + '"]');
  try {
    await api('/api/segments/' + id, {
      method: 'PUT',
      body: JSON.stringify({ text: text.value, voiceId: voice.value })
    });
    text.style.borderColor = '#2f9e44';
    setTimeout(() => { text.style.borderColor = ''; }, 800);
  } catch (e) {
    alert('儲存失敗: ' + e.message);
  }
}

document.querySelectorAll('.save-seg').forEach(btn => {
  btn.addEventListener('click', () => saveSegment(btn.dataset.segmentId));
});

document.querySelectorAll('.synth-seg').forEach(btn => {
  btn.addEventListener('click', async () => {
    btn.disabled = true;
    btn.textContent = '合成中…';
    try {
      // 非同步 job：TTS 呼叫可能需數十秒，不阻塞畫面
      const job = await api('/api/segments/' + btn.dataset.segmentId + '/synthesize', { method: 'POST' });
      const box = document.createElement('div');
      box.className = 'panel';
      box.style.marginTop = '12px';
      btn.parentElement.parentElement.appendChild(box);
      pollJob(job.id, box);
    } catch (e) {
      alert('合成失敗: ' + e.message);
      btn.disabled = false;
      btn.textContent = '重跑';
    }
  });
});

document.getElementById('synthAllBtn').addEventListener('click', async () => {
  const msg = document.getElementById('msg');
  msg.textContent = '已加入合成佇列…';
  try {
    const job = await api('/api/chapters/' + CHAPTER_ID + '/synthesize?onlyPending=true', { method: 'POST' });
    pollJob(job.id, document.getElementById('jobBox') || msg.parentElement);
  } catch (e) {
    msg.textContent = '失敗: ' + e.message;
  }
});

document.getElementById('mergeChapterBtn').addEventListener('click', async () => {
  const msg = document.getElementById('msg');
  if (!CHAPTER_ID) { msg.textContent = '請先選擇章節'; return; }
  try {
    const audio = await api('/api/chapters/' + CHAPTER_ID + '/merge', { method: 'POST' });
    msg.textContent = '合併完成：' + audio.fileName + '（' + fmtDuration(audio.durationMs) + '）';
  } catch (e) {
    msg.textContent = '失敗: ' + e.message;
  }
});
