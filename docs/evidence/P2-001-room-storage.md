# P2-001 Room track storage evidence

Date: 2026-07-29

Result: PASS for the P2-001 acceptance criteria. TrailVeil now stores canonical recording sessions, segments, and points in an explicit Room v2 schema, prevents contradictory or duplicate active state at the SQLite boundary, preserves the tested v1 data during migration, and excludes app-private data from Android backup and device transfer.

## Implemented guarantees

- `recording_sessions` stores stable string statuses and uses nullable `active_slot` plus a unique index.
- SQLite INSERT and UPDATE triggers require `ACTIVE` exactly when `active_slot = 1` and `ended_at IS NULL`; terminal rows require a null slot and non-null end time.
- `track_segments` cascades from its session and has unique `(session_id, sequence)` plus unique `(id, session_id)`.
- `track_points` uses composite `(segment_id, session_id)` foreign keys, preventing points from referencing a segment owned by another session. Point sequences are unique within a segment, and `(session_id, timestamp)` supports ordered track queries.
- DAO start, accepted-point summary, rejected-point summary, and close operations use Room transactions with abort-on-conflict inserts. No destructive migration fallback or `REPLACE` conflict strategy is used.
- `MIGRATION_1_2` preserves the hierarchy, keeps the newest legacy ACTIVE row active, honestly marks older duplicates `INTERRUPTED`, adds the singleton slot/index, and creates the invariant triggers.
- Room exports and versions `app/schemas/.../1.json` and `2.json` are version controlled.
- The application declares `allowBackup=false` and references both legacy and Android 12+ extraction rules. Legacy backup, cloud backup, and device transfer each exclude all nine Android private-storage domains and contain no includes.
- The initial product floor is Android 14 / API 34. Room 2.8.4's migration runtime is paired with an explicit kotlinx serialization JSON 1.8.1 pin.

## Automated build and static validation

The isolated offline gate ran with Gradle 9.5.0, JDK 17, and at most two workers:

```text
:app:assembleDebug
:app:assembleDebugAndroidTest
:app:testDebugUnitTest
:app:lintDebug
:app:assembleInternal
:app:lintInternal
:app:assembleMapLibreInternal
:app:lintMapLibreInternal
--offline --no-daemon --max-workers=2
```

Result: `BUILD SUCCESSFUL` in 1m 10s; 170 tasks, 92 executed and 78 up-to-date. The eight JVM suites contain 34 tests with zero failures, errors, or skips. Debug, Internal, and MapLibre Internal lint reports contain zero issues.

Additional assertions passed:

- all three current variant dependency models resolve `kotlinx-serialization-core/json-jvm:1.8.1`, with 1.7.3 absent;
- all three merged application manifests contain `allowBackup=false`, `@xml/backup_rules`, and `@xml/data_extraction_rules`;
- legacy, cloud, and device-transfer rules each contain the exact nine-domain exclusion set and no `include` element;
- `git diff --check` passes;
- forbidden `fallbackToDestructiveMigration`, `OnConflictStrategy.REPLACE`, and custom `BackupAgent` patterns are absent;
- `CLAUDE.md`, `docs/PLAN.md`, `docs/TODO.md`, Gradle state, signing properties, and keystore patterns are ignored, and no signing artifact or ignored control file is tracked.

## Instrumentation evidence

On the official Android API 36 x86_64 emulator, the latest serialization-pinned Debug and androidTest APKs were installed from a clean package state. The package-filtered Room suite completed in 0.36 seconds:

```text
RoomTrackStorageTest: 4 passed
TrailVeilDatabaseMigrationTest: 1 passed
OK (5 tests)
```

This exercises raw invalid and second-active writes, sequence/composite relationship constraints, transactional rollback, bounding-box/relationship/cascade behavior, v1-to-v2 preservation, duplicate-active repair, trigger presence, and a zero-row `PRAGMA foreign_key_check`.

After that run, warning-only source changes adopted the current AGP source-directory API, matched the Room migration parameter name, and used the current database-class `MigrationTestHelper` constructor. The complete build, unit, lint, and instrumentation compilation gate above passed after those changes.

## Independent verification and residual risk

A fresh-context Terra verifier returned PASS with no commit-blocking issue after reviewing the full diff, schemas, invariants, DAO transactions, migration, tests, backup resources, minSdk, dependency pin, and Git secret boundary.

API 34 and API 35 runtime reruns remain pending because starting their existing official AVDs requires writes to the user AVD directory and the sandbox-outside approval was rejected by the execution service's usage limit. This is an environment coverage gap, not an observed product failure. Malformed legacy v1 states beyond the explicitly repaired duplicate-active and missing-end cases are also not exhaustively synthesized; the migration preserves and validates the supported historical schema.
