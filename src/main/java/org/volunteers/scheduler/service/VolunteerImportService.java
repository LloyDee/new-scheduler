package org.volunteers.scheduler.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.commons.csv.*;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.volunteers.scheduler.model.*;
import org.volunteers.scheduler.repository.*;

@Service
public class VolunteerImportService {
    private static final List<String> REQUIRED = List.of("name", "phone", "email", "congregation", "privilege");
    private final SchedulerService scheduler;
    private final Events events;
    private final Volunteers volunteers;
    private final Departments departments;

    public VolunteerImportService(SchedulerService scheduler, Events events, Volunteers volunteers, Departments departments) {
        this.scheduler = scheduler; this.events = events; this.volunteers = volunteers; this.departments = departments;
    }

    public String template(long eventId) {
        scheduler.event(eventId);
        try {
            StringWriter out = new StringWriter();
            try (CSVPrinter csv = new CSVPrinter(out, CSVFormat.DEFAULT)) {
                List<String> headers = new ArrayList<>(List.of("Name", "Phone", "Email", "Congregation", "Privilege", "Notes"));
                departments.findByEventIdOrderByNameAscIdAsc(eventId).forEach(d -> headers.add("Department: " + d.getName()));
                csv.printRecord(headers);
            }
            return out.toString();
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    @Transactional
    public int importFile(long eventId, MultipartFile file) {
        events.lockForVolunteerUpdate(eventId).orElseThrow(() -> new IllegalArgumentException("Choose an active event first."));
        if (file.isEmpty()) throw new IllegalArgumentException("Choose a non-empty CSV or Excel file.");
        if (file.getSize() > 10 * 1024 * 1024) throw new IllegalArgumentException("File exceeds the 10 MB upload limit.");
        List<Line> lines = read(file);
        if (lines.size() < 2) throw new IllegalArgumentException("The file must contain a header and at least one volunteer.");
        List<String> headers = lines.getFirst().cells();
        Map<String, List<Department>> byName = new HashMap<>();
        departments.findByEventIdOrderByNameAscIdAsc(eventId).forEach(d -> byName.computeIfAbsent(key(d.getName()), k -> new ArrayList<>()).add(d));
        List<String> fields = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String header : headers) {
            String normalized = key(header);
            if (!seen.add(normalized)) throw new IllegalArgumentException("Duplicate column: " + header);
            if (REQUIRED.contains(normalized) || normalized.equals("notes")) fields.add(normalized);
            else if (normalized.startsWith("department:")) {
                List<Department> matches = byName.getOrDefault(key(normalized.substring(11)), List.of());
                if (matches.size() != 1) throw new IllegalArgumentException("Unknown or ambiguous department column: " + header);
                fields.add("departmentRole_" + matches.getFirst().getId());
            } else throw new IllegalArgumentException("Unknown column: " + header);
        }
        for (String required : REQUIRED) if (!seen.contains(required)) throw new IllegalArgumentException("Missing required column: " + required);
        if (new HashSet<>(fields).size() != fields.size()) throw new IllegalArgumentException("Duplicate department columns refer to the same department.");
        Map<String, String> names = new HashMap<>(), phones = new HashMap<>();
        for (Volunteer v : volunteers.findByEventIdOrderByNameAscIdAsc(eventId)) {
            names.put(key(v.getName()), "an existing volunteer");
            phones.put(digits(v.getPhone()), "an existing volunteer");
        }
        int count = 0;
        for (Line line : lines.subList(1, lines.size())) {
            if (line.cells().size() != headers.size()) throw error(line.row(), "columns", "Expected " + headers.size() + " values.");
            Map<String, String> input = new LinkedHashMap<>();
            for (int i = 0; i < fields.size(); i++) {
                String field = fields.get(i), value = line.cells().get(i).strip();
                if (field.equals("privilege") && !value.isEmpty()) value = privilege(value, line.row());
                if (field.startsWith("departmentRole_") && !value.isEmpty()) value = role(value, line.row(), headers.get(i));
                input.put(field, value);
            }
            duplicate(names, key(input.get("name")), line.row(), "Name");
            duplicate(phones, digits(input.get("phone")), line.row(), "Phone");
            if (input.entrySet().stream().noneMatch(e -> e.getKey().startsWith("departmentRole_") && !e.getValue().isBlank())) input.put("departmentChoice", "none");
            try { scheduler.saveVolunteer(eventId, null, input); }
            catch (IllegalArgumentException ex) { throw error(line.row(), validationColumn(ex.getMessage(), headers), ex.getMessage()); }
            count++;
        }
        return count;
    }

    private static void duplicate(Map<String, String> values, String value, int row, String column) {
        String previous = values.putIfAbsent(value, "row " + row);
        if (previous != null) throw error(row, column, "Duplicate of " + previous + ".");
    }
    private static String validationColumn(String message, List<String> headers) {
        String normalized = key(message);
        for (String field : List.of("phone", "email", "congregation", "notes", "name", "privilege")) {
            if (normalized.contains(field)) return Character.toUpperCase(field.charAt(0)) + field.substring(1);
        }
        for (String header : headers) {
            if (key(header).startsWith("department:") && normalized.startsWith(key(header.substring(header.indexOf(':') + 1)))) return header;
        }
        return "Departments";
    }
    private static String privilege(String value, int row) {
        for (Volunteer.Privilege p : Volunteer.Privilege.values()) if (key(p.name()).equals(key(value)) || key(p.getLabel()).equals(key(value))) return p.name();
        throw error(row, "Privilege", "Choose Elder, Ministerial Servant, or Publisher.");
    }
    private static String role(String value, int row, String column) {
        for (Volunteer.Role r : Volunteer.Role.values()) if (key(r.name()).equals(key(value)) || key(r.getLabel()).equals(key(value))) return r.name();
        throw error(row, column, "Unknown role: " + value);
    }
    private static String key(String value) { return value == null ? "" : value.strip().toLowerCase(Locale.ROOT); }
    private static String digits(String value) { return value == null ? "" : value.replaceAll("[^0-9]", ""); }
    private static IllegalArgumentException error(int row, String column, String message) { return new IllegalArgumentException("Row " + row + ", " + column + ": " + message); }
    private record Line(int row, List<String> cells) { }

    private static List<Line> read(MultipartFile file) {
        String name = key(file.getOriginalFilename());
        List<Line> lines = new ArrayList<>();
        try (InputStream stream = file.getInputStream()) {
            if (name.endsWith(".csv")) {
                String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                if (text.startsWith("\uFEFF")) text = text.substring(1);
                try (CSVParser parser = CSVParser.parse(text, CSVFormat.DEFAULT)) {
                    for (CSVRecord record : parser) {
                        List<String> cells = record.toList();
                        if (cells.stream().anyMatch(s -> !s.isBlank())) lines.add(new Line((int) record.getRecordNumber(), cells));
                    }
                }
            } else if (name.endsWith(".xls") || name.endsWith(".xlsx")) {
                try (Workbook workbook = WorkbookFactory.create(stream)) {
                    if (workbook.getNumberOfSheets() == 0) throw new IllegalArgumentException("The workbook has no worksheets.");
                    Sheet sheet = workbook.getSheetAt(0);
                    DataFormatter formatter = new DataFormatter(Locale.US);
                    int width = -1;
                    for (Row row : sheet) {
                        List<String> cells = new ArrayList<>();
                        int size = Math.max(width, row.getLastCellNum());
                        for (int i = 0; i < size; i++) {
                            Cell cell = row.getCell(i);
                            if (cell != null && (cell.getCellType() == CellType.FORMULA || cell.getCellType() == CellType.ERROR)) throw error(row.getRowNum() + 1, "column " + (i + 1), "Formula and error cells are not supported.");
                            cells.add(cell == null ? "" : formatter.formatCellValue(cell));
                        }
                        while (width >= 0 && cells.size() > width && cells.getLast().isBlank()) cells.removeLast();
                        if (cells.stream().allMatch(String::isBlank)) continue;
                        if (width < 0) width = cells.size();
                        lines.add(new Line(row.getRowNum() + 1, cells));
                    }
                }
            } else throw new IllegalArgumentException("Choose a .csv, .xls, or .xlsx file.");
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalArgumentException("Unable to read the file. Upload a valid CSV or Excel workbook.", ex); }
        return lines;
    }
}
