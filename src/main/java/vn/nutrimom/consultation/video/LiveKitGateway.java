package vn.nutrimom.consultation.video;

import java.time.Instant;

public interface LiveKitGateway {
    void ensureRoom(String room);
    String participantToken(String room, String identity, Instant expiresAt);
    String encryptionKey(String room);
    void closeRoom(String room, String userIdentity, String expertIdentity, Instant cutoff);
    record RoomEvent(String type, String room, String identity, Instant createdAt) { }
    RoomEvent verifyWebhook(String rawBody, String authorization);
}
