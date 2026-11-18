/**
 * AI 生成小說的整合測試（走 mock OpenRouter，不需真實 API Key）。
 *
 * 驗證：
 *   1. 產生大綱 → 回傳純文字大綱
 *   2. 生成章節 → 回傳正文與字數
 *   3. 參數正規化（章數、字數範圍的預設與夾限）
 *   4. 錯誤處理（缺少想法、字數範圍不合法）
 */
const { spawn } = require('child_process');
const path = require('path');

const APP = process.env.APP_URL || 'http://127.0.0.1:8090';
const MOCK_PORT = 18099;
// app 跑在 podman bridge 網路裡，容器內的 127.0.0.1 指的是容器自己。
// mock 綁在 host 的 0.0.0.0，app 端要用 host.containers.internal 才連得到。
// （rootless podman 會把這個名稱解析到 169.254.1.2）
const MOCK_HOST = process.env.MOCK_HOST || 'host.containers.internal';
const MOCK_BASE = `http://${MOCK_HOST}:${MOCK_PORT}/api/v1`;
const KEY = 'sk-or-v1-mock-test-key';

let failures = 0;
function check(name, cond, detail) {
  console.log(`${cond ? '  ✓' : '  ✗'} ${name}${detail ? ' — ' + detail : ''}`);
  if (!cond) failures++;
}

async function post(pathname, body) {
  const resp = await fetch(APP + '/api/gateway' + pathname, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-OpenRouter-Key': KEY,
      'X-OpenRouter-Base-Url': MOCK_BASE,
    },
    body: JSON.stringify(body),
  });
  const text = await resp.text();
  let json = null;
  try { json = JSON.parse(text); } catch (e) { /* 保留原始文字 */ }
  return { status: resp.status, json, text };
}

async function main() {
  const mock = spawn('python3', [
    path.resolve(__dirname, '../mock_openrouter.py'),
    '--port', String(MOCK_PORT),
  ], { stdio: 'ignore' });

  // 等 mock 起來
  for (let i = 0; i < 30; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${MOCK_PORT}/api/v1/models`);
      if (r.ok) break;
    } catch (e) { /* 還沒起來 */ }
    await new Promise((r) => setTimeout(r, 300));
  }

  try {
    console.log('\n=== 1. 產生大綱 ===');
    const outline = await post('/generate-outline', {
      idea: '一名失憶的書店老闆，在雨夜收到一封來自十年前的信',
      chapterCount: 3,
      minChars: 800,
      maxChars: 1500,
    });
    check('回 200', outline.status === 200, `status=${outline.status}`);
    check('回傳大綱文字', typeof outline.json?.outline === 'string' && outline.json.outline.length > 0,
      (outline.json?.outline || '').slice(0, 50));
    check('大綱有章節行', (outline.json?.outline || '').includes('第1章'),
      (outline.json?.outline || '').split('\n')[0]);

    console.log('\n=== 2. 生成章節 ===');
    const ch = await post('/generate-chapter', {
      idea: '一名失憶的書店老闆',
      chapterNumber: 2,
      chapterCount: 3,
      minChars: 500,
      maxChars: 1000,
      outline: outline.json.outline,
      previousEnding: '他讀完那封信，久久沒有說話。',
    });
    check('回 200', ch.status === 200, `status=${ch.status}`);
    check('回傳正文', typeof ch.json?.content === 'string' && ch.json.content.length > 0,
      `${ch.json?.content?.length || 0} 字`);
    check('章序正確', ch.json?.chapterNumber === 2, `chapterNumber=${ch.json?.chapterNumber}`);
    check('字數統計一致', ch.json?.charCount === (ch.json?.content || '').length,
      `charCount=${ch.json?.charCount}`);

    console.log('\n=== 3. 參數正規化 ===');
    // 不給 min/max → 用預設（1000 / 3000）
    const dflt = await post('/generate-chapter', {
      idea: '測試',
      chapterNumber: 1,
      chapterCount: 1,
    });
    check('缺省參數仍能回 200', dflt.status === 200, `status=${dflt.status}`);
    // max < min → 夾限到 min，不該 500
    const clamp = await post('/generate-chapter', {
      idea: '測試',
      chapterNumber: 1,
      chapterCount: 1,
      minChars: 900,
      maxChars: 100,
    });
    check('max<min 被夾限而非報錯', clamp.status === 200, `status=${clamp.status}`);

    console.log('\n=== 4. 錯誤處理 ===');
    const emptyIdea = await post('/generate-chapter', {
      idea: '   ', chapterNumber: 1, chapterCount: 1,
    });
    check('空想法被拒（4xx）', emptyIdea.status >= 400 && emptyIdea.status < 500,
      `status=${emptyIdea.status}`);

    const badRange = await post('/generate-chapter', {
      idea: '想法', chapterNumber: 1, chapterCount: 1, minChars: 0, maxChars: 0,
    });
    check('字數範圍不合法被拒（4xx）', badRange.status >= 400 && badRange.status < 500,
      `status=${badRange.status}`);

    const badChapter = await post('/generate-chapter', {
      idea: '想法', chapterNumber: 99, chapterCount: 3,
    });
    check('章序超出範圍被拒（4xx）', badChapter.status >= 400 && badChapter.status < 500,
      `status=${badChapter.status}`);

    console.log('\n=== 5. 缺少 API Key ===');
    const noKey = await fetch(APP + '/api/gateway/generate-chapter', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ idea: 'x', chapterNumber: 1, chapterCount: 1 }),
    });
    check('無 key 回 401', noKey.status === 401, `status=${noKey.status}`);

    console.log(`\n${failures === 0 ? '全部通過' : failures + ' 項失敗'}`);
    process.exitCode = failures === 0 ? 0 : 1;
  } finally {
    mock.kill();
  }
}

main().catch((e) => {
  console.error('測試失敗:', e);
  process.exit(1);
});
