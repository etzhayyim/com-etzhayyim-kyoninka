(ns kyoninka.methods.procedure-test
  "The permitting contract as executable tests — the platform-side mirror of the
  langgraph PermitGovernor contract in orgs/etzhayyim/com-etzhayyim-kyoninka. Run:
    clojure -M:test"
  (:require [clojure.test :refer [deftest is run-tests]]
            [kyoninka.methods.procedure :as p]))

(deftest clean-jp-deployment-escalates-to-authority
  (let [r (p/evaluate (p/deployment "dp-jp"))]
    (is (every? :ok? (:rows r)) "全要件充足")
    (is (empty? (:violations r)))
    (is (= :escalate (:verdict r)) "clean でも公道走行は当局サインオフ必須")))

(deftest deficient-ca-deployment-holds
  (let [r (p/evaluate (p/deployment "dp-ca-bad"))]
    (is (:hard? r))
    (is (= :hold (:verdict r)))
    (is (some #{:cpuc-passenger} (:violations r)) "CPUC旅客許可の欠落")
    (is (some #{:liability} (:violations r)) "賠償下限未満")
    (is (some #{:vehicle-type-approval} (:violations r)) "型式指定の失効")
    (is (some #{:safety-plan} (:violations r)) "安全計画届出の欠落")))

(deftest over-level-deployment-holds
  (let [r (p/evaluate (p/deployment "dp-zz"))]
    (is (= :hold (:verdict r)))
    (is (some #{:sae-level} (:violations r)) "L2法域でL4は上限超過")))

(deftest expiry-is-evaluated-as-of-today
  ;; the same dp-ca-bad type approval would be valid before its expiry date
  (let [before (p/evaluate (p/deployment "dp-ca-bad") 20231231)]
    (is (not (some #{:vehicle-type-approval} (:violations before)))
        "失効日前は型式指定は有効")))

(deftest singapore-clean-escalates
  (let [r (p/evaluate (p/deployment "dp-sg"))]
    (is (empty? (:violations r)))
    (is (= :escalate (:verdict r)))))

#?(:clj
   (defn -main [& _]
     (let [{:keys [fail error]} (run-tests 'kyoninka.methods.procedure-test)]
       (when (pos? (+ (or fail 0) (or error 0)))
         (System/exit 1)))))
