package org.volunteers.scheduler;

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
    Long id;
    @Column(nullable = false, length = 160) String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false) Type type;
    @Column(nullable = false) LocalDate startDate;
    @Column(nullable = false) LocalDate endDate;
    @Column(length = 240) String venue;
    @Column(length = 60) String assembly;
    public Long getId() { return id; }
    public String getName() { return name; }
    public Type getType() { return type; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public String getVenue() { return venue; }
}
