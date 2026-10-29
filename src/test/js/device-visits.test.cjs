const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const script = readFileSync('src/main/resources/static/js/device-visits.js', 'utf8');

async function run(mode, cache = null, ua = 'Mozilla/5.0 Windows Chrome/140.0 Edg/140.1') {
  const requests = [];
  const storage = new Map(cache ? [['schedulerLocation', JSON.stringify(cache)]] : []);
  const context = {
    navigator: { userAgent: ua, platform: 'Win32', language: 'en-US', maxTouchPoints: 0 },
    Intl, Date, AbortSignal,
    document: { querySelector: (selector) => ({ content: selector.includes('_csrf_header') ? 'X-CSRF-TOKEN' : selector.includes('device-visits-url') ? '/api/device-visits' : 'token' }) },
    localStorage: { getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value) },
    fetch: async (url, options) => {
      requests.push({ url, options });
      if (url === '/api/device-visits') return { ok: true };
      if (mode === 'timeout') throw new DOMException('Timeout', 'TimeoutError');
      return { ok: mode !== 'failure', json: async () => ({ city: 'Austin', region: 'Texas', country_name: 'United States' }) };
    }
  };
  await vm.runInNewContext(script, context);
  const visit = requests.find(r => r.url === '/api/device-visits');
  assert.ok(visit);
  assert.equal(visit.options.headers['X-CSRF-TOKEN'], 'token');
  return { data: JSON.parse(visit.options.body), requests, storage };
}

test('records successful location and correctly recognizes Edge ahead of Chrome', async () => {
  const { data, storage } = await run('success');
  assert.equal(data.city, 'Austin');
  assert.equal(data.region, 'Texas');
  assert.equal(data.browser, 'Edge 140.1');
  assert.equal(data.deviceCategory, 'Desktop');
  assert.ok(storage.has('schedulerLocation'));
});
for (const mode of ['timeout', 'failure']) test(`records visit when location lookup returns ${mode}`, async () => {
  const { data } = await run(mode);
  assert.equal(data.city, undefined);
  assert.equal(data.language, 'en-US');
});
test('uses cached location without another external request', async () => {
  const { data, requests } = await run('failure', { timestamp: Date.now(), location: { city: 'Chicago' } });
  assert.equal(data.city, 'Chicago');
  assert.equal(requests.length, 1);
});
test('recognizes mobile Safari', async () => {
  const { data } = await run('success', null, 'Mozilla iPhone Version/18.0 Mobile Safari/605.1');
  assert.equal(data.browser, 'Safari 18.0');
  assert.equal(data.deviceCategory, 'Mobile');
  assert.equal(data.operatingSystem, 'iOS / iPadOS');
});
