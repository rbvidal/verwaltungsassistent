package verwaltungsassistent.web.util;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;

/** Read-only EXIF audit of the bundled demo photos (prints findings, asserts nothing). */
class ExifAuditTest {

    @Test
    void audit() throws Exception {
        File dir = new File("uploads/photos");
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".jpg")
                || n.toLowerCase().endsWith(".jpeg") || n.toLowerCase().endsWith(".png"));
        System.out.println("=== EXIF-AUDIT: " + files.length + " Fotos ===");
        for (File f : files) {
            Metadata m = ImageMetadataReader.readMetadata(f);
            StringBuilder sb = new StringBuilder(f.getName());
            sb.append(" | dirs=").append(m.getDirectoryCount());
            String make = null, model = null, date = null, gps = null, software = null, artist = null, copyright = null;
            for (Directory d : m.getDirectories()) {
                for (Tag t : d.getTags()) {
                    String name = t.getTagName();
                    String desc = t.getDescription();
                    if (name.contains("Make")) make = desc;
                    if (name.contains("Model")) model = desc;
                    if (name.contains("Date/Time Original") || name.contains("Date/Time")) date = desc;
                    if (name.contains("GPS Latitude") || name.contains("GPS Longitude")) {
                        if (gps == null) gps = "";
                        gps += desc + " ";
                    }
                    if (name.contains("Software")) software = desc;
                    if (name.contains("Artist")) artist = desc;
                    if (name.contains("Copyright")) copyright = desc;
                }
            }
            sb.append(" | make=").append(make).append(" | model=").append(model)
              .append(" | date=").append(date).append(" | gps=").append(gps == null ? "none" : gps.trim())
              .append(" | software=").append(software).append(" | artist=").append(artist)
              .append(" | copyright=").append(copyright);
            System.out.println(sb);
        }
    }

    /** Regression check: the display copies served to users carry no EXIF. */
    @Test
    void displayCopiesAreSanitized() throws Exception {
        File dir = new File("uploads/photos");
        File[] copies = dir.listFiles((d, n) -> n.endsWith("_display.jpg") || n.endsWith("_display.png"));
        if (copies == null || copies.length == 0) {
            System.out.println("no display copies found — run the demo import first");
            return;
        }
        System.out.println("=== DISPLAY-KOPIEN: " + copies.length + " ===");
        for (File f : copies) {
            Metadata m = ImageMetadataReader.readMetadata(f);
            long exifDirs = 0;
            boolean gps = false;
            for (Directory d : m.getDirectories()) {
                if (d.getName().contains("Exif")) exifDirs++;
                if (d instanceof GpsDirectory) gps = true;
            }
            System.out.println(f.getName() + " | dirs=" + m.getDirectoryCount()
                    + " | exif=" + exifDirs + " | gps=" + gps);
            org.junit.jupiter.api.Assertions.assertEquals(0, exifDirs,
                    "display copy must not carry EXIF: " + f.getName());
            org.junit.jupiter.api.Assertions.assertFalse(gps,
                    "display copy must not carry GPS: " + f.getName());
        }
    }
}
