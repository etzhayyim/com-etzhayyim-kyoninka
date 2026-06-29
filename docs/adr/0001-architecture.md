# ADR-0001 — com-etzhayyim-kyoninka architecture (robotaxi legal-deployment actor)

- Status: accepted
- Date: 2026-06-27
- Org: etzhayyim
- Relates to: robotaxi-actor (AR1 ⊣ SafetyGovernor), gftd-talent-actor
  (HR-LLM ⊣ PolicyGovernor), ai-gftd-itonami (ops-LLM ⊣ CertGovernor);
  `.cursor/rules/always/actor-pattern-rule.mdc`

## Context

We need a system that determines the legal/administrative readiness to operate a
robotaxi service across jurisdictions (Japan and worldwide) and produces an
auditable record of *why* and *who signed off*. The naive approach — ask an LLM
"can we launch here?" — is unacceptable for a liability-bearing decision: it
hallucinates compliance, blurs jurisdictions, and (as a tool-using agent) can
take outward actions on its own judgement.

## Decision

Build kyoninka as the **fourth instance of the workspace actor pattern**, not a
bespoke design. Concretely:

1. **Containment + independent governor + immutable ledger.** The regulatory
   advisor (**reg-LLM**) is sealed into one node and returns *proposals only*
   (`:recommendation` + rationale + cited facts, `:effect :assessment`). An
   independent **PermitGovernor** censors every proposal against hard legal
   invariants over the EAVT ground datoms and dispositions it
   commit / hold / human-approval. Single invariant: *the actor never records a
   launch readiness the governor would reject, and never grants a permit or
   activates a vehicle.* Every commit/hold/record appends to an append-only
   ledger (the permitting genealogy).

2. **langgraph-clj StateGraph, 1 run = 1 operation.** No unbounded inner loop;
   `interrupt-before #{:request-approval}` is the human-in-the-loop seam — a
   public-road launch always pauses for a regulatory authority sign-off, even
   when the governor is fully clean (high-stakes).

3. **Three injection seams.** Store (`MemStore` ‖ `DatomicStore`), Advisor
   (`mock-advisor` ‖ `llm-advisor` on `langchain.model`), Phase (0→3). The core
   is invariant under all three.

4. **Store is `:db-api`-driven.** The store talks to its backend only through
   the langchain.db `{:q :transact! :db :pull :entid}` map; `langchain.db/api`
   and `langchain.kotoba-db/kotoba-api` both implement it, so the same record
   runs in-memory, on real Datomic, or on the kotoba-server pod. Enforced by a
   `MemStore ≡ DatomicStore` contract test.

5. **The jurisdiction rulebook is data, not code.** Mandatory permits, statutory
   minimum cover, required filings, max SAE level and authority are attributes of
   a `jurisdiction` ground datom. Adding/correcting a jurisdiction is a
   `:jurisdiction/register` transaction reviewed by counsel — no code change.

6. **Integers, not floats** (charter): money in JPY, dates as `yyyymmdd`
   integers (expiry compared to an injected `:today`), SAE level / fleet as
   integers — keeping the whole actor `.cljc`-portable (JVM / SCI / cljs / WASM)
   with no date/decimal libraries.

## The seven invariants (PermitGovernor)

jurisdiction recognized · target level ≤ jurisdiction max · all mandatory
permits granted & unexpired · liability cover ≥ statutory minimum · all mandatory
filings accepted · remote operator present when required · no-actuation
(`:effect` must be `:assessment`). Plus soft rules: confidence floor → escalate;
a public-road launch is always high-stakes → human authority.

`:permit/assess` (pre-application) checks the framework invariants only (1, 2,
7); `:launch/assess` (go-live) checks all seven and is always high-stakes.

## Consequences

- A clean deployment cannot auto-launch; it interrupts for a named authority
  (e.g. 都道府県公安委員会 / 運輸局 / DMV / KBA). A deficient one is held with the
  exact violated rules in the ledger, and the hold cannot be overridden by a
  human (you cannot approve past a missing permit or below-minimum cover).
- The reg-LLM can be upgraded (or swapped to a real model) without touching the
  legal guarantees; the guarantees live in the governor and the data.
- The rulebook is illustrative until curated with counsel per jurisdiction;
  because it is data, that curation is a reviewed transaction, not a refactor.

## Follow-ups

- Register the repo in the west manifest via a single-entry GitHub-API clean
  commit (`manifest/repos.edn` → regenerate `west.yml`), pin == repo HEAD —
  same procedure as the other actors. (Deferred: requires creating/pushing the
  GitHub repo; do when the user authorizes the outward action.)
- Optional sovereign ledger on kotoba-server (kotobase.net): give the actor its
  own Ed25519 identity (`.kyoninka/identity.edn`, gitignored) and bind the store
  to `langchain.kotoba-db`, per `ai-gftd-itonami/src/itonami/cacao.clj`.
- Curate the per-jurisdiction rulebook (JP / US states / EU member states / SG /
  CN / …) with legal counsel; extend `:permit` / `:filing` taxonomies as needed.
