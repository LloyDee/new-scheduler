package org.volunteers.scheduler.bootstrap;

import org.volunteers.scheduler.model.Department;
import org.volunteers.scheduler.model.Event;
import org.volunteers.scheduler.model.Volunteer;
import org.volunteers.scheduler.service.SchedulerService;
import java.util.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Opt-in, repeat-safe sample data: --scheduler.seed-test-data=true. */
@Component
@ConditionalOnProperty(name = "scheduler.seed-test-data", havingValue = "true")
public class TestVolunteerData implements CommandLineRunner {
    private static final String MARKER = "H2 volunteer testing data (100 volunteers)";
    private final SchedulerService service;

    public TestVolunteerData(SchedulerService service) { this.service = service; }

    @Override
    @Transactional
    public void run(String... args) {
        if (service.events().stream().anyMatch(e -> MARKER.equals(e.getVenue()))) return;
        Event event = service.saveEvent(null, Map.of("type", "REGIONAL_CONVENTION",
                "startDate", "2027-06-04", "venue", MARKER));
        event.setName("Volunteer Test Event");
        List<Department> departments = new ArrayList<>();
        for (String name : List.of("Attendants", "Parking", "Cleaning", "First Aid", "Information")) {
            departments.add(service.saveDepartment(event.getId(), null, Map.of("name", name)));
        }
        Random random = new Random(20261028L);
        String[] first = {"Alex", "Jamie", "Jordan", "Taylor", "Morgan", "Casey", "Riley", "Sam", "Cameron", "Drew",
                "Avery", "Quinn", "Devon", "Blake", "Reese", "Parker", "Robin", "Jesse", "Skyler", "Hayden"};
        String[] last = {"Rivera", "Chen", "Patel", "Garcia", "Johnson", "Kim", "Brown", "Wilson", "Davis", "Lopez",
                "Martin", "Clark", "Lewis", "Walker", "Hall", "Allen", "Young", "King", "Wright", "Scott"};
        String[] congregations = {"North", "South", "East", "West", "Central", "Lakeside", "Hillcrest", "Riverside"};
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String name;
            do {
                name = first[random.nextInt(first.length)] + " " + last[random.nextInt(last.length)];
            }
            while (!names.add(name));
            int departmentIndex = i % departments.size();
            Volunteer.Role role = i < 5 ? Volunteer.Role.OVERSEER
                    : i < 15 ? Volunteer.Role.ASSISTANT_OVERSEER
                    : i >= 25 && i < 30 ? Volunteer.Role.CAPTAIN
                    : i >= 30 && i < 35 ? Volunteer.Role.KEY_MAN : Volunteer.Role.VOLUNTEER;
            Map<String, String> input = new HashMap<>(Map.of("name", name,
                    "phone", String.format("202-555-%04d", 100 + i),
                    "email", "test.getVolunteer()." + (i + 1) + "@example.com",
                    "congregation", congregations[random.nextInt(congregations.length)],
                    "privilege", (i < 15 ? Volunteer.Privilege.ELDER
                            : Volunteer.Privilege.values()[random.nextInt(Volunteer.Privilege.values().length)]).name(),
                    "departmentRole_" + departments.get(departmentIndex).getId(), role.name()));
            // Exactly ten people have two departments; leadership stays in their primary department.
            if (i >= 15 && i < 25)
                input.put("departmentRole_" + departments.get((departmentIndex + 1) % 5).getId(), "VOLUNTEER");
            service.saveVolunteer(event.getId(), null, input);
        }
    }
}
