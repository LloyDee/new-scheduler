(() => {
function initializeSettings() {
document.querySelectorAll('[data-app-dialog]').forEach((button) => {
    button.addEventListener('click', () => {
        const dialog = document.getElementById(button.dataset.appDialog);
        if (dialog && !dialog.open) {
            dialog.showModal();
            dialog.querySelector('.app-dialog-content').scrollTop = 0;
        }
    });
});
}
document.addEventListener('scheduler:page', initializeSettings);
initializeSettings();
})();
