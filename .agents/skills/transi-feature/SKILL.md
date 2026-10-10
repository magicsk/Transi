---
name: transi-feature
description: Deliver a new Transi Android feature from discovery through implementation, headless emulator verification, visual evidence, and clean review loops. Use when adding or materially extending app behavior; use transi-issue for reported defects.
---

# Transi Feature

Own an implementation request end to end. Preserve unrelated work and the existing architecture. If the user asks only for discovery or a plan, stop after that phase and do not start implementation, emulator, or review-loop work.

## Understand before editing

1. Inspect Git state, relevant fragments/adapters/view models/models, all callers, persisted state, APIs, manifest entries, and resources.
2. When the feature exists in the iOS app (`~/Developer/swift/Transi`), read its implementation there first and port its behavior, endpoints, constants, and edge cases rather than reinventing them. Adapt the UI to Android conventions instead of copying iOS chrome.
3. Restate the outcome as concrete acceptance criteria, including the primary Android 17 (API 37) experience and required behavior back to `minSdk 26`.
4. Ask one compact batch of questions when product or technical choices materially change UX, data, scope, compatibility, or privacy. Do not ask questions answered by the code. You may include a clearly provisional plan, but wait for answers before editing when those decisions are material. If nothing material is open, state assumptions and continue.

## Plan

- Use one implementation plan for one cohesive feature.
- For multiple independent features, make separate tracks and name their shared dependencies and integration order.
- For a complex feature, map requirements to files/data flow, failure states, checks, emulator scenarios, and proof before implementation. Keep the plan in the task; create a plan document only if requested.

## Implement with subagents

- When collaboration is available, delegate at least one bounded implementation or verification task. Give each subagent the acceptance criteria and require it to read `AGENTS.md` and this skill.
- Parallelize read-only discovery and edits to non-overlapping files only. The primary agent owns integration, cross-file invariants, and final decisions.
- Reuse the current Fragment/ViewBinding, Hilt, Retrofit/`DataRepository`, `SharedPreferences`, and coroutine patterns. Prefer current platform APIs with a `minSdk 26` fallback. Add no dependency or architectural layer without a demonstrated need.
- Add the smallest meaningful JVM unit test for non-trivial logic. Cover loading, empty, error, offline/background, permission, and accessibility states when relevant.

## Prove the result

Run the repository checks required by `AGENTS.md`, then use `$transi-visual-proof` to exercise every acceptance criterion and publish the evidence.

## Review loop

After behavior is proven, run two independent read-only subagent passes: one with `$transi-review`, one with the available Ponytail review skill. Fix every actionable finding, rerun the affected checks and visual scenarios, and repeat both reviews until they report no actionable findings. Stop and report when progress needs user action or new authority; retry an external failure only when new evidence makes a different result plausible.

Finish with the implemented behavior, checks, review result, evidence page paths, and both local and Tailnet URLs.
