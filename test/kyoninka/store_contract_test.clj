(ns kyoninka.store-contract-test
  "Backend-swap contract: MemStore ≡ DatomicStore. The same seed + the same
  reads must agree, and the actor's launch/permit verdicts must be identical on
  either backend — the property that lets the kotoba-server pod (kotobase.net)
  drop in via langchain.kotoba-db with the same record."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [kyoninka.store :as store]
            [kyoninka.deployment :as dep]))

(defn- launch [actor tid did]
  (g/run* actor {:request {:op :launch/assess :deployment did} :context {:phase 3}}
          {:thread-id tid}))

(deftest mem-and-datomic-reads-agree
  (testing "the two backends return equal domain reads for the demo data"
    (let [m (store/seed-db) d (store/datomic-seed-db)]
      (is (= (mapv :id (store/all-jurisdictions m))
             (mapv :id (store/all-jurisdictions d))))
      (is (= (mapv :id (store/all-deployments m))
             (mapv :id (store/all-deployments d))))
      (is (= (store/jurisdiction m "JP") (store/jurisdiction d "JP")))
      (is (= (store/deployment m "dp-jp") (store/deployment d "dp-jp")))
      (is (= (set (store/permits-of m "dp-ca-bad"))
             (set (store/permits-of d "dp-ca-bad"))))
      (is (= (set (store/insurance-of m "dp-jp")) (set (store/insurance-of d "dp-jp"))))
      ;; multi-row child reads are order-independent (datalog has no inherent order)
      (is (= (set (store/filings-of m "dp-jp")) (set (store/filings-of d "dp-jp")))))))

(deftest verdicts-match-across-backends
  (testing "clean → interrupt on both; deficient → hold on both"
    (let [ma (dep/build (store/seed-db))
          da (dep/build (store/datomic-seed-db))]
      (is (= :interrupted (:status (launch ma "m1" "dp-jp"))
             (:status (launch da "d1" "dp-jp"))))
      (is (= :hold
             (get-in (launch ma "m2" "dp-ca-bad") [:state :disposition])
             (get-in (launch da "d2" "dp-ca-bad") [:state :disposition]))))))
