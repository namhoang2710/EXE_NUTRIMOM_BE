package vn.nutrimom.assistant.persistence;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "assistant_messages", schema = "app", uniqueConstraints =
        @UniqueConstraint(columnNames = {"conversation_id", "client_message_id", "role"}))
public class AssistantMessage {
    @Id @Column(length = 36) public String id;
    @Column(name = "conversation_id", length = 36, nullable = false) public String conversationId;
    @Column(name = "client_message_id", length = 36, nullable = false) public String clientMessageId;
    @Column(length = 12, nullable = false) public String role;
    @Column(columnDefinition = "TEXT", nullable = false) public String content;
    @Column(columnDefinition = "TEXT") public String responseJson;
    @Column(nullable = false) public OffsetDateTime createdAt;
}
