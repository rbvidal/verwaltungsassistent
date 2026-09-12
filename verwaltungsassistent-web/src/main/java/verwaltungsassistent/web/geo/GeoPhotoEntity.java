package verwaltungsassistent.web.geo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One uploaded field photo for the Geoinformation module. The photo itself is
 * stored locally (uploads/photos/…); GPS metadata is extracted from the image
 * EXIF data and persisted here. Photos become evidence documents when a
 * Geovorgang is created.
 */
@Entity
@Table(name = "geo_photos")
public class GeoPhotoEntity {

    @Id
    private UUID id;

    @Column(name = "original_name", nullable = false)
    private String originalName;

    /** Relative path under the app upload directory. */
    @Column(name = "storage_path", nullable = false)
    private String storagePath;

    /**
     * Relative path of the untouched source file (persistent original), or
     * null for records from before the sanitization pass. EXIF re-extraction
     * reads the original; the viewer/download always use {@link #storagePath}
     * (the sanitized display copy).
     */
    @Column(name = "original_path")
    private String originalPath;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "orientation")
    private String orientation;

    @Column(name = "has_geo", nullable = false)
    private boolean hasGeo = false;

    /** Where the coordinates came from: "EXIF" or "MANUAL". Null = unknown. */
    @Column(name = "gps_source", length = 20)
    private String gpsSource;

    /** The Geovorgang (workspace) this photo belongs to, once created. */
    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "uploaded_by", nullable = false)
    private String uploadedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected GeoPhotoEntity() {
    }

    public GeoPhotoEntity(UUID id, String originalName, String storagePath, String contentType,
                          String uploadedBy, Instant createdAt) {
        this.id = id;
        this.originalName = originalName;
        this.storagePath = storagePath;
        this.contentType = contentType;
        this.uploadedBy = uploadedBy;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getOriginalName() { return originalName; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    public String getOriginalPath() { return originalPath; }
    public void setOriginalPath(String originalPath) { this.originalPath = originalPath; }
    public String getContentType() { return contentType; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Instant getCapturedAt() { return capturedAt; }
    public void setCapturedAt(Instant capturedAt) { this.capturedAt = capturedAt; }
    public String getOrientation() { return orientation; }
    public void setOrientation(String orientation) { this.orientation = orientation; }
    public boolean isHasGeo() { return hasGeo; }
    public void setHasGeo(boolean hasGeo) { this.hasGeo = hasGeo; }
    public String getGpsSource() { return gpsSource; }
    public void setGpsSource(String gpsSource) { this.gpsSource = gpsSource; }
    public UUID getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(UUID workspaceId) { this.workspaceId = workspaceId; }
    public String getUploadedBy() { return uploadedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
