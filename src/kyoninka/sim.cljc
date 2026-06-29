(ns kyoninka.sim
  "Demo: drive robotaxi deployment-permitting through one DeploymentActor.

    ingest      register a new jurisdiction + a deployment (observe → datoms)
    launch dp-jp     clean L4 build → PermitGovernor passes → authority signoff
                     (interrupt) → 公安委員会/運輸局 approves → assessment recorded
    launch dp-ca-bad missing CPUC passenger permit + expired type approval +
                     below-minimum cover → HARD HOLD (no human can approve past it)
    launch dp-zz     L4 in an L2-only regime → HARD HOLD (over-permitted-level)
    permit dp-jp     pre-application readiness → auto-commit (phase 3, not launch)
    phase 0          launch/assess in survey-only phase → held (phase-disabled)

  Run: clojure -M:dev:run"
  (:require [langgraph.graph :as g]
            [kyoninka.store :as store]
            [kyoninka.deployment :as dep]))

(defn- line [& xs] (println (apply str xs)))

(defn- drive [actor tid req phase approve?]
  (let [res (g/run* actor {:request req :context {:phase phase}} {:thread-id tid})]
    (if (= :interrupted (:status res))
      (do (line "   ⏸  規制当局サインオフ — authority review (reason: "
                (-> res :state :audit last :reason) ")")
          (let [r2 (g/run* actor {:approval {:status (if approve? :approved :rejected)
                                             :by "JP-公安委員会-3"}}
                           {:thread-id tid :resume? true})]
            (line "   ▶  " (if approve? "認可" "却下") " → " (get-in r2 [:state :disposition]))
            r2))
      (do (line "   → " (get-in res [:state :disposition])
                (when-let [pr (-> res :state :audit last :phase-reason)] (str " (" pr ")")))
          res))))

(defn -main [& _]
  (let [st    (store/seed-db)
        actor (dep/build st)]

    (line "── ingest (observe → EAVT 法的状態 datoms) ──")
    (drive actor "i1" {:op :jurisdiction/register :jurisdiction "SG"
                       :value {:id "SG" :name "Singapore" :authority "LTA"
                               :av-law "Road Traffic Act / LTA AV rules" :max-level 4
                               :regime :permit :required-permits #{:vehicle-type-approval}
                               :min-insurance-jpy 100000000 :required-filings #{:safety-plan}
                               :remote-operator-required? true}} 3 true)
    (drive actor "i2" {:op :deployment/register :deployment "dp-sg"
                       :value {:id "dp-sg" :jurisdiction "SG" :sae-level 4
                               :area "one-north" :fleet 5 :service :robotaxi
                               :remote-operator "RO-sg-1"}} 3 true)
    (line "  registered jurisdictions: " (mapv :id (store/all-jurisdictions st)))

    (line "\n── launch/assess dp-jp (clean L4 build) ──")
    (drive actor "l-jp" {:op :launch/assess :deployment "dp-jp"} 3 true)

    (line "\n── launch/assess dp-ca-bad (missing/expired permits + under-insured) ──")
    (drive actor "l-ca" {:op :launch/assess :deployment "dp-ca-bad"} 3 true)

    (line "\n── launch/assess dp-zz (L4 in an L2-only regime) ──")
    (drive actor "l-zz" {:op :launch/assess :deployment "dp-zz"} 3 true)

    (line "\n── permit/assess dp-jp (申請準備 readiness; not high-stakes) ──")
    (drive actor "p-jp" {:op :permit/assess :deployment "dp-jp"} 3 true)

    (line "\n── 段階導入: launch/assess を phase 0 (survey-only) で ──")
    (drive actor "l-p0" {:op :launch/assess :deployment "dp-jp"} 0 true)

    (line "\n── 許認可ジェネアロジー台帳 (append-only; 規制トレーサビリティ) ──")
    (doseq [f (store/ledger st)] (line "  " (store/ledger-line f)))

    (line "\n── バックエンド差し替え: DatomicStore でも同一契約 ──")
    (let [ds (store/datomic-seed-db) da (dep/build ds)]
      (drive da "d1" {:op :launch/assess :deployment "dp-jp"} 3 true)
      (line "  DatomicStore assessment dp-jp: " (:recommendation (store/assessment-of ds "dp-jp"))))
    (line "\ndone.")))
