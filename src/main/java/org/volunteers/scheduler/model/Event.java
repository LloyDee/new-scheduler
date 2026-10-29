package org.volunteers.scheduler.model;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "scheduler_event")
public class Event {
    public enum Type {
        REGIONAL_CONVENTION("Regional Convention"), CIRCUIT_ASSEMBLY("Circuit Assembly");
        private final String label;
        Type(String label) { this.label = label; }
        public String getLabel() { return label; }
    }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 160) private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Type type;
    @Column(nullable = false) private LocalDate startDate;
    @Column(nullable = false) private LocalDate endDate;
    @Column(length = 240) private String venue;
    @Column(length = 60) private String assembly;
    public Long getId() { return id; }
    public String getName() { return name; }
    public Type getType() { return type; }
    public String getAssembly() { return assembly; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public String getVenue() { return venue; }
    public void setName(String name) { this.name = name; }
    public void setType(Type type) { this.type = type; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public void setVenue(String venue) { this.venue = venue; }
    public void setAssembly(String assembly) { this.assembly = assembly; }
}
