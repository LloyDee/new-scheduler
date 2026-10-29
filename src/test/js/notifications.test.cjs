const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const script = readFileSync('src/main/resources/static/js/notifications.js', 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));

function setup(items = []) {
  class Element {
    constructor() { this.children = []; this.events = {}; this.hidden = false; this.textContent = ''; this.classes = new Set(); this.classList = { toggle: (name, on) => on ? this.classes.add(name) : this.classes.delete(name) }; }
    addEventListener(name, fn) { this.events[name] = fn; }
    setAttribute(name, value) { this[name] = value; }
    replaceChildren() { this.children = []; }
    append(...items) { this.children.push(...items); }
    showModal() { this.open = true; }
    focus() { this.focused = true; }
  }
  const elements = Object.fromEntries(['bell', 'dialog', 'badge', 'status', 'messages', 'retry'].map(name => [name, new Element()]));
  elements.bell.dataset = { url: '/api/notifications' };
  const state = { items, requests: [], failRead: false, failLoad: false, deferLoad: null };
  const windowEvents = {};
  const context = {
    Date, AbortSignal,
    document: {
      hidden: false,
      getElementById: id => elements[id.replace('notification-', '')],
      createElement: () => new Element(),
      querySelector: selector => ({ content: selector.includes('_csrf_header') ? 'X-CSRF-TOKEN' : 'token' }),
      addEventListener() {}
    },
    window: { addEventListener: (name, fn) => { windowEvents[name] = fn; } },
    setInterval: fn => { state.poll = fn; },
    fetch: async (url, options) => {
      state.requests.push({ url, options });
      if (url.endsWith('/count')) return { ok: true, json: async () => ({ count: state.items.length }) };
      if (url.endsWith('/read')) {
        if (state.failRead) return { ok: false };
        const ids = JSON.parse(options.body);
        state.items = state.items.filter(item => !ids.includes(item.id));
        return { ok: true };
      }
      if (state.deferLoad) await state.deferLoad;
      return { ok: !state.failLoad, json: async () => [...state.items] };
    }
  };
  vm.runInNewContext(script, context);
  return { ...elements, state, windowEvents, close: () => { elements.dialog.open = false; elements.dialog.events.close(); } };
}
const item = id => ({ id, message: `<script>message ${id}</script>\nsecond line`, createdAt: '2026-10-29T12:00:00Z' });

test('badge transitions from count to dot to empty as device reads notifications', async () => {
  const ui = setup([item(1), item(2)]);
  await tick();
  assert.equal(ui.badge.textContent, '2');
  assert.ok(ui.badge.classes.has('has-count'));
  ui.state.items = [item(2)];
  await ui.state.poll();
  await tick();
  assert.equal(ui.badge.textContent, '');
  assert.equal(ui.badge.hidden, false);
  ui.bell.events.click();
  await tick();
  assert.equal(ui.messages.children[0].children[1].textContent, item(2).message);
  assert.equal(ui.badge.hidden, true);
  const ack = ui.state.requests.find(request => request.url.endsWith('/read'));
  assert.equal(ack.options.headers['X-CSRF-TOKEN'], 'token');
  assert.equal(ack.options.body, '[2]');
  assert.equal(ui.messages.children.length, 1);
  ui.close();
  assert.equal(ui.messages.children.length, 0);
  assert.ok(ui.bell.focused);
  ui.bell.events.click();
  await tick();
  assert.equal(ui.status.textContent, 'No unread notifications');
});

test('failed acknowledgement preserves displayed messages and retry does not consume new arrivals', async () => {
  const ui = setup([item(1)]);
  ui.state.failRead = true;
  ui.bell.events.click();
  await tick();
  assert.equal(ui.retry.hidden, false);
  assert.equal(ui.messages.children.length, 1);
  assert.equal(ui.state.items.length, 1);
  ui.state.items.push(item(2));
  ui.state.failRead = false;
  await ui.retry.events.click();
  assert.deepEqual(ui.state.items.map(item => item.id), [2]);
  assert.equal(ui.messages.children.length, 1);
  assert.equal(ui.badge.hidden, false);
});

test('load errors can retry and a popup closed before loading never marks messages read', async () => {
  const ui = setup([item(1)]);
  ui.state.failLoad = true;
  ui.bell.events.click();
  await tick();
  assert.equal(ui.retry.hidden, false);
  assert.equal(ui.messages.children.length, 0);
  ui.state.failLoad = false;
  let release;
  ui.state.deferLoad = new Promise(resolve => { release = resolve; });
  const loading = ui.retry.events.click();
  ui.close();
  release();
  await loading;
  assert.equal(ui.state.requests.filter(request => request.url.endsWith('/read')).length, 0);
  assert.equal(ui.state.items.length, 1);
});
