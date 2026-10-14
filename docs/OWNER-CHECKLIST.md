# Owner checklist

What only you can do, in order. Details are in [Install WatchGPT](getting-started.md); limits are in [Behavior](WATCHGPT_BEHAVIOR.md).

1. **Set the signing secret once.** Make the key, save it as `WATCHGPT_SIGNING_KEYSTORE` and back it up: [Signing](getting-started.md#signing-one-time-so-updates-install). The next push to `main` (or **Run workflow** on `main`) then publishes a pair that later pairs can update.
2. **Install one pair from one release.** Take the newest draft on the [Releases page](https://github.com/Ali-sial-786/WatchGPT/releases), check `SHA256SUMS`, and install both APKs from it: [Install](getting-started.md#install). If WatchGPT from an earlier pair is installed and adb refuses the update, see [If an install fails](getting-started.md#if-an-install-fails).
3. **Allow notifications on both devices.** Open **Reminders & agenda** on the phone and **Reminders** on the watch, and allow notifications. The red "Reminder notifications are off" line must be gone on both.
4. **Sign in and test voice.** On the phone, **Your AI → Sign in with ChatGPT** in the browser. Try phone voice, then watch voice, interrupting, and your headphones. The undocumented voice endpoint can refuse your account; a subscription alone doesn't prove access. No API key is needed.
5. **Tile and double press.** Add the WatchGPT talk tile. Assign the watch's Home-button double press to WatchGPT in Samsung settings. Default-assistant and long-press replacement stay unverified.
6. **Checks that need the real devices:**
   - **Watch alone.** Switch the phone off (or put the watch in airplane mode). On the watch, create a reminder due in a few minutes with **Alert on: Watch**. It must ring on the watch. Try **Snooze** and **Done**.
   - **Reconnect.** Switch the phone back on. What you did on the watch while apart must reach the phone. Then create, edit and delete items on each device, tap **Sync now**, and check that both devices end up with the same list.
   - **Reboot.** Set a reminder a few minutes ahead, restart the device and unlock it. The reminder must still ring. Do this on the phone and on the watch.
   - **Time zone.** Change the time zone in the phone's date and time settings. An item with **Keep local time when travelling** on keeps its clock time; one with it off keeps its moment in the saved zone.
   - **Do Not Disturb.** With Do Not Disturb on, see whether a reminder alerts. Android's Do Not Disturb rules decide this, not WatchGPT.
   - **Clock.** Ask for an alarm with WatchGPT on screen, and once from the background. The background one only happens after you tap WatchGPT's notification and confirm in Clock.
7. **Optional: Google Calendar.** On the phone, **Reminders & agenda → Google Calendar · optional**, allow calendar access and choose one of your Google calendars. Edit a WatchGPT copy in Google Calendar and check the change reaches the phone and the watch; delete a copy and check the item is deleted. Copies are events, not Google Tasks; reminders work without them.

No hosted database or new subscription needs to be set up.
