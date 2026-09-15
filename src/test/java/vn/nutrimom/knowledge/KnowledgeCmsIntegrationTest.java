package vn.nutrimom.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.auth.domain.*;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.knowledge.repository.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.url=jdbc:h2:mem:knowledge_test;MODE=MSSQLServer;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS app",
        "app.bootstrap.admin-user-enabled=false", "app.knowledge.r2.enabled=true",
        "app.knowledge.r2.account-id=00000000000000000000000000000000", "app.knowledge.r2.access-key-id=test",
        "app.knowledge.r2.secret-access-key=test", "app.knowledge.r2.bucket-name=knowledge-test",
        "app.knowledge.r2.public-base-url=https://media.example.com"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class KnowledgeCmsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @MockitoSpyBean KnowledgeArticleRepository articles;
    @Autowired ArticleMediaRepository media;
    @Autowired ArticleBookmarkRepository bookmarks;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @MockitoBean S3Client s3;
    private String adminId;
    private String userId;

    @BeforeEach void setup() {
        bookmarks.deleteAll(); articles.deleteAll(); media.deleteAll(); users.deleteAll();
        adminId = user("+84990000001", UserRole.ADMIN);
        userId = user("+84990000002", UserRole.USER);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(PutObjectResponse.builder().build());
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenReturn(DeleteObjectResponse.builder().build());
    }
    private String user(String phone, UserRole role) {
        UserEntity user = new UserEntity(); user.setPhone(phone); user.setDisplayName("NutriMom Team");
        user.setRoles(Set.of(role)); user.setStatus(UserStatus.ACTIVE); return users.saveAndFlush(user).getId();
    }
    private RequestPostProcessor admin() { return jwt().jwt(j -> j.subject(adminId)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")); }
    private RequestPostProcessor member() { return jwt().jwt(j -> j.subject(userId)).authorities(new SimpleGrantedAuthority("ROLE_USER")); }
    private String body(String slug, String status) {
        return """
            {"slug":"%s","title":"Vitamin D","excerpt":"Overview","category":"nutrition","stage":"trimester-1",
             "topics":["vitamin","nutrition"],"status":"%s","publishedAt":"2026-01-01T00:00:00Z",
             "lead":"Introduction","sections":[{"heading":"First","paragraphs":["%s"],"bullets":["Benefit"]},
             {"heading":"Second","paragraphs":["Second paragraph"]}],"source":{"label":"WHO","href":"https://www.who.int"}}
            """.formatted(slug, status, "Long paragraph ".repeat(30));
    }
    private MvcResult create(String slug, String status) throws Exception {
        return mvc.perform(post("/api/v1/admin/knowledge/articles").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(body(slug,status))).andExpect(status().isCreated()).andReturn();
    }
    private String id(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()).at("/data/id").stringValue(); }

    @Test void articleLifecyclePreservesContractAndPublicationVisibility() throws Exception {
        String id = id(create("vitamin-d", "draft"));
        mvc.perform(get("/api/v1/knowledge/articles/vitamin-d")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/knowledge/articles/"+id).with(admin()))
                .andExpect(jsonPath("$.data.status").value("draft")).andExpect(jsonPath("$.data.publishedAt").doesNotExist());
        mvc.perform(put("/api/v1/admin/knowledge/articles/"+id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("vitamin-d", "published"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/knowledge/articles/vitamin-d"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.author.name").value("NutriMom Team"))
                .andExpect(jsonPath("$.data.publishedAt").exists()).andExpect(jsonPath("$.data.sections[0].heading").value("First"))
                .andExpect(jsonPath("$.data.sections[1].heading").value("Second"))
                .andExpect(jsonPath("$.data.sections[0].paragraphs[0]").value("Long paragraph ".repeat(30)))
                .andExpect(jsonPath("$.data.sections[1].bullets").isEmpty()).andExpect(jsonPath("$.meta.request_id").exists());
        mvc.perform(put("/api/v1/admin/knowledge/articles/"+id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("vitamin-d", "archived"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/knowledge/articles/vitamin-d")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/admin/knowledge/articles/"+id).with(admin())).andExpect(status().isOk());
        assertThat(articles.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app.knowledge_article_sections", Long.class)).isZero();
    }
    @Test void listFiltersPaginationAndBookmarksUseRealDatabase() throws Exception {
        create("alpha", "published"); create("beta", "published"); create("hidden", "draft");
        create("future", "published");
        jdbc.update("UPDATE app.knowledge_articles SET published_at=? WHERE slug='future'", java.time.OffsetDateTime.now().plusDays(2));
        mvc.perform(get("/api/v1/knowledge/articles").param("pageSize","1").param("page","2")
                .param("category","nutrition").param("stage","trimester-1").param("topic","vitamin").param("sort","title:asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalItems").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2)).andExpect(jsonPath("$.data.currentPage").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(get("/api/v1/knowledge/articles").param("topic","vit")).andExpect(jsonPath("$.data.totalItems").value(0));
        mvc.perform(get("/api/v1/knowledge/articles").param("page","999")).andExpect(jsonPath("$.data.currentPage").value(1));
        mvc.perform(get("/api/v1/knowledge/articles/future")).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/knowledge/articles/alpha/bookmark").with(member())).andExpect(status().isOk());
        mvc.perform(put("/api/v1/knowledge/articles/alpha/bookmark").with(member())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/knowledge/articles").with(member()).param("savedOnly","true"))
                .andExpect(jsonPath("$.data.totalItems").value(1)).andExpect(jsonPath("$.data.items[0].slug").value("alpha"));
        mvc.perform(get("/api/v1/knowledge/articles").with(admin()).param("savedOnly","true")).andExpect(jsonPath("$.data.totalItems").value(0));
        mvc.perform(get("/api/v1/knowledge/articles").param("savedOnly","true")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/knowledge/articles/alpha/bookmark").with(member())).andExpect(status().isOk());
        assertThat(bookmarks.count()).isZero();
    }
    @Test void uploadStoresOnlyMetadataAndCanBeUsedForCoverAndSection() throws Exception {
        MvcResult upload = mvc.perform(multipart("/api/v1/admin/knowledge/media/upload").file(ImageOptimizationServiceTest.file(
                        ImageOptimizationServiceTest.png(100,60), "image/png")).param("alt","Image description").param("caption","Caption").with(admin()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.image_url").value(org.hamcrest.Matchers.startsWith("https://media.example.com/article/")))
                .andExpect(jsonPath("$.data.width").value(100)).andExpect(jsonPath("$.data.height").value(60)).andReturn();
        String mediaId = id(upload);
        var saved = media.findById(mediaId).orElseThrow(); assertThat(saved.getContentType()).isEqualTo("image/jpeg");
        String request = body("with-images","published").replace("\"lead\":", "\"coverImage\":{\"id\":\""+mediaId+"\"},\"lead\":")
                .replace("\"heading\":\"First\"", "\"image\":{\"id\":\""+mediaId+"\"},\"heading\":\"First\"");
        mvc.perform(post("/api/v1/admin/knowledge/articles").with(admin()).contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/knowledge/articles/with-images")).andExpect(jsonPath("$.data.coverImage.alt").value("Image description"))
                .andExpect(jsonPath("$.data.coverImage.caption").value("Caption"))
                .andExpect(jsonPath("$.data.sections[0].image.caption").value("Caption"));
        var captor = org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().key()).matches("article/\\d{4}/\\d{2}/[a-f0-9-]+\\.jpg");
        assertThat(captor.getValue().contentType()).isEqualTo("image/jpeg");
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }
    @Test void coverCaptionOverridesSurviveReadsAndReplacementWithoutChangingMedia() throws Exception {
        String mediaId = id(mvc.perform(multipart("/api/v1/admin/knowledge/media/upload")
                .file(ImageOptimizationServiceTest.file(ImageOptimizationServiceTest.png(20,10), "image/png"))
                .param("caption", "Upload caption").with(admin())).andExpect(status().isCreated()).andReturn());
        String original = body("cover-caption", "published");
        String cover = "\"coverImage\":{\"id\":\"" + mediaId + "\",\"caption\":\"Chú thích ảnh bìa\"},\"lead\":";
        String request = original.replace("\"lead\":", cover);
        String articleId = id(mvc.perform(post("/api/v1/admin/knowledge/articles").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.coverImage.caption").value("Chú thích ảnh bìa"))
                .andReturn());
        mvc.perform(get("/api/v1/admin/knowledge/articles/" + articleId).with(admin()))
                .andExpect(jsonPath("$.data.coverImage.caption").value("Chú thích ảnh bìa"));
        mvc.perform(get("/api/v1/admin/knowledge/articles").with(admin()))
                .andExpect(jsonPath("$.data.items[0].coverImage.caption").value("Chú thích ảnh bìa"));
        mvc.perform(get("/api/v1/knowledge/articles/cover-caption"))
                .andExpect(jsonPath("$.data.coverImage.caption").value("Chú thích ảnh bìa"));
        mvc.perform(get("/api/v1/knowledge/articles"))
                .andExpect(jsonPath("$.data.items[0].coverImage.caption").value("Chú thích ảnh bìa"));
        mvc.perform(put("/api/v1/admin/knowledge/articles/" + articleId).with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(request.replace("Chú thích ảnh bìa", "Updated caption")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.coverImage.caption").value("Updated caption"));
        mvc.perform(get("/api/v1/admin/knowledge/articles/" + articleId).with(admin()))
                .andExpect(jsonPath("$.data.coverImage.caption").value("Updated caption"));
        // Omitting the placement caption uses the upload metadata, matching section images.
        mvc.perform(put("/api/v1/admin/knowledge/articles/" + articleId).with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(original.replace("\"lead\":",
                        "\"coverImage\":{\"id\":\"" + mediaId + "\"},\"lead\":")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.coverImage.caption").value("Upload caption"));
        mvc.perform(put("/api/v1/admin/knowledge/articles/" + articleId).with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(original))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.coverImage").doesNotExist());
        assertThat(articles.findById(articleId).orElseThrow().getCoverImageCaption()).isNull();
        assertThat(media.findById(mediaId).orElseThrow().getCaption()).isEqualTo("Upload caption");
    }
    @Test void concurrentArticleUpdateReturns409AndRollsBackLosingChanges() throws Exception {
        String articleId = id(create("concurrent-update", "draft"));
        long initialVersion = articles.findById(articleId).orElseThrow().getVersion();
        TransactionTemplate competing = new TransactionTemplate(transactions);
        competing.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var firstFlush = new java.util.concurrent.atomic.AtomicBoolean(true);
        var repositoryAnswer = mockingDetails(articles).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            if (firstFlush.getAndSet(false)) {
                // Commit another writer after the article is loaded, before Hibernate flushes.
                competing.executeWithoutResult(transaction -> assertThat(jdbc.update(
                        "UPDATE app.knowledge_articles SET title=?, version=version+1 WHERE id=?",
                        "Winning update", articleId)).isEqualTo(1));
            }
            return repositoryAnswer.answer(invocation);
        }).when(articles).flush();
        mvc.perform(put("/api/v1/admin/knowledge/articles/" + articleId).with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(body("concurrent-update", "published")
                        .replace("Vitamin D", "Losing update").replace("\"heading\":\"First\"", "\"heading\":\"Losing section\"")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        mvc.perform(get("/api/v1/admin/knowledge/articles/" + articleId).with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("Winning update"))
                .andExpect(jsonPath("$.data.status").value("draft"))
                .andExpect(jsonPath("$.data.sections[0].heading").value("First"));
        assertThat(articles.findById(articleId).orElseThrow().getVersion()).isEqualTo(initialVersion + 1);
        mvc.perform(put("/api/v1/admin/knowledge/articles/" + articleId).with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(body("concurrent-update", "published")))
                .andExpect(status().isOk());
    }
    @Test void dbInsertFailureDeletesUploadedObjectAndRollsBackMetadata() throws Exception {
        jdbc.execute("ALTER TABLE app.knowledge_article_media ADD CONSTRAINT test_db_failure CHECK (caption IS NULL OR caption <> 'force-failure')");
        try {
            mvc.perform(multipart("/api/v1/admin/knowledge/media/upload").file(ImageOptimizationServiceTest.file(
                            ImageOptimizationServiceTest.png(20,10), "image/png")).param("caption","force-failure").with(admin()))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("DB_SAVE_FAILED"));
            assertThat(media.count()).isZero();
            var put = org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
            var deleted = org.mockito.ArgumentCaptor.forClass(DeleteObjectRequest.class);
            verify(s3).putObject(put.capture(), any(RequestBody.class)); verify(s3).deleteObject(deleted.capture());
            assertThat(deleted.getValue().key()).isEqualTo(put.getValue().key());
        } finally { jdbc.execute("ALTER TABLE app.knowledge_article_media DROP CONSTRAINT test_db_failure"); }
    }
    @Test void r2FailureDoesNotInsertMetadata() throws Exception {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(S3Exception.builder().message("Unavailable").statusCode(503).build());
        mvc.perform(multipart("/api/v1/admin/knowledge/media/upload").file(ImageOptimizationServiceTest.file(
                        ImageOptimizationServiceTest.png(20,10), "image/png")).with(admin()))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.error.code").value("R2_UPLOAD_FAILED"));
        assertThat(media.count()).isZero(); verify(s3).deleteObject(any(DeleteObjectRequest.class));
    }
    @Test void cleanupFailureStillReturnsStructuredErrorAndRollsBackDatabase() throws Exception {
        jdbc.execute("ALTER TABLE app.knowledge_article_media ADD CONSTRAINT test_cleanup_failure CHECK (caption IS NULL OR caption <> 'force-failure')");
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenThrow(S3Exception.builder().message("Delete unavailable").statusCode(503).build());
        try {
            mvc.perform(multipart("/api/v1/admin/knowledge/media/upload").file(ImageOptimizationServiceTest.file(
                            ImageOptimizationServiceTest.png(20,10), "image/png")).param("caption","force-failure").with(admin()))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("DB_SAVE_FAILED"));
            assertThat(media.count()).isZero();
            mvc.perform(get("/api/v1/knowledge/articles")).andExpect(status().isOk());
        } finally { jdbc.execute("ALTER TABLE app.knowledge_article_media DROP CONSTRAINT test_cleanup_failure"); }
    }
    @Test void actualHttpMultipartEnforcesServletLimitsWithRealJwt() throws Exception {
        var accepted = httpUpload(ImageOptimizationServiceTest.png(20,10));
        assertThat(accepted.statusCode()).isEqualTo(201);
        long before = media.count();
        var rejected = httpUpload(new byte[10*1024*1024+1]);
        assertThat(rejected.statusCode()).isEqualTo(413);
        assertThat(json.readTree(rejected.body()).at("/error/code").stringValue()).isEqualTo("FILE_TOO_LARGE");
        assertThat(media.count()).isEqualTo(before);
    }
    private java.net.http.HttpResponse<String> httpUpload(byte[] image) throws Exception {
        var now = java.time.Instant.now();
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer("nutrimom-test")
                .subject(adminId).issuedAt(now).expiresAt(now.plusSeconds(60)).claim("roles", List.of("ADMIN")).build();
        var header = org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
        String token = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(header, claims)).getTokenValue();
        String boundary = "knowledge-test-boundary";
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"image.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.write(image); body.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/api/v1/admin/knowledge/media/upload"))
                .timeout(java.time.Duration.ofSeconds(30)).header("Authorization", "Bearer "+token)
                .header("Content-Type", "multipart/form-data; boundary="+boundary)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
    }
    @Test void uploadAndMutationAreAdminOnlyAndInvalidPayloadsNeverReachR2() throws Exception {
        mvc.perform(post("/api/v1/admin/knowledge/articles").contentType(MediaType.APPLICATION_JSON).content(body("no-auth","draft"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/knowledge/articles").with(member()).contentType(MediaType.APPLICATION_JSON).content(body("no-role","draft"))).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/admin/knowledge/media/upload").with(member())).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/admin/knowledge/media/upload").with(admin())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_MULTIPART"));
        mvc.perform(multipart("/api/v1/admin/knowledge/media/upload").file(ImageOptimizationServiceTest.file(new byte[]{1}, "image/gif")).with(admin()))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.error.code").value("INVALID_FILE_TYPE"));
        mvc.perform(multipart("/api/v1/admin/knowledge/media/upload").file(ImageOptimizationServiceTest.file(
                        new byte[10*1024*1024+1], "image/png")).with(admin()))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.error.code").value("FILE_TOO_LARGE"));
        verifyNoInteractions(s3);
    }
    @Test void rejectsDuplicateSlugBadTaxonomyAndInvalidQuery() throws Exception {
        create("unique-slug","draft");
        mvc.perform(post("/api/v1/admin/knowledge/articles").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body("unique-slug","draft")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SLUG_ALREADY_EXISTS"));
        mvc.perform(post("/api/v1/admin/knowledge/articles").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body("Invalid Slug","draft"))).andExpect(status().isUnprocessableContent());
        mvc.perform(post("/api/v1/admin/knowledge/articles").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body("bad-category","draft").replace("nutrition","unknown"))).andExpect(status().isUnprocessableContent());
        mvc.perform(get("/api/v1/knowledge/articles").param("page","zero")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/knowledge/articles").param("pageSize","101")).andExpect(status().isUnprocessableContent());
        mvc.perform(get("/api/v1/knowledge/articles").param("page", "2147483647").param("pageSize", "100")).andExpect(status().isUnprocessableContent());
        mvc.perform(get("/api/v1/knowledge/articles").param("sort","passwordHash:desc")).andExpect(status().isUnprocessableContent());
        mvc.perform(post("/api/v1/admin/knowledge/articles").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("vietnamese-filters","published").replace("nutrition","Dinh dưỡng").replace("trimester-1","Trong thai kỳ"))).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/knowledge/articles").param("category","Dinh dưỡng").param("stage","Trong thai kỳ"))
                .andExpect(jsonPath("$.data.totalItems").value(1));
    }
}
