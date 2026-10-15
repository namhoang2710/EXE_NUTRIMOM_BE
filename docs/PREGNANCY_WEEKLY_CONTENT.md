# Pregnancy weekly content: code fix and publishing status

## Code changes

### Optional dashboard query isolation

`MomDashboardService` assembles its response in a read transaction. Previously its direct
weekly-content repository call joined that transaction. An SQL failure marked the transaction
rollback-only; catching the exception and returning `baby_summary = null` did not prevent
`UnexpectedRollbackException` at the end of the request and HTTP 500.

`PregnancyWeekContentReader.findPublishableWeek` now runs the optional read in a separate
`REQUIRES_NEW`, read-only transaction. An unsuccessful read finishes rollback before the
dashboard catches it. Other dashboard reads retain their existing transaction behavior,
including calendar/subscription blocks and callers with uncommitted fixtures.

The reader selects only publishable content from `app.pregnancy_week_contents` (see
`PregnancyWeekContentPublication`: `REVIEWED` plus reviewer, review time, positive version and
non-blank required text). Missing, unreviewed or incomplete content produces a null block. Weeks outside 0–42 keep their actual gestational
age and produce a null content block; no calculation or pregnancy lifecycle is changed.

The read returns an entity containing basic columns only, without lazy associations. If that
model gains lazy relationships later, map the required values inside the reader transaction.

`REQUIRES_NEW` uses an additional connection while the caller's transaction is suspended.
Account for that when sizing the pool for production concurrency; the fix does not change
global connection limits. The fallback protects a failed optional query, not a database-wide
outage that also prevents loading the authenticated user's profile/pregnancy.

### Baby weight field compatibility

Jackson's global snake-case rule serialized `weightGRange` as `weight_grange`. The expected
field used by the frontend is `weight_g_range`.

The DTO explicitly exposes `weight_g_range` and retains `weight_grange` as a legacy response
field with the same value. Consumers can migrate without losing the old field. Optional
null measurements remain omitted according to the existing JSON inclusion policy.

## API contracts retained

- Authenticated, valid week with reviewed content: HTTP 200.
- Existing unreviewed content: HTTP 409, `CONTENT_NOT_REVIEWED`.
- Content labelled `REVIEWED` but missing reviewer/review time/required text: also HTTP 409
  `CONTENT_NOT_REVIEWED` (no new error code), logged with field names only.
- Missing content: HTTP 404, `RESOURCE_NOT_FOUND`.
- Week -1 or 43: HTTP 422, `VALIDATION_ERROR`.
- Unauthenticated requests remain protected.
- Dashboard summary uses the same week/content fields as the weekly endpoint.
- Review metadata, existing text fields, `sources` string type, maternal changes and care topics
  remain compatible. No gestational-age formula or pregnancy DTO is changed.

## Automated coverage

`PregnancyWeeklyContentIntegrationTest` covers the contracts, reviewed weeks
0/1/20/40/42, metadata, optional measurements, dashboard consistency, missing/unreviewed
fallback, week 43 without clamping, and a real SQL failure through Spring/JPA.

`PregnancyWeeklyContentPostgresFallbackTest` repeats the SQL-failure case on a real, disposable
local PostgreSQL database (enabled only by `NUTRIMOM_PG_TEST_URL` pointing at localhost); see
`docs/pregnancy-weekly-content/verification.md`.

The H2 SQL-failure test uses its own in-memory database, checks the JDBC URL before renaming
the table, and restores it in `finally`. It does not modify any shared/cloud database. Test
content and measurements are clearly synthetic fixtures, not a medically approved dataset.

Before the fix, that request returned HTTP 500. After the fix, the same test expects HTTP 200,
`baby_summary = null` and the existing FREE subscription block.

Run focused regression checks:

```powershell
.\mvnw.cmd "-Dtest=PregnancyWeeklyContentIntegrationTest,PregnancyIntegrationTest,MomDashboardIntegrationTest,MomDashboardCalendarIntegrationTest" verify
```

Run the repository suite from clean build outputs:

```powershell
.\mvnw.cmd clean verify
```

## Data publishing remains pending

The code fix does not turn placeholder rows into `REVIEWED` and does not add a publishing
migration. A 409 for a currently unreviewed row remains expected.

Before publishing, the project content owner must provide the approved weeks and identify
the person/unit responsible for approval. Each row needs the required content fields,
sources/disclaimer, real `reviewed_by`/`reviewed_at`, and a positive content version.
Measurements and the next review date may be absent when no approved values exist.

Then add a new migration after the highest deployed version, test the upgrade on a separate
PostgreSQL database, and verify HTTP responses on the named deployment environment. Preserve
existing migration checksums and prevent overwriting a newer reviewed version. Handoff
package, template, validator and current status: `docs/pregnancy-weekly-content/handoff.md`.

The full content-publishing task is complete only after that dataset/migration step and
positive weekly/dashboard responses on the updated environment, with approval provenance.
