package org.volunteers.scheduler.repository;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.volunteers.scheduler.model.Announcement;
import java.util.List;

public interface Announcements extends JpaRepository<Announcement, Long> {
    @Query("select a from Announcement a where not exists (select r from AnnouncementRead r where r.deviceId = :device and r.announcementId = a.id) order by a.createdAt desc, a.id desc")
    List<Announcement> unread(@Param("device") String device);

    @Query("select count(a) from Announcement a where not exists (select r from AnnouncementRead r where r.deviceId = :device and r.announcementId = a.id)")
    long unreadCount(@Param("device") String device);
}
