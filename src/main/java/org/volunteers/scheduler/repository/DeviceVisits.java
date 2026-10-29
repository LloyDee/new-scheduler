package org.volunteers.scheduler.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.volunteers.scheduler.model.DeviceVisit;

public interface DeviceVisits extends JpaRepository<DeviceVisit, String> {}
