/**
 * 共用 pipeline：小說分析、語音合成、合併、打包。
 *
 * 訪客模式下所有資料都在瀏覽器：
 * - 資料來源：window.NovelTtsDB（IndexedDB）
 * - AI 運算：window.NovelTtsGateway（server 端無狀態閘道）
 * - 純邏輯：window.NovelTtsLogic（切章、TTS 分組、自動指派音色）
 *
 * 這裡把原本 server 端的流程拆成可重用的函式，
 * novels.js / novel-detail.js / characters.js / segments.js 共用。
 */
(function (global) {
  'use strict';

  const DB = window.NovelTtsDB;
  const GW = window.NovelTtsGateway;
  const LOGIC = window.NovelTtsLogic;

  /** 備用音色（server 端音色庫讀不到時使用）。 */
  const FALLBACK_VOICES = [
    { voiceId: 'Kore', displayName: 'Kore（柔和女聲）', gender: '女', styleDesc: '溫柔敘述' },
    { voiceId: 'Aoede', displayName: 'Aoede（清亮女聲）', gender: '女', styleDesc: '清亮' },
    { voiceId: 'Leda', displayName: 'Leda（成熟女聲）', gender: '女', styleDesc: '成熟' },
    { voiceId: 'Puck', displayName: 'Puck（活潑男聲）', gender: '男', styleDesc: '活潑' },
    { voiceId: 'Charon', displayName: 'Charon（低沉男聲）', gender: '男', styleDesc: '低沉' },
    { voiceId: 'Fenrir', displayName: 'Fenrir（有力男聲）', gender: '男', styleDesc: '有力' },
    { voiceId: 'Orus', displayName: 'Orus（嚴肅男聲）', gender: '男', styleDesc: '嚴肅' },
    { voiceId: 'Zephyr', displayName: 'Zephyr（輕快女聲）', gender: '女', styleDesc: '輕快' },
  ];

  let VOICE_CACHE = null;

  /** 已建立的 object URL，重繪或離開頁面時要 revoke。 */
  let AUDIO_URLS = [];

  /** 取得音色清單（server 端共用音色庫；讀不到就用備用清單）。 */
  async function loadVoices() {
    if (VOICE_CACHE) return VOICE_CACHE;
    let rows = [];
    try {
      rows = await window.api('/api/settings/voices');
    } catch (e) {
      rows = [];
    }
    if (!rows || !rows.length) {
      VOICE_CACHE = FALLBACK_VOICES.slice();
    } else {
      VOICE_CACHE = rows.map((v) => ({
        voiceId: v.voiceId,
        displayName: v.displayName,
        gender: v.gender || '',
        styleDesc: v.styleDesc || '',
      }));
    }
    return VOICE_CACHE;
  }

  /** 依 voiceId 找顯示名稱。 */
  function voiceName(voiceId) {
    if (!voiceId) return '';
    const found = (VOICE_CACHE || FALLBACK_VOICES).find((v) => v.voiceId === voiceId);
    return found ? found.displayName : voiceId;
  }

  /** 產生音色下拉選項。 */
  function voiceOptions(selected, includeEmpty, emptyText) {
    const opts = [];
    if (includeEmpty) {
      opts.push(window.el('option', { value: '', text: emptyText || '（沿用角色）' }));
    }
    (VOICE_CACHE || FALLBACK_VOICES).forEach((v) => {
      const attrs = {
        value: v.voiceId,
        text: v.displayName + (v.voiceId ? '（' + v.voiceId + '）' : ''),
      };
      if (selected && selected === v.voiceId) attrs.selected = 'selected';
      opts.push(window.el('option', attrs));
    });
    return opts;
  }

  /** 釋放所有已建立的 object URL。 */
  function revokeAudioUrls() {
    AUDIO_URLS.forEach((u) => {
      try { URL.revokeObjectURL(u); } catch (e) { /* 忽略 */ }
    });
    AUDIO_URLS = [];
  }

  /**
   * 建立一個帶播放器的 audio 元素。
   *
   * @param {string} novelId
   * @param {string} kind  音檔種類：SEGMENT / CHAPTER / NOVEL
   * @param {string} refId
   */
  async function createAudioElement(novelId, kind, refId) {
    const url = await DB.audio.url(novelId, kind, refId);
    if (!url) return null;
    AUDIO_URLS.push(url);
    const audio = window.el('audio', { controls: 'controls' });
    audio.src = url;
    return audio;
  }

  /** 小說章節數與字數。 */
  function novelStats(novel) {
    const chapters = novel.chapters || [];
    const totalChars = chapters.reduce(
      (sum, c) => sum + ((c.content || '').length || 0), 0) || (novel.content || '').length || 0;
    return { chapters: chapters.length, chars: totalChars };
  }

  /** 依角色名稱或別名在群組中找共用角色。 */
  function findGroupCharacter(group, name) {
    if (!group || !name) return null;
    const chars = group.characters || [];
    return chars.find((c) => c.name === name)
      || chars.find((c) => (c.aliases || []).includes(name))
      || null;
  }

  /** 取得小說所屬群組（沒有則 null）。 */
  async function loadGroup(novel) {
    if (!novel || !novel.groupId) return null;
    return await DB.groups.get(novel.groupId);
  }

  /**
   * 段落實際使用的音色。
   * 優先序：段落自訂 → 角色音色 → 群組旁白音色 → 全域預設。
   */
  function resolveVoice(segment, character, group, settings) {
    if (segment.type === 'NARRATION') {
      return (group && group.narratorVoice) || settings.narratorVoice || 'Kore';
    }
    return (character && character.ttsVoice)
      || segment.voice
      || (group && group.narratorVoice)
      || settings.defaultVoice
      || 'Kore';
  }

  /** 段落實際使用的語氣指令。 */
  function resolveInstructions(segment, character, group, settings) {
    return segment.instructions
      || (character && character.ttsInstructions)
      || (group && group.narratorInstructions)
      || settings.globalInstructions
      || '';
  }

  /** 依分析結果建立／更新角色清單，並把段落關聯到角色。 */
  function upsertCharacters(novel, segments) {
    const characters = novel.characters || [];
    const byName = new Map(characters.map((c) => [c.name, c]));
    for (const s of segments) {
      if (s.type !== 'DIALOGUE') continue;
      const name = s.characterName || s.speakerName;
      if (!name) continue;
      let c = byName.get(name);
      if (!c) {
        c = {
          id: window.uid('char'),
          name: name,
          aliases: [],
          ttsVoice: '',
          ttsInstructions: '',
          segmentCount: 0,
          gender: '',
          ageGroup: '',
          personality: '',
          description: '',
        };
        characters.push(c);
        byName.set(name, c);
      }
      c.segmentCount = (c.segmentCount || 0) + 1;
      s.characterId = c.id;
      s.characterName = name;
    }
    novel.characters = characters;
    return characters;
  }

  /**
   * 套用群組共用音色：群組裡有同名（或別名相符）的角色就套用群組設定。
   */
  function applyGroupVoices(novel, group) {
    if (!group) return;
    for (const c of novel.characters || []) {
      const shared = findGroupCharacter(group, c.name);
      if (!shared) continue;
      if (shared.ttsVoice) c.ttsVoice = shared.ttsVoice;
      if (shared.ttsInstructions) c.ttsInstructions = shared.ttsInstructions;
      if (shared.aliases && shared.aliases.length) {
        c.aliases = Array.from(new Set((c.aliases || []).concat(shared.aliases)));
      }
    }
  }

  /**
   * 分析整本小說：切章 → 逐章呼叫閘道分析 → 建立／更新角色 → 套用群組共用音色。
   *
   * @param {object} novel
   * @param {function} onProgress (pct, label)
   */
  async function analyzeNovel(novel, onProgress) {
    const settings = await DB.settings.get();
    const maxChars = settings.chapterMaxChars || 3000;
    const raw = LOGIC.splitChapters(novel.content || '', maxChars);
    if (!raw.length) throw new Error('小說內容是空的，沒有可分析的章節');

    const group = await loadGroup(novel);
    const chapters = [];
    for (let i = 0; i < raw.length; i++) {
      const r = raw[i];
      if (onProgress) onProgress(Math.round((i / raw.length) * 100), '分析第 ' + (i + 1) + '/' + raw.length + ' 章：' + (r.title || ''));
      const result = await GW.analyze({ chapterTitle: r.title, chapterContent: r.content });
      const list = (result && result.segments) || [];
      const chapterId = window.uid('ch');
      const segments = list.map((s, idx) => ({
        id: window.uid('seg'),
        seq: idx + 1,
        chapterId: chapterId,
        type: s.type === 'DIALOGUE' ? 'DIALOGUE' : 'NARRATION',
        characterId: null,
        characterName: s.character_name || null,
        speakerName: s.speaker_name || s.character_name || '旁白',
        text: s.text || '',
        emotion: s.emotion || '',
        styleHint: s.style_hint || '',
        instructions: s.style_instructions || '',
        voice: '',
        ttsStatus: 'PENDING',
      }));
      chapters.push({
        id: chapterId,
        index: r.index,
        title: r.title || ('第 ' + r.index + ' 章'),
        content: r.content,
        charCount: (r.content || '').length,
        status: 'ANALYZED',
        segments: segments,
      });
    }

    novel.chapters = chapters;
    novel.characters = [];
    const allSegments = chapters.flatMap((c) => c.segments);
    upsertCharacters(novel, allSegments);
    applyGroupVoices(novel, group);

    // 段落音色／語氣同步套用群組與角色設定
    const byId = new Map((novel.characters || []).map((c) => [c.id, c]));
    for (const s of allSegments) {
      const c = s.characterId ? byId.get(s.characterId) : null;
      s.voice = resolveVoice(s, c, group, settings);
      s.instructions = resolveInstructions(s, c, group, settings);
    }

    novel.status = 'ANALYZED';
    await DB.novels.save(novel);
    if (onProgress) onProgress(100, '分析完成：' + chapters.length + ' 章 / ' + allSegments.length + ' 段');
    return novel;
  }

  /**
   * 合成單一段落，音檔存進 IndexedDB。
   */
  async function synthesizeSegment(novel, segment, onProgress) {
    const settings = await DB.settings.get();
    const group = await loadGroup(novel);
    const character = (novel.characters || []).find((c) => c.id === segment.characterId) || null;
    const voice = resolveVoice(segment, character, group, settings);
    const instructions = resolveInstructions(segment, character, group, settings);
    segment.voice = voice;
    if (instructions) segment.instructions = instructions;

    const blob = await GW.synthesize([{ text: segment.text, voice: voice, instructions: instructions }]);
    await DB.audio.save(novel.id, 'SEGMENT', segment.id, blob, {
      chapterId: segment.chapterId || '',
      voice: voice,
      fileName: 'seg-' + segment.id + '.mp3',
    });
    segment.ttsStatus = 'DONE';
    if (onProgress) onProgress(100, '段落合成完成');
    return blob;
  }

  /**
   * 合成整章：逐段合成後合併成本章音檔。
   *
   * @param {boolean} onlyPending 只合成尚未完成的段落
   */
  async function synthesizeChapter(novel, chapter, onlyPending, onProgress) {
    const segments = chapter.segments || [];
    if (!segments.length) throw new Error('本章沒有段落，請先執行 AI 分析');
    let done = 0;
    for (const s of segments) {
      if (onlyPending && s.ttsStatus === 'DONE') { done++; continue; }
      if (onProgress) {
        onProgress(Math.round((done / segments.length) * 100), '合成段落 ' + (done + 1) + '/' + segments.length);
      }
      await synthesizeSegment(novel, s);
      done++;
    }
    chapter.status = 'READY';
    await DB.novels.save(novel);
    await mergeChapterAudio(novel, chapter);
    return chapter;
  }

  /**
   * 合併本章：把本章各段音檔合併成一個音檔。
   */
  async function mergeChapterAudio(novel, chapter) {
    const items = [];
    for (const s of chapter.segments || []) {
      const row = await DB.audio.get(novel.id, 'SEGMENT', s.id);
      if (row && row.blob) {
        items.push({ blob: row.blob, name: 'ch' + chapter.index + '-' + s.seq + '.mp3' });
      }
    }
    if (!items.length) throw new Error('本章沒有已合成的音檔');
    const blob = await GW.merge(items);
    await DB.audio.save(novel.id, 'CHAPTER', chapter.id, blob, {
      chapterIndex: chapter.index,
      fileName: 'chapter-' + chapter.index + '.mp3',
    });
    return blob;
  }

  /**
   * 合併整本：把所有章節音檔合併成一個音檔。
   */
  async function mergeNovelAudio(novel) {
    const chapters = novel.chapters || [];
    const items = [];
    for (const ch of chapters) {
      const row = await DB.audio.get(novel.id, 'CHAPTER', ch.id);
      if (row && row.blob) {
        items.push({ blob: row.blob, name: 'chapter-' + ch.index + '.mp3' });
      }
    }
    if (!items.length) {
      // 沒有章節音檔就退回用段落音檔合併
      for (const ch of chapters) {
        for (const s of ch.segments || []) {
          const row = await DB.audio.get(novel.id, 'SEGMENT', s.id);
          if (row && row.blob) items.push({ blob: row.blob, name: 'ch' + ch.index + '-' + s.seq + '.mp3' });
        }
      }
    }
    if (!items.length) throw new Error('整本還沒有任何音檔，請先執行語音合成');
    const blob = await GW.merge(items);
    await DB.audio.save(novel.id, 'NOVEL', novel.id, blob, {
      fileName: (novel.title || 'novel') + '.mp3',
    });
    return blob;
  }

  /**
   * 打包整本為 ZIP。
   */
  async function zipNovel(novel) {
    const chapters = novel.chapters || [];
    const items = [];
    for (const ch of chapters) {
      const row = await DB.audio.get(novel.id, 'CHAPTER', ch.id);
      if (row && row.blob) {
        items.push({ blob: row.blob, name: 'chapter-' + ch.index + '.mp3' });
      }
    }
    if (!items.length) {
      for (const ch of chapters) {
        for (const s of ch.segments || []) {
          const row = await DB.audio.get(novel.id, 'SEGMENT', s.id);
          if (row && row.blob) items.push({ blob: row.blob, name: 'ch' + ch.index + '-' + s.seq + '.mp3' });
        }
      }
    }
    if (!items.length) throw new Error('整本還沒有任何音檔，請先執行語音合成');
    return await GW.zip(items, novel.title || 'novel');
  }

  /**
   * 自動指派音色：依發言次數排序後分配不重複的音色。
   *
   * @returns {number} 指派成功的角色數
   */
  async function autoAssignVoices(novel, overwrite) {
    await loadVoices();
    const voices = (VOICE_CACHE || FALLBACK_VOICES).map((v) => v.voiceId).filter(Boolean);
    if (!voices.length) throw new Error('沒有可用的音色');
    const segments = (novel.chapters || []).flatMap((c) => c.segments || []);
    const map = LOGIC.autoAssignVoices(novel.characters || [], segments, voices);
    let count = 0;
    for (const c of novel.characters || []) {
      const voice = map.get(c.name);
      if (!voice) continue;
      if (c.ttsVoice && !overwrite) continue;
      c.ttsVoice = voice;
      count++;
    }
    // 段落語氣沿用角色
    const byId = new Map((novel.characters || []).map((c) => [c.id, c]));
    for (const s of segments) {
      const c = s.characterId ? byId.get(s.characterId) : null;
      if (c && c.ttsVoice && !s.voice) s.voice = c.ttsVoice;
    }
    await DB.novels.save(novel);
    return count;
  }

  /** 依 id 從 URL 取得小說 id（訪客模式的 id 是字串）。 */
  function novelIdFromPath() {
    const m = window.location.pathname.match(/\/novels\/([^/?#]+)/);
    return m ? decodeURIComponent(m[1]) : null;
  }

  /**
   * 是否為訪客模式（未登入）。
   *
   * 訪客模式的資料在瀏覽器；帳號模式的資料在 server。
   * 這四個頁面已改為讀瀏覽器端資料，所以帳號模式下會看到提示。
   */
  function isGuestMode() {
    const meta = window.document.querySelector('meta[name="_guest_mode"]');
    if (!meta) return true;
    return meta.getAttribute('content') !== 'false';
  }

  /**
   * 在頁面上顯示「此頁已改為訪客模式」的提示（帳號模式才有需要）。
   *
   * @param {string} boxId 提示要放進去的容器 id
   */
  function showGuestModeNotice(boxId) {
    if (isGuestMode()) return;
    const box = window.document.getElementById(boxId);
    if (!box) return;
    const p = window.el('div', { class: 'alert alert-danger' });
    p.appendChild(window.el('strong', { text: '此頁已改為訪客模式。' }));
    p.appendChild(window.el('span', {
      text: ' 小說、章節、角色、段落與音檔都存在你的瀏覽器（IndexedDB），'
        + '不再讀取 server 資料庫。帳號模式的既有資料請改由設定頁的匯出／匯入功能處理。',
    }));
    box.appendChild(p);
  }

  global.NovelTtsPipeline = {
    loadVoices: loadVoices,
    voiceName: voiceName,
    voiceOptions: voiceOptions,
    revokeAudioUrls: revokeAudioUrls,
    createAudioElement: createAudioElement,
    novelStats: novelStats,
    findGroupCharacter: findGroupCharacter,
    loadGroup: loadGroup,
    resolveVoice: resolveVoice,
    resolveInstructions: resolveInstructions,
    upsertCharacters: upsertCharacters,
    applyGroupVoices: applyGroupVoices,
    analyzeNovel: analyzeNovel,
    synthesizeSegment: synthesizeSegment,
    synthesizeChapter: synthesizeChapter,
    mergeChapterAudio: mergeChapterAudio,
    mergeNovelAudio: mergeNovelAudio,
    zipNovel: zipNovel,
    autoAssignVoices: autoAssignVoices,
    novelIdFromPath: novelIdFromPath,
    isGuestMode: isGuestMode,
    showGuestModeNotice: showGuestModeNotice,
  };
})(window);
