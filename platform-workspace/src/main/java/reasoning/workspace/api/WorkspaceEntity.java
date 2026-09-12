package reasoning.workspace.api;

import reasoning.common.model.WorkspaceStatus;
import reasoning.common.model.WorkspacePhase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** JPA entity representing a workspace with name, type, status, phase, and workspace code. */
@Entity
@Table(name = "workspaces")
public class WorkspaceEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    /**
     * Optimistische Sperre (Phase 2B.6): gleichzeitige Schreibzugriffe auf
     * denselben Fall (z. B. Übergabe vs. Abschluss) enden nie in einem
     * stillen Überschreiben — der Verlierer erhält eine
     * OptimisticLockException und die Transaktion rollt zurück.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** Aktenzeichen/Vorgangsnummer; bei Mailbox-Intake deterministisch aus der Auslöser-E-Mail. */
    @Column(name = "workspace_code", unique = true, length = 50)
    private String workspaceCode;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "workspace_type", nullable = false)
    private String workspaceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private WorkspaceStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", nullable = false)
    private WorkspacePhase phase;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "phase_data", columnDefinition = "jsonb")
    private String phaseData;

    // ── Structured geographic context (nullable — existing cases stay valid) ──

    @Column(name = "geo_address")
    private String geoAddress;

    @Column(name = "geo_street")
    private String geoStreet;

    @Column(name = "geo_house_number")
    private String geoHouseNumber;

    @Column(name = "geo_postal_code")
    private String geoPostalCode;

    @Column(name = "geo_city")
    private String geoCity;

    @Column(name = "geo_latitude")
    private Double geoLatitude;

    @Column(name = "geo_longitude")
    private Double geoLongitude;

    @Column(name = "geo_district")
    private String geoDistrict;

    @Column(name = "geo_authority")
    private String geoAuthority;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Zuständige Mitarbeiterin; {@code null} = bewusst NICHT zugewiesen
     * (allgemeiner Arbeitspool, z. B. automatischer Mailbox-Intake — es gibt
     * keinen Fake-Mitarbeiter für die Poststelle, Phase 2C.3b).
     */
    @Column(name = "owner_id", nullable = true)
    private String ownerId;

    public WorkspaceEntity() {}

    public WorkspaceEntity(String workspaceCode, String name, String description,
                           String workspaceType, String ownerId) {
        this.id = UUID.randomUUID();
        this.workspaceCode = workspaceCode;
        this.name = name;
        this.description = description;
        this.workspaceType = workspaceType;
        this.status = WorkspaceStatus.DRAFT;
        this.phase = WorkspacePhase.SETUP;
        this.phaseData = "{}";
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.ownerId = ownerId;
    }

    public UUID getUuid() { return id; }

    public String getId() { return id != null ? id.toString() : null; }
    public void setId(String id) { this.id = id != null ? UUID.fromString(id) : null; }

    public String getWorkspaceCode() { return workspaceCode; }
    public void setWorkspaceCode(String workspaceCode) { this.workspaceCode = workspaceCode; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getWorkspaceType() { return workspaceType; }
    public void setWorkspaceType(String workspaceType) { this.workspaceType = workspaceType; }

    public WorkspaceStatus getStatus() { return status; }
    public void setStatus(WorkspaceStatus status) { this.status = status; }

    public WorkspacePhase getPhase() { return phase; }
    public void setPhase(WorkspacePhase phase) { this.phase = phase; }

    public String getPhaseData() { return phaseData; }
    public void setPhaseData(String phaseData) { this.phaseData = phaseData; }

    /** Parses the phase data JSON into a mutable map. Returns empty map on parse failure. */
    public Map<String, Object> getPhaseDataMap() {
        if (phaseData == null || phaseData.isBlank() || "{}".equals(phaseData))
            return new java.util.LinkedHashMap<>();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(phaseData, new com.fasterxml.jackson.core.type.TypeReference<
                            java.util.LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return new java.util.LinkedHashMap<>();
        }
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getGeoAddress() { return geoAddress; }
    public void setGeoAddress(String geoAddress) { this.geoAddress = geoAddress; }

    public String getGeoStreet() { return geoStreet; }
    public void setGeoStreet(String geoStreet) { this.geoStreet = geoStreet; }

    public String getGeoHouseNumber() { return geoHouseNumber; }
    public void setGeoHouseNumber(String geoHouseNumber) { this.geoHouseNumber = geoHouseNumber; }

    public String getGeoPostalCode() { return geoPostalCode; }
    public void setGeoPostalCode(String geoPostalCode) { this.geoPostalCode = geoPostalCode; }

    public String getGeoCity() { return geoCity; }
    public void setGeoCity(String geoCity) { this.geoCity = geoCity; }

    public Double getGeoLatitude() { return geoLatitude; }
    public void setGeoLatitude(Double geoLatitude) { this.geoLatitude = geoLatitude; }

    public Double getGeoLongitude() { return geoLongitude; }
    public void setGeoLongitude(Double geoLongitude) { this.geoLongitude = geoLongitude; }

    public String getGeoDistrict() { return geoDistrict; }
    public void setGeoDistrict(String geoDistrict) { this.geoDistrict = geoDistrict; }

    public String getGeoAuthority() { return geoAuthority; }
    public void setGeoAuthority(String geoAuthority) { this.geoAuthority = geoAuthority; }
}
