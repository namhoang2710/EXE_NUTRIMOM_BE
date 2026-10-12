package vn.nutrimom.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import vn.nutrimom.assistant.service.AiProviderClient;
import vn.nutrimom.consultation.video.LiveKitGateway;
import vn.nutrimom.file.domain.FileStatus;
import vn.nutrimom.file.domain.StorageProvider;
import vn.nutrimom.file.repository.FileRepository;
import vn.nutrimom.file.service.PrivateR2StorageService;
import vn.nutrimom.support.ApiIntegrationTestSupport;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PrivateFileR2IntegrationTest.StorageTestConfig.class)
class PrivateFileR2IntegrationTest extends ApiIntegrationTestSupport {
    @Autowired FileRepository files;
    @Autowired S3Harness harness;
    @MockitoBean AiProviderClient assistantProvider;
    @MockitoBean LiveKitGateway videoProvider;

    @Test
    void medicalImageIsStoredAndDownloadedWithoutChangingItsBytes() throws Exception {
        Session owner = registerViaOtp("0912388005", "Private image owner");
        byte[] bytes = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5V8AAAAASUVORK5CYII=");
        String fileId = createUpload(owner, bytes, "scan.png", "image/png");
        mockMvc.perform(put("/api/v1/files/{id}/content", fileId).header("Authorization", bearer(owner))
                        .contentType(MediaType.IMAGE_PNG).content(bytes))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/files/{id}/complete", fileId).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY"));
        mockMvc.perform(get("/api/v1/files/{id}/content", fileId).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void privateR2FileCompletesAttachesAndDownloadsWithOwnershipAndExpiringToken() throws Exception {
        Session owner = registerViaOtp("0912388001", "Private file owner");
        Session foreign = registerViaOtp("0912388002", "Foreign file user");
        byte[] bytes = "%PDF-1.7\nPrivate medical integration test\n".getBytes(StandardCharsets.US_ASCII);
        String fileId = createUpload(owner, bytes);
        var stored = files.findById(fileId).orElseThrow();
        assertThat(stored.getStorageProvider()).isEqualTo(StorageProvider.R2);
        assertThat(stored.getStorageBucket()).isEqualTo("private-integration-bucket");
        assertThat(stored.getStorageKey()).startsWith("storage/" + owner.userId() + "/" + fileId + "/");

        mockMvc.perform(put("/api/v1/files/{id}/content", fileId).header("Authorization", bearer(foreign))
                        .contentType(MediaType.APPLICATION_PDF).content(bytes))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/files/{id}/content", fileId).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_PDF).content(bytes))
                .andExpect(status().isNoContent());
        assertThat(harness.objects.get(stored.getStorageBucket() + "/" + stored.getStorageKey())).isEqualTo(bytes);
        mockMvc.perform(post("/api/v1/files/{id}/complete", fileId).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY"));
        mockMvc.perform(post("/api/v1/files/{id}/complete", fileId).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY"));

        var pregnancy = mockMvc.perform(post("/api/v1/pregnancies").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"calculation_source\":\"MANUAL\",\"gestational_week\":20,\"gestational_day\":3}"))
                .andExpect(status().isCreated()).andReturn();
        String pregnancyId = objectMapper.readTree(pregnancy.getResponse().getContentAsString()).at("/data/id").stringValue();
        var record = mockMvc.perform(post("/api/v1/medical-records").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("pregnancy_id", pregnancyId,
                                "category", "LAB_RESULT", "title", "Private report", "occurred_at", "2026-09-19T07:00:00Z",
                                "attachment_ids", java.util.List.of(fileId)))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.attachment_count").value(1)).andReturn();
        String recordId = objectMapper.readTree(record.getResponse().getContentAsString()).at("/data/id").stringValue();
        mockMvc.perform(get("/api/v1/medical-records/{id}", recordId).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.attachments[0].id").value(fileId));
        mockMvc.perform(get("/api/v1/files/{id}/download-url", fileId).header("Authorization", bearer(foreign)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/files/{id}/content", fileId).header("Authorization", bearer(foreign)))
                .andExpect(status().isForbidden());
        var result = mockMvc.perform(get("/api/v1/files/{id}/download-url", fileId).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andReturn();
        String url = objectMapper.readTree(result.getResponse().getContentAsString()).at("/data/download_url").stringValue();
        assertThat(url).startsWith("/api/v1/files/").doesNotContain("r2.dev", "cloudflarestorage.com");
        mockMvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().bytes(bytes))
                .andExpect(header().string("Cache-Control", "private, no-store"));
        var expired = files.findById(fileId).orElseThrow();
        expired.setDownloadTokenExpiresAt(java.time.OffsetDateTime.now().minusMinutes(1));
        files.saveAndFlush(expired);
        mockMvc.perform(get(url)).andExpect(status().isForbidden());
    }

    @Test
    void nonMedicalUploadSessionDoesNotUsePrivateR2() throws Exception {
        Session owner = registerViaOtp("0912388006", "Other purpose owner");
        byte[] bytes = "%PDF-1.7\nother purpose test\n".getBytes(StandardCharsets.US_ASCII);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var result = mockMvc.perform(post("/api/v1/files/upload-sessions").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("purpose", "OTHER", "file_name", "other.pdf",
                                "mime_type", "application/pdf", "size_bytes", bytes.length, "sha256", hash))))
                .andExpect(status().isOk()).andReturn();
        String fileId = objectMapper.readTree(result.getResponse().getContentAsString()).at("/data/file_id").stringValue();
        var file = files.findById(fileId).orElseThrow();
        assertThat(file.getStorageProvider()).isEqualTo(StorageProvider.LOCAL);
        assertThat(file.getStorageBucket()).isNull();
        assertThat(file.getStorageKey()).doesNotStartWith("storage/");
    }

    @Test
    void unavailableR2DoesNotCompleteOrSilentlyWriteToLocalStorage() throws Exception {
        Session owner = registerViaOtp("0912388003", "Storage failure user");
        byte[] bytes = "%PDF-1.7\nfailure test\n".getBytes(StandardCharsets.US_ASCII);
        String fileId = createUpload(owner, bytes);
        var file = files.findById(fileId).orElseThrow();
        harness.failedKeys.add(file.getStorageKey());
        mockMvc.perform(put("/api/v1/files/{id}/content", fileId).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_PDF).content(bytes))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.error.code").value("FILE_STORAGE_UNAVAILABLE"));
        assertThat(files.findById(fileId).orElseThrow().getStatus()).isEqualTo(FileStatus.UPLOADING);
        harness.failedKeys.remove(file.getStorageKey());
        mockMvc.perform(post("/api/v1/files/{id}/complete", fileId).header("Authorization", bearer(owner)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("UPLOAD_NOT_COMPLETE"));
        assertThat(files.findById(fileId).orElseThrow().getStatus()).isEqualTo(FileStatus.UPLOADING);
    }

    @Test
    void tamperedR2ObjectIsRejectedByChecksumValidation() throws Exception {
        Session owner = registerViaOtp("0912388004", "Checksum user");
        byte[] bytes = "%PDF-1.7\nchecksum test\n".getBytes(StandardCharsets.US_ASCII);
        String fileId = createUpload(owner, bytes);
        mockMvc.perform(put("/api/v1/files/{id}/content", fileId).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_PDF).content(bytes))
                .andExpect(status().isNoContent());
        var file = files.findById(fileId).orElseThrow();
        byte[] tampered = bytes.clone();
        tampered[tampered.length - 2] ^= 1;
        harness.objects.put(file.getStorageBucket() + "/" + file.getStorageKey(), tampered);
        mockMvc.perform(post("/api/v1/files/{id}/complete", fileId).header("Authorization", bearer(owner)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("UPLOAD_NOT_COMPLETE"));
        assertThat(files.findById(fileId).orElseThrow().getStatus()).isEqualTo(FileStatus.UPLOADING);
    }

    private String createUpload(Session owner, byte[] bytes) throws Exception {
        return createUpload(owner, bytes, "report.pdf", "application/pdf");
    }

    private String createUpload(Session owner, byte[] bytes, String fileName, String mimeType) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var result = mockMvc.perform(post("/api/v1/files/upload-sessions").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("purpose", "MEDICAL_RECORD", "file_name", fileName,
                                "mime_type", mimeType, "size_bytes", bytes.length, "sha256", hash))))
                .andExpect(status().isOk()).andReturn();
        var upload = objectMapper.readTree(result.getResponse().getContentAsString()).at("/data");
        String id = upload.at("/file_id").stringValue();
        assertThat(upload.at("/upload_url").stringValue()).isEqualTo("/api/v1/files/" + id + "/content");
        return id;
    }

    private String bearer(Session session) { return "Bearer " + session.accessToken(); }

    static class S3Harness {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        final java.util.Set<String> failedKeys = ConcurrentHashMap.newKeySet();
        final S3Client client = mock(S3Client.class);

        S3Harness() {
            when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
                PutObjectRequest request = call.getArgument(0);
                if (failedKeys.contains(request.key())) throw S3Exception.builder().statusCode(503).build();
                RequestBody body = call.getArgument(1);
                try (var input = body.contentStreamProvider().newStream()) {
                    objects.put(request.bucket() + "/" + request.key(), input.readAllBytes());
                }
                return PutObjectResponse.builder().build();
            });
            when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenAnswer(call -> {
                GetObjectRequest request = call.getArgument(0);
                if (failedKeys.contains(request.key())) throw S3Exception.builder().statusCode(503).build();
                byte[] bytes = objects.get(request.bucket() + "/" + request.key());
                if (bytes == null) throw S3Exception.builder().statusCode(404).build();
                return ResponseBytes.fromByteArray(GetObjectResponse.builder().contentLength((long) bytes.length).build(), bytes);
            });
        }
    }

    @TestConfiguration
    static class StorageTestConfig {
        @Bean S3Harness s3Harness() { return new S3Harness(); }
        @Bean PrivateR2StorageService testPrivateR2Storage(S3Harness harness) {
            return new PrivateR2StorageService(harness.client, "private-integration-bucket");
        }
    }
}
