(() => {
  const themes = { teal: '#174d44', royal: '#5941a9', rose: '#a33460', azure: '#1264a3' };
  const key = 'gather-theme';
  let current = 'teal';
  try {
    const saved = localStorage.getItem(key);
    if (Object.hasOwn(themes, saved)) current = saved;
  } catch (_) { /* The theme still works when browser storage is unavailable. */ }

  const apply = (theme) => {
    document.documentElement.dataset.theme = theme;
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', themes[theme]);
  };
  apply(current);
  const initialized = new WeakSet();
  const initializeThemeOptions = () => {
    const options = document.querySelectorAll('input[name="theme"]');
    options.forEach((option) => {
      option.checked = option.value === current;
      if (initialized.has(option)) return;
      initialized.add(option);
      option.addEventListener('change', () => {
        if (!option.checked || !Object.hasOwn(themes, option.value)) return;
        current = option.value;
        apply(current);
        let saved = true;
        try { localStorage.setItem(key, current); } catch (_) { saved = false; }
        const status = document.querySelector('[data-theme-status]');
        if (status) status.textContent = `${current[0].toUpperCase() + current.slice(1)} theme applied${saved ? ' and saved.' : '. Browser storage is unavailable; this choice applies to this page.'}`;
      });
    });
  };
  document.addEventListener('scheduler:page', initializeThemeOptions);
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initializeThemeOptions);
  } else {
    initializeThemeOptions();
  }
})();
