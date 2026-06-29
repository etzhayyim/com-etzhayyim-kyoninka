(ns kyoninka.store
  "SSoT for the kyoninka (許認可 = permits/licensing/approvals) actor — the
  legal / administrative state of a robotaxi *deployment* in a given
  jurisdiction, behind a `Store` protocol so the backend is a swap
  (MemStore default ‖ DatomicStore via langchain.db, itself swappable to real
  Datomic Local / kotoba-server).

  Domain = the cross-jurisdiction paperwork a driverless-taxi service must hold
  before it may legally carry a passenger on a public road. Entities:

    jurisdiction — a legal regime: the AV statute, the granting authority, the
                   max SAE level it permits, the mandatory permits/filings, the
                   minimum liability cover, whether a remote operator is
                   required. (e.g. JP 改正道路交通法 特定自動運行 / 道路運送車両法 /
                   道路運送法; US-CA DMV+CPUC+FMVSS; DE StVG AFGBV / UNECE / GDPR)
    deployment   — a concrete service we want to launch in a jurisdiction:
                   target SAE level, operating area (ODD), fleet size, the
                   remote operator on record.
    permit       — a permit/approval obtained for a deployment (型式指定 /
                   特定自動運行許可 / 旅客運送事業許可 / DMV deployment / CPUC
                   passenger / GDPR DPIA …): type, status, authority, expiry.
    insurance    — mandatory liability cover for a deployment.
    filing       — administrative notifications/届出 (remote-operator
                   registration, safety plan, collision reporting …).
    assessment   — the committed legal-readiness recommendation (output).

  Charter (ADR-0001): integers, not floats (cover in JPY; dates as yyyymmdd
  ints; SAE level/fleet ints); EAVT ground datoms are canonical; the
  append-only **ledger is the permitting genealogy** — immutable regulatory
  provenance, the property a SaaS or a mutable DB row can't give you. The actor
  is observe→recommend only: it never *grants* a permit and never activates a
  vehicle."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.db :as d]))

(defprotocol Store
  (jurisdiction [s id])
  (all-jurisdictions [s])
  (deployment [s id])
  (all-deployments [s])
  (permits-of [s id]    "permits/approvals held for a deployment")
  (insurance-of [s id]  "liability policies for a deployment")
  (filings-of [s id]    "administrative filings/notifications for a deployment")
  (assessment-of [s id] "committed legal-readiness assessment, or nil")
  (ledger [s])
  (record-datom! [s record] "append a legal-state ground fact to the SSoT")
  (append-ledger! [s fact]  "append one immutable permitting-genealogy fact")
  (seed! [s data]           "bulk-seed entity collections (idempotent upsert)"))

;; ───────────────────────── demo data ─────────────────────────

(defn demo-data
  "Three real-shaped jurisdictions + one fictional unready regime, and three
  deployments: dp-jp is a clean L4 build (all permits valid, cover ≥ min,
  filings accepted, remote operator on record); dp-ca-bad is missing the CPUC
  passenger permit, carries an expired type approval and below-minimum cover;
  dp-zz targets L4 in a regime that only permits L2."
  []
  {:jurisdictions
   {"JP"
    {:id "JP" :name "日本"
     :authority "都道府県公安委員会 / 国土交通省(MLIT)"
     :av-law "改正道路交通法(2023) 特定自動運行 / 道路運送車両法 型式指定 / 道路運送法"
     :max-level 4 :regime :permit
     :required-permits #{:vehicle-type-approval :road-use-permit
                         :passenger-transport :data-protection}
     :min-insurance-jpy 200000000          ; 2億円(対人賠償の実務水準)
     :required-filings #{:remote-operator-registration :safety-plan}
     :remote-operator-required? true}      ; 特定自動運行主任者
    "US-CA"
    {:id "US-CA" :name "United States — California"
     :authority "California DMV / CPUC / NHTSA"
     :av-law "CA Veh.Code §38750 + DMV AV regs(Title 13) / CPUC Driverless Deployment / FMVSS"
     :max-level 4 :regime :permit
     :required-permits #{:vehicle-type-approval :dmv-deployment
                         :cpuc-passenger :data-protection}
     :min-insurance-jpy 500000000          ; ~ $5M 桁の例
     :required-filings #{:dmv-collision-reporting :safety-plan}
     :remote-operator-required? true}
    "DE"
    {:id "DE" :name "Deutschland (EU)"
     :authority "KBA / BMDV (Autonomes-Fahren-Gesetz)"
     :av-law "StVG §1d–1l (AFGBV 2022, Level 4) / UNECE type approval / GDPR"
     :max-level 4 :regime :permit
     :required-permits #{:vehicle-type-approval :operating-area-approval
                         :technical-supervisor :data-protection}
     :min-insurance-jpy 1500000000
     :required-filings #{:safety-plan}
     :remote-operator-required? true}      ; Technische Aufsicht
    "ZZ"
    {:id "ZZ" :name "(架空)AV法未整備の法域"
     :authority "—" :av-law "L4 を許可する枠組みなし(L2 ADAS まで)"
     :max-level 2 :regime :none
     :required-permits #{} :min-insurance-jpy 0
     :required-filings #{} :remote-operator-required? false}}
   :deployments
   {"dp-jp"
    {:id "dp-jp" :jurisdiction "JP" :sae-level 4 :area "東京・お台場ループ"
     :fleet 20 :service :robotaxi :remote-operator "RO-tokyo-1"}
    "dp-ca-bad"
    {:id "dp-ca-bad" :jurisdiction "US-CA" :sae-level 4 :area "SF downtown"
     :fleet 50 :service :robotaxi :remote-operator "RO-sf-1"}
    "dp-zz"
    {:id "dp-zz" :jurisdiction "ZZ" :sae-level 4 :area "(架空)中心市街地"
     :fleet 10 :service :robotaxi :remote-operator nil}}
   :permits
   {"dp-jp"
    [{:type :vehicle-type-approval :status "granted" :authority "MLIT" :expires 20271231}
     {:type :road-use-permit       :status "granted" :authority "警視庁"  :expires 20271231}
     {:type :passenger-transport   :status "granted" :authority "関東運輸局" :expires 20281231}
     {:type :data-protection       :status "granted" :authority "PPC"    :expires nil}]
    "dp-ca-bad"
    ;; type approval expired; CPUC passenger permit absent → cannot carry fares
    [{:type :vehicle-type-approval :status "granted" :authority "NHTSA"  :expires 20240101}
     {:type :dmv-deployment        :status "granted" :authority "CA DMV" :expires 20271231}
     {:type :data-protection       :status "granted" :authority "CPPA"   :expires nil}]
    "dp-zz" []}
   :insurance
   {"dp-jp"     [{:type :liability :coverage-jpy 300000000 :status "active"}]
    "dp-ca-bad" [{:type :liability :coverage-jpy 100000000 :status "active"}]
    "dp-zz"     []}
   :filings
   {"dp-jp"
    [{:type :remote-operator-registration :status "accepted" :filed 20260115}
     {:type :safety-plan                  :status "accepted" :filed 20260120}]
    "dp-ca-bad"
    [{:type :dmv-collision-reporting :status "accepted" :filed 20260201}]
    "dp-zz" []}})

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (jurisdiction [_ id] (get-in @a [:jurisdictions id]))
  (all-jurisdictions [_] (sort-by :id (vals (:jurisdictions @a))))
  (deployment [_ id] (get-in @a [:deployments id]))
  (all-deployments [_] (sort-by :id (vals (:deployments @a))))
  (permits-of [_ id] (get-in @a [:permits id] []))
  (insurance-of [_ id] (get-in @a [:insurance id] []))
  (filings-of [_ id] (get-in @a [:filings id] []))
  (assessment-of [_ id] (get-in @a [:assessments id]))
  (ledger [_] (:ledger @a))
  (record-datom! [s {:keys [kind id value]}]
    (case kind
      :jurisdiction (swap! a update-in [:jurisdictions id] merge value)
      :deployment   (swap! a update-in [:deployments id] merge value)
      :permit       (swap! a update-in [:permits id] (fnil conj []) value)
      :insurance    (swap! a update-in [:insurance id] (fnil conj []) value)
      :filing       (swap! a update-in [:filings id] (fnil conj []) value)
      :assessment   (swap! a assoc-in [:assessments id] value)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (seed! [s data] (swap! a merge (select-keys data
                                              [:jurisdictions :deployments :permits
                                               :insurance :filings])) s))

(defn seed-db []
  (->MemStore (atom (assoc (demo-data) :assessments {} :ledger []))))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────────────

(def ^:private schema
  {:jurisdiction/id {:db/unique :db.unique/identity}
   :deployment/id   {:db/unique :db.unique/identity}
   :ledger/seq      {:db/unique :db.unique/identity}
   :assessment/deployment {:db/valueType :db.type/ref :db/unique :db.unique/identity}
   :rec/deployment  {:db/valueType :db.type/ref}})   ; permit/insurance/filing rows

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

;; The store talks to its backend ONLY through the langchain.db `:db-api` map
;; {:q :transact! :db :pull :entid}. langchain.db/api (in-process EAVT) and
;; langchain.kotoba-db/kotoba-api (kotoba-server XRPC, e.g. kotobase.net) both
;; implement it, so the same record runs on either by construction.

(defn- q* [{:keys [api conn]} query & inputs]
  (apply (:q api) query ((:db api) conn) inputs))
(defn- pull* [{:keys [api conn]} pattern eid] ((:pull api) ((:db api) conn) pattern eid))
(defn- tx* [{:keys [api conn]} txd] ((:transact! api) conn txd))

(defrecord DatomicStore [api conn]
  Store
  (jurisdiction [this id]
    (when-let [m (pull* this [:jurisdiction/id :jurisdiction/edn] [:jurisdiction/id id])]
      (when (:jurisdiction/id m) (dec* (:jurisdiction/edn m)))))
  (all-jurisdictions [this]
    (->> (q* this '[:find [?id ...] :where [?e :jurisdiction/id ?id]])
         (map #(jurisdiction this %)) (sort-by :id)))
  (deployment [this id]
    (when-let [m (pull* this [:deployment/id :deployment/edn] [:deployment/id id])]
      (when (:deployment/id m) (dec* (:deployment/edn m)))))
  (all-deployments [this]
    (->> (q* this '[:find [?id ...] :where [?e :deployment/id ?id]])
         (map #(deployment this %)) (sort-by :id)))
  (permits-of [this id] (->> (q* this '[:find [?v ...] :in $ ?did :where
                                        [?e :deployment/id ?did] [?r :rec/deployment ?e]
                                        [?r :rec/kind :permit] [?r :rec/edn ?v]] id)
                             (mapv dec*)))
  (insurance-of [this id] (->> (q* this '[:find [?v ...] :in $ ?did :where
                                          [?e :deployment/id ?did] [?r :rec/deployment ?e]
                                          [?r :rec/kind :insurance] [?r :rec/edn ?v]] id)
                               (mapv dec*)))
  (filings-of [this id] (->> (q* this '[:find [?v ...] :in $ ?did :where
                                        [?e :deployment/id ?did] [?r :rec/deployment ?e]
                                        [?r :rec/kind :filing] [?r :rec/edn ?v]] id)
                             (mapv dec*)))
  (assessment-of [this id]
    (dec* (q* this '[:find ?p . :in $ ?did :where [?e :deployment/id ?did]
                     [?x :assessment/deployment ?e] [?x :assessment/edn ?p]] id)))
  (ledger [this]
    (->> (q* this '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]])
         (sort-by first) (mapv (comp dec* second))))
  (record-datom! [s {:keys [kind id value]}]
    (case kind
      :jurisdiction (tx* s [{:jurisdiction/id id :jurisdiction/edn (enc value)}])
      :deployment   (tx* s [{:deployment/id id :deployment/edn (enc value)}])
      :assessment   (tx* s [{:assessment/deployment [:deployment/id id] :assessment/edn (enc value)}])
      (:permit :insurance :filing)
      (tx* s [{:rec/deployment [:deployment/id id] :rec/kind kind :rec/edn (enc value)}])
      nil)
    s)
  (append-ledger! [s fact]
    (tx* s [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}]) fact)
  (seed! [s data]
    (doseq [[id j] (:jurisdictions data)] (record-datom! s {:kind :jurisdiction :id id :value j}))
    (doseq [[id d] (:deployments data)]   (record-datom! s {:kind :deployment :id id :value d}))
    (doseq [k [:permits :insurance :filings]
            [id rows] (get data k)
            row rows]
      (record-datom! s {:kind ({:permits :permit :insurance :insurance :filings :filing} k)
                        :id id :value row}))
    s))

(defn datomic-store
  "DatomicStore on the in-process langchain.db EAVT backend (default Datomic-
  shaped store; verifiable offline). For the kotoba-server pod (kotobase.net),
  bind the same record to langchain.kotoba-db/kotoba-api — same record, different
  :db-api (see ADR-0001 / docs/DESIGN.md)."
  ([] (datomic-store nil))
  ([data] (let [s (->DatomicStore d/api (d/create-conn schema))]
            (when data (seed! s data)) s)))

(defn datomic-seed-db [] (datomic-store (demo-data)))

;; ───────────────────────── ledger formatting ─────────────────────────

(defn ledger-line [{:keys [op deployment disposition basis]}]
  (str/join " · " [(name (or disposition :record)) (str "op=" op)
                   (str "deployment=" deployment) (str "basis=" (pr-str basis))]))
