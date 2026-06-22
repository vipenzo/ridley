(ns ridley.turtle.corner-cost-measure-test
  "MEASUREMENT harness (not a pass/fail suite — prints tables).

   Brief: misurare il costo del corner-treatment e la sua soglia di densità.

   The corner-treatment reader `corner-rotation?` (extrusion.cljs:1111) fires in
   BOTH the extrude sweep (extrude-from-path) and the loft sweep (loft.cljs:455):
   an UNTAGGED th/tv is a hard corner (segment split + shortening + joint rings);
   a :smooth th/tv (what bezier-to records) is skipped.

   Key experiment = TAG-FLIP: one hand-built (f)(th) rail swept twice — once with
   th's left untagged (what arc-h / a hand path produce), once with the SAME th's
   programmatically tagged :smooth (what Class-A1 would emit). Same geometry, same
   density; the only variable is the tag. This isolates the tag's cost exactly.

   SOLID  = extrude (canonical joint-welding sweep, pure-extrude-path).
   FORATO = embroid honeycomb wall lofted along the rail (pure-loft-shape-fn).

   Run: npx shadow-cljs compile test && node out/test.js
   then grep for the >>> lines."
  (:require [cljs.test :refer [deftest is]]
            [ridley.editor.sci-harness :as h]
            [ridley.editor.operations :as ops]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.shape-fn :as sfn]
            [ridley.geometry.mesh-utils :as mu]))

;; ── metrics ───────────────────────────────────────────────

(defn- bbox-extent [mesh]
  (let [vs (:vertices mesh)]
    (when (seq vs)
      (mapv (fn [axis]
              (let [xs (map #(nth % axis) vs)]
                (/ (Math/round (* 100 (- (apply max xs) (apply min xs)))) 100.0)))
            [0 1 2]))))

(defn- metrics [thunk]
  (try
    (let [m (thunk)]
      (if (and (map? m) (seq (:vertices m)))
        (let [d (mu/mesh-diagnose m)]
          {:faces (count (:faces m))
           :verts (count (:vertices m))
           :wt (:is-watertight? d)
           :nm (:non-manifold-edges d)
           :oe (:open-edges d)
           :bbox (bbox-extent m)})
        {:err "nil/empty mesh"}))
    (catch :default e {:err (.-message e)})))

(defn- fmt [m]
  (if (:err m)
    (str "ERR(" (:err m) ")")
    (str "faces=" (:faces m) " verts=" (:verts m)
         " wt=" (:wt m) " nm=" (:nm m) " oe=" (:oe m) " bbox=" (:bbox m))))

;; ── rails ─────────────────────────────────────────────────

(defn- rail-from-dsl [code] (:result (h/eval-dsl code)))

(def R 30)
(def ARC-LEN (* (/ Math/PI 2) R))                       ; ≈ 47.124
(def bz-k (* (/ 4 3) (Math/tan (/ Math/PI 8)) R))       ; ≈ 16.57

(defn- tag-smooth
  "Flip every th/tv on a rail to :smooth — what Class-A1 would emit. Same
   geometry, only the tag bit changes."
  [rail]
  (update rail :commands
          (fn [cmds] (mapv #(if (#{:th :tv} (:cmd %)) (assoc % :smooth true) %) cmds))))

(defn- rail-arc-h [steps] (rail-from-dsl (str "(path (arc-h " R " 90 :steps " steps "))")))
(defn- rail-bezier [] (rail-from-dsl (str "(path (bezier-to [" R " " R " 0] [" bz-k " 0 0] [" R " " (- R bz-k) " 0]))")))
(defn- rail-manual [n total-deg L]
  (let [a (/ total-deg n) d (/ L n)]
    (rail-from-dsl (str "(path (dotimes [_ " n "] (f " d ") (th " a ")))"))))

;; ── sweeps ────────────────────────────────────────────────

(defn- solid [rail] (metrics #(ops/pure-extrude-path (shape/circle-shape 6 24) rail)))
(defn- solid-loft [rail] (metrics #(ops/pure-loft-path (shape/circle-shape 6 24) (fn [s _t] s) rail)))

(def WALL (rail-from-dsl "(path (f 24))"))
(defn- forato [rail]
  (metrics #(ops/pure-loft-shape-fn
             (sfn/embroid WALL 3 :wall {:style :honeycomb :cells 6 :margin 0.12}) rail)))

;; ── PART 1: three equivalent forms, solid (extrude+loft) vs forato ──

(deftest part1-solid-vs-perforated
  (println "\n========== PART 1: quarter circle R=30 / 90°, three forms ==========")
  (doseq [[label rail]
          [["arc-h(16) untagged" (rail-arc-h 16)]
           ["bezier   :smooth  " (rail-bezier)]
           ["manual(16) untagged" (rail-manual 16 90 ARC-LEN)]]]
    (println (str ">>> " label
                  "\n      SOLID-extrude: " (fmt (solid rail))
                  "\n      SOLID-loft   : " (fmt (solid-loft rail))
                  "\n      FORATO       : " (fmt (forato rail)))))
  (is true))

;; ── PART 1b: TAG-FLIP — same rail, corner vs :smooth ──

(deftest part1b-tag-flip
  (println "\n========== PART 1b: TAG-FLIP (same manual-16 rail, corner vs :smooth) ==========")
  (let [rail (rail-manual 16 90 ARC-LEN)
        smooth (tag-smooth rail)]
    (println (str ">>> CORNER (untagged th)"
                  "\n      SOLID-extrude: " (fmt (solid rail))
                  "\n      SOLID-loft   : " (fmt (solid-loft rail))
                  "\n      FORATO       : " (fmt (forato rail))))
    (println (str ">>> SMOOTH (:smooth th, same geometry)"
                  "\n      SOLID-extrude: " (fmt (solid smooth))
                  "\n      SOLID-loft   : " (fmt (solid-loft smooth))
                  "\n      FORATO       : " (fmt (forato smooth)))))
  (is true))

;; ── PART 2 axis A: segment count at fixed 90° / fixed length, corner vs smooth ──

(deftest part2-axisA-segment-count
  (println "\n========== PART 2 AXIS A: N segments (fixed 90°, length≈47) — corner vs smooth ==========")
  (doseq [n [4 8 16 32 64 90]]
    (let [rail (rail-manual n 90 ARC-LEN)
          smooth (tag-smooth rail)
          se (solid rail)   ss (solid smooth)
          fe (forato rail)  fs (forato smooth)
          fr (when (and (:faces fe) (:faces fs) (pos? (:faces fs)))
               (/ (Math/round (* 100 (/ (:faces fe) (:faces fs)))) 100.0))]
      (println (str ">>> N=" n
                    " | SOLID corner f=" (:faces se) " wt=" (:wt se) " nm=" (:nm se)
                    " / smooth f=" (:faces ss) " wt=" (:wt ss)
                    " | FORATO corner f=" (:faces fe) " wt=" (:wt fe)
                    " / smooth f=" (:faces fs) " | forato corner/smooth=" fr))))
  (is true))

;; ── PART 2 axis B: per-th angle at fixed count + length, corner vs smooth ──

(deftest part2-axisB-per-th-angle
  (println "\n========== PART 2 AXIS B: per-th angle α (fixed n=24, length=48) — corner vs smooth ==========")
  (doseq [a [0.5 1 2 5 10 20]]
    (let [n 24
          rail (rail-manual n (* n a) 48)
          smooth (tag-smooth rail)
          se (solid rail)   ss (solid smooth)
          fe (forato rail)  fs (forato smooth)
          fr (when (and (:faces fe) (:faces fs) (pos? (:faces fs)))
               (/ (Math/round (* 100 (/ (:faces fe) (:faces fs)))) 100.0))]
      (println (str ">>> α=" a "° (turn=" (* n a) "°)"
                    " | SOLID corner f=" (:faces se) " wt=" (:wt se) " nm=" (:nm se)
                    " / smooth f=" (:faces ss) " wt=" (:wt ss)
                    " | FORATO corner f=" (:faces fe) " wt=" (:wt fe)
                    " / smooth f=" (:faces fs) " | forato corner/smooth=" fr))))
  (is true))

;; ── PART 3: guardrail meshes' real diagnostics (the two committed tests) ──

(deftest part3-guardrail-diagnostics
  (println "\n========== PART 3: guardrail mesh diagnostics (current baseline) ==========")
  (doseq [[label code]
          [["arc-profile EXTRUDE"
            (str "(extrude (path-to-shape (path-2d (move-to [-20 -8])"
                 " (f 40) (arc-h 8 90) (f 16) (arc-h 8 90)"
                 " (f 40) (arc-h 8 90) (f 16) (arc-h 8 90))) (f 14))")]
           ["arc-profile REVOLVE"
            "(revolve (path-to-shape (path-2d (move-to [0 -8]) (arc-h 8 180))) 360)"]]]
    (let [{:keys [result error]} (h/eval-dsl code)]
      (println (str ">>> " label ": "
                    (if error (str "ERR(" error ")")
                        (fmt (metrics (fn [] result))))))))
  (is true))
