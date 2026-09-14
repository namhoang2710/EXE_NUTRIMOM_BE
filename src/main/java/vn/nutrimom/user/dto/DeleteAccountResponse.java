package vn.nutrimom.user.dto;

import java.time.OffsetDateTime;

public record DeleteAccountResponse(
        String deletionRequestId,
        OffsetDateTime scheduledFor,
        OffsetDateTime disabledAt,
        String status) { }
