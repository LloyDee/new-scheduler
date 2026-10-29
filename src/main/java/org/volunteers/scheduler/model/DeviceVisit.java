package org.volunteers.scheduler.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "scheduler_device_visit")
public class DeviceVisit {
    @Id public String id;
    public String name;
    public String browser;
    public String operatingSystem;
    public String deviceCategory;
    public String city;
    public String region;
    public String country;
    public String timezone;
    public String language;
    public Instant firstSeen;
    public Instant lastSeen;
    public long visitCount;
}
