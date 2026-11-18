/* 段落與音檔（訪客模式：資料在瀏覽器 IndexedDB） */

const NOVEL_ID = (window.NovelTtsPipeline && window.NovelTtsPipeline.novelIdFromPath())
  || novelId();
const CHAPTER_ID = new URLSearchParams(window.location.search).get('chapterId');

/** 目前小說。 */
let NOVEL = null;
/** 目前章節。 */
let CHAPTER = null;

/** 重新載入小說與章節。 */
async function loadData() {
  NOVEL = await window.NovelTtsDB.novels.get(NOVEL_ID);
  if (!NOVEL) return;
  const chapters = NOVEL.chapters || [];
  CHAPTER = chapters.find((c) => String(c.id) === String(CHAPTER_ID)) || chapters[0] || null;
  if (!CHAPTER && chapters.length) CHAPTER = chapters[0];
}

/** 章節下拉選單。 */
function renderChapterSelect() {
  const select = document.getElementById('chapterSelect');
  if (!select) return;
  select.innerHTML = '';
  const chapters = (NOVEL && NOVEL.chapters) || [];
  if (!chapters.length) {
    select.appendChild(el('option', { value: '', text: '（尚無章節）' }));
    return;
  }
  for (const ch of chapters) {
    const url = '/novels/' + NOVEL.id + '/segments?chapterId=' + encodeURIComponent(ch.id);
    const opt = el('option', { value: url, text: '第 ' + ch.index + ' 章 ' + (ch.title || '') });
    if (CHAPTER && String(ch.id) === String(CHAPTER.id)) opt.selected = 'selected';
    select.appendChild(opt);
  }
  select.onchange = () => {
    if (select.value) location.href = select.value;
  };
}

/** 段落列表。 */
async function renderSegments() {
  const tbody = document.getElementById('segRows');
  if (!tbody) return;
  window.NovelTtsPipeline.revokeAudioUrls();
  tbody.innerHTML = '';
  const titleEl = document.getElementById('novelTitle');
  if (titleEl && NOVEL) {
    titleEl.textContent = NOVEL.title;
    titleEl.href = '/novels/' + NOVEL.id;
  }

  const segments = (CHAPTER && CHAPTER.segments) || [];
  if (!segments.length) {
    tbody.appendChild(el('tr', {}, el('td', { colspan: '10', class: 'muted', text: '本章尚無段落，請先執行 AI 分析' })));
    return;
  }
  for (const s of segments) {
    const voiceSelect = el('select', { class: 'seg-voice', 'data-segment-id': s.id });
    voiceSelect.appendChild(el('option', { value: '', text: '（沿用角色）' }));
    window.NovelTtsPipeline.voiceOptions(s.voice || '', false).forEach((o) => voiceSelect.appendChild(o));

    const audio = await window.NovelTtsPipeline.createAudioElement(NOVEL.id, 'SEGMENT', s.id);

    tbody.appendChild(el('tr', {},
      el('td', { text: String(s.seq) }),
      el('td', {}, el('span', { class: 'badge ' + s.type, text: s.type })),
      el('td', { text: s.speakerName || (s.type === 'NARRATION' ? '旁白' : '-') }),
      el('td', { style: 'max-width:420px' },
        el('div', { text: s.text }),
        el('input', {
          type: 'text', class: 'seg-text', value: s.text,
          'data-segment-id': s.id, placeholder: '編輯文字',
        })
      ),
      el('td', { text: s.emotion || '-' }),
      el('td', { text: s.styleHint || '-' }),
      el('td', {}, voiceSelect),
      el('td', {}, el('span', { class: 'badge ' + (s.ttsStatus || 'PENDING'), text: s.ttsStatus || 'PENDING' })),
      el('td', {}, audio ? audio : el('span', { text: '-' })),
      el('td', {},
        el('button', { class: 'btn btn-sm save-seg', 'data-segment-id': s.id, text: '儲存' }),
        ' ',
        el('button', { class: 'btn btn-sm synth-seg', 'data-segment-id': s.id, text: '重跑' })
      )
    ));
  }
}

/** 儲存段落文字與音色。 */
async function saveSegment(id) {
  const text = document.querySelector('.seg-text[data-segment-id="' + id + '"]');
  const voice = document.querySelector('.seg-voice[data-segment-id="' + id + '"]');
  if (!NOVEL || !CHAPTER || !text || !voice) return;
  const s = (CHAPTER.segments || []).find((x) => x.id === id);
  if (!s) return;
  try {
    s.text = text.value;
    s.voice = voice.value || '';
    await window.NovelTtsDB.novels.save(NOVEL);
    text.style.borderColor = '#2f9e44';
    setTimeout(() => { text.style.borderColor = ''; }, 800);
  } catch (e) {
    alert('儲存失敗: ' + e.message);
  }
}

document.getElementById('segRows').addEventListener('click', async (e) => {
  const saveBtn = e.target.closest('.save-seg');
  if (saveBtn) {
    await saveSegment(saveBtn.dataset.segmentId);
    return;
  }
  const synthBtn = e.target.closest('.synth-seg');
  if (synthBtn) {
    const id = synthBtn.dataset.segmentId;
    const s = (CHAPTER && CHAPTER.segments || []).find((x) => x.id === id);
    if (!s || !NOVEL) return;
    if (!requireApiKey()) return;
    synthBtn.disabled = true;
    synthBtn.textContent = '合成中…';
    try {
      await window.NovelTtsPipeline.synthesizeSegment(NOVEL, s);
      await window.NovelTtsDB.novels.save(NOVEL);
      await renderSegments();
      setMsg('段落合成完成', true);
    } catch (err) {
      alert('合成失敗: ' + err.message);
      synthBtn.disabled = false;
      synthBtn.textContent = '重跑';
    }
  }
});

/** 更新訊息。 */
function setMsg(message, ok) {
  const msg = document.getElementById('msg');
  if (!msg) return;
  msg.textContent = message;
  msg.className = ok ? 'muted' : 'muted';
  const box = document.getElementById('jobBox');
  if (box) {
    box.innerHTML = '';
    box.appendChild(el('div', { class: 'alert ' + (ok ? 'alert-success' : 'alert-danger'), text: message }));
  }
}

/* 合成本章待處理段落 */
document.getElementById('synthAllBtn').addEventListener('click', async () => {
  if (!requireApiKey()) return;
  if (!NOVEL || !CHAPTER) { setMsg('請先選擇章節', false); return; }
  const btn = document.getElementById('synthAllBtn');
  btn.disabled = true;
  const box = document.getElementById('jobBox');
  try {
    const segments = CHAPTER.segments || [];
    if (!segments.length) throw new Error('本章尚無段落，請先執行 AI 分析');
    for (let i = 0; i < segments.length; i++) {
      const s = segments[i];
      if (s.ttsStatus === 'DONE') continue;
      if (box) {
        box.innerHTML = '';
        box.appendChild(progressBox(Math.round((i / segments.length) * 100),
          '合成段落 ' + (i + 1) + '/' + segments.length));
      }
      await window.NovelTtsPipeline.synthesizeSegment(NOVEL, s);
    }
    await window.NovelTtsDB.novels.save(NOVEL);
    await renderSegments();
    setMsg('本章合成完成', true);
  } catch (e) {
    setMsg('失敗: ' + e.message, false);
  } finally {
    btn.disabled = false;
  }
});

/* 合併本章 */
document.getElementById('mergeChapterBtn').addEventListener('click', async () => {
  if (!NOVEL || !CHAPTER) { setMsg('請先選擇章節', false); return; }
  const btn = document.getElementById('mergeChapterBtn');
  btn.disabled = true;
  try {
    const blob = await window.NovelTtsPipeline.mergeChapterAudio(NOVEL, CHAPTER);
    await window.NovelTtsDB.novels.save(NOVEL);
    setMsg('合併完成：chapter-' + CHAPTER.index + '.mp3（' + fmtSize(blob.size) + '）', true);
  } catch (e) {
    setMsg('失敗: ' + e.message, false);
  } finally {
    btn.disabled = false;
  }
});

(async () => {
  window.NovelTtsPipeline.showGuestModeNotice('msg');
  await window.NovelTtsPipeline.loadVoices();
  await loadData();
  renderChapterSelect();
  await renderSegments();
})();
