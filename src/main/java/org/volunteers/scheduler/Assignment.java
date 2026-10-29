package org.volunteers.scheduler;

import jakarta.persistence.*;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"shift_id", "volunteer_id"}))
public class Assignment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @ManyToOne(optional = false) Shift shift;
    @ManyToOne(optional = false) Volunteer volunteer;
    public Long getId() { return id; }
    public Shift getShift() { return shift; }
    public Volunteer getVolunteer() { return volunteer; }
}
