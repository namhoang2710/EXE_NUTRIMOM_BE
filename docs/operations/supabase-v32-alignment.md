# Supabase V32 reconciliation

Prepared on 2026-10-12 against `main` commit `bdb116b`. This document and its SQL
script describe a proposed administrative operation; adding them to Git does not
execute anything against Supabase.

## Evidence

- Supabase has successfully applied V1 through V41. The current main branch also
  contains V41 (`private_file_storage_provider`).
- Flyway reports one checksum mismatch: V32 (`assistant`), applied `598666640`,
  resolved `570677793`.
- The repository V32 checksum was calculated with the project's actual Flyway
  `ChecksumCalculator`, rather than an approximate hashing algorithm.
- Removing exactly `CREATE INDEX ix_assistant_conversations_expiry ON
  app.assistant_conversations(updated_at);` from a comparison copy produces
  `598666640`. Read-only Flyway validation against that comparison directory
  succeeded (42 validated entries, including the schema-history baseline). The
  active repository V32 is unchanged.
- Cloud metadata confirms that this index is absent. Column definitions,
  constraints, and the other V32 indexes match, accounting for V33's changes to
  assistant context defaults. No application records were read for this check.

A checksum is not proof of SQL identity by itself. The matching candidate and
the matching observed schema together establish the concrete discrepancy being
reconciled here. The original deployed SQL artifact was not supplied.

## Recommended operation

Keep the V32 file on GitHub unchanged. Restore the missing index on Supabase, then
align only the affected V32 history checksum with the repository. This preserves
the migration file used by environments that already applied GitHub's V32.

The adjacent `supabase-v32-alignment.sql` performs these two changes in one
PostgreSQL transaction. It requires the reviewed V32 checksum and V41 history,
rejects failed or newer migrations, verifies the resulting index definition, and
updates only the V32 checksum. It does not run a broad Flyway `repair`, replay V32,
change application records, or disable validation. On guard failure or timeout,
the operator must roll back the failed transaction before any retry.

## Execution prerequisites

1. Review the operation with the owner of this shared Supabase database. Pause
   deployment/migration jobs during the operation and save the existing V32
   history entry in a private audit file.
2. Run fresh read-only Flyway validation against the current main resources.
   Abort if there is any error besides the expected V32 checksum mismatch, or if
   the checksum pair/main migration set has changed.
3. Recheck assistant table metadata and the missing index. This reconciliation
   does not substitute for a full database drift audit.
4. Execute the script as a single transaction with stop-on-error behavior. Keep
   its five-second lock timeout and sixty-second statement timeout. Index creation
   can briefly block writes to `assistant_conversations`; schedule this accordingly.
5. Run ordinary Flyway validation again. It must succeed against the unmodified
   repository V32 and all V1–V41 resources. Then start the backend and perform a
   read-only health/API check with demo/admin initialization and OTP debug disabled.

If the database state differs, stop and investigate it. Do not force checksums,
disable `validate-on-migrate`, use `clean`, or rewrite an already executed SQL file.

## Limits

The history guard block passed when executed against Supabase in a transaction
with `transaction_read_only=on`. The index creation and checksum update block
have not been executed. The local Docker PostgreSQL engine was unavailable, so
the complete operation has not been tested in a disposable PostgreSQL instance.
The checksum comparison and cloud metadata checks were read-only. Backend
authentication tests run with H2/test fixtures; they do not establish live SMTP
delivery or private R2 access.
