(require '[clojure.test :as t])

(doseq [ns-sym '[kyoninka.governor-contract-test
                  kyoninka.store-contract-test
                  kyoninka.methods.procedure-test
                  kyoninka.methods.organism-test
                  kyoninka.repository-contract-test]]
  (require ns-sym))

(let [result (apply t/run-tests
                    '[kyoninka.governor-contract-test
                      kyoninka.store-contract-test
                      kyoninka.methods.procedure-test
                      kyoninka.methods.organism-test
                      kyoninka.repository-contract-test])]
  (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1)))
