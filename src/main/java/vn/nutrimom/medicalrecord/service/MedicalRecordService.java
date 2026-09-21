package vn.nutrimom.medicalrecord.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.api.CursorPage;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.file.domain.FileEntity;
import vn.nutrimom.file.domain.FileStatus;
import vn.nutrimom.file.repository.FileRepository;
import vn.nutrimom.medicalrecord.domain.MedicalRecordAuditEntity;
import vn.nutrimom.medicalrecord.domain.MedicalRecordCategory;
import vn.nutrimom.medicalrecord.domain.MedicalRecordEntity;
import vn.nutrimom.medicalrecord.dto.CreateMedicalRecordRequest;
import vn.nutrimom.medicalrecord.dto.MedicalRecordAttachmentResponse;
import vn.nutrimom.medicalrecord.dto.MedicalRecordResponse;
import vn.nutrimom.medicalrecord.dto.UpdateMedicalRecordRequest;
import vn.nutrimom.medicalrecord.repository.MedicalRecordAuditRepository;
import vn.nutrimom.medicalrecord.repository.MedicalRecordRepository;
import vn.nutrimom.pregnancy.domain.PregnancyEntity;
import vn.nutrimom.pregnancy.domain.PregnancyStatus;
import vn.nutrimom.pregnancy.repository.PregnancyRepository;

@Service
public class MedicalRecordService {
    private final UserRepository users;
    private final PregnancyRepository pregnancies;
    private final MedicalRecordRepository records;
    private final MedicalRecordAuditRepository audits;
    private final FileRepository files;

    public MedicalRecordService(UserRepository users, PregnancyRepository pregnancies,
                                MedicalRecordRepository records, MedicalRecordAuditRepository audits,
                                FileRepository files) {
        this.users = users;
        this.pregnancies = pregnancies;
        this.records = records;
        this.audits = audits;
        this.files = files;
    }

    @Transactional(readOnly = true)
    public CursorPage<MedicalRecordResponse> list(String userId, String pregnancyId,
                                                  MedicalRecordCategory category, LocalDate from,
                                                  LocalDate to, String cursor, int limit) {
        requireActiveUser(userId);
        if (pregnancyId != null) requireOwnedPregnancy(userId, pregnancyId, false);
        int pageSize = normalizeLimit(limit);
        List<MedicalRecordEntity> filtered = records
                .findByOwnerUserIdAndDeletedAtIsNullOrderByOccurredAtDescIdDesc(userId)
                .stream()
                .filter(record -> pregnancyId == null || pregnancyId.equals(record.getPregnancyId()))
                .filter(record -> category == null || category == record.getCategory())
                .filter(record -> from == null || !record.getOccurredAt().toLocalDate().isBefore(from))
                .filter(record -> to == null || !record.getOccurredAt().toLocalDate().isAfter(to))
                .toList();
        int offset = decodeCursor(cursor, filtered.size());
        int end = Math.min(offset + pageSize, filtered.size());
        List<MedicalRecordResponse> page = filtered.subList(offset, end).stream()
                .map(record -> toResponse(record, false)).toList();
        boolean hasMore = end < filtered.size();
        return new CursorPage<>(page, hasMore ? encodeCursor(end) : null, hasMore);
    }

    @Transactional
    public MedicalRecordResponse create(String userId, CreateMedicalRecordRequest request) {
        requireActiveUser(userId);
        PregnancyEntity pregnancy = requireOwnedPregnancy(userId, request.pregnancyId(), true);
        MedicalRecordEntity record = new MedicalRecordEntity();
        record.setOwnerUserId(userId);
        record.setPregnancyId(pregnancy.getId());
        record.setCategory(request.category());
        record.setTitle(request.title().trim());
        record.setOccurredAt(request.occurredAt());
        record.setFacilityName(trimToNull(request.facilityName()));
        record.setClinicianName(trimToNull(request.clinicianName()));
        record.setSummary(trimToNull(request.summary()));
        record.setNote(trimToNull(request.note()));
        records.saveAndFlush(record);
        attachFiles(userId, record, request.attachmentIds());
        audit(record.getId(), userId, "CREATED");
        return toResponse(record, true);
    }

    @Transactional
    public MedicalRecordResponse get(String userId, String id) {
        requireActiveUser(userId);
        MedicalRecordEntity record = loadOwned(userId, id);
        audit(record.getId(), userId, "VIEWED");
        return toResponse(record, true);
    }

    @Transactional
    public MedicalRecordResponse update(String userId, String id, UpdateMedicalRecordRequest request) {
        requireActiveUser(userId);
        MedicalRecordEntity record = loadOwned(userId, id);
        if (request.version() != record.getVersion()) throw versionConflict();
        if (request.category() != null) record.setCategory(request.category());
        if (request.title() != null) record.setTitle(request.title().trim());
        if (request.occurredAt() != null) record.setOccurredAt(request.occurredAt());
        if (request.facilityName() != null) record.setFacilityName(trimToNull(request.facilityName()));
        if (request.clinicianName() != null) record.setClinicianName(trimToNull(request.clinicianName()));
        if (request.summary() != null) record.setSummary(trimToNull(request.summary()));
        if (request.note() != null) record.setNote(trimToNull(request.note()));
        records.saveAndFlush(record);
        attachFiles(userId, record, request.attachmentIds());
        audit(record.getId(), userId, "UPDATED");
        return toResponse(record, true);
    }

    @Transactional
    public void delete(String userId, String id) {
        requireActiveUser(userId);
        MedicalRecordEntity record = loadOwned(userId, id);
        record.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        records.saveAndFlush(record);
        audit(record.getId(), userId, "DELETED");
    }

    private void attachFiles(String userId, MedicalRecordEntity record, List<String> attachmentIds) {
        if (attachmentIds == null) return;
        for (String attachmentId : attachmentIds) {
            FileEntity file = files.findByIdAndOwnerUserId(attachmentId, userId).orElseThrow(this::notFound);
            if (file.getStatus() != FileStatus.READY) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "UPLOAD_NOT_COMPLETE",
                        "Only completed files can be attached to a medical record.");
            }
            if (file.getMedicalRecordId() != null && !record.getId().equals(file.getMedicalRecordId())) {
                throw new BusinessException(HttpStatus.CONFLICT, "FILE_ALREADY_ATTACHED",
                        "The file is already attached to another medical record.");
            }
            file.setMedicalRecordId(record.getId());
            file.setPregnancyId(record.getPregnancyId());
            files.save(file);
        }
    }

    private MedicalRecordResponse toResponse(MedicalRecordEntity record, boolean includeAttachments) {
        List<FileEntity> attachedFiles = files.findByMedicalRecordIdAndOwnerUserId(
                record.getId(), record.getOwnerUserId());
        List<MedicalRecordAttachmentResponse> attachments = includeAttachments
                ? attachedFiles.stream()
                    .map(file -> new MedicalRecordAttachmentResponse(file.getId(), file.getFileName(),
                            file.getMimeType(), file.getSizeBytes(), file.getStatus().name())).toList()
                : List.of();
        return new MedicalRecordResponse(record.getId(), record.getPregnancyId(), record.getCategory().name(),
                record.getTitle(), record.getOccurredAt(), record.getFacilityName(), record.getClinicianName(),
                record.getSummary(), record.getNote(), attachedFiles.size(), attachments, record.getVersion(),
                record.getCreatedAt(), record.getUpdatedAt());
    }

    private void audit(String recordId, String userId, String eventType) {
        MedicalRecordAuditEntity audit = new MedicalRecordAuditEntity();
        audit.setMedicalRecordId(recordId);
        audit.setUserId(userId);
        audit.setEventType(eventType);
        audits.save(audit);
    }

    private PregnancyEntity requireOwnedPregnancy(String userId, String pregnancyId, boolean activeOnly) {
        return pregnancies.findByIdAndOwnerUserId(pregnancyId, userId)
                .filter(item -> !activeOnly || item.getStatus() == PregnancyStatus.ACTIVE)
                .orElseThrow(this::notFound);
    }

    private MedicalRecordEntity loadOwned(String userId, String id) {
        return records.findByIdAndOwnerUserIdAndDeletedAtIsNull(id, userId).orElseThrow(this::notFound);
    }

    private void requireActiveUser(String userId) {
        users.findById(userId).filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED,
                        "UNAUTHORIZED", "The authenticated account is unavailable."));
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) throw validation("limit must be at least 1.");
        return Math.min(limit, 100);
    }

    private int decodeCursor(String cursor, int size) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            int offset = Integer.parseInt(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
            if (offset < 0 || offset > size) throw new IllegalArgumentException();
            return offset;
        } catch (IllegalArgumentException ex) {
            throw validation("cursor is invalid.");
        }
    }

    private String encodeCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    private String trimToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private BusinessException versionConflict() {
        return new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                "The medical record was updated elsewhere. Reload and try again.");
    }

    private BusinessException validation(String message) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_ERROR", message);
    }

    private BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Medical record was not found.");
    }
}
