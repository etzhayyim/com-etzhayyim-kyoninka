# com-etzhayyim-kyoninka （許認可 artificial organism）

A **robotaxi legal-deployment artificial organism** — the cross-jurisdiction permits,
licences, filings and statutory conditions a driverless-taxi service must hold
before it may legally carry a passenger on a public road, in **Japan and
worldwide**.

Platform vocabulary:

- **kotoba** is the sovereign data/compute substrate: CID, Datom log, WASM,
  auth and network primitives.
- **kototama** is the common organism/actor platform and runtime adapter layer.
- **app-aozora** is the AT Protocol product boundary: PDS, AppView, XRPC,
  lexicons, feeds/search and profile publication.
- **com-etzhayyim-kyoninka** is the domain organism. It may surface as an
  AT Protocol actor, but it does not run its own PDS.

The current runnable topology uses
[`langgraph-clj`](../../com-junkawasaki/langgraph-clj) StateGraph as the
orchestration backend (portable `.cljc`, supervised run, `interrupt-before`
human-in-the-loop, Datomic/in-mem checkpoints), in the same governed shape as
the three reference actors: **robotaxi-actor** (AR1 ⊣ SafetyGovernor) /
**gftd-talent-actor** (HR-LLM ⊣ PolicyGovernor) / **ai-gftd-itonami**
(ops-LLM ⊣ CertGovernor).

> **Why an actor layer?** "Can we launch a robotaxi here?" is not a model
> question — it is a question of binding law: which permits are mandatory in
> *this* jurisdiction, whether each is granted and unexpired, whether liability
> cover meets the statutory floor, whether the required filings were accepted,
> whether a remote operator is on record, and finally whether the regulator
> signs off. A language model can *advise* on all of this; it must never be the
> thing that says "go." This project seals the regulatory advisor (reg-LLM) into
> one node and wraps it with an independent **PermitGovernor** that enforces the
> legal invariants and routes every public-road go-live to a human authority.

See [`docs/DESIGN.md`](docs/DESIGN.md) and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md).

## The core contract

```
deployment facts (jurisdiction rules · permits · insurance · filings)
        │
        ▼
   ┌──────────┐    proposal     ┌────────────────┐
   │ reg-LLM  │ ──────────────▶ │ PermitGovernor │  (independent system)
   │ (sealed) │  readiness +    │ legal invariants│
   └──────────┘  cited facts    └───────┬────────┘
                            commit/hold ◀┴▶ escalate
                               │            │
                            assessment   human regulatory
                            datom        authority sign-off
```

**The actor never records a launch readiness the PermitGovernor would reject,
and never grants a permit or activates a vehicle** — observe → recommend only.
That single invariant is the kyoninka analog of robotaxi's safety contract.

## Jurisdictions modelled (demo)

| id | regime | max SAE | mandatory permits (sample) | min liability |
|----|--------|---------|----------------------------|---------------|
| `JP` | 改正道路交通法(2023) 特定自動運行 / 道路運送車両法 型式指定 / 道路運送法 | L4 | 型式指定・特定自動運行許可・旅客運送事業許可・個情法 | 2億円 |
| `US-CA` | CA Veh.Code §38750 + DMV AV regs / CPUC Driverless Deployment / FMVSS | L4 | type approval・DMV deployment・CPUC passenger・data | ~$5M 桁 |
| `DE` | StVG §1d–1l (AFGBV 2022, L4) / UNECE / GDPR | L4 | type approval・operating-area・technical supervisor・data | (高) |
| `ZZ` | (架空) AV 法未整備 | L2 | — | 0 |

The rulebook is **data** (`kyoninka.store/demo-data`): adding a jurisdiction is
an `:jurisdiction/register` ground datom, not a code change.

## Run

```bash
clojure -M:dev:run     # drive deployments through one DeploymentActor
clojure -M:dev:test    # the permitting contract as executable tests
clojure -M:lint        # clj-kondo (errors fail)
```

Demo walks: ingest a new jurisdiction+deployment → `dp-jp` clean L4 (governor
passes → authority sign-off interrupt → 公安委員会 approves → recorded) →
`dp-ca-bad` (missing CPUC permit + expired type approval + under-insured → HARD
HOLD) → `dp-zz` (L4 in an L2-only regime → HOLD) → `permit/assess` pre-
application readiness (auto-commit) → phase-0 survey-only (held) → the append-
only permitting-genealogy ledger → the same contract on `DatomicStore`.

## Layout

| File | Actor / role |
|---|---|
| `src/kyoninka/store.cljc` | SSoT — jurisdictions · deployments · permits · insurance · filings; `MemStore` ‖ `DatomicStore` (langchain.db `:db-api`); append-only ledger |
| `src/kyoninka/regllm.cljc` | **reg-LLM** — the contained intelligence node (regulatory advisor); mock ‖ real LLM via `langchain.model` |
| `src/kyoninka/governor.cljc` | **PermitGovernor** — independent legal invariants; HOLD on missing/expired permits, under-insurance, over-level, missing filings, no-actuation |
| `src/kyoninka/phase.cljc` | Phase 0→3 staged rollout (survey-only → supervised); launch is never auto |
| `src/kyoninka/deployment.cljc` | **DeploymentActor** — the langgraph-clj StateGraph (1 run = 1 op) |
| `src/kyoninka/sim.cljc` | demo driver |
| `test/kyoninka/governor_contract_test.clj` | the permitting invariant, executable |
| `test/kyoninka/store_contract_test.clj` | `MemStore ≡ DatomicStore` |

## Status

Reference design + runnable skeleton. The jurisdiction rulebook is illustrative
(verify against current statutes before any real filing), and the reg-LLM is a
deterministic mock. The **actor topology, the legal invariants, the human
authority sign-off, the phase gate, and the append-only permitting ledger are
real and tested.** Productionizing means (1) curating the per-jurisdiction
rulebook with counsel, (2) swapping `regllm/mock-advisor` for `llm-advisor` on a
real `langchain.model`, and (3) optionally binding the store to kotoba-server
(kotobase.net) so the ledger is an actor-signed CACAO graph (see ADR-0001).
