package verwaltungsassistent.web.geo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Repository for uploaded field photos (Geotagged Fotos). */
public interface GeoPhotoRepository extends JpaRepository<GeoPhotoEntity, UUID> {

    List<GeoPhotoEntity> findByUploadedByOrderByCreatedAtDesc(String uploadedBy);

    /** Only genuine image records — a PDF record can never appear as a photograph. */
    List<GeoPhotoEntity> findByUploadedByAndContentTypeInOrderByCreatedAtDesc(
            String uploadedBy, Collection<String> contentTypes);
}
