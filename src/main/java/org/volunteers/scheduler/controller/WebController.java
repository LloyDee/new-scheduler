package org.volunteers.scheduler.controller;

import org.volunteers.scheduler.model.Assignment;
import org.volunteers.scheduler.model.Department;
import org.volunteers.scheduler.model.Event;
import org.volunteers.scheduler.model.Shift;
import org.volunteers.scheduler.model.Volunteer;
import org.volunteers.scheduler.service.SchedulerService;
import org.volunteers.scheduler.service.BackupService;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class WebController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WebController.class);
    private final SchedulerService service;
    private final BackupService backups;
    private final org.volunteers.scheduler.service.PhoneAccess access;
    private final org.volunteers.scheduler.service.VolunteerImportService imports;
    public WebController(SchedulerService service, BackupService backups, org.volunteers.scheduler.service.VolunteerImportService imports, org.volunteers.scheduler.service.PhoneAccess access) {
        this.access = access; this.service = service; this.backups = backups; this.imports = imports;
    }

    @GetMapping("/settings/data/volunteers/template.csv")
    ResponseEntity<String> volunteerTemplate(HttpSession session) {
        Long eventId = (Long) session.getAttribute("activeEvent");
        if (eventId == null) throw new IllegalArgumentException("Choose an active event first.");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=volunteers.csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(imports.template(eventId));
    }

    @PostMapping("/settings/data/volunteers/import")
    String importVolunteers(@RequestParam("file") MultipartFile file, HttpSession session, RedirectAttributes flash) {
        Long eventId = (Long) session.getAttribute("activeEvent");
        try {
            if (eventId == null) throw new IllegalArgumentException("Choose an active event first.");
            int count = imports.importFile(eventId, file);
            flash.addFlashAttribute("importNotice", count + (count == 1 ? " volunteer imported." : " volunteers imported."));
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("importError", ex.getMessage() + " No volunteers were imported.");
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            log.error("Volunteer import rejected by the database for event {}", eventId, ex);
            flash.addFlashAttribute("importError", "Import failed because the database rejected a record. No volunteers were imported. Contact the administrator.");
        }
        return "redirect:/settings#data";
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String invalidRequest(Model model) {
        model.addAttribute("appVersion", new org.volunteers.scheduler.version.Version());
        model.addAttribute("status", 400); return "error";
    }

    @ModelAttribute
    void common(Model model, HttpSession session, HttpServletRequest request,
                @CookieValue(name = "schedulerTimeFormat", defaultValue = "12") String format) {
        model.addAttribute("event", null);
        Object id = session.getAttribute("activeEvent");
        String path = org.springframework.web.util.UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        boolean eventPage = path.equals("/events") || path.matches("/events/\\d+/(home|volunteers|departments|schedule|reports|more)");
        if (id instanceof Long eventId && !eventPage && Set.of("GET", "HEAD").contains(request.getMethod())) {
            try { model.addAttribute("event", service.event(eventId)); }
            catch (org.springframework.web.server.ResponseStatusException ex) { session.removeAttribute("activeEvent"); }
        }
        model.addAttribute("timeFormat", "24".equals(format) ? "24" : "12");
        model.addAttribute("timePattern", "24".equals(format) ? "HH:mm" : "h:mm a");
    }

    @GetMapping("/")
    String index(HttpSession session) {
        Long id = (Long) session.getAttribute("activeEvent");
        return id == null ? "redirect:/events" : "redirect:/events/" + id + "/home";
    }

    @GetMapping("/events")
    String events(@RequestParam(required = false) Long edit,
                  @RequestParam(defaultValue = "false") boolean create, @RequestParam(defaultValue = "0") int page,
                  Model model, HttpSession session) {
        var eventPage = service.eventsPage(page);
        var events = eventPage.getContent();
        Set<Long> manageable = new HashSet<>(), administrable = new HashSet<>();
        for (Event listed : events) {
            if (access.canManage(session, listed.getId())) manageable.add(listed.getId());
            if (access.canAdminister(session, listed.getId())) administrable.add(listed.getId());
        }
        model.addAttribute("manageableEventIds", manageable);
        model.addAttribute("administrableEventIds", administrable);
        model.addAttribute("listPage", eventPage);
        model.addAttribute("events", events); model.addAttribute("types", Event.Type.values());
        Event active = events.stream().filter(e -> e.getId().equals(session.getAttribute("activeEvent"))).findFirst().orElse(null);
        if (active == null && session.getAttribute("activeEvent") instanceof Long activeId) {
            try { active = service.event(activeId); }
            catch (org.springframework.web.server.ResponseStatusException ex) { session.removeAttribute("activeEvent"); }
        }
        model.addAttribute("event", active);
        Map<String, String> defaults = new HashMap<>();
        if (edit != null) {
            Event e = events.stream().filter(item -> item.getId().equals(edit)).findFirst().orElseGet(() -> service.event(edit));
            defaults.putAll(Map.of("name", e.getName(), "type", e.getType().name(), "startDate", e.getStartDate().toString(),
                    "endDate", e.getEndDate().toString(), "venue", e.getVenue()));
            defaults.put("assembly", e.getAssembly() == null ? "" : e.getAssembly());
        }
        draft(model, defaults); model.addAttribute("editId", edit);
        model.addAttribute("showEditor", create || edit != null || model.containsAttribute("error"));
        return "pages/events";
    }

    @PostMapping("/events/save")
    String saveEvent(@RequestParam(required = false) Long id, @RequestParam Map<String, String> input,
                     RedirectAttributes flash, HttpSession session) {
        return attempt(() -> {
            Event e = access.saveEvent(id, input, session); session.setAttribute("activeEvent", e.getId());
            return "/events/" + e.getId() + "/home";
        }, id == null ? "/events?create=true" : "/events" + editQuery(id), input, flash, "Event saved.");
    }

    @PostMapping("/events/{id}/select")
    String select(@PathVariable long id, HttpSession session) {
        service.event(id); session.setAttribute("activeEvent", id); return "redirect:/events/" + id + "/home";
    }

    @PostMapping("/events/{id}/delete")
    String deleteEvent(@PathVariable long id, HttpSession session, RedirectAttributes flash) {
        service.deleteEvent(id);
        if (Objects.equals(session.getAttribute("activeEvent"), id)) session.removeAttribute("activeEvent");
        flash.addFlashAttribute("notice", "Event and its data deleted."); return "redirect:/events";
    }

    @GetMapping("/events/{eventId}/home")
    String home(@PathVariable long eventId, Model model, HttpSession session) {
        var data = prepare(eventId, model, session);
        model.addAttribute("missing", data.rows().stream().mapToInt(SchedulerService.ShiftRow::getMissing).sum());
        LocalDateTime now = LocalDateTime.now();
        Set<Long> ownIds = access.ids(session);
        model.addAttribute("upcoming", data.rows().stream().filter(r ->
                        r.assignments().stream().anyMatch(a -> ownIds.contains(a.getVolunteer().getId())) &&
                        LocalDateTime.of(r.shift().getDate(), r.shift().getEndTime()).isAfter(now))
                .toList());
        return "pages/home";
    }

    @GetMapping("/events/{eventId}/volunteers")
    String volunteers(@PathVariable long eventId, @RequestParam(defaultValue = "") String q,
                      @RequestParam(required = false) Long departmentId,
                      @RequestParam(required = false) Long edit, @RequestParam(required = false) Long view,
                      @RequestParam(required = false) Long assignments,
                      @RequestParam(defaultValue = "0") int page,
                      @RequestParam(defaultValue = "false") boolean create,
                      Model model, HttpSession session, HttpServletRequest request) {
        final Long selectedDepartment = request.getParameter("departmentId") == null ? access.department(session, eventId) : departmentId;
        var details = java.util.stream.Stream.of(edit, view, assignments).filter(Objects::nonNull).toList();
        var result = service.volunteerPage(eventId, selectedDepartment, q, page, details);
        var data = result.snapshot();
        prepare(data, model, session);
        model.addAttribute("listPage", result.page());
        String query = q.strip().toLowerCase(Locale.ROOT);
        model.addAttribute("q", q);
        model.addAttribute("departmentId", selectedDepartment);
        model.addAttribute("filteredVolunteers", result.page().getContent());
        Map<String, String> defaults = new HashMap<>();
        if (edit != null) {
            Volunteer v = snapshotVolunteer(data, edit);
            defaults.putAll(Map.of("name", v.getName(), "phone", v.getPhone(), "email", v.getEmail(),
                    "congregation", v.getCongregation(), "notes", v.getNotes()));
            if (v.isEventAdministrator() && v.getDepartmentRoles().isEmpty()) defaults.put("departmentChoice", "none");
            defaults.put("eventAdministrator", Boolean.toString(v.isEventAdministrator()));
            defaults.put("privilege", v.getPrivilege() == null ? "" : v.getPrivilege().name());
            v.getDepartmentRoles().forEach((department, role) -> defaults.put("departmentRole_" + department.getId(), role.name()));
        }
        Map<Long, Volunteer> dialogVolunteers = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        List<Volunteer> visible = (List<Volunteer>) model.getAttribute("filteredVolunteers");
        visible.forEach(v -> dialogVolunteers.put(v.getId(), v));
        if (view != null) dialogVolunteers.put(view, snapshotVolunteer(data, view));
        model.addAttribute("dialogVolunteers", dialogVolunteers.values());
        model.addAttribute("openVolunteerId", view);
        model.addAttribute("currentVolunteerId", access.ownVolunteerId(session, eventId));
        Map<Long, List<SchedulerService.ShiftRow>> volunteerAssignments = new HashMap<>();
        var rows = data.rows();
        dialogVolunteers.keySet().forEach(id -> volunteerAssignments.put(id, rows.stream()
                .filter(row -> row.assignments().stream().anyMatch(a -> a.getVolunteer().getId().equals(id))).toList()));
        model.addAttribute("volunteerAssignments", volunteerAssignments);
        if (assignments != null) {
            model.addAttribute("assignmentVolunteer", snapshotVolunteer(data, assignments));
            model.addAttribute("personalRows", data.rows().stream()
                    .filter(row -> row.assignments().stream().anyMatch(a -> a.getVolunteer().getId().equals(assignments))).toList());
        }
        draft(model, defaults); model.addAttribute("editId", edit);
        model.addAttribute("showEditor", create || edit != null || model.containsAttribute("error") && model.containsAttribute("draft"));
        model.addAttribute("volunteerRoles", java.util.Arrays.stream(Volunteer.Role.values()).filter(role -> role != Volunteer.Role.ADMIN).toList());
        model.addAttribute("volunteerPrivileges", Volunteer.Privilege.values());
        return "pages/volunteers";
    }

    @PostMapping("/events/{eventId}/volunteers/save")
    String saveVolunteer(@PathVariable long eventId, @RequestParam(required = false) Long id,
                         @RequestParam Map<String, String> input, RedirectAttributes flash, HttpSession session) {
        String base = "/events/" + eventId + "/volunteers";
        return attempt(() -> { if (access.canManage(session, eventId)) access.saveManagedVolunteer(eventId, id, input, session);
            else access.saveOwn(eventId, id, input, session); return base; },
                base + editQuery(id) + "#editor", input, flash, "Volunteer saved.");
    }

    @PostMapping("/events/{eventId}/volunteers/{id}/delete")
    String deleteVolunteer(@PathVariable long eventId, @PathVariable long id, RedirectAttributes flash, HttpSession session) {
        try { access.deleteManagedVolunteer(eventId, id, session); flash.addFlashAttribute("notice", "Volunteer and their assignments deleted."); }
        catch (IllegalArgumentException ex) { flash.addFlashAttribute("error", ex.getMessage()); }
        return "redirect:/events/" + eventId + "/volunteers";
    }

    @GetMapping("/events/{eventId}/departments")
    String departments(@PathVariable long eventId, @RequestParam(required = false) Long edit,
                       @RequestParam(required = false) Long view, @RequestParam(defaultValue = "false") boolean create,
                       @RequestParam(defaultValue = "0") int page,
                       Model model, HttpSession session) {
        var data = prepare(eventId, model, session);
        var departmentPage = page(data.departments(), page);
        model.addAttribute("listPage", departmentPage);
        model.addAttribute("departments", departmentPage.getContent());
        Map<String, String> defaults = new HashMap<>();
        if (edit != null) {
            Department d = snapshotDepartment(data, edit);
            defaults.putAll(Map.of("name", d.getName(), "description", d.getDescription()));
        }
        Map<Long, Map<String, Object>> departmentDetails = new HashMap<>();
        Map<Long, List<SchedulerService.ShiftRow>> departmentSchedules = new HashMap<>();
        var rows = data.rows();
        for (Department d : departmentPage.getContent()) {
            departmentSchedules.put(d.getId(), rows.stream().filter(row -> row.shift().getDepartment().getId().equals(d.getId())).toList());
            List<Volunteer> members = data.volunteers().stream()
                    .filter(v -> v.getDepartmentRoles().keySet().stream().anyMatch(dept -> dept.getId().equals(d.getId()))).toList();
            Map<String, Object> details = new HashMap<>();
            details.put("volunteerCount", members.size());
            for (Volunteer.Role role : List.of(Volunteer.Role.OVERSEER, Volunteer.Role.ASSISTANT_OVERSEER)) {
                List<Volunteer> names = members.stream()
                        .filter(v -> v.getDepartmentRoles().entrySet().stream()
                                .anyMatch(entry -> entry.getKey().getId().equals(d.getId()) && entry.getValue() == role))
                        .toList();
                details.put(role.name(), names);
            }
            departmentDetails.put(d.getId(), details);
        }
        model.addAttribute("departmentDetails", departmentDetails);
        model.addAttribute("departmentSchedules", departmentSchedules);
        if (view != null) {
            model.addAttribute("selectedDepartment", snapshotDepartment(data, view));
            model.addAttribute("departmentRows", data.rows().stream().filter(r -> r.shift().getDepartment().getId().equals(view)).toList());
        }
        draft(model, defaults); model.addAttribute("editId", edit);
        model.addAttribute("showEditor", create || edit != null || model.containsAttribute("error") && model.containsAttribute("draft"));
        return "pages/departments";
    }

    @PostMapping("/events/{eventId}/departments/save")
    String saveDepartment(@PathVariable long eventId, @RequestParam(required = false) Long id,
                          @RequestParam Map<String, String> input, RedirectAttributes flash) {
        String base = "/events/" + eventId + "/departments";
        return attempt(() -> { service.saveDepartment(eventId, id, input); return base; },
                base + editQuery(id) + "#editor", input, flash, "Department saved.");
    }

    @PostMapping("/events/{eventId}/departments/{id}/delete")
    String deleteDepartment(@PathVariable long eventId, @PathVariable long id, RedirectAttributes flash) {
        service.deleteDepartment(eventId, id); flash.addFlashAttribute("notice", "Department, shifts, and assignments deleted.");
        return "redirect:/events/" + eventId + "/departments";
    }

    @GetMapping("/events/{eventId}/schedule")
    String schedule(@PathVariable long eventId, @RequestParam(required = false) Long edit,
                    @RequestParam(defaultValue = "false") boolean create,
                    @RequestParam(required = false) Long departmentId, @RequestParam(required = false) LocalDate date,
                    @RequestParam(defaultValue = "0") int page,
                    Model model, HttpSession session, HttpServletRequest request) {
        var data = prepare(eventId, model, session);
        if (request.getParameter("departmentId") == null) departmentId = access.department(session, eventId);
        filter(data, departmentId, date, model);
        @SuppressWarnings("unchecked") var filteredRows = (List<SchedulerService.ShiftRow>) model.getAttribute("rows");
        var shiftPage = page(filteredRows, page);
        model.addAttribute("listPage", shiftPage);
        model.addAttribute("rows", shiftPage.getContent());
        Map<Long, List<Volunteer>> assignmentOptions = new HashMap<>();
        Map<Long, Set<Long>> assignedVolunteerIds = new HashMap<>();
        shiftPage.getContent().stream().map(SchedulerService.ShiftRow::shift).forEach(s -> {
            Set<Long> current = new HashSet<>();
            data.assignments().stream().filter(a -> a.getShift().getId().equals(s.getId()))
                    .forEach(a -> current.add(a.getVolunteer().getId()));
            assignedVolunteerIds.put(s.getId(), current);
            Set<Long> eligible = new HashSet<>(current);
            data.availableVolunteers(s).stream()
                    .filter(v -> v.getDepartmentRoles().keySet().stream()
                            .anyMatch(d -> d.getId().equals(s.getDepartment().getId())))
                    .forEach(v -> eligible.add(v.getId()));
            assignmentOptions.put(s.getId(), data.volunteers().stream()
                    .filter(v -> eligible.contains(v.getId())).toList());
        });
        model.addAttribute("assignmentOptions", assignmentOptions);
        model.addAttribute("assignedVolunteerIds", assignedVolunteerIds);
        Map<String, String> defaults = new HashMap<>(Map.of("date", data.event().getStartDate().toString(), "requiredCount", "1"));
        if (departmentId != null) defaults.put("departmentId", departmentId.toString());
        if (edit != null) {
            Shift s = data.shifts().stream().filter(item -> item.getId().equals(edit)).findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND));
            defaults.putAll(Map.of("name", s.getName(), "departmentId", s.getDepartment().getId().toString(), "date", s.getDate().toString(),
                    "startTime", s.getStartTime().toString(), "endTime", s.getEndTime().toString(),
                    "requiredCount", Integer.toString(s.getRequiredCount()), "notes", s.getNotes()));
        }
        draft(model, defaults); model.addAttribute("editId", edit);
        model.addAttribute("shiftTimes", java.util.stream.IntStream.rangeClosed(0, 46)
                .mapToObj(i -> java.time.LocalTime.of(6, 0).plusMinutes(i * 15L)).toList());
        model.addAttribute("showEditor", create || edit != null || model.containsAttribute("error") && model.containsAttribute("draft"));
        return "pages/schedule";
    }

    @PostMapping("/events/{eventId}/schedule/save")
    String saveShift(@PathVariable long eventId, @RequestParam(required = false) Long id,
                     @RequestParam Map<String, String> input, RedirectAttributes flash) {
        String base = "/events/" + eventId + "/schedule";
        return attempt(() -> { service.saveShift(eventId, id, input); return base; },
                base + editQuery(id) + "#editor", input, flash, "Shift saved.");
    }

    @PostMapping("/events/{eventId}/schedule/{id}/delete")
    String deleteShift(@PathVariable long eventId, @PathVariable long id, RedirectAttributes flash) {
        service.deleteShift(eventId, id); flash.addFlashAttribute("notice", "Shift and its assignments deleted.");
        return "redirect:/events/" + eventId + "/schedule";
    }

    @PostMapping("/events/{eventId}/schedule/{id}/assign")
    String assign(@PathVariable long eventId, @PathVariable long id, @RequestParam Map<String, String> input,
                  RedirectAttributes flash) {
        String base = "/events/" + eventId + "/schedule";
        try {
            service.assign(eventId, id, SchedulerService.number(input, "volunteerId"), SchedulerService.confirmed(input));
            flash.addFlashAttribute("notice", "Volunteer assigned.");
        } catch (SchedulerService.OverlapException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
            flash.addFlashAttribute("pending", Map.of("shiftId", Long.toString(id), "volunteerId", input.get("volunteerId")));
        } catch (IllegalArgumentException ex) { flash.addFlashAttribute("error", ex.getMessage()); }
        return "redirect:" + base + "#shift-" + id;
    }

    @PostMapping("/events/{eventId}/schedule/{id}/assign-batch")
    String assignBatch(@PathVariable long eventId, @PathVariable long id,
                       @RequestParam(required = false) List<Long> volunteerIds, RedirectAttributes flash) {
        try {
            service.assignBatch(eventId, id, volunteerIds);
            flash.addFlashAttribute("notice", "Volunteers assigned.");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/events/" + eventId + "/schedule#shift-" + id;
    }

    @PostMapping("/events/{eventId}/schedule/{id}/assignments")
    String updateAssignments(@PathVariable long eventId, @PathVariable long id,
                             @RequestParam(required = false) List<Long> volunteerIds, RedirectAttributes flash) {
        try {
            service.updateAssignments(eventId, id, volunteerIds == null ? List.of() : volunteerIds);
            flash.addFlashAttribute("notice", "Volunteers updated.");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/events/" + eventId + "/schedule#shift-" + id;
    }

    @PostMapping("/events/{eventId}/assignments/{id}/delete")
    String unassign(@PathVariable long eventId, @PathVariable long id, RedirectAttributes flash) {
        service.unassign(eventId, id); flash.addFlashAttribute("notice", "Assignment removed.");
        return "redirect:/events/" + eventId + "/schedule";
    }

    @GetMapping("/events/{eventId}/more")
    String more(@PathVariable long eventId, Model model, HttpSession session) {
        model.addAttribute("event", service.event(eventId));
        model.addAttribute("base", "/events/" + eventId);
        session.setAttribute("activeEvent", eventId);
        return "pages/more";
    }

    @GetMapping("/settings")
    String settings(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return "pages/settings";
    }

    @PostMapping("/settings")
    String settings(@RequestParam(defaultValue = "12") String timeFormat, HttpServletResponse response,
                    RedirectAttributes flash) {
        Cookie cookie = new Cookie("schedulerTimeFormat", "24".equals(timeFormat) ? "24" : "12");
        cookie.setPath("/"); cookie.setHttpOnly(true); cookie.setMaxAge(365 * 24 * 60 * 60); cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie); flash.addFlashAttribute("notice", "Display preferences saved.");
        return "redirect:/settings";
    }

    @GetMapping("/events/{eventId}/reports")
    String reports(@PathVariable long eventId, @RequestParam(defaultValue = "department") String type,
                   @RequestParam(required = false) Long departmentId, @RequestParam(required = false) LocalDate date,
                   Model model, HttpSession session) {
        var data = prepare(eventId, model, session);
        boolean backupView = "backup".equals(type) || BackupService.supports(type);
        model.addAttribute("backup", backupView);
        if (backupView) {
            var backup = backup(eventId, session);
            model.addAttribute("backupCounts", backup.counts());
            model.addAttribute("backupToken", backup.token());
            departmentId = null; date = null;
        }
        var rows = filter(data, departmentId, date, model);
        model.addAttribute("reportType", backupView ? "backup" : reportType(type));
        model.addAttribute("volunteerRows", volunteerReport(data, rows, departmentId != null || date != null));
        return "pages/reports";
    }

    @GetMapping("/events/{eventId}/reports.csv")
    ResponseEntity<byte[]> csv(@PathVariable long eventId, @RequestParam(defaultValue = "department") String type,
                               @RequestParam(required = false) Long departmentId, @RequestParam(required = false) LocalDate date,
                               @RequestParam(required = false) String snapshot, HttpSession session) {
        if (BackupService.supports(type)) {
            var backup = backup(eventId, session);
            if (snapshot != null && !snapshot.equals(backup.token())) throw new IllegalArgumentException("Backup expired. Reload Reports.");
            return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"event-" + eventId + "-" + backup.token() + "-" + type + ".csv\"")
                    .body(backup.files().get(type));
        }
        var data = service.snapshot(eventId); String selected = reportType(type);
        List<SchedulerService.ShiftRow> rows = filtered(data, departmentId, date);
        StringBuilder out = new StringBuilder("\ufeff");
        if (selected.equals("volunteer")) {
            csvLine(out, "Volunteer", "Congregation", "Department", "Shift", "Date", "Start", "End");
            for (VolunteerReport v : volunteerReport(data, rows, departmentId != null || date != null)) {
                if (v.assignments().isEmpty()) csvLine(out, v.volunteer().getName(), v.volunteer().getCongregation(), "Unassigned", "", "", "", "");
                for (Assignment a : v.assignments()) csvLine(out, v.volunteer().getName(), v.volunteer().getCongregation(),
                        a.getShift().getDepartment().getName(), a.getShift().getName(), a.getShift().getDate(), a.getShift().getStartTime(), a.getShift().getEndTime());
            }
        } else {
            csvLine(out, "Department", "Shift", "Date", "Start", "End", "Required", "Assigned", "Missing", "Status", "Volunteers");
            for (var r : rows) csvLine(out, r.shift().getDepartment().getName(), r.shift().getName(), r.shift().getDate(), r.shift().getStartTime(),
                    r.shift().getEndTime(), r.shift().getRequiredCount(), r.assignments().size(), r.getMissing(), r.getStatus(),
                    String.join("; ", r.assignments().stream().map(a -> a.getVolunteer().getName()).toList()));
        }
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"event-" + eventId + "-" + selected + ".csv\"")
                .body(out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private BackupService.Backup backup(long eventId, HttpSession session) {
        service.event(eventId);
        synchronized (session) {
            Object saved = session.getAttribute("csvBackup");
            if (saved instanceof BackupService.Backup backup && backup.eventId() == eventId) return backup;
            var backup = backups.capture(eventId);
            session.setAttribute("csvBackup", backup);
            return backup;
        }
    }

    @PostMapping("/events/{eventId}/backups/refresh")
    String refreshBackup(@PathVariable long eventId, HttpSession session, RedirectAttributes flash) {
        synchronized (session) { session.setAttribute("csvBackup", backups.capture(eventId)); }
        flash.addFlashAttribute("notice", "New backup captured. Download all three CSV files from this snapshot.");
        return "redirect:/events/" + eventId + "/reports?type=backup";
    }

    @PostMapping("/events/{eventId}/backups/restore")
    String restoreBackup(@PathVariable long eventId, @RequestParam(required = false) MultipartFile departments,
                         @RequestParam(required = false) MultipartFile volunteers, @RequestParam(required = false) MultipartFile shifts,
                         @RequestParam(defaultValue = "false") boolean confirmReplace,
                         HttpSession session, RedirectAttributes flash) {
        service.event(eventId);
        try {
            if (!confirmReplace) throw new IllegalArgumentException("Confirm replacement of this event's data before restoring.");
            if (departments == null || volunteers == null || shifts == null)
                throw new IllegalArgumentException("Choose all three backup CSV files.");
            Map<String, byte[]> files = new HashMap<>();
            for (var entry : Map.of("departments", departments, "volunteers", volunteers, "shifts", shifts).entrySet()) {
                var file = entry.getValue();
                if (file.isEmpty() || file.getOriginalFilename() == null || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".csv"))
                    throw new IllegalArgumentException(entry.getKey() + ": Choose a backup CSV file.");
                files.put(entry.getKey(), file.getBytes());
            }
            var counts = backups.restore(eventId, files);
            synchronized (session) { session.removeAttribute("csvBackup"); }
            flash.addFlashAttribute("notice", "Backup restored: " + counts.get("departments") + " departments, "
                    + counts.get("volunteers") + " volunteers, " + counts.get("shifts") + " shifts, " + counts.get("assignments") + " assignments.");
        } catch (IllegalArgumentException | java.io.IOException ex) {
            flash.addFlashAttribute("error", "Restore failed. Existing data was kept. " + ex.getMessage());
        } catch (org.springframework.dao.DataAccessException ex) {
            flash.addFlashAttribute("error", "Restore failed because the data could not be saved. Existing data was kept.");
        }
        return "redirect:/events/" + eventId + "/reports?type=backup";
    }

    private static Volunteer snapshotVolunteer(SchedulerService.Snapshot data, long id) {
        return data.volunteers().stream().filter(v -> v.getId().equals(id)).findFirst()
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    private static Department snapshotDepartment(SchedulerService.Snapshot data, long id) {
        return data.departments().stream().filter(d -> d.getId().equals(id)).findFirst()
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    private SchedulerService.Snapshot prepare(long eventId, Model model, HttpSession session) {
        var data = service.snapshot(eventId);
        prepare(data, model, session);
        return data;
    }
    private void prepare(SchedulerService.Snapshot data, Model model, HttpSession session) {
        long eventId = data.event().getId(); session.setAttribute("activeEvent", eventId);
        model.addAttribute("event", data.event()); model.addAttribute("base", "/events/" + eventId);
        model.addAttribute("volunteers", data.volunteers()); model.addAttribute("departments", data.departments());
        model.addAttribute("allRows", data.rows());
    }
    private static <T> org.springframework.data.domain.Page<T> page(List<T> items, int number) {
        int safePage = Math.max(0, number);
        int start = (int) Math.min((long) safePage * 25, items.size());
        return new org.springframework.data.domain.PageImpl<>(items.subList(start, Math.min(start + 25, items.size())),
            org.springframework.data.domain.PageRequest.of(safePage, 25), items.size());
    }
    private List<SchedulerService.ShiftRow> filter(SchedulerService.Snapshot data, Long departmentId, LocalDate date, Model model) {
        var rows = filtered(data, departmentId, date);
        model.addAttribute("rows", rows); model.addAttribute("departmentId", departmentId); model.addAttribute("date", date); return rows;
    }
    private List<SchedulerService.ShiftRow> filtered(SchedulerService.Snapshot data, Long departmentId, LocalDate date) {
        if (departmentId != null && data.departments().stream().noneMatch(d -> d.getId().equals(departmentId)))
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
        return data.rows().stream().filter(r -> departmentId == null || r.shift().getDepartment().getId().equals(departmentId))
                .filter(r -> date == null || r.shift().getDate().equals(date)).toList();
    }
    private static List<VolunteerReport> volunteerReport(SchedulerService.Snapshot data, List<SchedulerService.ShiftRow> rows, boolean filtered) {
        List<Assignment> selected = rows.stream().flatMap(r -> r.assignments().stream()).toList();
        return data.volunteers().stream().map(v -> new VolunteerReport(v, selected.stream()
                .filter(a -> a.getVolunteer().getId().equals(v.getId())).toList())).filter(v -> !filtered || !v.assignments().isEmpty()).toList();
    }
    private static String reportType(String value) {
        if (!Set.of("department", "volunteer", "coverage").contains(value) && !BackupService.supports(value)) throw new IllegalArgumentException("Unknown report type.");
        return value;
    }
    static void csvLine(StringBuilder out, Object... values) {
        StringJoiner line = new StringJoiner(",");
        for (Object value : values) {
            String text = Objects.toString(value, "");
            if (text.stripLeading().matches("(?s)^[=+@\\-\\t\\r\\n].*")) text = "'" + text;
            line.add("\"" + text.replace("\"", "\"\"") + "\"");
        }
        out.append(line).append("\r\n");
    }
    @SuppressWarnings("unchecked")
    private static void draft(Model model, Map<String, String> defaults) {
        Object previous = model.getAttribute("draft");
        if (previous instanceof Map<?, ?> map) defaults.putAll((Map<String, String>) map);
        model.addAttribute("draft", defaults);
    }
    private static String editQuery(Long id) { return id == null ? "" : "?edit=" + id; }
    private static String attempt(Supplier<String> work, String failureUrl, Map<String, String> input,
                                  RedirectAttributes flash, String notice) {
        try { String target = work.get(); flash.addFlashAttribute("notice", notice); return "redirect:" + target; }
        catch (IllegalArgumentException ex) {
            Map<String, String> draft = new HashMap<>(input); draft.remove("_csrf"); draft.remove("confirmOverlap");
            flash.addFlashAttribute("draft", draft); flash.addFlashAttribute("error", ex.getMessage());
            if (ex instanceof SchedulerService.OverlapException) flash.addFlashAttribute("overlap", true);
            return "redirect:" + failureUrl;
        }
    }
    public record VolunteerReport(Volunteer volunteer, List<Assignment> assignments) { }
}

// Multipart parsing can fail before Spring selects a controller, so this handler is global.
@ControllerAdvice
class BackupUploadErrors {
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    String backupTooLarge(HttpServletRequest request, RedirectAttributes flash) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.equals("/settings/data/volunteers/import")) {
            flash.addFlashAttribute("importError", "File exceeds the 10 MB upload limit. No volunteers were imported.");
            return "redirect:/settings#data";
        }
        flash.addFlashAttribute("error", "Restore failed. Backup files exceed the upload size limit. Existing data was kept.");
        if (path.matches("/events/[0-9]+/backups/restore"))
            return "redirect:" + path.replace("/backups/restore", "/reports?type=backup");
        return "redirect:/";
    }
}
