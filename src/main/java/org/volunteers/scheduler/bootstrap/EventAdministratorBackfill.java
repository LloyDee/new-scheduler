package org.volunteers.scheduler.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.volunteers.scheduler.model.Volunteer;
import org.volunteers.scheduler.repository.*;
import org.volunteers.scheduler.service.SchedulerService;

@Component
@org.springframework.core.annotation.Order(100)
public class EventAdministratorBackfill implements ApplicationRunner {
    private final Events events;
    private final Volunteers volunteers;
    private final SchedulerService scheduler;
    @org.springframework.beans.factory.annotation.Value("${scheduler.app-owner-phone:8135160335}")
    private String ownerPhone;
    public EventAdministratorBackfill(Events events, Volunteers volunteers, SchedulerService scheduler) {
        this.events = events; this.volunteers = volunteers; this.scheduler = scheduler;
    }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        events.findAll().stream().map(event -> event.getId()).sorted().forEach(id -> {
            var event = events.lockForVolunteerUpdate(id).orElseThrow();
            var members = volunteers.findByEventIdOrderByNameAscIdAsc(id);
            for (var member : members) {
                member.getDepartmentRoles().replaceAll((department, role) -> {
                    if (role == Volunteer.Role.ADMIN) {
                        member.setEventAdministrator(true);
                        return Volunteer.Role.VOLUNTEER;
                    }
                    return role;
                });
            }
            if (members.stream().noneMatch(Volunteer::isEventAdministrator)) scheduler.ensureAdministrator(event, ownerPhone);
        });
    }
}
