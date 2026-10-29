package org.volunteers.scheduler;

import jakarta.persistence.*;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
public class Volunteer {
    public enum Role {
        ADMIN("Admin"), OVERSEER("Overseer"), ASSISTANT_OVERSEER("Assistant Overseer"), VOLUNTEER("Volunteer");
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
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @ManyToOne(optional = false) Event event;
    @Column(nullable = false, length = 160) String name;
    @Column(length = 60) String phone;
    @Column(length = 254) String email;
    @Column(length = 160) String congregation;
    @Column(length = 2000) String notes;
    @Enumerated(EnumType.STRING) Privilege privilege;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "volunteer_department_roles", joinColumns = @JoinColumn(name = "volunteer_id"))
    @MapKeyJoinColumn(name = "department_id")
    @Enumerated(EnumType.STRING)
    @Column(name = "department_role", nullable = false)
    Map<Department, Role> departmentRoles = new LinkedHashMap<>();
    public Privilege getPrivilege() { return privilege; }
    public Map<Department, Role> getDepartmentRoles() { return departmentRoles; }
    public Long getId() { return id; }
    public Event getEvent() { return event; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public String getEmail() { return email; }
    public String getCongregation() { return congregation; }
    public String getNotes() { return notes; }
}
