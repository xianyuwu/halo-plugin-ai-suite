import assert from 'node:assert/strict';
import { build } from 'esbuild';
const output = await build({ entryPoints: ['ui/src/utils/model-test-jobs.ts'], bundle: true, write: false, format: 'esm', platform: 'node' });
const { ModelTestRunner, readTestResponse, TEST_KINDS } = await import('data:text/javascript;base64,' + Buffer.from(output.outputFiles[0].text).toString('base64'));
const id = '00000000-0000-4000-8000-000000000001';
const json = (data, status = 200) => new Response(JSON.stringify(data), { status });
const job = (kind, status = 'pending', result = undefined) => ({ job: { id, kind, status, result } });
const waitFor = async predicate => {
  const until = Date.now() + 1500;
  while (!predicate()) {
    if (Date.now() > until) throw new Error('Timed out waiting for test state');
    await new Promise(resolve => setTimeout(resolve, 2));
  }
};
function harness(fetcher, saved = {}) {
  let stored = JSON.stringify(saved);
  const states = {};
  const runner = new ModelTestRunner({
    fetch: fetcher, uuid: () => id, interval: 1, requestTimeout: 30,
    storage: { getItem: () => stored, setItem: (_, value) => { stored = value; }, removeItem: () => {} },
    update: (kind, active, id, result) => { states[kind] = { active, id, result }; },
  });
  return { runner, states, saved: () => JSON.parse(stored) };
}
for (const kind of TEST_KINDS) {
  let posts = 0, gets = 0;
  const result = kind === 'image' ? { imageCount: 1 } : kind === 'embedding' ? { dimensions: 1024 }
    : kind === 'rerank' ? { relevanceScore: 0.8 } : { reply: '成功' };
  const h = harness(async (_, options) => {
    if (options.method === 'POST') { posts++; return json(job(kind), 202); }
    gets++; return json(job(kind, gets < 3 ? 'pending' : 'done', result));
  });
  await h.runner.start(kind, { model: 'model' });
  assert.equal(h.states[kind].active, true);
  await h.runner.start(kind, { model: 'model' });
  await waitFor(() => h.states[kind].result.ok === true);
  assert.equal(posts, 1);
  assert.ok(gets >= 3);
  assert.deepEqual(h.states[kind].result, { ...result, ok: true });
  assert.deepEqual(h.saved(), {});
  h.runner.dispose();
}
for (const response of [new Response('', { status: 200 }), new Response('<html>timeout</html>'), new Response('', { status: 524 })]) {
  await assert.rejects(() => readTestResponse(response), /空响应|非 JSON|HTTP 524/);
}
{
  let posts = 0, gets = 0;
  const h = harness(async (_, options) => {
    if (options.method === 'POST') { posts++; throw new TypeError('Failed to fetch'); }
    gets++; return json(job('image', 'done', { imageCount: 1 }));
  });
  await h.runner.start('image', { model: 'model' });
  assert.equal(h.states.image.result.ok, null);
  assert.equal(h.saved().image, id);
  assert.match(h.states.image.result.error, /额外费用/);
  await h.runner.start('image', { model: 'changed' });
  await waitFor(() => h.states.image.result.ok === true);
  assert.equal(posts, 1); assert.equal(gets, 1);
  h.runner.dispose();
}
{
  let attempts = 0;
  const h = harness(async () => {
    if (++attempts <= 5) throw new TypeError('offline');
    return json(job('chat', 'failed', undefined));
  }, { chat: id });
  h.runner.restore();
  await waitFor(() => h.states.chat && !h.states.chat.active);
  assert.equal(attempts, 5);
  assert.equal(h.states.chat.result.ok, null);
  assert.equal(h.saved().chat, id);
  h.runner.resume('chat');
  await waitFor(() => h.states.chat.result.ok === false);
  assert.deepEqual(h.saved(), {});
  h.runner.dispose();
}
for (const status of [401, 403, 404]) {
  const h = harness(async () => new Response('', { status }), { image: id });
  h.runner.restore();
  await waitFor(() => h.states.image && !h.states.image.active);
  assert.equal(h.states.image.result.ok, null);
  assert.match(h.states.image.result.error, new RegExp('HTTP ' + status));
  assert.equal(h.saved().image, id);
  h.runner.dispose();
}
{
  const h = harness(async () => json({ error: '任务较多' }, 429));
  await h.runner.start('image', { model: 'model' });
  assert.equal(h.states.image.id, null);
  assert.deepEqual(h.saved(), {});
  assert.equal(h.states.image.result.ok, null);
  h.runner.dispose();
}
{
  let calls = 0;
  const h = harness(async () => { calls++; return json(job('image')); });
  await h.runner.start('image', { model: 'model' });
  h.runner.dispose();
  await new Promise(resolve => setTimeout(resolve, 20));
  assert.equal(calls, 1);
  assert.equal(h.saved().image, id);
}
{
  const h = harness((_, options) => new Promise((_, reject) => {
    options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
  }));
  await h.runner.start('image', { model: 'model' });
  assert.equal(h.states.image.result.ok, null);
  assert.match(h.states.image.result.error, /超时/);
  assert.equal(h.saved().image, id);
  h.runner.dispose();
}
console.log('PASS: all five model tests, polling, uncertain submission recovery, no duplicate POST, HTTP/empty/non-JSON errors, restore, retry limits, request timeout and disposal.');
