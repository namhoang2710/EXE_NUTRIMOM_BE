package vn.nutrimom.file.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.file.domain.FileEntity;
import vn.nutrimom.file.domain.FileStatus;
import vn.nutrimom.file.dto.CompleteFileRequest;
import vn.nutrimom.file.dto.CreateUploadSessionRequest;
import vn.nutrimom.file.dto.DownloadUrlResponse;
import vn.nutrimom.file.dto.FileResponse;
import vn.nutrimom.file.dto.UploadSessionResponse;
import vn.nutrimom.file.repository.FileRepository;
import vn.nutrimom.medicalrecord.repository.MedicalRecordRepository;
import vn.nutrimom.pregnancy.repository.PregnancyRepository;

@Service
public class FileService {
    private static final long MAX_FILE_SIZE = 25L * 1024L * 1024L;
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/png", "image/webp");
    private static final int DOWNLOAD_TTL_MINUTES = 5;

    private final UserRepository users;
    private final PregnancyRepository pregnancies;
    private final MedicalRecordRepository records;
    private final FileRepository files;
    private final StorageService storage;
    private final MalwareScanner malwareScanner;
    private final SecureRandom random = new SecureRandom();

    public FileService(UserRepository users, PregnancyRepository pregnancies,
                       MedicalRecordRepository records, FileRepository files,
                       StorageService storage, MalwareScanner malwareScanner) {
        this.users = users;
        this.pregnancies = pregnancies;
        this.records = records;
        this.files = files;
        this.storage = storage;
        this.malwareScanner = malwareScanner;
    }

    @Transactional
    public UploadSessionResponse createUploadSession(String userId, CreateUploadSessionRequest request) {
        requireActiveUser(userId);
        String mimeType = request.mimeType().trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_MIME_TYPES.contains(mimeType)) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_FILE_TYPE",
                    "The requested MIME type is not supported.");
        }
        if (request.sizeBytes() > MAX_FILE_SIZE) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "FILE_TOO_LARGE",
                    "The file exceeds the maximum allowed size.");
        }
        String pregnancyId = request.pregnancyId();
        if (pregnancyId != null && !pregnancies.findByIdAndOwnerUserId(pregnancyId, userId).isPresent()) {
            throw notFound();
        }
        if (request.medicalRecordId() != null) {
            var record = records.findByIdAndOwnerUserIdAndDeletedAtIsNull(request.medicalRecordId(), userId)
                    .orElseThrow(this::notFound);
            if (pregnancyId != null && !pregnancyId.equals(record.getPregnancyId())) throw notFound();
            pregnancyId = record.getPregnancyId();
        }
        FileEntity file = new FileEntity();
        file.setOwnerUserId(userId);
        file.setPregnancyId(pregnancyId);
        file.setMedicalRecordId(request.medicalRecordId());
        file.setPurpose(request.purpose().trim().toUpperCase(Locale.ROOT));
        file.setFileName(sanitizeFileName(request.fileName()));
        file.setMimeType(mimeType);
        file.setSizeBytes(request.sizeBytes());
        file.setSha256(request.sha256().toLowerCase(Locale.ROOT));
        file.setStatus(FileStatus.UPLOADING);
        file.setUploadExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15));
        file.setStorageKey(userId + "/pending/" + UUID.randomUUID());
        files.saveAndFlush(file);
        file.setStorageKey(userId + "/" + file.getId() + "/" + UUID.randomUUID());
        files.saveAndFlush(file);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", mimeType);
        headers.put("X-File-Id", file.getId());
        return new UploadSessionResponse(file.getId(), storage.uploadUrl(file.getId()), headers,
                file.getUploadExpiresAt());
    }

    @Transactional
    public void putContent(String userId, String fileId, byte[] content) {
        FileEntity file = loadOwned(userId, fileId);
        if (file.getStatus() != FileStatus.UPLOADING || isExpired(file.getUploadExpiresAt())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "UPLOAD_NOT_COMPLETE",
                    "The upload session is no longer active.");
        }
        if (content == null || content.length == 0) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "UPLOAD_NOT_COMPLETE",
                    "The uploaded content is empty.");
        }
        if (content.length > MAX_FILE_SIZE) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "FILE_TOO_LARGE",
                    "The file exceeds the maximum allowed size.");
        }
        storage.put(file.getStorageKey(), content);
    }

    @Transactional
    public FileResponse complete(String userId, String fileId, CompleteFileRequest request) {
        FileEntity file = loadOwned(userId, fileId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (file.getStatus() == FileStatus.READY) return toResponse(file);
        if (file.getStatus() != FileStatus.UPLOADING || !file.getUploadExpiresAt().isAfter(now)) {
            file.setStatus(FileStatus.EXPIRED);
            files.save(file);
            throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "UPLOAD_NOT_COMPLETE",
                    "The upload session has expired or is incomplete.");
        }
        StorageService.StoredObject object = storage.head(file.getStorageKey()).orElseThrow(() ->
                new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "UPLOAD_NOT_COMPLETE",
                        "The object was not found in storage."));
        if (object.sizeBytes() != file.getSizeBytes()
                || (request != null && request.sizeBytes() != null && request.sizeBytes() != object.sizeBytes())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "UPLOAD_NOT_COMPLETE",
                    "The uploaded file size does not match the upload session.");
        }
        String actualSha256 = sha256(object.content());
        if (!actualSha256.equalsIgnoreCase(file.getSha256())
                || (request != null && request.sha256() != null && !actualSha256.equalsIgnoreCase(request.sha256()))) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "UPLOAD_NOT_COMPLETE",
                    "The uploaded file checksum does not match the upload session.");
        }
        if (!mimeMatches(file.getMimeType(), object.content())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_FILE_TYPE",
                    "The uploaded content does not match the declared MIME type.");
        }
        MalwareScanner.ScanResult scan = malwareScanner.scan(object.content());
        if (!scan.clean()) {
            file.setStatus(FileStatus.QUARANTINED);
            files.saveAndFlush(file);
            throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "MALWARE_DETECTED",
                    "The uploaded file did not pass malware scanning.");
        }
        file.setStatus(FileStatus.READY);
        file.setCompletedAt(now);
        files.saveAndFlush(file);
        return toResponse(file);
    }

    @Transactional
    public DownloadUrlResponse createDownloadUrl(String userId, String fileId) {
        FileEntity file = loadOwned(userId, fileId);
        if (file.getStatus() != FileStatus.READY) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "UPLOAD_NOT_COMPLETE",
                    "The file is not ready for download.");
        }
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(DOWNLOAD_TTL_MINUTES);
        String token = randomToken();
        file.setDownloadTokenHash(sha256(token.getBytes(StandardCharsets.UTF_8)));
        file.setDownloadTokenExpiresAt(expiresAt);
        files.saveAndFlush(file);
        return new DownloadUrlResponse(file.getId(), "/api/v1/files/" + file.getId()
                + "/content?token=" + token, expiresAt);
    }

    @Transactional(readOnly = true)
    public DownloadedFile download(String authenticatedUserId, String fileId, String token) {
        FileEntity file = files.findById(fileId).orElseThrow(this::notFound);
        boolean owner = authenticatedUserId != null && authenticatedUserId.equals(file.getOwnerUserId());
        boolean signed = token != null && file.getDownloadTokenHash() != null
                && file.getDownloadTokenExpiresAt() != null
                && file.getDownloadTokenExpiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))
                && MessageDigest.isEqual(file.getDownloadTokenHash().getBytes(StandardCharsets.US_ASCII),
                        sha256(token.getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.US_ASCII));
        if (!owner && !signed) throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "File access is not allowed.");
        if (file.getStatus() != FileStatus.READY) throw notFound();
        return new DownloadedFile(file.getFileName(), file.getMimeType(), storage.read(file.getStorageKey()));
    }

    private FileResponse toResponse(FileEntity file) {
        return new FileResponse(file.getId(), file.getPurpose(), file.getFileName(), file.getMimeType(),
                file.getSizeBytes(), file.getSha256(), file.getStatus().name(), file.getPregnancyId(),
                file.getMedicalRecordId(), file.getUploadExpiresAt(), file.getCompletedAt());
    }

    private FileEntity loadOwned(String userId, String fileId) {
        return files.findByIdAndOwnerUserId(fileId, userId).orElseThrow(this::notFound);
    }

    private void requireActiveUser(String userId) {
        users.findById(userId).filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED,
                        "UNAUTHORIZED", "The authenticated account is unavailable."));
    }

    private boolean isExpired(OffsetDateTime expiresAt) {
        return expiresAt == null || !expiresAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC));
    }

    private String sanitizeFileName(String name) {
        String sanitized = name.trim().replace('\\', '/');
        sanitized = sanitized.substring(sanitized.lastIndexOf('/') + 1);
        if (sanitized.isBlank() || sanitized.equals(".") || sanitized.equals("..")) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_ERROR",
                    "file_name is invalid.");
        }
        return sanitized;
    }

    private boolean mimeMatches(String expected, byte[] content) {
        if (content.length >= 4 && content[0] == '%' && content[1] == 'P'
                && content[2] == 'D' && content[3] == 'F') return "application/pdf".equals(expected);
        if (content.length >= 8 && (content[0] & 0xff) == 0x89 && content[1] == 'P'
                && content[2] == 'N' && content[3] == 'G') return "image/png".equals(expected);
        if (content.length >= 3 && (content[0] & 0xff) == 0xff && (content[1] & 0xff) == 0xd8
                && (content[2] & 0xff) == 0xff) return "image/jpeg".equals(expected);
        return true;
    }

    private String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception ex) { throw new IllegalStateException("SHA-256 is not available", ex); }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "File was not found.");
    }

    public record DownloadedFile(String fileName, String mimeType, byte[] content) { }
}
