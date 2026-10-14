# WatchGPT verification

The fork imports upstream commit `ccf9dd26a28badac5f78debc11d5079e13161ddf` with its full history. LICENSE, NOTICE, and THIRD_PARTY_NOTICES.md were compared against upstream and remain unchanged.

This file has two parts: the review of Codex's work and its fixes (newest first), then Codex's original record, kept as history. What needs the owner's own devices is in the [owner checklist](OWNER-CHECKLIST.md).

## Review and fixes (2026-10-14)

A review of Codex's commits (`ccf9dd2..f2f2e30`) found bugs in alerts, storage, watch sync, Calendar sync, the reminders screen and the voice tools. The fixes are commits after `f2f2e30`; their messages describe each change, and [Behavior](WATCHGPT_BEHAVIOR.md) describes the result.

- Pending CI results — filled in after the run

## Codex's original verification (2026-10-13)

> **Superseded in part.** The certificate fingerprints and draft-release links below belong to pairs Codex produced. Each CI run without the `WATCHGPT_SIGNING_KEYSTORE` secret signed with a different key, so none of those pairs can be updated by a later one. Use the newest draft on the [Releases page](https://github.com/Ali-sial-786/WatchGPT/releases) and set up [Signing](getting-started.md#signing-one-time-so-updates-install). Corrections from the review are marked *Review:*.

### Automated checks completed

- 248 unit-test executions across the fork and retained upstream modules: zero failures or errors. This includes reminder recurrence, month-end completion, weekdays, DST gaps and overlaps, travel-zone policy, snooze/completion, duplicate replay, conflicting offline edits, deletion precedence, and supported Calendar recurrence round trips.
- Formatting, synthetic-secret fixture checks and Android lint passed. The phone and watch release APKs compile and shrink successfully.
- Seven Android integration tests passed against Android 15, using actual SQLite, Android Keystore, notification services, and Calendar Provider. They cover ciphertext/persistence after database reopen, stale editor rejection, duplicate merge/deletion protection, persistent delivery receipts, stale and duplicate notification handling, opt-in Calendar writes, remote Calendar edits/deletion, protection of unrelated events, and manual monthly reminder creation through the native editor. *Review:* stale editor rejection was tested in storage only; the reminders screen always saved on top of the newest version, so it overwrote changes from the other device. The fix (commit `6482f84`) made the screen keep the version it opened, so the rejection now holds in the app.
- APK signatures verified and match. Both application IDs are `io.github.alisial786.watchgpt`. APK ZIP alignment and all bundled ARM64 ELF LOAD segment alignments meet 16 KB requirements.
- The wake smoke test recognizes the natural synthetic “Hey Watch G P T” fixture and rejects the negative fixture. A robotic eSpeak positive is a recorded miss, retained as a limitation.
- GitHub secret scanning and source/dependency scans passed after the inherited CI-only PyJWT dependency was updated to 2.15.0. At the time, Actions artifact storage on the account was full; report and APK upload failures were reported without hiding test failures.

The final Android UI suite recorded 7 tests, 0 failures and 0 errors in [run 38424993120](https://github.com/Ali-sial-786/WatchGPT/actions/runs/38424993120), and captured nine screens after the small-screen layout refinements. The [screen previews](screenshots/README.md) show the Compose interfaces as captured then. Screenshots use synthetic records only.

### APK pair

Phone: `app/build/outputs/apk/release/app-release.apk` (about 41 MB).
Watch: `wear/build/outputs/apk/release/wear-release.apk` (about 52 MB).

Codex's own build had installation certificate SHA-256 `b0a5e0cf242d82ac3ef0c5dc14ceebf04feebae91c31a763e5cf830b4e4dd860`. Its copies stayed in Codex's working environment and were never available to the owner. These were optimized private sideload builds, signed with a developer test certificate. Release screenshot protection remains enabled. Signing key material is not in Git.

### Checks that needed the owner's devices

These were not performed on a physical Watch9 or S24 Ultra. The current list is the [owner checklist](OWNER-CHECKLIST.md).

1. Install the matching pair, grant notifications and precise-alarm access, create a watch reminder with the phone disconnected, then reconnect and verify edits/deletions converge. *Review:* precise-alarm access is no longer a step; exact alarms are granted at install on Android 13 and later.
2. Verify actual notifications after watch/phone reboot and unlock, timezone changes, DND/power-saving settings and permission changes. Database reopen tests establish persistence; they do not prove Samsung's physical reboot or battery behavior.
3. Sign in with the owner's ChatGPT account on the phone and verify live voice, interruption, headphone routing, usage-limit handling and reconnect. The undocumented voice endpoint may refuse the account or change; no live subscription entitlement is inferred from a build.
4. Add the tile and manually assign Samsung's double-press shortcut. Long-press/default-system-assistant replacement remains unverified.
5. If desired, choose a writable Google calendar and verify an owned event's edit and deletion round trip through Google's servers. Automated Calendar Provider tests verify local integration, not Google cloud delivery.
6. If repeatable CI updates are desired, the owner must set `WATCHGPT_SIGNING_KEYSTORE`. Codex's repository connection could not write Actions secrets. Until then, every fresh CI runner creates a new certificate.

### Private repository delivery

Completed source and the nine previews were pushed to `main`. Main-branch CI also created a private draft release containing a matching APK pair, checksums, and installation notes.

The final [main-branch CI run 38428337005](https://github.com/Ali-sial-786/WatchGPT/actions/runs/38428337005), at commit `8c30d1841f1fb7f5206ff2ddbddc49cbab859df1`, completed successfully: builds, unit checks, lint, secret/source/dependency scans, signature/alignment checks, and 7 Android tests with 0 failures or errors. Both APKs, SHA256SUMS, INSTALL.md and BEHAVIOR.md were uploaded to a private draft release (link removed: superseded, and CI now keeps only the newest two drafts). That CI pair had certificate `994b8060e8b6bd87ae58d853b2aaf983d98df72873f33a5f450022e7f24804e5`, different from Codex's own build. The completion commit that added the download links changed documentation only.
