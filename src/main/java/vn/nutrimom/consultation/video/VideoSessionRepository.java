package vn.nutrimom.consultation.video;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VideoSessionRepository extends JpaRepository<VideoSession, String> {
    Optional<VideoSession> findByRoomName(String roomName);
    @Query("select s.requestId from VideoSession s where s.roomName = :room")
    Optional<String> findRequestIdByRoomName(@Param("room") String roomName);
    @Query("select s.requestId from VideoSession s where s.cleanupAt is null")
    List<String> findUncleanRequestIds();
}
