const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const script = readFileSync('src/main/resources/static/js/theme.js', 'utf8');
const palettes = { teal: '#174d44', royal: '#5941a9', rose: '#a33460', azure: '#1264a3' };
function setup(saved, blocked = false) {
  const events = {}, root = { dataset: {} }, meta = {}, status = {};
  let options = [];
  const state = { root, meta, status, saved };
  vm.runInNewContext(script, { document: {
    readyState: 'loading', documentElement: root,
    addEventListener: (name, fn) => { events[name] = fn; },
    querySelectorAll: () => options,
    querySelector: selector => selector.includes('meta') ? { setAttribute: (_, value) => { meta.color = value; } } : status
  }, localStorage: {
    getItem() { if (blocked) throw Error(); return state.saved; },
    setItem(_, value) { if (blocked) throw Error(); state.saved = value; }
  } });
  state.enterSettings = () => {
    options = Object.keys(palettes).map(value => ({ value, checked: false, bindings: 0,
      addEventListener(_, fn) { this.change = fn; this.bindings++; } }));
    events['scheduler:page'](); return options;
  };
  state.events = events;
  return state;
}
for (const [theme, color] of Object.entries(palettes)) {
  test(`${theme} loads, applies, saves, and survives returning to Settings`, () => {
    const state = setup(theme);
    assert.equal(state.root.dataset.theme, theme);
    assert.equal(state.meta.color, color);
    let options = state.enterSettings();
    assert.equal(options.find(o => o.checked).value, theme);
    for (const option of options) {
      option.checked = true; option.change();
      assert.equal(state.root.dataset.theme, option.value);
      assert.equal(state.meta.color, palettes[option.value]);
      assert.equal(state.saved, option.value);
    }
    state.events['scheduler:page']();
    assert.ok(options.every(o => o.bindings === 1));
    options = state.enterSettings();
    assert.equal(options.find(o => o.checked).value, 'azure');
    assert.equal(setup(state.saved).root.dataset.theme, 'azure');
  });
}
test('invalid storage defaults to teal; blocked storage still allows changing themes', () => {
  assert.equal(setup('invalid').root.dataset.theme, 'teal');
  const state = setup(null, true);
  const royal = state.enterSettings().find(o => o.value === 'royal');
  royal.checked = true; royal.change();
  assert.equal(state.root.dataset.theme, 'royal');
  assert.match(state.status.textContent, /storage is unavailable/);
});
