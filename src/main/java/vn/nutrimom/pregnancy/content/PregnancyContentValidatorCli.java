package vn.nutrimom.pregnancy.content;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import vn.nutrimom.pregnancy.content.PregnancyContentDatasetValidator.Mode;
import vn.nutrimom.pregnancy.content.PregnancyContentDatasetValidator.Report;

/**
 * Command line entry for {@link PregnancyContentDatasetValidator}. Read-only: never writes a DB.
 *
 * <p>Exit codes: 0 = publishable, 1 = blocking errors found, 2 = usage or file error.
 * See {@code tools/pregnancy-content/README.md} for how to run it.
 */
public final class PregnancyContentValidatorCli {
    private PregnancyContentValidatorCli() { }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Path dataset = null;
        Path published = null;
        Path reportPath = null;
        Mode mode = Mode.FULL;
        Set<Integer> weeks = new TreeSet<>();
        try {
            for (String arg : args) {
                if (arg.startsWith("--mode=")) {
                    mode = Mode.valueOf(arg.substring(7).toUpperCase());
                } else if (arg.startsWith("--weeks=")) {
                    weeks = parseWeeks(arg.substring(8));
                } else if (arg.startsWith("--published=")) {
                    published = Path.of(arg.substring(12));
                } else if (arg.startsWith("--report=")) {
                    reportPath = Path.of(arg.substring(9));
                } else if (!arg.startsWith("--") && dataset == null) {
                    dataset = Path.of(arg);
                } else {
                    return usage("Unknown argument: " + arg);
                }
            }
            if (dataset == null) {
                return usage("Missing dataset file.");
            }
            Report report = new PregnancyContentDatasetValidator(Clock.systemUTC()).validate(
                    Files.readAllBytes(dataset), mode, weeks,
                    published == null ? null : Files.readAllBytes(published));
            String json = JsonMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .build()
                    .writeValueAsString(report);
            if (reportPath != null) {
                Files.writeString(reportPath, json);
            }
            System.out.println(json);
            System.err.printf("%s: %d valid, %d invalid, %d missing, %d errors, %d warnings%n",
                    report.publishable() ? "PUBLISHABLE" : "BLOCKED", report.validWeeks().size(),
                    report.invalidWeeks().size(), report.missingWeeks().size(),
                    report.errors().size(), report.warnings().size());
            return report.publishable() ? 0 : 1;
        } catch (IllegalArgumentException ex) {
            return usage(ex.getMessage());
        } catch (IOException ex) {
            System.err.println("Cannot read or write file: " + ex.getMessage());
            return 2;
        }
    }

    /** Parses "10-20,22" into {10..20, 22}. */
    static Set<Integer> parseWeeks(String spec) {
        Set<Integer> weeks = new TreeSet<>();
        for (String part : spec.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            String[] bounds = token.split("-", 2);
            int from = Integer.parseInt(bounds[0].trim());
            int to = bounds.length == 2 ? Integer.parseInt(bounds[1].trim()) : from;
            if (from > to) {
                throw new IllegalArgumentException("Invalid week range: " + token);
            }
            for (int week = from; week <= to; week++) {
                weeks.add(week);
            }
        }
        return weeks;
    }

    private static int usage(String problem) {
        System.err.println(problem);
        System.err.println("Usage: <dataset.json> [--mode=full|partial] [--weeks=10-20,22] "
                + "[--published=published.json] [--report=report.json]");
        return 2;
    }
}
