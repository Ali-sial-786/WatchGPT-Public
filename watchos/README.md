# WatchGPT for Apple Watch

A watchOS version of WatchGPT: the same mascot, the same ChatGPT sign-in and safety gate, and reminders, notes, memory
and sleep, on an Apple Watch. **It has no iPhone app.** It runs on the watch alone (a watch-only app, watchOS 10 or
later) and reaches ChatGPT over the watch's own connection: through the paired iPhone, Wi-Fi or cellular.

> Not yet run on a real Apple Watch. The shared code's tests pass on Linux, and the `apple-watch` CI job runs them on
> macOS and builds the app for watchOS without signing. Sign-in, answers, speech and notifications have to be checked
> on your own watch.

## Why there is no iPhone app

On Android the phone does the work because that is where WebRTC voice, the Gemma model and the phone's actions live.
None of that carries over to an iPhone app here:

- **Apps can't do those things on iOS.** No app may read other apps' notifications, send texts by itself, use other
  apps, or set the ringer or Do Not Disturb.
- **Audio can't usefully go through the iPhone.** Watch Connectivity has no live audio link, so the watch would still
  do the listening and speaking itself.
- **The watch can do the rest alone.** Sign-in with a code, ChatGPT's answers, reminders and timers (notifications),
  sleep (Apple Health), the calendar, contacts and calls all work on the watch.

An iPhone app would only add a second install and a second place to sign in, so there isn't one.

## What it does

| | Apple Watch | Compared with Android and Wear OS |
|---|---|---|
| Talk | Tap WatchGPT (or double tap, the complication, "Talk to WatchGPT" with Siri, or the Action button). The watch's dictation hears you, ChatGPT answers, and the watch says it sentence by sentence, then listens again until you say bye or stay quiet for the time set in Settings (10 seconds to 15 minutes, or until you stop). | Android uses ChatGPT's realtime voice (GPT-Live over WebRTC). WebRTC isn't on watchOS and watchOS limits the networking it needs, so here ChatGPT answers in text through the same Codex Responses endpoint the Android chat uses, and the watch's own voices speak it. |
| Wake phrase | None. | watchOS doesn't let apps listen in the background, so "Hey Watch G P T" can't exist here. |
| ChatGPT sign-in | Code on the watch: enter it at auth.openai.com/codex/device on your iPhone or a computer. | The same OAuth client as Android's device-code sign-in. The same account gets the same WatchGPT (the mascot's seed comes from the account id). |
| Safety gate | The same levels: unknown tools refused, bye stops everything but lookups, calls and texts read back and done only after your yes in a later turn, within two minutes. | Ported from `core/brain` (`Guard`, `Redactor`, `Pending`). |
| Private readout | Notes, reminders, the calendar and sleep are read out by the watch itself, with codes, numbers and links left out unless you asked for one; ChatGPT only hears "Here you go". Switches: **Private things stay on this watch**, **Sleep in ChatGPT's voice**. | The phone's fallback without the Gemma model: there's no on-device model on the watch. |
| Reminders and events | Daily, weekdays, weekly and monthly repeats, Done and Snooze 10 min on the notification, all-day items at 09:00, advance alerts for events, by voice or on the Reminders screen. | The same rules (`core/reminders` ported). Kept on the watch only: no sync with an Android phone (an Apple Watch can't pair with one) and no Google Calendar copies. |
| Timers | A WatchGPT notification after the time you said. | Not the Timer or Alarms app: watchOS gives apps no way to set those. |
| Calendar | Reads the watch's calendar (the iPhone's calendars) for "what's on today". | |
| Sleep | Apple Health: when you fell asleep and woke up, deep, core and REM sleep, wakings. | Health Connect on Android. No sleep score either way. |
| Calls and texts | By contact name or number, read back, then the watch's Phone or Messages opens; a text goes when you tap Send. | Android sends after your yes. |
| Memory | "Remember that…", forget, earlier conversations (30 days), kept on the watch. | The same. |
| Not here | Reading or answering messages from other apps, music and volume, ringer, Do Not Disturb, finding the phone, flashlight, opening or using apps, directions, WatchGPT Plus. | watchOS gives apps no access to these. |

## Install

You need a Mac with **Xcode 16 or later**, an **Apple Watch with watchOS 10 or later** paired with an iPhone, and an
Apple ID. A free Apple ID works; the app then has to be installed again from Xcode every 7 days (a paid Apple
Developer membership makes that a year).

1. Turn on Developer Mode on the iPhone (Settings → Privacy & Security → Developer Mode) and, once Xcode has seen the
   watch, on the watch too (Settings → Privacy & Security → Developer Mode).
2. Clone this repository and open `watchos/WatchGPT.xcodeproj`.
3. Create `watchos/Config/Local.xcconfig` (it isn't committed) with your team and a bundle id of your own:

   ```
   DEVELOPMENT_TEAM = ABCDE12345
   WATCHGPT_BUNDLE_ID = com.yourname.watchgpt
   ```

   The team id is in Xcode → Settings → Accounts, or pick the team under Signing & Capabilities for both targets.
4. Choose the **WatchGPT** scheme and your watch as the run destination, and press Run. The first install over the
   iPhone takes a few minutes.
5. On the watch, open WatchGPT → Settings → **Sign in**, and enter the code it shows at auth.openai.com/codex/device
   on your iPhone or a computer. If the watch says sign-in with a code is off, check device code sign-in in your
   ChatGPT security settings (a workspace admin can turn it off).
6. Allow what you want WatchGPT to use in Settings: notifications (reminders and timers), sleep, calendar, contacts.

If Xcode says your team can't use HealthKit, remove the HealthKit capability (Signing & Capabilities, or the two
keys in `App/WatchGPT.entitlements`). Everything but sleep keeps working.

## Starting a conversation

- Tap WatchGPT. Tap again to stop.
- Double tap: index finger and thumb together twice (Series 9, Ultra 2 and later, watchOS 11).
- The **Talk to WatchGPT** complication or Smart Stack widget.
- "Talk to WatchGPT" with Siri, or a shortcut: on Apple Watch Ultra, Settings → Action Button → Shortcut.
- **Type instead** under WatchGPT, when dictation isn't there.

## Privacy

WatchGPT never records audio: it only gets the words from the watch's dictation, which is Apple's (on the watch or on
Apple's servers, depending on the watch and language). Your words and the tool calls go from the watch straight to
OpenAI under your own ChatGPT account. The sign-in is in the watch's keychain (this device only); reminders, notes,
memory and conversations are files protected by the watch's encryption and left out of backups. Answers are spoken
by the watch's own voices, on the watch. See [PRIVACY.md](../PRIVACY.md#watchgpt-on-apple-watch).

## How it's built

| Folder | What |
|---|---|
| `WatchGPTKit/` | Swift package, plain Foundation, tested on Linux and macOS: ChatGPT device-code sign-in and token refresh (`ChatGPT/`), Responses streaming with tools, the Guard, Redactor and Pending (`Guard/`), memory and notes (`Memory/`), reminders, recurrence and the alert plan (`Reminders/`), the sleep report (`Health/`), the watch's tools (`Tools/`), the mascot engine (`Buddy/`, ported from `core/buddy`, itself from bloub, MIT) and the sentence splitter (`Voice/`). |
| `App/` | The watch app (SwiftUI): the face (`FaceView`, a Canvas), the conversation (`Conversation`, `Dictation`, `Speaker`), sign-in, settings, reminders, notifications, HealthKit, EventKit, Contacts, the keychain, the Siri intent. |
| `Complication/` | The WidgetKit complication. |
| `project.yml` | The [XcodeGen](https://github.com/yonaskolb/XcodeGen) spec `WatchGPT.xcodeproj` is generated from. After changing it, run `xcodegen generate` in this folder and commit the project. |

Tests: `swift test --package-path watchos/WatchGPTKit` (any Mac with Xcode, or Linux with Swift 5.9 or later).
Build: `xcodebuild -project watchos/WatchGPT.xcodeproj -scheme WatchGPT -destination 'generic/platform=watchOS' CODE_SIGNING_ALLOWED=NO build`.
