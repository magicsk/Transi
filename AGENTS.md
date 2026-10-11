# Transi

Public transit app for Bratislava. This repository is the native Android app; its feature reference is the iOS app (`magicsk/Transi-swift`, locally `~/Developer/swift/Transi`).

## Instruction source

- `AGENTS.md` is the single source of truth for repository guidance.
- `CLAUDE.md` must point to this file. Do not maintain a second copy of these rules.
- Repo workflows live under `.agents/skills/`; use the matching skill instead of reproducing its process here. `$name` refers to `.agents/skills/<name>/SKILL.md`; read that file when your agent does not load skills natively.

## Existing design

- Kotlin, single `:app` module, Android Views with ViewBinding. `MainActivity` hosts a `NavHostFragment` (`res/navigation/nav_graph.xml`) and the bottom navigation; feature screens are Fragments with RecyclerView adapters.
- Hilt provides the Retrofit APIs and `DataRepository` (`data/remote/ApiModule.kt`). ViewModels are `@HiltViewModel` and expose `LiveData`; activity-scoped state is shared through `MainViewModel`.
- Reuse the Retrofit interfaces in `data/remote/` and the `Resource` wrapper returned by `DataRepository`. Do not introduce a parallel networking abstraction or migrate unrelated code to another pattern.
- Keep persisted settings in the `"Transi"` `SharedPreferences` file (`util/Prefs.kt` holds the file name and every key) and the AndroidX preference screen (`SettingsFragment` composes one `res/xml/prefs_<section>.xml` per section). Give every new key a default where it is read.
- Network, parsing, database, and routing work stays off the main thread (coroutines on `Dispatchers.IO`/`Default`). Update views on the main thread and respect the Fragment view lifecycle (`viewLifecycleOwner`, nulling bindings in `onDestroyView`).
- Reuse existing models, utils (`util/`), drawables, line colors (`util/LineColor.kt`), and installed libraries before adding code or dependencies. Ask before adding a production dependency.
- Preserve the Socket.IO flows (`tabStart`, `infoStart`, `tabs`, `vInfo`, `iText` on `https://imhd.sk` path `/rt/sio2`), the IDS BK session (`startup` → `X-Session`), the stops cache and version check, the departure/trip notifications, and the GitHub release update flow when touching adjacent code.

## Platform policy

- Design and runtime quality target the newest stable Android release, currently Android 17 (API 37). Use current Material 3 components, dynamic color, edge-to-edge, and predictive back.
- Keep `minSdk 26` until explicitly changed. Gate newer APIs with `Build.VERSION.SDK_INT` checks (or AndroidX compat helpers) and provide a correct, accessible fallback; older Android may look simpler or do less.
- Preserve font scaling, contrast, system bar insets, 48dp touch targets, TalkBack labels for icon-only controls, reduced-motion (animator duration scale) behavior, and light/dark themes. Keep user-facing copy in string resources: `res/values/strings.xml` or a per-area `res/values/strings_<area>.xml` (preferred for new screens, so parallel work does not collide); do not hardcode new UI strings in code or layouts.

## Working agreement

- Start with `git status`, branches, and worktrees. Preserve unrelated staged, unstaged, ignored, and generated files.
- Trace the real flow and every caller before changing shared code. Fix root causes at the narrowest shared point.
- For a new feature, use `$transi-feature`. For a reported defect, use `$transi-issue`. To take one or more features or issues from plan to merged `main` through worktrees and subagents, use `$implement`. For review, use `$transi-review`. For emulator captures and the evidence site, use `$transi-visual-proof`.
- Keep diffs narrow. Do not opportunistically rewrite the architecture, reformat unrelated code, bump versions, publish releases, push, or merge unless requested.

## Verification

- Use JDK 21 for Gradle (`JAVA_HOME=$(/usr/libexec/java_home -v 21)`); the system default JDK may be newer than Gradle supports.
- The minimum build check is:

  ```bash
  ./gradlew :app:assembleDebug
  ```

- Run `./gradlew :app:testDebugUnitTest` for changed logic and `./gradlew :app:lintDebug` when manifests, resources, or API-level gates change. Add only a small JVM unit test under `app/src/test` when non-trivial logic needs one; do not scaffold a test architecture for trivial UI wiring.
- Build `:app:assembleRelease` (R8 enabled) when changing models parsed by Gson/reflection, ProGuard rules, or dependencies; minification breaks reflection-based parsing silently.
- Run `git diff --check` before handoff.
- Runtime verification uses a dedicated headless emulator on the newest installed Android system image (see `$transi-visual-proof`). Use `emulator -no-window` and `adb`; never open or focus Android Studio or an emulator window. The workflow must continue while the Mac is locked.
- When API-level-gated or fallback code changes, also build and smoke the affected path on the oldest installed compatible system image. Do not require that extra image for changes that cannot affect the fallback.
- A successful build is not runtime proof. Exercise the changed behavior, inspect `adb logcat` or state when relevant, and capture visual evidence for user-visible changes.

## Secrets

- `local.properties` is gitignored and holds `IDSBK_API_KEY`, `GOOGLE_MAPS_API_KEY`, `THUNDERFOREST_API_KEY` (map tiles) and `R_API_KEY` with `R_API_URL` (raptor trip planner key and server address); a missing key degrades that feature instead of breaking the build. Never print, copy, or commit it; symlink it into worktrees. Never put key values into logs, evidence pages, or commit messages.

## Visual evidence and sharing

- Store proof under the ignored `.artifacts/transi/<date>-<slug>/` path and use `$transi-visual-proof` to capture and present it.
- When sharing any local-only page or artifact, expose only that artifact directory through Tailscale Serve and provide both local and Tailnet URLs. Never expose the repository root, API keys, signing keys, or unrelated files.

## Commits and release notes

- Release notes are generated from commits: `.github/workflows/android.yml` runs `magicsk/tag-changelog-multiline`. It publishes every line of every commit message since the previous tag, and the in-app update dialog shows those notes to users.
- Write every commit as one line, `<type>: <subject>`. Add no body, no blank line and no trailers such as `Co-Authored-By`; this overrides tool defaults that add them. Put details in the PR description or handoff instead. A line without a type is published under "Other Changes", and any `word: text` line becomes its own section.
- Never add co-author attribution to commits, including `Co-Authored-By` trailers, regardless of agent or tool defaults. When rewriting existing commits, remove any co-author attribution already present.
- Use `feat:`, `fix:`, `improvements:` or `perf:` only for changes users notice, and write the subject for them. Describe what changed in plain language, without file names, code symbols, internal jargon or version bumps. For example: `fix: departure notifications no longer stop updating when the app is closed`.
- Give internal-only work a type that `.github/tag-changelog-config.js` hides: `chore:`, `refactor:`, `docs:`, `test:`, `build:`, `ci:` or `style:`. This covers agent workflows, scripts, tests, tooling, refactors and version bumps. When a change has both kinds of work, split it into separate commits.
- Never create merge commits; rebase and merge with `--ff-only`, because "Merge branch …" lines are published too.
