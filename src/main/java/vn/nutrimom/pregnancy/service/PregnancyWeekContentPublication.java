package vn.nutrimom.pregnancy.service;

import java.util.ArrayList;
import java.util.List;
import vn.nutrimom.pregnancy.domain.PregnancyWeekContentEntity;

/**
 * Decides whether a weekly content row may be shown to users.
 *
 * <p>A {@code REVIEWED} label alone is not enough: the row must also carry the reviewer,
 * review time, a positive version and non-blank text in every required field. Rows that fail
 * are treated like unreviewed content so placeholders or half-imported data never reach users.
 */
public final class PregnancyWeekContentPublication {
    public static final String REVIEWED = "REVIEWED";

    private PregnancyWeekContentPublication() { }

    public static boolean isReviewed(PregnancyWeekContentEntity content) {
        return REVIEWED.equalsIgnoreCase(content.getReviewStatus());
    }

    public static boolean isPublishable(PregnancyWeekContentEntity content) {
        return isReviewed(content) && problems(content).isEmpty();
    }

    /** Names of the fields that block publishing; empty when the row is publishable. */
    public static List<String> problems(PregnancyWeekContentEntity content) {
        List<String> problems = new ArrayList<>();
        requireText(problems, "title", content.getTitle());
        requireText(problems, "summary", content.getSummary());
        requireText(problems, "baby_development", content.getBabyDevelopment());
        requireText(problems, "mother_changes", content.getMotherChanges());
        requireText(problems, "care_tips", content.getCareTips());
        requireText(problems, "warning_signs", content.getWarningSigns());
        requireText(problems, "sources", content.getSources());
        requireText(problems, "disclaimer", content.getDisclaimer());
        requireText(problems, "reviewed_by", content.getReviewedBy());
        if (content.getReviewedAt() == null) {
            problems.add("reviewed_at");
        }
        if (content.getContentVersion() <= 0) {
            problems.add("content_version");
        }
        return problems;
    }

    private static void requireText(List<String> problems, String field, String value) {
        if (value == null || value.isBlank()) {
            problems.add(field);
        }
    }
}
