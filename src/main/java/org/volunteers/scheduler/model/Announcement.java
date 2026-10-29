package org.volunteers.scheduler.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "scheduler_announcement")
public class Announcement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, length = 5000)
    public String message;
    @Column(nullable = false)
    public Instant createdAt;
}
