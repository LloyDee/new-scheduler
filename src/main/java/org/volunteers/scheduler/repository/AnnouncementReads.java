package org.volunteers.scheduler.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.volunteers.scheduler.model.AnnouncementRead;

public interface AnnouncementReads extends JpaRepository<AnnouncementRead, AnnouncementRead.Key> {}
