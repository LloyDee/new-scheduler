package org.volunteers.scheduler.model;

import jakarta.persistence.*;

@Entity
public class Department {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) private Event event;
    @Column(nullable = false, length = 160) private String name;
    @Column(length = 2000) private String description;
    public Long getId() { return id; }
    public Event getEvent() { return event; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public void setEvent(Event event) { this.event = event; }
    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
}
