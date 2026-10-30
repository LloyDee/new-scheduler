const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const script = readFileSync('src/main/resources/static/js/volunteers.js', 'utf8');

function setup(autoOpen = false, scrollGreeting = false, visible = true, reducedMotion = false) {
  const listeners = {};
  const scroll = { scrollTop: 100 };
  const dialog = {
    id: 'volunteer-dialog-1', open: false,
    addEventListener: (name, handler) => { listeners[name] = handler; },
    showModal() { this.open = true; },
    querySelector: () => scroll
  };
  const trigger = { dataset: { volunteerDialog: dialog.id }, focused: false,
    focus() { this.focused = true; },
    addEventListener: (name, handler) => { listeners.click = handler; }
  };
  const name = { focus() { this.focused = true; }, scrollIntoView(options) { this.behavior = options.behavior; } };
  if (scrollGreeting) trigger.dataset.volunteerScroll = 'volunteer-name-1';
  vm.runInNewContext(script, { window: { matchMedia: () => ({ matches: reducedMotion }) }, document: {
    addEventListener() {},
    querySelectorAll: () => [trigger],
    getElementById: id => id === 'volunteer-name-1' ? (visible ? name : null) : id === dialog.id ? dialog : null,
    querySelector: selector => selector.includes('data-open-on-load') && autoOpen ? dialog : null
  } });
  return { dialog, trigger, listeners, scroll, name };
}

test('Volunteers greeting scrolls and focuses the visible name without opening dialog', async () => {
  for (const reduced of [false, true]) {
    const { listeners, dialog, name } = setup(false, true, true, reduced);
    let prevented = false;
    await listeners.click({ button: 0, preventDefault() { prevented = true; } });
    assert.equal(prevented, true);
    assert.equal(name.focused, true);
    assert.equal(name.behavior, reduced ? 'instant' : 'smooth');
    assert.equal(dialog.open, false);
  }
});

test('hidden Volunteers name follows reveal link instead of opening dialog', async () => {
  const { listeners, dialog } = setup(false, true, false);
  await listeners.click({ button: 0, preventDefault() { assert.fail('reveal navigation prevented'); } });
  assert.equal(dialog.open, false);
});

test('opens card dialog, resets scroll and restores focus on close', () => {
  const { dialog, trigger, listeners, scroll } = setup();
  let prevented = false;
  listeners.click({ button: 0, preventDefault() { prevented = true; } });
  assert.equal(dialog.open, true);
  assert.equal(prevented, true);
  assert.equal(scroll.scrollTop, 0);
  listeners.close();
  assert.equal(trigger.focused, true);
});
test('direct view opens selected dialog', () => assert.equal(setup(true).dialog.open, true));
test('modified link clicks preserve normal navigation', () => {
  const { dialog, listeners } = setup();
  listeners.click({ button: 0, ctrlKey: true, preventDefault() { assert.fail('modified click prevented'); } });
  assert.equal(dialog.open, false);
});

const tick = () => new Promise(resolve => setImmediate(resolve));
function setupSearch() {
  const events = {}, inputEvents = {}, formEvents = {}, timers = new Map(), requests = [];
  const input = { value: '', selectionStart: 2, addEventListener: (name, fn) => { inputEvents[name] = fn; } };
  const status = { textContent: '' };
  const state = { input, status, requests, timers, replaced: 0, url: '', activeElement: input };
  function results(count) {
    return { setAttribute() {}, removeAttribute() {}, querySelector: () => ({ textContent: `${count} volunteers` }),
      replaceWith(next) { state.results = next; state.replaced++; } };
  }
  state.results = results(25);
  const form = { action: 'https://scheduler.test/events/1/volunteers', elements: { q: input },
    before() {}, addEventListener: (name, fn) => { formEvents[name] = fn; } };
  const document = {
    addEventListener: (name, fn) => { events[name] = fn; },
    querySelectorAll: () => [],
    querySelector: selector => ({ '[data-volunteer-search]': form, '[data-search-status]': status,
      '[data-volunteer-results]': state.results })[selector] || null
  };
  vm.runInNewContext(script, { document,
    URL, URLSearchParams, AbortController, AbortSignal,
    location: { href: form.action },
    FormData: class { *[Symbol.iterator]() { yield ['q', input.value]; yield ['departmentId', '7']; } },
    history: { state: {}, replaceState(_, __, url) { state.url = url.href; } },
    setTimeout: (fn, delay) => { assert.equal(delay, 250); timers.set(fn, fn); return fn; },
    clearTimeout: id => timers.delete(id),
    fetch: (url, options) => new Promise(resolve => requests.push({ url, options, resolve })),
    DOMParser: class { parseFromString(count) { return {
      querySelector: selector => selector === '[data-volunteer-results]' ? results(count) : null,
      querySelectorAll: () => []
    }; } }
  });
  state.type = value => { input.value = value; inputEvents.input(); };
  state.flush = () => { const tasks = [...timers.values()]; timers.clear(); tasks.forEach(fn => fn()); };
  state.respond = (index, count = 1, ok = true) => requests[index].resolve({ ok, text: async () => count });
  return { state, events, inputEvents, formEvents };
}

test('debounces input, searches with department and keeps the input and caret', async () => {
  const { state } = setupSearch();
  state.type('A'); state.type('Al');
  assert.equal(state.requests.length, 0);
  assert.equal(state.timers.size, 1);
  state.flush();
  assert.equal(state.requests[0].url.searchParams.get('q'), 'Al');
  assert.equal(state.requests[0].url.searchParams.get('departmentId'), '7');
  assert.equal(state.requests[0].url.searchParams.has('page'), false);
  state.respond(0, 3); await tick();
  assert.equal(state.replaced, 1);
  assert.equal(state.status.textContent, '3 volunteers');
  assert.equal(state.activeElement, state.input);
  assert.equal(state.input.selectionStart, 2);
  assert.match(state.url, /q=Al/);
});
test('ignores an old response as soon as new input arrives and supports clearing', async () => {
  const { state } = setupSearch();
  state.type('Alex'); state.flush();
  state.type('');
  assert.equal(state.requests[0].options.signal.aborted, true);
  state.respond(0); await tick();
  assert.equal(state.replaced, 0);
  state.flush(); state.respond(1, 25); await tick();
  assert.equal(state.requests[1].url.searchParams.get('q'), '');
  assert.equal(state.status.textContent, '25 volunteers');
});
test('Enter retries a failed search and navigation cancels pending work', async () => {
  const { state, formEvents, events } = setupSearch();
  state.type('Alex'); state.flush(); state.respond(0, 0, false); await tick();
  assert.match(state.status.textContent, /press Enter/);
  let prevented = false;
  formEvents.submit({ preventDefault() { prevented = true; } });
  assert.equal(prevented, true);
  assert.equal(state.requests.length, 2);
  events['scheduler:before-page']();
  assert.equal(state.requests[1].options.signal.aborted, true);
  state.respond(1); await tick();
  assert.equal(state.replaced, 0);
});

function setupRemoteDialog() {
  const events = {}, requests = [], attributes = new Map();
  const status = { textContent: '', setAttribute() {} };
  const dialog = { open: false, matches: () => true, removeAttribute() {},
    addEventListener(name, fn) { this[name] = fn; },
    querySelector: () => ({ scrollTop: 50 }), showModal() { this.open = true; } };
  let inserted, click;
  const trigger = { dataset: { volunteerDialog: 'volunteer-dialog-1' },
    href: 'https://scheduler.test/events/1/volunteers?view=1',
    hasAttribute: name => name === 'data-own-volunteer-link',
    setAttribute: (name, value) => attributes.set(name, value),
    removeAttribute: name => attributes.delete(name), after() {}, focus() { this.focused = true; },
    addEventListener: (_, fn) => { click = fn; } };
  vm.runInNewContext(script, { document: {
    addEventListener(name, fn) { events[name] = fn; },
    querySelectorAll: () => [trigger], querySelector: () => null,
    createElement: () => status,
    getElementById: id => id === 'main' ? { append(node) { inserted = node; } } : inserted
  }, AbortController, AbortSignal,
    HTMLDialogElement: class { showModal() {} },
    fetch: (url, options) => new Promise(resolve => requests.push({ url, options, resolve })),
    DOMParser: class { parseFromString() { return { getElementById: () => dialog }; } }
  });
  return { events, requests, attributes, status, dialog, trigger,
    click: () => click({ button: 0, preventDefault() {} }),
    respond: (ok = true) => requests.at(-1).resolve({ ok, text: async () => '' }) };
}

test('greeting fetches once, opens in place, reuses dialog and restores focus', async () => {
  const state = setupRemoteDialog();
  const pending = state.click();
  await state.click();
  assert.equal(state.requests.length, 1);
  assert.equal(state.attributes.get('aria-busy'), 'true');
  assert.equal(state.requests[0].options.headers['X-Scheduler-Section'], 'main');
  state.respond(); await pending;
  assert.equal(state.dialog.open, true);
  assert.equal(state.status.textContent, '');
  assert.equal(state.attributes.has('aria-busy'), false);
  state.dialog.open = false; state.dialog.close();
  assert.equal(state.trigger.focused, true);
  await state.click();
  assert.equal(state.dialog.open, true);
  assert.equal(state.requests.length, 1);
});

test('failed greeting load offers retry', async () => {
  const state = setupRemoteDialog();
  let pending = state.click(); state.respond(false); await pending;
  assert.match(state.status.textContent, /retry/);
  pending = state.click(); state.respond(); await pending;
  assert.equal(state.requests.length, 2);
  assert.equal(state.dialog.open, true);
});

test('navigation cancels greeting load and ignores late response', async () => {
  const state = setupRemoteDialog();
  const pending = state.click();
  state.events['scheduler:before-page']();
  assert.equal(state.requests[0].options.signal.aborted, true);
  state.respond(); await pending;
  assert.equal(state.dialog.open, false);
});
