/**
 * 完整 pipeline 端到端測試：瀏覽器資料層 + pipeline.js → 閘道 → 真實 OpenRouter。
 *
 * 測的是實際部署到 web/src/main/resources/static/js/ 的檔案。
 * 需要執行中的應用（預設 http://127.0.0.1:8090）與有效的 OPENROUTER_API_KEY。
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

/** 極簡 DOM stub：pipeline.js 只用到 createElement 的部分功能。 */
function makeEl(tag) {
  const node = {
    tagName: tag, children: [], attributes: {}, style: {}, dataset: {},
    className: '', value: '', href: '', innerHTML: '', textContent: '', selected: false,
    appendChild(c) { this.children.push(c); return c; },
    setAttribute(k, v) { this.attributes[k] = v; },
    getAttribute(k) { return this.attributes[k]; },
    addEventListener() {},
    removeChild(c) { this.children = this.children.filter((x) => x !== c); },
    closest() { return null; },
    querySelector() { return null; },
    querySelectorAll() { return []; },
  };
  return node;
}
global.document = {
  createElement: makeEl,
  querySelector: () => null,
  querySelectorAll: () => [],
  getElementById: () => null,
  addEventListener: () => {},
};

const JS_DIR = path.resolve(__dirname, '../../web/src/main/resources/static/js');
for (const f of ['db.js', 'logic.js', 'gateway.js', 'common.js', 'pipeline.js']) {
  // indirect eval：函式宣告會掛到 global，模擬瀏覽器的全域語意
  const src = fs.readFileSync(path.join(JS_DIR, f), 'utf8');
  (0, eval)(src);
}
// common.js 以 function 宣告掛在 eval 作用域，明式掛到 window 供 pipeline.js 使用
// （瀏覽器裡 function 宣告本身就是 window 的成員，但 eval 作用域不同）
for (const name of ['api', 'el', 'uid', 'fmtDuration', 'fmtSize', 'showError', 'showOk',
                    'progressBox', 'downloadBlob', 'pickTextFile', 'requireApiKey',
                    'csrfToken', 'csrfHeaderName', 'novelId']) {
  if (typeof global[name] === 'function') {
    window[name] = global[name];
  }
}

const DB = global.NovelTtsDB;
const GW = global.NovelTtsGateway;
const P = global.NovelTtsPipeline;

let failures = 0;
const check = (name, cond, detail) => {
  console.log(`${cond ? '  ✓' : '  ✗'} ${name}${detail ? ' — ' + detail : ''}`);
  if (!cond) failures++;
};

async function main() {
  const key = process.env.OPENROUTER_API_KEY;
  if (!key) {
    console.log('需要 OPENROUTER_API_KEY 環境變數');
    process.exit(2);
  }
  GW.setApiBase(process.env.GATEWAY_URL || 'http://127.0.0.1:8090/api/gateway');
  DB.credentials.set(key);

  console.log('\n=== 1. 匯入小說 ===');
  const novel = await DB.novels.save({
    id: 'e2e',
    title: '燈火重亮',
    author: '示範作者',
    content: '第一章 夜歸\n\n林晚推開舊書店的大門，銅鈴響了一聲。\n她低聲說：「今晚風很大，我以為你不會來。」\n陳硯沒有回頭。\n「我從來沒說過我不會來。」\n\n第二章 舊信\n\n第二天清晨，林晚帶著一封信回來。\n陳硯推開窗戶：「你先坐下，風大。」',
    chapters: [],
    characters: [],
    status: 'IMPORTED',
  });
  check('小說已存入 IndexedDB', !!novel.id);

  console.log('\n=== 2. AI 分析（真實 OpenRouter）===');
  const progress = [];
  await P.analyzeNovel(novel, (p) => progress.push(p));
  check('有進度回報', progress.length > 0, `${progress.length} 次`);
  check('切成 2 章', novel.chapters.length === 2, `實際 ${novel.chapters.length}`);
  check('章節標題正確', novel.chapters[0].title.includes('夜歸'), novel.chapters[0].title);

  const segs = novel.chapters.flatMap((c) => c.segments || []);
  check('有段落', segs.length > 0, `${segs.length} 段`);
  const narration = segs.filter((s) => s.type === 'NARRATION');
  const dialogue = segs.filter((s) => s.type === 'DIALOGUE');
  check('有旁白段落', narration.length > 0, `${narration.length} 段`);
  check('有對話段落', dialogue.length > 0, `${dialogue.length} 段`);
  check('角色已建立', novel.characters.length > 0,
    novel.characters.map((c) => c.name).join(', '));

  console.log('\n=== 3. 語音合成（真實 OpenRouter）===');
  await P.synthesizeChapter(novel, novel.chapters[0], false, (p) => {});
  const audios = await DB.audio.listOfNovel(novel.id);
  check('音檔已存 IndexedDB', audios.length > 0, `${audios.length} 個`);
  check('音檔大小合理', audios.every((a) => a.size > 1000),
    audios.map((a) => `${a.kind}:${a.size}B`).join(' '));
  const segAudio = audios.find((a) => a.kind === 'SEGMENT');
  if (segAudio && segAudio.blob) {
    const head = Buffer.from(await segAudio.blob.slice(0, 3).arrayBuffer());
    check('MP3 標頭正確', head.toString('ascii') === 'ID3' || head[0] === 0xff,
      head.toString('hex'));
  }

  console.log('\n=== 4. 合併本章 ===');
  const merged = await P.mergeChapterAudio(novel, novel.chapters[0]);
  check('合併後有音訊', merged.size > 1000, `${merged.size} bytes`);
  const chapterAudio = await DB.audio.listOfNovel(novel.id);
  check('章節合併檔已存', chapterAudio.some((a) => a.kind === 'CHAPTER'));

  console.log('\n=== 5. 合併整本 ===');
  const whole = await P.mergeNovelAudio(novel);
  check('整本合併有音訊', whole.size > 1000, `${whole.size} bytes`);

  console.log('\n=== 6. ZIP 匯出 ===');
  const zip = await P.zipNovel(novel);
  check('ZIP 有內容', zip.size > 1000, `${zip.size} bytes`);
  const head = Buffer.from(await zip.slice(0, 2).arrayBuffer());
  check('ZIP 魔術字節 PK', head.toString('ascii') === 'PK', head.toString('hex'));

  console.log('\n=== 7. 資料隔離 ===');
  const dump = await DB.exportAll();
  check('匯出不含 API Key', !JSON.stringify(dump).includes(key));
  check('Key 只在 localStorage', !!localStorage.getItem('noveltts.openrouterKey'));

  console.log('\n=== 8. 群組跨集音色一致 ===');
  await DB.groups.save({
    id: 'g1', title: '燈火重亮',
    narratorVoice: 'Kore', narratorInstructions: '語氣沉穩',
    characters: [
      { id: 'c1', name: '林晚', aliases: ['晚晚'], ttsVoice: 'Aoede', ttsInstructions: '語氣清冷' },
      { id: 'c2', name: '陳硯', aliases: ['陳先生'], ttsVoice: 'Charon', ttsInstructions: '語氣低沉' },
    ],
  });
  const novel2 = await DB.novels.save({
    id: 'e2e-2', title: '燈火重亮 第二集', author: '示範作者',
    content: '第一章 續\n\n林晚拆開了信。\n陳硯終於轉過身來：「你早就知道了，對不對。」',
    groupId: 'g1', chapters: [], characters: [], status: 'IMPORTED',
  });
  await P.analyzeNovel(novel2, () => {});
  const applied = novel2.characters.map((c) => `${c.name}→${c.ttsVoice}`);
  check('群組角色音色已套用', novel2.characters.length > 0, applied.join(' '));
  const linwan = novel2.characters.find((c) => c.name === '林晚');
  check('林晚音色與第一集一致（Aoede）', !linwan || linwan.ttsVoice === 'Aoede',
    linwan ? linwan.ttsVoice : '(未出現)');
  const chenyan = novel2.characters.find((c) => c.name === '陳硯');
  check('陳硯音色與第一集一致（Charon）', !chenyan || chenyan.ttsVoice === 'Charon',
    chenyan ? chenyan.ttsVoice : '(未出現)');

  console.log(`\n${failures === 0 ? '全部通過' : failures + ' 項失敗'}`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('測試失敗:', e);
  process.exit(1);
});
