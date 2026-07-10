(ns kyoninka.phase
  "Phase 0→3 staged rollout, gating only the ASSESS ops (recommendations).
  Recording legal-state ground datoms (the observe function) is always on —
  that is kyoninka's charter (durable EAVT observations of the permitting
  state). The phase only decides how much autonomy the *recommendations* have,
  and can only add caution.

    0 survey-only    — record legal facts; emit NO readiness assessments yet
                       (legal-research / shadow mode).
    1 assisted       — assessments allowed, but always human approval.
    2 assisted-prep  — pre-application readiness (:permit/assess) may auto-
                       commit; a launch still routes to a human.
    3 supervised     — pre-application auto-commits when clean+confident; a
                       public-road launch (:launch/assess) is high-stakes and
                       ALWAYS routes to a human regulatory authority (never auto).")

(def record-ops #{:jurisdiction/register :deployment/register :permit/record
                  :insurance/record :filing/record})
(def assess-ops #{:permit/assess :launch/assess})

(def phases
  {0 {:label "survey-only"   :assess #{}        :auto #{}}
   1 {:label "assisted"      :assess assess-ops :auto #{}}
   2 {:label "assisted-prep" :assess assess-ops :auto #{:permit/assess}}
   3 {:label "supervised"    :assess assess-ops :auto #{:permit/assess}}})

(def default-phase
  "The phase used when `context` carries no :phase at all
  (kyoninka.deployment: (:phase context phase/default-phase)), AND the
  fallback `gate` itself uses for an unrecognized phase NUMBER
  (`(get phases phase (get phases default-phase))`). This is directly
  reachable by any ordinary caller that simply omits :phase -- not just
  malformed/malicious input -- so it must be the MOST CONSERVATIVE
  phase, never the most permissive: 'can only add caution' (this
  namespace's own docstring) has to hold for a MISSING phase too, not
  only an explicitly-set low one. This was 3 (supervised, the most
  permissive tier -- :permit/assess can auto-commit) until a live check
  confirmed a caller who forgets :phase silently got maximum autonomy
  instead of the safe default -- the same accidental-fail-open shape
  already found and fixed this session in the shared talent.phase
  template this actor's own README names as its reference lineage
  (gftd-talent-actor), plus its siblings newscaster.phase and
  wami.phase, which both inherited the same bug. 1 (assisted) matches
  those fixes and the sibling ports that already chose it correctly
  (tsumugu.phase / shiropico.phase) -- :launch/assess remains unaffected
  either way (never in any phase's :auto set)."
  1)

(defn record-op? [op] (contains? record-ops op))

(defn gate
  "Adjust an assess op's governor disposition for the rollout phase.
  Returns {:disposition kw :reason kw|nil}. launch/assess is never in :auto, so
  it always escalates (a public-road go-live is a human authority call)."
  [phase {:keys [op]} disposition]
  (let [{:keys [assess auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold disposition)        {:disposition :hold :reason nil}
      (not (contains? assess op))  {:disposition :hold :reason :phase-disabled}
      (and (= :commit disposition)
           (not (contains? auto op))) {:disposition :escalate :reason :phase-approval}
      :else                        {:disposition disposition :reason nil})))

(defn verdict->disposition [v]
  (cond (:hard? v) :hold (:escalate? v) :escalate :else :commit))
