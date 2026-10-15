package vn.nutrimom.dashboard.dto;

/**
 * Former weekly-article block of the mom dashboard.
 *
 * @deprecated Weekly pregnancy articles were dropped on 15/10/2026. The dashboard always returns
 *     {@code baby_summary = null}; the field is kept only so older clients keep parsing the
 *     response, and will be removed once no client reads it.
 */
@Deprecated
public record BabySummaryResponse(
        int week,
        String title,
        String summary,
        String babyDevelopment,
        String disclaimer) { }
