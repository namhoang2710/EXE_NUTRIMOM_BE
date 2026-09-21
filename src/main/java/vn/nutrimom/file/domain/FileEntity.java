package vn.nutrimom.file.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "files", schema = "app")
public class FileEntity {
    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "owner_user_id", nullable = false, length = 36)
    private String ownerUserId;

    @Column(name = "pregnancy_id", length = 36)
    private String pregnancyId;

    @Column(name = "medical_record_id", length = 36)
    private String medicalRecordId;

    @Column(name = "purpose", nullable = false, length = 50)
    private String purpose;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FileStatus status;

    @Column(name = "upload_expires_at", nullable = false)
    private OffsetDateTime uploadExpiresAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "download_token_hash", length = 64)
    private String downloadTokenHash;

    @Column(name = "download_token_expires_at")
    private OffsetDateTime downloadTokenExpiresAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void beforeInsert() {
        if (id == null) id = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void beforeUpdate() { updatedAt = OffsetDateTime.now(ZoneOffset.UTC); }

    public String getId() { return id; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String value) { ownerUserId = value; }
    public String getPregnancyId() { return pregnancyId; }
    public void setPregnancyId(String value) { pregnancyId = value; }
    public String getMedicalRecordId() { return medicalRecordId; }
    public void setMedicalRecordId(String value) { medicalRecordId = value; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String value) { purpose = value; }
    public String getFileName() { return fileName; }
    public void setFileName(String value) { fileName = value; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String value) { mimeType = value; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long value) { sizeBytes = value; }
    public String getSha256() { return sha256; }
    public void setSha256(String value) { sha256 = value; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String value) { storageKey = value; }
    public FileStatus getStatus() { return status; }
    public void setStatus(FileStatus value) { status = value; }
    public OffsetDateTime getUploadExpiresAt() { return uploadExpiresAt; }
    public void setUploadExpiresAt(OffsetDateTime value) { uploadExpiresAt = value; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime value) { completedAt = value; }
    public String getDownloadTokenHash() { return downloadTokenHash; }
    public void setDownloadTokenHash(String value) { downloadTokenHash = value; }
    public OffsetDateTime getDownloadTokenExpiresAt() { return downloadTokenExpiresAt; }
    public void setDownloadTokenExpiresAt(OffsetDateTime value) { downloadTokenExpiresAt = value; }
    public long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
