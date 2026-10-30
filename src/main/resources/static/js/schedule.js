(() => {
  document.querySelectorAll('[data-assignment-dialog]').forEach(trigger => {
    const dialog = document.getElementById(trigger.dataset.assignmentDialog);
    if (!dialog) return;
    const form = dialog.querySelector('form');
    const submit = dialog.querySelector('[data-assignment-submit]');
    const update = () => {
      submit.disabled = !form.querySelector('input[name="volunteerIds"]:checked');
    };
    trigger.addEventListener('click', () => {
      form.reset();
      update();
      dialog.showModal();
      dialog.querySelector('.app-dialog-content').scrollTop = 0;
    });
    form.addEventListener('change', update);
    form.addEventListener('submit', event => {
      if (!form.querySelector('input[name="volunteerIds"]:checked')) event.preventDefault();
    });
    dialog.querySelector('[data-assignment-cancel]').addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', () => trigger.focus());
  });
})();
