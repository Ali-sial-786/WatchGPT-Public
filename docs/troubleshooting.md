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
  then skipped.

### The status says "N item(s) … could not be read"

"N item(s) from your watch (or phone) could not be read; update both apps": the other device sent
items this version can't read, for example because it runs a newer WatchGPT. Install both APKs from the
same, newest release. The rest keeps syncing meanwhile. "N stored item(s) could not be decrypted"
means rows on this device itself can't be read; the other items still work.

### The watch app is slow

Install the **release** build on the watch: `gradlew :wear:installRelease`. Debug builds are much slower
on a watch.

### "Hey Watch G P T" doesn't hear me

- By default the watch listens after you raise your wrist. Raise it, then say it.
- Open **Learn my voice** on the watch and read the five sentences. WatchGPT keeps only the spellings it
  heard, not your recordings.
- **Always listening** keeps it on all the time. It uses more battery.

### WatchGPT can't read my messages

- Allow **notification access** in "What WatchGPT can do". WatchGPT reads messages from their notifications,
  keeps them in memory only, and for at most 6 hours.
- Banking, payment, password and authenticator apps are skipped on purpose.
- WatchGPT can reply to a chat that has a notification, but can't start a new WhatsApp chat hands-free.

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
