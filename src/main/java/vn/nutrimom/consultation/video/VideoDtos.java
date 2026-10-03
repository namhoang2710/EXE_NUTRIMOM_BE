package vn.nutrimom.consultation.video;

import java.time.Instant;
import vn.nutrimom.consultation.domain.ConsultationStatus;
import vn.nutrimom.consultation.domain.Specialty;

public final class VideoDtos {
    private VideoDtos() { }
    public record RoomInfo(String requestId, String userName, String expertName, Specialty specialty,
                           String note, boolean expert, ConsultationStatus consultationStatus,
                           String state, boolean canJoin, boolean configured, Instant opensAt,
                           Instant closesAt, Instant serverTime) { }
    public record JoinRoom(String serverUrl, String participantToken, String encryptionKey, Instant closesAt) { }
}
