package verwaltungsassistent.web.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the backup history listing: only recognized backup files are
 * listed, metadata is parsed from the file name and size, newest first.
 */
class DataBackupServiceTest {

    @TempDir
    Path tempDir;

    private DataBackupService service() {
        return new DataBackupService(
                mock(DataRestoreService.class),
                "va-postgres", "verwaltungsassistent", "verwaltungsassistent",
                "mda-neo4j", "neo4j:5-community",
                "mda-qdrant", "http://localhost:6333",
                tempDir.toString());
    }

    private void write(String name, int sizeKb) throws IOException {
        Files.write(tempDir.resolve(name), new byte[sizeKb * 1024]);
    }

    @Test
    void list_parsesComponentTimestampSizeAndSortsNewestFirst() throws Exception {
        write("postgres-20260821-1612.dump", 42);
        write("neo4j-20260821-1613.dump", 18);
        write("qdrant-20260821-1614.snapshot", 126);
        Files.write(tempDir.resolve("unrelated.txt"), new byte[10]);

        List<DataBackupService.BackupEntry> entries = service().list();

        assertEquals(3, entries.size(), "unrelated files must not be listed");
        assertEquals("qdrant-20260821-1614.snapshot", entries.get(0).fileName(), "newest first");
        assertEquals("neo4j-20260821-1613.dump", entries.get(1).fileName());
        assertEquals("postgres-20260821-1612.dump", entries.get(2).fileName());

        var pg = entries.get(2);
        assertEquals("PostgreSQL", pg.component());
        assertEquals("21.08.2026 16:12", pg.timestamp(), "timestamp is displayed in German format");
        assertEquals(42 * 1024, pg.sizeBytes());
        assertEquals("Erfolgreich", pg.status());
        assertTrue(pg.path().endsWith("postgres-20260821-1612.dump"),
                "the absolute backup path must be exposed for the admin history");

        var neo4j = entries.get(1);
        assertEquals("Neo4j", neo4j.component());
        var qdrant = entries.get(0);
        assertEquals("Qdrant", qdrant.component());
    }

    @Test
    void list_emptyDirectory_returnsEmpty() {
        assertTrue(service().list().isEmpty());
    }

    @Test
    void backupDir_isCreatedWhenMissing() {
        Path missing = tempDir.resolve("nested").resolve("backups");
        DataBackupService s = new DataBackupService(
                mock(DataRestoreService.class),
                "va-postgres", "verwaltungsassistent", "verwaltungsassistent",
                "mda-neo4j", "neo4j:5-community",
                "mda-qdrant", "http://localhost:6333",
                missing.toString());
        assertTrue(Files.isDirectory(missing), "the backup directory must be created");
    }
}
