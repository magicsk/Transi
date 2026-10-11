---
name: transi-review
description: Review Transi Android code changes for correctness, regressions, platform behavior, and missing proof. Use for local diffs, branches, commits, or pull requests; read-only unless the user also asks to fix findings.
---

# Transi Review

Review the requested change against its real base and the repository rules. Optimize for defects a user can hit, not stylistic preference.

## Establish the target

1. Inspect status, branches, worktrees, the requested base, and the complete diff. Do not assume `main` or a remote ref is current.
2. Read every changed file in context. Trace callers, shared models, persistence, networking, manifest entries, resources, and UI state affected by the change.
3. Keep the review read-only unless fixes were explicitly requested.

## Review for

- Incorrect behavior, crashes, data loss, stale state, error paths, and regressions outside the named happy path.
- Main-thread violations, leaked coroutines or threads, Fragment view-lifecycle misuse (bindings after `onDestroyView`, observers on the wrong owner), configuration-change and process-death state loss, and races around sockets, handlers, timers, or Socket.IO callbacks.
- API/session failures, Gson/JSON drift and R8/ProGuard stripping of reflected models, date/time-zone mistakes (Europe/Bratislava), online/offline parity, database consistency, and `SharedPreferences` key/default mismatches.
- Android 17 behavior and design, `SDK_INT` gates with a correct `minSdk 26` fallback, runtime permissions (location, notifications), foreground-service types, exported components and `PendingIntent` mutability, edge-to-edge insets, and predictive back.
- Security: secrets in logs or VCS, cleartext traffic, unsafe intents/deep links, file-provider scope, downloaded-file integrity, and backup of sensitive data.
- Accessibility, font scaling, contrast, 48dp touch targets, TalkBack labels, light/dark themes, reduced motion, and user-facing failure/empty/loading states.
- Verification gaps: missing focused checks, untested reproduction, build gaps (debug and R8 release), or screenshots/recordings that do not prove the requested behavior.

Ignore cosmetic style differences that match nearby code. Do not request speculative abstractions, broad refactors, or new dependencies when a smaller correction works.

## Validate

Run focused checks that are safe and proportional to the diff. For user-visible behavior, inspect the supplied visual proof or reproduce it headlessly on the newest installed Android system image. When API-gated or fallback code changes, require a build and smoke of that path on the oldest installed compatible system image too. A catalog entry, successful compile, or static screenshot of the wrong state is not proof.

## Report

List findings first in severity order:

- `[P0]` data loss, security, or app-unusable failure.
- `[P1]` common crash or major broken behavior.
- `[P2]` real but narrower correctness, compatibility, performance, or accessibility defect.

Each finding must name a precise file/line, triggering scenario, user impact, and the smallest safe correction. If there are no actionable findings, say `No actionable findings.` Then state what was validated and any residual risk or unverified platform. Do not turn optional polish into a blocking finding.
