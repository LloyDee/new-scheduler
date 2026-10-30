(() => {
function initializeVolunteers() {
  const triggers = [...document.querySelectorAll('[data-volunteer-dialog]')];
  const openDialog = (dialog, trigger) => {
    if (!dialog || dialog.open || typeof dialog.showModal !== 'function') return false;
    dialog.addEventListener('close', () => {
      if (trigger) trigger.focus();
    }, { once: true });
    dialog.showModal();
    dialog.querySelector('.volunteer-dialog-content').scrollTop = 0;
    return true;
  };
  triggers.forEach(trigger => {
    if (trigger.dataset.dialogBound) return;
    trigger.dataset.dialogBound = 'true';
    let pending, disposed = false, status;
    document.addEventListener('scheduler:before-page', () => {
      disposed = true;
      pending?.abort();
    }, { once: true });
    trigger.addEventListener('click', async event => {
      if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || event.button !== 0) return;
      if (trigger.dataset.volunteerScroll) {
        const name = document.getElementById(trigger.dataset.volunteerScroll);
        if (name) {
          event.preventDefault();
          name.focus({ preventScroll: true });
          name.scrollIntoView({ block: 'center', behavior:
            window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' });
        }
        return;
      }
      const dialog = document.getElementById(trigger.dataset.volunteerDialog);
      if (dialog) {
        if (openDialog(dialog, trigger) || dialog.open) event.preventDefault();
        return;
      }
      if (!trigger.hasAttribute('data-own-volunteer-link') || typeof HTMLDialogElement === 'undefined'
          || typeof HTMLDialogElement.prototype.showModal !== 'function') return;
      event.preventDefault();
      if (pending || disposed) return;
      pending = new AbortController();
      trigger.setAttribute('aria-busy', 'true');
      if (!status) {
        status = document.createElement('span');
        status.setAttribute('role', 'status');
        trigger.after(status);
      }
      status.textContent = 'Loading details…';
      try {
        const response = await fetch(trigger.href, { credentials: 'same-origin', cache: 'no-store',
          headers: { 'X-Scheduler-Section': 'main' },
          signal: AbortSignal.any([pending.signal, AbortSignal.timeout(20000)]) });
        if (disposed) return;
        if (!response.ok || response.redirected) throw new Error('Details unavailable');
        const page = new DOMParser().parseFromString(await response.text(), 'text/html');
        if (disposed) return;
        const loaded = page.getElementById(trigger.dataset.volunteerDialog);
        if (!loaded?.matches('dialog.volunteer-dialog')) throw new Error('Details unavailable');
        loaded.removeAttribute('data-open-on-load');
        document.getElementById('main').append(loaded);
        if (!openDialog(loaded, trigger)) throw new Error('Details unavailable');
        status.textContent = '';
      } catch (error) {
        if (!disposed) status.textContent = 'Could not load details. Select your name to retry.';
      } finally {
        pending = null;
        trigger.removeAttribute('aria-busy');
      }
    });
  });
  const selected = document.querySelector('.volunteer-dialog[data-open-on-load="true"]');
  if (selected) openDialog(selected, triggers.find(trigger => trigger.dataset.volunteerDialog === selected.id));
}
function initializeSearch() {
  const form = document.querySelector('[data-volunteer-search]');
  if (!form) return;
  const input = form.elements.q;
  const status = document.querySelector('[data-search-status]');
  let timer, controller;
  let sequence = 0;
  let disposed = false;
  const invalidate = () => {
    clearTimeout(timer);
    controller?.abort();
    sequence++;
    document.querySelector('[data-volunteer-results]')?.removeAttribute('aria-busy');
  };
  async function search() {
    invalidate();
    const current = sequence;
    controller = new AbortController();
    const url = new URL(form.action, location.href);
    url.search = new URLSearchParams(new FormData(form)).toString();
    const results = document.querySelector('[data-volunteer-results]');
    results.setAttribute('aria-busy', 'true');
    status.textContent = 'Searching…';
    try {
      const response = await fetch(url, { credentials: 'same-origin', cache: 'no-store',
        headers: { 'X-Scheduler-Section': 'main' },
        signal: AbortSignal.any([controller.signal, AbortSignal.timeout(20000)]) });
      if (disposed || current !== sequence) return;
      if (response.redirected) { location.assign(response.url); return; }
      if (!response.ok) throw new Error('Search failed');
      const page = new DOMParser().parseFromString(await response.text(), 'text/html');
      if (disposed || current !== sequence) return;
      const nextResults = page.querySelector('[data-volunteer-results]');
      if (!nextResults) throw new Error('Search failed');
      document.querySelectorAll('.volunteer-dialog').forEach(dialog => dialog.remove());
      page.querySelectorAll('.volunteer-dialog').forEach(dialog => form.before(dialog));
      results.replaceWith(nextResults);
      const greeting = document.querySelector('[data-own-volunteer-link]');
      const nextGreeting = page.querySelector('[data-own-volunteer-link]');
      if (greeting && nextGreeting) greeting.href = nextGreeting.href;
      history.replaceState({ ...history.state, scheduler: true }, '', url);
      status.textContent = nextResults.querySelector('.list-count').textContent;
      initializeVolunteers();
    } catch (error) {
      if (!disposed && current === sequence) status.textContent = 'Search failed. Type again or press Enter to retry.';
    } finally {
      if (!disposed && current === sequence) results.removeAttribute('aria-busy');
    }
  }
  input.addEventListener('input', () => {
    invalidate();
    status.textContent = 'Searching…';
    if (!input.composing) timer = setTimeout(search, 250);
  });
  input.addEventListener('compositionstart', () => { input.composing = true; invalidate(); });
  input.addEventListener('compositionend', () => { input.composing = false; timer = setTimeout(search, 250); });
  form.addEventListener('submit', event => { event.preventDefault(); search(); });
  document.addEventListener('scheduler:before-page', () => { disposed = true; invalidate(); }, { once: true });
}
document.addEventListener('scheduler:page', initializeSearch);
document.addEventListener('scheduler:page', initializeVolunteers);
initializeVolunteers();
initializeSearch();
})();
