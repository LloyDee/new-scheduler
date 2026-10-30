(() => {
function initializePage() {
document.querySelectorAll("[data-phone]").forEach((input) => {
  const formatPhone = () => {
    const caret = input.selectionStart;
    const digitsBeforeCaret = input.value.slice(0, caret).replace(/[^0-9]/g, "").length;
    const digits = input.value.replace(/[^0-9]/g, "").slice(0, 10);
    let formatted = digits ? `(${digits.slice(0, 3)}` : "";
    if (digits.length > 3) formatted += `)${digits.slice(3, 6)}`;
    if (digits.length > 6) formatted += `-${digits.slice(6)}`;
    input.value = formatted;
    if (document.activeElement === input && caret !== null) {
      let position = 0;
      let count = 0;
      while (position < formatted.length && count < digitsBeforeCaret) {
        if (/[0-9]/.test(formatted[position])) count++;
        position++;
      }
      input.setSelectionRange(position, position);
    }
    input.setCustomValidity(digits.length && digits.length !== 10 ? "Enter a 10-digit phone number." : "");
  };
  input.addEventListener("input", formatPhone);
  formatPhone();
});

document.querySelectorAll("[data-auto-submit]").forEach((select) => {
  select.addEventListener("change", () => select.form.requestSubmit());
});

document.querySelectorAll("[data-department-picker]").forEach((picker) => {
  const departmentSelect = picker.querySelector("[data-department-select]");
  if (!departmentSelect) return;
  const memberships = [...picker.querySelectorAll("[data-department-membership]")];
  const updateOptions = () => {
    for (const option of departmentSelect.options) {
      if (!option.value || option.value === "none") continue;
      option.disabled = memberships.some((row) =>
        row.dataset.departmentId === option.value && !row.hidden);
    }
    departmentSelect.required = !memberships.some((row) => !row.hidden);
  };
  departmentSelect.addEventListener("change", () => {
    if (departmentSelect.value === "none") {
      memberships.forEach((row) => {
        const role = row.querySelector("select");
        role.value = "";
        role.required = false;
        row.hidden = true;
      });
      updateOptions();
      return;
    }
    const row = memberships.find((item) => item.dataset.departmentId === departmentSelect.value);
    if (!row) return;
    row.hidden = false;
    const role = row.querySelector("select");
    role.required = true;
    departmentSelect.value = "";
    updateOptions();
    role.focus();
  });
  memberships.forEach((row) => {
    row.querySelector("[data-remove-department]").addEventListener("click", () => {
      const role = row.querySelector("select");
      role.value = "";
      role.required = false;
      row.hidden = true;
      updateOptions();
      departmentSelect.focus();
    });
  });
  updateOptions();
});

document.querySelectorAll("[data-notification]").forEach((notification) => {
  window.setTimeout(() => {
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      notification.remove();
      return;
    }
    const fade = notification.animate([{ opacity: 1 }, { opacity: 0 }], {
      duration: 400,
      easing: "ease-out",
      fill: "forwards",
    });
    fade.finished.then(() => notification.remove());
  }, 2500);
});

const eventForm = document.querySelector("[data-event-form]");
if (eventForm) {
  const type = eventForm.elements.type;
  const startDate = eventForm.elements.startDate;
  const assembly = eventForm.elements.assembly;
  const preview = eventForm.querySelector("[data-event-preview]");
  const updateEvent = () => {
    const isAssembly = type.value === "CIRCUIT_ASSEMBLY";
    eventForm.querySelector("[data-assembly-field]").hidden = !isAssembly;
    assembly.required = isAssembly;
    assembly.disabled = !isAssembly;
    preview.textContent = "";
    if (!type.value || !startDate.value) return;
    const start = new Date(`${startDate.value}T00:00:00Z`);
    if (Number.isNaN(start.getTime())) return;
    const end = new Date(start);
    end.setUTCDate(end.getUTCDate() + (isAssembly ? 0 : 2));
    const year = start.getUTCFullYear() + (start.getUTCMonth() >= 8 ? 1 : 0);
    const name = isAssembly ? `Circuit Assembly ${assembly.value}` : `${year} Regional Convention`;
    preview.textContent = `${name.trim()} · End date: ${end.toISOString().slice(0, 10)}`;
  };
  eventForm.addEventListener("change", updateEvent);
  updateEvent();
}

}
document.addEventListener('scheduler:page', initializePage);
initializePage();

document.addEventListener("submit", (event) => {
  const message = event.target.dataset.confirm;
  if (message && !window.confirm(message)) event.preventDefault();
});
document.addEventListener("click", (event) => {
  if (event.target.closest("[data-print]")) window.print();
});

function closeMore() {
  document.querySelector('.more-toggle')?.setAttribute('aria-expanded', 'false');
  const menu = document.querySelector('.more-menu');
  if (menu) menu.hidden = true;
}
document.addEventListener('click', event => {
  const navigation = document.querySelector('.more-navigation');
  if (!navigation) return;
  if (event.target.closest('.more-toggle')) {
    const menu = navigation.querySelector('.more-menu');
    menu.hidden = !menu.hidden;
    navigation.querySelector('.more-toggle').setAttribute('aria-expanded', String(!menu.hidden));
  } else if (!navigation.contains(event.target) || event.target.closest('.more-menu a')) closeMore();
});
document.addEventListener('keydown', event => {
  if (event.key === 'Escape' && document.querySelector('.more-menu:not([hidden])')) {
    closeMore();
    document.querySelector('.more-toggle').focus();
  }
});
document.addEventListener('focusout', event => {
  const navigation = event.target.closest('.more-navigation');
  if (navigation && !navigation.contains(event.relatedTarget)) closeMore();
});
})();
