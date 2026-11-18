/**
 * 產生說明頁所需的示範資料與截圖素材。
 *
 * 走真實 OpenRouter，產出與說明頁截圖一致的內容：
 * 兩集小說（同一群組）、共用角色套用、段落與章節音檔。
 *
 * 產出：
 *   /tmp/guide-data.json  — 示範資料（含音檔的 base64，供截圖時注入瀏覽器）
 */
const fs = require('fs');
const path = require('path');
const { indexedDB } = require('fake-indexeddb');

global.window = global;
global.indexedDB = indexedDB;
global.localStorage = (() => {
  const m = new Map();
  return {
    getItem: (k) => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => m.set(k, String(v)),
    removeItem: (k) => m.delete(k),
  };
})();
global.navigator = global.navigator || {};
global.URL.createObjectURL = (blob) => 'blob:mock/' + Math.random();
global.URL.revokeObjectURL = () => {};

function makeEl(tag) {
  return {
    tagName: tag, children: [], attributes: {}, style: {}, dataset: {},
    className: '', value: '', href: '', innerHTML: '', textContent: '', selected: false,
    appendChild(c) { this.children.push(c); return c; },
    setAttribute(k, v) { this.attributes[k] = v; },
    getAttribute(k) { return this.attributes[k]; },
    addEventListener() {}, removeChild(c) { this.children = this.children.filter((x) => x !== c); },
    closest() { return null; }, querySelector() { return null; }, querySelectorAll() { return []; },
  };
}
global.document = {
  createElement: makeEl, querySelector: () => null, querySelectorAll: () => [],
  getElementById: () => null, addEventListener: () => {},
};

const JS_DIR = path.resolve(__dirname, '../../web/src/main/resources/static/js');
for (const f of ['db.js', 'logic.js', 'gateway.js', 'common.js', 'pipeline.js']) {
  (0, eval)(fs.readFileSync(path.join(JS_DIR, f), 'utf8'));
}
for (const name of ['api', 'el', 'uid', 'fmtDuration', 'fmtSize', 'showError', 'showOk',
                    'progressBox', 'downloadBlob', 'pickTextFile', 'requireApiKey']) {
  if (typeof global[name] === 'function') window[name] = global[name];
}

const DB = global.NovelTtsDB;
const GW = global.NovelTtsGateway;
const P = global.NovelTtsPipeline;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  const key = process.env.OPENROUTER_API_KEY;
  if (!key) { console.log('需要 OPENROUTER_API_KEY'); process.exit(2); }
  GW.setApiBase(process.env.GATEWAY_URL || 'http://127.0.0.1:8090/api/gateway');
  DB.credentials.set(key);

  // 群組
  await DB.groups.save({
    id: 'demo_group', title: '燈火重亮', author: '示範作者',
    description: '同一本連載小說的各集，共用角色音色與旁白音色',
    narratorVoice: 'Kore', narratorInstructions: '語氣沉穩，敘述節奏平緩',
    characters: [
      { id: 'dc1', name: '林晚', aliases: ['晚晚', '小林'], ttsVoice: 'Aoede', ttsInstructions: '語氣清冷，語速偏慢' },
      { id: 'dc2', name: '陳硯', aliases: ['陳先生'], ttsVoice: 'Charon', ttsInstructions: '語氣低沉，話少，帶點冷幽默' },
    ],
  });

  const contents = [
    ['第一集', '第一章 夜歸\n\n林晚推開舊書店的大門，銅鈴響了一聲。\n她低聲說：「今晚風很大，我以為你不會來。」\n陳硯沒有回頭。\n「我從來沒說過我不會來。」\n\n第二章 舊信\n\n第二天清晨，林晚帶著一封信回來。\n陳硯推開窗戶：「你先坐下，風大。」'],
    ['第二集', '第三章 舊信\n\n林晚拆開那封信，指尖微微發抖。\n「你早就知道了，對不對。」\n陳硯終於轉過身來。\n「我等了很久，才等到你願意問。」\n\n第四章 雨\n\n窗外下起雨來。林晚收起信，抬頭看他。\n「那就再等等吧。」'],
  ];

  const result = { novels: [], audio: [] };

  for (let i = 0; i < contents.length; i++) {
    const [label, content] = contents[i];
    const novel = await DB.novels.save({
      id: 'demo_n' + (i + 1), title: '燈火重亮 ' + label, author: '示範作者',
      content, groupId: 'demo_group', groupOrder: i + 1,
      chapters: [], characters: [], status: 'IMPORTED',
    });

    await P.analyzeNovel(novel, () => {});
    console.log(`${label} 分析完成：${novel.chapters.length} 章 / ` +
      `${novel.chapters.flatMap(c => c.segments || []).length} 段`);

    // 逐段合成（TTS 端點有上游限流，需重試）
    for (const ch of novel.chapters) {
      for (const seg of ch.segments || []) {
        for (let attempt = 0; attempt < 5; attempt++) {
          try {
            await P.synthesizeSegment(novel, seg, () => {});
            break;
          } catch (e) {
            if (attempt === 4) { console.log(`  段落合成放棄：${e.message}`); continue; }
            await sleep(15000);
          }
        }
        await sleep(1200);
      }
    }
    // 合併本章
    try {
      await P.mergeChapterAudio(novel, novel.chapters[0]);
    } catch (e) {
      console.log(`  合併失敗：${e.message}`);
    }

    const audios = await DB.audio.listOfNovel(novel.id);
    console.log(`  音檔：${audios.length} 個`);
    for (const a of audios) {
      if (a.blob) {
        const buf = Buffer.from(await a.blob.arrayBuffer());
        // listOfNovel 回傳的 row 上，meta 的欄位是平鋪的（durationMs、voice…）
        const meta = {};
        for (const k of ['durationMs', 'voice', 'fileName', 'chapterIndex', 'chapterId']) {
          if (a[k] !== undefined) meta[k] = a[k];
        }
        result.audio.push({
          novelId: a.novelId, kind: a.kind, refId: a.refId,
          size: a.size, meta: meta,
          b64: buf.toString('base64'),
        });
      }
    }
    result.novels.push(novel);
  }

  fs.writeFileSync('/tmp/guide-data.json', JSON.stringify(result));
  console.log(`\n已寫出 /tmp/guide-data.json（${result.audio.length} 個音檔）`);
}

main().catch((e) => { console.error('失敗:', e); process.exit(1); });
