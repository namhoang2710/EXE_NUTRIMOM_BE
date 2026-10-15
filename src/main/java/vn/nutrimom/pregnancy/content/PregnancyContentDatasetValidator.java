package vn.nutrimom.pregnancy.content;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Offline checker for a weekly pregnancy content package before it is imported or published.
 *
 * <p>It never touches a database. It only reports whether the package satisfies the publishing
 * rules (complete text, real sources, real review metadata, consistent ranges and versions).
 * Passing the checker does not prove medical correctness; that remains the reviewer's job.
 */
public class PregnancyContentDatasetValidator {
    public static final int MIN_WEEK = 0;
    public static final int MAX_WEEK = 42;
    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    public static final String PUBLISHABLE_STATUS = "APPROVED";

    static final List<String> TEXT_FIELDS = List.of(
            "title", "summary", "baby_development", "mother_changes",
            "care_tips", "warning_signs", "disclaimer");
    private static final Set<String> KNOWN_WEEK_FIELDS = Set.of(
            "week", "title", "summary", "baby_development", "mother_changes", "care_tips",
            "warning_signs", "sources", "disclaimer", "reviewed_by", "reviewed_at", "next_review_at",
            "content_version", "approval_reference", "baby_length_cm_min", "baby_length_cm_max",
            "baby_weight_g_min", "baby_weight_g_max", "comparison_label");
    /** Helps catch obvious unfinished text; it does not replace a human review. */
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\b(todo|tbd|fixme|lorem ipsum|placeholder)\\b|đang chờ .*thẩm định|chờ thẩm định|chưa có nội dung",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final BigDecimal MAX_LENGTH_CM = new BigDecimal("9999.99");

    public enum Mode { FULL, PARTIAL }

    public record Issue(Integer week, String field, String code, String message) { }

    public record Report(
            String datasetId,
            String sha256,
            OffsetDateTime checkedAt,
            Mode mode,
            List<Integer> targetWeeks,
            List<Integer> validWeeks,
            List<Integer> invalidWeeks,
            List<Integer> missingWeeks,
            List<Issue> errors,
            List<Issue> warnings,
            boolean publishable) { }

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final Clock clock;

    public PregnancyContentDatasetValidator(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param dataset    package to check, UTF-8 JSON bytes
     * @param mode       FULL requires exactly weeks 0–42; PARTIAL requires {@code targetWeeks}
     * @param targetWeeks weeks the PARTIAL package must cover (ignored for FULL)
     * @param published  optional export of what is already published (same JSON format), or null
     */
    public Report validate(byte[] dataset, Mode mode, Set<Integer> targetWeeks, byte[] published) {
        List<Issue> errors = new ArrayList<>();
        List<Issue> warnings = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now(clock);
        Set<Integer> target = mode == Mode.FULL ? range(MIN_WEEK, MAX_WEEK) : new TreeSet<>(targetWeeks);
        if (mode == Mode.PARTIAL && target.isEmpty()) {
            errors.add(new Issue(null, "weeks", "INVALID_DATASET", "PARTIAL mode needs an explicit list of target weeks."));
        }

        JsonNode root = parse(dataset, "dataset", errors);
        String datasetId = null;
        Map<Integer, JsonNode> weeks = new LinkedHashMap<>();
        Set<Integer> invalid = new TreeSet<>();
        if (root != null) {
            datasetId = checkDatasetHeader(root, errors);
            JsonNode items = root.get("weeks");
            if (items == null || !items.isArray()) {
                errors.add(new Issue(null, "weeks", "INVALID_TYPE", "\"weeks\" must be an array."));
            } else {
                for (int i = 0; i < items.size(); i++) {
                    checkWeek(items.get(i), i, target, now, weeks, invalid, errors, warnings);
                }
            }
        }

        if (published != null && root != null) {
            JsonNode publishedRoot = parse(published, "published", errors);
            if (publishedRoot != null) {
                checkVersions(weeks, publishedRoot, invalid, errors);
            }
        }

        Set<Integer> missing = new TreeSet<>(target);
        missing.removeAll(weeks.keySet());
        for (int week : missing) {
            errors.add(new Issue(week, "week", "MISSING_WEEK", "Week is required by the target scope but absent."));
        }
        Set<Integer> valid = new TreeSet<>(weeks.keySet());
        valid.removeAll(invalid);
        return new Report(datasetId, sha256(dataset), now, mode, List.copyOf(target), List.copyOf(valid),
                List.copyOf(invalid), List.copyOf(missing), List.copyOf(errors), List.copyOf(warnings),
                errors.isEmpty());
    }

    private JsonNode parse(byte[] bytes, String what, List<Issue> errors) {
        try {
            return mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (JacksonException ex) {
            errors.add(new Issue(null, what, "INVALID_JSON",
                    "Cannot parse %s JSON at line %d, column %d: %s".formatted(what,
                            ex.getLocation() == null ? -1 : ex.getLocation().getLineNr(),
                            ex.getLocation() == null ? -1 : ex.getLocation().getColumnNr(),
                            ex.getOriginalMessage())));
            return null;
        }
    }

    private String checkDatasetHeader(JsonNode root, List<Issue> errors) {
        String datasetId = text(root, "dataset_id");
        if (datasetId == null || datasetId.isBlank()) {
            errors.add(new Issue(null, "dataset_id", "EMPTY_FIELD", "dataset_id is required."));
        }
        JsonNode schema = root.get("schema_version");
        if (schema == null || !schema.isIntegralNumber() || schema.intValue() != SUPPORTED_SCHEMA_VERSION) {
            errors.add(new Issue(null, "schema_version", "INVALID_DATASET",
                    "schema_version must be the integer " + SUPPORTED_SCHEMA_VERSION + "."));
        }
        String status = text(root, "dataset_status");
        if (!PUBLISHABLE_STATUS.equals(status)) {
            errors.add(new Issue(null, "dataset_status", "UNAPPROVED_CONTENT",
                    "dataset_status is \"%s\"; only %s packages can be published.".formatted(status, PUBLISHABLE_STATUS)));
        }
        return datasetId;
    }

    private void checkWeek(JsonNode item, int index, Set<Integer> target, OffsetDateTime now,
                           Map<Integer, JsonNode> weeks, Set<Integer> invalid,
                           List<Issue> errors, List<Issue> warnings) {
        if (item == null || !item.isObject()) {
            errors.add(new Issue(null, "weeks[" + index + "]", "INVALID_TYPE", "Each week entry must be an object."));
            return;
        }
        JsonNode weekNode = item.get("week");
        if (weekNode == null || !weekNode.isIntegralNumber()) {
            errors.add(new Issue(null, "weeks[" + index + "].week", "INVALID_TYPE",
                    "week must be an integer, got " + (weekNode == null ? "nothing" : weekNode.toString()) + "."));
            return;
        }
        int week = weekNode.intValue();
        int before = errors.size();
        if (week < MIN_WEEK || week > MAX_WEEK) {
            errors.add(new Issue(week, "week", "INVALID_RANGE", "week must be between 0 and 42."));
        } else if (!target.contains(week)) {
            errors.add(new Issue(week, "week", "OUT_OF_SCOPE_WEEK", "week is outside the target scope of this package."));
        }
        if (weeks.containsKey(week)) {
            errors.add(new Issue(week, "week", "DUPLICATE_WEEK", "week appears more than once."));
            invalid.add(week);
            return;
        }
        weeks.put(week, item);

        for (String name : item.propertyNames()) {
            if (!KNOWN_WEEK_FIELDS.contains(name)) {
                warnings.add(new Issue(week, name, "UNKNOWN_FIELD", "Field is not part of the template and will be ignored."));
            }
        }
        for (String field : TEXT_FIELDS) {
            requireText(item, week, field, errors);
        }
        checkMaxLength(item, week, "title", 200, errors);
        checkSources(item.get("sources"), week, errors);
        checkReview(item, week, now, errors, warnings);
        checkMeasurements(item, week, errors);
        if (errors.size() > before) {
            invalid.add(week);
        }
    }

    private void requireText(JsonNode item, int week, String field, List<Issue> errors) {
        JsonNode node = item.get(field);
        if (node == null || node.isNull()) {
            errors.add(new Issue(week, field, "EMPTY_FIELD", field + " is required."));
        } else if (!node.isString()) {
            errors.add(new Issue(week, field, "INVALID_TYPE", field + " must be a string."));
        } else if (node.stringValue().isBlank()) {
            errors.add(new Issue(week, field, "EMPTY_FIELD", field + " must not be blank."));
        } else if (PLACEHOLDER.matcher(node.stringValue()).find()) {
            errors.add(new Issue(week, field, "PLACEHOLDER_TEXT", field + " looks like placeholder text."));
        }
    }

    private void checkMaxLength(JsonNode item, int week, String field, int max, List<Issue> errors) {
        String value = text(item, field);
        if (value != null && value.codePointCount(0, value.length()) > max) {
            errors.add(new Issue(week, field, "TOO_LONG", field + " exceeds " + max + " characters."));
        }
    }

    private void checkSources(JsonNode sources, int week, List<Issue> errors) {
        if (sources == null || !sources.isArray() || sources.isEmpty()) {
            errors.add(new Issue(week, "sources", "INVALID_SOURCE", "sources must be a non-empty array."));
            return;
        }
        for (int i = 0; i < sources.size(); i++) {
            JsonNode source = sources.get(i);
            String field = "sources[" + i + "]";
            if (!source.isObject()) {
                errors.add(new Issue(week, field, "INVALID_SOURCE", "Each source must be an object with name and url."));
                continue;
            }
            String name = text(source, "name");
            if (name == null || name.isBlank()) {
                errors.add(new Issue(week, field + ".name", "INVALID_SOURCE", "Source name is required."));
            }
            String url = text(source, "url");
            String reference = text(source, "reference");
            if ((url == null || url.isBlank()) && (reference == null || reference.isBlank())) {
                errors.add(new Issue(week, field, "INVALID_SOURCE", "Source needs a url or an agreed internal reference."));
            } else if (url != null && !url.isBlank() && !isRealUrl(url)) {
                errors.add(new Issue(week, field + ".url", "INVALID_SOURCE", "url must be an absolute http(s) link to a real host."));
            }
        }
    }

    private boolean isRealUrl(String url) {
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost();
            if (host == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                return false;
            }
            host = host.toLowerCase(Locale.ROOT);
            return !(host.equals("localhost") || host.endsWith(".invalid") || host.endsWith(".test")
                    || host.endsWith(".example") || host.matches("(.*\\.)?example\\.(com|org|net)"));
        } catch (Exception ex) {
            return false;
        }
    }

    private void checkReview(JsonNode item, int week, OffsetDateTime now, List<Issue> errors, List<Issue> warnings) {
        String reviewer = text(item, "reviewed_by");
        if (reviewer == null || reviewer.isBlank()) {
            errors.add(new Issue(week, "reviewed_by", "UNAPPROVED_CONTENT", "reviewed_by is required before publishing."));
        } else if (reviewer.length() > 255) {
            errors.add(new Issue(week, "reviewed_by", "TOO_LONG", "reviewed_by exceeds 255 characters."));
        }
        String reference = text(item, "approval_reference");
        if (reference == null || reference.isBlank()) {
            errors.add(new Issue(week, "approval_reference", "UNAPPROVED_CONTENT",
                    "approval_reference must point to the approval of this content version."));
        }
        OffsetDateTime reviewedAt = timestamp(item, week, "reviewed_at", errors);
        if (reviewedAt == null && !hasValue(item, "reviewed_at")) {
            errors.add(new Issue(week, "reviewed_at", "UNAPPROVED_CONTENT", "reviewed_at is required before publishing."));
        } else if (reviewedAt != null && reviewedAt.isAfter(now)) {
            errors.add(new Issue(week, "reviewed_at", "INVALID_RANGE", "reviewed_at is in the future."));
        }
        OffsetDateTime nextReview = timestamp(item, week, "next_review_at", errors);
        if (nextReview != null && reviewedAt != null && !nextReview.isAfter(reviewedAt)) {
            errors.add(new Issue(week, "next_review_at", "INVALID_RANGE", "next_review_at must be after reviewed_at."));
        } else if (nextReview != null && nextReview.isBefore(now)) {
            warnings.add(new Issue(week, "next_review_at", "REVIEW_OVERDUE", "Scheduled re-review date has passed."));
        }
        JsonNode version = item.get("content_version");
        if (version == null || !version.isIntegralNumber() || version.intValue() < 1) {
            errors.add(new Issue(week, "content_version", "INVALID_TYPE", "content_version must be a positive integer."));
        }
    }

    private OffsetDateTime timestamp(JsonNode item, int week, String field, List<Issue> errors) {
        if (!hasValue(item, field)) {
            return null;
        }
        JsonNode node = item.get(field);
        if (!node.isString()) {
            errors.add(new Issue(week, field, "INVALID_TYPE", field + " must be an ISO-8601 string with a time zone."));
            return null;
        }
        try {
            return OffsetDateTime.parse(node.stringValue());
        } catch (DateTimeParseException ex) {
            errors.add(new Issue(week, field, "INVALID_TYPE", field + " must be ISO-8601 with an offset, e.g. 2026-10-01T09:00:00+07:00."));
            return null;
        }
    }

    private void checkMeasurements(JsonNode item, int week, List<Issue> errors) {
        checkPair(item, week, "baby_length_cm_min", "baby_length_cm_max", false, errors);
        checkPair(item, week, "baby_weight_g_min", "baby_weight_g_max", true, errors);
        if (hasValue(item, "comparison_label")) {
            JsonNode label = item.get("comparison_label");
            if (!label.isString() || label.stringValue().isBlank()) {
                errors.add(new Issue(week, "comparison_label", "INVALID_TYPE", "comparison_label must be null or non-blank text."));
            } else {
                checkMaxLength(item, week, "comparison_label", 200, errors);
            }
        }
    }

    private void checkPair(JsonNode item, int week, String minField, String maxField, boolean integer, List<Issue> errors) {
        boolean hasMin = hasValue(item, minField);
        boolean hasMax = hasValue(item, maxField);
        if (hasMin != hasMax) {
            errors.add(new Issue(week, hasMin ? maxField : minField, "INVALID_RANGE",
                    "%s and %s must both be set or both be null.".formatted(minField, maxField)));
            return;
        }
        if (!hasMin) {
            return;
        }
        BigDecimal min = number(item, week, minField, integer, errors);
        BigDecimal max = number(item, week, maxField, integer, errors);
        if (min != null && max != null && min.compareTo(max) > 0) {
            errors.add(new Issue(week, minField, "INVALID_RANGE", minField + " is greater than " + maxField + "."));
        }
    }

    private BigDecimal number(JsonNode item, int week, String field, boolean integer, List<Issue> errors) {
        JsonNode node = item.get(field);
        if (!node.isNumber() || (integer && !node.isIntegralNumber())) {
            errors.add(new Issue(week, field, "INVALID_TYPE", field + " must be " + (integer ? "an integer." : "a number.")));
            return null;
        }
        BigDecimal value = node.decimalValue();
        if (value.signum() < 0) {
            errors.add(new Issue(week, field, "INVALID_RANGE", field + " must not be negative."));
            return null;
        }
        if (!integer && (value.compareTo(MAX_LENGTH_CM) > 0 || value.scale() > 2)) {
            errors.add(new Issue(week, field, "INVALID_RANGE", field + " must fit DECIMAL(6,2)."));
            return null;
        }
        return value;
    }

    /** Blocks lowering a version, or reusing a published version number for different content. */
    private void checkVersions(Map<Integer, JsonNode> weeks, JsonNode publishedRoot, Set<Integer> invalid, List<Issue> errors) {
        JsonNode items = publishedRoot.get("weeks");
        if (items == null || !items.isArray()) {
            errors.add(new Issue(null, "published.weeks", "INVALID_TYPE", "Published snapshot must have a \"weeks\" array."));
            return;
        }
        Map<Integer, JsonNode> published = new HashMap<>();
        for (JsonNode item : items) {
            if (item.has("week") && item.get("week").isIntegralNumber()) {
                published.put(item.get("week").intValue(), item);
            }
        }
        weeks.forEach((week, candidate) -> {
            JsonNode current = published.get(week);
            JsonNode candidateVersion = candidate.get("content_version");
            if (current == null || candidateVersion == null || !candidateVersion.isIntegralNumber()
                    || candidateVersion.intValue() < 1) {
                return; // an invalid version is already reported by checkReview
            }
            int newVersion = candidateVersion.intValue();
            int oldVersion = current.path("content_version").asInt(0);
            if (newVersion < oldVersion) {
                errors.add(new Issue(week, "content_version", "VERSION_CONFLICT",
                        "Published version %d is newer than package version %d.".formatted(oldVersion, newVersion)));
                invalid.add(week);
            } else if (newVersion == oldVersion && !contentFingerprint(candidate).equals(contentFingerprint(current))) {
                errors.add(new Issue(week, "content_version", "VERSION_CONFLICT",
                        "Content changed but content_version %d was not increased.".formatted(newVersion)));
                invalid.add(week);
            }
        });
    }

    static String contentFingerprint(JsonNode item) {
        StringBuilder text = new StringBuilder();
        for (String field : TEXT_FIELDS) {
            text.append(field).append('=').append(item.path(field).asString("")).append('\u0000');
        }
        text.append("sources=").append(item.path("sources").toString()).append('\u0000');
        for (String field : List.of("baby_length_cm_min", "baby_length_cm_max", "baby_weight_g_min",
                "baby_weight_g_max", "comparison_label")) {
            text.append(field).append('=').append(item.path(field).toString()).append('\u0000');
        }
        return sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static boolean hasValue(JsonNode item, String field) {
        JsonNode node = item.get(field);
        return node != null && !node.isNull();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    private static Set<Integer> range(int from, int to) {
        Set<Integer> weeks = new TreeSet<>();
        for (int week = from; week <= to; week++) {
            weeks.add(week);
        }
        return weeks;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
