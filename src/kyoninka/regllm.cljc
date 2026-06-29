(ns kyoninka.regllm
  "reg-LLM — the contained intelligence node (regulatory advisor). It reads a
  deployment's legal EAVT ground datoms (jurisdiction rules, permits held,
  insurance, filings) and returns a PROPOSAL: a legal-readiness recommendation
  + rationale + the facts it cited, never a granted permit and never a launch.
  Every output is censored by `kyoninka.governor` before anything is recorded,
  and a launch recommendation always routes to a human regulatory authority
  (charter: observe→recommend, no actuation).

  Advisor is injected (mock | real LLM via langchain.model), same as
  robotaxi.ar1 / talent.hrllm / itonami.opsllm.

  Proposal shape:
    {:recommendation kw   ; :ready-to-apply | :ready-to-launch | :not-ready
     :summary str :rationale str :cites [kw ..]
     :effect :assessment  ; the actor only ever writes an assessment datom
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.model :as model]
            [kyoninka.store :as store]))

;; ───────────────────────── deterministic mock ─────────────────────────

(defn- valid-permit? [today {:keys [status expires]}]
  (and (= "granted" status)
       (or (nil? expires) (>= expires today))))

(defn- launch-blockers
  "The legal gaps that stop a *launch* — mirrors what the governor will check,
  so a clean deployment yields :ready-to-launch (then escalates to a human) and
  a deficient one yields :not-ready (the governor also holds)."
  [st today dep]
  (let [jur   (store/jurisdiction st (:jurisdiction dep))
        held  (->> (store/permits-of st (:id dep))
                   (filter (partial valid-permit? today))
                   (map :type) set)
        cover (reduce max 0 (map :coverage-jpy
                                 (filter #(= "active" (:status %))
                                         (store/insurance-of st (:id dep)))))
        filed (->> (store/filings-of st (:id dep))
                   (filter #(= "accepted" (:status %))) (map :type) set)]
    (cond-> []
      (nil? jur)                              (conj :unknown-jurisdiction)
      (and jur (> (:sae-level dep) (:max-level jur))) (conj :over-permitted-level)
      (and jur (seq (remove held (:required-permits jur)))) (conj :missing-permits)
      (and jur (< cover (:min-insurance-jpy jur)))   (conj :under-insured)
      (and jur (seq (remove filed (:required-filings jur)))) (conj :missing-filings)
      (and jur (:remote-operator-required? jur) (nil? (:remote-operator dep)))
      (conj :no-remote-operator))))

(defn- assess-launch [st today {:keys [deployment]}]
  (let [dep   (store/deployment st deployment)
        gaps  (launch-blockers st today dep)
        ready (empty? gaps)]
    {:recommendation (if ready :ready-to-launch :not-ready)
     :summary    (str deployment " 公道走行(launch)準備: "
                      (if ready "全許認可・保険・届出が充足" "未充足"))
     :rationale  (if ready
                   (str (:jurisdiction dep) " の必須許認可/保険/届出をすべて充足。"
                        "最終可否は規制当局の判断。")
                   (str "不足: " (str/join "/" (map name gaps)) "。"))
     :cites      [:jurisdiction :permits :insurance :filings]
     :effect     :assessment
     :confidence (if ready 0.88 0.82)}))

(defn- assess-permit
  "Pre-application readiness: can we file a permit application at all? This only
  needs a recognized jurisdiction and a target level within its framework —
  permits/insurance are what the application is *for*."
  [st {:keys [deployment]}]
  (let [dep (store/deployment st deployment)
        jur (store/jurisdiction st (:jurisdiction dep))
        ok? (and jur (<= (:sae-level dep) (:max-level jur)))]
    {:recommendation (if ok? :ready-to-apply :not-ready)
     :summary    (str deployment " 申請準備: "
                      (if ok? "申請可能な法域・水準" "法域/水準が枠組み外"))
     :rationale  (if ok?
                   (str (:jurisdiction dep) "(上限L" (:max-level jur) ") に L"
                        (:sae-level dep) " 申請可。")
                   "L4 を許可する枠組みのない法域、または上限超過。")
     :cites      [:jurisdiction :deployment]
     :effect     :assessment
     :confidence (if ok? 0.86 0.8)}))

(defn infer [st today {:keys [op] :as req}]
  (case op
    :permit/assess (assess-permit st req)
    :launch/assess (assess-launch st today req)
    {:recommendation :unknown :summary "未対応" :rationale (str op)
     :cites [] :effect :noop :confidence 0.0}))

;; ───────────────────────── Advisor protocol ─────────────────────────

(defprotocol Advisor
  (-advise [advisor store today request]))

(defn mock-advisor [] (reify Advisor (-advise [_ st today req] (infer st today req))))

(def ^:private system-prompt
  (str "あなたは自動運転タクシーの法務・許認可助言者です。与えられた事実(法域規定/"
       "取得済み許認可/保険/届出)のみに基づき、提案を1つ EDN マップで返します。"
       "EDN だけを出力。\n"
       "キー: :recommendation(:ready-to-apply|:ready-to-launch|:not-ready) :summary "
       ":rationale :cites(使った事実キー) :effect(:assessment 固定) :confidence(0..1)。\n"
       "重要: 許認可の付与や車両の起動は提案しない(observe→recommend)。"))

(defn- facts-for [st {:keys [deployment]}]
  (let [dep (store/deployment st deployment)]
    {:deployment dep
     :jurisdiction (store/jurisdiction st (:jurisdiction dep))
     :permits (store/permits-of st deployment)
     :insurance (store/insurance-of st deployment)
     :filings (store/filings-of st deployment)}))

(defn- parse-proposal [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p (update :cites #(vec (or % [])))
            (update :confidence #(if (number? %) (double %) 0.0))
            (update :effect #(or % :noop)))
      {:recommendation :unknown :summary "LLM応答を解釈できません" :rationale (str content)
       :cites [] :effect :noop :confidence 0.0})))

(defn llm-advisor
  "Advisor backed by a langchain.model/ChatModel (Anthropic / OpenAI-compatible
  / mock-model). Output is parsed defensively → an unparseable response is a
  confidence-0 noop the governor will hold/escalate."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st _today req]
       (let [resp (model/-generate chat-model
                    [{:role :system :content system-prompt}
                     {:role :user :content (str "操作:" (:op req) " デプロイ:" (:deployment req)
                                                "\n事実:" (pr-str (facts-for st req)))}]
                    gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace [request proposal]
  {:t :regllm-proposal :op (:op request) :deployment (:deployment request)
   :recommendation (:recommendation proposal) :summary (:summary proposal)
   :rationale (:rationale proposal) :cites (:cites proposal)
   :confidence (:confidence proposal)})
