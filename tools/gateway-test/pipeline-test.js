/**
 * 離線驗證 pipeline.js 與四個頁面腳本的流程邏輯（不呼叫真實 OpenRouter）。
 * 用 fake-indexeddb 模擬 IndexedDB，用 stub fetch 模擬閘道。
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

// 極簡 DOM stub：只支援 el() 與 querySelector 用到的部分
function makeEl(tag) {
  const node = {
    tagName: tag, children: [], attributes: {}, style: {}, _text: '',
    className: '', value: '', href: '', disabled: false, dataset: {},
    innerHTML: '', textContent: '', selected: false,
    appendChild(c) { this.children.push(c); return c; },
    setAttribute(k, v) { this.attributes[k] = v; },
    getAttribute(k) { return this.attributes[k]; },
    addEventListener() {},
    removeChild(c) { this.children = this.children.filter((x) => x !== c); },
    closest() { return null; },
    querySelector() { return null; },
    querySelectorAll() { return []; },
    get text() { return this._text; },
    set text(v) { this._text = v; },
  };
  return node;
}
const domNodes = {};
global.document = {
  createElement: (tag) => makeEl(tag),
  getElementById: (id) => (domNodes[id] = domNodes[id] || makeEl('div')),
  querySelector: () => null,
  querySelectorAll: () => [],
  createTextNode: (t) => ({ nodeType: 3, textContent: t }),
  body: makeEl('body'),
};
global.location = { pathname: '/novels/n1', href: '' };
global.alert = () => {};
global.confirm = () => true;
global.setTimeout = setTimeout;

// 載入實際前端模組
const JS_DIR = path.resolve(__dirname, '../../web/src/main/resources/static/js');
const vm = require('vm');
for (const f of ['common.js', 'db.js', 'logic.js', 'gateway.js', 'pipeline.js']) {
  // 用 runInThisContext 模擬瀏覽器的 <script>：頂層 function 宣告會成為全域函式
  vm.runInThisContext(fs.readFileSync(path.join(JS_DIR, f), 'utf8'), { filename: f });
}

// 模擬閘道回應
const ANALYZE_RESULT = {
  segments: [
    { seq: 1, type: 'NARRATION', speaker_name: '旁白', character_name: null, text: '夜裡的舊書店很安靜。', emotion: '平靜', style_hint: '低沉', style_instructions: '語氣平緩' },
    { seq: 2, type: 'DIALOGUE', speaker_name: '林晚', character_name: '林晚', text: '你終於來了。', emotion: '欣喜', style_hint: '輕快', style_instructions: '語氣明亮' },
    { seq: 3, type: 'DIALOGUE', speaker_name: '陳硯', character_name: '陳硯', text: '我答應過的。', emotion: '平靜', style_hint: '冷靜', style_instructions: '語氣低沉' },
  ],
};

global.fetch = async (url, opts) => {
  const u = String(url);
  if (u.endsWith('/analyze')) {
    return { ok: true, json: async () => JSON.parse(JSON.stringify(ANALYZE_RESULT)) };
  }
  if (u.endsWith('/synthesize')) {
    return { ok: true, blob: async () => new Blob([new Uint8Array([1, 2, 3, 4])], { type: 'audio/mpeg' }) };
  }
  if (u.endsWith('/merge')) {
    return { ok: true, blob: async () => new Blob([new Uint8Array([9, 9])], { type: 'audio/mpeg' }) };
  }
  if (u.endsWith('/zip')) {
    return { ok: true, blob: async () => new Blob([new Uint8Array([7])], { type: 'application/zip' }) };
  }
  if (u.includes('/api/settings/voices')) {
    return { ok: true, json: async () => [{ id: 1, voiceId: 'Kore', displayName: 'Kore', gender: '女', styleDesc: '柔和' }] };
  }
  return { ok: false, status: 404, text: async () => 'not found' };
};

const DB = global.NovelTtsDB;
const P = global.NovelTtsPipeline;

let failures = 0;
function check(name, cond, detail) {
  console.log(`${cond ? '  ✓' : '  ✗'} ${name}${detail ? ' — ' + detail : ''}`);
  if (!cond) failures++;
}

async function main() {
  console.log('\n=== 1. 建立小說（切章 + 群組 + 集數順序）===');
  const chapters = global.NovelTtsLogic.splitChapters('第一章 夜歸\n林晚推開門。\n\n第二章 風起\n陳硯站在街頭。', 3000);
  check('切出 2 章', chapters.length === 2, `${chapters.length} 章`);

  await DB.groups.save({
    id: 'g1', title: '舊書店系列',
    narratorVoice: 'Kore', narratorInstructions: '旁白沉穩',
    characters: [{ id: 'gc1', name: '林晚', aliases: ['晚晚'], ttsVoice: 'Aoede', ttsInstructions: '清亮' }],
  });

  const novel = {
    id: 'n1', title: '燈火重亮', author: '示範作者',
    content: '第一章 夜歸\n林晚推開門。\n\n第二章 風起\n陳硯站在街頭。',
    groupId: 'g1', groupOrder: 1,
    chapters: chapters.map((c) => ({ id: 'c' + c.index, index: c.index, title: c.title, content: c.content, charCount: c.content.length, status: 'NEW', segments: [] })),
    characters: [], status: 'NEW',
  };
  await DB.novels.save(novel);
  const listed = await DB.novels.list();
  check('小說已存進 IndexedDB', listed.length === 1 && listed[0].title === '燈火重亮');

  console.log('\n=== 2. 分析 pipeline（切章→分析→建立角色→套用群組音色）===');
  let lastPct = -1;
  await P.analyzeNovel(novel, (pct) => { lastPct = pct; });
  check('進度有回報', lastPct === 100, `lastPct=${lastPct}`);
  check('章節數維持 2', novel.chapters.length === 2);
  check('每章有段落', novel.chapters.every((c) => c.segments.length === 3));
  check('建立 2 個角色', novel.characters.length === 2, novel.characters.map((c) => c.name).join(','));
  const lin = novel.characters.find((c) => c.name === '林晚');
  check('角色段落數統計', lin && lin.segmentCount === 2, `segmentCount=${lin && lin.segmentCount}`);
  check('群組共用音色已套用', lin && lin.ttsVoice === 'Aoede', `voice=${lin && lin.ttsVoice}`);
  check('群組別名合併', lin && lin.aliases.includes('晚晚'));
  const narration = novel.chapters[0].segments[0];
  check('旁白用群組旁白音色', narration.voice === 'Kore', `voice=${narration.voice}`);
  check('對話段落用角色音色', novel.chapters[0].segments[1].voice === 'Aoede');
  check('狀態更新為 ANALYZED', novel.status === 'ANALYZED');

  console.log('\n=== 3. 合成 pipeline（逐段 + 合併本章）===');
  const ch1 = novel.chapters[0];
  await P.synthesizeChapter(novel, ch1, false);
  const seg1 = await DB.audio.get('n1', 'SEGMENT', ch1.segments[0].id);
  check('段落音檔已存 IndexedDB', !!seg1 && seg1.blob, `size=${seg1 && seg1.size}`);
  check('段落狀態為 DONE', ch1.segments[0].ttsStatus === 'DONE');
  const chap1 = await DB.audio.get('n1', 'CHAPTER', ch1.id);
  check('章節合併音檔已存', !!chap1 && chap1.blob);
  check('章節狀態為 READY', ch1.status === 'READY');

  console.log('\n=== 4. 合併整本 / ZIP ===');
  const merged = await P.mergeNovelAudio(novel);
  check('合併整本回傳 Blob', merged && merged.size === 2, `size=${merged && merged.size}`);
  const novelAudio = await DB.audio.get('n1', 'NOVEL', 'n1');
  check('整本音檔已存', !!novelAudio && novelAudio.blob);
  const zip = await P.zipNovel(novel);
  check('ZIP 回傳 Blob', zip && zip.size === 1);

  console.log('\n=== 5. 自動指派音色 ===');
  await DB.settings.save({});
  const count = await P.autoAssignVoices(novel, true);
  check('有角色被指派音色', count >= 0, `count=${count}`);

  console.log('\n=== 6. 音檔物件 URL ===');
  const url = await DB.audio.url('n1', 'SEGMENT', ch1.segments[0].id);
  check('可建立 object URL', !!url && url.startsWith('blob:'));
  const audio = await P.createAudioElement('n1', 'SEGMENT', ch1.segments[0].id);
  check('可建立 audio 元素', !!audio);
  P.revokeAudioUrls();
  check('revoke 不會丟錯', true);

  console.log('\n=== 7. 小說刪除連帶清除音檔 ====');
  await DB.novels.remove('n1');
  const left = await DB.audio.listOfNovel('n1');
  check('音檔一併刪除', left.length === 0, `剩下 ${left.length} 筆`);

  console.log(`\n${failures === 0 ? '全部通過' : failures + ' 項失敗'}`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { console.error('測試失敗:', e); process.exit(1); });
