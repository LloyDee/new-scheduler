document.querySelectorAll("[data-department-picker]").forEach((picker) => {
  const departmentSelect = picker.querySelector("[data-department-select]");
  if (!departmentSelect) return;
  const memberships = [...picker.querySelectorAll("[data-department-membership]")];
  const updateOptions = () => {
    for (const option of departmentSelect.options) {
      if (!option.value) continue;
      option.disabled = memberships.some((row) =>
        row.dataset.departmentId === option.value && !row.hidden);
    }
    departmentSelect.disabled = memberships.every((row) => !row.hidden);
    departmentSelect.required = !memberships.some((row) => !row.hidden);
  };
  departmentSelect.addEventListener("change", () => {
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

document.addEventListener("submit", (event) => {
  const message = event.target.dataset.confirm;
  if (message && !window.confirm(message)) event.preventDefault();
});
document.addEventListener("click", (event) => {
  if (event.target.closest("[data-print]")) window.print();
});

const moreNavigation = document.querySelector(".more-navigation");
if (moreNavigation) {
  const toggle = moreNavigation.querySelector(".more-toggle");
  const menu = moreNavigation.querySelector(".more-menu");
  const setOpen = (open) => {
    toggle.setAttribute("aria-expanded", String(open));
    menu.hidden = !open;
  };

  toggle.addEventListener("click", () => setOpen(menu.hidden));
  document.addEventListener("click", (event) => {
    if (!moreNavigation.contains(event.target) || event.target.closest(".more-menu a")) {
      setOpen(false);
    }
  });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && !menu.hidden) {
      setOpen(false);
      toggle.focus();
    }
  });
  moreNavigation.addEventListener("focusout", (event) => {
    if (!moreNavigation.contains(event.relatedTarget)) setOpen(false);
  });
}
