package vn.nutrimom.consultation.video;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class VideoSessionTest {
    @Test void reorderedWebhooksAndReconnectsKeepEarliestVerifiedJoinForEachParticipant() {
        var start=Instant.parse("2026-10-15T02:00:00Z");
        var session=new VideoSession("request","room",start.minusSeconds(300),start.plusSeconds(3600));
        session.markJoined(true,start.plusSeconds(600));session.markJoined(true,start);
        session.markJoined(true,start.plusSeconds(1200));session.markJoined(true,start);
        session.markJoined(false,start.plusSeconds(10));session.markJoined(false,start.minusSeconds(10));
        session.markJoined(false,start.plusSeconds(20));
        assertEquals(start,session.getExpertJoinedAt());assertEquals(start.minusSeconds(10),session.getUserJoinedAt());
    }
}
