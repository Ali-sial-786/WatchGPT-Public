# WatchGPT

Private Android + Wear OS fork of [Buddy / watch-ai](https://github.com/thenguyentrong/watch-ai), for a Galaxy phone and watch. Both apps use the name **WatchGPT** and application ID `io.github.alisial786.watchgpt`. The original mascot, animation engine, voice bridge, local models, conversation history, and privacy controls are retained.

**Live voice is experimental.** This fork retains Buddy's ChatGPT account sign-in and undocumented realtime endpoint. It uses no OpenAI API key and has no automatic paid fallback. A ChatGPT subscription does not guarantee that endpoint is available. Sign-in, live voice, headphones, and Samsung button behavior must be verified on your own devices. WatchGPT is an independent project, not an OpenAI or Samsung app.

## What's added

- Native reminders and agenda on the watch and phone: create, edit, complete, delete, snooze, notes, date/time, recurring schedules, and alert destination. Manual use works without the phone or internet.
- Encrypted on-device SQLite storage; Wear Data Layer synchronization, deterministic concurrent-edit resolution, deletion records, reboot/time-change rescheduling, and persistent alert receipts.
- Optional Google Calendar copies through the phone's existing Calendar Provider. Choose a writable Google calendar explicitly. These are calendar events, **not Google Tasks**. No hosted database, server account, or extra subscription is required.
- Configurable voice inactivity timeout: 30 seconds to 15 minutes, or until stopped.
- “Hey Watch G P T” wake phrase, existing voice training, launcher, talk tile, and Samsung double-press setup.
- Native Clock requests with recurring weekdays and accurate results: requested, needs a notification tap, or failed. Android does not provide proof that its Clock app created an alarm.

## Use and build

[Installation and permissions](docs/getting-started.md) · [Completed screen previews](docs/screenshots/README.md) · [Behavior and limitations](docs/WATCHGPT_BEHAVIOR.md) · [Implementation plan](docs/WATCHGPT_PLAN.md) · [Verification](docs/WATCHGPT_VERIFICATION.md)

```sh
git clone https://github.com/Ali-sial-786/WatchGPT.git
cd WatchGPT
git remote add upstream https://github.com/thenguyentrong/watch-ai.git
./gradlew spotlessCheck checkSyntheticFixtures test testDebugUnitTest lintDebug :app:assembleRelease :wear:assembleRelease
```

JDK 17 and Android SDK 37 are required; the Gradle wrapper and SDK manager resolve the pinned dependencies. Phone and watch APKs must use matching signing certificates. An optional private CI signing secret preserves the installation identity across builds; owner setup is documented. CI builds both APKs together and stores verified main-branch pairs in private draft releases, with an additional private Actions upload attempt. The owner account currently has no artifact quota available; private draft releases and the local handoff bypass that quota. Download both APKs from the same release or handoff. Never install a watch build from one job with a phone build from another.

## Upstream and licensing

Imported with full upstream history from `ccf9dd26a28badac5f78debc11d5079e13161ddf`. Original work by Vinh Nguyen and upstream contributors. Original mascot sources and third-party acknowledgments are preserved. See [LICENSE](LICENSE), [NOTICE](NOTICE), and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). WatchGPT changes are provided under the same Apache-2.0 license. Original screenshots and historic design documents under `docs/media` describe upstream; they are not evidence of WatchGPT device testing.

This repository is private. There is no app-store or public-hosting deployment workflow.
