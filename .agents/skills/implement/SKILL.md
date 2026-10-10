---
name: implement
description: Take one or more Transi Android features or issues from a proposed plan to merged `master`, using isolated Git worktrees and subagents. Use when the user asks to implement, build, or fix work end to end, especially several items at once; use transi-feature or transi-issue directly for a single in-place change.
---

# Implement

You coordinate; subagents write and review the code. You own the plan, the gates, judging review findings, evidence, the merge, and cleanup. Follow any user instruction that narrows this flow (plan only, wait for approval, no worktree, no subagents, skip a phase). For this run, the review, proof, and cleanup steps below replace the ones in the delegated skills.

## Any agent

- `$name` means `.agents/skills/<name>/SKILL.md`. Read it if your agent does not load skills natively.
- "Subagent" means your agent's delegation tool. Without one, run tracks one after another, and do each subagent step as a separate pass that starts from the diff, not from memory. State in the handoff that review was not independent.
- Worktrees live outside the repo, and the emulator, `adb`, and `tailscale` touch the system. Request whatever permission or sandbox escalation your agent needs for them.

## 1. Propose the plan

1. Inspect `git status`, branches, `git worktree list`, and the code every item touches: callers, shared models, persistence, manifest, and resources.
2. Split the request into items. Mark each as a feature (`$transi-feature`) or an issue (`$transi-issue`) and write its acceptance criteria.
3. Give each independent item its own track: one worktree, one branch, one subagent, approved and merged as a unit. Items that edit the same files or depend on each other share a track or get an explicit merge order. Shared hot spots in this repo are `app/build.gradle.kts`, `AndroidManifest.xml`, `res/values/strings.xml`, `nav_graph.xml`, `MainActivity.kt`, and `DataRepository.kt`; give their edits an explicit merge order. A shared track that contains an issue follows `$transi-issue`.
4. Compare each track's files with the main checkout's uncommitted changes. New worktrees start from committed `master` and cannot see those changes, and the merge cannot overwrite them. Ask about each overlap now and hold only that track. Suggest committing those edits to `master` first.
5. Post the plan: tracks, acceptance criteria, files, branch names, merge order, checks, and the emulator scenarios that will prove each criterion.
6. Start the unblocked tracks in the same turn. Hold a track only if the user asked to review the plan first, or if an open question would change its UX, data, scope, or compatibility. Ask all open questions in one compact batch.

## 2. Create worktrees and the ledger

You are already in a linked worktree when `git rev-parse --git-dir` differs from `git rev-parse --git-common-dir`. Reuse it only if the plan has one track and its non-`master` branch is clean and contains `master` with no other commits (`git rev-list --count master..HEAD` prints `0`). Otherwise create one worktree per track from local `master`:

```bash
git -C <main checkout> worktree add -b <feat|fix>/<slug> ~/.config/superpowers/worktrees/Transi-android/<slug> master
ln -s "<main checkout>/local.properties" ~/.config/superpowers/worktrees/Transi-android/<slug>/local.properties
```

`local.properties` is gitignored and holds the SDK path and the API keys every build needs. Symlink it; never copy or commit it.

Create the run directory `<main checkout>/.artifacts/transi/<date>-<slug>/`. Store each track's evidence in a `<track-slug>/` subfolder, so it survives worktree removal and can be served on its own. Keep `ledger.tsv` at the top of the run directory and add one line for each thing anyone creates or starts:

- `<track|run> worktree <path>` and `<track|run> branch <name>`
- `<track|run> avd <name> created` and `<track|run> emulator <serial> booted`. Log an emulator as `booted` only if it was not running before you started it.
- `<track|run> pid <pid> <purpose>`
- `<track|run> serve <exact serve command>`

Before implementation starts, run `./gradlew :app:assembleDebug` in one fresh worktree. That untouched build is the baseline for the gate.

## 3. Implement with subagents

Spawn one implementation subagent per track and run them in parallel. Each prompt contains:

- the absolute worktree path, with the rule to read, edit, and build only there. Subagents do not commit;
- the item's plan and acceptance criteria, and the iOS reference files to port from when the feature exists in `~/Developer/swift/Transi`;
- instructions to read `AGENTS.md` and `$transi-feature` or `$transi-issue`, then follow only its understand/reproduce, plan, and implement sections;
- the run directory and ledger path. Parallel Gradle builds in several worktrees are fine; parallel tracks must not share one emulator. A subagent that must reproduce an issue on an emulator creates its own AVD from the newest image and logs it. It saves before-fix evidence in its track folder and logs everything it starts. If the baseline cannot build, it reproduces with a small JVM test or data harness and reports that runtime reproduction is blocked;
- no shortcuts: meet every criterion and every relevant loading, empty, error, offline, and accessibility state. Do not leave TODO placeholders, stubbed or hardcoded data, disabled checks, `@Suppress`ed warnings, `!!` added to get past errors, or commented-out code;
- report back: changed files, checks run with results, and anything unfinished.

Gate every track, then commit it in its worktree with a `feat:`/`fix:` message that matches the history. Later fixes become further commits.

- Read the diff yourself. If anything is partial or a placeholder, send the track back.
- Run `git add -A` and check `git status` for stray files. Then `git diff --cached --check` must be clean.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes with zero errors and adds no warnings in changed files. Run `:app:lintDebug` when manifests, resources, or API gates change, and `:app:assembleRelease` when models, ProGuard rules, or dependencies change.
- If the baseline build fails the same way, the environment is the blocker. Compile what you can (`:app:compileDebugKotlin`) and report the blocker in the handoff. Never call it a clean build.

## 4. Review, fix, re-review

For each track:

1. Spawn a fresh read-only review subagent that did not write the code. It runs `$transi-review` on `git diff master...<branch>`. Tell it emulator proof comes later: it must not use the emulator or report missing proof.
2. Check every finding against the code yourself. Mark it **fix** (real) or **reject** (wrong, speculative, or out of scope) with a one-line reason. If nothing is marked fix, go to section 5.
3. Spawn a new fix subagent with only the findings marked fix, the worktree path, and the no-shortcut rules. Rerun the gate.
4. Spawn one more fresh review subagent on the updated branch and judge its findings the same way. Send confirmed findings to another fix subagent, then rerun the gate. Do not run a third review. List anything still unresolved in the handoff.

## 5. Prove on the emulator

Use `$transi-visual-proof` for each track. Build from that worktree, exercise every acceptance criterion, and save the evidence in the track folder. Tracks share the pinned AVD and application ID, so prove them one after another. To prove tracks in parallel, give each one its own AVD and log it as `created`. When API-gated or fallback code changes, also smoke that path on the oldest installed compatible system image. Prove behavior you cannot see, such as vibration or background updates, with logs or `dumpsys` state. If no build can run, mark the proof as blocked and do not claim runtime proof.

Check the evidence before sending it. Open each page, view every screenshot and video, and confirm each criterion is visible in the right state. Recapture anything that is blank, cropped, stale, or shows the old behavior. If no browser tool is available, view the image files directly and fetch the page and its media over HTTP.

## 6. Hand off and request merge approval

For each track, report what changed, the branch and commits, check and build results, review findings fixed and rejected, the local and Tailnet evidence URLs, and anything unresolved or blocked. Ask for approval to merge each branch into `master`, then stop until the user answers. Do not push.

If the user requests changes, make them in the same worktree, rerun the gate and the affected proof, and ask again.

## 7. Merge and clean up

Only after explicit approval, merge the approved tracks in plan order. Rebase in the track's worktree. Run merge, worktree, and branch commands against the main checkout, never the current directory:

```bash
git -C <worktree> rebase master
git -C <main checkout> merge --ff-only <branch>
```

Rebase each approved track onto the new `master`, then rerun the gate before merging it. If a rebase conflict changes behavior, re-prove that track and ask for approval again. Never stash, reset, or overwrite uncommitted changes in the main checkout. If a merge would touch them, stop that track and ask.

After merging, clean up everything in the ledger and nothing outside it. This cleanup is the user's request to remove the routes and emulators that `$transi-visual-proof` keeps. Keep only what an unapproved track still needs: its worktree, its branch, and its evidence route along with any server behind that route.

- `git -C <main checkout> worktree remove <path>` and `git -C <main checkout> branch -d <branch>`. Do not use `--force` or `-D` without asking. Keep a worktree you did not create, and tell the user it remains.
- Kill each logged PID: logcat streams, HTTP servers, screen recordings, and background builds.
- Rerun each logged `tailscale serve` command with its target replaced by `off`. Keep the same `--http`/`--https` port and `--set-path`.
- `adb -s <serial> emu kill` for emulators you booted, and `avdmanager delete avd -n <name>` for AVDs you created.
- Verify with `git worktree list`, `tailscale serve status`, and `adb devices`. Then report what you removed and what you kept.
