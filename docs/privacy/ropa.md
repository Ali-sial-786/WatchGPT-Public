# Record of processing (GDPR Art. 30) and DPIA screening

Controller: Vinh Nguyen (VYDE), vyde.apps@gmail.com. Processors of ours: none (no servers). OpenAI,
Hugging Face, Google and the OpenStreetMap Foundation act under their own terms with the user when the
user uses those features.

| Processing | Purpose | Legal basis | Data subjects | Data | Recipients | Retention |
|---|---|---|---|---|---|---|
| Answering questions | Provide the app | Art. 6(1)(b) | App users | Questions/answers | OpenAI, only for ChatGPT answers | Session; History below |
| History and remembered facts | Remember what the user talked about and asked WatchGPT to keep | Art. 6(1)(b) (the user can switch it off and delete it) | App users; people they mention | Their words, WatchGPT's replies, facts they asked to keep | OpenAI, cleaned, at the start of each conversation | 30 days; facts until forgotten |
| Keeping the ChatGPT sign-in | Stay signed in | Art. 6(1)(b); § 25(2) TDDDG strictly necessary | App users | Tokens, account id, plan, masked email | None (on the phone) | Until sign-out |
| Gemini Nano | On-device answers | Art. 6(1)(a) consent; § 25(1) TDDDG | Users who opt in | ML Kit diagnostics | Google | Google's terms |
| Map of a place in the pop-up | Show where a place is | Art. 6(1)(b) | Users who ask for directions | Place name, IP address | OpenStreetMap Foundation (Nominatim, tile servers) | Not stored by us |
| Sleep answers | Tell the user how they slept, when they ask | Art. 6(1)(b); Art. 9(2)(a) explicit consent, given in Health Connect's permission screen, and for OpenAI by turning on Say it in ChatGPT's voice | App users | Sleep times and stages (health data) | None by default (read and told on the phone); OpenAI, only with Say it in ChatGPT's voice on | Not stored; with ChatGPT's voice on, ChatGPT's answer is kept in History on the phone, never sent again |
| Flagged answers | Safety (Play policy) | Art. 6(1)(f) | Users who flag | Answer, optionally the question | None yet (on the phone) | Last 50 |

## DPIA screening (2026-09-27)

New technology (AI) and users may type sensitive things, so the screening is recorded. Result for M1:
**no full DPIA needed yet** — we don't receive or store conversations, nothing is profiled, and the only
off-device processing is by providers the user chooses under their own account. Redo the screening
before M2 (voice from the watch) and before any server or history feature.

Sleep (2026-10-15) is health data (Art. 9). It's read only on the user's request and permission, on the phone, and
by default never leaves it and isn't stored, so it adds no off-device processing.

Screening redone (2026-10-15) for **Say it in ChatGPT's voice**, which sends the sleep summary to OpenAI so ChatGPT can
say it. It's off by default and needs its own explicit consent (Art. 9(2)(a)), given by turning it on next to Sleep;
it sends only when the user asks out loud, only to the provider they chose under their own ChatGPT account, and we
never receive it. Nothing is profiled, it isn't large-scale processing by us, and turning it off counts at once.
ChatGPT's answer then stays in History (30 days, encrypted on the phone) but is never sent to ChatGPT again. The result stands: **no
full DPIA needed**. Redo it if sleep ever goes anywhere else, or is kept beyond History.
