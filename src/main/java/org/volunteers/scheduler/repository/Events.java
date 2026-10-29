package org.volunteers.scheduler.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.volunteers.scheduler.model.Event;

public interface Events extends JpaRepository<Event, Long> {
    List<Event> findAllByOrderByStartDateDescIdDesc();
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from Event e where e.id = :id")
    java.util.Optional<Event> lockForVolunteerUpdate(@org.springframework.data.repository.query.Param("id") long id);
}
