package vn.nutrimom.assistant.persistence;

import java.util.*;
import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface AssistantMessageRepository extends JpaRepository<AssistantMessage, String> {
    List<AssistantMessage> findByConversationIdOrderByCreatedAtAscIdAsc(String conversationId);
    Optional<AssistantMessage> findByConversationIdAndClientMessageIdAndRole(String conversationId, String clientMessageId, String role);
    long countByConversationId(String conversationId);
    void deleteByConversationId(String conversationId);
    @Modifying
    @Query("delete from AssistantMessage m where m.conversationId in (select c.id from AssistantConversation c where c.updatedAt < :before)")
    void deleteExpired(@Param("before") OffsetDateTime before);
}
