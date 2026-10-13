# Verification record

Work is being validated against the imported upstream plus WatchGPT changes. This file is updated with the final build and test results before delivery.

The repository includes JVM tests for reminder merge ordering, duplicate replay, offline deletion, monthly/weekday recurrence, early completion, DST gaps/overlaps, timezone policy, snooze and Calendar recurrence mappings. Android instrumentation exercises real SQLite/Keystore persistence and ciphertext, stale editor rejection, persistent delivery receipts, duplicate/stale notification delivery, Calendar opt-in, duplicate prevention, unrelated-event protection, remote edits and deletion.

The wake smoke test and its positive/negative synthetic fixtures are in `tools/wake-test`; the documented difficult eSpeak positive is intentionally retained as a recognition limitation.

Hardware-only verification remains necessary: the owner's ChatGPT sign-in and endpoint entitlement, a live watch conversation and interruption, microphone/speaker/headphone routes, Samsung double-press menu, actual battery/Doze behavior, paired phone/watch transport reconnect and Google server sync. Automated tests and previews do not establish those results.
