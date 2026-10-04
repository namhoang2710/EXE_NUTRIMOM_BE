package vn.nutrimom.assistant.persistence;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "assistant_preferences", schema = "app")
public class AssistantPreferences {
    @Id @Column(name = "user_id", length = 36) public String userId;
    @Column(nullable = false) public boolean cloudConsent;
    @Column(nullable = false) public boolean useProfile = true;
    @Column(nullable = false) public boolean usePregnancy = true;
    @Column(nullable = false) public boolean useMedicalRecords = true;
    @Column(nullable = false) public long contextVersion;
    @Column public LocalDate usageDay;
    @Column(nullable = false) public int aiRequests;
    @Version public long version;
}
