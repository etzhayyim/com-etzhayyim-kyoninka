# com-etzhayyim-kyoninka — Design

許認可 (kyoninka) = *permits, licences and approvals*. This actor answers one
question for a robotaxi service, per jurisdiction, with an immutable paper
trail: **"are we legally clear to operate here, and who must sign off?"**

It is the fourth instance of the workspace actor pattern
(`.cursor/rules/always/actor-pattern-rule.mdc`): a contained intelligence node
that returns *proposals only*, an independent governor that censors them
against hard invariants, a phase gate that adds caution, and an append-only
audit ledger. The single invariant mirrors robotaxi's safety contract:

> **The actor never records a launch readiness the PermitGovernor would reject,
> and never grants a permit or activates a vehicle. observe → recommend only.**

## 1. Why this domain needs an actor, not a chatbot

Regulatory go/no-go is a *liability* decision. The failure modes a plain LLM
exhibits are exactly the ones that get a service shut down:

- **Hallucinated compliance** — "you're good to launch" when the CPUC passenger
  permit was never granted, or the type approval expired last quarter.
- **Jurisdiction blur** — applying California's framework to a regime that has
  no Level-4 statute at all.
- **Silent actuation** — a tool-using agent that "files" or "activates"
  something on the strength of its own judgement.

The fix is structural, not a better prompt: the model proposes, a separate
rules engine over the *facts* decides, and a human authority makes the final
public-road call. The LLM's value — reading messy filings, summarizing a
rationale, citing the facts it used — is preserved; its authority is removed.

## 2. Topology (langgraph-clj StateGraph)

One graph run = one operation. Two flows share the graph:

```
intake ─┬─(record op)→ record ─────────────────────────────────────→ END
        └─(assess op)→ advise → govern → decide ─┬─ commit ─────────→ END
                       (reg-LLM) (PermitGov)      ├─ hold ───────────→ END
                                                  └─ request-approval ┐ (interrupt)
                                                       │ approved → commit
                                                       └ rejected → hold
```

- **record path** (`:jurisdiction/register`, `:deployment/register`,
  `:permit/record`, `:insurance/record`, `:filing/record`) — the *observe*
  charter. Always on, never an LLM call, never an actuation; it turns the legal
  state of the world into durable EAVT ground datoms.
- **assess path** (`:permit/assess`, `:launch/assess`) — the reg-LLM proposes,
  the PermitGovernor censors, the phase gates, and a public-road launch always
  interrupts for a human (`interrupt-before #{:request-approval}`).

## 3. The three injection seams (swap, core invariant)

| Seam | default | production |
|---|---|---|
| **Store** | `MemStore` | `DatomicStore` (langchain.db `:db-api`) → real Datomic Local / kotoba-server, same record |
| **Advisor** | `regllm/mock-advisor` (deterministic, mirrors the governor) | `regllm/llm-advisor` on a `langchain.model` ChatModel (Anthropic etc.) |
| **Phase** | `3 supervised` | `0 survey-only` → … as intervention/override rates fall |

The store speaks to its backend **only** through the langchain.db `:db-api` map
`{:q :transact! :db :pull :entid}`. Both `langchain.db/api` (in-process EAVT)
and `langchain.kotoba-db/kotoba-api` (kotoba-server XRPC) implement it, so the
same `DatomicStore` record runs on either — proven by `store_contract_test`
(`MemStore ≡ DatomicStore`).

## 4. PermitGovernor — the legal invariants

Rules over the EAVT ground datoms (a different system than the LLM). HARD
violations force HOLD and **cannot be overridden by a human**; the final go for
a clean launch is *still* a human authority sign-off (high-stakes).

| # | invariant | scope | failure |
|---|-----------|-------|---------|
| 1 | jurisdiction recognized | both | `:unknown-jurisdiction` |
| 2 | target SAE level ≤ jurisdiction max | both | `:over-permitted-level` |
| 3 | every mandatory permit held, `granted`, unexpired (`today`) | launch | `:missing-permits` |
| 4 | active liability cover ≥ statutory minimum | launch | `:under-insured` |
| 5 | every mandatory filing `accepted` | launch | `:missing-filings` |
| 6 | remote operator present when required | launch | `:no-remote-operator` |
| 7 | proposal effect is `:assessment` (no grant/activation) | both | `:no-actuation` |
| 8 | confidence ≥ floor (0.6) | soft | escalate |
| 9 | a public-road launch is high-stakes | soft | always human |

`:permit/assess` (pre-application: *can we even file?*) checks only 1, 2, 7 —
the permits/insurance/filings are precisely what the application is *for*.
`:launch/assess` (go-live) checks all of 1–7 and is always high-stakes.

Dates are integers (`yyyymmdd`) compared against a `:today` injected via context
(default `20260627`) — no date library, fully `.cljc`-portable. Money is integer
JPY. SAE level and fleet are integers. (Charter: integers, not floats.)

## 5. Phase gate (staged rollout)

`0 survey-only` (record facts, emit no assessments — legal research / shadow) →
`1 assisted` (assessments, always human) → `2 assisted-prep`
(`:permit/assess` may auto-commit; launch still human) → `3 supervised`
(pre-application auto-commits when clean+confident; **a launch is never auto**).
The phase can only *add* caution to the governor's disposition.

## 6. The ledger = permitting genealogy

Every `commit`/`hold`/`record` appends to an immutable ledger. This is the
property a mutable DB row or a SaaS dashboard can't give you: a regulator (or an
incident review) can replay *exactly* which facts justified each readiness
decision and who signed off — the data-sovereignty / traceability core shared by
all four actors.

## 7. kotoba-server (kotobase.net) — optional sovereign ledger

Per the actor-pattern rule, an actor can hold its **own Ed25519 key**; the
key-derived IPNS name *is* that actor's graph, so depth-1 self-mint to its own
ledger is structurally authorized (no owner hand-off, no shared token). To run
the kyoninka ledger as an actor-signed CACAO graph, follow
`ai-gftd-itonami/src/itonami/cacao.clj` + `kotoba.clj`: bind `DatomicStore` to
`langchain.kotoba-db/kotoba-api`, keep the private key in `.kyoninka/identity.edn`
(gitignored, never committed). This is a seam, not a dependency — the actor runs
fully offline on `MemStore`/`DatomicStore` today.

## 8. What is real vs. illustrative

Real and tested: the topology, the seven invariants, the human-authority
interrupt, the phase gate, the append-only ledger, backend equivalence.
Illustrative: the **per-jurisdiction rulebook** (`demo-data`) and the reg-LLM
mock. The statutory specifics (which permit, which minimum, which filing) must
be curated with counsel per jurisdiction before any real filing — but adding or
correcting a jurisdiction is a *data* edit (`:jurisdiction/register`), not a
code change, which is the point of putting the rulebook in the store.
