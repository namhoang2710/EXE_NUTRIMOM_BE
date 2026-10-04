package vn.nutrimom.assistant.persistence;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "assistant_quota", schema = "app")
public class AssistantQuota {
    @Id @Column(length = 20) public String id = "global";
    @Column public LocalDate usageDay;
    @Column(nullable = false) public int requests;
    @Column(nullable = false) public long reservedTokens;
}
