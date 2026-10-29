package org.volunteers.scheduler.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.volunteers.scheduler.model.Department;

public interface Departments extends JpaRepository<Department, Long> {
    List<Department> findByEventIdOrderByNameAscIdAsc(Long eventId);
}
