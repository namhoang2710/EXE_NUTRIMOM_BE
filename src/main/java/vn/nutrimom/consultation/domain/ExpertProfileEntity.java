package vn.nutrimom.consultation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Hồ sơ nghề nghiệp của chuyên gia. PK = user_id, ánh xạ 1-1 với {@code app.users}
 * (tài khoản mang role EXPERT do admin tạo). Không có field cơ sở bệnh viện theo yêu cầu.
 */
@Entity
@Table(name = "expert_profiles", schema = "app")
public class ExpertProfileEntity {
    @Id
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "specialty", nullable = false, length = 20)
    private Specialty specialty;

    @Column(name = "title", length = 100)
    private String title;

    @Column(name = "workplace", length = 255)
    private String workplace;

    @Column(name = "years_of_experience", nullable = false)
    private int yearsOfExperience;

    @Column(name = "bio", columnDefinition = "nvarchar(max)")
    private String bio;

    @Column(name = "avatar_key", length = 200)
    private String avatarKey;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ExpertStatus status = ExpertStatus.ACTIVE;

    @Column(name = "average_rating", nullable = false, precision = 3, scale = 2)
    private BigDecimal averageRating = BigDecimal.ZERO;

    @Column(name = "rating_count", nullable = false)
    private int ratingCount;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void beforeInsert() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getUserId() { return userId; }
    public void setUserId(String value) { userId = value; }
    public String getFullName() { return fullName; }
    public void setFullName(String value) { fullName = value; }
    public Specialty getSpecialty() { return specialty; }
    public void setSpecialty(Specialty value) { specialty = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public String getWorkplace() { return workplace; }
    public void setWorkplace(String value) { workplace = value; }
    public int getYearsOfExperience() { return yearsOfExperience; }
    public void setYearsOfExperience(int value) { yearsOfExperience = value; }
    public String getBio() { return bio; }
    public void setBio(String value) { bio = value; }
    public String getAvatarKey() { return avatarKey; }
    public void setAvatarKey(String value) { avatarKey = value; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String value) { avatarUrl = value; }
    public ExpertStatus getStatus() { return status; }
    public void setStatus(ExpertStatus value) { status = value; }
    public BigDecimal getAverageRating() { return averageRating; }
    public void setAverageRating(BigDecimal value) { averageRating = value; }
    public int getRatingCount() { return ratingCount; }
    public void setRatingCount(int value) { ratingCount = value; }
    public long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
