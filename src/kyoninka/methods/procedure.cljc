(ns kyoninka.methods.procedure
  "kyoninka 許認可 — the robotaxi legal-deployment PROCEDURE substrate.

  A compact, dependency-free (.cljc / bb-runnable) restatement of the
  per-jurisdiction permitting rulebook + the PermitGovernor's legal invariants,
  so the 手続き (procedure) can be evaluated and rendered on the web without the
  full langgraph-clj runtime. The runnable StateGraph actor (reg-LLM ⊣
  PermitGovernor, the robotaxi/itonami sibling) lives in the west repo
  `orgs/etzhayyim/com-etzhayyim-kyoninka`; this namespace is the same domain logic in
  the kotoba-native, observe-only form the etzhayyim platform consumes.

  Charter alignment: observe → recommend ONLY. kyoninka never *grants* a permit
  and never activates a vehicle; it maps a deployment's legal facts to a
  readiness verdict and a human-authority sign-off requirement. Integers, not
  floats (cover in JPY, dates yyyymmdd, SAE level int)."
  (:require [clojure.string :as str]))

(def default-today 20260627)

;; ───────────────────────── jurisdiction rulebook (data) ─────────────────────────
;; This is illustrative and must be curated with counsel before any real filing;
;; because it is DATA, that curation is a reviewed edit, not a code change.

(def jurisdictions
  [{:id "JP" :name "日本" :glyph "🇯🇵"
    :authority "都道府県公安委員会 / 国土交通省(MLIT)"
    :av-law "改正道路交通法(2023) 特定自動運行 / 道路運送車両法 型式指定 / 道路運送法"
    :max-level 4
    :required-permits [:vehicle-type-approval :road-use-permit :passenger-transport :data-protection]
    :min-insurance-jpy 200000000
    :required-filings [:remote-operator-registration :safety-plan]
    :remote-operator-required? true}
   {:id "US-CA" :name "United States — California" :glyph "🇺🇸"
    :authority "California DMV / CPUC / NHTSA"
    :av-law "CA Veh.Code §38750 + DMV AV regs(Title 13) / CPUC Driverless Deployment / FMVSS"
    :max-level 4
    :required-permits [:vehicle-type-approval :dmv-deployment :cpuc-passenger :data-protection]
    :min-insurance-jpy 500000000
    :required-filings [:dmv-collision-reporting :safety-plan]
    :remote-operator-required? true}
   {:id "DE" :name "Deutschland (EU)" :glyph "🇪🇺"
    :authority "KBA / BMDV (Autonomes-Fahren-Gesetz)"
    :av-law "StVG §1d–1l (AFGBV 2022, Level 4) / UNECE type approval / GDPR"
    :max-level 4
    :required-permits [:vehicle-type-approval :operating-area-approval :technical-supervisor :data-protection]
    :min-insurance-jpy 1500000000
    :required-filings [:safety-plan]
    :remote-operator-required? true}
   {:id "SG" :name "Singapore" :glyph "🇸🇬"
    :authority "Land Transport Authority (LTA)"
    :av-law "Road Traffic Act (AV provisions) / LTA AV rules / TR68"
    :max-level 4
    :required-permits [:vehicle-type-approval :lta-trial-or-deployment :data-protection]
    :min-insurance-jpy 100000000
    :required-filings [:safety-plan]
    :remote-operator-required? true}
   {:id "ZZ" :name "(架空)AV法未整備の法域" :glyph "⚠"
    :authority "—" :av-law "L4 を許可する枠組みなし(L2 ADAS まで)"
    :max-level 2
    :required-permits [] :min-insurance-jpy 0
    :required-filings [] :remote-operator-required? false}])

(defn jurisdiction [id] (first (filter #(= id (:id %)) jurisdictions)))

;; permit-type → human label (the 手続き each row stands for)
(def permit-labels
  {:vehicle-type-approval    "車両型式指定 / Vehicle type approval"
   :road-use-permit          "特定自動運行許可 / Road-use permit"
   :passenger-transport      "旅客自動車運送事業許可 / Passenger-transport licence"
   :data-protection          "個人情報保護 / Data-protection (DPIA)"
   :dmv-deployment           "DMV Driverless Deployment permit"
   :cpuc-passenger           "CPUC Driverless Passenger permit"
   :operating-area-approval  "Betriebsbereich-Genehmigung / Operating-area approval"
   :technical-supervisor     "Technische Aufsicht / Technical-supervisor approval"
   :lta-trial-or-deployment  "LTA trial / deployment authorisation"})

(def filing-labels
  {:remote-operator-registration "遠隔監視者(特定自動運行主任者)届出"
   :safety-plan                  "安全運行計画 / Safety plan"
   :dmv-collision-reporting      "DMV collision reporting enrolment"})

;; ───────────────────────── deployments (seed) ─────────────────────────

(def deployments
  [{:id "dp-jp" :jurisdiction "JP" :sae-level 4 :area "東京・お台場ループ" :fleet 20
    :remote-operator "RO-tokyo-1"
    :permits [{:type :vehicle-type-approval :status "granted" :expires 20271231}
              {:type :road-use-permit       :status "granted" :expires 20271231}
              {:type :passenger-transport   :status "granted" :expires 20281231}
              {:type :data-protection       :status "granted" :expires nil}]
    :insurance [{:coverage-jpy 300000000 :status "active"}]
    :filings [{:type :remote-operator-registration :status "accepted"}
              {:type :safety-plan :status "accepted"}]}
   {:id "dp-ca-bad" :jurisdiction "US-CA" :sae-level 4 :area "SF downtown" :fleet 50
    :remote-operator "RO-sf-1"
    :permits [{:type :vehicle-type-approval :status "granted" :expires 20240101} ; expired
              {:type :dmv-deployment        :status "granted" :expires 20271231}
              {:type :data-protection       :status "granted" :expires nil}]    ; cpuc-passenger MISSING
    :insurance [{:coverage-jpy 100000000 :status "active"}]                     ; below 5億 floor
    :filings [{:type :dmv-collision-reporting :status "accepted"}]}             ; safety-plan MISSING
   {:id "dp-sg" :jurisdiction "SG" :sae-level 4 :area "one-north" :fleet 5
    :remote-operator "RO-sg-1"
    :permits [{:type :vehicle-type-approval :status "granted" :expires 20281231}
              {:type :lta-trial-or-deployment :status "granted" :expires 20281231}
              {:type :data-protection :status "granted" :expires nil}]
    :insurance [{:coverage-jpy 150000000 :status "active"}]
    :filings [{:type :safety-plan :status "accepted"}]}
   {:id "dp-zz" :jurisdiction "ZZ" :sae-level 4 :area "(架空)中心市街地" :fleet 10
    :remote-operator nil :permits [] :insurance [] :filings []}])

(defn deployment [id] (first (filter #(= id (:id %)) deployments)))

;; ───────────────────────── PermitGovernor invariants ─────────────────────────

(defn- valid-permit? [today {:keys [status expires]}]
  (and (= "granted" status) (or (nil? expires) (>= expires today))))

(defn evaluate
  "Evaluate a deployment's launch readiness. Returns a per-requirement checklist
  + the overall verdict (:commit | :escalate | :hold) and the violated rules.
  This is the same legal contract the langgraph PermitGovernor enforces."
  ([dep] (evaluate dep default-today))
  ([dep today]
   (let [jur (jurisdiction (:jurisdiction dep))
         held (->> (:permits dep) (filter (partial valid-permit? today)) (map :type) set)
         cover (reduce max 0 (map :coverage-jpy (filter #(= "active" (:status %)) (:insurance dep))))
         filed (->> (:filings dep) (filter #(= "accepted" (:status %))) (map :type) set)
         permit-rows (mapv (fn [t] {:kind :permit :req t :label (permit-labels t)
                                    :ok? (contains? held t)}) (:required-permits jur))
         filing-rows (mapv (fn [t] {:kind :filing :req t :label (filing-labels t)
                                    :ok? (contains? filed t)}) (:required-filings jur))
         insurance-row {:kind :insurance :req :liability
                        :label (str "賠償責任保険 ≥ " (:min-insurance-jpy jur) " JPY")
                        :ok? (>= cover (:min-insurance-jpy jur)) :have cover}
         level-row {:kind :level :req :sae-level
                    :label (str "SAE Level " (:sae-level dep) " ≤ 法域上限 L" (:max-level jur))
                    :ok? (<= (:sae-level dep) (:max-level jur))}
         operator-row (when (:remote-operator-required? jur)
                        {:kind :operator :req :remote-operator
                         :label "遠隔監視者(特定自動運行主任者等)の指定"
                         :ok? (some? (:remote-operator dep))})
         rows (vec (concat [level-row] permit-rows [insurance-row] filing-rows
                           (when operator-row [operator-row])))
         violations (->> rows (remove :ok?) (mapv :req))
         hard? (boolean (seq violations))]
     {:deployment (:id dep) :jurisdiction (:id jur) :rows rows
      :violations violations :hard? hard?
      ;; a public-road launch is high-stakes → always human authority sign-off,
      ;; even when the checklist is fully clean.
      :verdict (cond hard? :hold :else :escalate)
      :authority (:authority jur)})))

;; ───────────────────────── procedure stages (the 手続き flow) ─────────────────────────

(def stages
  "The DeploymentActor StateGraph as a linear procedure the public can read."
  [{:id :intake   :ja "受付"           :doc "デプロイ申請を受け、観測(法的状態の記録)か査定かに振り分け"}
   {:id :advise   :ja "助言(reg-LLM)" :doc "封じ込めた reg-LLM が事実のみから準備度を提案(付与はしない)"}
   {:id :govern   :ja "検閲(PermitGovernor)" :doc "独立ガバナーが法的不変条件で提案を検閲"}
   {:id :decide   :ja "判定"           :doc "commit / hold / 人間承認 へ振り分け(段階導入ゲート込み)"}
   {:id :approval :ja "規制当局サインオフ" :doc "公道走行は高リスク → 必ず人間の規制当局が承認(interrupt)"}
   {:id :commit   :ja "記録"           :doc "承認された準備度を不変台帳へ append(付与・起動はしない)"}])

(defn verdict-label [v]
  (case v :commit "可(記録)" :escalate "要・当局サインオフ" :hold "保留(不充足)" (str v)))
