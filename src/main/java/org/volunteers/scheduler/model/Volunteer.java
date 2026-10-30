package org.volunteers.scheduler.model;

import jakarta.persistence.*;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(indexes = @Index(name = "idx_volunteer_normalized_phone", columnList = "normalized_phone"))
public class Volunteer {
    public enum Role {
        ADMIN("Admin"), OVERSEER("Overseer"), ASSISTANT_OVERSEER("Assistant Overseer"), CAPTAIN("Captain"),KEY_MAN("Key Man"),VOLUNTEER("Volunteer");
        private final String label;
        Role(String label) { this.label = label; }
        public String getLabel() { return label; }
    }
    public enum Privilege {
        ELDER("Elder"), MINISTERIAL_SERVANT("Ministerial Servant"), PUBLISHER("Publisher");
        private final String label;
        Privilege(String label) { this.label = label; }
        public String getLabel() { return label; }
    }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) private Event event;
    @Column(nullable = false, length = 160) private String name;
    @Column(length = 60) private String phone;
    @Column(name = "normalized_phone", length = 10) private String normalizedPhone;
    @Column(length = 254) private String email;
    @Column(length = 160) private String congregation;
    @Column(length = 2000) private String notes;
    @Enumerated(EnumType.STRING) private Privilege privilege;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "volunteer_department_roles", joinColumns = @JoinColumn(name = "volunteer_id"))
    @MapKeyJoinColumn(name = "department_id")
    @Enumerated(EnumType.STRING)
    @Column(name = "department_role", nullable = false)
    private Map<Department, Role> departmentRoles = new LinkedHashMap<>();
    public Privilege getPrivilege() { return privilege; }
    public Map<Department, Role> getDepartmentRoles() { return departmentRoles; }
    public Long getId() { return id; }
    public Event getEvent() { return event; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public String getEmail() { return email; }
    public String getCongregation() { return congregation; }
    public String getNotes() { return notes; }
    public void setEvent(Event event) { this.event = event; }
    public void setName(String name) { this.name = name; }
    public void setPhone(String phone) { this.phone = phone; normalizePhone(); }
    @PrePersist @PreUpdate
    private void normalizePhone() {
        normalizedPhone = org.volunteers.scheduler.service.PhoneAccess.normalize(phone);
    }
    public void setEmail(String email) { this.email = email; }
    public void setCongregation(String congregation) { this.congregation = congregation; }
    public void setNotes(String notes) { this.notes = notes; }
    public void setPrivilege(Privilege privilege) { this.privilege = privilege; }
    public void setDepartmentRoles(Map<Department, Role> departmentRoles) { this.departmentRoles = departmentRoles; }
}
