(ns kyoninka.repository-contract-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]))

(deftest canonical-repository-shape
  (doseq [path ["manifest.edn" "identity.edn" "dependencies.edn"
                "repository-contracts.edn" "deps.edn"]]
    (is (map? (edn/read-string (slurp path))) path))
  (is (not (re-find #":local/root" (slurp "deps.edn")))))
