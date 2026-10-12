package vn.nutrimom.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class FileStorageMigrationTest {
    @Test
    void migrationPreservesLegacyRowsAndRejectsInvalidProviderBucketCombinations() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:file_migration;MODE=PostgreSQL", "sa", "")) {
            var statement = connection.createStatement();
            statement.execute("CREATE SCHEMA app");
            statement.execute("CREATE TABLE app.files (id VARCHAR(36) PRIMARY KEY, storage_key VARCHAR(500))");
            statement.execute("INSERT INTO app.files VALUES ('old-file', 'original/local/key')");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                    "db/migration/V41__private_file_storage_provider.sql"));
            try (var result = statement.executeQuery("SELECT * FROM app.files WHERE id = 'old-file'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("storage_provider")).isEqualTo("LOCAL");
                assertThat(result.getString("storage_bucket")).isNull();
                assertThat(result.getString("storage_key")).isEqualTo("original/local/key");
            }
            statement.execute("INSERT INTO app.files VALUES ('r2-file', 'private/key', 'R2', 'private-bucket')");
            assertThatThrownBy(() -> statement.execute(
                    "INSERT INTO app.files VALUES ('invalid', 'key', 'R2', NULL)"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.execute(
                    "INSERT INTO app.files VALUES ('invalid', 'key', 'LOCAL', 'public-bucket')"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
