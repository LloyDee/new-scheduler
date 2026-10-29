package org.volunteers.scheduler;

import jakarta.persistence.*;

@Entity
public class Department {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @ManyToOne(optional = false) Event event;
    @Column(nullable = false, length = 160) String name;
    @Column(length = 2000) String description;
    public Long getId() { return id; }
    public Event getEvent() { return event; }
    public String getName() { return name; }
    public String getDescription() { return description; }
}
