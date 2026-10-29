package org.volunteers.scheduler.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.volunteers.scheduler.model.Shift;

public interface Shifts extends JpaRepository<Shift, Long> {
    List<Shift> findByDepartmentEventIdOrderByDateAscStartTimeAscIdAsc(Long eventId);
}
