package vn.nutrimom.knowledge;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Runs the existing CMS database/security/storage-mock scenarios without a listening socket.
 * The original real-HTTP multipart test remains in KnowledgeCmsIntegrationTest unchanged.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK,properties={
        "spring.datasource.url=jdbc:h2:mem:admin_knowledge_regression;MODE=MSSQLServer;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS app",
        "local.server.port=0", "app.bootstrap.admin-user-enabled=false", "app.knowledge.r2.enabled=true",
        "app.knowledge.r2.account-id=00000000000000000000000000000000", "app.knowledge.r2.access-key-id=test",
        "app.knowledge.r2.secret-access-key=test", "app.knowledge.r2.bucket-name=knowledge-test",
        "app.knowledge.r2.public-base-url=https://media.example.com"})
class AdminKnowledgeMockMvcRegressionTest extends KnowledgeCmsIntegrationTest {
    @Override
    @Test
    @Disabled("Transport test requires a listening Tomcat; Windows sandbox currently rejects Java loopback selector connections.")
    void actualHttpMultipartEnforcesServletLimitsWithRealJwt() {}
}
