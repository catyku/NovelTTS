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

  /**
   * 取得音色清單。
   *
   * <p>優先用使用者自己的音色庫（IndexedDB，可從 OpenRouter 匯入、可自訂名稱與分類）；
   * 空的話才退回 server 的共用音色庫，再不行就用內建備援清單。
   */
  async function loadVoices() {
    if (VOICE_CACHE) return VOICE_CACHE;
    let rows = [];
    try {
      if (window.NovelTtsDB && window.NovelTtsDB.voices) {
        rows = await window.NovelTtsDB.voices.listEnabled();
      }
    } catch (e) {
      rows = [];
    }
    if (!rows || !rows.length) {
      try {
        const server = await window.api('/api/settings/voices');
        if (Array.isArray(server) && server.length) {
          rows = server.map((v) => ({
            voiceId: v.voiceId,
            displayName: v.displayName,
            gender: v.gender || '',
            styleDesc: v.styleDesc || '',
            model: v.model || '',
          }));
        }
      } catch (e) {
        rows = [];
      }
    }
    if (!rows || !rows.length) {
      VOICE_CACHE = FALLBACK_VOICES.slice();
    } else {
      VOICE_CACHE = rows.map((v) => ({
        voiceId: v.voiceId,
        displayName: v.displayName || v.voiceId,
        gender: v.gender || '',
        styleDesc: v.styleDesc || '',
        model: v.model || '',
      }));
    }
    return VOICE_CACHE;
  }

  /** 清掉音色快取（音色庫改動後要呼叫，讓下次重新載入）。 */
  function clearVoiceCache() {
    VOICE_CACHE = null;
  }

  /** 依 voiceId 找顯示名稱。 */
  function voiceName(voiceId) {
    if (!voiceId) return '';
    const found = (VOICE_CACHE || FALLBACK_VOICES).find((v) => v.voiceId === voiceId);
    return found ? found.displayName : voiceId;
  }

  /**
   * 產生音色下拉選項。
   *
   * <p>用 optgroup 依模型分組 —— 音色庫可能有數百筆（OpenRouter 上
   * Gemini 30 個、Deepgram 90 個、MAI 97 個…），不分組根本找不到想要的。
   *
   * <p>分組也帶出另一個重要資訊：voice_id 是「模型內」的概念，
   * Gemini 的 Kore 不能用在 ElevenLabs 上，所以分組讓人不會選錯。
   */
  function voiceOptions(selected, includeEmpty, emptyText) {
    const opts = [];
    if (includeEmpty) {
      opts.push(window.el('option', { value: '', text: emptyText || '（沿用角色）' }));
    }
    const voices = VOICE_CACHE || FALLBACK_VOICES;

    // 依模型分組，組內依 sortOrder
    const groups = new Map();
    for (const v of voices) {
      const key = v.model || '';
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(v);
    }

    for (const [model, list] of groups) {
      const label = model ? (MODEL_LABELS[model] || model) : '未指定模型';
      const group = window.el('optgroup', { label: label });
      for (const v of list) {
        const attrs = {
          value: v.voiceId,
          text: v.displayName + (v.voiceId ? '（' + v.voiceId + '）' : ''),
        };
        if (selected && selected === v.voiceId) attrs.selected = 'selected';
        group.appendChild(window.el('option', attrs));
      }
      opts.push(group);
    }
    return opts;
  }

  /**
   * 模型 id → 中文名。
   *
   * <p>放在這裡而不是只放在 voices.js，是因為所有用到音色下拉的頁面
   * （角色、段落、小說詳情）不一定載入 voices.js。
   */
  const MODEL_LABELS = {
    'google/gemini-3.8-flash-tts': 'Gemini 3.8 Flash TTS',
    'google/gemini-3.8-flash-lite-tts': 'Gemini 3.8 Flash Lite TTS',
    'google/gemini-3.1-flash-tts-preview': 'Gemini 3.1 Flash TTS',
    'mistralai/voxtral-mini-tts-3-0': 'Voxtral Mini TTS 3',
    'mistralai/voxtral-mini-tts-2603': 'Voxtral Mini TTS',
    'elevenlabs/eleven-v3': 'ElevenLabs v3',
    'elevenlabs/eleven-v4': 'ElevenLabs v4',
    'elevenlabs/eleven-turbo-v2.5': 'ElevenLabs Turbo v2.5',
    'elevenlabs/eleven-flash-v2.5': 'ElevenLabs Flash v2.5',
    'elevenlabs/eleven-multilingual-v2': 'ElevenLabs Multilingual v2',
    'microsoft/mai-voice-2.1': 'MAI Voice 2.1',
    'microsoft/mai-voice-2.1-flash': 'MAI Voice 2.1 Flash',
    'microsoft/mai-voice-2': 'MAI Voice 2',
    'microsoft/mai-voice-2-flash': 'MAI Voice 2 Flash',
    'deepgram/aura-2': 'Deepgram Aura 2',
    'deepgram/flux-tts': 'Deepgram Flux',
    'hexgrad/kokoro-82m': 'Kokoro 82M',
    'sesame/csm-1b': 'Sesame CSM-1B',
    'speechify/simba-3.5': 'Speechify Simba 3.5',
    'canopylabs/orpheus-3b-0.1-ft': 'Orpheus 3B',
  };

  /** 找出某個音色所屬的模型。
   *
   * <p>voice_id 是模型內的概念 —— 合成時必須用音色所屬的模型，
   * 否則 EitherLabs 會找不到這個 voice 而報錯，或音色跟對不上。
   *
   * @param {string} voiceId
   * @returns {string} 模型 id；查不到回傳空字串
   */
  async function modelOfVoice(voiceId) {
    if (!voiceId) return '';
    const voices = VOICE_CACHE || FALLBACK_VOICES;
    const matches = voices.filter((v) => v.voiceId === voiceId);
    if (!matches.length) return '';
    // 同一個 voice_id 可能出現在多個模型（例如 Charon 在三個 Gemini 版本都有）。
    // 優先選目前設定的 TTS 模型，否則使用者設定的模型跟實際合成用的模型會不一致。
    try {
      const settings = await DB.settings.get();
      const preferred = matches.find((v) => v.model === settings.ttsModel);
      if (preferred) return preferred.model;
    } catch (e) { /* 忽略 */ }
    return matches[0].model || '';
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
   * 章節的唯一鍵：index + 內容的簡易雜湊。
   *
   * <p>用來判斷「這一章跟上次分析的是同一章」——
   * 內容沒變就沿用結果，內容改了才重新分析。
   */
  function chapterKey(chapter) {
    const content = chapter.content || '';
    let hash = 0;
    for (let i = 0; i < content.length; i++) {
      hash = ((hash << 5) - hash + content.charCodeAt(i)) | 0;
    }
    return chapter.index + ':' + (hash >>> 0) + ':' + content.length;
  }

  /**
   * 分析單一章節 → 章節物件（含段落）。
   *
   * @param {object} r splitChapters 的單一章節
   */
  async function analyzeOneChapter(r) {
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
    return {
      id: chapterId,
      index: r.index,
      title: r.title || ('第 ' + r.index + ' 章'),
      content: r.content,
      charCount: (r.content || '').length,
      status: 'ANALYZED',
      segments: segments,
    };
  }

  /**
   * 分析整本小說：切章 → 逐章呼叫閘道分析 → 建立／更新角色 → 套用群組共用音色。
   *
   * <p><b>可續傳</b>：每分析完一章就存回 IndexedDB，並在
   * {@code novel.analysisProgress} 記錄進度。中途失敗（網路、額度、
   * 上游偶發錯誤）時，已完成的章節不會遺失；再次按分析會從中斷處接續，
   * 不會從頭再跑一遍。
   *
   * <p>單章失敗不中斷整本 —— 那一章標記為 FAILED 後繼續下一章，
   * 最後統一回報有哪幾章沒成功。
   *
   * <p>{@code opts.force = true} 時忽略已完成的章節，全部重跑。
   *
   * @param {object} novel
   * @param {function} onProgress (pct, label)
   * @returns {Promise<object>} 分析後的小說（含 analysisProgress）
   */
  async function analyzeNovel(novel, onProgress, opts) {
    const options = opts || {};
    const force = options.force === true;
    const settings = await DB.settings.get();
    const maxChars = settings.chapterMaxChars || 3000;
    const raw = LOGIC.splitChapters(novel.content || '', maxChars);
    if (!raw.length) throw new Error('小說內容是空的，沒有可分析的章節');

    const group = await loadGroup(novel);

    // 續傳：沿用先前已分析完成的章節。
    // 章節以「index + 內容雜湊」配對 —— 使用者改了內容就會重新分析那一章。
    const previous = Array.isArray(novel.chapters) ? novel.chapters : [];
    const prevByKey = new Map(previous.map((c) => [chapterKey(c), c]));
    const chapters = [];
    const failed = [];

    for (let i = 0; i < raw.length; i++) {
      const r = raw[i];
      const key = chapterKey({ index: r.index, content: r.content });
      const done = prevByKey.get(key);

      if (!force && done && done.status === 'ANALYZED' && (done.segments || []).length) {
        // 這章上次已經分析成功 —— 直接沿用，不重跑
        chapters.push(done);
        novel.analysisProgress = {
          done: chapters.length, total: raw.length, failed: failed.slice(),
          updatedAt: Date.now(),
        };
        await DB.novels.save(novel);
        if (onProgress) {
          onProgress(Math.round((chapters.length / raw.length) * 100),
            '分析第 ' + (i + 1) + '/' + raw.length + ' 章（沿用已完成）');
        }
        continue;
      }

      if (onProgress) {
        onProgress(Math.round((i / raw.length) * 100),
          '分析第 ' + (i + 1) + '/' + raw.length + ' 章：' + (r.title || ''));
      }

      let chapter;
      try {
        chapter = await analyzeOneChapter(r);
      } catch (e) {
        // 單章失敗不中斷整本
        failed.push({ index: r.index, title: r.title || '', message: String(e.message || e) });
        chapter = {
          id: window.uid('ch'),
          index: r.index,
          title: r.title || ('第 ' + r.index + ' 章'),
          content: r.content,
          charCount: (r.content || '').length,
          status: 'FAILED',
          error: String(e.message || e),
          segments: [],
        };
      }
      chapters.push(chapter);

      // 每章完成就存檔 —— 中途中斷也不會遺失已完成的章節
      novel.chapters = chapters;
      novel.analysisProgress = {
        done: chapters.length,
        total: raw.length,
        failed: failed.slice(),
        updatedAt: Date.now(),
      };
      await DB.novels.save(novel);
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

    novel.status = failed.length ? 'PARTIAL' : 'ANALYZED';
    novel.analysisProgress = {
      done: chapters.length,
      total: raw.length,
      failed: failed.slice(),
      updatedAt: Date.now(),
    };
    await DB.novels.save(novel);

    if (failed.length) {
      // 有章節失敗 → 說明是哪幾章、為什麼，讓使用者知道再按一次能續傳
      const detail = failed.map((f) => '第 ' + f.index + ' 章：' + f.message).join('；');
      throw new Error('有 ' + failed.length + ' 章分析失敗（已完成的章節都保留，'
        + '再次按「觸發 AI 分析」會從中斷處繼續）：' + detail);
    }

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

    // 音色必須搭配它所屬的模型 —— voice_id 是模型內的概念，
    // 用錯模型會找不到這個 voice（400），或出來的音色跟選的不一樣。
    // modelOfVoice 是 async（要讀設定才能判斷優先模型），一定要 await。
    const voiceModel = await modelOfVoice(voice);
    const blob = await GW.synthesize(
      [{ text: segment.text, voice: voice, instructions: instructions }],
      (typeof voiceModel === 'string' && voiceModel) ? { model: voiceModel } : undefined
    );
    const durationMs = await DB.audio.durationMs(blob);
    await DB.audio.save(novel.id, 'SEGMENT', segment.id, blob, {
      chapterId: segment.chapterId || '',
      voice: voice,
      fileName: segment.id + '.mp3',
      durationMs: durationMs,
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
    const durationMs = await DB.audio.durationMs(blob);
    await DB.audio.save(novel.id, 'CHAPTER', chapter.id, blob, {
      chapterIndex: chapter.index,
      fileName: 'chapter-' + chapter.index + '.mp3',
      durationMs: durationMs,
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
    const durationMs = await DB.audio.durationMs(blob);
    await DB.audio.save(novel.id, 'NOVEL', novel.id, blob, {
      fileName: (novel.title || 'novel') + '.mp3',
      durationMs: durationMs,
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
    clearVoiceCache: clearVoiceCache,
    modelOfVoice: modelOfVoice,
    modelLabel: function (id) { return MODEL_LABELS[id] || id; },
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
    analyzeOneChapter: analyzeOneChapter,
    chapterKey: chapterKey,
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
