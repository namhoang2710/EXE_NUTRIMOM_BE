package vn.nutrimom.care;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import vn.nutrimom.support.ApiIntegrationTestSupport;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CareAndMedicalIntegrationTest extends ApiIntegrationTestSupport {

    @Test
    void carePlanPreparationAndBirthPlanUseOwnershipAndVersions() throws Exception {
        Session mom = registerViaOtp("0912397001", "Care Mom");
        String pregnancyId = createPregnancy(mom);

        mockMvc.perform(get("/api/v1/care-plans/current")
                        .header("Authorization", bearer(mom)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pregnancy_id").value(pregnancyId))
                .andExpect(jsonPath("$.data.milestones").isArray());

        MvcResult preparation = mockMvc.perform(get("/api/v1/preparation-items")
                        .header("Authorization", bearer(mom)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andReturn();
        String itemId = objectMapper.readTree(preparation.getResponse().getContentAsString())
                .at("/data/0/id").stringValue();

        mockMvc.perform(patch("/api/v1/preparation-items/{id}", itemId)
                        .header("Authorization", bearer(mom))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\":true,\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completed").value(true))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(patch("/api/v1/preparation-items/{id}", itemId)
                        .header("Authorization", bearer(mom))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\":false,\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));

        mockMvc.perform(get("/api/v1/birth-plans/current")
                        .header("Authorization", bearer(mom)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0));

        mockMvc.perform(put("/api/v1/birth-plans/current")
                        .header("Authorization", bearer(mom))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companion\":\"Partner\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companion").value("Partner"))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(get("/api/v1/dashboard/mom")
                        .header("Authorization", bearer(mom)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.care_progress.completed").value(1));

        mockMvc.perform(get("/api/v1/verified-guidance")
                        .header("Authorization", bearer(mom)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.has_more").value(false));
    }

    @Test
    void medicalRecordsAreOwnerScopedSoftDeletedAndOptimisticallyLocked() throws Exception {
        Session owner = registerViaOtp("0912397002", "Record Owner");
        Session foreign = registerViaOtp("0912397003", "Record Foreign");
        String pregnancyId = createPregnancy(owner);

        MvcResult created = mockMvc.perform(post("/api/v1/medical-records")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRecord(
                                pregnancyId, "LAB_RESULT", "Blood test", "2026-09-19T07:00:00Z"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.attachment_count").value(0))
                .andReturn();
        JsonNode record = objectMapper.readTree(created.getResponse().getContentAsString()).at("/data");
        String recordId = record.at("/id").stringValue();

        mockMvc.perform(get("/api/v1/medical-records/{id}", recordId)
                        .header("Authorization", bearer(foreign)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(patch("/api/v1/medical-records/{id}", recordId)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"Reviewed\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(patch("/api/v1/medical-records/{id}", recordId)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"Stale\",\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));

        mockMvc.perform(get("/api/v1/medical-records")
                        .header("Authorization", bearer(owner))
                        .queryParam("pregnancy_id", pregnancyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)));

        mockMvc.perform(delete("/api/v1/medical-records/{id}", recordId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/medical-records/{id}", recordId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void filesRequireOwnershipValidateChecksumAndProvidePrivateDownloadUrl() throws Exception {
        Session owner = registerViaOtp("0912397004", "File Owner");
        Session foreign = registerViaOtp("0912397005", "File Foreign");
        byte[] pdfBytes = "%PDF-1.7\nNutriMom test\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        String checksum = sha256(pdfBytes);

        MvcResult session = mockMvc.perform(post("/api/v1/files/upload-sessions")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UploadRequest(
                                "MEDICAL_RECORD", "report.pdf", "application/pdf", pdfBytes.length, checksum))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.file_id").isString())
                .andReturn();
        JsonNode upload = objectMapper.readTree(session.getResponse().getContentAsString()).at("/data");
        String fileId = upload.at("/file_id").stringValue();

        mockMvc.perform(get("/api/v1/files/{id}/download-url", fileId)
                        .header("Authorization", bearer(foreign)))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/v1/files/{id}/content", fileId)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_PDF)
                        .content(pdfBytes))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/files/{id}/complete", fileId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"));

        MvcResult url = mockMvc.perform(get("/api/v1/files/{id}/download-url", fileId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andReturn();
        String downloadUrl = objectMapper.readTree(url.getResponse().getContentAsString())
                .at("/data/download_url").stringValue();
        mockMvc.perform(get(downloadUrl))
                .andExpect(status().isOk())
                .andExpect(content().bytes(pdfBytes));
    }

    private String createPregnancy(Session session) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/pregnancies")
                        .header("Authorization", bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PregnancyBody(LocalDate.now(ZoneOffset.UTC).plusDays(100)))))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data/id").stringValue();
    }

    private String bearer(Session session) { return "Bearer " + session.accessToken(); }

    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private record PregnancyBody(LocalDate estimatedDueDate) { }
    private record CreateRecord(String pregnancyId, String category, String title, String occurredAt) { }
    private record UploadRequest(String purpose, String fileName, String mimeType, long sizeBytes, String sha256) { }
}
