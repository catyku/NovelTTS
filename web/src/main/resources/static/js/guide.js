/* 說明頁的「試試看」區塊：顯示範例內容、匯入、移除 */
(function () {
  'use strict';

  const box = document.getElementById('demoBox');
  if (!box || !window.NovelTtsDemo) return;

  const btnImport = document.getElementById('demoImport');
  const btnRemove = document.getElementById('demoRemove');
  const progressWrap = document.getElementById('demoProgress');
  const bar = document.getElementById('demoBar');
  const progressText = document.getElementById('demoProgressText');
  const elSummary = document.getElementById('demoSummary');
  const elChars = document.getElementById('demoChars');
  const elAudio = document.getElementById('demoAudio');

  function fmtDuration(ms) {
    if (ms == null) return '—';
    const s = Math.round(ms / 1000);
    return Math.floor(s / 60) + ' 分 ' + (s % 60) + ' 秒';
  }

  /** 依 refId 找出對應的段落，得到說話者與音色。 */
  function findSegment(novels, refId) {
    for (const n of novels) {
      for (const ch of n.chapters || []) {
        for (const s of ch.segments || []) {
          if (s.id === refId) {
            const chara = (n.characters || []).find((c) => c.id === s.characterId);
            return {
              novel: n,
              segment: s,
              speaker: s.speakerName || s.characterName || '旁白',
              voice: s.voice || (chara && chara.ttsVoice) || 'Kore',
            };
          }
        }
      }
    }
    return null;
  }

  async function render() {
    const demo = await window.NovelTtsDemo.load();

    const totalChars = (demo.novels.reduce((n, b) => n + (b.characters || []).length, 0));
    const totalSegs = demo.novels.reduce((n, b) =>
      n + (b.chapters || []).reduce((m, c) => m + (c.segments || []).length, 0), 0);
    elSummary.textContent =
      demo.novels.length + ' 集 · ' + totalSegs + ' 段 · ' + totalChars + ' 位角色 · ' +
      demo.audio.length + ' 個音檔（可直接試聽）';

    // 群組共用角色
    elChars.innerHTML = '';
    for (const c of demo.group.characters || []) {
      const row = document.createElement('div');
      row.className = 'guide-demo-char';
      const name = document.createElement('span');
      name.className = 'name';
      name.textContent = c.name;
      const al = document.createElement('span');
      al.className = 'aliases';
      al.textContent = (c.aliases || []).join('、') || '';
      const v = document.createElement('span');
      v.className = 'voice';
      v.textContent = c.ttsVoice || '—';
      row.appendChild(name);
      row.appendChild(al);
      row.appendChild(v);
      elChars.appendChild(row);
    }
    const narrator = document.createElement('div');
    narrator.className = 'guide-demo-char';
    narrator.innerHTML = '<span class="name">旁白</span>' +
      '<span class="aliases">跨集共用</span>' +
      '<span class="voice"></span>';
    narrator.querySelector('.voice').textContent = demo.group.narratorVoice || '—';
    elChars.appendChild(narrator);

    // 試聽清單：取第一集的段落音檔
    elAudio.innerHTML = '';
    const segs = demo.audio.filter((a) => a.kind === 'SEGMENT');
    for (const entry of segs.slice(0, 6)) {
      const info = findSegment(demo.novels, entry.refId);
      const row = document.createElement('div');
      row.className = 'guide-demo-audio';
      const label = document.createElement('span');
      label.className = 'label';
      label.textContent = info ? info.speaker : '旁白';
      const tag = document.createElement('span');
      tag.className = 'voice-tag';
      tag.textContent = info ? info.voice : '';
      label.appendChild(tag);
      const audio = document.createElement('audio');
      audio.controls = true;
      // preload="metadata" 讓播放器顯示時長（0:00 看起來像壞掉）
      audio.preload = 'metadata';
      audio.src = window.NovelTtsDemo.audioUrl(entry);
      row.appendChild(label);
      row.appendChild(audio);
      elAudio.appendChild(row);
    }

    // 已經匯入過？
    const imported = await window.NovelTtsDemo.hasImported();
    btnRemove.style.display = imported ? '' : 'none';
    btnImport.textContent = imported ? '重新匯入範例' : '把範例匯入我的瀏覽器';
  }

  btnImport.addEventListener('click', async () => {
    btnImport.disabled = true;
    progressWrap.style.display = '';
    try {
      const report = await window.NovelTtsDemo.importAll((done, total) => {
        bar.style.width = Math.round((done / total) * 100) + '%';
        progressText.textContent = '匯入音檔 ' + done + ' / ' + total;
      });
      progressText.textContent = '匯入完成：'
        + report.novels + ' 集、' + report.audio + ' 個音檔';
      window.location.href = '/novels';
    } catch (e) {
      progressText.textContent = '匯入失敗：' + e.message;
      btnImport.disabled = false;
    }
  });

  btnRemove.addEventListener('click', async () => {
    btnRemove.disabled = true;
    try {
      await window.NovelTtsDemo.removeAll();
      btnRemove.style.display = 'none';
      btnImport.textContent = '把範例匯入我的瀏覽器';
      progressWrap.style.display = 'none';
    } catch (e) {
      alert('移除失敗：' + e.message);
    } finally {
      btnRemove.disabled = false;
    }
  });

  render().catch((e) => {
    elSummary.textContent = '範例載入失敗：' + e.message;
  });
})();
