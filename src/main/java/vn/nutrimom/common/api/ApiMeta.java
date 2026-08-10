package vn.nutrimom.common.api;

import java.time.OffsetDateTime;

public record ApiMeta(String requestId, OffsetDateTime serverTime) {
}
