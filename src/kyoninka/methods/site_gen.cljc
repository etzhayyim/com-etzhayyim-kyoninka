(ns kyoninka.methods.site-gen
  "kyoninka 許認可 — crawlable static web visualization of the robotaxi
  deployment 手続き (permitting procedure).

  Renders, from the `procedure` rulebook, a self-contained static site:
    index.html              the procedure flow + per-jurisdiction requirement
                            matrix + per-deployment readiness board
    <deployment>.html       the full legal checklist (✓/✗ each permit / insurance
                            / filing / level / remote-operator) + verdict +
                            which human authority must sign off

  House style mirrors `tate/methods/site_gen.cljc`: no ad/tracking/external
  asset, inline CSS, a non-adjudicating disclaimer on every page, deployment is
  an operator step. Run:

    clojure -M -m kyoninka.methods.site-gen"
  (:require [clojure.string :as str]
            [kyoninka.methods.procedure :as p]
            #?(:clj [clojure.java.io :as io])))

(def BASE-DEFAULT "https://etzhayyim.com/kyoninka")

(def DISCLAIMER
  (str "本ページは <strong>一般的な法情報 (legal information)</strong> であり、個別の法的助言 "
       "(legal advice) ではありません。kyoninka 許認可 は <strong>observe → recommend のみ</strong> — "
       "許認可を付与せず、車両を起動しません。法域別の要件は<strong>例示</strong>であり、実際の申請前に"
       "必ず専門家・各規制当局で現行法令を確認してください。"))

(def CSS
  (str "body{font-family:system-ui,sans-serif;max-width:54em;margin:1em auto;padding:0 1em;line-height:1.6;color:#1a1a1a}"
       "h1,h2{border-bottom:1px solid #ccc;padding-bottom:.2em}"
       "a{color:#0645ad}.ok{color:#0a7a25;font-weight:bold}.no{color:#b00020;font-weight:bold}"
       "table{border-collapse:collapse;width:100%;margin:1em 0}td,th{border:1px solid #ccc;padding:.35em .6em;text-align:left}"
       "th{background:#f3f3f3}"
       ".flow{display:flex;flex-wrap:wrap;align-items:stretch;gap:.4em;margin:1em 0}"
       ".step{background:#f6f8fa;border:1px solid #d0d7de;border-radius:6px;padding:.5em .7em;flex:1 1 8em;min-width:8em}"
       ".step b{display:block}.arrow{align-self:center;color:#888}"
       ".badge{display:inline-block;padding:.1em .5em;border-radius:4px;font-size:.85em;font-weight:bold}"
       ".b-hold{background:#fde2e2;color:#b00020}.b-esc{background:#fff3cd;color:#7a5b00}.b-ok{background:#d8f3df;color:#0a7a25}"
       ".box{background:#f6f6f6;border-left:4px solid #888;padding:.5em 1em;margin:1em 0}"
       "footer{margin-top:2em;font-size:.85em;color:#555;border-top:1px solid #ccc;padding-top:.6em}"))

(defn- escape [s]
  (-> (str s)
      (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;") (str/replace "'" "&#x27;")))

(defn- yen [n] (str (str/replace (format "%,d" (long n)) "," ",") " 円"))

(defn- page [title body]
  (str "<!doctype html><html lang=\"ja\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
       "<title>" (escape title) "</title><style>" CSS "</style></head><body>"
       body
       "<footer><p>" DISCLAIMER "</p>"
       "<p>許認可 kyoninka · <a href=\"https://etzhayyim.com/actor/kyoninka/profile.json\">actor profile</a> · "
       "did:web:etzhayyim.com:actor:kyoninka · 実装: langgraph-clj actor "
       "(reg-LLM ⊣ PermitGovernor) — Apache-2.0 + Charter Rider.</p></footer></body></html>"))

(defn- verdict-badge [v]
  (let [[cls txt] (case v
                    :hold     ["b-hold" (p/verdict-label v)]
                    :escalate ["b-esc"  (p/verdict-label v)]
                    ["b-ok" (p/verdict-label v)])]
    (str "<span class=\"badge " cls "\">" (escape txt) "</span>")))

(defn- flow-html []
  (str "<div class=\"flow\">"
       (->> p/stages
            (map (fn [{:keys [ja doc]}]
                   (str "<div class=\"step\"><b>" (escape ja) "</b>"
                        "<span>" (escape doc) "</span></div>")))
            (str/join "<div class=\"arrow\">→</div>"))
       "</div>"))

(defn- jurisdiction-matrix []
  (str "<table><thead><tr><th>法域 Jurisdiction</th><th>AV法</th><th>上限</th>"
       "<th>必須許認可</th><th>賠償下限</th><th>届出</th><th>遠隔監視</th></tr></thead><tbody>"
       (->> p/jurisdictions
            (map (fn [j]
                   (str "<tr><td>" (escape (:glyph j)) " <b>" (escape (:id j)) "</b><br>"
                        (escape (:name j)) "<br><small>" (escape (:authority j)) "</small></td>"
                        "<td><small>" (escape (:av-law j)) "</small></td>"
                        "<td>L" (:max-level j) "</td>"
                        "<td>" (count (:required-permits j)) " 件</td>"
                        "<td>" (escape (yen (:min-insurance-jpy j))) "</td>"
                        "<td>" (count (:required-filings j)) " 件</td>"
                        "<td>" (if (:remote-operator-required? j) "<span class=\"ok\">要</span>" "—") "</td></tr>")))
            (str/join))
       "</tbody></table>"))

(defn- readiness-board []
  (str "<table><thead><tr><th>デプロイ Deployment</th><th>法域</th><th>SAE</th>"
       "<th>充足</th><th>判定 Verdict</th><th>詳細</th></tr></thead><tbody>"
       (->> p/deployments
            (map (fn [dep]
                   (let [r (p/evaluate dep)
                         oks (count (filter :ok? (:rows r)))
                         tot (count (:rows r))]
                     (str "<tr><td><b>" (escape (:id dep)) "</b><br><small>" (escape (:area dep)) "</small></td>"
                          "<td>" (escape (:jurisdiction dep)) "</td><td>L" (:sae-level dep) "</td>"
                          "<td>" oks "/" tot "</td>"
                          "<td>" (verdict-badge (:verdict r)) "</td>"
                          "<td><a href=\"" (escape (:id dep)) ".html\">手続き →</a></td></tr>"))))
            (str/join))
       "</tbody></table>"))

(defn index-html []
  (page "許認可 kyoninka — ロボタクシー許認可手続きの可視化"
    (str "<h1>許認可 <small>kyoninka</small></h1>"
         "<p>日本および世界でロボタクシーを公道で走らせるための<strong>法的・行政手続き(許認可)</strong>を、"
         "actor として設計・評価し可視化する。<strong>observe → recommend のみ</strong>: 許認可は付与せず、"
         "車両は起動しない。最終可否は人間の規制当局のサインオフ。</p>"
         "<h2>手続きフロー</h2>"
         "<p>1 run = 1 操作。封じ込めた reg-LLM の提案を、独立した PermitGovernor が法的不変条件で検閲し、"
         "公道走行は必ず規制当局へ。</p>"
         (flow-html)
         "<h2>法域別 要件マトリクス</h2>" (jurisdiction-matrix)
         "<h2>デプロイ準備状況</h2>" (readiness-board)
         "<div class=\"box\">この評価は <code>methods/procedure.cljc</code> の PermitGovernor 不変条件"
         "(法域認識・SAE上限・必須許認可の有効性・賠償下限・届出・遠隔監視・no-actuation)が算出する。"
         "ルールブックは<strong>データ</strong>であり、法域の追加・修正はコード変更でなくレビュー済み編集。</div>")))

(defn deployment-html [dep]
  (let [r (p/evaluate dep)
        jur (p/jurisdiction (:jurisdiction dep))]
    (page (str (:id dep) " — 許認可手続き")
      (str "<p><a href=\"index.html\">← 一覧</a></p>"
           "<h1>" (escape (:id dep)) " <small>" (escape (:area dep)) "</small></h1>"
           "<p>法域 <b>" (escape (:id jur)) "</b> (" (escape (:name jur)) ") · "
           "SAE Level " (:sae-level dep) " · 車両 " (:fleet dep) " 台 · "
           "判定 " (verdict-badge (:verdict r)) "</p>"
           "<div class=\"box\"><b>適用法</b>: " (escape (:av-law jur)) "<br>"
           "<b>所管</b>: " (escape (:authority jur)) "</div>"
           "<h2>法的チェックリスト</h2>"
           "<table><thead><tr><th>区分</th><th>要件</th><th>充足</th></tr></thead><tbody>"
           (->> (:rows r)
                (map (fn [row]
                       (str "<tr><td>" (escape (name (:kind row))) "</td>"
                            "<td>" (escape (:label row))
                            (when (= :insurance (:kind row))
                              (str "<br><small>有効額 " (escape (yen (:have row))) "</small>")) "</td>"
                            "<td>" (if (:ok? row) "<span class=\"ok\">✓</span>"
                                       "<span class=\"no\">✗ 不足</span>") "</td></tr>")))
                (str/join))
           "</tbody></table>"
           (if (:hard? r)
             (str "<div class=\"box\"><b>保留 (HOLD)</b> — 不充足: <code>"
                  (escape (str/join ", " (map name (:violations r))))
                  "</code>。人間でも不充足を超えて承認はできない(構造的不変条件)。</div>")
             (str "<div class=\"box\"><b>" (escape (:authority jur)) "</b> のサインオフが必要。"
                  "チェックリストが全て充足でも、公道走行は高リスクのため必ず人間の規制当局が最終承認する"
                  "(自動では走らせない)。</div>"))))))

#?(:clj
   (defn -main [& args]
     (let [out (or (first args) "50-infra/etzhayyim-did-web/public/kyoninka")]
       (io/make-parents (str out "/index.html"))
       (spit (str out "/index.html") (index-html))
       (doseq [dep p/deployments]
         (spit (str out "/" (:id dep) ".html") (deployment-html dep)))
       (println (str "wrote " out "/index.html + " (count p/deployments) " deployment pages")))))
