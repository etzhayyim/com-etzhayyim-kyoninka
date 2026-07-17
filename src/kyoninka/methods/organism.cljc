(ns kyoninka.methods.organism
  "kyoninka 許認可 — the autonomous-publication cell, built on the kototama
  common organism library (SHA-pinned kotoba-lang/kototama). Per the 種をまく
  doctrine (ADR-2606281500) kyoninka PUBLISHES its deployment-readiness digest
  autonomously — no per-post operator prior restraint — bounded by the seed:
  a revocable member CACAO leash, the Rider §2 content scan, and the append-only
  public kotoba log. PUBLICATION ≠ ACTUATION: this cell only SPEAKS the
  assessment; the robotaxi launch sign-off (manifest G3) and no-permit-grant
  (G1) stay human-gated.

  Run (classpath spans this actor + the kototama lib):
    clojure -M -m kyoninka.methods.organism"
  (:require [clojure.string :as str]
            [kototama.organism :as org]
            [kototama.leash :as leash]
            [kyoninka.methods.procedure :as p]))

(defn- verdict-short [v] (case v :escalate "要署名" :hold "保留" :commit "可" (str v)))

(defn readiness-digest
  "A ≤300-grapheme readiness digest folded from the procedure evaluation of
  every known deployment — the post text kyoninka publishes."
  [created-at]
  (let [rows (->> p/deployments
                  (map (fn [dep]
                         (let [r (p/evaluate dep)
                               oks (count (filter :ok? (:rows r)))
                               tot (count (:rows r))]
                           (str (:id dep) ":" oks "/" tot " " (verdict-short (:verdict r))))))
                  (str/join " · "))
        ymd  (let [s (str created-at)] (if (>= (count s) 10) (subs s 0 10) s))
        text (str "許認可準備ダイジェスト " ymd
                  " — " rows "。最終可否は各規制当局。")]
    (if (> (count text) 300) (str (subs text 0 297) "…") text)))

(defn decide
  "kototama decide hook: speak the digest; tag a mood event by overall health."
  [{:keys [created-at]}]
  (let [held (count (filter #(= :hold (:verdict (p/evaluate %))) p/deployments))]
    {:events [(if (zero? held) :event/flourishing :event/uncertain)]
     :act? true
     :post-text (readiness-digest created-at)}))

(defn build
  "Construct the kyoninka organism. `leash-map` is the member-issued CACAO leash
  (present-only). Posts to :app-aozora by default; :com-etzhayyim also available."
  [leash-map & [{:keys [target] :or {target :app-aozora}}]]
  (org/organism
    {:id "did:web:etzhayyim.com:actor:kyoninka"
     :baseline 1 :target target
     :leash (leash/leash leash-map)
     :decide decide}))

(defn -main [& _]
  ;; Demo leash (in production the member issues this in their own runtime and
  ;; the secret seed never touches the repo). now/created-at are passed in — no
  ;; wall-clock in the cell.
  (let [now 1782604800
        created-at "2026-06-28T00:00:00Z"
        o (build {:member-did "did:plc:founder" :capability "datom:transact"
                  :graph "kyoninka" :exp 9999999999 :cacao-b64 "<member-signed-opaque>"})
        r (org/beat o {:now now :created-at created-at})]
    (println "mood     :" (:mood r))
    (println "post     :" (:post-text r))
    (println "envelope :" (select-keys (:envelope r)
                                       [:status :target :requiresMemberSignature
                                        :serverHeldKey :writeAuthor :pds]))
    (println "head-cid :" (:head-cid r))
    (println "(status :dry-run — member/leashed node signs; kyoninka asserts no :published)")))
