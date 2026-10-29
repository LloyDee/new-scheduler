const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const script = readFileSync('src/main/resources/static/js/volunteers.js', 'utf8');

function setup(autoOpen = false) {
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
  vm.runInNewContext(script, { document: {
    querySelectorAll: () => [trigger],
    getElementById: id => id === dialog.id ? dialog : null,
    querySelector: () => autoOpen ? dialog : null
  } });
  return { dialog, trigger, listeners, scroll };
}

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
