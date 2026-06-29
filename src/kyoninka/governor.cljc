(ns kyoninka.governor
  "PermitGovernor — the independent legal layer that earns the reg-LLM the right
  to *recommend*. The LLM has no binding notion of which permits are mandatory
  in which jurisdiction, of statutory minimum cover, of the no-actuation
  charter, so this MUST be a separate system (rules over the EAVT ground
  datoms) able to *reject* a recommendation and fall back to HOLD — the
  kyoninka analog of robotaxi's MRC / itonami's airworthiness hold.

  Charter (ADR-0001): the actor is **observe → recommend only**. It never
  *grants* a permit, never marks a permit `granted`, never activates a vehicle.
  Below, HARD invariants force HOLD (no human can approve past a missing
  mandatory permit or below-minimum liability cover); the final go for a public-
  road launch is a human regulatory authority sign-off even when everything is
  clean (high-stakes).

  HARD invariants:
    1. Jurisdiction recognized   — the deployment names a known legal regime.
    2. Level within framework    — target SAE level ≤ the jurisdiction's max
                                   (you cannot run L4 where only L2 is permitted).
    3. Mandatory permits (launch)— every required permit is held, `granted`, and
                                   not expired (as of `today`).
    4. Minimum insurance (launch)— active liability cover ≥ statutory minimum.
    5. Mandatory filings (launch)— every required notification is `accepted`.
    6. Remote operator (launch)  — present when the jurisdiction requires one.
    7. No-actuation              — the proposal writes an :assessment, never a
                                   permit grant / vehicle activation.
  SOFT:
    8. Confidence floor → escalate.
    9. A public-road launch is high-stakes → ALWAYS human authority sign-off.

  Op scope: :permit/assess (pre-application readiness) checks only 1,2,7 — the
  permits/insurance/filings are precisely what the application is *for*.
  :launch/assess (go-live) checks all of 1–7 and is always high-stakes."
  (:require [kyoninka.store :as store]))

(def confidence-floor 0.6)
(def default-today 20260627)

;; ───────────────────────── invariant checks ─────────────────────────

(defn- valid-permit? [today {:keys [status expires]}]
  (and (= "granted" status)
       (or (nil? expires) (>= expires today))))

(defn- jurisdiction-violations [jur dep]
  (cond-> []
    (nil? jur)
    (conj {:rule :unknown-jurisdiction
           :detail (str "未知の法域 " (:jurisdiction dep))})
    (and jur (> (:sae-level dep) (:max-level jur)))
    (conj {:rule :over-permitted-level
           :detail (str "L" (:sae-level dep) " は上限 L" (:max-level jur) " を超過")})))

(defn- permit-violations [st today jur dep]
  (let [held (->> (store/permits-of st (:id dep))
                  (filter (partial valid-permit? today)) (map :type) set)
        miss (remove held (:required-permits jur))]
    (when (seq miss)
      [{:rule :missing-permits
        :detail (str "必須許認可の未取得/失効: " (vec miss))}])))

(defn- insurance-violations [st jur dep]
  (let [cover (reduce max 0 (map :coverage-jpy
                                 (filter #(= "active" (:status %))
                                         (store/insurance-of st (:id dep)))))]
    (when (< cover (:min-insurance-jpy jur))
      [{:rule :under-insured
        :detail (str "有効賠償額 " cover " < 法定下限 " (:min-insurance-jpy jur) " JPY")}])))

(defn- filing-violations [st jur dep]
  (let [filed (->> (store/filings-of st (:id dep))
                   (filter #(= "accepted" (:status %))) (map :type) set)
        miss  (remove filed (:required-filings jur))]
    (when (seq miss)
      [{:rule :missing-filings :detail (str "未受理の届出: " (vec miss))}])))

(defn- operator-violations [jur dep]
  (when (and (:remote-operator-required? jur) (nil? (:remote-operator dep)))
    [{:rule :no-remote-operator :detail "遠隔監視者(特定自動運行主任者等)が未指定"}]))

(defn- actuation-violations [proposal]
  ;; observe→recommend: the actor may write an :assessment datom, never a permit
  ;; grant or a vehicle activation.
  (when (not= :assessment (:effect proposal))
    [{:rule :no-actuation
      :detail (str "actor は許認可付与/車両起動をしない(observe→recommend)。effect="
                   (:effect proposal))}]))

(defn check
  "Censors a reg-LLM proposal for a deployment op. Returns
   {:ok? :violations :confidence :hard? :escalate? :high-stakes?}.

   Hard violations force HOLD and cannot be overridden by a human. A public-road
   :launch/assess is always high-stakes → human authority sign-off even clean.
   opts: {:today yyyymmdd-int}."
  ([request proposal st] (check request proposal st nil))
  ([request proposal st {:keys [today] :or {today default-today}}]
   (let [dep  (store/deployment st (:deployment request))
         jur  (store/jurisdiction st (:jurisdiction dep))
         base (jurisdiction-violations jur dep)
         hard (case (:op request)
                :launch/assess
                (vec (concat base
                             ;; only meaningful once the jurisdiction is known
                             (when jur (permit-violations st today jur dep))
                             (when jur (insurance-violations st jur dep))
                             (when jur (filing-violations st jur dep))
                             (when jur (operator-violations jur dep))
                             (actuation-violations proposal)))
                :permit/assess
                (vec (concat base (actuation-violations proposal)))
                [])
         conf    (:confidence proposal 0.0)
         low?    (< conf confidence-floor)
         stakes? (= :launch/assess (:op request))
         hard?   (boolean (seq hard))]
     {:ok?          (and (not hard?) (not low?) (not stakes?))
      :violations   hard
      :confidence   conf
      :hard?        hard?
      :escalate?    (and (not hard?) (or low? stakes?))
      :high-stakes? stakes?})))

(defn hold-fact [request verdict]
  {:t :permit-hold :op (:op request) :deployment (:deployment request)
   :disposition :hold :basis (mapv :rule (:violations verdict))
   :violations (:violations verdict) :confidence (:confidence verdict)})
