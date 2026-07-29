# P2-003 Recording State Machine Evidence

Date: 2026-07-29

Status: PASS

## Implemented contract

- Recording starts as a durable `STARTING` reservation. Activation atomically changes it to
  `ACTIVE`, assigns the process owner token, and opens the initial segment. Start failure and
  stop-during-start persist honest terminal states.
- Every command and location delivery has a durable operation receipt. The receipt captures the
  resulting projection in the same Room transaction; replay decodes only that snapshot and never
  rebuilds the result from mutable live state.
- A location rejected before quality evaluation still receives a coordinate-free durable guard
  receipt. Replaying that operation after recovery cannot write a point or change counters.
- Accepted points, rejected counters, distance summaries, segment close/open transitions, point
  sequence allocation, and receipts commit or roll back together.
- A process runtime token and expected open-segment ID guard every location transaction. Recovery
  rotates the segment and transfers ownership after process restart. Same-process secondary
  repositories, old owners, externally rotated segments, and replay races cannot acquire a second
  independent quality-filter writer.
- Rejected persistence receives only rejection metadata and an optional break reason; raw rejected
  provider coordinates never cross the recording-store boundary.

## Database and migration evidence

- Room schema version 3 adds `STARTING`, one open-segment slot per session, the active location
  owner token, and durable receipt projection columns.
- Migration 1 to 2 uses version-2-only session triggers and does not reference later columns.
- Migration 2 to 3 replaces those triggers, repairs legacy open/partially closed segments, preserves
  points, marks migrated active sessions as requiring recovery, creates receipt/index structures,
  and recreates current session/segment invariant triggers.
- Migration instrumentation verifies `PRAGMA foreign_key_check`, preserved points, repaired
  timestamps/end reasons, singleton open-segment enforcement, owner-token invariants, and required
  indices.
- Exported schema 3 parses successfully. SHA-256:
  `4606180183FE42A0E3C16CF2E929CE26F36F4DF3D21AB6FF4BAAB1F0A91607F9`.

## Deterministic race and failure coverage

- JVM repository tests cover complete start, first/continuous fixes, gap rejection, after-break
  resume, stop, injected DB failures, filter rollback, operation collisions, stable replay
  projections, durable local stale replay, same/new-process recovery ownership, old-owner guards,
  external state-refresh revocation, and old-command replay isolation.
- A targeted receipt race forces the outer repository lookup to miss while the store transaction
  finds a concurrently committed guard receipt. Replay restores the filter checkpoint without
  changing the valid owner gate; the next point remains `FIRST` with zero distance.
- Room instrumentation covers receipt/summary rollback, command collision, start races, migration,
  raw SQL invariant bypass attempts, recovery rotation, stable projection replay, durable stale
  replay, and same/new-runtime owner behavior.

## Final validation

- `testDebugUnitTest`: 69 tests, 0 failures, 0 errors, 0 skipped.
- `compileDebugAndroidTestKotlin`: PASS.
- Official Android Emulator connected tests:
  - API 34: 18 tests, 0 failures.
  - API 35: 18 tests, 0 failures.
  - API 36: 18 tests, 0 failures.
- `assembleDebug`, `assembleInternal`, and `assembleMapLibreInternal`: PASS.
- `lintDebug`, `lintInternal`, and `lintMapLibreInternal`: PASS; each XML report contains 0 issues.
- `git diff --check`: PASS.
- Tracked-file scans found no `CLAUDE.md`, plan/TODO file, signing properties, keystore, or private-key
  artifact. Internal signing was consumed only through the approved external properties path; no
  key material was copied into the repository.

## Independent verification

A fresh GPT-5.6-sol verifier first found and blocked a concurrent replayed-guard ordering bug. The
replay branch was made authoritative and a deterministic reproduction was added. A second fresh
GPT-5.6-sol verifier independently reviewed the frozen tree and returned PASS with no
commit-blocking finding. It forced a clean execution of 69 JVM tests plus Android-test compilation,
verified all 31 relevant tasks executed successfully, and independently checked the schema, lint
reports, diff cleanliness, receipt/owner invariants, and sensitive-file boundary.

## Residual nonblocking scope

- The deterministic receipt lookup/transaction race is covered at the repository boundary; a
  separate real Room multi-connection stress test can be added with the foreground-service work.
- Foreground-service lifecycle, notification actions, process-death orchestration, permissions, and
  long locked-screen device runs remain P3 tasks.
- Detailed quality rejection reasons are not encoded in durable receipt outcomes; the persisted
  break reason and coordinate-free rejection result are sufficient for P2-003 correctness.
