# Owner setup and device checks

1. Download and install one matching phone/watch APK pair. Open both apps once. Enable notifications and precise-alarm access on each device that should alert. Do not mix the local handoff with a different CI release.
2. Sign in on the phone with your existing ChatGPT account through the browser. Try phone voice, then watch voice, interruption and your headphones. Buddy's undocumented voice endpoint can refuse access; a subscription alone does not prove eligibility. No API key is needed.
3. Add the WatchGPT talk tile. Assign the watch's Home-button double press to WatchGPT in Samsung settings. Default-assistant / long-press replacement remains unverified.
4. Disconnect the phone and create a watch reminder due in a few minutes, targeting Watch. Verify its local alert and snooze/completion. Reconnect and check that creation, editing and deletion converge. Check a recurring item, reboot/unlock, timezone change and revoked/restored notification permission on your physical devices.
5. Try a native Clock alarm from an open screen and from the background. Background requests require tapping the WatchGPT notification and finishing any Clock confirmation. WatchGPT reports a request, not verified Clock creation.
6. Optional: choose a writable Google calendar on the phone, and verify an owned event's edit/deletion round trip through Google. Calendar copies are events, not Google Tasks; local reminders need no Google sync.
7. Optional: back up the private signing keystore and set WATCHGPT_SIGNING_KEYSTORE for matching future CI updates. Instructions are in private-signing/README.txt; the key stays outside Git. Without this secret, CI pairs can require reinstalling when their certificate changes. Preserve unsynchronized data before reinstalling.

No hosted database or new core subscription needs to be provisioned. GitHub Actions artifact storage is full; this handoff and private draft release downloads bypass that quota. Detailed installation and permissions are in INSTALL.md; limitations are in BEHAVIOR.md.
