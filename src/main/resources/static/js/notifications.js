(() => {
  const bell = document.getElementById('notification-bell');
  if (!bell) return;
  const dialog = document.getElementById('notification-dialog');
  const badge = document.getElementById('notification-badge');
  const status = document.getElementById('notification-status');
  const messages = document.getElementById('notification-messages');
  const retry = document.getElementById('notification-retry');
  const url = bell.dataset.url;
  let pending = [];
  let generation = 0;
  let busy = false;
  let refreshVersion = 0;

  async function request(path, options = {}) {
    const response = await fetch(url + path, { credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.timeout(15000), ...options });
    if (!response.ok) throw new Error('Notification request failed');
    return response;
  }

  async function refresh() {
    const version = ++refreshVersion;
    try {
      const { count } = await (await request('/count')).json();
      if (version !== refreshVersion) return;
      badge.hidden = count === 0;
      badge.classList.toggle('has-count', count > 1);
      badge.textContent = count > 1 ? String(count) : '';
      bell.setAttribute('aria-label', count ? `Notifications, ${count} unread` : 'Notifications, no unread messages');
    } catch (_) { /* Keep the last known badge; opening provides an explicit retry. */ }
  }

  async function load() {
    if (busy) return;
    busy = true;
    const current = generation;
    retry.hidden = true;
    status.textContent = pending.length ? 'Saving read status…' : 'Loading notifications…';
    try {
      if (!pending.length) {
        const unread = await (await request('')).json();
        if (!dialog.open || generation !== current) return;
        messages.replaceChildren();
        for (const item of unread) {
          const article = document.createElement('article');
          article.className = 'notification-message';
          const time = document.createElement('time');
          time.dateTime = item.createdAt;
          time.textContent = new Date(item.createdAt).toLocaleString();
          const text = document.createElement('p');
          text.textContent = item.message;
          article.append(time, text);
          messages.append(article);
        }
        pending = unread.map(item => item.id);
      }
      if (pending.length) {
        const token = document.querySelector('meta[name="_csrf"]').content;
        const header = document.querySelector('meta[name="_csrf_header"]').content;
        await request('/read', { method: 'POST', headers: { 'Content-Type': 'application/json', [header]: token }, body: JSON.stringify(pending) });
        if (generation !== current) return;
        pending = [];
      }
      if (dialog.open) status.textContent = messages.children.length ? '' : 'No unread notifications';
      await refresh();
    } catch (_) {
      if (dialog.open && generation === current) {
        status.textContent = pending.length ? 'Unable to save read status. These messages remain unread. Please retry.' : 'Unable to load notifications. Please retry.';
        retry.hidden = false;
      }
    } finally {
      busy = false;
      if (dialog.open && generation !== current) load();
    }
  }

  bell.addEventListener('click', () => {
    if (dialog.open) return;
    generation++;
    pending = [];
    messages.replaceChildren();
    dialog.showModal();
    load();
  });
  retry.addEventListener('click', load);
  dialog.addEventListener('close', () => {
    generation++;
    pending = [];
    messages.replaceChildren();
    bell.focus();
    refresh();
  });
  window.addEventListener('focus', refresh);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
  setInterval(() => { if (!document.hidden) refresh(); }, 30000);
  refresh();
})();
