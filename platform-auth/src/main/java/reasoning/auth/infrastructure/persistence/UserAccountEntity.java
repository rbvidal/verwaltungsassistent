package reasoning.auth.infrastructure.persistence;

import reasoning.auth.model.Role;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * JPA entity representing a user account with email, hashed password, roles,
 * enable/lock state and the employee master data (department, office, room,
 * phone, salutation, vacation periods) used for official correspondence and
 * the admin user administration.
 */
@Entity
@Table(name = "auth_users")
public class UserAccountEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private boolean locked = false;

    // ── Employee master data (nullable — legacy accounts work unchanged) ──

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "phone")
    private String phone;

    @Column(name = "department")
    private String department;

    @Column(name = "office")
    private String office;

    @Column(name = "room")
    private String room;

    @Column(name = "position")
    private String position;

    /** Formal salutation (Frau/Herr) — never inferred from the first name. */
    @Column(name = "salutation")
    private String salutation;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_vacations", joinColumns = @JoinColumn(name = "user_id"))
    private List<VacationPeriod> vacations = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "auth_user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Set<Role> roles = new HashSet<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** One planned absence period (Urlaub etc.) of an employee. */
    @Embeddable
    public record VacationPeriod(
            @jakarta.persistence.Column(name = "vacation_from") LocalDate from,
            @jakarta.persistence.Column(name = "vacation_to") LocalDate to,
            @jakarta.persistence.Column(name = "vacation_type") String type,
            @jakarta.persistence.Column(name = "vacation_note") String note) {
        public VacationPeriod {
            type = type != null && !type.isBlank() ? type : "Urlaub";
        }
    }

    protected UserAccountEntity() {
    }

    public UserAccountEntity(String email, String passwordHash, String displayName, Set<Role> roles) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.roles = new HashSet<>(roles);
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public Set<Role> getRoles() {
        return Set.copyOf(roles);
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getOffice() {
        return office;
    }

    public void setOffice(String office) {
        this.office = office;
    }

    public String getRoom() {
        return room;
    }

    public void setRoom(String room) {
        this.room = room;
    }

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position;
    }

    public String getSalutation() {
        return salutation;
    }

    public void setSalutation(String salutation) {
        this.salutation = salutation;
    }

    public List<VacationPeriod> getVacations() {
        return vacations;
    }

    public void setVacations(List<VacationPeriod> vacations) {
        this.vacations = vacations != null ? new ArrayList<>(vacations) : new ArrayList<>();
    }
}
