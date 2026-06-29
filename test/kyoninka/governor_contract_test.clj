(ns kyoninka.governor-contract-test
  "The permitting contract as executable tests — kyoninka's analog of robotaxi's
  safety_contract_test / itonami's governor_contract_test. Invariant: the actor
  never records a launch readiness the PermitGovernor would reject, never auto-
  authorizes a public-road go-live, and always records observations."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [kyoninka.store :as store]
            [kyoninka.regllm :as regllm]
            [kyoninka.deployment :as dep]))

(defn- fresh [] (let [s (store/seed-db)] [s (dep/build s)]))
(defn- ctx [phase] {:phase phase})

(defn- run [actor tid req phase]
  (g/run* actor {:request req :context (ctx phase)} {:thread-id tid}))

(deftest ingest-always-records
  (testing "observe path records a ground datom regardless of phase"
    (let [[s actor] (fresh)
          res (run actor "i" {:op :permit/record :deployment "dp-jp"
                              :value {:type :noise-permit :status "granted"
                                      :authority "市" :expires 20301231}} 0)]
      (is (= :record (get-in res [:state :disposition])))
      (is (some #(= :noise-permit (:type %)) (store/permits-of s "dp-jp"))))))

(deftest clean-deployment-requires-authority-signoff
  (testing "a clean L4 build never auto-launches — it interrupts for a human"
    (let [[s actor] (fresh)
          r1 (run actor "l" {:op :launch/assess :deployment "dp-jp"} 3)]
      (is (= :interrupted (:status r1)) "launch is high-stakes → always human")
      (let [r2 (g/run* actor {:approval {:status :approved :by "JP-公安委員会-3"}}
                       {:thread-id "l" :resume? true})]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :ready-to-launch (:recommendation (store/assessment-of s "dp-jp"))))
        (is (= "JP-公安委員会-3" (:approved-by (store/assessment-of s "dp-jp"))))))))

(deftest deficient-deployment-is-held-and-unoverridable
  (testing "dp-ca-bad: missing CPUC permit + expired type approval + under-insured"
    (let [[s actor] (fresh)
          res (run actor "b" {:op :launch/assess :deployment "dp-ca-bad"} 3)
          basis (-> (store/ledger s) last :basis)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:missing-permits} basis))
      (is (some #{:under-insured} basis))
      (is (nil? (store/assessment-of s "dp-ca-bad")) "nothing recorded on hold"))))

(deftest over-permitted-level-is-held
  (testing "dp-zz: L4 requested in a regime that only permits L2"
    (let [[s actor] (fresh)
          res (run actor "z" {:op :launch/assess :deployment "dp-zz"} 3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:over-permitted-level} (-> (store/ledger s) last :basis))))))

(deftest no-actuation-invariant
  (testing "a proposal that tries to grant a permit / activate a vehicle is held"
    (let [[s _] (fresh)
          bad-adv (reify regllm/Advisor
                    (-advise [_ _ _ _] {:recommendation :ready-to-launch :effect :grant-permit
                                        :summary "x" :rationale "x" :cites [] :confidence 0.9}))
          a2 (dep/build s {:advisor bad-adv})
          res (g/run* a2 {:request {:op :launch/assess :deployment "dp-jp"} :context (ctx 3)}
                      {:thread-id "na"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-actuation} (-> (store/ledger s) last :basis))))))

(deftest phase0-disables-assessments
  (let [[s actor] (fresh)
        res (run actor "p0" {:op :launch/assess :deployment "dp-jp"} 0)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= :phase-disabled (-> (store/ledger s) last :phase-reason)))))

(deftest permit-assess-auto-commits-when-confident
  (testing "phase 3: a confident pre-application readiness is not high-stakes → auto"
    (let [[s actor] (fresh)
          res (run actor "p" {:op :permit/assess :deployment "dp-jp"} 3)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= :ready-to-apply (:recommendation (store/assessment-of s "dp-jp")))))))

(deftest reject-signoff-holds
  (testing "an authority rejection records a hold, not a launch authorization"
    (let [[s actor] (fresh)
          _  (run actor "r" {:op :launch/assess :deployment "dp-jp"} 3)
          r2 (g/run* actor {:approval {:status :rejected :by "JP-公安委員会-3"}}
                     {:thread-id "r" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (nil? (store/assessment-of s "dp-jp"))))))
