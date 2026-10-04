package vn.nutrimom.assistant.persistence;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "assistant_conversations", schema = "app")
public class AssistantConversation {
    @Id @Column(length = 36) public String id;
    @Column(name = "owner_user_id", length = 36, nullable = false) public String ownerUserId;
    @Column(length = 100, nullable = false) public String title;
    @Column(nullable = false) public long contextVersion;
    @Column(length = 36) public String processingMessageId;
    @Column public OffsetDateTime processingSince;
    @Column(nullable = false) public OffsetDateTime createdAt;
    @Column(nullable = false) public OffsetDateTime updatedAt;
}
