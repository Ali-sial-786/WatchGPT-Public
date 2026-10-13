# WatchGPT verification

The fork imports upstream commit `ccf9dd26a28badac5f78debc11d5079e13161ddf` with its full history. LICENSE, NOTICE, and THIRD_PARTY_NOTICES.md were compared against upstream and remain unchanged.

## Automated checks completed

- 248 unit-test executions across the fork and retained upstream modules: zero failures or errors. This includes reminder recurrence, month-end completion, weekdays, DST gaps and overlaps, travel-zone policy, snooze/completion, duplicate replay, conflicting offline edits, deletion precedence, and supported Calendar recurrence round trips.
- Formatting, synthetic-secret fixture checks and Android lint passed. The phone and watch release APKs compile and shrink successfully.
- Seven Android integration tests passed against Android 15, using actual SQLite, Android Keystore, notification services, and Calendar Provider. They cover ciphertext/persistence after database reopen, stale editor rejection, duplicate merge/deletion protection, persistent delivery receipts, stale and duplicate notification handling, opt-in Calendar writes, remote Calendar edits/deletion, protection of unrelated events, and manual monthly reminder creation through the native editor.
- APK signatures verified and match. Both application IDs are `io.github.alisial786.watchgpt`. APK ZIP alignment and all bundled ARM64 ELF LOAD segment alignments meet 16 KB requirements.
- The wake smoke test recognizes the natural synthetic “Hey Watch G P T” fixture and rejects the negative fixture. A robotic eSpeak positive is a recorded miss, retained as a limitation.
- GitHub secret scanning and source/dependency scans passed after the inherited CI-only PyJWT dependency was updated to 2.15.0. Artifact storage is exhausted on the GitHub account; report and APK upload failures are reported without hiding test failures.

The final Android UI suite recorded 7 tests, 0 failures and 0 errors in [run 38424993120](https://github.com/Ali-sial-786/WatchGPT/actions/runs/38424993120), and captured nine screens after the small-screen layout refinements. The retained [screen previews](screenshots/README.md) show the actual Compose interfaces. Output remains in the job log if artifacts are unavailable. Screenshots use synthetic records only.

## APK pair

Phone: `app/build/outputs/apk/release/app-release.apk` (about 41 MB).
Watch: `wear/build/outputs/apk/release/wear-release.apk` (about 52 MB).

Shared installation certificate SHA-256:
`b0a5e0cf242d82ac3ef0c5dc14ceebf04feebae91c31a763e5cf830b4e4dd860`

The handoff folder contains copies named `WatchGPT-phone.apk` and `WatchGPT-watch.apk`, plus `SHA256SUMS`. These are optimized private sideload builds, signed with the same developer test certificate. Release screenshot protection remains enabled. Signing key material is not in Git.

## Checks that need the owner's devices

These were not performed on a physical Watch9 or S24 Ultra:

1. Install the matching pair, grant notifications and precise-alarm access, create a watch reminder with the phone disconnected, then reconnect and verify edits/deletions converge.
2. Verify actual notifications after watch/phone reboot and unlock, timezone changes, DND/power-saving settings and permission changes. Database reopen tests establish persistence; they do not prove Samsung's physical reboot or battery behavior.
3. Sign in with the owner's ChatGPT account on the phone and verify live voice, interruption, headphone routing, usage-limit handling and reconnect. Buddy's undocumented voice endpoint may refuse the account or change; no live subscription entitlement is inferred from a build.
4. Add the tile and manually assign Samsung's double-press shortcut. Long-press/default-system-assistant replacement remains unverified.
5. If desired, choose a writable Google calendar and verify an owned event's edit and deletion round trip through Google's servers. Automated Calendar Provider tests verify local integration, not Google cloud delivery.
6. If repeatable CI updates are desired, the owner must set `WATCHGPT_SIGNING_KEYSTORE` from the backed-up local keystore. This task's repository connection cannot write Actions secrets. Until then, fresh CI runners create a new matching certificate pair.
