package org.volunteers.scheduler.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.volunteers.scheduler.model.Assignment;

public interface Assignments extends JpaRepository<Assignment, Long> {
    List<Assignment> findByShiftDepartmentEventIdOrderByIdAsc(Long eventId);
    boolean existsByShiftIdAndVolunteerId(Long shiftId, Long volunteerId);
}
