# NutriMom Assistant

Assistant automatically loads the signed-in account's context after login, before the chatbox opens. It can explain website workflows, retrieve published knowledge, summarize the account and suggest next steps from current data. The backend always derives identity from JWT. It has no model-generated SQL, arbitrary-user lookup, external browsing, file download, diagnosis, prescribing, or data-writing tools.

## Enable Groq

1. Create a free API key in [Groq Console](https://console.groq.com/keys).
2. Enable Zero Data Retention for inference in Console → Data Controls. [Groq data controls](https://console.groq.com/docs/your-data).
3. Set these variables in the **backend** `.env` or deployment secrets:

```properties
GROQ_API_KEY=your_key_here
GROQ_ZDR_CONFIRMED=true
NUTRIMOM_ASSISTANT_ENABLED=true
NUTRIMOM_ASSISTANT_MODEL=openai/gpt-oss-20b
```

4. Restart the backend so Flyway applies `V32__assistant.sql` and `V33__assistant_automatic_context.sql` and the settings take effect.
5. Sign in. Account, pregnancy/care and medical context are enabled automatically inside NutriMom. Open the chatbox or `/app/assistant` → **Cá nhân hóa** to enable cloud processing, or turn individual data sources off.

The key never belongs in FE, a `VITE_*` variable, a commit, or a chat message. `GROQ_ZDR_CONFIRMED` is an operator confirmation; the application cannot inspect or enable the Groq console setting itself. When the key is absent, the provider is disabled, privacy setup is unconfirmed, or the user has not consented, the same chatbox returns clearly labelled built-in guidance. It never silently switches provider.

## API

All endpoints are under `/api/v1/assistant` and require an active authenticated user.

| Method | Path | Result |
| --- | --- | --- |
| GET | `/status` | AI availability, user daily allowance, retention period |
| GET | `/context` | Personal greeting, current context coverage and suggested questions, bound to the signed-in owner |
| GET / PATCH | `/preferences` | Cloud consent and profile/pregnancy/medical-record permissions |
| GET / POST | `/conversations` | List up to 20 owned conversations / create one |
| GET / DELETE | `/conversations/{id}` | Read / delete an owned conversation |
| POST | `/conversations/{id}/messages` | Send and receive a complete validated response |

Send body:

```json
{
  "content": "Tôi lưu kết quả khám ở đâu?",
  "client_message_id": "12345678-1234-1234-1234-123456789abc",
  "page_path": "/app/profile/records"
}
```

Preferences PATCH requires all four boolean values (`cloud_consent`, `use_profile`, `use_pregnancy`, `use_medical_records`) and the current `version`. The three local data sources default to true; cloud consent defaults to false. V33 updates old all-false defaults at context revision zero, increments their revision, and leaves existing revised choices unchanged. The version is a **context revision**, separate from internal JPA locking and usage counters. Any changed permission starts a new context revision. Previous conversations remain readable/deletable, but cannot be submitted to the model again. Responses completing after a permission change or conversation deletion are discarded.

Each response includes `user_message`, `assistant_message`, and `remaining_ai_messages`. Assistant messages contain typed citations, allowlisted actions, `context_used`, health notices, emergency/escalation flags, and a mode: `AI`, `GUIDE`, or `SAFETY`. Model source/action IDs must resolve to evidence supplied by the backend. FE also restricts links and renders message content as plain text.

## Data scope

- The versioned `src/main/resources/assistant/website-knowledge.json` contains 26 verified topics: profile, pregnancy, medical records, care preparation/birth plan, blogs/bookmarks, consultations/cancellation/reviews, pricing/payOS, family sharing/tasks, support, settings, account actions, roles and unfinished screens. Every AI request includes a compact map of all topics plus three detailed relevant guides. ACTIVE/PARTIAL/PLANNED prevents presenting mock or planned screens as working features. Update this resource whenever routes, labels or workflows change; the assistant does not inspect the repository at runtime.
- Blog retrieval uses only published articles whose publication date is not in the future, with at most three matching articles and bounded excerpts. Nutrition questions use the nutrition category and prioritize the current pregnancy stage over newer unrelated posts.
- **Account and activity (`use_profile`)**: role, age, gender/onboarding state, current service plan, up to five own consultation requests, published bookmarks/count, pending support count, active family membership count and relevant recent payment summaries. It excludes payment URLs, QR content, other members' data and consultation notes. Name/contact/date-of-birth questions are answered locally in NutriMom; these replies are excluded from model history. The model's structured profile facts exclude name, email, phone, exact birth date and account ID.
- **Pregnancy and care (`use_pregnancy`)**: existing service's current week/day/trimester/due-date calculation, days remaining, first/multiple-pregnancy flags, checklist progress and bounded birth-plan preferences. Assistant reads care repositories directly and does not auto-create a checklist or birth plan. Companion and facility contact details are excluded. An absent active pregnancy is an empty state, not an invented pregnancy or a chat failure.
- **Medical records (`use_medical_records`)**: total own non-deleted records is known immediately; relevant questions retrieve up to three matching records, including older records, with recent records as a fallback. It uses bounded titles/summaries and dates. Notes, clinicians, facilities, file bytes, and signed download URLs are excluded. It does not read attached scans/PDFs or infer diagnoses from record counts.
- Context is rebuilt from current database state for every question. Frontend also refreshes its overview after navigation, opening chat, preferences changes and replies. Follow-up questions retain the previous website topic while reading fresh account data. “Bạn biết gì về thông tin của tôi?” and next-step summaries work locally without a provider key or cloud consent.
- Common email, phone, and JWT strings are redacted from provider-bound text. This is minimization, not a guarantee that arbitrary free text contains no identifying information. Consent therefore covers the submitted question and selected context.
- Family-shared health data is outside this release's scope. Knowing the account's membership count does not grant access to other accounts.

## Limits, persistence, and retention

`assistant_preferences`, `assistant_conversations`, `assistant_messages`, and `assistant_quota` persist in PostgreSQL. Foreign keys cascade when a user/conversation is physically deleted. Users can delete individual conversations through the UI. Conversations inactive for 30 days are unavailable and removed by the hourly cleanup (the period is configurable).

Default caps: 20 AI attempts per user/day, 100 global attempts/day, and a conservative 150,000-token reservation budget/day. Counters reset at UTC midnight, are database-locked, persist across restarts, and do not reset when history is deleted. Token reservations are estimates, not provider billing measurements. All configured counters should stay below the organization's actual [Groq limits](https://console.groq.com/docs/rate-limits). Built-in guidance continues when limits are exhausted.

One request per user can be in flight across all conversations. A request claim expires after two minutes; the upstream timeout is 35 seconds. A conversation holds at most 60 messages. Replaying the same `client_message_id` and content returns the existing answer without another provider call; changing content with that ID returns `IDEMPOTENCY_CONFLICT`. Active concurrent requests return `ASSISTANT_BUSY`. History caps return `ASSISTANT_HISTORY_LIMIT`. The HTTP rate policy permits eight message requests/minute/user.

Provider requests run outside database transactions. Inputs/outputs are not logged. Backend errors return safe fallback reasons, not provider error bodies. Frontend data stays in memory and is cleared/aborted on logout or account changes; it is never written to localStorage. The router remains mounted so login return links are preserved.

## Validation

```powershell
mvn "-Dtest=AssistantIntegrationTest,GroqAssistantClientTest,WebsiteGuideCatalogTest,AssistantSchemaMigrationTest" test
```

Assistant tests cover automatic local defaults with separate cloud consent, account isolation, current and older-record retrieval, personalized summaries, rich activity/care context, local identity replies excluded from model history, explicit opt-out, stage-aware nutrition retrieval, idempotency, published-only sources, quota persistence, changes during in-flight requests, concurrency, safety routing, deletion/retention, history limits, and Vietnamese guide ranking. The provider HTTP adapter is tested against a local stub for strict schema, invalid citations, failures and timeout. Both migrations' defaults, revision changes, constraints and cascade behavior are tested in H2 PostgreSQL mode; this does not replace checking the full Flyway chain on a PostgreSQL staging database.

Frontend validation: `npm test`, `npm run lint`, and `npm run build`. Browser checks use an isolated test backend and synthetic accounts: context loads after login before chat opens, personalized greeting/overview is present, guide replies have working source/action links, own records are summarized automatically, old permission contexts are read-only, chat survives route changes, and logout during a pending reply clears state before the next account signs in. No chat data is written to localStorage. Light/dark desktop layouts are checked.

A real Groq key is still required to evaluate live Vietnamese answer quality; no live provider was used in these tests.
