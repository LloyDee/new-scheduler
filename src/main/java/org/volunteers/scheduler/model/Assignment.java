package org.volunteers.scheduler.model;

import jakarta.persistence.*;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"shift_id", "volunteer_id"}))
public class Assignment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) private Shift shift;
    @ManyToOne(optional = false) private Volunteer volunteer;
    public Long getId() { return id; }
    public Shift getShift() { return shift; }
    public Volunteer getVolunteer() { return volunteer; }
    public void setShift(Shift shift) { this.shift = shift; }
    public void setVolunteer(Volunteer volunteer) { this.volunteer = volunteer; }
}
