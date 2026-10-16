package vn.nutrimom.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.flywaydb.core.internal.resource.filesystem.FileSystemResource;
import org.junit.jupiter.api.Test;

/** These exact SQL revisions were recovered from edit history and verified against the shared DB. */
class AdminMigrationChecksumTest {
    @Test void appliedAdminSqlRemainsImmutable() {
        var checksums = Map.of(
                "V42__admin_operations.sql", -366517247,
                "V46__recognize_subscription_upgrades.sql", 1540402008,
                "V51__admin_expert_performance.sql", 1932212754,
                "V53__expert_kpi_v2_and_deadlines.sql", 66632425);
        Path directory = Path.of("src/main/resources/db/migration").toAbsolutePath();
        for (var entry : checksums.entrySet()) {
            var resource = new FileSystemResource(Location.fromPath("filesystem:", directory.toString()),
                    directory.resolve(entry.getKey()).toString(), StandardCharsets.UTF_8, false);
            assertEquals(entry.getValue().intValue(), ChecksumCalculator.calculate(resource),
                    entry.getKey() + ": applied SQL is immutable; add a new migration instead");
        }
    }
}
