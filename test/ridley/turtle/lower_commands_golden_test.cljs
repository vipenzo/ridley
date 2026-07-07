(ns ridley.turtle.lower-commands-golden-test
  "Golden corpus for dev-docs/brief-recording-highlevel-fase1.md — Phase 1
   ('recording alto livello, rappresentazione nuova, comportamento identico').

   GOLDEN VALUES BELOW WERE CAPTURED PRE-FIX (before lower-commands / the
   high-level recording existed), via ridley.editor.sci-harness running the
   exact corpus DSL strings in `corpus` against production macro-defs. They
   are the byte-identical reference: after Phase 1 lands, the SAME corpus,
   evaluated the SAME way, read through whatever accessor replaces direct
   `:commands` access (path-micro-commands), must equal these vectors
   exactly (tags included) — no update to this file is expected as part of
   landing Phase 1. If a value here ever needs to change, that is a
   regression against the phase's own acceptance criterion, not a fixture
   update.

   Covers: straight segments, th/tv/tr corners, arc-h/arc-v, bezier-to
   explicit/quadratic/auto/:local/:preserve-heading, bezier-as
   default/:cubic/:control (incl. a mid-tessellation :control mark),
   bezier-to-anchor, path-2d, mark+side-trip, nested path (follow),
   resolution :n and :s, and resolution-changed-between-def-and-consumption
   (c9a is captured again after the global resolution changes).

   Fase 2a exception (dev-docs/brief-recording-highlevel-fase2a.md, Parte 1):
   `follow` now splices HIGH-LEVEL commands instead of already-lowered ones,
   so a bezier's :pure rider gets recomputed against the actual outer path
   instead of staying frozen in the sub-path's own frame. c8c's golden was
   captured POST-fix (deliberately, per the brief) — its movement/rotation
   micro-commands are pose-invariant and match what pre-fix would also
   produce (verified by hand before landing); only the :pure rider's c1/c2/
   end differ from what a naive pre-fix capture would show."
  (:require [cljs.test :refer [deftest testing is]]
            [clojure.walk :as walk]
            [ridley.editor.sci-harness :as h]
            [ridley.turtle.core :as turtle]))

(def ^:private corpus
  "One DSL string per case, evaluated independently (fresh turtle each time)
   except the c9 pair, which shares a sequence to exercise def-time vs
   consumption-time resolution."
  {:c1  "(path (f 10) (f 5))"
   :c2  "(path (f 10) (th 45) (f 10) (tv 30) (f 10) (tr 20) (f 10))"
   :c3a "(path (arc-h 10 90 :steps 4))"
   :c3b "(path (arc-v 8 60 :steps 3))"
   :c4a "(path (bezier-to [20 10 0] [5 5 0] [15 5 0] :steps 4))"
   :c4b "(path (bezier-to [20 0 0] [10 10 0] :steps 4))"
   :c4c "(path (bezier-to [20 10 0] :steps 4))"
   :c4d "(path (bezier-to [10 0 5] :local :steps 3))"
   :c4e "(path (bezier-to [20 0 0] :preserve-heading :steps 4 :tension 0.5))"
   :c5a "(do (def skel (path (f 10) (th 30) (f 10) (th -20) (f 10)))
             (path (bezier-as skel :steps 3)))"
   :c5b "(do (def skel (path (f 10) (th 30) (f 10) (th -20) (f 10)))
             (path (bezier-as skel :cubic true :steps 3)))"
   :c5c "(do (def skel (path (f 10) (th 30) (f 10) (th -20) (f 10)))
             (path (bezier-as skel :control true :steps 3)))"
   :c5d "(do (def skel-marked (path (f 10) (mark :mid) (th 30) (f 10) (th -20) (f 10) (mark :end2)))
             (path (bezier-as skel-marked :control true :steps 4)))"
   :c6  "(path (f 10) (mark :m1) (th 20) (f 10) (bezier-to-anchor :m1 :steps 4))"
   :c7  "(path-2d (f 10) (th 30) (f 10))"
   :c8a "(path (f 10) (mark :a) (side-trip (path (th 90) (f 5))) (f 10))"
   :c8b "(do (def sub (path (f 5) (th 10) (f 5)))
             (path (f 10) (follow sub) (f 10)))"
   ;; Fase 2a: follow splices HIGH-LEVEL commands now (dev-docs/brief-
   ;; recording-highlevel-fase2a.md, Parte 1) — a bezier inside `sub`,
   ;; preceded by a rotation in the outer path, is the trap test: movement
   ;; micro-commands must stay pose-invariant (identical), only the :pure
   ;; rider's c1/c2/end change (now correctly re-expressed against outer's
   ;; real entry pose, instead of staying frozen in sub's own frame — see
   ;; dev-docs/code-issues.md, \"il rider :pure ... è congelato\").
   :c8c "(do (def sub2 (path (bezier-to [10 0 5] :steps 4)))
              (path (th 90) (follow sub2) (f 3)))"})

;; c9a/c9b need to share one turtle-state/context sequence (resolution set
;; globally, then two paths defined, then resolution changed again) — see
;; the accertamento's residual point 1 (resolution must be captured at
;; record time, not re-read at consumption time).
(def ^:private c9-sequence
  ["(resolution :n 4)"
   "(def c9a (path (bezier-to [20 10 0])))"
   "(def c9b (path (resolution :s 2) (bezier-to [20 10 0])))"
   "(resolution :n 64)"
   "[c9a c9b]"])

(def ^:private golden
  {:c1  [{:cmd :f, :args [10]} {:cmd :f, :args [5]}]
   :c2  [{:cmd :f, :args [10]} {:cmd :th, :args [45]} {:cmd :f, :args [10]}
         {:cmd :tv, :args [30]} {:cmd :f, :args [10]} {:cmd :tr, :args [20]} {:cmd :f, :args [10]}]
   :c3a [{:cmd :th, :args [11.25], :arc-cap :lead} {:cmd :f, :args [3.9018064403225647]}
         {:cmd :th, :args [22.5]} {:cmd :f, :args [3.9018064403225647]}
         {:cmd :th, :args [22.5]} {:cmd :f, :args [3.9018064403225647]}
         {:cmd :th, :args [22.5]} {:cmd :f, :args [3.9018064403225647]}
         {:cmd :th, :args [11.25], :arc-cap :trail}]
   :c3b [{:cmd :tv, :args [10], :arc-cap :lead} {:cmd :f, :args [2.7783708426708853]}
         {:cmd :tv, :args [20]} {:cmd :f, :args [2.7783708426708853]}
         {:cmd :tv, :args [20]} {:cmd :f, :args [2.7783708426708853]}
         {:cmd :tv, :args [10], :arc-cap :trail}]
   :c4a [{:cmd :th, :args [33.23171106797936], :smooth true, :bez-cap :lead, :veer-deg 45.00000000000001,
          :pure {:cmd :bezier-to, :c1 [5 5 0], :c2 [15 5 0], :end [20 10 0], :span 8}}
         {:cmd :f, :args [5.417167444799912]}
         {:cmd :th, :args [-12.855275854142974], :smooth true}
         {:cmd :f, :args [5.833798344560772]}
         {:cmd :f, :args [5.833798344560772]}
         {:cmd :th, :args [12.855275854142969], :smooth true}
         {:cmd :f, :args [5.417167444799912]}
         {:cmd :th, :args [11.768288932020644], :smooth true}]
   :c4b [{:cmd :th, :args [43.45184230102203], :smooth true, :bez-cap :lead, :veer-deg 45.00000000000001,
          :pure {:cmd :bezier-to, :c1 [10 10 0], :c2 [10 10 0], :end [20 0 0], :span 9}}
         {:cmd :f, :args [8.178907705189001]}
         {:cmd :th, :args [-18.67670173219011], :smooth true}
         {:cmd :f, :args [4.47431908227386]}
         {:cmd :th, :args [-49.550281137663845], :smooth true}
         {:cmd :f, :args [4.47431908227386]}
         {:cmd :th, :args [-18.676701732190118], :smooth true}
         {:cmd :f, :args [8.178907705189001]}
         {:cmd :th, :args [-1.5481576989779626], :smooth true}]
   :c4c [{:cmd :th, :args [11.687685807512748], :smooth true, :bez-cap :lead, :veer-deg 0,
          :pure {:cmd :bezier-to, :c1 [7.379024325749307 0 0], :c2 [13.399999999999999 6.699999999999999 0], :end [20 10 0], :span 9}}
         {:cmd :f, :args [5.422325366084945]}
         {:cmd :th, :args [16.446287986314555], :smooth true}
         {:cmd :f, :args [5.649767872148324]}
         {:cmd :th, :args [6.034872701755426], :smooth true}
         {:cmd :f, :args [5.845114299082352]}
         {:cmd :th, :args [-2.932020929500846], :smooth true}
         {:cmd :f, :args [5.6976869193099615]}
         {:cmd :th, :args [-4.671774389003893], :smooth true}]
   :c4d [{:cmd :th, :args [-35.889969533501734], :smooth true, :bez-cap :lead, :veer-deg 0,
          :pure {:cmd :bezier-to, :c1 [3.6895121628746534 0 0], :c2 [3.3499999999999996 -6.699999999999999 0], :end [5 -10 0], :span 7}}
         {:cmd :f, :args [3.171549652364789]}
         {:cmd :th, :args [-37.4571008519544], :smooth true}
         {:cmd :f, :args [4.26016005899636]}
         {:cmd :th, :args [-0.057851784261887904], :smooth true}
         {:cmd :f, :args [4.235687884414417]}
         {:cmd :th, :args [9.969973346796014], :smooth true}]
   :c4e [{:cmd :f, :args [5.9375], :pure {:cmd :bezier-to, :c1 [10 0 0], :c2 [10 0 0], :end [20 0 0], :span 4}}
         {:cmd :f, :args [4.0625]} {:cmd :f, :args [4.0625]} {:cmd :f, :args [5.9375]}]
   :c5a [{:cmd :f, :args [3.325925925925926]} {:cmd :f, :args [3.348148148148148]} {:cmd :f, :args [3.325925925925926]}
         {:cmd :th, :args [16.811589758127038]} {:cmd :f, :args [3.2142042519245124]}
         {:cmd :th, :args [-16.811589758127038]} {:cmd :th, :args [36.07292942663576]}
         {:cmd :f, :args [3.465846377293325]} {:cmd :th, :args [0.0391147626873444]}
         {:cmd :f, :args [3.443749667784148]} {:cmd :th, :args [-6.112044189323098]}
         {:cmd :th, :args [-11.192362284686743]} {:cmd :f, :args [3.2761070254206013]}
         {:cmd :th, :args [11.192362284686746]} {:cmd :th, :args [-24.22846367322487]}
         {:cmd :f, :args [3.4016329045652562]} {:cmd :th, :args [-0.027779665075541183]}
         {:cmd :f, :args [3.379471569103428]} {:cmd :th, :args [4.256243338300386]}]
   :c5b [{:cmd :th, :args [-3.241852521589766], :bez-cap :lead, :veer-deg 0}
         {:cmd :f, :args [3.3562846411294633]} {:cmd :th, :args [3.2418525215897662]}
         {:cmd :th, :args [-3.220540299383579]} {:cmd :f, :args [3.378471535346253]}
         {:cmd :th, :args [9.83021993634037]} {:cmd :f, :args [3.2978703144135557]}
         {:cmd :th, :args [8.390320363043207]} {:cmd :th, :args [10.611586227666605]}
         {:cmd :f, :args [3.296756732685807]} {:cmd :th, :args [-10.611586227666614]}
         {:cmd :th, :args [20.353587504539277]} {:cmd :f, :args [3.3991041893874234]}
         {:cmd :th, :args [-6.4702841993315925]} {:cmd :f, :args [3.329264000537785]}
         {:cmd :th, :args [-8.883303305207685]} {:cmd :th, :args [-5.591682200708623]}
         {:cmd :f, :args [3.3134464497702822]} {:cmd :th, :args [5.5916822007086155]}
         {:cmd :th, :args [-12.1708959591736]} {:cmd :f, :args [3.3617018663524103]}
         {:cmd :th, :args [-0.01444247736513865]} {:cmd :f, :args [3.339495699401179]}
         {:cmd :th, :args [2.185338436538741]}]
   :c5c [{:cmd :th, :args [2.634606018523227], :bez-cap :lead, :veer-deg 0}
         {:cmd :f, :args [6.043068370621227]} {:cmd :th, :args [-2.6346060185232276]}
         {:cmd :th, :args [9.896090638982898]} {:cmd :f, :args [4.848854851960684]}
         {:cmd :th, :args [11.654795998838784]} {:cmd :f, :args [3.781065076110234]}
         {:cmd :th, :args [8.449113362178316]} {:cmd :th, :args [-5.6784458566165545]}
         {:cmd :f, :args [3.8407276676688467]} {:cmd :th, :args [5.678445856616552]}
         {:cmd :th, :args [-13.363727411622994]} {:cmd :f, :args [4.932536670794481]}
         {:cmd :th, :args [-4.84555228587903]} {:cmd :f, :args [6.080576554852279]}
         {:cmd :th, :args [-1.790720302497968]}]
   :c5d [{:cmd :th, :args [1.92634506333143], :bez-cap :lead, :veer-deg 0}
         {:cmd :f, :args [4.648259831751349]} {:cmd :th, :args [-1.92634506333143]}
         {:cmd :th, :args [6.790001605990572]} {:cmd :f, :args [3.9647066537512523]}
         {:cmd :mark, :args [:mid]}
         {:cmd :th, :args [6.8146054458025676]} {:cmd :f, :args [3.3213549720406936]}
         {:cmd :th, :args [9.862305142070081]} {:cmd :f, :args [2.746601438773576]}
         {:cmd :th, :args [6.533087806136772]} {:cmd :th, :args [-4.405188349729648]}
         {:cmd :f, :args [2.7830295111553283]} {:cmd :th, :args [4.4051883497296505]}
         {:cmd :th, :args [-10.918356966460953]} {:cmd :f, :args [3.385711550016844]}
         {:cmd :th, :args [-4.505349315996313]} {:cmd :f, :args [4.0187737806347625]}
         {:cmd :th, :args [-3.2648284302277806]} {:cmd :f, :args [4.669877221079958]}
         {:cmd :th, :args [-1.3114652873149457]}
         {:cmd :mark, :args [:end2]}]
   :c6  [{:cmd :f, :args [10]} {:cmd :mark, :args [:m1]} {:cmd :th, :args [20]} {:cmd :f, :args [10]}]
   :c7  [{:cmd :th, :args [-90]} {:cmd :f, :args [10]} {:cmd :tv, :args [30]} {:cmd :f, :args [10]}]
   :c8a [{:cmd :f, :args [10]} {:cmd :mark, :args [:a]}
         {:cmd :side-trip, :args [{:type :path, :commands [{:cmd :th, :args [90]} {:cmd :f, :args [5]}]}]}
         {:cmd :f, :args [10]}]
   :c8b [{:cmd :f, :args [10]} {:cmd :f, :args [5]} {:cmd :th, :args [10]} {:cmd :f, :args [5]} {:cmd :f, :args [10]}]
   :c8c [{:cmd :th, :args [90]}
         {:cmd :tv, :args [11.687685807512748], :smooth true, :bez-cap :lead, :veer-deg 0,
          :pure {:cmd :bezier-to,
                 :c1 [2.2591746303398364E-16 3.6895121628746534 0],
                 :c2 [4.102566777143633E-16 6.699999999999999 3.3499999999999996],
                 :end [6.123233995736766E-16 10 5], :span 9}}
         {:cmd :f, :args [2.7111626830424727]}
         {:cmd :tv, :args [16.44628798631455], :smooth true}
         {:cmd :f, :args [2.824883936074162]}
         {:cmd :tv, :args [6.034872701755433], :smooth true}
         {:cmd :f, :args [2.922557149541176]}
         {:cmd :tv, :args [-2.9320209295008492], :smooth true}
         {:cmd :f, :args [2.8488434596549808]}
         {:cmd :tv, :args [-4.671774389003898], :smooth true}
         {:cmd :f, :args [3]}]})

(def ^:private golden-c9a
  [{:cmd :th, :args [11.687685807512748], :smooth true, :bez-cap :lead, :veer-deg 0,
    :pure {:cmd :bezier-to, :c1 [7.379024325749307 0 0], :c2 [13.399999999999999 6.699999999999999 0], :end [20 10 0], :span 9}}
   {:cmd :f, :args [5.422325366084945]}
   {:cmd :th, :args [16.446287986314555], :smooth true}
   {:cmd :f, :args [5.649767872148324]}
   {:cmd :th, :args [6.034872701755426], :smooth true}
   {:cmd :f, :args [5.845114299082352]}
   {:cmd :th, :args [-2.932020929500846], :smooth true}
   {:cmd :f, :args [5.6976869193099615]}
   {:cmd :th, :args [-4.671774389003893], :smooth true}])

(def ^:private golden-c9b
  [{:cmd :th, :args [4.208257652937551], :smooth true, :bez-cap :lead, :veer-deg 0,
    :pure {:cmd :bezier-to, :c1 [7.379024325749307 0 0], :c2 [13.399999999999999 6.699999999999999 0], :end [20 10 0], :span 25}}
   {:cmd :f, :args [1.8224980083510274]} {:cmd :th, :args [7.856667093033361], :smooth true}
   {:cmd :f, :args [1.8076535568083243]} {:cmd :th, :args [6.7280660739980185], :smooth true}
   {:cmd :f, :args [1.8217092453249586]} {:cmd :th, :args [5.466892332133611], :smooth true}
   {:cmd :f, :args [1.8517059595893408]} {:cmd :th, :args [4.219229467338947], :smooth true}
   {:cmd :f, :args [1.8868252588068573]} {:cmd :th, :args [3.0531113513455126], :smooth true}
   {:cmd :f, :args [1.9188762354122488]} {:cmd :th, :args [1.9795218721438432], :smooth true}
   {:cmd :f, :args [1.9421566821835266]} {:cmd :th, :args [0.9777748489606156], :smooth true}
   {:cmd :f, :args [1.9531124303983485]} {:cmd :th, :args [0.012580400728447886], :smooth true}
   {:cmd :f, :args [1.9500364843000169]} {:cmd :th, :args [-0.9569529643745245], :smooth true}
   {:cmd :f, :args [1.9329084555052636]} {:cmd :th, :args [-1.9724520278377775], :smooth true}
   {:cmd :f, :args [1.903406777242241]} {:cmd :th, :args [-3.0712773628510575], :smooth true}
   {:cmd :f, :args [1.865097979973258]} {:cmd :th, :args [-1.9363675604785684], :smooth true}])

(defn- strip-micro-commands
  "Phase 1 attaches a :micro-commands memoization delay to every constructed
   path map, including nested ones (e.g. a :side-trip's sub-path in :args) —
   a caching artifact, not part of the byte-identical surface this golden
   corpus checks (and Delay has no value equality anyway). Strip it
   recursively before comparing to the pre-fix golden literals."
  [cmds]
  (walk/postwalk (fn [x] (if (map? x) (dissoc x :micro-commands) x)) cmds))

(defn- approx=
  "Structural equality, but numbers compare within a tolerance instead of
   exactly. The golden literals were captured directly (no local<->world
   round-trip); Phase 1's lowering decodes bezier-to/bezier-as control
   points and directions through world->local/local->world first, which is
   mathematically an identity but not bit-identical (floating-point
   non-associativity) — a ~1e-12 relative difference in the last couple of
   significant digits is expected there, not a regression. :span (an exact
   integer count) still has to match exactly, same as everything non-numeric
   (tags, keywords, structure)."
  [a b]
  (cond
    (and (number? a) (number? b)) (< (abs (- a b)) 1e-6)
    (and (map? a) (map? b)) (and (= (set (keys a)) (set (keys b)))
                                 (every? (fn [k] (approx= (get a k) (get b k))) (keys a)))
    (and (sequential? a) (sequential? b)) (and (= (count a) (count b))
                                               (every? true? (map approx= a b)))
    :else (= a b)))

(deftest golden-corpus-byte-identical
  (testing "single-form corpus cases match the pre-fix golden :commands vector"
    (doseq [[k dsl] corpus]
      (let [{:keys [result error]} (h/eval-dsl dsl)]
        (is (nil? error) (str k " raised: " error))
        (is (approx= (get golden k) (strip-micro-commands (turtle/path-micro-commands result)))
            (str k " diverged from golden")))))
  (testing "resolution captured at record time, not re-read at consumption time"
    (let [{:keys [result error]} (apply h/eval-dsl-seq c9-sequence)
          [c9a c9b] result]
      (is (nil? error) (str "c9 sequence raised: " error))
      (is (approx= [golden-c9a golden-c9b]
                   (strip-micro-commands [(turtle/path-micro-commands c9a) (turtle/path-micro-commands c9b)]))))))

(deftest follow-splices-high-level-so-bezier-pure-rider-tracks-outer-pose
  ;; Regression for dev-docs/code-issues.md, "Il rider :pure di un bezier è
  ;; congelato nel frame del sotto-path, non del path che lo `follow`a" — closed
  ;; by dev-docs/brief-recording-highlevel-fase2a.md Parte 1 (rec-follow* now
  ;; splices sub's HIGH-LEVEL commands, so :pure is recomputed against the
  ;; outer path's real entry pose when lower-commands tessellates it). c8c in
  ;; the golden corpus above covers the same fix at the :commands level; this
  ;; test asserts the rider's WORLD c1/c2/end are actually correct, not just
  ;; different from sub's own frame.
  (testing "outer :pure rider matches sub's local c1/c2/end transformed through outer's real entry pose"
    (let [{:keys [result error]}
          (h/eval-dsl "(do (def sub (path (bezier-to [10 0 5] :steps 4)))
                            [sub (path (th 90) (follow sub))])")
          [sub outer] result
          sub-cmd (first (:commands sub))
          outer-pure (:pure (first (filter :pure (turtle/path-micro-commands outer))))
          entry (turtle/th {:heading [1 0 0] :up [0 0 1]} 90)
          expect (fn [local] (turtle/local->world {:position [0 0 0]
                                                   :heading (:heading entry) :up (:up entry)}
                                                  local))]
      (is (nil? error) (str "raised: " error))
      (is (some? outer-pure) "outer path's spliced bezier must carry a :pure rider")
      (is (approx= (expect (:c1 sub-cmd)) (:c1 outer-pure))
          "rider c1 must be computed against the outer path's real entry pose (post th 90), not sub's identity pose")
      (is (approx= (expect (:c2 sub-cmd)) (:c2 outer-pure)))
      (is (approx= (expect (:end sub-cmd)) (:end outer-pure)))
      (is (not (approx= (:c1 sub-cmd) (:c1 outer-pure)))
          "rider must differ from sub's own frozen local encoding — proves it is not stale"))))
