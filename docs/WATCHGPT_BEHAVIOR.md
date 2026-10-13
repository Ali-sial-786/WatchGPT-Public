# WatchGPT behavior and boundaries

## Reminders and agenda

Every item has a UUID, title, notes, wall-clock date/time, zone policy, recurrence, completion/deletion state, snooze time, and a selected alert destination. Both devices have the same native editor. Manual edits persist first to local encrypted SQLite and then queue sync. No Google account or internet is needed for local use.

Daily, weekdays, weekly, and monthly repeats are supported. Monthly day 29–31 clamps to the last valid day, retaining the original day for later months. Completing a repeated item advances the next due occurrence; deleting it removes the whole series. DST gaps move forward through the gap; overlaps use the first instance of that wall time, avoiding a second alert. “Keep local time when travelling” follows each device's current zone. Turn it off to retain the saved zone. Snooze is an absolute instant. Missed one-off reminders are delivered when scheduling resumes; recurring schedules resume at the next occurrence instead of replaying a backlog.

Alarms are local to the chosen alert device: **Watch**, **Phone**, or **Both**. Default manually created items target the local device; voice-created items target the conversation device. Watch-targeted items created on the phone must synchronize before the watch can alert. There is deliberately no automatic phone failover while disconnected: it cannot know if the watch already alerted. “Both” explicitly requests two alerts. Notifications set `localOnly` so the phone's reminder notification is not mirrored to the watch as an extra alert.

Android may delay inexact alarms, background jobs or notifications under power saving, DND, disabled notification channels, missing permissions, or force-stop. Exact access reduces delays; it is not an absolute delivery guarantee. Reminder scheduling resumes after boot/unlock, time and zone changes, app update, foreground use, and periodic recovery. Android does not permit ordinary apps to restart after a user force-stop until reopened.

## Device synchronization

Wear Data Layer authenticates the matching application IDs and signatures. Each item is a persistent DataItem. A device-specific Lamport counter orders revisions without trusting wall-clock agreement. Equal revisions use stable author IDs, then a deterministic payload comparison. A deletion always wins over an offline edit, even one with a higher counter. Tombstones are retained because arbitrarily long offline peers cannot safely forget them. Conflicting field edits resolve at the whole-item level; the losing edit is not combined field by field. Stale open editors are rejected so they cannot unknowingly overwrite a received edit.

“Sent to Wear Data Layer” means accepted by the local transport with a connected peer, not an end-to-end receipt from the other app. Offline/pending/error states are shown; open both apps and use Sync now when diagnosing. WorkManager and the data listener retry and recover. Bluetooth pairing/Google Play services are required; a disconnected LTE watch can still manage its own reminders but does not independently connect to ChatGPT.

Local data uses Android Keystore AES-256-GCM with each UUID bound as authenticated data; application backups are disabled. Sign-in and existing memory protections remain upstream's. Wear sync uses Google's authenticated transport, which can use Google's relay service. Google Calendar copies, when enabled, live under Google's account and calendar policies. Delete everything queues tombstones; reconnect the other device to finish deletion there. Uninstalling both apps is different: it removes their local keys/data, but does not guarantee erasure of old Google calendar copies.

## Google Calendar

Google sync is opt-in, phone-only, and restricted to the selected visible writable Google calendar. The app creates copies with a `[WatchGPT:UUID]` marker. It never enumerates unrelated records for editing. Stable links plus marker lookup recover an interrupted insert without inserting a second copy. A removed marker or moved event pauses sync rather than changing an unrelated entry. Switching calendars or disabling sync leaves existing copies in the previous calendar; the selection screen explains this.

Supported remote title, notes, date/time, duration, timezone and recurrence edits update WatchGPT and then the watch. A deletion of a linked owned event deletes the WatchGPT item. Simultaneous local/calendar edits keep the WatchGPT version, with a visible conflict count. Unsupported recurrence (for example custom intervals, COUNT, UNTIL, yearly rules or individual-instance exceptions) is left pending and must be simplified in Calendar; it is never silently approximated. One-off and all-day events are supported. Monthly rules encode end-of-month clamping explicitly.

Copies have no Calendar reminders: WatchGPT controls alerts. Completing a one-off item removes its calendar copy; completing a recurring item advances its series. Reminders are represented as events, not Google Tasks. The calendar provider handles Google cloud transfer separately: “Written to Android Calendar” confirms only the local provider write, not successful Google server synchronization. Device and Calendar progress are shown separately. Background calendar checks occur periodically and when WatchGPT opens or syncs; they are not instant push subscriptions.

## Voice and native alarms

The preserved Buddy architecture uses ChatGPT/Codex account OAuth and an undocumented backend realtime endpoint (`/backend-api/codex/realtime/calls`, `intent=quicksilver`, `architecture=avas`). This is not a documented public ChatGPT subscription voice SDK. Official Sign in with ChatGPT offerings do not establish support for this particular endpoint. OpenAI can change the protocol, limits or eligibility without notice. No API key, API billing fallback, new core subscription or hosted database is introduced. Real access requires the owner's interactive sign-in and a real voice test.

Audio interruption, local memory, conversations, tools and upstream privacy gates are retained. Reminder commands now save native records. Native Clock intents support repeated weekdays when the installed Clock app honors `EXTRA_DAYS`. Foreground commands report **requested**, not verified creation. Background commands produce a notification that the user must tap to open Clock and confirm. Android offers no general API to read a third-party Clock app's alarms and verify creation. Clock notifications expire after 15 minutes.

The watch remains a companion with a tile, launcher/double-press activation, and local wake recognition. It is not advertised as replacing Samsung's system assistant. No live-voice or Watch9 hardware behavior is considered verified just because an APK compiles.
