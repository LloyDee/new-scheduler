package org.volunteers.scheduler;

import jakarta.persistence.*;
import java.time.*;

@Entity
public class Shift {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @ManyToOne(optional = false) Department department;
    @Column(length = 160) String name;
    @Column(nullable = false) LocalDate date;
    @Column(nullable = false) LocalTime startTime;
    @Column(nullable = false) LocalTime endTime;
    @Column(nullable = false) int requiredCount;
    @Column(length = 2000) String notes;
    public Long getId() { return id; }
    public String getName() { return name == null || name.isBlank() ? department.name : name; }
    public Department getDepartment() { return department; }
    public LocalDate getDate() { return date; }
    public LocalTime getStartTime() { return startTime; }
    public LocalTime getEndTime() { return endTime; }
    public int getRequiredCount() { return requiredCount; }
    public String getNotes() { return notes; }
}
