---
name: transi-issue
description: Reproduce, diagnose, fix, and prove a Transi Android defect, then run independent correctness and simplification reviews. Use for bug reports, regressions, crashes, or incorrect behavior; reproduce before editing.
---

# Transi Issue

Do not patch the symptom from the report. First prove the failure and trace its shared cause. If the user asks only for diagnosis, stop after the evidence-backed cause and do not implement a fix.

## Reproduce

1. Inspect Git state, the exact build/runtime, relevant data and settings, `adb logcat`, callers, and sibling flows.
2. Ask one compact batch of questions before reproducing when expected behavior, environment, data/account state, target Android version, or scope is materially ambiguous. Do not ask for details the code or available state can establish.
3. Reproduce headlessly on the primary emulator required by `AGENTS.md`. Use another installed system image when the report names an Android version.
4. Record exact steps, expected versus actual behavior, and evidence. For a visual defect, capture the broken state before changing code.
5. If it cannot be reproduced after focused attempts, do not guess. Report the evidence and ask for the smallest missing input that would distinguish likely causes.

## Diagnose and plan

- Identify the root cause and every caller that shares it. Prefer one correction at the shared boundary over guards in individual screens.
- Define the regression check and the exact reproduction that must pass after the fix.
- Use one short plan for a localized issue. Split independent defects or a cross-system issue into coordinated tracks with explicit integration order.

## Fix with subagents

- When collaboration is available, delegate at least one bounded reproduction, implementation, or verification task. Parallelize only read-only work or edits to non-overlapping files.
- The primary agent owns the diagnosis and integration. Require subagents to read `AGENTS.md` and this skill.
- Make the narrowest root-cause fix that preserves established architecture and platform behavior. Add a focused JVM unit test for non-trivial logic.

## Verify and review

1. Repeat the original reproduction, run the `AGENTS.md` checks plus nearby sibling scenarios, then use `$transi-visual-proof` to publish before/after evidence.
2. Run independent `$transi-review` and Ponytail review subagents. Fix actionable findings, then rerun the reproduction, affected checks, visual proof, and both reviews until clean.

Stop and report when progress needs user action or new authority; retry an external failure only when new evidence makes a different result plausible.
