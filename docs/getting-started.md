# Install WatchGPT

WatchGPT needs an Android phone (Android 12+) paired through Galaxy Wearable with a Wear OS watch (Wear OS 4+). The intended devices are the S24 Ultra and Galaxy Watch9; device-specific verification is separate from software builds.

## Get the matching pair

Use the delivered **WatchGPT-phone.apk** and **WatchGPT-watch.apk** pair. Verify the files against the supplied `SHA256SUMS` before installation. GitHub Actions also builds a matching pair as **WatchGPT-phone-and-watch-COMMIT**, but artifact storage is currently exhausted on the owner account; upload warnings do not mean that a downloadable artifact exists. Verified main-branch builds also create a **private draft release** containing both APKs, checksums, and install instructions; repository collaborators can download it from GitHub Releases while signed in. These private releases and the handoff APKs are independent of the Actions artifact quota. Always use the pair from one release or one handoff, since CI may use a different signing certificate until the optional secret is configured. If building locally, the output paths are:

- `app/build/outputs/apk/release/app-release.apk`
- `wear/build/outputs/apk/release/wear-release.apk`

Both use `io.github.alisial786.watchgpt` and the same developer test signing key. Release optimization and release privacy protections are enabled. This is a sideload build, not a Play Store signing arrangement. Keep your local `$HOME/.android/debug.keystore` private and backed up if you want to build updates with the same certificate. CI supports the optional private repository secret `WATCHGPT_SIGNING_KEYSTORE` (base64 of the backed-up debug keystore). The GitHub connection available during setup cannot manage secrets (HTTP 403), so this secret must be set by the repository owner. Until then, CI produces a fresh matching test pair on each runner. Keystore contents are never committed or logged. A different local build machine must use your backed-up key; switching signing keys requires uninstalling both apps, which erases local data. Do not uninstall a device that holds unsynchronized reminders.

## Install

1. On the phone enable Developer options and USB debugging. Connect it to your computer and authorize the computer.
2. On the watch enable Developer options and Wireless debugging. Use the pairing address and code displayed on the watch: `adb pair ADDRESS:PAIRING_PORT`, then `adb connect ADDRESS:DEBUGGING_PORT`. Pairing and debugging ports are different and can change.
3. Find serials with `adb devices`. Install with explicit serials:

   ```sh
   adb -s PHONE_SERIAL install -r WatchGPT-phone.apk
   adb -s WATCH_SERIAL install -r WatchGPT-watch.apk
   ```

   Run these commands from the delivered APK folder; for your own builds substitute the output paths above.

4. Open WatchGPT on both devices once. The phone menu contains **Reminders & agenda**. On the watch scroll below the mascot to **Reminders**. Reminders do not require ChatGPT sign-in.

## Permissions

- **Notifications**, on each alerting device: reminder alerts and background Clock request prompts. If denied, items still save. Re-enable from app settings.
- **Alarms & reminders / exact alarms** special access, on each alerting device: timely delivery. The reminders screen links to the system control. Without it Android may delay reminders; the app uses an inexact fallback.
- **Microphone**, for voice and optional wake listening. Start listening from the visible app. After reboot, reopen WatchGPT to rearm wake listening; reminder scheduling itself recovers automatically after unlock.
- **Calendar read/write**, phone only, if you choose Google sync. Select the writable Google calendar in Reminders & agenda → Google Calendar. This is off by default. Google must already be configured and calendar synchronization enabled on the phone.
- Existing optional permissions (contacts, notifications access, accessibility, appearing on top, etc.) remain individually controlled in **What WatchGPT can do**. They are not required for manual reminders.
- Samsung battery restrictions can delay background sync. If this happens, allow background usage / unrestricted battery for WatchGPT on the phone and review watch power-saving settings. Force-stop suspends Android delivery until the app is reopened.

## Sign in and talk

In the phone menu choose **Your AI → Sign in with ChatGPT** and complete the browser sign-in yourself. Do not provide credentials or tokens to anyone. Enable cloud use in the app when you want ChatGPT. Start a conversation on the phone first, then on the watch.

The watch microphone/speaker stream through the phone; supported headphones connected to the phone use Buddy's existing route selection. Watch-connected headphones and Samsung firmware behavior require hardware testing. Phone internet and an active account are required for subscription live voice. Account expiration, access refusal, usage limits, endpoint changes and lost connections are shown explicitly. Tap to reconnect after failure; an automatic retry never repeats prior reminder or alarm commands.

Set **Settings → Conversation timeout** on the phone. The choice applies to subsequent phone and watch calls.

## Activation

- Add the **WatchGPT** talk tile from your watch's tile picker.
- On Samsung, open **Settings → Advanced features → Customize buttons → Home button → Double press**, then choose **WatchGPT** (labels can vary with One UI Watch). The existing launcher activity starts a conversation when opened, subject to microphone permission and phone connectivity.
- Say **“Hey Watch G P T”**, spelling the three letters. Enable wake listening first; **Learn my voice** can adapt the recognizer. Always listening costs battery and may generate false activations. This is a longer phrase than Buddy's original wake word.
- An Android `ACTION_ASSIST` entry point is registered for the phone. OEM availability in assistant settings varies. No unverified Wear OS `VoiceInteractionService`, long-press replacement, or privileged default-assistant claim is made. Samsung does not provide an ordinary app API to silently assign its hardware button.

## Build

Use JDK 17, SDK platform 37 / `platforms;android-37.0`, and build-tools 36.0.0. Run `./gradlew :app:assembleRelease :wear:assembleRelease` from the repository. The wake model and sherpa library are fetched with pinned SHA-256 checks. No RevenueCat key, API token, hosted database, or OAuth cloud project is required.

For emulator development only: `./gradlew :app:assembleDebug :wear:assembleDebug -Pemulator` adds x86_64. Debug builds allow screenshots and include existing debug hooks; use release builds for normal private use.
