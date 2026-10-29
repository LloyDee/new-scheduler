package org.volunteers.scheduler;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface Events extends JpaRepository<Event, Long> {
    List<Event> findAllByOrderByStartDateDescIdDesc();
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from Event e where e.id = :id")
    java.util.Optional<Event> lockForVolunteerUpdate(@org.springframework.data.repository.query.Param("id") long id);
}
interface Volunteers extends JpaRepository<Volunteer, Long> {
    List<Volunteer> findByEventIdOrderByNameAscIdAsc(Long eventId);
}
interface Departments extends JpaRepository<Department, Long> {
    List<Department> findByEventIdOrderByNameAscIdAsc(Long eventId);
}
interface Shifts extends JpaRepository<Shift, Long> {
    List<Shift> findByDepartmentEventIdOrderByDateAscStartTimeAscIdAsc(Long eventId);
}
interface Assignments extends JpaRepository<Assignment, Long> {
    List<Assignment> findByShiftDepartmentEventIdOrderByIdAsc(Long eventId);
    boolean existsByShiftIdAndVolunteerId(Long shiftId, Long volunteerId);
}
