package org.volunteers.scheduler.model;

import jakarta.persistence.*;
import java.time.*;

@Entity
public class Shift {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) private Department department;
    @Column(length = 160) private String name;
    @Column(nullable = false) private LocalDate date;
    @Column(nullable = false) private LocalTime startTime;
    @Column(nullable = false) private LocalTime endTime;
    @Column(nullable = false) private int requiredCount;
    @Column(length = 2000) private String notes;
    public Long getId() { return id; }
    public String getName() { return name == null || name.isBlank() ? department.getName() : name; }
    public Department getDepartment() { return department; }
    public LocalDate getDate() { return date; }
    public LocalTime getStartTime() { return startTime; }
    public LocalTime getEndTime() { return endTime; }
    public int getRequiredCount() { return requiredCount; }
    public String getNotes() { return notes; }
    public void setDepartment(Department department) { this.department = department; }
    public void setName(String name) { this.name = name; }
    public void setDate(LocalDate date) { this.date = date; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }
    public void setRequiredCount(int requiredCount) { this.requiredCount = requiredCount; }
    public void setNotes(String notes) { this.notes = notes; }
}
