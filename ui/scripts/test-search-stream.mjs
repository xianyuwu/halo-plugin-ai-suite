import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync('src/main/resources/static/js/chat-widget.js', 'utf8');
const start = source.indexOf('  function parseSseStream(response, callbacks) {');
const end = source.indexOf('  function streamChat(text, intentRouteId) {', start);
assert.ok(start >= 0 && end > start);
const context = { TextDecoder, console };
vm.createContext(context);
vm.runInContext(source.slice(start, end) + '\nthis.parseSseStream = parseSseStream;', context);

async function parse(chunks, failAtEnd = false) {
  const events = [];
  let index = 0;
  const encoder = new TextEncoder();
  const reader = {
    read: async () => {
      if (index < chunks.length) return { done: false, value: encoder.encode(chunks[index++]) };
      if (failAtEnd) throw new Error('connection lost');
      return { done: true };
    },
    cancel: async () => {},
  };
  context.parseSseStream({ body: { getReader: () => reader } }, {
    onToken: value => events.push(['token', value]),
    onDone: () => events.push(['done']),
    onIncomplete: () => events.push(['incomplete']),
    onErrorFrame: value => events.push(['error-frame', value]),
    onError: () => events.push(['read-error']),
  });
  await new Promise(resolve => setImmediate(resolve));
  return events;
}

assert.deepEqual(await parse(['data:{"content":"完整"}\n\ndata:[DONE]\n\n']),
  [['token', '完整'], ['done']]);
assert.deepEqual(await parse(['data:{"content":"半句话"}\n\n']),
  [['token', '半句话'], ['incomplete']]);
assert.deepEqual(await parse(['data:{"content":"半句话"}\n\n'], true),
  [['token', '半句话'], ['read-error']]);
assert.deepEqual(await parse(['event:error\ndata:{"content":"已中断"}\n\ndata:[DONE]\n\n']),
  [['error-frame', '已中断'], ['done']]);

console.log('PASS: SSE normal completion, premature EOF, read failure, and server error remain distinct.');

// Exercise the real modal listeners: typing and IME candidate confirmation must not spend a model request.
const modalStart = source.indexOf('  function openSearchModal() {');
const modalEnd = source.indexOf('  /** 关闭搜索弹框 */', modalStart);
assert.ok(modalStart >= 0 && modalEnd > modalStart);
const handlers = {};
const input = { value: '', focus: () => {}, addEventListener: (name, fn) => { handlers['input:' + name] = fn; } };
const clear = { style: {}, addEventListener: (name, fn) => { handlers['clear:' + name] = fn; } };
const submit = { addEventListener: (name, fn) => { handlers['submit:' + name] = fn; } };
const close = { addEventListener: () => {} };
const keywordList = { addEventListener: () => {} };
const modal = {
  innerHTML: '',
  setAttribute: () => {},
  querySelector: selector => ({
    '.ai-search-input': input,
    '.ai-search-clear': clear,
    '.ai-search-submit': submit,
    '.ai-search-close': close,
    '.ai-search-kw-list': keywordList,
  })[selector],
};
const overlay = { appendChild: () => {}, setAttribute: () => {}, addEventListener: () => {}, contains: () => true };
const searches = [];
let resets = 0;
let created = 0;
const modalContext = {
  document: { createElement: () => created++ === 0 ? overlay : modal, body: { appendChild: () => {} } },
  config: { searchTheme: 'inherit', theme: 'light' },
  resolveComponentTheme: () => 'light',
  applySearchThemeVars: () => {},
  setTimeout: fn => fn(),
  resetSearchResults: () => { resets++; },
  doSearch: value => { searches.push(value); },
  closeSearchModal: () => {},
  searchModal: null,
};
vm.createContext(modalContext);
vm.runInContext(source.slice(modalStart, modalEnd) + '\nthis.openSearchModal = openSearchModal;', modalContext);
modalContext.openSearchModal();
input.value = '作者在海南';
handlers['input:input']();
assert.equal(resets, 1);
assert.equal(searches.length, 0);
let prevented = false;
handlers['input:keydown']({ key: 'Enter', isComposing: true, keyCode: 229, preventDefault: () => { prevented = true; } });
assert.equal(prevented, false);
assert.equal(searches.length, 0);
input.value = '作者在海南玩了几天？';
handlers['input:input']();
handlers['input:keydown']({ key: 'Enter', isComposing: false, keyCode: 13, preventDefault: () => { prevented = true; } });
assert.equal(prevented, true);
assert.deepEqual(searches, ['作者在海南玩了几天？']);
handlers['submit:click']();
assert.equal(searches.length, 2);
console.log('PASS: typing and IME confirmation make zero requests; Enter and search button submit explicitly.');
