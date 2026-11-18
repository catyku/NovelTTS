/**
 * 端到端整合測試：瀏覽器資料層 + 閘道 client → 真實 OpenRouter。
 *
 * 用 fake-indexeddb 模擬瀏覽器的 IndexedDB，用 Node 24 內建的 fetch 呼叫閘道。
 * 測的是實際部署到 web/src/main/resources/static/js/ 的那兩個檔案。
 */
const fs = require('fs');
const path = require('path');
const { indexedDB } = require('fake-indexeddb');

// 模擬瀏覽器全域
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
global.console = console;

// 載入實際的前端模組
const JS_DIR = path.resolve(__dirname, '../../web/src/main/resources/static/js');
for (const f of ['db.js', 'gateway.js']) {
  const src = fs.readFileSync(path.join(JS_DIR, f), 'utf8');
  // eslint-disable-next-line no-eval
  eval(src);
}

const DB = global.NovelTtsDB;
const GW = global.NovelTtsGateway;
const BASE = 'http://127.0.0.1:8090';

function log(label, value) {
  console.log(`${label}: ${typeof value === 'string' ? value : JSON.stringify(value)}`);
}

async function main() {
  // API Key 從環境變數讀，不進版控
  const key = process.env.OPENROUTER_API_KEY || '';
  if (!key) {
    console.log('需要 OPENROUTER_API_KEY 環境變數才能跑真實呼叫');
    process.exit(2);
  }
  let failures = 0;
  const check = (name, cond, detail) => {
    console.log(`${cond ? '  ✓' : '  ✗'} ${name}${detail ? ' — ' + detail : ''}`);
    if (!cond) failures++;
  };

  console.log('\n=== 1. 資料層：IndexedDB 寫讀 ===');
  await DB.novels.save({
    id: 'n1', title: '燈火重亮', author: '示範作者',
    chapters: [{ index: 1, title: '第一章', content: '內容', segments: [] }],
    characters: [],
  });
  const n = await DB.novels.get('n1');
  check('小說寫入後可讀回', n && n.title === '燈火重亮', n && n.title);

  await DB.groups.save({ id: 'g1', title: '群組', characters: [] });
  check('群組寫入', (await DB.groups.list()).length === 1);

  const blob = new Blob([new Uint8Array([1, 2, 3])], { type: 'audio/mpeg' });
  await DB.audio.save('n1', 'SEGMENT', 's1', blob, { durationMs: 900 });
  const a = await DB.audio.get('n1', 'SEGMENT', 's1');
  check('音檔 Blob 存取', a && a.size === 3 && a.blob instanceof Blob, `size=${a && a.size}`);

  console.log('\n=== 2. 閘道：缺少 API Key 應被拒 ===');
  DB.credentials.set('');
  try {
    await GW.analyze({ chapterContent: '測試' });
    check('無 key 時應失敗', false, '竟然成功了');
  } catch (e) {
    check('無 key 時正確拒絕', true, e.message.slice(0, 50));
  }

  console.log('\n=== 3. 閘道：真實 OpenRouter 分析 ===');
  DB.credentials.set(key);
  GW.setApiBase(process.env.GATEWAY_URL || 'http://127.0.0.1:8090/api/gateway');
  const analysis = await GW.analyze({
    chapterTitle: '第一章 夜歸',
    chapterContent: '林晚推開舊書店的大門。\n她低聲說：「今晚風很大，我以為你不會來。」\n陳硯沒有回頭。\n「我從來沒說過我不會來。」',
  });
  check('回傳段落', analysis.segments && analysis.segments.length > 0, `${analysis.segments.length} 段`);
  analysis.segments.forEach((s) => {
    console.log(`     [${s.type}] ${s.character_name || s.speaker_name}: ${(s.text || '').slice(0, 26)}`);
  });
  const hasDialogue = analysis.segments.some((s) => s.type === 'DIALOGUE');
  const hasNarration = analysis.segments.some((s) => s.type === 'NARRATION');
  check('同時有旁白與對話', hasDialogue && hasNarration);

  console.log('\n=== 4. 閘道：真實 TTS 合成 ===');
  const mp3 = await GW.synthesize([
    { text: '林晚推開舊書店的大門。', voice: 'Aoede', instructions: '語氣清冷' },
    { text: '我從來沒說過我不會來。', voice: 'Charon', instructions: '語氣低沉' },
  ]);
  const head = Buffer.from(await mp3.slice(0, 4).arrayBuffer());
  check('回傳 MP3', mp3.size > 10000, `${mp3.size} bytes`);
  // MP3 可能是 ID3v2 標籤開頭（"ID3"）或直接是 frame 同步位元組（0xFF Ex）
  const isId3 = head.toString('ascii', 0, 3) === 'ID3';
  const isFrame = head[0] === 0xff && (head[1] & 0xe0) === 0xe0;
  check('MP3 標頭正確（ID3 標籤或 frame 同步位元組）', isId3 || isFrame,
    isId3 ? 'ID3v2 標籤開頭' : head.toString('hex'));

  console.log('\n=== 5. 音檔存回 IndexedDB ===');
  await DB.audio.save('n1', 'SEGMENT', 'gw1', mp3, { durationMs: 5000 });
  const stored = await DB.audio.get('n1', 'SEGMENT', 'gw1');
  check('MP3 已存進瀏覽器', stored.size === mp3.size, `${stored.size} bytes`);
  const url = await DB.audio.url('n1', 'SEGMENT', 'gw1');
  check('可產生 object URL', !!url && url.startsWith('blob:'));

  console.log('\n=== 6. 憑證處理 ===');
  check('API Key 存在 localStorage', !!DB.credentials.get());
  check('遮蔽顯示正確', DB.credentials.masked() === 'sk-****34fd', DB.credentials.masked());
  const dump = await DB.exportAll();
  check('匯出資料不含 API Key', !JSON.stringify(dump).includes(key));

  console.log(`\n${failures === 0 ? '全部通過' : failures + ' 項失敗'}`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('測試失敗:', e);
  process.exit(1);
});
