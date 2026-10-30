package org.volunteers.scheduler.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.volunteers.scheduler.model.Volunteer;

public interface Volunteers extends JpaRepository<Volunteer, Long> {
    interface AccessRow {
        Long getVolunteerId();
        String getName();
        boolean getEventAdministrator();
        Long getEventId();
        Long getDepartmentId();
        Volunteer.Role getRole();
    }

    @org.springframework.data.jpa.repository.Query("""
        select v.id as volunteerId, v.name as name, v.eventAdministrator as eventAdministrator, v.event.id as eventId,
               key(roles).id as departmentId, value(roles) as role
        from Volunteer v left join v.departmentRoles roles
        where v.normalizedPhone = :phone
        """)
    List<AccessRow> findAccessByPhone(@org.springframework.data.repository.query.Param("phone") String phone);

    String PAGE_FILTER = """
        from Volunteer v where v.event.id = :eventId
        and (:departmentId is null or exists
            (select member.id from Volunteer member join member.departmentRoles roles
             where member.id = v.id and key(roles).id = :departmentId))
        and locate(:search, lower(concat(v.name, ' ', coalesce(v.congregation, ''), ' ',
            coalesce(v.phone, ''), ' ', coalesce(v.email, '')))) > 0
        """;
    @org.springframework.data.jpa.repository.Query(value = "select v.id " + PAGE_FILTER + " order by v.name, v.id",
        countQuery = "select count(v) " + PAGE_FILTER)
    org.springframework.data.domain.Page<Long> findPageIds(
        @org.springframework.data.repository.query.Param("eventId") long eventId,
        @org.springframework.data.repository.query.Param("departmentId") Long departmentId,
        @org.springframework.data.repository.query.Param("search") String search,
        org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"event", "departmentRoles"})
    List<Volunteer> findByIdInOrderByNameAscIdAsc(java.util.Collection<Long> ids);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"event", "departmentRoles"})
    List<Volunteer> findByNormalizedPhone(String normalizedPhone);
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"event", "departmentRoles"})
    List<Volunteer> findByEventIdOrderByNameAscIdAsc(Long eventId);
}
