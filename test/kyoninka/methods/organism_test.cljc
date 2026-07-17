(ns kyoninka.methods.organism-test
  "kyoninka autonomous-publication cell tests (on the kototama common lib). Run:
    clojure -M:test"
  (:require [clojure.test :refer [deftest is run-tests]]
            [kototama.organism :as org]
            [kyoninka.methods.organism :as k]))

(def leash {:member-did "did:plc:founder" :capability "datom:transact"
            :graph "kyoninka" :exp 9999999999 :cacao-b64 "opaque"})

(deftest digest-is-bounded-and-mentions-deployments
  (let [d (k/readiness-digest "2026-06-28T00:00:00Z")]
    (is (<= (count d) 300) "post fits AT-Proto 300-grapheme limit")
    (is (re-find #"dp-jp" d))
    (is (re-find #"dp-ca-bad" d))))

(deftest leashed-beat-publishes-dry-run-member-attributed
  (let [o (k/build leash)
        r (org/beat o {:now 1782604800 :created-at "2026-06-28T00:00:00Z"})
        e (:envelope r)]
    (is (some? e) "leashed + act? → an envelope")
    (is (= :dry-run (:status e)) "kyoninka never asserts :published")
    (is (true? (:requiresMemberSignature e)))
    (is (false? (:serverHeldKey e)) "no custodial key")
    (is (= "did:plc:founder" (:writeAuthor e)) "attributed to consenting member")
    (is (:appended? r) "persisted to the append-only log")))

(deftest unleashed-cannot-publish
  (let [o (k/build {:member-did "did:plc:x" :capability "datom:transact"
                    :graph "kyoninka" :exp 1 :cacao-b64 "opaque"})]   ; expired
    (is (nil? (:envelope (org/beat o {:now 9999999999 :created-at "t"})))
        "expired leash → publication refused")))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'kyoninka.methods.organism-test)]
    (when (pos? (+ (or fail 0) (or error 0))) (System/exit 1))))
