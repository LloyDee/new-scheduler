(() => {
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
    trigger.addEventListener('click', event => {
      if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || event.button !== 0) return;
      const dialog = document.getElementById(trigger.dataset.volunteerDialog);
      if (openDialog(dialog, trigger)) event.preventDefault();
    });
  });
  const selected = document.querySelector('.volunteer-dialog[data-open-on-load="true"]');
  if (selected) openDialog(selected, triggers.find(trigger => trigger.dataset.volunteerDialog === selected.id));
})();
