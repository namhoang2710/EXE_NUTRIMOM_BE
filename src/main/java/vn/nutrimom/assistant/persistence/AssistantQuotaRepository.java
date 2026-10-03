package vn.nutrimom.assistant.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;

public interface AssistantQuotaRepository extends JpaRepository<AssistantQuota, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from AssistantQuota q where q.id = 'global'")
    Optional<AssistantQuota> lockGlobal();
}
