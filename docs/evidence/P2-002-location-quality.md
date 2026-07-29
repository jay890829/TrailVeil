# P2-002 location quality pipeline evidence

Date: 2026-07-29

Result: PASS for the P2-002 acceptance criteria. TrailVeil now has a provider-independent location stream contract, a deterministic fake, geodesic distance, and a stateful quality gate that accepts only canonical fixes and makes rejected coordinates unavailable to persistence consumers.

## Contract and boundaries

- `LocationEngine.fixes()` exposes a Kotlin `Flow<RawLocationFix>` without selecting an Android or Play Services provider.
- `LocationUpdateRequest` defaults to a 5,000 ms interval, 2,000 ms minimum interval, and 5 m minimum displacement, with constructor invariants.
- Ordering, age, gaps, and implied speed use monotonic elapsed realtime nanoseconds. Epoch milliseconds are retained as payload only and do not control ordering.
- Default quality limits are inclusive at 50 m horizontal accuracy and 15 seconds fix age.
- A continuity gap occurs only after more than 60 seconds. First and post-break accepted fixes contribute zero distance.
- Great-circle distance uses the fixed IUGG mean Earth radius of 6,371,008.8 m, a normalized dateline delta, a clamped haversine term, and `atan2`.
- Jump detection uses `max(0, centerDistance - previousAccuracy - currentAccuracy) / elapsedSeconds`. Speeds through 100 m/s are accepted; a `1e-9 m/s` comparison tolerance handles only floating-point reconstruction noise, while substantive values above the limit are rejected.
- Accepted continuous distance remains center-to-center distance; accuracy is not subtracted from the stored trip distance.
- A gap or impossible jump clears the continuity anchor while preserving the last accepted monotonic ordering floor. Recovery is `AFTER_BREAK`, contributes zero distance, and carries the exact `GAP` or `IMPOSSIBLE_JUMP` reason.
- `LocationQualityDecision.Rejected` contains only rejection reasons and an optional break reason. It has no raw fix, latitude, longitude, or other coordinate field.

P2-002 deliberately does not add a platform provider, permission, service, Room mapping, repository transaction, sequence allocation, or persistence write. Those integrations remain assigned to P2-003 and P3.

## Deterministic test coverage

The focused suite contains 21 JVM tests:

- 3 geodesic-distance tests: zero, one-degree reference distance, and short dateline paths in both directions;
- 4 engine/request tests: defaults, invalid request invariants, deterministic fake delivery/request capture, and cancellation;
- 14 quality-filter tests: first/continuous fixes, accuracy and coordinate boundaries, invalid optional fields, mock metadata, age/future/invalid timestamps, duplicate/out-of-order fixes, epoch regression, dateline continuity, exact/over-gap behavior, late delivery, rejected gap recovery, plausible/exact/too-fast movement, accuracy uncertainty, jump recovery/order floor, and accepted-only persistence shape.

Important boundary proofs include accuracy 49.999/50.0/50.001 m, age exactly 15 seconds versus 15 seconds plus 1 ns, gap below/exactly/over 60 seconds, 90/exactly 100/101 m/s movement, valid ±90°/±180°, NaN/infinity, and a wall-clock epoch regression with increasing monotonic time.

The integration-style consumer test records only `Accepted.fix` values, feeds inaccurate, malformed, jump, and gap-triggering rejected inputs, and confirms that none of their coordinates enter the accepted list. It also checks the rejected JVM shape for coordinate/raw-fix fields.

## Build and verification

The current isolated offline project gate ran:

```text
:app:testDebugUnitTest
:app:assembleDebug
:app:lintDebug
:app:assembleInternal
:app:lintInternal
:app:assembleMapLibreInternal
:app:lintMapLibreInternal
--offline --no-daemon --max-workers=2
```

Result: `BUILD SUCCESSFUL` in 47 seconds; 140 tasks, 41 executed and 99 up-to-date. The full 11-suite JVM report contains 55 tests with zero failures, errors, or skips. Debug, Internal, and MapLibre Internal lint reports each contain zero issues.

Static checks also passed for exactly six new location files, no trailing whitespace or tabs, no wall-clock/sleep usage, and no Android provider, Room, database entity, repository, service, permission, or dependency coupling.

A fresh-context GPT-5.6-sol verifier independently reviewed the source, tests, PLAN/TODO contract, boundary inclusivity, state transitions, NaN/overflow paths, and rejected non-persistence. It reran the focused offline suite with signing redirected to a verified nonexistent path, observed 21/21 tests pass with zero failures/errors/skips, accessed no private signing material, and returned PASS with no commit-blocking findings or acceptance-relevant test gap.
