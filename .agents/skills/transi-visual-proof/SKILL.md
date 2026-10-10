---
name: transi-visual-proof
description: Verify Transi Android UI headlessly on an emulator, capture screenshots or recordings, build an HTML evidence page, and publish only that page through Tailnet. Use after user-visible feature or issue work, or when visual/runtime proof is requested.
---

# Transi Visual Proof

Produce proof that shows the requested behavior, not merely that the app launched.

## Run without stealing focus

- Pin verification to a dedicated AVD named `Transi_API<level>` on the newest installed Android system image (`google_apis_playstore` arm64, current Pixel Pro device). Reuse it; if it does not exist, create it with `avdmanager create avd -n Transi_API<level> -k "system-images;android-<level>;google_apis_playstore;arm64-v8a" -d pixel_9_pro`. Never borrow an arbitrary running emulator or a connected phone.
- Boot it headlessly: `emulator -avd <name> -no-window -no-audio -no-boot-anim -no-snapshot-save &`, then `adb -s <serial> wait-for-device` and poll `adb -s <serial> shell getprop sys.boot_completed` until it prints `1`. Never launch Android Studio or an emulator window. Always pass `-s <serial>` to `adb`.
- Build with `./gradlew :app:assembleDebug` (JDK 21), install with `adb install -r -g app/build/outputs/apk/debug/app-debug.apk`, and launch with `adb shell am start -n eu.magicsk.transi/.MainActivity`. Use `-g` only when permission prompts are not under test; prove permission flows without it.
- Drive the UI semantically: `adb shell uiautomator dump /sdcard/ui.xml` and read bounds/`content-desc`/`text` before `adb shell input tap|swipe|text|keyevent`. Re-dump after navigation or layout changes. Use `adb shell am start -a android.intent.action.VIEW -d <uri>` for deep links.
- Set location with `adb emu geo fix <lng> <lat>` (Bratislava, e.g. `17.1077 48.1486`), dark mode with `adb shell cmd uimode night yes|no`, font scale with `adb shell settings put system font_scale 1.3`, and network loss with `adb shell svc wifi disable; adb shell svc data disable`. Restore every setting you change.
- Use `adb logcat -d` (filtered by the app PID) for logs and state; `adb shell dumpsys notification --noredact` proves notification content you cannot screenshot.

## Capture proportional evidence

- A stable one-screen change: capture clear screenshots of every changed state with `adb exec-out screencap -p > <name>.png`.
- Navigation, gestures, animation, loading transitions, multi-step features, or a reproduced defect: record the interaction with `adb shell screenrecord --time-limit <s> /sdcard/<name>.mp4` and `adb pull` it, and also capture key screenshots.
- Include happy path plus material loading, empty, error, permission, offline, background, light/dark, font scale, or reduced-motion states affected by the change. Expand the notification shade (`adb shell cmd statusbar expand-notifications`) to capture notifications.
- Use descriptive media names. Do not include personal notifications, credentials, API keys, real personal location/history, or unrelated desktop content.

## Build the evidence page

Create `.artifacts/transi/<date>-<slug>/index.html` with plain self-contained HTML/CSS and relative media paths. No framework, package, CDN, analytics, or build step.

The page must contain:

- feature/issue name, branch/commit, emulator device and Android version/API level;
- acceptance criteria and short steps showing how each changed behavior works now;
- screenshots and videos grouped by feature/state, with useful captions and native video controls;
- focused checks and build results;
- known limits or unverified Android versions.

Open the page in a browser tool and verify layout, every image, and video playback before sharing it. If no browser tool is available, view the image files directly and fetch the page and its media over HTTP.

## Share on Tailnet

Inspect `tailscale status` and existing Serve routes first. Publish only the absolute evidence directory at a unique path, for example:

```bash
tailscale serve --bg --set-path /transi-android/<unique-slug> /absolute/path/to/.artifacts/transi/<date>-<slug>
```

Read the actual URL from Tailscale Serve status and verify the page and media return successfully through that URL. Keep the route available for the user; replace or remove it only when asked or superseded. If Tailscale is unavailable or unauthenticated, report that exact blocker and do not claim the local page is shared.

Return the artifact directory, `index.html`, local URL, Tailnet URL, captured scenarios, and any state that could not be demonstrated.
