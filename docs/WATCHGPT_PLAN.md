# WatchGPT implementation plan

Source: thenguyentrong/watch-ai, commit ccf9dd26a28badac5f78debc11d5079e13161ddf.
Destination: Ali-sial-786/WatchGPT (private). Preserve upstream history, Apache-2.0 and third-party notices.

1. Rebrand both existing apps, keep subscription voice and mascot, matching app identity/signing.
2. Shared pure Kotlin reminder/event model, recurrence and deterministic conflict resolution; encrypted SQLite on both devices.
3. Persistent Wear Data Layer records, deletion tombstones, reconnect reconciliation, native local alerts and reboot recovery.
4. Phone and round-watch reminder/agenda editors with date, time, recurrence, snooze, completion and notification permissions.
5. Opt-in Calendar Provider projection to an explicitly selected Google calendar, owned-record reconciliation and sync status.
6. Native reminder voice tools, configurable voice timeout, recurring alarms, truthful clock handoff and activation shortcuts.
7. Automated unit checks, lint, paired signed APKs, CI artifacts, UI previews and device-only verification instructions.

Decisions: no cloud database; two apps, reminders integrated into each; local records are authoritative; calendar sync only touches explicitly owned projections. Conflicting concurrent edits use logical revision and device ID, deletion wins permanently. Each reminder selects its alert device; no disconnected automatic failover that could cause duplicate alerts. Cloud voice remains dependent on the phone and undocumented provider access.
