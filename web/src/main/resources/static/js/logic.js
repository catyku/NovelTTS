/**
 * 瀏覽器端的純邏輯：章節切分、TTS 分組、角色音色指派。
 *
 * 這三段邏輯原本在 server 端（ChapterSplitter / TtsGroupBuilder / AutoVoiceAssigner）。
 * 因為它們是純函式（不碰資料庫），可以直接搬到瀏覽器端，
 * 讓「資料留在瀏覽器」的架構成立。
 *
 * 與 server 端版本保持行為一致 —— 改這裡也要同步改 Java 端的對應類別。
 */
(function (global) {
  'use strict';

  /** 中文章節標題：第一章 / 第 12 回 / 第三节 */
  const CN_HEADING = /^[\s　]*(第\s*[0-9０-９一二三四五六七八九十百千零兩]+\s*[章回節卷部篇])[^\n]{0,40}$/;
  /** 英文章節標題：Chapter 1 / CHAPTER I */
  const EN_HEADING = /^[\s　]*(CHAPTER|Chapter)\s+[0-9IVXLCivxlc]+[^\n]{0,60}$/;
  /** Markdown 標題：## 第一章 */
  const MD_HEADING = /^[\s　]*#{1,3}\s+\S[^\n]{0,60}$/;

  /** 判斷是否為章節標題行。 */
  function isHeading(line) {
    if (!line || !line.trim()) return false;
    const t = line.trim();
    return CN_HEADING.test(t) || EN_HEADING.test(t) || MD_HEADING.test(t);
  }

  /** 依字數上限切分（找不到標題時使用）。 */
  function splitBySize(text, maxChars) {
    const result = [];
    const paragraphs = text.split(/\n\s*\n/);
    let sb = '';
    // 用區塊編號產生標題，不要用會被 ++ 順延的 index ——
    // 先 push 再 ++ 的寫法會讓第一段拿到「第 2 段」。
    const push = () => {
      const n = result.length + 1;
      result.push({ index: n, title: `第 ${n} 段`, content: sb.trim() });
    };
    for (const p of paragraphs) {
      if (!p.trim()) continue;
      if (sb.length + p.length + 2 > maxChars && sb.length > 0) {
        push();
        sb = '';
      }
      sb += p.trim() + '\n\n';
    }
    if (sb.length > 0) {
      push();
    }
    return result;
  }

  /**
   * 章節切分。
   *
   * @param {string} fullText 完整小說文字
   * @param {number} maxChars 找不到標題時的切分字數上限
   * @returns {Array<{index:number,title:string,content:string}>}
   */
  function splitChapters(fullText, maxChars) {
    const result = [];
    if (!fullText || !fullText.trim()) return result;

    const lines = fullText.split(/\r?\n/);
    const blocks = [];
    const titles = [];
    let start = 0;
    let currentTitle = null;

    for (let i = 0; i < lines.length; i++) {
      if (isHeading(lines[i])) {
        if (i > start) {
          blocks.push([start, i]);
          titles.push(currentTitle);
        }
        start = i;
        currentTitle = lines[i].trim();
      }
    }
    if (start < lines.length) {
      blocks.push([start, lines.length]);
      titles.push(currentTitle);
    }

    if (blocks.length <= 1) {
      return splitBySize(fullText, maxChars);
    }

    let index = 1;
    for (let b = 0; b < blocks.length; b++) {
      // 區塊第一行是標題，已存入 title；留在 content 會被 AI 當成旁白念出來。
      let from = blocks[b][0];
      if (from < blocks[b][1] && isHeading(lines[from])) {
        from++;
      }
      const parts = [];
      for (let i = from; i < blocks[b][1]; i++) {
        parts.push(lines[i]);
      }
      const content = parts.join('\n').trim();
      if (!content) continue;
      result.push({ index: index++, title: titles[b], content: content });
    }
    return result;
  }

  /**
   * 依角色身分把段落分組成 multi-speaker TTS 請求。
   *
   * 分組規則（與 server 端 TtsGroupBuilder 一致）：
   * 1. 依「說話者」切 turn：同一說話者連續多段合併為一個 turn。
   *    判斷說話者的依據是「角色身分」，不能只比音色與語氣 —— 兩個不同角色
   *    若恰好音色與語氣相同（常見於尚未指派音色的角色），只看 voice/instructions
   *    會被合併，造成同一音色念出兩個人的對話。
   * 2. 再依 group 字數上限切分；單一 turn 超過上限時獨自成組。
   *
   * @param {Array} segments 段落（需含 type / characterId / speakerName / text / voice / instructions）
   * @param {object} groupContext {narratorVoiceId, narratorInstructions}
   * @param {number} maxChars group 字數上限
   */
  function buildTtsGroups(segments, groupContext, maxChars) {
    const groups = [];
    if (!segments || !segments.length) return groups;
    const ctx = groupContext || {};

    const resolveVoice = (s) => {
      if (s.type === 'NARRATION') {
        return ctx.narratorVoiceId || s.voice || null;
      }
      return s.voice || ctx.narratorVoiceId || null;
    };
    const resolveInstructions = (s) => {
      if (s.type === 'NARRATION') {
        return ctx.narratorInstructions || s.instructions || null;
      }
      return s.instructions || ctx.narratorInstructions || null;
    };
    // 說話者身分：對話用角色 id，旁白用固定標記
    const resolveSpeaker = (s) => {
      if (s.type === 'NARRATION') return '__NARRATION__';
      return s.characterId || s.characterName || s.speakerName || '__UNKNOWN__';
    };

    // 1. 切 turn
    const drafts = [];
    for (const s of segments) {
      const voice = resolveVoice(s);
      const instructions = resolveInstructions(s);
      const speaker = resolveSpeaker(s);
      const last = drafts[drafts.length - 1];
      if (last && last.speaker === speaker && last.voice === voice && last.instructions === instructions) {
        last.text += '\n' + (s.text || '');
        last.segments.push(s);
        continue;
      }
      drafts.push({
        speaker, voice, instructions,
        text: s.text || '',
        segments: [s],
      });
    }

    // 2. 切 group
    let currentTurns = [];
    let currentSegments = [];
    let currentChars = 0;
    const limit = Math.max(1, maxChars || 3000);

    const buildGroup = (segs, turns, chars) => ({
      turns: turns.map((t) => ({ text: t.text, voice: t.voice, instructions: t.instructions })),
      segmentIds: segs.map((s) => s.id),
      charCount: chars,
    });

    for (const draft of drafts) {
      const draftChars = draft.text.length;
      const overflow = draftChars > limit;
      if (currentTurns.length && (currentChars + draftChars > limit || overflow)) {
        groups.push(buildGroup(currentSegments, currentTurns, currentChars));
        currentTurns = [];
        currentSegments = [];
        currentChars = 0;
      }
      currentTurns.push(draft);
      currentSegments = currentSegments.concat(draft.segments);
      currentChars += draftChars;
    }
    if (currentTurns.length) {
      groups.push(buildGroup(currentSegments, currentTurns, currentChars));
    }
    return groups;
  }

  /**
   * 自動指派音色。
   *
   * 依角色在文中的出現頻率排序，依次分配音色（不重複），
   * 讓主要角色有固定且互異的音色。
   *
   * @param {Array} characters 角色（含 name）
   * @param {Array} segments 段落（含 characterName / speakerName）
   * @param {Array} voices 可用音色（voiceId）
   * @returns {Map<string,string>} 角色名 → voiceId
   */
  function autoAssignVoices(characters, segments, voices) {
    const result = new Map();
    if (!voices || !voices.length) return result;

    // 統計各角色的發言次數
    const counts = new Map();
    for (const s of segments || []) {
      const name = s.characterName || s.speakerName;
      if (!name) continue;
      counts.set(name, (counts.get(name) || 0) + 1);
    }

    // 依出現頻率排序；未發言的角色排在最後但仍佔用音色
    const ordered = (characters || []).slice().sort((a, b) => {
      const ca = counts.get(a.name) || 0;
      const cb = counts.get(b.name) || 0;
      if (ca !== cb) return cb - ca;
      return (a.name || '').localeCompare(b.name || '');
    });

    ordered.forEach((c, i) => {
      const voice = voices[i % voices.length];
      if (voice) {
        result.set(c.name, voice);
      }
    });
    return result;
  }

  window.NovelTtsLogic = {
    splitChapters: splitChapters,
    buildTtsGroups: buildTtsGroups,
    autoAssignVoices: autoAssignVoices,
    isHeading: isHeading,
  };
})(window);
