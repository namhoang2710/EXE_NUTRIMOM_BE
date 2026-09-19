package vn.nutrimom.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "user_preferences", schema = "app")
public class UserPreferenceEntity {
    @Id
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "language", nullable = false, length = 10)
    private String language = "vi";

    @Column(name = "locale", nullable = false, length = 20)
    private String locale = "vi-VN";

    @Column(name = "timezone", nullable = false, length = 50)
    private String timezone = "Asia/Ho_Chi_Minh";

    @Column(name = "theme", nullable = false, length = 20)
    private String theme = "SYSTEM";

    @Column(name = "weight_unit", nullable = false, length = 20)
    private String weightUnit = "KG";

    @Column(name = "length_unit", nullable = false, length = 20)
    private String lengthUnit = "CM";

    @Column(name = "glucose_unit", nullable = false, length = 20)
    private String glucoseUnit = "MMOL_L";

    @Column(name = "backup_enabled", nullable = false)
    private boolean backupEnabled;

    @Column(name = "notification_enabled", nullable = false)
    private boolean notificationEnabled = true;

    @Column(name = "push_enabled", nullable = false)
    private boolean pushEnabled = true;

    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled;

    @Column(name = "sms_enabled", nullable = false)
    private boolean smsEnabled;

    @Column(name = "preferred_reminder_time")
    private LocalTime preferredReminderTime;

    @Column(name = "quiet_hours_start")
    private LocalTime quietHoursStart;

    @Column(name = "quiet_hours_end")
    private LocalTime quietHoursEnd;

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
    public void setUserId(String userId) { this.userId = userId; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getLocale() { return locale; }
    public void setLocale(String value) { locale = value; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public String getTheme() { return theme; }
    public void setTheme(String value) { theme = value; }
    public String getWeightUnit() { return weightUnit; }
    public void setWeightUnit(String value) { weightUnit = value; }
    public String getLengthUnit() { return lengthUnit; }
    public void setLengthUnit(String value) { lengthUnit = value; }
    public String getGlucoseUnit() { return glucoseUnit; }
    public void setGlucoseUnit(String value) { glucoseUnit = value; }
    public boolean isBackupEnabled() { return backupEnabled; }
    public void setBackupEnabled(boolean value) { backupEnabled = value; }
    public boolean isNotificationEnabled() { return notificationEnabled; }
    public void setNotificationEnabled(boolean value) { notificationEnabled = value; }
    public boolean isPushEnabled() { return pushEnabled; }
    public void setPushEnabled(boolean value) { pushEnabled = value; }
    public boolean isEmailEnabled() { return emailEnabled; }
    public void setEmailEnabled(boolean value) { emailEnabled = value; }
    public boolean isSmsEnabled() { return smsEnabled; }
    public void setSmsEnabled(boolean value) { smsEnabled = value; }
    public LocalTime getPreferredReminderTime() { return preferredReminderTime; }
    public void setPreferredReminderTime(LocalTime value) { preferredReminderTime = value; }
    public LocalTime getQuietHoursStart() { return quietHoursStart; }
    public void setQuietHoursStart(LocalTime value) { quietHoursStart = value; }
    public LocalTime getQuietHoursEnd() { return quietHoursEnd; }
    public void setQuietHoursEnd(LocalTime value) { quietHoursEnd = value; }
    public long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
