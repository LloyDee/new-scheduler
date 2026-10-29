package org.volunteers.scheduler.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.volunteers.scheduler.model.Volunteer;

public interface Volunteers extends JpaRepository<Volunteer, Long> {
    List<Volunteer> findByEventIdOrderByNameAscIdAsc(Long eventId);
}
