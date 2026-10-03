package vn.nutrimom.assistant.persistence;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface AssistantConversationRepository extends JpaRepository<AssistantConversation, String> {
    Optional<AssistantConversation> findByIdAndOwnerUserId(String id, String ownerUserId);
    List<AssistantConversation> findTop20ByOwnerUserIdOrderByUpdatedAtDesc(String ownerUserId);
    long countByOwnerUserId(String ownerUserId);
    boolean existsByOwnerUserIdAndProcessingSinceAfter(String ownerUserId, OffsetDateTime since);
    @Modifying
    @Query("delete from AssistantConversation c where c.updatedAt < :before")
    void deleteExpired(@Param("before") OffsetDateTime before);
}
