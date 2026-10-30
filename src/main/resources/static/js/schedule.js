(() => {
  document.querySelectorAll('[data-assignment-dialog]').forEach(trigger => {
    const dialog = document.getElementById(trigger.dataset.assignmentDialog);
    if (!dialog) return;
    const form = dialog.querySelector('form');
    trigger.addEventListener('click', () => {
      form.reset();
      dialog.showModal();
      dialog.querySelector('.app-dialog-content').scrollTop = 0;
    });
    dialog.querySelector('[data-assignment-cancel]').addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', () => trigger.focus());
  });
})();
