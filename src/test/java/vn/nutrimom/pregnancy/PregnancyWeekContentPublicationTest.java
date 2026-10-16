package vn.nutrimom.pregnancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import vn.nutrimom.pregnancy.domain.PregnancyWeekContentEntity;
import vn.nutrimom.pregnancy.service.PregnancyWeekContentPublication;

class PregnancyWeekContentPublicationTest {

    @Test
    void completeReviewedRowIsPublishable() {
        assertThat(PregnancyWeekContentPublication.isPublishable(complete())).isTrue();
        assertThat(PregnancyWeekContentPublication.problems(complete())).isEmpty();
    }

    @Test
    void unreviewedRowIsNeverPublishableEvenWhenComplete() {
        PregnancyWeekContentEntity content = complete();
        content.setReviewStatus("UNREVIEWED");
        assertThat(PregnancyWeekContentPublication.isPublishable(content)).isFalse();
    }

    @Test
    void listsEveryBlockingField() {
        PregnancyWeekContentEntity content = complete();
        content.setTitle(" ");
        content.setWarningSigns(null);
        content.setReviewedBy("");
        content.setReviewedAt(null);
        content.setContentVersion(0);

        assertThat(PregnancyWeekContentPublication.isPublishable(content)).isFalse();
        assertThat(PregnancyWeekContentPublication.problems(content))
                .containsExactly("title", "warning_signs", "reviewed_by", "reviewed_at", "content_version");
    }

    private PregnancyWeekContentEntity complete() {
        PregnancyWeekContentEntity content = new PregnancyWeekContentEntity();
        content.setWeek(20);
        content.setTitle("t");
        content.setSummary("s");
        content.setBabyDevelopment("b");
        content.setMotherChanges("m");
        content.setCareTips("c");
        content.setWarningSigns("w");
        content.setSources("[]");
        content.setDisclaimer("d");
        content.setReviewStatus("REVIEWED");
        content.setReviewedBy("Reviewer");
        content.setReviewedAt(OffsetDateTime.parse("2026-09-20T08:00:00Z"));
        content.setContentVersion(1);
        return content;
    }
}
