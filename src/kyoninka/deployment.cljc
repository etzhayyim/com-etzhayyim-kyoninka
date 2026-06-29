(ns kyoninka.deployment
  "DeploymentActor — one kyoninka operation = one supervised actor run, a
  langgraph-clj StateGraph. Two flows share one auditable graph:

    ingest (record path):  intake → record → END
        jurisdiction rules / deployments / permits / insurance / filings become
        durable EAVT ground datoms. This is the observe charter; always on,
        never an LLM call, never an actuation.

    assess path:  intake → advise → govern → decide → commit|hold|approval
        the reg-LLM (sealed) proposes a legal-readiness recommendation; the
        PermitGovernor enforces the jurisdiction's mandatory-permit / insurance
        / filing invariants; the phase gate adds caution; a public-road launch
        recommendation ALWAYS routes to a human regulatory authority
        (interrupt-before :request-approval).

  Single invariant (the kyoninka analog of robotaxi's safety contract):
    the actor never records a launch readiness the PermitGovernor would reject,
    and never grants a permit / activates a vehicle."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [kyoninka.regllm :as regllm]
            [kyoninka.governor :as gov]
            [kyoninka.phase :as phase]
            [kyoninka.store :as store]))

(defn- request->record
  "Map an ingest request to a store ground-datom record."
  [{:keys [op jurisdiction deployment value]}]
  (case op
    :jurisdiction/register {:kind :jurisdiction :id jurisdiction :value value}
    :deployment/register   {:kind :deployment   :id deployment   :value value}
    :permit/record         {:kind :permit       :id deployment   :value value}
    :insurance/record      {:kind :insurance    :id deployment   :value value}
    :filing/record         {:kind :filing       :id deployment   :value value}))

(defn- subject [{:keys [deployment jurisdiction]}] (or deployment jurisdiction))

(defn build
  "Compiles a DeploymentActor bound to `store` (any kyoninka.store/Store).
  opts: :advisor (default mock), :checkpointer (default in-mem)."
  [store & [{:keys [advisor checkpointer]
             :or   {advisor      (regllm/mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}   ; :phase + :today (+ future authn)
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; ── ingest path: record a legal-state ground datom (observe) ──
      (g/add-node :record
        (fn [{:keys [request]}]
          (let [rec (request->record request)
                f   {:t :recorded :op (:op request) :deployment (subject request)
                     :disposition :record :basis (:kind rec)}]
            (store/record-datom! store rec)
            (store/append-ledger! store f)
            {:disposition :record :audit [f]})))

      ;; ── assess path ──
      (g/add-node :advise
        (fn [{:keys [request context]}]
          (let [today (:today context gov/default-today)
                p     (regllm/-advise advisor store today request)]
            {:proposal p :audit [(regllm/trace request p)]})))

      (g/add-node :govern
        (fn [{:keys [request proposal context]}]
          {:verdict (gov/check request proposal store
                               {:today (:today context gov/default-today)})}))

      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [base (phase/verdict->disposition verdict)
                ph   (:phase context phase/default-phase)
                {:keys [disposition reason]} (phase/gate ph request base)]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(cond-> (gov/hold-fact request verdict)
                         reason (assoc :phase-reason reason :phase ph))]}
              :escalate
              {:disposition :escalate
               :audit [{:t :approval-requested :op (:op request) :deployment (subject request)
                        :reason (or reason (if (:high-stakes? verdict) :authority-signoff
                                               :low-confidence))
                        :recommendation (:recommendation proposal)
                        :phase ph :confidence (:confidence verdict)}]}
              :commit
              {:disposition :commit
               :record {:kind :assessment :id (subject request)
                        :value {:recommendation (:recommendation proposal)
                                :summary (:summary proposal) :by :auto}}}))))

      (g/add-node :request-approval
        (fn [{:keys [request proposal approval verdict]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :record {:kind :assessment :id (subject request)
                      :value {:recommendation (:recommendation proposal)
                              :summary (:summary proposal)
                              :approved-by (:by approval)}}
             :audit [{:t :authority-signoff :op (:op request)
                      :deployment (subject request) :by (:by approval)
                      :recommendation (:recommendation proposal)}]}
            {:disposition :hold
             :audit [(merge (gov/hold-fact request
                                           (assoc verdict :violations
                                                  [{:rule :authority-rejected}]))
                            {:t :signoff-rejected})]})))

      ;; commit an assessment datom + ledger (assess path only).
      (g/add-node :commit
        (fn [{:keys [request record]}]
          (store/record-datom! store record)
          (let [f {:t :assessed :op (:op request) :deployment (subject request)
                   :disposition :commit :basis (get-in record [:value :recommendation])}]
            (store/append-ledger! store f)
            {:audit [f]})))

      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:permit-hold :signoff-rejected} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      ;; intake routes ingest vs assess.
      (g/add-conditional-edges :intake
        (fn [{:keys [request]}]
          (if (phase/record-op? (:op request)) :record :advise)))
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)
      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition :commit :commit, :escalate :request-approval, :hold)))
      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}] (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :record)
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer checkpointer :interrupt-before #{:request-approval}})))
