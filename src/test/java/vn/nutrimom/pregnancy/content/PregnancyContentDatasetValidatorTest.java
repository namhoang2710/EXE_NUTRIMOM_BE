package vn.nutrimom.pregnancy.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vn.nutrimom.pregnancy.content.PregnancyContentDatasetValidator.Issue;
import vn.nutrimom.pregnancy.content.PregnancyContentDatasetValidator.Mode;
import vn.nutrimom.pregnancy.content.PregnancyContentDatasetValidator.Report;

/** Synthetic packages only; none of this text is medical content. */
class PregnancyContentDatasetValidatorTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final PregnancyContentDatasetValidator validator = new PregnancyContentDatasetValidator(CLOCK);

    @Test
    void completeApprovedFullPackageIsPublishable() {
        Report report = full(dataset -> { });

        assertThat(report.errors()).isEmpty();
        assertThat(report.publishable()).isTrue();
        assertThat(report.validWeeks()).hasSize(43).startsWith(0).endsWith(42);
        assertThat(report.sha256()).hasSize(64);
        assertThat(report.datasetId()).isEqualTo("synthetic-test");
    }

    @Test
    void draftPackageIsBlocked() {
        Report report = full(dataset -> dataset.put("dataset_status", "DRAFT"));
        assertThat(codes(report)).contains("UNAPPROVED_CONTENT");
        assertThat(report.publishable()).isFalse();
    }

    @Test
    void missingAndDuplicateWeeksAreReported() {
        Report report = full(dataset -> {
            ArrayNode weeks = (ArrayNode) dataset.get("weeks");
            weeks.remove(5);
            weeks.add(week(7));
        });
        assertThat(report.missingWeeks()).containsExactly(5);
        assertThat(report.errors()).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("DUPLICATE_WEEK");
            assertThat(issue.week()).isEqualTo(7);
        });
    }

    @Test
    void fractionalWeekIsNotRounded() {
        Report report = full(dataset -> ((ObjectNode) dataset.get("weeks").get(20)).put("week", 20.5));
        assertThat(codes(report)).contains("INVALID_TYPE");
        assertThat(report.missingWeeks()).containsExactly(20);
    }

    @Test
    void missingReviewMetadataBlocksTheWeek() {
        Report report = full(dataset -> {
            ObjectNode week = (ObjectNode) dataset.get("weeks").get(10);
            week.putNull("reviewed_by");
            week.putNull("reviewed_at");
            week.remove("approval_reference");
        });
        assertThat(report.invalidWeeks()).containsExactly(10);
        assertThat(report.errors()).extracting(Issue::field)
                .contains("reviewed_by", "reviewed_at", "approval_reference");
    }

    @Test
    void placeholderTextAndExampleSourcesAreRejected() {
        Report report = full(dataset -> {
            ObjectNode week = (ObjectNode) dataset.get("weeks").get(20);
            week.put("summary", "Nội dung tổng quan cho tuần 20 đang chờ đội ngũ chuyên môn thẩm định.");
            ((ObjectNode) week.get("sources").get(0)).put("url", "https://example.org/path-to-source");
        });
        assertThat(codes(report)).contains("PLACEHOLDER_TEXT", "INVALID_SOURCE");
        assertThat(report.invalidWeeks()).containsExactly(20);
    }

    @Test
    void measurementPairsMustBeCompleteAndOrdered() {
        Report report = full(dataset -> {
            ObjectNode week20 = (ObjectNode) dataset.get("weeks").get(20);
            week20.put("baby_weight_g_min", 300);
            week20.put("baby_weight_g_max", 200);
            ObjectNode week21 = (ObjectNode) dataset.get("weeks").get(21);
            week21.put("baby_length_cm_min", 25.5);
        });
        assertThat(report.invalidWeeks()).containsExactly(20, 21);
        assertThat(codes(report)).containsOnly("INVALID_RANGE");
    }

    @Test
    void unicodeQuotesAndNewlinesArePreservedAsValidText() {
        Report report = full(dataset -> ((ObjectNode) dataset.get("weeks").get(3))
                .put("care_tips", "Mẹ nên \"nghỉ ngơi\" đủ;\nuống nước – O'Brien ✓"));
        assertThat(report.publishable()).isTrue();
    }

    @Test
    void sameVersionWithChangedContentIsAVersionConflict() throws Exception {
        ObjectNode published = dataset();
        ObjectNode candidate = dataset();
        ((ObjectNode) candidate.get("weeks").get(12)).put("summary", "Changed synthetic summary");
        ((ObjectNode) candidate.get("weeks").get(13)).put("content_version", 0);
        ((ObjectNode) published.get("weeks").get(14)).put("content_version", 5);

        Report report = validator.validate(bytes(candidate), Mode.FULL, Set.of(), bytes(published));

        assertThat(report.errors()).filteredOn(issue -> issue.code().equals("VERSION_CONFLICT"))
                .extracting(Issue::week).containsExactlyInAnyOrder(12, 14);
        assertThat(report.publishable()).isFalse();
    }

    @Test
    void unchangedRepublishWithSameVersionIsAllowed() throws Exception {
        Report report = validator.validate(bytes(dataset()), Mode.FULL, Set.of(), bytes(dataset()));
        assertThat(report.publishable()).isTrue();
    }

    @Test
    void partialModeChecksOnlyTheAgreedWeeksAndRejectsOthers() throws Exception {
        ObjectNode dataset = dataset();
        ArrayNode weeks = mapper.createArrayNode();
        for (int week : new int[] {10, 11, 30}) {
            weeks.add(week(week));
        }
        dataset.set("weeks", weeks);

        Report report = validator.validate(bytes(dataset), Mode.PARTIAL, Set.of(10, 11, 12), null);

        assertThat(report.targetWeeks()).containsExactly(10, 11, 12);
        assertThat(report.missingWeeks()).containsExactly(12);
        assertThat(codes(report)).contains("OUT_OF_SCOPE_WEEK", "MISSING_WEEK");
        assertThat(report.publishable()).isFalse();
    }

    @Test
    void partialModeWithoutTargetWeeksIsRejected() throws Exception {
        Report report = validator.validate(bytes(dataset()), Mode.PARTIAL, Set.of(), null);
        assertThat(report.publishable()).isFalse();
        assertThat(codes(report)).contains("INVALID_DATASET");
    }

    @Test
    void brokenJsonReportsLocationInsteadOfThrowing() {
        Report report = validator.validate("{\"weeks\": [".getBytes(StandardCharsets.UTF_8), Mode.FULL, Set.of(), null);
        assertThat(report.errors()).first().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("INVALID_JSON");
            assertThat(issue.message()).contains("line");
        });
    }

    @Test
    void cliExitCodesFollowTheResult() throws Exception {
        var dir = java.nio.file.Files.createTempDirectory("pregnancy-content");
        var good = java.nio.file.Files.write(dir.resolve("good.json"), bytes(dataset()));
        ObjectNode draft = dataset();
        draft.put("dataset_status", "DRAFT");
        var bad = java.nio.file.Files.write(dir.resolve("draft.json"), bytes(draft));
        var report = dir.resolve("report.json");

        assertThat(PregnancyContentValidatorCli.run(new String[] {good.toString(), "--report=" + report})).isZero();
        assertThat(java.nio.file.Files.readString(report)).contains("\"publishable\" : true", "\"valid_weeks\"");
        assertThat(PregnancyContentValidatorCli.run(new String[] {bad.toString()})).isEqualTo(1);
        assertThat(PregnancyContentValidatorCli.run(new String[] {})).isEqualTo(2);
        assertThat(PregnancyContentValidatorCli.parseWeeks("10-12, 20")).containsExactly(10, 11, 12, 20);
    }

    private Report full(Consumer<ObjectNode> change) {
        ObjectNode dataset = dataset();
        change.accept(dataset);
        return validator.validate(bytes(dataset), Mode.FULL, Set.of(), null);
    }

    private ObjectNode dataset() {
        ObjectNode dataset = mapper.createObjectNode();
        dataset.put("dataset_id", "synthetic-test");
        dataset.put("dataset_status", "APPROVED");
        dataset.put("schema_version", 1);
        ArrayNode weeks = dataset.putArray("weeks");
        for (int week = 0; week <= 42; week++) {
            weeks.add(week(week));
        }
        return dataset;
    }

    private ObjectNode week(int week) {
        ObjectNode item = mapper.createObjectNode();
        item.put("week", week);
        item.put("title", "Synthetic week " + week);
        for (String field : PregnancyContentDatasetValidator.TEXT_FIELDS) {
            if (!field.equals("title")) {
                item.put(field, "Synthetic " + field + " for week " + week);
            }
        }
        ObjectNode source = item.putArray("sources").addObject();
        source.put("name", "Synthetic source");
        source.put("url", "https://www.who.int/synthetic-test-reference");
        item.put("reviewed_by", "Synthetic reviewer");
        item.put("reviewed_at", "2026-10-01T09:00:00+07:00");
        item.putNull("next_review_at");
        item.put("content_version", 1);
        item.put("approval_reference", "TEST-APPROVAL-1");
        return item;
    }

    private byte[] bytes(ObjectNode node) {
        return mapper.writeValueAsBytes(node);
    }

    private static java.util.List<String> codes(Report report) {
        return report.errors().stream().map(Issue::code).toList();
    }
}
