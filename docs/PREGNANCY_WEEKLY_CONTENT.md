# Pregnancy weekly content (deprecated)

Weekly pregnancy articles were dropped on 15/10/2026. The pregnancy feature now only calculates, stores and shows
gestational week, day and estimated due date.

## Current behaviour

- `GET /api/v1/dashboard/mom` does not read `app.pregnancy_week_contents`. `baby_summary` is always `null` and
  marked deprecated; `pregnancy_summary` is unchanged.
- `GET /api/v1/pregnancy-content/weeks/{week}` is kept unchanged for clients released before the change and marked
  deprecated in Swagger: 200 only for complete `REVIEWED` rows (`PregnancyWeekContentPublication`), 409
  `CONTENT_NOT_REVIEWED` otherwise, 404 when missing, 422 outside 0–42.
- The table and migrations V5/V7/V22 stay as they are; applied migrations are never edited.

## Removal plan

Once the web FE without weekly calls is deployed everywhere (and no other client uses it), remove in one PR:
`PregnancyWeekContentController`, `PregnancyWeekContentService`, `PregnancyWeekContentPublication`,
`PregnancyWeekContentEntity`/`Repository`, `PregnancyWeekContentResponse`, `PregnancyBabyContentResponse`,
`BabySummaryResponse` + the `baby_summary` field, and their tests. Dropping the table is optional and needs a new
migration after the highest applied version.

Status, consumers, migration comparison and test evidence: `docs/pregnancy-weekly-content/handoff.md`.
