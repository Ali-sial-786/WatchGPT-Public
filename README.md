# WatchGPT

Android + Wear OS fork of [Buddy / watch-ai](https://github.com/thenguyentrong/watch-ai), for a Galaxy phone and watch. Both apps use the name **WatchGPT** and application ID `io.github.alisial786.watchgpt`. The original mascot, animation engine, voice bridge, local models, conversation history, and privacy controls are retained.

**Live voice is experimental.** This fork keeps upstream's ChatGPT account sign-in and its undocumented realtime voice endpoint. It uses no OpenAI API key and has no automatic paid fallback. A ChatGPT subscription does not guarantee that endpoint is available. Sign-in, live voice, headphones, and Samsung button behavior must be verified on your own devices. WatchGPT is an independent project, not an OpenAI or Samsung app.

## Install in 5 steps

You need a computer, a USB cable for the phone, and the watch on the same Wi-Fi as the computer.

1. **Find the newest pair.** Open the [Releases page](https://github.com/Ali-sial-786/WatchGPT-Public/releases) while signed in to GitHub as the owner (the drafts are private). Take the newest draft named **WatchGPT &lt;commit&gt; · key &lt;certificate&gt;**. If its notes say **FRESH signing key** and WatchGPT is already installed, don't use it: see [Signing](docs/getting-started.md#signing-one-time-so-updates-install).
2. **Download and check.** Download `WatchGPT-phone.apk`, `WatchGPT-watch.apk` and `SHA256SUMS` from that one release into one folder. In that folder run `sha256sum -c SHA256SUMS` (macOS: `shasum -a 256 -c SHA256SUMS`). Both lines must say `OK`.
3. **Get adb.** Install [Android Platform-Tools](https://developer.android.com/tools/releases/platform-tools) and run the commands below from its folder (or add it to your PATH).
4. **Install both apps.** Both APKs must come from the same release.
   - Phone: turn on Developer options and USB debugging, connect it, and allow the computer.
   - Watch: turn on Developer options, ADB debugging and Wireless debugging. Under Wireless debugging choose **Pair new device**; the watch shows an address, a pairing port and a code.

   ```sh
   adb devices                                   # the phone's serial
   adb -s PHONE_SERIAL install -r WatchGPT-phone.apk
   adb pair WATCH_IP:PAIRING_PORT                # type the code the watch shows
   adb connect WATCH_IP:DEBUG_PORT               # the port on the Wireless debugging screen, not the pairing port
   adb -s WATCH_IP:DEBUG_PORT install -r WatchGPT-watch.apk
   ```

   If adb says `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the installed app has a different signing key: see [Troubleshooting](docs/troubleshooting.md#install_failed_update_incompatible).
5. **Open both apps and allow notifications.** On the phone open the menu → **Reminders & agenda**; on the watch scroll below the mascot to **Reminders**. Allow notifications when asked; without them reminders can't alert. Reminders don't need a ChatGPT sign-in.

More detail: [Installation and permissions](docs/getting-started.md) · [Owner checklist](docs/OWNER-CHECKLIST.md) · [Behavior and limitations](docs/WATCHGPT_BEHAVIOR.md) · [Screen previews](docs/screenshots/README.md) · [Implementation plan](docs/WATCHGPT_PLAN.md) · [Verification](docs/WATCHGPT_VERIFICATION.md)

## What's added

- Native reminders and agenda on the watch and phone: create, edit, complete, delete, snooze, notes, date/time, repeats (daily, weekdays, weekly, monthly), and which device alerts. Manual use works without the phone or internet.
- Encrypted on-device storage (AES-256-GCM with an Android Keystore key). To sync, the two devices exchange items through Google Play services' Wear Data Layer, which also keeps a copy of each item on both devices; WatchGPT doesn't encrypt that copy. Sync resolves concurrent edits the same way on both devices, keeps deletion records without content, reschedules after reboot and time changes, and remembers which alerts already rang.
- Exact alarms are granted at install (`USE_EXACT_ALARM` on Android 13 and later): there is no special-access switch to find.
- Optional Google Calendar copies through the phone's Calendar Provider, as private events in a Google calendar you own and choose. These are calendar events, **not Google Tasks**. No hosted database, server account, or extra subscription is required.
- Voice reminders: reminders and events created by voice alert on both devices unless you name one. Deleting one by voice is read back and waits for your yes.
- Configurable voice conversation timeout: 10 seconds to 15 minutes (default 30 seconds), or "Until I stop".
- “Hey Watch G P T” wake phrase, or your own two to five words (**Change phrase** on the watch), existing voice training, launcher, talk tile, and Samsung double-press setup.
- Sleep from Health Connect ("How did I sleep?"): Samsung Health's sleep times and stages, told by the phone in its own voice, or by ChatGPT in its voice if you turn that on.
- Native Clock requests with recurring weekdays and honest results: sent to Clock, waiting for a notification tap, or failed. Android gives no proof that its Clock app created an alarm.

## Build

```sh
git clone https://github.com/Ali-sial-786/WatchGPT-Public.git
cd WatchGPT
git remote add upstream https://github.com/thenguyentrong/watch-ai.git
./gradlew spotlessCheck checkSyntheticFixtures test testDebugUnitTest lintDebug :app:assembleRelease :wear:assembleRelease
```

JDK 17 and Android SDK 37 are required; the Gradle wrapper and SDK manager resolve the pinned dependencies. The phone and watch APKs must be signed with the same key, and an update installs only over an app signed with the same key. Set that key up once as described in [Signing](docs/getting-started.md#signing-one-time-so-updates-install). CI builds both APKs together and publishes verified `main` pairs as private draft releases. Never install a watch APK from one release or build with a phone APK from another.

## Upstream and licensing

Imported with full upstream history from `ccf9dd26a28badac5f78debc11d5079e13161ddf`. Original work by Vinh Nguyen and upstream contributors. Original mascot sources and third-party acknowledgments are preserved. See [LICENSE](LICENSE), [NOTICE](NOTICE), and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). WatchGPT changes are provided under the same Apache-2.0 license. Original screenshots and historic design documents under `docs/media` describe upstream; they are not evidence of WatchGPT device testing.

The code is public, under Apache-2.0. The APK pairs are not: they are private draft releases, visible only to the owner, signed with the owner's key. There is no app-store or public-hosting deployment workflow.
