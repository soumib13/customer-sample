# Insurance Claim — Step-by-Step Build Tutorial

> A build-order companion to `LEARNING-NOTES.md`. Follow the steps top to bottom; the project
> compiles at every **Checkpoint**, so you're never debugging a hundred errors at once. Each step
> cross-references the concept in `LEARNING-NOTES.md` (cited as **[Notes §N]**).
>
> Offline note: `mvn compile` works fully offline (dependencies were cached on an earlier build).
> Running the service and `curl`-ing localhost also work offline. Only the SDK *docs website* is
> unreachable — but your IDE's autocomplete reads the cached SDK jars, so use it to confirm exact
> class/method names.

---

## Build order at a glance

```
Phase A  Domain types (no behavior)      → compiles, nothing runs yet
   1. ClaimStatus enum
   2. Document record
   3. ClaimEvents sealed interface (all 8, with fields)
   4. ClaimCommands (all 8)
   5. ClaimState record + emptyState()
Phase B  The entity (the heart)          → the write side works
   6. ClaimEntity skeleton (emptyState + empty applyEvent)
   7. File slice (1st handler + 1st fold case)   ← prove the loop end-to-end
   8. Remaining 7 slices, one at a time
Phase C  The endpoint (HTTP in)          → you can POST commands
   9. Request DTOs + write routes
Phase D  Run & exercise                  → see it live
  10. Start service, curl a full lifecycle
Phase E  The view (read side)            → you can GET/query
  11. ClaimView (table updater + queries)
  12. Wire GET routes; observe eventual consistency
Phase F  Extensions (optional)
  13. Snapshots; add-a-view-later back-fill
```

Why this order: **types before behavior, write side before read side, one slice at a time.** The
entity can't compile without the events/commands/state it references, so those come first. The
endpoint needs the entity. The view is independent of the endpoint but easiest to verify once you
can already create data via HTTP. [Notes §5 dashboard shows the same layering.]

---

## Phase A — Domain types

### Step 1 — `ClaimStatus` enum
- **Do:** create `domain/model/ClaimStatus.java`: the 7 constants + `isTerminal()`.
- **Why:** everything else refers to status; define the vocabulary of the state machine first.
- **Concept:** the enum is the set of *boxes* in the state-machine diagram. `isTerminal()` is
  centralized because many handlers ask "is this claim closed?" [Notes §2 (enum design), §5 slice 8]
- **Checkpoint:** `mvn compile` — green.

### Step 2 — `Document` record
- **Do:** create `domain/model/Document.java`:
  `record Document(String documentId, String documentType, String fileRef, Instant submittedAt) {}`
- **Why:** `ClaimState` will hold a `List<Document>`; the element type must exist first.
- **Concept:** state accumulates sub-entities the events describe; the list element is a value type.
  [Notes §5 "Supporting type: Document"]
- **Checkpoint:** `mvn compile` — green.

### Step 3 — `ClaimEvents` sealed interface (all 8, WITH fields)
- **Do:** create `domain/events/ClaimEvents.java`: the sealed interface with the `claimId()` /
  `occurredAt()` accessors and all 8 records, each with its full field list from the slice specs.
- **Why:** the entity's `applyEvent` switches over these; the view consumes them. They depend on
  nothing domain-specific (just `String`/`BigDecimal`/`Instant`), so they can be fully defined now.
- **Concept:** a *closed set* of facts → `sealed` enables the exhaustive switch later. The two
  accessors are a *structural contract* every record satisfies by naming components `claimId` /
  `occurredAt`. [Notes §2 (sealed-interface rationale), §5 slice 1, §"timestamps" for occurredAt]
- **Watch:** every record needs a `claimId` and `occurredAt` component or it won't compile — that
  failure *is* the contract working.
- **Checkpoint:** `mvn compile` — green.

### Step 4 — `ClaimCommands` (all 8)
- **Do:** create `domain/commands/ClaimCommands.java`: a `final class` namespace holding the 8
  command records with their thin fields.
- **Why:** the entity's handler methods take these as parameters.
- **Concept:** commands carry *only the caller's input* — no `claimId` (entity id from context), no
  timestamps, no derived data. `final class` namespace, NOT sealed (no polymorphic handling — each
  command has its own method). [Notes §2 (why commands are a final class), §4 (command shape)]
- **Checkpoint:** `mvn compile` — green.

### Step 5 — `ClaimState` record + `emptyState()`
- **Do:** create `domain/model/ClaimState.java`: the record with all fields, the derived
  `outstanding()` and `isTerminal()` helpers, and a static `empty()` factory.
- **Why:** the entity is typed `EventSourcedEntity<ClaimState, ClaimEvents>` and needs this type.
- **Concept:** state is the *fold result* — accumulators (`totalPaid`, `documents`) + derived
  fields, looking like no single event. `empty()` is the null/zero starting point; choose a sentinel
  status (e.g. `null`) so "has it been filed?" is unambiguous. [Notes §3 (events vs state), §4
  (state shape), §5 slice 2 emptyState decision]
- **Checkpoint:** `mvn compile` — green. *(Phase A done: all types exist, nothing runs yet.)*

---

## Phase B — The entity (the heart)

### Step 6 — `ClaimEntity` skeleton
- **Do:** create `domain/entities/ClaimEntity.java`:
  `@ComponentId("claim")`, extend `EventSourcedEntity<ClaimState, ClaimEvents>`, override
  `emptyState()` → `ClaimState.empty()`, and an `applyEvent` switch that (temporarily) handles all 8
  cases by returning `currentState()` unchanged — just to compile.
- **Why:** establishes the spine so you can add one real handler next and see the whole loop work.
- **Concept:** `emptyState()` seeds the fold; `applyEvent` IS the fold. **Keep `applyEvent` a pure
  function of the event — no `Instant.now()`, no id generation, no I/O.** [Notes §3, §"replay
  determinism"]
- **Watch:** `@ComponentId` must be unique across the app (the customer entity uses `"Customer"`;
  use `"claim"`).
- **Checkpoint:** `mvn compile` — green.

### Step 7 — File slice (prove the loop end-to-end FIRST)
- **Do:** implement `public Effect<String> file(FileClaim cmd)` (empty-state invariant → build
  `ClaimFiled` with `claimId` from `commandContext().entityId()` and a stamped `occurredAt` →
  `persist().thenReply(...)`), and replace the placeholder `applyEvent(ClaimFiled)` case with the
  real fold that builds the initial FILED state from empty.
- **Why:** do ONE full vertical slice (command→handler→event→fold) before the rest, so you confirm
  the mechanism works before repeating it 7×.
- **Concept:** handler validates against `currentState()`, enriches the thin command into a fat
  event, persists; the fold turns "nothing" into a real claim. **Handler stamps, event stores, fold
  reads.** [Notes §5 slice 2, §"timestamps & determinism"]
- **Checkpoint (offline, no HTTP needed):** write a tiny unit test using Akka's event-sourced
  testkit (confirm the exact class name via IDE autocomplete — it's the `*TestKit` for event-sourced
  entities). Drive `file(...)`, assert the persisted event is `ClaimFiled` and the resulting state
  is FILED. This is the fastest offline feedback loop — no service start required.

### Step 8 — Remaining 7 slices, ONE AT A TIME
- **Do:** for each of Assign, RequestInfo, SubmitDocument, Approve, Deny, RecordPayment, Withdraw:
  add the handler (with its invariants), then make its `applyEvent` case real. Compile + test after
  *each* slice, not all at once.
- **Why:** small steps = small blast radius. A red compile points at the one slice you just touched.
- **Concept (per slice — cross-ref the spec):**
  - **Assign** [§5 slice 3]: conditional fold (FILED→UNDER_REVIEW, else keep adjuster only).
  - **RequestInfo** [§5 slice 4]: enrich event with `adjusterId` read from state.
  - **SubmitDocument** [§5 slice 5]: **generate `documentId` in the handler** (2nd "handler
    generates, event stores, fold reads"); fold appends to an immutable copy of `documents` and
    conditionally INFO_REQUESTED→UNDER_REVIEW.
  - **Approve** [§5 slice 3 worked example / §"Approve slice"]: amount ≤ claimed invariant; frozen
    decision (`approvedAmount`, `adjusterId`, `occurredAt`) into the event; fold stores
    `approvedAmount` for the pay handler to read later.
  - **Deny** [§5 slice 6]: `reason` required; terminal.
  - **RecordPayment** [§5 slice 7]: reads `approvedAmount` back out of state (state as relay);
    can't-overpay invariant; **PAID is derived in the fold** when `totalPaid` crosses the threshold
    — no MarkPaid command.
  - **Withdraw** [§5 slice 8]: single `!isTerminal()` guard — where centralizing `isTerminal()` pays.
- **Watch:** once all 8 cases are real, the `applyEvent` switch needs no `default` — the sealed
  interface makes it exhaustive. If you add a 9th event later (e.g. `ClaimSettled`), this switch
  won't compile until you handle it — by design. [Notes §2]
- **Checkpoint:** `mvn compile` green after each slice; testkit test per slice asserting the
  invariant rejects illegal calls and the fold produces the right status. *(Phase B done: the write
  side is correct and tested without any HTTP.)*

---

## Phase C — The endpoint (HTTP in)

### Step 9 — Request DTOs + write routes
- **Do:** create `api/ClaimEndpoint.java`: `@HttpEndpoint("/api")`, inject `ComponentClient`. Add a
  request DTO record per command (e.g. `FileClaimRequest`) and a POST route per command that maps
  DTO→command and invokes `client.forEventSourcedEntity(claimId).method(ClaimEntity::file)
  .invoke(cmd)`. Map domain errors to 4xx.
- **Why:** this is how a human/tool drives the entity over HTTP.
- **Concept:** the endpoint is a **thin translation layer — no business logic.** The request DTO is a
  *fourth shape* (DTO→command→event→state), owned by the API contract. Errors from
  `effects().error(...)` → map to 400/404/409. [Notes §5 endpoint design, Decisions 1–4]
- **Watch:** routes can share the `/api` base with the customer endpoint as long as full paths differ
  (`/api/claims` vs `/api/customers`). `claimId` comes from the URL path, not the body.
- **Checkpoint:** `mvn compile` — green.

---

## Phase D — Run & exercise

### Step 10 — Start the service and walk a full lifecycle
- **Do:** start the service the **same way you run the customer app** (Akka dev-mode on port 9000;
  the typical invocation is `mvn compile exec:java`). Then `curl` a full happy path:
  ```
  POST /api/claims                         → capture the returned claimId
  POST /api/claims/{id}/adjuster           → status FILED → UNDER_REVIEW
  POST /api/claims/{id}/documents          → adds a document
  POST /api/claims/{id}/approval           → status → APPROVED
  POST /api/claims/{id}/payments  (partial)→ stays APPROVED, totalPaid grows
  POST /api/claims/{id}/payments  (rest)   → status → PAID (derived!)
  ```
  Then probe the invariants: try to approve before assigning (expect 4xx), overpay (expect 4xx),
  withdraw after PAID (expect 4xx).
- **Why:** confirms the wiring AND lets you *see* the derived transitions and rejections you encoded.
- **Concept:** the rejections are your invariants firing in the entity, surfaced as HTTP status by
  the endpoint. The "two payments → PAID" is the fold's derived terminal transition. [Notes §5 slice
  7, endpoint Decision 4]
- **Checkpoint:** happy path returns 2xx; each illegal call returns 4xx with your error message.

---

## Phase E — The view (read side)

### Step 11 — `ClaimView` (table updater + queries)
- **Do:** create `views/ClaimView.java`: `@ComponentId("claimview")`, a `@Table("claims_table")`
  `TableUpdater` annotated `@Consume.FromEventSourcedEntity(ClaimEntity.class)` with `onEvent`
  handlers for the events that affect a row, plus `@Query` methods (`getByStatus`, `getByAdjuster`,
  `getById`, `getAll`).
- **Why:** the read side — queryable, multi-row, dashboard-style access the entity can't give you.
- **Concept:** **same events, a different fold** (`onEvent`) than `applyEvent`. Remember the
  multi-row return-type rule: collection query → wrapper record + `SELECT ... AS entries`; single-row
  → bare row type. Decide how to keep `status` correct (re-derive in updater / denormalize into
  events / keep view dumb). [Notes §1 (return-type rule), §6 (views, the duplication gotcha)]
- **Watch:** the very bug from your customer view — don't return a single-row type for `SELECT *`.
- **Checkpoint:** `mvn compile` — green; restart the service.

### Step 12 — Wire GET routes; observe eventual consistency
- **Do:** add `GET /api/claims` (→ view query) and `GET /api/claims/{id}` (→ view *or* entity-state
  read). `curl` them after creating claims.
- **Why:** completes the round-trip and lets you feel the projection lag.
- **Concept:** **single-claim-now → entity (strongly consistent); multi-row/filtered → view
  (eventually consistent).** Create a claim then immediately list — it may not appear for a moment.
  [Notes §5 Decision 3, §6 eventual consistency]
- **Checkpoint:** filtered queries (`?status=UNDER_REVIEW`) return the right subset; you observe (or
  knowingly tolerate) the lag.

---

## Phase F — Extensions (optional, deepen the concepts)

### Step 13a — Watch a snapshot kick in
- **Do:** record many `payments` (and documents) on one claim to pile up events, then restart the
  service and load the claim.
- **Concept:** Akka auto-snapshots at a configurable event threshold; load becomes "latest snapshot
  + replay the tail" instead of full replay. State must serialize (records + Jackson, already set
  up). Changing `ClaimState`'s shape may invalidate old snapshots → safe, just forces a replay.
  [Notes §7]

### Step 13b — Add a NEW view AFTER data exists (back-fill superpower)
- **Do:** once you have claims, add a brand-new view (e.g. a per-adjuster financial rollup, a
  different row *grain* → its own table) and restart.
- **Concept:** because events are the source of truth, Akka replays the existing log to populate the
  new read model — no write-side change, no data migration. This is the payoff of CQRS + event
  sourcing. [Notes §6 "two superpowers"]

---

## Quick cross-reference map

| Build step | Concept home in LEARNING-NOTES.md |
|---|---|
| 1 enum | §2 (enum design), §5 slice 8 |
| 3 events | §2 (sealed interface), §5 slice 1, §timestamps |
| 4 commands | §2 (final class), §4 (command shape) |
| 5 state | §3 (events vs state), §4 (shapes), §5 emptyState |
| 6–8 entity | §3, §5 all slices, §replay determinism |
| 9 endpoint | §5 endpoint design (Decisions 1–4) |
| 11–12 view | §1 (return-type rule), §6 (views) |
| 13 extensions | §6 (back-fill), §7 (snapshots) |
| 14–25 platform | §8 (full platform) |

---

# Part 2 — Scaling to a full Claims Management Platform

> Steps 1–13 build the core single-entity claim. Part 2 grows it into a substantial multi-component
> platform — enough building (with tests) to fill many hours. Each phase adds ONE Akka component type
> and ONE big concept. Keep `LEARNING-NOTES.md` §8 open alongside. Build phases in order: each
> depends on the previous. Compile + test after every step.
>
> Offline-accuracy note: concepts are exact; confirm the precise Akka **API names** (Workflow step
> DSL, `TimerScheduler`, `KeyValueEntity`, `Consumer`, `TimedAction`) via IDE autocomplete against the
> cached jars. Where a step says "shape," the pattern is right even if a method name needs checking.

## Build order at a glance (Part 2)

```
Phase G  PolicyEntity (2nd ES entity)        → cross-entity invariants exist
  14. Policy types (status, state, events, commands)
  15. PolicyEntity handlers + reservation commands
  16. Policy endpoint + curl issue/reserve
Phase H  AdjusterWorkload (KeyValueEntity)   → meet ES-vs-KV
  17. KV entity + capacity invariant
  18. Wire assignment to it from the claim flow
Phase I  ClaimSettlementWorkflow (Saga)      → durable orchestration + compensation
  19. Workflow state + steps (happy path)
  20. Add compensation (release reservation on failure)
  21. Settle endpoint kicks off the workflow
Phase J  NotificationConsumer (Consumer)     → event-driven side effects
  22. Consumer + idempotent side effect
Phase K  ClaimSlaTimer (TimedAction+timers)  → time-driven transitions
  23. Schedule on INFO_REQUESTED, fire, re-check, cancel on resolve
Phase L  Expanded views                      → multiple read models
  24. AdjusterDashboardView + FinancialView (policy events)
  25. PolicyClaimsView (denormalized policyId) + curl dashboards
```

Why this order: PolicyEntity first because the workflow and views depend on it; the KV entity is a
small standalone warm-up; the Workflow needs both Claim and Policy to exist; consumers/timers/views
are independent add-ons that observe the now-complete write side. [Notes §8.0 palette]

---

## Phase G — `PolicyEntity` (2nd event-sourced entity)

### Step 14 — Policy domain types
- **Do:** `PolicyStatus` enum (ACTIVE/LAPSED/CANCELLED); `PolicyState` record with
  `available() = coverageLimit − reserved − paidOut`; `PolicyEvents` sealed interface (PolicyIssued,
  CoverageReserved, ReservationReleased, PayoutRecorded, PolicyLapsed, PolicyCancelled);
  `PolicyCommands` namespace.
- **Why / Concept:** a second ES entity — re-applies everything from §1–5 in a new aggregate. The
  interesting field is `available()`, the derived headroom the reservation invariant guards. [Notes
  §8.2]
- **Checkpoint:** `mvn compile` green.

### Step 15 — `PolicyEntity` handlers (the reservation pattern)
- **Do:** `@ComponentId("policy")`. Handlers: `issue` (empty-state guard), `reserve`
  (**ACTIVE && amount ≤ available()** → CoverageReserved), `release` (ReservationReleased), `payout`
  (PayoutRecorded: reserved−, paidOut+), `lapse`/`cancel`. Real `applyEvent` folds.
- **Why / Concept:** the cross-entity invariant ("don't pay beyond coverage") is enforced **atomically
  inside the policy** at `reserve` — `available()` check + `reserved +=` is one entity transaction.
  The claim will *ask* the policy to reserve; the policy is the authority. [Notes §8.1, §8.2]
- **Checkpoint:** testkit — reserve up to the limit succeeds; reserving past `available()` is
  rejected; release/payout adjust the headroom correctly.

### Step 16 — Policy endpoint
- **Do:** `PolicyEndpoint` with POST issue / reserve / release / payout, GET policy.
- **Checkpoint:** curl: issue a policy, reserve coverage, watch `available()` shrink, over-reserve →
  4xx.

---

## Phase H — `AdjusterWorkload` (KeyValueEntity)

### Step 17 — The KV entity
- **Do:** create a `KeyValueEntity` (`@ComponentId("adjuster-workload")`) with state
  `(adjusterId, openCases, maxCapacity)`. Handlers `assignCase` (invariant `openCases < maxCapacity`),
  `closeCase` (floor 0), `setCapacity`. Use `effects().updateState(newState).thenReply(...)` — NOT
  `persist`.
- **Why / Concept:** **ES vs KV.** This state is a current-value counter; history is noise → KV.
  No events, no fold, no replay: `updateState` stores the latest value directly. Notice the
  "events vs state" split from §3 collapses — state IS what's stored. [Notes §8.3]
- **Checkpoint:** testkit — assign up to capacity, then rejection; closeCase decrements.

### Step 18 — Wire capacity into the claim assignment flow
- **Do:** decide the coordination: when `AssignAdjuster` succeeds on the claim, also `assignCase` on
  the adjuster's KV entity. (Two entities → not atomic — for now do it from the endpoint or, better,
  a small consumer; the "right" way is a workflow, foreshadowing Phase I.)
- **Why / Concept:** your first taste of a **cross-entity operation that isn't atomic** — what if the
  claim assign succeeds but the adjuster increment fails? Note the gap; Phase I's workflow is the
  principled fix. [Notes §8.1]
- **Checkpoint:** curl assign; the adjuster's open-case count goes up; assigning past capacity is
  handled (decide: reject the assignment, or allow claim-assign but flag overload — a real design
  choice to reason about).

---

## Phase I — `ClaimSettlementWorkflow` (saga / process manager)

### Step 19 — Workflow happy path
- **Do:** create a `Workflow` (`@ComponentId("claim-settlement")`) with state
  `(claimId, policyId, amount, step)`. Define steps: (1) reserve coverage on Policy → (2) approve the
  Claim → (3) record payment on Claim → (4) record payout on Policy + mark PAID. Each step calls the
  relevant entity via `ComponentClient` and transitions to the next on success.
- **Why / Concept:** **durable multi-step orchestration.** The workflow persists which step it's on,
  so a crash mid-sequence resumes instead of leaving a half-settled claim. [Notes §8.4]
- **Checkpoint:** curl-start a settlement on a valid claim+policy; all four steps run; claim ends
  PAID, policy `paidOut` increased, `reserved` back to 0.

### Step 20 — Compensation (the saga "undo")
- **Do:** add failure transitions: if step 2/3/4 fails, run a **compensation** step that calls
  `ReleaseReservation` on the policy (undo step 1), and leave the claim in a safe state
  (e.g. back to UNDER_REVIEW or DENIED).
- **Why / Concept:** you cannot ACID-wrap four entity calls; the saga guarantees **all-commit or
  compensate-the-committed.** Force a failure (e.g. deny mid-flow, or a deliberately failing step) and
  watch the reservation get released — the system left consistent without a distributed transaction.
  [Notes §8.1, §8.4]
- **Checkpoint:** inject a failure at step 3; assert the policy reservation is released and no money
  is "stuck."

### Step 21 — Settle endpoint
- **Do:** `POST /api/claims/{id}/settlement` starts the workflow.
- **Checkpoint:** the endpoint returns promptly while the workflow runs durably in the background;
  poll the claim/policy to see the end state.

---

## Phase J — `NotificationConsumer` (Consumer)

### Step 22 — Idempotent event-driven side effect
- **Do:** create a `Consumer` (`@ComponentId("claim-notifier")`,
  `@Consume.FromEventSourcedEntity(ClaimEntity.class)`) that reacts to `ClaimApproved`/`ClaimDenied`/
  `PaymentRecorded` by "sending" a notification (log it / simulate). Add a dedupe guard keyed on
  `(claimId, eventType)`.
- **Why / Concept:** consumers do **side effects**, not row writes. Delivery is **at-least-once** —
  the same event can arrive twice after a crash, so the effect must be **idempotent**. Contrast with
  views (idempotent by construction). [Notes §8.5]
- **Checkpoint:** approve a claim → one notification; simulate redelivery (or reason about it) → still
  one logical notification thanks to the dedupe.

---

## Phase K — `ClaimSlaTimer` (TimedAction + durable timers)

### Step 23 — Time-driven auto-escalation
- **Do:** when a claim enters INFO_REQUESTED, schedule a timer (via `TimerScheduler`, short duration
  for the kata) to call a `TimedAction`. The action **re-reads the claim's current status**; if still
  INFO_REQUESTED → issue an escalate/auto-withdraw command; else no-op. Cancel the timer when the
  claim leaves INFO_REQUESTED early.
- **Why / Concept:** some transitions are caused by the **passage of time**, not a command. Timers are
  **durable** (survive restarts). The action must **re-check state on fire** — the world may have moved
  on since scheduling; never act on stale intent. [Notes §8.6]
- **Checkpoint:** request info, wait out the (short) timer without responding → claim auto-escalates;
  in a second run, respond before it fires → timer cancelled, no escalation.

---

## Phase L — Expanded views (multiple read models)

### Step 24 — `AdjusterDashboardView` + `FinancialView`
- **Do:** `AdjusterDashboardView` (open claims + counts per adjuster; consumes claim events) and
  `FinancialView` (reserved vs paid per policy / portfolio totals; consumes **PolicyEntity** events).
- **Why / Concept:** views over **different source entities**; different **row grains** (per-adjuster,
  per-policy) → different tables. [Notes §8.7, §6]
- **Checkpoint:** dashboards return correct aggregates after a few claims/policies.

### Step 25 — `PolicyClaimsView` (denormalized foreign key)
- **Do:** carry `policyId` on the claim view's rows; add `getByPolicy(policyId)`.
- **Why / Concept:** **views don't do cross-entity SQL joins** — you denormalize the foreign key onto
  the row at projection time and query it. [Notes §8.7]
- **Checkpoint:** `GET /api/policies/{id}/claims` (or `?policyId=`) lists that policy's claims.

---

## Where you'll be when Part 2 is done

A platform using **EventSourcedEntity ×2, KeyValueEntity, Workflow, Consumer, TimedAction, View ×4,
HttpEndpoint ×3** — i.e. essentially the whole Akka Java SDK component surface — with the hard
concepts exercised for real: consistency boundaries, the reservation pattern, sagas + compensation,
at-least-once + idempotency, and durable timers. That's the jump from "I can do event sourcing" to
"I can design a distributed event-driven system."

---

# Appendix — Project scaffolding (creating a new Akka project)

How to stand up a fresh Akka Java SDK project, with the trade-offs of each method. Offline, the
**copy-an-existing-pom** method is recommended (it reuses only cached artifacts).

## Anatomy of a minimal Akka project

An Akka Java SDK project needs surprisingly little. The minimum viable layout:

```
my-project/
├── pom.xml                                   # build config: parent version, Java 21, deps, plugins
└── src/main/
    ├── java/com/<org>/<pkg>/                  # your components live here
    │   └── (at least one component class)
    └── resources/
        └── application.conf                   # runtime config (dev-mode http-port = 9000)
```

What each piece does:
- **`pom.xml`** — the hard part. Declares `io.akka:akka-javasdk-parent` (version pins the whole SDK +
  runtime), `java.version` 21, `docker.image` (defaults to `${project.artifactId}`), and your deps.
- **`application.conf`** — runtime settings. The dev-mode block sets the local HTTP port:
  ```
  akka.javasdk { dev-mode { http-port = 9000 } }
  ```
  Easy to forget when copying only the pom; without it you lose your port config.
- **`src/main/java/...`** — your components. **No central registry / service-descriptor file is
  needed** — Akka's annotation processor *discovers* components by scanning compiled classes at build
  time (the build log prints e.g. `detected components: 1 http-endpoint, 1 view, ...`). Drop in a
  class annotated `@HttpEndpoint` / `@ComponentId` and it's wired automatically. This is *why* a
  copied-pom skeleton "just works" — there's no hidden wiring to miss.
- *(Optional)* `logback.xml` in resources if you want custom logging; this project doesn't have one.

## Method 1 (recommended, esp. offline): new folder + copy `pom.xml` + `application.conf`

The pom is the error-prone file (correct parent version, plugin wiring, dep versions), so reuse a
known-good one. Cleaner than copying the whole project — no `.git` / `target` / old source to clean.

```bash
mkdir -p /Users/pradeep/source/repos/<new>/src/main/java/com/pradeepl/<pkg>
mkdir -p /Users/pradeep/source/repos/<new>/src/main/resources
cp /Users/pradeep/source/repos/my-akka-kata/pom.xml /Users/pradeep/source/repos/<new>/
cp /Users/pradeep/source/repos/my-akka-kata/src/main/resources/application.conf \
   /Users/pradeep/source/repos/<new>/src/main/resources/
```

Then **edit the copied pom's identity** (or you'll clash with the source project):
```xml
<artifactId>my-new-project</artifactId>   <!-- CHANGE -->
<name>my new project</name>               <!-- CHANGE -->
<!-- groupId: usually fine to keep -->
<!-- docker.image follows ${project.artifactId} automatically -->
<!-- KEEP <version> and the akka-javasdk-parent version (3.4.6) — do not bump offline -->
```

Verify the cache covers it before writing real code:
```bash
cd /Users/pradeep/source/repos/<new> && mvn -o compile
```
(It'll report no components until you add a class — that's fine; it proves the build resolves.)

## Method 2: `cp -R` the whole project, then prune

```bash
cp -R /Users/pradeep/source/repos/my-akka-kata /Users/pradeep/source/repos/<new>
cd /Users/pradeep/source/repos/<new>
rm -rf .git target
rm src/main/java/com/pradeepl/akkakata/**/*.java   # drop old sources, keep package dirs
# edit pom identity as in Method 1
```
Fine, but you must clean the inherited git history, build output, and old code. Method 1 avoids all
three.

## Method 3: Maven archetype — AVOID offline

```bash
# Online only, and pin to a cached archetype version if you must go offline:
mvn archetype:generate -DarchetypeGroupId=io.akka \
  -DarchetypeArtifactId=akka-javasdk-archetype -DarchetypeVersion=3.4.6 \
  -DgroupId=com.pradeepl -DartifactId=<new>
```
On this machine only archetype **3.3.2** is cached (not 3.4.6), and its generated deps may be
incomplete offline → likely to fail at first build with no way to fetch. Use online; prefer Method 1
offline.

## Scaffolding pitfalls checklist

- [ ] Edited `<artifactId>` and `<name>` in the copied pom (else duplicate identity / image clash).
- [ ] Copied `application.conf` (not just the pom) — keeps the dev-mode port.
- [ ] Created `src/main/java/...` package dirs (a pom alone has nowhere for code).
- [ ] Did NOT change the akka-javasdk-parent version (offline = only 3.4.6 is fully cached).
- [ ] Kept every `@ComponentId` value **unique within the project** (entity, view, workflow, etc.).
- [ ] Ran `mvn -o compile` to confirm the cache covers the new project before building features.

| Method | Offline-safe? | Drags cruft? | Verdict |
|---|---|---|---|
| 1. New folder + copy pom + conf | ✅ | no | **Recommended** |
| 2. `cp -R` then prune | ✅ | yes (.git, target, src) | OK, more cleanup |
| 3. Archetype | ⚠️ 3.3.2 only | no | Online only |

---

# Appendix — Offline setup & command cheat-sheet

**Verified on 2026-06-10 (while online) that this machine can build AND run Akka offline.** Maven
3.9.10, Java 21 (Amazon Corretto), Akka parent 3.4.6 — full dependency + plugin closure cached in
`~/.m2`. `mvn -o clean compile` and `mvn -o compile exec:java` both confirmed working with no
network and no Docker.

### The golden rule offline: always pass `-o`
`-o` forces Maven **offline mode** — it fails *immediately and loudly* if an artifact isn't cached,
instead of hanging on a network retry. Use it on every command so a missing dependency is obvious.

### Daily commands
```bash
mvn -o clean compile          # build (strict offline)
mvn -o test                   # run unit/testkit tests
mvn -o compile exec:java      # RUN the service — dev-mode on http://localhost:9000
                              #   (mainClass kalix.runtime.AkkaRuntimeMain, via exec-maven-plugin)
```
Stop the service with Ctrl-C. Quick smoke test in another terminal:
`curl -s http://localhost:9000/api/customers`.

### Creating a NEW project offline (recommended: clone the working one)
```bash
cp -R /Users/pradeep/source/repos/my-akka-kata /Users/pradeep/source/repos/<new-name>
cd /Users/pradeep/source/repos/<new-name>
rm -rf .git target
# remove old sources but KEEP the package dirs + application.conf:
#   src/main/java/...  (delete the .java files you don't want)
#   src/main/resources/application.conf  (keep — sets http-port 9000)
# edit pom.xml: change <artifactId> and <name>; KEEP parent version 3.4.6 (don't bump offline!)
mvn -o compile                # proves the cache covers the new project
```
Why clone, not archetype: only archetype **3.3.2** is cached (not 3.4.6), and its generated deps may
be incomplete offline. The clone reuses the exact 3.4.6 closure already proven to work.

### If `-o` build fails with "Cannot access ... in offline mode"
That artifact wasn't pre-cached. There's no offline fix — note it, stub around it, and resolve once
you're back online with:
`mvn dependency:go-offline` (re-run the pre-fetch that was done on 2026-06-10).

### Gotcha you'll hit first (and the fix)
`GET /api/customers` currently returns **HTTP 500 / `NoEntryFoundException`** — this is the §1 view
bug (single-row return type on a `SELECT *`). Fix: `CustomerView.getAll` →
`QueryEffect<customerEntries>` with `SELECT * AS entries`, and update the endpoint return type to
match. Good first offline win.
