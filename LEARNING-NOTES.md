# Akka Event Sourcing — Learning Notes

> A running transcript of concepts worked through while building an Akka Java SDK
> event-sourced kata. Saved for offline reading. Appended to as the conversation continues.

---

## Table of contents

1. [The view bug — multi-row query return types](#1-the-view-bug--multi-row-query-return-types)
2. [Why events are a `sealed interface` but commands are a `final class`](#2-why-events-are-a-sealed-interface-but-commands-are-a-final-class)
3. [If I have events, why do I also need a state class?](#3-if-i-have-events-why-do-i-also-need-a-state-class)
4. [The shape of commands, events, and state — do they end up similar?](#4-the-shape-of-commands-events-and-state--do-they-end-up-similar)
5. [Design exercise: Insurance Claim domain](#5-design-exercise-insurance-claim-domain)

---

## 1. The view bug — multi-row query return types

### The mental model: this is CQRS on top of event sourcing

The project has two sides:

- **Write side** — `CustomerEntity`, commands, events, state. The *source of truth*. Stores a
  log of events, not the current data.
- **Read side** — `CustomerView`. A *derived, throwaway* projection built by replaying events.
  You can delete it and rebuild it entirely from the event log.

Key point: **the view never "shares" data with the entity.** They communicate only through the
event stream. Entity persists an event → Akka delivers it to the view's `TableUpdater` → the
updater writes a row. That's the whole contract. This is why
`@Consume.FromEventSourcedEntity(CustomerEntity.class)` exists — it's the subscription connecting
the two sides.

### The bug

A view query's **return type tells the runtime how many rows to expect**, and the SQL must match.

```java
@Query("SELECT * FROM customers_table")
public QueryEffect<customerEntry> getAll() {   // customerEntry = ONE row
```

- `SELECT *` produces a **stream of rows** (zero, one, or many).
- `QueryEffect<customerEntry>` says "I'm returning **exactly one** `customerEntry`."

These disagree. When a query can return multiple rows you must return a **wrapper record holding a
`List`**, and alias the row stream into that record's field using `AS`:

```java
@Query("SELECT * AS entries FROM customers_table")
public QueryEffect<customerEntries> getAll() {
```

- `customerEntries` is `record customerEntries(List<customerEntry> entries)`.
- The SQL `AS entries` **must match the field name** in the wrapper record. That alias maps
  "all the rows" → "the list field called `entries`."

Why it *sometimes* seems to work with the single-row type: a single-row query is meant for lookups
like `SELECT * FROM customers_table WHERE customerId = :id`. With `SELECT *` + single-row return
type the behavior is undefined — you might get one arbitrary row, empty, or an error. That's the
"not sure if it works" feeling: it's nondeterministic because the types lie about the data shape.

**Rule:** collection query → wrapper record with a `List` + `SELECT ... AS <fieldName>`.
Single-result query → the row record directly, with a `WHERE` that identifies one row.

The same bug rides up into the endpoint: once the view returns `customerEntries`, the
`getCustomers()` endpoint method and its variable must follow (the compiler forces this through the
`CustomerView::getAll` method reference).

### Soft vs. hard delete in the view (a design choice, not a bug)

The delete handler does `updateRow(...)` with `deleted = true`:

- **Soft delete (current):** keep the row, flip a `deleted` flag. Row still appears in `SELECT *`;
  filter with `WHERE deleted = false` when you don't want them. Good for audit/history.
- **Hard delete:** `effects().deleteRow()` — the row vanishes entirely.

Neither is "correct" — depends on whether a deleted customer should remain queryable. The view is
just a projection of intent: the event says "this customer was deleted," and *you* decide what that
means for the read model.

### The view consumes events, not commands or state

The updater reacts to `customerCreated` / `customerDeleted` (events), and each event **carries all
the data the view needs**. This is deliberate: the view only ever sees the event, never the
entity's in-memory state. If `customerDeleted` were an empty event, the view couldn't populate the
row. So **events must be self-contained enough for every downstream consumer.** Trade-off: "thin
events" (just an ID) vs "fat events" (full snapshot). This project chose fat, which makes
projections simpler.

---

## 2. Why events are a `sealed interface` but commands are a `final class`

Precision on terms: `CustomerEvents` is a **sealed interface**; `CustomerCommands` is a plain
**`final class`** — it is *not* sealed. That distinction is the whole answer.

### Start from the entity's type signature

```java
public class CustomerEntity extends EventSourcedEntity<CustomerState, CustomerEvents>
```

Two type parameters: `<State, Event>`. Note what's there and what's NOT:

- `CustomerState` — the single state type.
- `CustomerEvents` — the single event type.
- There is **no command type parameter.**

That absence is the key. The framework forces all events to share *one* common supertype (the
second type arg) but never asks for a common command type. So events *must* unify; commands need
not.

### Why events are a sealed interface

**1. The entity is parameterized over a single event type.** `persist(event)` must accept any
event; the runtime stores them all as "a `CustomerEvents`." So both records `implements
CustomerEvents`. They need a common umbrella — the `interface` part.

**2. `applyEvent` switches over every event, exhaustively:**

```java
public CustomerState applyEvent(CustomerEvents event) {
  return switch (event) {
    case customerCreated e -> ...;
    case customerDeleted e -> ...;
  };
}
```

No `default` branch. That compiles *only because the interface is `sealed`.* `sealed` tells the
compiler "these records are the complete, closed universe of `CustomerEvents`." The compiler then
knows the switch is exhaustive.

The payoff: the day you add a `customerRenamed` event, this switch **won't compile** until you
handle it. The compiler becomes a checklist guaranteeing every event has a state transition.
Without `sealed` you'd need a `default ->` that silently swallows unknown events — exactly the bug
you don't want in a system that replays history.

So: **events are a sealed interface because they're a closed set of alternatives handled
polymorphically in one exhaustive switch.**

### Why commands are just a `final class`

Commands are consumed differently — no `applyCommand(Command)` switch. Each command has its **own
method**:

```java
public Effect<String> create(CreateCustomer cmd) { ... }
public Effect<String> delete(DeleteCustomer cmd) { ... }
```

And the endpoint routes to them *by name*, not polymorphically:

```java
.method(CustomerEntity::create).invoke(cmd);
.method(CustomerEntity::delete).invoke(cmd);
```

`CreateCustomer` and `DeleteCustomer` are **never treated as "a Command" at runtime.** Nothing
holds a variable of type "command" and switches on it. So they need **no shared supertype.**

That's why `CustomerCommands` is just a `final class`: a **namespace** grouping two unrelated
records so they live together and you write `CustomerCommands.CreateCustomer`. `final` only means
"can't subclass this container" (never meant to be instantiated — it's purely a holder). You could
equally have two top-level files. Wrapping them is purely organizational.

### Summary table

| | Events | Commands |
|---|---|---|
| Need a shared supertype? | **Yes** — entity typed `EventSourcedEntity<_, CustomerEvents>`; `persist`/`applyEvent` treat them uniformly | **No** — each handled by its own method |
| Need exhaustiveness? | **Yes** — `applyEvent` switch must cover all | **No** — no switch over commands exists |
| Therefore | `sealed interface` (closed, polymorphic) | `final class` (just a namespace) |

You *could* make commands a sealed interface too — but only if you handled them polymorphically.
With method-per-command routing you gain nothing, so the plainer `final class` fits better.

Minor convention nit: Java records are types → conventional name is `PascalCase`
(`CustomerCreated`, `CustomerEntry`). Commands already follow this; events/view records don't.
Cosmetic only.

---

## 3. If I have events, why do I also need a state class?

Short version: **events are what happened; state is where you are now.** You need both because they
answer different questions, and one is derived from the other.

### The events are the truth. The state is a convenience.

In event sourcing, **only the events are stored.** `CustomerState` is *not* persisted anywhere.
What's on disk is the event log:

```
customerCreated("c1", "Ada", "Lovelace")
customerDeleted("c1", "Ada", "Lovelace")
```

`CustomerState` is a value the entity **rebuilds in memory by replaying those events** — a cache of
"the answer so far." Delete every `CustomerState` instance and you lose nothing; the entity
reconstructs it by replaying events on next load.

```
events (stored, the truth)  --applyEvent-->  state (in-memory, derived)
```

`applyEvent` *is* that arrow. State is literally `fold(emptyState, events, applyEvent)`: start
empty, apply each event in order, end at "now."

### Why not use the events directly? Two different questions.

- An event answers: *"What happened?"*
- The state answers: *"What is true right now?"*

Command handlers need the second one:

```java
public Effect<String> create(CreateCustomer cmd) {
    if (currentState().customerId() != null) {     // ← decision based on "now"
        return effects().error("Customer already exists");
    }
    ...
}
```

To enforce "can't create a customer that already exists" you need the current situation. You do NOT
want to re-scan the whole event log on every command. So the entity keeps the folded result —
`currentState()` — ready to consult. **State exists so handlers can validate against "now" in O(1)
instead of replaying the whole log every time.**

| | Role | Answers | Stored? |
|---|---|---|---|
| **Events** | record of facts | "what happened" | **Yes** — source of truth |
| **State** | current snapshot | "where are we now" | **No** — derived by folding events |

### Walk-through (create then delete c1)

1. Entity loads. No events → `emptyState()` = `CustomerState(null, null, null, false)`.
2. `create` arrives. Handler checks `currentState().customerId()` → `null`, so allowed. Persists
   `customerCreated`.
3. Runtime calls `applyEvent(customerCreated)` → state = `CustomerState("c1","Ada","Lovelace",false)`.
4. `delete` arrives. Handler reads `currentState()` to grab the current name to build the event.
   Persists `customerDeleted`.
5. `applyEvent(customerDeleted)` → state = `CustomerState("c1","Ada","Lovelace",true)`.

The `delete` handler even reads the name out of state to put into the event — the command
(`DeleteCustomer`) is empty. The handler *enriches* the event using state. That only works because
state already folded history into a usable "now."

### The lifecycle of one interaction

```
Command   → "please do X"     (a request; may be rejected; not stored)
   │  validated against current State
   ▼
Event     → "X happened"      (a fact; never rejected; the stored truth)
   │  folded by applyEvent
   ▼
State     → "this is true now"(derived snapshot; lives in memory)
```

- **Command** = intent. Can fail validation. Future-tense/imperative ("Create", "Delete").
- **Event** = immutable fact that already occurred. Can't be rejected or undone. Past-tense.
- **State** = the running total of all events, so you don't recompute constantly.

Why keep all three instead of "just store the current row": events give you history, audit, and the
ability to build **new read models by replaying** (like the view!); state gives a fast current
answer for decisions. Keep events as truth, treat state as a derived cache.

This is the same fold the view does: `applyEvent` folds events into `CustomerState` for the write
side; the view's `TableUpdater.onEvent` folds the same events into rows for the read side. Same
events, two projections, two purposes. That symmetry is the heart of CQRS + event sourcing.

---

## 4. The shape of commands, events, and state — do they end up similar?

In a trivial CRUD-shaped domain (customer-with-a-name) the three look almost identical:

- `CreateCustomer(firstName, lastName)`
- `customerCreated(customerId, FirstName, LastName)`
- `CustomerState(customerId, FirstName, LastName, deleted)`

**The similarity is a property of this domain's simplicity, not a rule.** Keep them separate
anyway.

### The three shapes answer three different questions

| Type | Question it answers | What goes in it |
|---|---|---|
| **Command** | "What's the *minimum input* needed to decide?" | Just intent + arguments. Often a subset; sometimes empty. |
| **Event** | "What *fact* must I record so any consumer can react?" | Self-contained description of what happened. |
| **State** | "What's true *now*, and what do I need to *enforce invariants*?" | Accumulation of all events + derived fields. |

Three tells already in the customer code:

1. **`CreateCustomer` has no `customerId`, but `customerCreated` does.** The command is *input* — the
   ID comes from context (`commandContext().entityId()`). The event is a *fact* and must be
   self-contained, so it captures the ID.
2. **`DeleteCustomer()` is empty, but `customerDeleted` is fat.** Command = thin intent; event =
   self-contained fact. Inverses here.
3. **`CustomerState` has a `deleted` boolean no command/event has.** It's a *derived* fact. State is
   the fold, so it holds things none of the inputs hold.

### Make the domain real and they diverge (Order example)

```
Command:  AddLineItem(productId, quantity)
              ↑ only what the caller supplies

Event:    LineItemAdded(productId, quantity, unitPriceAtTime, lineId)
              ↑ enriched: handler looked up the price and stamped it as a permanent fact

State:    Order(orderId, status, List<LineItem> items, BigDecimal total, boolean canStillBeEdited)
              ↑ accumulation + derived; looks like no single command or event
```

- **Command** is tiny — fields the user typed.
- **Event** is enriched — handler added `unitPriceAtTime` (a frozen decision) and a generated
  `lineId`. Events often contain *more* than the command.
- **State** is a different *structure entirely* — a growing `List`, a running `total`, a `status`
  machine. The fold of *many* events of *different* types.

### Why you must NOT unify them, even when identical

They **change for different reasons and live for different durations:**

- **Events are persisted forever** → must be **versioned / backward-compatible**. Most rigid.
- **Commands are transient API contracts** → refactor freely; evolve with your API.
- **State is pure in-memory derivation** → restructure however/whenever; adjust `applyEvent`.

Collapse them into one shared type and a change driven by any one force ripples into the other two.
Keeping them separate lets each evolve at its own rate and direction. The occasional field
duplication is the price of that independence — a bargain.

### Practical checklist

- **Command** — "What does the caller actually need to *supply*?" Nothing more. Leave out anything
  derivable from context (IDs from URL, timestamps, current user). Name imperatively.
- **Event** — "If I knew *only this record*, could every consumer do its job?" If not, enrich it.
  Stamp in decisions (looked-up prices, generated IDs, timestamp). Name past-tense. Treat shape as
  **append-only / versioned** from day one.
- **State** — "What do command handlers need to enforce invariants, in the most convenient shape?"
  Include accumulated collections and derived fields freely. Optimize for reading in handlers.
  Refactor without fear.

Structurally: group commands (namespace), events as a `sealed interface`, state as its own record.
**Never** make state implement the event interface or a command double as an event, even when
identical.

---

## 5. Design exercise: Insurance Claim domain

Chosen because it has a real workflow, decisions frozen into history, money that accumulates, and
real invariants. Working through it as a kata: establish the backbone, do one worked vertical slice
end-to-end, then implement the rest.

### Step 1: Design the state machine first — everything derives from it

States and transitions:

```
                    FileClaim
                        │
                        ▼
                   ┌─────────┐   AssignAdjuster   ┌──────────────┐
                   │  FILED  │ ─────────────────▶ │ UNDER_REVIEW │ ◀──┐
                   └─────────┘                    └──────────────┘    │ SubmitDocument
                                                   │   │   │          │
                                        RequestInfo│   │   │Approve    │
                                                   ▼   │   ▼           │
                                      ┌───────────────┐ ┌──────────┐   │
                                      │ INFO_REQUESTED │ │ APPROVED │   │
                                      └───────────────┘ └──────────┘   │
                                              │             │          │
                                 SubmitDocument│   RecordPayment        │
                                              └─────────────┘──────────┘
                                                            │
                                                            ▼
                                                       ┌────────┐
                                                       │  PAID  │
                                                       └────────┘

  Deny:     UNDER_REVIEW → DENIED
  Withdraw: any open state → WITHDRAWN
```

Terminal states (no transitions out): **PAID**, **DENIED**, **WITHDRAWN**.

The diagram is the most important artifact. **Every arrow is a command-that-might-be-accepted;
every box is a status; every illegal arrow is a command your handler must reject.** That's where
invariants come from. E.g. no arrow `FILED → APPROVED`, so "can't approve before under review" is
an invariant.

### Step 2: The method for deriving the three types

For each transition ask:

1. **Command** — minimum the caller supplies (no IDs from context, no timestamps, no derived data).
2. **Event** — self-contained fact, enriched with what the handler computed/decided/looked up.
3. **State** — what the fold must hold so future handlers can enforce invariants.

### Step 3: One worked slice end-to-end — "Approve the claim"

**Command — thin intent:**
```java
record ApproveClaim(BigDecimal approvedAmount, String note) {}
```
Absent: no `claimId` (entity id from context), no `adjusterId` yet (from auth), no timestamp.
Commands carry input, nothing derivable.

**Handler — where invariants live:**
```java
public Effect<String> approve(ApproveClaim cmd) {
    var s = currentState();

    // INVARIANT 1: legal transition — must be UNDER_REVIEW (from the diagram)
    if (s.status() != ClaimStatus.UNDER_REVIEW)
        return effects().error("Can only approve a claim that is under review");

    // INVARIANT 2: business rule — can't approve more than was claimed
    if (cmd.approvedAmount().compareTo(s.claimedAmount()) > 0)
        return effects().error("Approved amount exceeds claimed amount");

    // INVARIANT 3: must have an adjuster
    if (s.adjusterId() == null)
        return effects().error("Claim has no assigned adjuster");

    // ENRICHMENT: handler stamps WHO, WHEN, HOW MUCH into a permanent fact
    var event = new ClaimApproved(
        s.claimId(), s.adjusterId(), cmd.approvedAmount(), cmd.note(), /* approvedAt */ now);

    return effects().persist(event).thenReply(__ -> s.claimId());
}
```
The handler validates against **current state** (invariants readable straight off the state machine
+ business rules), then turns the thin command into a **fat, enriched event.**

**Event — a frozen decision:**
```java
record ClaimApproved(
    String claimId,
    String adjusterId,         // ← enrichment: not in the command
    BigDecimal approvedAmount,  // ← the decision, frozen forever
    String note,
    Instant approvedAt          // ← enrichment: stamped by handler
) implements ClaimEvents {}
```
The "frozen decision" pattern: the approved amount becomes an immutable historical fact. If the
policy's coverage limit changes next year, *this* approval is untouched.

**State — the fold absorbs it:**
```java
case ClaimApproved e -> currentState(
    ... status = APPROVED,
    ... approvedAmount = e.approvedAmount()   // now available to the PAY handler
);
```
State is the relay between handlers: approve writes `approvedAmount`; pay reads it back to enforce
"can't pay more than was approved."

### Step 4: The state shape this domain pushes toward

```java
record ClaimState(
    String claimId, String policyId, String claimantId,
    ClaimStatus status,            // ← the state-machine position
    Instant incidentDate, String description,
    BigDecimal claimedAmount,
    String adjusterId,             // null until assigned
    BigDecimal approvedAmount,     // null until approved — a frozen decision result
    BigDecimal totalPaid,          // ← ACCUMULATES across many PaymentRecorded events
    List<Document> documents,      // ← ACCUMULATES across many DocumentSubmitted events
    String denialReason            // null unless denied
) {
    BigDecimal outstanding() {     // ← DERIVED, in no event
        return approvedAmount == null ? null : approvedAmount.subtract(totalPaid);
    }
    boolean isTerminal() { return status == PAID || status == DENIED || status == WITHDRAWN; }
}
```
`totalPaid` and `documents` are folds of *many* events — "state ≠ event" becomes undeniable.
`outstanding()` exists in nothing but the state. Because `totalPaid` accumulates, **partial
payments come for free** — behavior emerging from the fold.

### The kata — slices to implement (easiest → richest)

1. **`ClaimStatus`** enum + the **`ClaimEvents`** sealed interface (type skeleton — practice the
   closed-set idea).
2. **`FileClaim` slice** — command, handler (one invariant: the empty-state check, like the
   customer's "already exists"), event, and the `applyEvent` case that builds the initial state.
3. **`SubmitDocument` slice** — forces an **accumulating list** in state and a **generated id**
   (documentId) as enrichment. Which states should allow it?
4. **`RecordPayment` slice** — reads `approvedAmount` back out of state to enforce "can't overpay,"
   and folds into `totalPaid`. Allow partial payments?

---

### Slice 1 (designed): `ClaimStatus` enum + `ClaimEvents` skeleton

**Deriving the event set:** every accepted state-machine arrow → exactly one past-tense event.
Eight events total. Map:

| Transition (arrow) | Event |
|---|---|
| → FILED | `ClaimFiled` |
| → UNDER_REVIEW (assign) | `AdjusterAssigned` |
| → INFO_REQUESTED | `InfoRequested` |
| (doc added) | `DocumentSubmitted` |
| → APPROVED | `ClaimApproved` |
| → DENIED | `ClaimDenied` |
| (payment) | `PaymentRecorded` |
| → WITHDRAWN | `ClaimWithdrawn` |

**Trap to avoid: NO `ClaimStatusChanged` event.** Status is a *consequence* computed in the fold
(`applyEvent`), never a fact in itself. Name events for the business thing that happened, never for
the state field that changed. `StatusChanged(old,new)` is a DB-row-diff masquerading as a domain
event — it tells consumers nothing about *why*. Likewise, submitting a document while
`INFO_REQUESTED` moves you back to `UNDER_REVIEW`, but that's *one* business fact
(`DocumentSubmitted`); the status change is derived in the fold, not a separate event.

**Design decisions taken:**

- **Common accessors: IN.** The sealed interface declares `String claimId()` and `Instant
  occurredAt()`. In an audit-heavy domain "every event identifies its claim and is timestamped" is a
  real invariant — enforce it at the type level so you can't define an event that forgets them, and
  consumers can treat any `ClaimEvents` uniformly.
- **Enum: minimal, with `isTerminal()`.** That predicate is consulted by many handlers, so it earns
  centralization. Specific transition checks (`status != UNDER_REVIEW`) stay inline in each handler
  next to that command's other invariants (cohesion). Add a full `allowedNext()` table later only if
  inline checks start to repeat — centralize a rule when duplication actually hurts, not
  preemptively.

```java
// domain/model/ClaimStatus.java
public enum ClaimStatus {
    FILED, UNDER_REVIEW, INFO_REQUESTED, APPROVED, DENIED, PAID, WITHDRAWN;

    public boolean isTerminal() {
        return this == PAID || this == DENIED || this == WITHDRAWN;
    }
}
```

```java
// domain/events/ClaimEvents.java
import java.time.Instant;

public sealed interface ClaimEvents {
    String claimId();      // contract: every event identifies its claim
    Instant occurredAt();  // contract: every event is timestamped

    record ClaimFiled(...)        implements ClaimEvents {}
    record AdjusterAssigned(...)  implements ClaimEvents {}
    record InfoRequested(...)     implements ClaimEvents {}
    record DocumentSubmitted(...) implements ClaimEvents {}
    record ClaimApproved(...)     implements ClaimEvents {}
    record ClaimDenied(...)       implements ClaimEvents {}
    record PaymentRecorded(...)   implements ClaimEvents {}
    record ClaimWithdrawn(...)    implements ClaimEvents {}
}
```

**Subtle point — structural satisfaction by component NAME, not position.** A record satisfies the
`claimId()` / `occurredAt()` accessors simply by having components *named* exactly `claimId` and
`occurredAt` (the compiler auto-generates matching accessors). Order among other fields is
irrelevant; spelling is enforced. Typo `claimID` or omit `occurredAt` → compile error (the contract
doing its job). Consequence: records can't stay literal `...` and still compile — the interface
forces at least those two components into each, so slice 1 naturally flows into giving each event
its real fields.

```java
record ClaimFiled(String claimId, String policyId, BigDecimal claimedAmount, Instant occurredAt)
    implements ClaimEvents {}   // ✅ satisfies both accessors — names match, order irrelevant
```

---

### Concept: timestamps, ids, and replay determinism (the rules behind event fields)

Three tangled things, separated:

**a) Domain time vs system time are DIFFERENT fields.**
- `incidentDate` — when the accident/flood/injury actually happened. A **domain fact supplied by the
  claimant**; can be weeks before filing. Belongs in command + event.
- `occurredAt` — when the **system recorded** this event. System/recording time, generated by the
  machine at persist time.
- Never merge them. "When the accident happened" ≠ "when we logged the report." Conflating them is a
  real data bug. `ClaimFiled` carries BOTH.

**b) Do you even need `occurredAt` in the payload? The journal already has it.**
Akka's event journal auto-stamps every persisted event with metadata (sequence number + recording
timestamp). So recording-time exists for free outside your payload. Choice:
- Rely on journal metadata → no duplication, can't drift, but it's infra data, less convenient to
  surface to consumers.
- Put `occurredAt` in payload (what we chose) → explicit, part of the domain model, trivially
  available to views/reprojections, independent of journal implementation.
- **Rule:** if a timestamp is domain-meaningful (audit/SLA: "how long did approval take?"), model it
  explicitly in the payload; if it's pure infrastructure, lean on metadata. For a claim, recording
  time has audit significance → promote it into the event. (Events are append-only, so removing a
  field later is awkward — decide early.)

**c) Replay-determinism rule (the part that bites people).**
An entity does two things with very different rules:
- **Command handler** (`file(...)`) runs **once** when the command arrives. Calling `Instant.now()`
  here is fine — you capture "now" and bake it into the event; the non-determinism is frozen into a
  stored fact.
- **`applyEvent(...)`** runs **every time the entity is loaded/replayed**, possibly years later, many
  times. It MUST be a **pure function of the event** — never call `Instant.now()`, never generate a
  random id, never read outside state.

Why: if `applyEvent` computed the timestamp from the current clock, replaying the same event
tomorrow would produce different state than originally — state would change on every reload, which
destroys the core premise "replaying events reproduces state exactly."

**Pattern:** the handler generates every non-deterministic value (timestamp, generated id) and
stores it in the event; `applyEvent` only ever READS it back. The event is the carrier of
non-determinism, captured once. (Same logic applies to the generated `documentId` in the
SubmitDocument slice.) For testability, prefer injecting a `Clock` / passing the timestamp in over
calling static `Instant.now()`, so tests can pin time — but conceptually: handler stamps, event
stores, fold reads.

### Slice 2 (designed): `FileClaim`

**Command — thin intent (what the claimant supplies):**
```java
record FileClaim(String policyId, String claimantId, Instant incidentDate,
                 String description, BigDecimal claimedAmount) {}
```
Absent: no `claimId` (entity id from context), no `occurredAt` (system stamps it), no `status`
(derived), no `adjusterId` (not assigned yet).

**The single invariant — the empty-state guard.** Filing is the *creation* event, so the only rule
is "this claim doesn't already exist" — structurally identical to the customer's
`if (currentState().customerId() != null) error("already exists")`. Filing from any non-empty state
is illegal. Everything reachable after FILED is a *different* command.

**Event — `ClaimFiled`:** carries everything from the command PLUS enrichment: `claimId` (from
context) and `occurredAt` (stamped). The self-contained birth certificate of the claim.

**`applyEvent(ClaimFiled)` — builds initial real state from `emptyState()`:** set `status = FILED`,
copy domain fields, and crucially initialize the accumulators to empty:
`totalPaid = BigDecimal.ZERO`, `documents = List.of()`, `adjusterId = null`, `approvedAmount =
null`. This is where `emptyState()` matters — the null-everything starting point — and `ClaimFiled`
is the first fold step turning "nothing" into "a real FILED claim."

**`emptyState()` design decision:** what *is* an empty claim? Every field null/zero, and status =
what? Choice: a sentinel (`status = null`, empty-check tests `status == null`) vs a default. Cleaner
is a null/sentinel status so "has this claim been filed yet?" is unambiguous — mirrors the
customer's `customerId() == null` check. Decide consciously.

---

### Design status dashboard (checkpoint)

| Layer | Status | Locked | Open |
|---|---|---|---|
| State machine | Done | 7 states, all transitions, terminals, diagram | — |
| `ClaimStatus` enum | Done | minimal + `isTerminal()` | — |
| Events | Skeleton + 2/8 | sealed interface, `claimId()`/`occurredAt()` contract, 8 names; fields for `ClaimFiled` + `ClaimApproved` | full fields for other 6 |
| State (`ClaimState`) | Shape done | fields, accumulators, `outstanding()`/`isTerminal()`, `emptyState()` sentinel | refine as slices land |
| Commands | 2/8 | 8 names; fields for `FileClaim` + `ApproveClaim` | full fields for other 6 |
| Entity | Pattern + 2/8 handlers | handler shape (validate→enrich→persist→reply), `applyEvent` switch pattern, `file`/`approve` | other 6 handlers; concrete `applyEvent`; concrete `emptyState()` |
| Endpoint | Designed (below) | routes, 4 design decisions | implementation |

The **method** is fully established — every remaining slice is "turn the crank" with the
FileClaim/ApproveClaim patterns.

### The endpoint layer (designed)

**Principle: the endpoint is a thin translation layer (HTTP ⇄ command/query) with NO business
logic.** All invariants live in the entity. The endpoint only: parse request → build command →
invoke entity/view → map result to HTTP response. An `if` about claim *rules* in the endpoint is in
the wrong place.

**Routes (noun/sub-resource style chosen):**
```
POST   /claims                  → FileClaim       → returns claimId (201)
POST   /claims/{id}/adjuster    → AssignAdjuster
POST   /claims/{id}/documents   → SubmitDocument  → returns documentId
POST   /claims/{id}/info-requests → RequestInfo
POST   /claims/{id}/approval    → ApproveClaim
POST   /claims/{id}/denial      → DenyClaim
POST   /claims/{id}/payments    → RecordPayment
POST   /claims/{id}/withdrawal  → WithdrawClaim
GET    /claims/{id}             → read one claim
GET    /claims                  → list/query (the view)
```

**Decision 1 — sub-resources vs action verbs.** `POST /claims/{id}/approval` (create an approval,
noun) vs `POST /claims/{id}/approve` (verb). Noun style chosen because several actions create
sub-entities worth listing later (payments, documents → `GET /claims/{id}/payments`). Either is
fine; **pick one convention and apply consistently.**

**Decision 2 — request/response records are YET ANOTHER shape.** Pipeline:
`HTTP JSON → FileClaimRequest (API DTO) → FileClaim (command) → ClaimFiled (event) → ClaimState`.
Four shapes, each owned by a different concern. Keep the request DTO separate from the command
because: the public API contract evolves independently from internal commands; you may not want
domain types on the wire; the request lacks context fields (`claimId` from the URL) the command
needs. In a small kata you *can* collapse DTO into command, but knowing they're conceptually
distinct is the lesson — it's the command/event/state divergence extended outward.

**Decision 3 — reads: entity vs view.**
- Entity state read (`forEventSourcedEntity(id).method(...).invoke()`) → **strongly consistent**
  (every event so far), but single-entity-by-id only, needs a query method on the entity.
- View query → **eventually consistent** (small projection lag after a write), but multi-row,
  filterable by non-id fields.
- Rule: single-entity-by-id + need-it-now → entity; multi-row / filtered / dashboard → view. So
  `GET /claims/{id}` can hit the entity; `GET /claims?status=UNDER_REVIEW` MUST hit a view. This is
  where the eventual-consistency lag shows: file then immediately list → might not appear yet, by
  design.

**Decision 4 — entity errors → HTTP status.** `effects().error(...)` propagates back through the
`ComponentClient`. Map domain errors to **4xx**, not 500. Suggested convention: validation/domain
error → `400`; not found → `404`; illegal state transition → `409 Conflict`. Status mapping IS the
endpoint's job (translation, not business logic).

---

### Supporting type: `Document`

Documents accumulate in state, so they need their own value type (a nested record alongside the
state, or its own file):
```java
record Document(String documentId, String documentType, String fileRef, Instant submittedAt) {}
```

### Slice 3 (designed): `AssignAdjuster`  →  `AdjusterAssigned`

Transition: FILED → UNDER_REVIEW (first assignment). Reassignment allowed while still pre-decision.

**Command (thin):**
```java
record AssignAdjuster(String adjusterId) {}
```
**Invariants:**
- not terminal,
- status ∈ {FILED, UNDER_REVIEW, INFO_REQUESTED} (can't assign once a decision is made),
- `adjusterId` not null/blank.

**Event:**
```java
record AdjusterAssigned(String claimId, String adjusterId, Instant occurredAt)
    implements ClaimEvents {}
```
**`applyEvent` fold:** set `adjusterId = e.adjusterId()`; **conditional status transition** — if
current status is FILED → move to UNDER_REVIEW; if already UNDER_REVIEW/INFO_REQUESTED
(reassignment) keep the status unchanged.
> Teaching note: the status change is *conditional and derived in the fold* — the same event
> (`AdjusterAssigned`) either starts review or just swaps the adjuster, depending on current state.
> The event records the *fact* ("adjuster X assigned"); the fold decides the *consequence*.

### Slice 4 (designed): `RequestInfo`  →  `InfoRequested`

Transition: UNDER_REVIEW → INFO_REQUESTED (adjuster asks claimant for more documents).

**Command (thin):**
```java
record RequestInfo(String message) {}
```
**Invariants:**
- status must be UNDER_REVIEW,
- `message` not blank (you must say what you need).
- (an adjuster exists — guaranteed by being in UNDER_REVIEW).

**Event** (enriched with `adjusterId` from state):
```java
record InfoRequested(String claimId, String adjusterId, String message, Instant occurredAt)
    implements ClaimEvents {}
```
**`applyEvent` fold:** status → INFO_REQUESTED.
> Optional refinement: store the latest request `message` in state (e.g. a `pendingInfoRequest`
> field) so a view can show "waiting on claimant for: X". Not required for the state machine.

### Slice 5 (designed): `SubmitDocument`  →  `DocumentSubmitted`  (accumulating list + generated id)

Transition: allowed in FILED / UNDER_REVIEW / INFO_REQUESTED. Submitting while INFO_REQUESTED
answers the request and moves back to UNDER_REVIEW.

**Command (thin — note NO documentId):**
```java
record SubmitDocument(String documentType, String fileRef) {}
```
**Invariants:**
- not terminal,
- status ∈ {FILED, UNDER_REVIEW, INFO_REQUESTED} (no new docs after a decision),
- `documentType` / `fileRef` not blank.

**Event (handler GENERATES `documentId`):**
```java
record DocumentSubmitted(String claimId, String documentId, String documentType,
                         String fileRef, Instant occurredAt) implements ClaimEvents {}
```
> Teaching note — SECOND instance of "handler generates, event stores, fold reads": the
> `documentId` (e.g. a UUID) is generated in the command handler, frozen into the event, and only
> read back in the fold. NEVER generate the id inside `applyEvent` — it would differ on every replay
> and break determinism. Same rule as `occurredAt`.

**`applyEvent` fold:** append a new `Document` to the `documents` list (build a NEW immutable list —
never mutate the existing one), AND a **conditional status transition**: if current status is
INFO_REQUESTED → UNDER_REVIEW (claimant supplied the requested info); otherwise keep status.
```java
// fold sketch
var docs = new ArrayList<>(documents);
docs.add(new Document(e.documentId(), e.documentType(), e.fileRef(), e.occurredAt()));
var newStatus = (status == INFO_REQUESTED) ? UNDER_REVIEW : status;
return new ClaimState(..., List.copyOf(docs), ..., newStatus, ...);
```
> Teaching note: this is the slice that makes "state is a fold of MANY events" undeniable — the
> `documents` list grows one entry per `DocumentSubmitted`, something no single event holds.

### Slice 6 (designed): `DenyClaim`  →  `ClaimDenied`  (terminal)

Transition: UNDER_REVIEW → DENIED.

**Command (thin):**
```java
record DenyClaim(String reason) {}
```
**Invariants:**
- status must be UNDER_REVIEW,
- adjuster exists,
- `reason` not blank (denials must be justified — audit requirement).

**Event** (enriched with `adjusterId`):
```java
record ClaimDenied(String claimId, String adjusterId, String reason, Instant occurredAt)
    implements ClaimEvents {}
```
**`applyEvent` fold:** status → DENIED; `denialReason = e.reason()`.

### Slice 7 (designed): `RecordPayment`  →  `PaymentRecorded`  (overpay guard, partial payments, derived PAID)

Transition: APPROVED → APPROVED (partial payment) or APPROVED → PAID (final payment).

**Command (thin):**
```java
record RecordPayment(BigDecimal amount, String reference) {}
```
**Invariants:**
- status must be APPROVED,
- `amount` > 0,
- `amount` ≤ outstanding balance (`approvedAmount − totalPaid`) → **can't overpay**. This is the
  invariant that reads `approvedAmount` back out of state (written there by the Approve slice) — the
  state-as-relay-between-handlers concept.

**Event:**
```java
record PaymentRecorded(String claimId, BigDecimal amount, String reference, Instant occurredAt)
    implements ClaimEvents {}
```
**`applyEvent` fold:** `totalPaid = totalPaid.add(e.amount())`; then a **derived terminal
transition**: if `totalPaid` ≥ `approvedAmount` → status PAID, else stay APPROVED.
```java
var newTotal = totalPaid.add(e.amount());
var newStatus = (newTotal.compareTo(approvedAmount) >= 0) ? PAID : APPROVED;
```
> KEY teaching note — a state transition driven by ACCUMULATED DATA, computed in the fold, with NO
> dedicated command/event. Nobody commands "become paid"; PAID is the *consequence* of the last
> payment crossing the approved threshold. Partial payments fall out for free because `totalPaid`
> accumulates.
>
> Design nuance — "fully paid → PAID": two valid designs.
> - **(A) Derive in the fold (recommended for the kata):** single `PaymentRecorded` event; the fold
>   flips to PAID when the threshold is crossed. DRY; consumers recompute paid-ness from
>   `totalPaid` vs `approvedAmount`.
> - **(B) Emit a consequent milestone event:** when the final payment lands, the handler persists
>   TWO events — `PaymentRecorded` + a `ClaimSettled` (a 9th event). Gives downstream consumers
>   (views, notifications) a clean "claim settled" signal without recomputing the threshold.
>
> (B) is NOT the `StatusChanged` anti-pattern: `ClaimSettled` is a *named business milestone*, not a
> generic field-diff. The distinction: a meaningful domain event (settled, shipped, cancelled) = OK;
> a generic `StatusChanged(old,new)` = smell. Choose (A) for simplicity; reach for (B) when a
> consumer genuinely needs to react to the milestone.

### Slice 8 (designed): `WithdrawClaim`  →  `ClaimWithdrawn`  (terminal; where `isTerminal()` earns its keep)

Transition: any non-terminal state → WITHDRAWN (claimant pulls the claim).

**Command (thin):**
```java
record WithdrawClaim(String reason) {}
```
**Invariants:**
- `!status.isTerminal()` — the single guard, covering FILED / UNDER_REVIEW / INFO_REQUESTED /
  APPROVED. This is the slice that justifies factoring `isTerminal()` onto the enum: one predicate,
  reused, instead of listing four states.
- `reason` not blank.
> Design question answered: you CAN withdraw after APPROVED but before PAID (claimant changes mind
> before money moves); you CANNOT withdraw once PAID/DENIED/WITHDRAWN (terminal) — exactly what
> `!isTerminal()` expresses.

**Event:**
```java
record ClaimWithdrawn(String claimId, String reason, Instant occurredAt) implements ClaimEvents {}
```
**`applyEvent` fold:** status → WITHDRAWN.
> State-shape note: denial and withdrawal both carry a `reason`. You can keep `denialReason`
> separate from a `withdrawalReason`, OR unify them into one generic `closureReason` field set by
> whichever terminal event fired. Separate = explicit about *why* closed; unified = less state. Minor
> call — decide and stay consistent.

### Closing inventory — all 8 slices now specified

| Slice | Command | Event | Status effect (in fold) |
|---|---|---|---|
| File | `FileClaim` | `ClaimFiled` | empty → FILED |
| Assign | `AssignAdjuster` | `AdjusterAssigned` | FILED → UNDER_REVIEW (else keep) |
| Request info | `RequestInfo` | `InfoRequested` | UNDER_REVIEW → INFO_REQUESTED |
| Submit document | `SubmitDocument` | `DocumentSubmitted` | INFO_REQUESTED → UNDER_REVIEW (else keep); append doc |
| Approve | `ApproveClaim` | `ClaimApproved` | UNDER_REVIEW → APPROVED |
| Deny | `DenyClaim` | `ClaimDenied` | UNDER_REVIEW → DENIED |
| Record payment | `RecordPayment` | `PaymentRecorded` | APPROVED → APPROVED/PAID (derived) |
| Withdraw | `WithdrawClaim` | `ClaimWithdrawn` | any non-terminal → WITHDRAWN |

With this, every layer (state machine, enum, events, state, commands, entity handlers, endpoint) is
fully specified — implementation is now pure typing.

---

## 6. Cross-cutting: Views (read models) for claims

### One event stream, many projections

The write side (entity) is the source of truth. The read side is **any number of views, each shaped
for a specific query**, all built by folding the *same* `ClaimEvents` stream. This is the heart of
the CQRS read side: a view's row is **denormalized and optimized for its query — NOT a copy of
`ClaimState`.** Same events, different fold (`TableUpdater.onEvent`) than the entity's `applyEvent`
— the symmetry noted way back in section 1.

### One view/table vs many: granularity decides

A common beginner confusion: do I need a new View class per query? No.
- **Multiple `@Query` methods on ONE view/table** handle different *filters* of the same row shape.
  A single `claims_table` row `(claimId, status, claimantId, adjusterId, claimedAmount,
  approvedAmount, totalPaid)` can serve many queries:
  ```java
  @Query("SELECT * AS entries FROM claims_table WHERE status = :status")
  QueryEffect<ClaimEntries> getByStatus(String status);

  @Query("SELECT * AS entries FROM claims_table WHERE adjusterId = :adjusterId")
  QueryEffect<ClaimEntries> getByAdjuster(String adjusterId);

  @Query("SELECT * FROM claims_table WHERE claimId = :claimId")
  QueryEffect<ClaimRow> getById(String claimId);   // single-row → bare row type (see section 1 rule)
  ```
- **A separate view/table** is for a genuinely different *row granularity*. E.g. a portfolio
  financial view with **one row per adjuster** holding counts and summed amounts — different
  grain, so different table, different updater logic (accumulate on `ClaimApproved` /
  `PaymentRecorded`).

Rule: different *filter* of the same shape → another `@Query`. Different *shape/grain* → another
view/table.

### GOTCHA: the view must re-derive status — duplication of the fold's logic

The claims view's updater handles MANY of the 8 events (vs the customer view's 2), and here's the
real tension: to keep a `status` column correct, the updater has to react to **every status-changing
event** AND reproduce the *conditional* transitions from the entity's fold (e.g. `DocumentSubmitted`
moves INFO_REQUESTED→UNDER_REVIEW but otherwise leaves status alone; PAID is derived from
accumulated `totalPaid`). The `TableUpdater` can read the current row (`rowState()`) to make those
decisions — but now the **state-machine logic lives in two places** (entity fold + view updater).
That duplication is a genuine CQRS pain point. Three ways to handle it:

1. **Duplicate the conditional logic in the updater** (read `rowState()`, apply the same rules).
   Simple to start, but brittle — change the state machine and you must change it in two places.
2. **Denormalize the resulting status INTO status-changing events** (add a `status` field to those
   event payloads) so the view just reads it. This is a *controlled* denormalization — it is NOT the
   `StatusChanged` anti-pattern (the event is still a named business fact like `AdjusterAssigned`;
   we're adding a derived convenience field), but it does couple events to a derived value, so weigh
   it.
3. **Keep the view dumb**: store only raw facts that need no recomputation (adjusterId, amounts,
   timestamps, document list) and compute status-dependent answers at query time or let the
   single-claim read hit the *entity* (strongly consistent) instead of the view.

No free lunch — pick per view based on how much the query needs the state machine vs raw facts.

### Two superpowers to remember

- **Add a new view later, back-filled from history.** Because the event log is the truth, you can
  introduce a brand-new read model months from now and Akka replays the existing events to populate
  it — no write-side change, no data migration. (Try this as a kata extension: ship claims, then add
  the by-adjuster view afterward and watch it back-fill.)
- **Views are rebuildable/disposable.** Delete a view and replay to rebuild it. The read side is
  derived; only events are precious.

### Eventual consistency (recap, made concrete)

Views lag the write side by a small projection delay. File a claim, immediately `GET /claims` → it
may not be there yet. Strongly-consistent single-claim reads should hit the **entity**; queryable /
multi-row / dashboard reads hit the **view** and tolerate the lag. (This is endpoint Decision 3 from
section 5, seen from the read-model side.)

## 7. Cross-cutting: Snapshots

### The problem

Loading an event-sourced entity = replay every event through `applyEvent`, cost O(number of
events). A long-lived claim accumulates many events (every `DocumentSubmitted`, every partial
`PaymentRecorded`). For high-volume entities, replay-from-zero on every load gets slow.

### The mechanism

A **snapshot is the cached result of the fold at a given sequence number** — i.e. a persisted copy
of `ClaimState` as of event N. On load, the runtime restores the latest snapshot and replays **only
the events after it**, cost O(events since last snapshot). Akka's Java SDK takes snapshots
**automatically** at a configurable event-count threshold — you rarely write snapshot code yourself.

Semantically nothing changes: snapshots are a pure performance optimization. Events remain the
source of truth; you can always delete all snapshots and rebuild state by full replay (as long as
the events still deserialize).

### GOTCHA: this puts an asterisk on "state is freely refactorable"

Back in section 4 the rule was: *"State is pure in-memory derivation → restructure however/whenever,
just adjust `applyEvent`."* Snapshots qualify that. Once snapshots exist, **`ClaimState` is also
persisted**, so its shape acquires a (softer) serialization/versioning concern:

- State must **serialize cleanly** — records + Jackson (already configured in this project's
  `pom.xml`). Watch `BigDecimal`, `Instant`, and the `List<Document>` — all fine with the JSR-310
  module that's already a dependency.
- Change `ClaimState`'s shape and **old snapshots may no longer deserialize.** This is *softer* than
  event evolution because the fallback is safe: discard the incompatible snapshot and replay from
  events. You lose the optimization, not the data.

So the corrected hierarchy of evolution-rigidity:

| Type | Persisted? | Evolution rigidity |
|---|---|---|
| **Events** | Yes — the permanent truth | **Hardest.** Must stay backward-compatible forever; old events must always deserialize on replay. |
| **State (with snapshots)** | Yes — as an optimization | **Medium.** Should deserialize, but incompatible snapshots can be discarded + replayed from events. |
| **Commands / request DTOs** | No — transient | **Easiest.** Refactor freely. |

### Two evolution concerns, kept separate

- **Event schema evolution** = a hard requirement (events are forever). Strategies: only add
  *optional* fields, never repurpose/remove, version event types if needed.
- **Snapshot/state schema evolution** = softer. Strategies: tolerant deserialization, or simply
  accept that a state-shape change invalidates old snapshots and triggers a one-time replay — safe
  whenever events are intact.

### Why claims is a good snapshot example

A claim that's been paid in many installments, with many documents, has a long event tail. Snapshots
let it load fast without re-folding the whole payment history every time. It's also a clean way to
*feel* the events-vs-state distinction: the snapshot is literally "the fold result frozen," exactly
what `applyEvent` would recompute from scratch.

---

## 8. Scaling up to a full Claims Management Platform

The single-entity claim is the *core*. To make this a substantial system (and to meet the rest of
the Akka component palette and the genuinely hard distributed concepts), we grow it into a platform
of cooperating components. Build these on top of the working claim from sections 1–7.

### 8.0 The component palette (and what each teaches)

| Component | Akka type | New concept |
|---|---|---|
| `ClaimEntity` (done) | EventSourcedEntity | event sourcing basics |
| `PolicyEntity` | EventSourcedEntity (2nd) | cross-entity invariants, **reservation pattern** |
| `AdjusterWorkload` | **KeyValueEntity** | **ES vs KV** — current-value state, no history |
| `ClaimSettlementWorkflow` | **Workflow** | **saga / process manager**, durable steps + **compensation** |
| `NotificationConsumer` | **Consumer** | event-driven side effects, at-least-once, **idempotency** |
| `ClaimSlaTimer` | **TimedAction** + timers | **time-driven** transitions, durable timers |
| `ClaimView`, `PolicyClaimsView`, `AdjusterDashboardView`, `FinancialView` | View | multiple read models |
| `PolicyEndpoint`, `AdjusterEndpoint`, settle route | HttpEndpoint | more translation surface |

> Offline-accuracy note: the *concepts* below are exact; some Akka **API signatures** (Workflow
> step DSL, TimerScheduler, KeyValueEntity/Consumer base classes) I describe by shape — confirm the
> precise class/method names via IDE autocomplete against the cached SDK jars. The patterns are
> right even where a method name needs checking.

### 8.1 THE keystone concept — the entity is the consistency boundary

This is the most important idea in the whole platform.

- **One entity instance = one consistency (transaction) boundary.** Commands on a *single* entity
  are atomic: validate against current state, persist event(s), all-or-nothing.
- **Operations spanning TWO entities are NOT atomic.** There is **no distributed ACID transaction**
  across, say, a `ClaimEntity` and a `PolicyEntity`. They live on potentially different machines and
  each is its own island of consistency.

Consequence: an invariant that *spans* entities — "a claim's payout must not exceed the policy's
remaining coverage" — **cannot be enforced in a single atomic step.** This is not an Akka
limitation; it's the nature of distributed state. You handle it three ways, in increasing
sophistication:

1. **Put the invariant inside ONE entity** if you can (co-locate the data). Not always possible —
   coverage genuinely belongs to the policy, the claim amount to the claim.
2. **Reservation pattern** — a two-phase dance: *reserve* coverage on the policy (the policy
   atomically checks-and-holds), then approve the claim, then *confirm/release* the reservation.
   Each step is atomic *within one entity*; the sequence is coordinated externally.
3. **Saga / Workflow** — a durable orchestrator runs the multi-step sequence and **compensates**
   (undoes earlier steps) if a later one fails. This is where `ClaimSettlementWorkflow` comes in.

Everything in §8 is, at bottom, a way of living within consistency boundaries.

### 8.2 `PolicyEntity` (2nd event-sourced entity) + the reservation pattern

A policy is the financial backstop a claim draws against.

**State:**
```java
record PolicyState(String policyId, String holderId, BigDecimal coverageLimit,
                   BigDecimal reserved, BigDecimal paidOut, PolicyStatus status) {
    BigDecimal available() { return coverageLimit.subtract(reserved).subtract(paidOut); }
}
enum PolicyStatus { ACTIVE, LAPSED, CANCELLED }
```

**Commands / events (reservation pattern):**
| Command | Event | Rule / fold |
|---|---|---|
| `IssuePolicy(holderId, coverageLimit)` | `PolicyIssued` | empty-state guard; status ACTIVE |
| `ReserveCoverage(claimId, amount)` | `CoverageReserved` | **must be ACTIVE and `amount ≤ available()`** → `reserved += amount` |
| `ReleaseReservation(claimId, amount)` | `ReservationReleased` | `reserved -= amount` (compensation / claim denied) |
| `RecordPayout(claimId, amount)` | `PayoutRecorded` | `reserved -= amount; paidOut += amount` (reservation becomes a real payout) |
| `LapsePolicy()` / `CancelPolicy()` | `PolicyLapsed` / `PolicyCancelled` | status change |

> Concept: the cross-entity invariant ("don't pay more than coverage") is enforced **atomically
> inside the policy** at the moment of `ReserveCoverage` — `available()` check + `reserved +=` is one
> entity transaction. The claim never directly checks coverage; it asks the policy to reserve, and
> the policy is the authority. That's how you move a spanning invariant *into a single boundary*.

### 8.3 `AdjusterWorkload` — a KeyValueEntity (and ES vs KV)

Not everything needs an event log. An adjuster's *current open-case count* is a mutable counter; you
don't care about the history of every increment. That's a **KeyValueEntity**: state is stored
directly (the framework persists the latest state), no events, no fold.

**State:** `record AdjusterWorkload(String adjusterId, int openCases, int maxCapacity) {}`

**Commands:**
- `assignCase()` — invariant `openCases < maxCapacity` → `openCases + 1` (reject if at capacity).
- `closeCase()` — `openCases - 1` (floor at 0).
- `setCapacity(int)` — admin.

A KeyValueEntity handler looks like:
```java
public Effect<String> assignCase() {
    if (currentState().openCases() >= currentState().maxCapacity())
        return effects().error("Adjuster at capacity");
    return effects().updateState(currentState().withOpenCasesPlusOne()).thenReply("ok");
}
```
Note: `updateState(newState)` instead of `persist(event)` — no fold, no replay of history.

> **ES vs KV decision rule:** choose **EventSourcedEntity** when history/audit/temporal queries
> matter, when you want to derive multiple read models, or when "how did we get here" is valuable
> (claims, policies — regulated, auditable). Choose **KeyValueEntity** when you only ever need the
> *current* value and history is noise (a counter, a cache, a config flag, a session). Claims and
> policies are ES; adjuster load is KV. Picking the right one per piece of state is a core design
> skill. [contrast with §3 — KV has no fold, so the "events vs state" split collapses: state IS
> what's stored.]

### 8.4 `ClaimSettlementWorkflow` — saga / process manager

The crown jewel and the most building. When a claim is approved-for-settlement, several things must
happen across entities, and any can fail:

```
Step 1  reserve coverage on PolicyEntity   (ReserveCoverage)
Step 2  mark the claim approved            (ClaimEntity.approve)
Step 3  disburse payment                   (RecordPayment, possibly external gateway)
Step 4  record the payout on the policy     (RecordPayout) + mark claim PAID
        ── on success: notify
        ── on FAILURE at any step after 1: COMPENSATE → ReleaseReservation (undo the hold)
```

A **Workflow** is a durable, restartable state machine: it persists its progress, survives crashes,
resumes mid-sequence, and lets you define **compensations** (the saga "undo" actions) for steps that
already succeeded when a later step fails.

**Workflow state:** `record SettlementState(String claimId, String policyId, BigDecimal amount,
Step step) {}` where `Step` tracks where in the sequence you are.

**Concept — saga vs transaction:** you cannot wrap steps 1–4 in one ACID transaction (they touch
different entities/systems). Instead the workflow guarantees *eventual* completion-or-compensation:
either all steps commit, or the ones that committed get compensated, leaving the system consistent.
This is **eventual consistency with explicit rollback**, the distributed replacement for ACID.

> Why a Workflow and not just "the endpoint calls four things in a row": if the service crashes
> between step 2 and 3, a plain endpoint leaves a half-settled claim with a dangling reservation
> forever. The Workflow persists its step and resumes/compensates on recovery — durability is the
> whole point. (Confirm the exact Workflow step-definition DSL via IDE; conceptually: each step =
> {action, on-success → next step, on-failure → compensation}.)

### 8.5 `NotificationConsumer` — a Consumer (event-driven side effects)

A **Consumer** subscribes to an event stream (like a view's updater) but instead of writing a row it
performs a **side effect** — send an email/Slack, push to an external system, write to a log.

```java
@ComponentId("claim-notifier")
@Consume.FromEventSourcedEntity(ClaimEntity.class)
public class NotificationConsumer extends Consumer {
    public Effect onEvent(ClaimEvents event) {
        return switch (event) {
            case ClaimApproved e -> { sendEmail(e.claimId(), "approved"); yield effects().done(); }
            case ClaimDenied   e -> { sendEmail(e.claimId(), "denied");   yield effects().done(); }
            default -> effects().ignore();
        };
    }
}
```

> Concept — **at-least-once delivery → idempotency.** A consumer may see the *same event more than
> once* (after a crash/redelivery). So side effects must be **idempotent**: sending "approved" twice
> should not double-charge or double-email. Strategies: dedupe on `(claimId, eventType)`, use the
> event's identity as an idempotency key with the external system, or make the effect naturally
> repeatable. This is a different reliability model from the view (which is idempotent by
> construction — re-applying an `updateRow` is harmless). Side effects are where you must *design* for
> redelivery.

### 8.6 `ClaimSlaTimer` — TimedAction + durable timers

Real claims have SLAs: a claim sitting in INFO_REQUESTED too long should auto-escalate or auto-close.

- When a claim enters INFO_REQUESTED, **schedule a timer** (via `TimerScheduler`) to fire after some
  duration (compress to seconds/minutes for the kata) calling a **TimedAction**.
- The TimedAction, when it fires, checks the claim's *current* status; if it's *still*
  INFO_REQUESTED, it issues an escalation/auto-withdraw command. If the claim already moved on, it's
  a no-op.
- If the claim resolves earlier, **cancel the timer**.

> Concepts: (1) **time as a trigger for state transitions** — some events are caused by the *passage
> of time*, not a user command. (2) **Durable timers** survive restarts (the schedule is persisted),
> unlike an in-memory `ScheduledExecutorService`. (3) The TimedAction must **re-check current state**
> when it fires (the world may have changed since scheduling) — never blindly act on stale intent.
> (Confirm `TimerScheduler` / `TimedAction` exact API via IDE.)

### 8.7 Expanded views (multiple read models)

- **`ClaimView`** — by status / by adjuster / by id / by **policyId** (so a policy's claim history is
  queryable). [§1, §6]
- **`AdjusterDashboardView`** — open claims per adjuster, counts; consumes claim events. Different
  *grain* possible (one row per adjuster) → its own table.
- **`FinancialView`** — reserved vs paid per policy / portfolio totals; consumes **policy** events.
  Demonstrates a view over a *different* entity type.
- **`PolicyClaimsView`** — joins conceptually by carrying `policyId` on claim rows; teaches that
  views don't do SQL joins across entities — you **denormalize** the foreign key onto the row and
  query it.

> Concept: views can each subscribe to a *different* source entity; there are **no cross-entity
> joins** — you denormalize whatever you need onto the row at projection time. Multiple read models
> over the same and different streams is the read side of CQRS at full strength.

### 8.8 New concepts introduced in §8 (summary)

| Concept | Where | One-liner |
|---|---|---|
| Consistency boundary | 8.1 | one entity = one atomic unit; nothing spans two atomically |
| Reservation pattern | 8.2 | move a spanning invariant into one entity via reserve→confirm/release |
| ES vs KV entity | 8.3 | history-matters → ES; current-value-only → KV (no fold) |
| Saga / Workflow / compensation | 8.4 | durable multi-step orchestration; undo committed steps on later failure |
| Consumer + idempotency | 8.5 | event-driven side effects; design for at-least-once redelivery |
| Durable timers / TimedAction | 8.6 | time-triggered transitions that survive restarts; re-check state on fire |
| Multiple read models / no joins | 8.7 | denormalize foreign keys onto rows; many projections, many sources |

---

_(Notes continue below as the conversation progresses.)_
