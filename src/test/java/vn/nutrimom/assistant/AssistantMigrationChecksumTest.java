package vn.nutrimom.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.flywaydb.core.internal.resource.filesystem.FileSystemResource;
import org.junit.jupiter.api.Test;

/** V32 must stay identical to the revision applied on the shared DB (checksum verified 16/10/2026). */
class AssistantMigrationChecksumTest {
    @Test void appliedAssistantSqlRemainsImmutable() {
        Path directory = Path.of("src/main/resources/db/migration").toAbsolutePath();
        var resource = new FileSystemResource(Location.fromPath("filesystem:", directory.toString()),
                directory.resolve("V32__assistant.sql").toString(), StandardCharsets.UTF_8, false);
        assertEquals(598666640, ChecksumCalculator.calculate(resource),
                "V32__assistant.sql: applied SQL is immutable; add a new migration instead");
    }
}
