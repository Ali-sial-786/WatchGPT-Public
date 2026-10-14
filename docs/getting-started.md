# Install WatchGPT

WatchGPT needs an Android phone (Android 12+) paired through Galaxy Wearable with a Wear OS watch (Wear OS 4+). The intended devices are the S24 Ultra and Galaxy Watch9; device-specific verification is separate from software builds.

## Get the matching pair

Open the repository's [Releases page](https://github.com/Ali-sial-786/WatchGPT/releases) while signed in to GitHub. CI publishes verified builds of `main` there as private drafts named **WatchGPT &lt;commit&gt; · key &lt;certificate&gt;** and keeps the newest two ([Signing](#signing-one-time-so-updates-install) says when it publishes). Take the newest. Each holds **WatchGPT-phone.apk**, **WatchGPT-watch.apk**, `SHA256SUMS`, this file (as INSTALL.md) and BEHAVIOR.md. The release notes say which key signed the pair:

- "updates installed pairs with certificate …": the long-term key from [Signing](#signing-one-time-so-updates-install). It installs over an earlier pair with the same key.
- "FRESH signing key": a key made for that one run. It installs only where WatchGPT isn't installed yet.

Download both APKs and `SHA256SUMS` from the same release into one folder, and check them there:

```sh
sha256sum -c SHA256SUMS            # macOS: shasum -a 256 -c SHA256SUMS
```

Both lines must say `OK`. Every CI run also tries to upload the pair as a short-lived Actions artifact (kept 3 days); the draft release is the one to use. Builds of pull requests and other branches always get a fresh key.

If you build locally, the output paths are:

- `app/build/outputs/apk/release/app-release.apk`
- `wear/build/outputs/apk/release/wear-release.apk`

Both use `io.github.alisial786.watchgpt` and the same signing key. Release optimization and release privacy protections are enabled. This is a sideload build, not a Play Store signing arrangement.

## Install

You need [Android Platform-Tools](https://developer.android.com/tools/releases/platform-tools) (it contains `adb`). Run the commands from its folder, or add it to your PATH.

1. On the phone enable Developer options and USB debugging. Connect it to your computer and allow the computer.
2. On the watch enable Developer options, ADB debugging and Wireless debugging. The watch and the computer must be on the same Wi-Fi. Under Wireless debugging choose **Pair new device**, then pair and connect with the address, port and code the watch shows. The pairing port and the debugging port are different and can change.

   ```sh
   adb pair WATCH_IP:PAIRING_PORT      # type the pairing code
   adb connect WATCH_IP:DEBUG_PORT     # the port shown on the Wireless debugging screen
   ```

3. Find serials with `adb devices` (the watch's serial is `WATCH_IP:DEBUG_PORT`). Install with explicit serials, both APKs from the same release:

   ```sh
   adb -s PHONE_SERIAL install -r WatchGPT-phone.apk
   adb -s WATCH_SERIAL install -r WatchGPT-watch.apk
   ```

   Run these commands from the folder with the APKs; for your own builds substitute the output paths above. `-r` updates an installed app and keeps its data. If adb says `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, see [If an install fails](#if-an-install-fails).

4. Open WatchGPT on both devices once. The phone menu contains **Reminders & agenda**. On the watch scroll below the mascot to **Reminders**. Allow notifications when each asks. Reminders do not require ChatGPT sign-in.

## Signing (one-time, so updates install)

Android installs an update only when it is signed with the same key as the installed app, and the phone and watch apps only talk to each other when they share a key. Release builds of both apps are signed with Android's debug signing config (`signingConfig = signingConfigs.getByName("debug")` in `app/build.gradle.kts` and `wear/build.gradle.kts`). So the key in `~/.android/debug.keystore` on the build machine signs them. On GitHub, CI writes the repository secret `WATCHGPT_SIGNING_KEYSTORE` to that file before it builds `main` (`.github/workflows/ci.yml`, "Restore private sideload signing identity"). Without the secret, every runner makes a new key.

Do this once, on a computer with JDK 17 (it includes `keytool`):

1. Make the key:

   ```sh
   keytool -genkeypair -keystore watchgpt-sideload.keystore -storetype PKCS12 -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=WatchGPT sideload"
   ```

   The password `android` and the alias `androiddebugkey` are what the debug signing config expects. They protect nothing, so the file itself must stay private.

2. Save it as the repository secret `WATCHGPT_SIGNING_KEYSTORE`, base64-encoded on one line. Either on GitHub: the repository's **Settings → Secrets and variables → Actions → New repository secret**, name `WATCHGPT_SIGNING_KEYSTORE`, value the output of `base64 -w0 watchgpt-sideload.keystore` (macOS: `base64 -i watchgpt-sideload.keystore`). Or with the GitHub CLI:

   ```sh
   gh secret set WATCHGPT_SIGNING_KEYSTORE -R Ali-sial-786/WatchGPT < <(base64 -w0 watchgpt-sideload.keystore)
   ```

3. Back up `watchgpt-sideload.keystore` somewhere private, for example a password manager. Never commit it. If it is lost, the next pair can't update the installed apps.

4. To build locally with the same key, copy it to `~/.android/debug.keystore` (move any existing file there aside first).

What CI does with it:

- **With the secret:** every push to `main` publishes a draft pair signed with your key. All of them install over each other.
- **Without the secret:** pushes to `main` no longer publish draft releases. A manual run on `main` (Actions → **WatchGPT checks and APKs** → **Run workflow**) still publishes a pair, marked "FRESH signing key". Android refuses it as an update to WatchGPT installed from any other pair. Install it only where WatchGPT isn't installed. Uninstalling to make room deletes that device's reminders.

Apps installed before you set the secret have a key you don't have. To move to your key, uninstall both apps once and install a pair signed with it. This deletes the reminders on both devices, so note any you need first.

## Permissions

- **Notifications**, on each device that should alert: reminder alerts and background Clock request prompts. The reminders screen asks for it the first time it opens. If it's off, that screen says so first and offers **Enable alerts**. Items still save without it.
- **Exact alarms**: nothing to switch on. On Android 13 and later (every supported watch) WatchGPT gets them at install through `USE_EXACT_ALARM`. Only on an Android 12 phone does it use the **Alarms & reminders** special access (`SCHEDULE_EXACT_ALARM`, declared up to API 32); if that is off, the reminders screen says "Precise timing is off" and links to the setting. Without exact alarms Android may delay alerts; the app then uses an inexact alarm.
- **Microphone**, for voice and optional wake listening. Start listening from the visible app. After a watch reboot, reopen WatchGPT on the watch to switch wake listening back on; reminder alarms are rescheduled by themselves after the device is unlocked.
- **Calendar read/write**, phone only, if you choose Google sync. Select your Google calendar in Reminders & agenda → Google Calendar. This is off by default. Google must already be configured and calendar synchronization enabled on the phone.
- Existing optional permissions (contacts, notifications access, accessibility, appearing on top, etc.) remain individually controlled in **What WatchGPT can do**. They are not required for manual reminders.
- Samsung battery restrictions can delay background sync. If this happens, allow background usage / unrestricted battery for WatchGPT on the phone and review watch power-saving settings. Force-stop suspends Android delivery until the app is reopened.

## Sign in and talk

In the phone menu choose **Your AI → Sign in with ChatGPT** and complete the browser sign-in yourself. Do not provide credentials or tokens to anyone. Enable cloud use in the app when you want ChatGPT. Start a conversation on the phone first, then on the watch.

The watch microphone and speaker stream through the phone. During a call on the phone, answers play on headphones connected to the phone, otherwise on the loudspeaker. Watch-connected headphones and Samsung firmware behavior require hardware testing. Phone internet and an active account are required for subscription live voice. Account expiration, access refusal, usage limits, endpoint changes and lost connections are shown explicitly. Tap to reconnect after failure; an automatic retry never repeats prior reminder or alarm commands.

Set **Settings → Conversation timeout** on the phone: 10 sec, 30 sec (the default), 1, 2, 5 or 15 min, or **Until I stop**. It is the silence that ends a call, and it applies to the next phone or watch call. A call where nobody has spoken yet ends within a minute whatever the setting, so a wake-up by mistake doesn't keep the microphone open. "Until I stop" still ends a call after 30 minutes of silence.

## Activation

- Add the **WatchGPT** talk tile from your watch's tile picker.
- On Samsung, open **Settings → Advanced features → Customize buttons → Home button → Double press**, then choose **WatchGPT** (labels can vary with One UI Watch). The watch app starts a conversation when it opens, subject to microphone permission and phone connectivity.
- Say **“Hey Watch G P T”**, spelling the three letters. Enable wake listening first; **Learn my voice** can adapt the recognizer. Always listening costs battery and may generate false activations. This is a longer phrase than upstream's original wake word.
- The phone app registers an Android `ACTION_ASSIST` entry point. When WatchGPT is opened as the assistant, it starts listening. Whether the phone offers WatchGPT in its assistant settings varies by maker. No Wear OS `VoiceInteractionService`, long-press replacement, or privileged default-assistant claim is made. Samsung does not provide an ordinary app API to silently assign its hardware button.

## Build

Use JDK 17, SDK platform 37 / `platforms;android-37.0`, and build-tools 36.0.0. Run `./gradlew :app:assembleRelease :wear:assembleRelease` from the repository. The wake model and sherpa library are fetched with pinned SHA-256 checks. No RevenueCat key, API token, hosted database, or OAuth cloud project is required. To sign with your long-term key, see [Signing](#signing-one-time-so-updates-install).

For emulator development only: `./gradlew :app:assembleDebug :wear:assembleDebug -Pemulator` adds x86_64. Debug builds allow screenshots and include existing debug hooks; use release builds for normal private use.

### Optional: WatchGPT Plus

WatchGPT Plus is inherited from upstream and appears only in a phone build made with a RevenueCat public SDK key: add `-Prevenuecat.apiKey=YOUR_KEY` to the Gradle command. Without it, the build has no Plus. A Test Store key (starting with `test_`) only goes into debug builds; release builds leave it out.

## If an install fails

- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`**: the installed WatchGPT was signed with a different key, so Android won't update it. Set up [Signing](#signing-one-time-so-updates-install) so this doesn't happen again. To install anyway, uninstall WatchGPT on that device first; this deletes the reminders stored there. Sync both devices first (**Sync now** on each): the other device sends its reminders back only if both end up with apps signed by the same key. If you move both devices to a new key, note any reminders you need first.
- **The watch and phone don't see each other's reminders**: both APKs must come from the same release (or your own builds with the same key).

More fixes are in the repository's `docs/troubleshooting.md`.
