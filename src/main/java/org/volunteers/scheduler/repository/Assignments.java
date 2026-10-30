package org.volunteers.scheduler.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.volunteers.scheduler.model.Assignment;

public interface Assignments extends JpaRepository<Assignment, Long> {
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"shift.department.event", "volunteer.event"})
    @org.springframework.data.jpa.repository.Query("""
        select a from Assignment a where a.shift.id in
        (select own.shift.id from Assignment own where own.volunteer.id in :ids) order by a.id
        """)
    List<Assignment> findForVolunteerShifts(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);
    List<Assignment> findByShiftDepartmentEventIdOrderByIdAsc(Long eventId);
    boolean existsByShiftIdAndVolunteerId(Long shiftId, Long volunteerId);
}
