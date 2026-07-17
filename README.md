# Akka Workshop — training sample

Companion sample project for the **Akka SDK training track**. Each branch in
this repo maps to a checkpoint in one of the training decks — clone,
`git checkout` the branch for your current lesson, and code along.

## The training track

All three decks are hosted on GitHub Pages:

| Deck | Duration | Sample branch(es) | Link |
|------|----------|-------------------|------|
| **Akka SDK Fundamentals** | 3 hours | `main` | [Open deck](https://pradeeploganathan.github.io/akka-presentations/training/akka-sdk-fundamentals-3h/generated/overview/) |
| **Workflows & Consumers** | Day-long | `workflows`, `consumers` | [Open deck](https://pradeeploganathan.github.io/akka-presentations/training/akka-sdk-workflows-day/generated/overview/) |
| **Akka SDK Mastery** | 5 days | `agents` | [Open deck](https://pradeeploganathan.github.io/akka-presentations/training/akka-sdk-mastery-5d/generated/overview/) |

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
  ↓          + SupportAgent (OpenAI), CustomerMcpServer (MCP)
agents
```

Bug fixes made on `main` should be merged forward through the chain.

## Prerequisites

- **Java 21+**
- **Maven 3.8+**
- **OpenAI API key** — optional; only needed on the `agents` branch
- **Docker + a container registry** — optional; only needed for the Day-5 deploy lab

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
```

## Working through the training

### Day 1 — Fundamentals (`main`)

- `POST /api/customers`, `GET /api/customers`, `DELETE /api/customers/{id}`
- `PUT /api/customers/{id}/preferences`, `GET /api/customers/{id}/preferences`
- Focus: entities (value + event-sourced), views, endpoints, event sourcing

### Day 2 morning — Workflows (`workflows`)

```bash
git checkout workflows
mvn compile exec:java
```

- `POST /api/orders` — starts the OrderWorkflow saga
- Try `sku-1`, `sku-2`, `sku-3` for the happy path (SHIPPED)
- Try any other SKU to trigger compensation (FAILED)
- `GET /api/orders/{orderId}` — final entity state
- `GET /api/orders/customer/{customerId}` — view projection

### Day 2 afternoon — Consumers (`consumers`)

```bash
git checkout consumers
mvn compile exec:java
```

- Create then delete a customer, then `GET /api/audit/customers/{id}`
- Audit entries appear asynchronously via `CustomerAuditConsumer`

### Days 3–5 — Agents & MCP (`agents`)

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
- Tests are intentionally sparse; extending them is the Day 4 lab.
