package vn.nutrimom.consultation.video;

import java.time.Instant;

public interface LiveKitGateway {
    /** Lightweight authenticated provider probe; never exposes room/patient data. */
    default void probe() { throw new IllegalStateException("Provider health probe not implemented"); }
    void ensureRoom(String room);
    String participantToken(String room, String identity, Instant expiresAt);
    String encryptionKey(String room);
    void closeRoom(String room, String userIdentity, String expertIdentity, Instant cutoff);
    record RoomEvent(String type, String room, String identity, Instant createdAt) { }
    RoomEvent verifyWebhook(String rawBody, String authorization);
}
