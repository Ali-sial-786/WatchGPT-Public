# Troubleshooting

Common problems and what to do about them.

---

### The watch and the phone don't find each other

- The watch has to be paired with the phone in the Galaxy Wearable or Wear OS app.
- Both apps have to be signed with the **same key**, or the Data Layer won't connect them. Install
  both APKs from the same release, or build both on the same machine (`:app:installDebug` and
  `:wear:installRelease` use the same debug key).

### `INSTALL_FAILED_UPDATE_INCOMPATIBLE`

adb refused the update because the installed WatchGPT was signed with a different key. CI pairs made
without the signing secret each have their own key.

- Set the `WATCHGPT_SIGNING_KEYSTORE` secret once, so every later pair has the same key:
  [Signing](getting-started.md#signing-one-time-so-updates-install).
- To install a pair with a different key, uninstall WatchGPT on that device first. **This deletes
  the reminders stored on it.** Sync both devices first (**Sync now**): the other device sends its
  reminders back only if both end up with apps signed by the same key. When you move both devices to
  a new key, note any reminders you need first.

### A reminder didn't ring on the watch

- **Notifications on the watch.** Open **Reminders** on the watch. If it says "Reminder notifications
  are off", tap **Enable alerts** and allow them.
- **Alert device.** Open the item: **Alert on** must be **Watch** or **Both**. By default, items made
  by hand alert on the device they were made on, and items made by voice on both unless you said one.
- **Sync.** An item made on the phone reaches the watch only after a sync. Check the status line at
  the bottom of the reminders screen on both devices, and tap **Sync now** with both nearby.
- A missed alert is still delivered late for 12 hours (one-off) or 1 hour (a repeat's occurrence),
  counted from the time it is for, then skipped.

### The status says "N item(s) … could not be read"

"N item(s) from your watch (or phone) could not be read; update both apps": the other device sent
items this version can't read, for example because it runs a newer WatchGPT. Install both APKs from the
same, newest release. The rest keeps syncing meanwhile. "N stored item(s) could not be decrypted"
means rows on this device itself can't be read; the other items still work.

### The watch app is slow

Install the **release** build on the watch: `gradlew :wear:installRelease`. Debug builds are much slower
on a watch.

### Answers are too quiet on the watch

Turn the bezel (or the crown) while WatchGPT is talking: the face shows the volume for a moment. Answers play
as a call, so the watch's echo cancelling lets you talk over them; that's why the media and assistant volume
sliders don't change them, and the watch's own call volume is only offered during a phone call. The level you
set stays for the next conversation (and for calls on the watch).

Call and media volume are separate, each with its own top level. If the call volume at its top is still too quiet,
switch on **Settings → Answers on the watch → Play answers as media** on the phone: answers then follow the media
volume (the bezel sets it too), which may go louder, but without echo cancelling the watch listens again only once an
answer has finished, so you can't talk over it: tap the face to stop the answer, then talk. The switch counts at once,
also in a conversation already on.

### Play answers as media still sounds like a call

- The switch counts at once, also mid-conversation (older builds waited for the next conversation).
- Look at the face while WatchGPT talks: as media it says **Speaking · tap to interrupt**, as a call just **Speaking**.
  Turning the bezel shows **Media volume** or **Call volume**. As a call or as media, the voice is the same, sent from
  the phone in the same quality, so it can sound much the same either way.
- With earbuds on the phone, answers play there whatever this switch says: set their volume on the phone.
- Install both apps from the same release: an older watch app doesn't know the setting and always plays as a call.

### Music starts when I turn the volume down

Set WatchGPT's volume with the bezel (or the crown) on WatchGPT's face while it talks; on a Galaxy Watch without a
turning bezel, switch on Touch bezel in the watch's settings and swipe around the edge. On WatchGPT's face this only
changes the volume, never presses play. The watch's own volume settings may play a sample of music while you drag the
media slider, and with answers as media the watch's media controls may come up over WatchGPT and start the last song.
Asked to talk quieter, WatchGPT is told to point you to the bezel, not to change the phone's volume or press play. If
music still starts, note how you turned it down and which app played.

### "Hey Watch G P T" (or my own phrase) doesn't hear me

- By default the watch listens after you raise your wrist. Raise it, then say it.
- Open **Learn my voice** on the watch and read the five sentences. WatchGPT keeps only the spellings it
  heard, not your recordings. Run it again after **Change phrase**: a new phrase starts without it.
- **Always listening** keeps it on all the time. It uses more battery.
- With your own phrase, a name or an unusual word makes it wake by mistake less often.

### WatchGPT can't see or use my phone's screen

- Using an app for you needs **WatchGPT** switched on in the phone's Settings → Accessibility → Installed apps.
- The phone has to be unlocked with its screen on: Android shows nothing else to a locked phone. Talking through
  the watch with the phone in a pocket, WatchGPT says so and asks you to unlock it. An app it opened while the phone
  was locked waits behind the lock screen.
- Each app has to be allowed once (WatchGPT asks). Banking, payment, password and system apps never are.

### WatchGPT can't read my messages

- Allow **notification access** in "What WatchGPT can do". WatchGPT reads messages from their notifications,
  keeps them in memory only, and for at most 6 hours.
- Banking, payment, password and authenticator apps are skipped on purpose.
- WatchGPT can reply to a chat that has a notification, but can't start a new WhatsApp chat hands-free.

### WatchGPT can't tell how I slept, or what my sleep score is

- Allow **Sleep** in "What WatchGPT can do"; Health Connect asks. After two no's it stops asking; then the button
  opens Health Connect's settings: open App permissions, then WatchGPT, and allow Sleep.
- Samsung Health has to share your sleep: **Samsung Health → Settings → Health Connect**, let it write Sleep.
  Last night can take a while to get there after you wake up; opening Samsung Health sends it. Until then WatchGPT
  tells you the night before, and says last night isn't in yet.
- If WatchGPT says Health Connect only lets it read while it's open: that happens when no conversation is running,
  for example after leaving the chat while it answers. Open WatchGPT on the phone and ask again, or allow WatchGPT's
  access in the background in Health Connect (App permissions, then WatchGPT), where your phone's Health Connect has it.
- There's no sleep score: Samsung Health keeps it to itself and doesn't share it through Health Connect. WatchGPT
  says so and tells you when you slept, for how long, and your sleep stages. With the phone unlocked, you can also ask
  it to open Samsung Health and read the score off its screen.

### WatchGPT tells me my sleep in a different, robotic voice

- That's your phone's own voice, on purpose: your sleep is health data, so it stays on the phone and ChatGPT never
  gets it, and ChatGPT's voice can only say what ChatGPT gets.
- To hear it in ChatGPT's voice, turn on **Say it in ChatGPT's voice** under Sleep in "What WatchGPT can do". Your
  sleep then goes to ChatGPT (OpenAI, under your account) when you ask out loud. It counts at once. In the chat your
  phone still shows it on screen.
- "Private things stay on this phone" in Settings doesn't change this; it covers messages, notes and the calendar.
- A score read off Samsung Health's screen still comes in your phone's voice, even with this on: what's on an app's
  screen always stays on the phone.

### Nothing was sent after I said yes

A text or call waits for your yes **in a later turn**, **within two minutes**, **while you're there**
(watch unlocked on your wrist, phone unlocked, or earbuds in), and at most ten an hour. If one of those
doesn't hold, the gate refuses. "What WatchGPT did" shows what happened.

### Private answers take long the first time

The on-device model has to load: about 9 to 11 s cold on the S23 Ultra, about 3 s warm. WatchGPT warms it
at the start of each conversation. Without an offline model, the phone reads the items out plainly.

### WatchGPT Plus isn't there

The build has no RevenueCat key. See [Optional: WatchGPT Plus](getting-started.md#optional-watchgpt-plus). A
Test Store key only goes into debug builds; release builds leave Plus out.

### Sign-in doesn't come back from the browser

Use the device code option instead. It needs **"Allow device code login"** in your ChatGPT security
settings.

### Starting the phone app from adb says "Activity class does not exist"

The app only accepts launches that match its intent filter. Add the launcher action and category:

```bash
adb shell am start -n io.github.alisial786.watchgpt/.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER
```

### The build picks the wrong Java

Gradle's daemon uses the JDK 17 from `gradle/gradle-daemon-jvm.properties`. If your shell starts
Gradle with an older Java anyway, set `JAVA_HOME` to a JDK 17 for that command.

### Windows blocks a tool

Smart App Control can block unsigned tools, for example the Java runtime bundled with Android's newest
command-line tools. The command-line tools 22.0 work.
