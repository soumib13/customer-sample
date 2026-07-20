# Akka Workshop - Training sample

Companion sample project for a **five-day Akka SDK developer training**. Each
day is a self-contained deck with a matching git branch (or two). Clone,
`git checkout` your current day's branch, and code along.

The track composes flexibly: run **Day 1 alone as a 3-hour intro**, **Days 1–2
as a day-long deep-dive**, or **the full arc as a five-day mastery track**.

## The 5-day training

| Day | Deck | What you'll learn | Branch(es) |
|-----|------|-------------------|------------|
| 1 | **[Fundamentals](https://pradeeploganathan.github.io/akka-presentations/training/akka-sdk-fundamentals/generated/overview/)** | Entities (value + event-sourced), views, endpoints, event sourcing from first principles | `main` |
| 2 | **[Workflows & Consumers](https://pradeeploganathan.github.io/akka-presentations/training/akka-sdk-workflows/generated/overview/)** | Saga orchestration with compensation, timers, cross-component calls, and reactive event processing | `workflows` → `consumers` |
| 3 | **[Agents & MCP](https://pradeeploganathan.github.io/akka-presentations/training/akka-sdk-agents/generated/overview/)** | LLM-backed components with function tools, session memory, structured responses, and MCP server exposure | `agents` |
| 4 | **[Testing](https://pradeeploganathan.github.io/akka-presentations/training/akka-sdk-testing/generated/overview/)** | TestKit patterns per component, integration + chaos + contract tests, idempotency | `tests` |
| 5 | **[Deploy & Multi-region](https://pradeeploganathan.github.io/akka-presentations/training/akka-sdk-deploy/generated/overview/)** | `akka` CLI deploy, rolling upgrades, autoscale, multi-region topology, observability, runbooks | *no new branch — deploys the code on `agents`* |

Full presentations catalogue: <https://pradeeploganathan.github.io/akka-presentations/>

## Branch progression

Each branch adds one teaching concept on top of the previous. `git diff main workflows`
(or any adjacent pair) shows exactly what was introduced.

```
main         Customer + CustomerPreferences (event-sourced + KVE)
  ↓          + Order aggregate, OrderWorkflow with saga compensation,
workflows      abandon-cart timer, Inventory/Payment KVE stubs
  ↓          + CustomerAuditConsumer, AuditLogEntity
consumers
  ↓          + SupportAgent (OpenAI), CustomerMcpServer (MCP),
agents         CustomerEntity.get() query
  ↓          + Baseline test suite (Day 4 lab starting point)
tests
```

Day 5's deck doesn't add a branch — its whole story is "deploy the code you already have on `agents`."

Bug fixes made on `main` should be merged forward through the chain.

## Prerequisites

- **Java 21+**
- **Maven 3.8+**
- **OpenAI API key** — optional; only needed on the `agents` (and later) branches
- **Docker + a container registry** — optional; only needed for the Day-5 deploy lab
- **An Akka account** — for the Day-5 hands-on deploy

No database, no message broker, no Kubernetes required for local development.

## Quick start

```bash
git clone https://github.com/PradeepLoganathan/akka-workshop.git
cd akka-workshop

# start on Day 1 material
git checkout main
mvn compile exec:java

# in another terminal
curl -XPOST http://localhost:9000/api/customers \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"c1","FirstName":"Ada","LastName":"Lovelace"}'

curl http://localhost:9000/api/customers
```

Local console: <http://localhost:9000/akka/console>

## What lives where

```
src/main/java/com/pradeepl/akkakata/
├── domain/
│   ├── commands/      Intent — requests that come into aggregates
│   ├── events/        Facts — sealed interfaces per aggregate
│   ├── model/         State records
│   ├── entities/      Value + event-sourced entities
│   ├── workflows/     Order orchestration (workflows branch onward)
│   └── agents/        SupportAgent (agents branch)
├── views/             Read-optimised projections
├── consumers/         Reactive processors (consumers branch onward)
├── mcp/               MCP server (agents branch)
└── api/               HTTP endpoints

src/test/java/com/pradeepl/akkakata/   (tests branch onward)
    baseline TestKit suite for Day 4
```

## Working through the training

### Day 1 — Fundamentals (`main`)

- `POST /api/customers`, `GET /api/customers`, `DELETE /api/customers/{id}`
- `PUT /api/customers/{id}/preferences`, `GET /api/customers/{id}/preferences`
- Focus: entities (value + event-sourced), views, endpoints, event sourcing

### Day 2 — Workflows & Consumers (`workflows` → `consumers`)

```bash
git checkout workflows
mvn compile exec:java
```

- `POST /api/orders` — starts the OrderWorkflow saga
- Try `sku-1`, `sku-2`, `sku-3` for the happy path (SHIPPED)
- Try any other SKU to trigger compensation (FAILED)
- `GET /api/orders/{orderId}` — final entity state
- `GET /api/orders/customer/{customerId}` — view projection

Later in the day, switch to `consumers`:

```bash
git checkout consumers
```

- Create then delete a customer, then `GET /api/audit/customers/{id}`
- Audit entries appear asynchronously via `CustomerAuditConsumer`

### Day 3 — Agents & MCP (`agents`)

```bash
git checkout agents
export OPENAI_API_KEY=sk-...
mvn compile exec:java

# ask the support agent
curl -XPOST http://localhost:9000/api/support/session-1 \
  -H 'Content-Type: application/json' \
  -d '{"question":"Where are customer c1'\''s orders?"}'

# tools/list on the MCP server (used by Claude Desktop, Cursor, etc.)
curl -XPOST http://localhost:9000/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

### Day 4 — Testing (`tests`)

```bash
git checkout tests
mvn test
```

Nine seed tests should pass (`CustomerEntityTest`, `OrderEntityTest`,
`CustomerPreferencesEntityTest`). The lab extends this suite — add
integration tests, chaos tests, contract tests as covered in the deck.

### Day 5 — Deploy & Multi-region

```bash
# still on tests (or agents — the code is what matters here, not the test suite)
akka auth login
akka services deploy akka-workshop ghcr.io/<you>/akka-workshop:1.0
```

See the [deck](https://pradeeploganathan.github.io/akka-presentations/training/akka-sdk-deploy/generated/overview/) for the full checklist.

## Additional docs

- [`BUILD-TUTORIAL.md`](BUILD-TUTORIAL.md) — line-by-line walkthrough of the initial `main`-branch build
- [`LEARNING-NOTES.md`](LEARNING-NOTES.md) — background reading on the concepts introduced

## Notes on this sample

This code is intentionally minimal — enough to teach the SDK's shape, not a
template for production. Some deliberate simplifications:

- `InventoryEntity` and `PaymentEntity` on the `workflows` branch are
  in-memory stubs that make the workflow's cross-component orchestration
  visible without external services.
- The abandon-cart timer on the `workflows` branch is scheduled but never
  fires under the current workflow shape — the workflow charges immediately
  after reserve. Left in as a teaching artifact for the timer block; a
  follow-up will restructure the workflow to pause and wait for payment.
- The `tests` branch ships nine baseline TestKit tests as a Day 4 starting
  point — the lab extends them with integration, chaos, and contract tests.
