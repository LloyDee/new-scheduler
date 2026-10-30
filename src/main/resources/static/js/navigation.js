(() => {
  const supported = url => url.origin === location.origin
    && /^\/(events(?:\/\d+\/(home|volunteers|departments|schedule|reports|more))?|settings|logs)\/?$/.test(url.pathname);
  let pending;
  let sequence = 0;
  const status = document.createElement('p');
  status.className = 'sr-only';
  status.setAttribute('role', 'status');
  status.setAttribute('aria-live', 'polite');
  document.body.append(status);
  history.replaceState({ ...history.state, scheduler: true }, '', location.href);
  history.scrollRestoration = 'manual';

  async function navigate(url, push = true, scroll = null) {
    pending?.abort();
    pending = new AbortController();
    const current = ++sequence;
    const main = document.getElementById('main');
    main.setAttribute('aria-busy', 'true');
    status.textContent = 'Loading page…';
    try {
      const response = await fetch(url, {
        credentials: 'same-origin', cache: 'no-store',
        headers: { 'X-Scheduler-Section': 'main' },
        signal: AbortSignal.any([pending.signal, AbortSignal.timeout(20000)]),
      });
      if (current !== sequence) return;
      if (!response.ok || response.redirected) {
        location.assign(response.redirected ? response.url : url.href);
        return;
      }
      const documentFragment = new DOMParser().parseFromString(await response.text(), 'text/html');
      if (current !== sequence) return;
      const nextMain = documentFragment.getElementById('main');
      const nextNav = documentFragment.querySelector('.bottom-nav');
      if (!nextMain || !nextNav || !nextMain.dataset.pageTitle) throw new Error('Incomplete page');
      if (push) {
        history.replaceState({ ...history.state, scroll: [scrollX, scrollY] }, '', location.href);
        history.pushState({ scheduler: true, scroll: [0, 0] }, '', url);
      }
      document.dispatchEvent(new Event('scheduler:before-page'));
      main.replaceWith(nextMain);
      document.querySelector('.bottom-nav').replaceWith(nextNav);
      document.title = nextMain.dataset.pageTitle;
      document.dispatchEvent(new Event('scheduler:page'));
      nextMain.focus({ preventScroll: true });
      if (scroll) window.scrollTo(...scroll);
      else if (url.hash) {
        const target = document.getElementById(decodeURIComponent(url.hash.slice(1)));
        if (target) target.scrollIntoView();
        else window.scrollTo(0, 0);
      } else window.scrollTo(0, 0);
      status.textContent = document.title;
    } catch (error) {
      if (current !== sequence || error.name === 'AbortError') return;
      location.assign(url.href);
    } finally {
      if (current === sequence) document.getElementById('main')?.removeAttribute('aria-busy');
    }
  }

  document.addEventListener('click', event => {
    if (event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    const link = event.target.closest('a[href]');
    if (!link || !link.closest('#main, .bottom-nav') || link.hasAttribute('download')
        || (link.target && link.target !== '_self')) return;
    const url = new URL(link.href, location.href);
    if (!supported(url) || (url.pathname === location.pathname && url.search === location.search && url.hash)) return;
    event.preventDefault();
    navigate(url);
  });
  document.addEventListener('submit', event => {
    const form = event.target;
    if (event.defaultPrevented || form.method.toLowerCase() !== 'get' || !form.closest('#main')) return;
    const url = new URL(form.action, location.href);
    if (!supported(url)) return;
    url.search = new URLSearchParams(new FormData(form)).toString();
    event.preventDefault();
    navigate(url);
  });
  window.addEventListener('popstate', event => {
    const url = new URL(location.href);
    if (supported(url)) navigate(url, false, event.state?.scroll || [0, 0]);
    else location.reload();
  });
})();
