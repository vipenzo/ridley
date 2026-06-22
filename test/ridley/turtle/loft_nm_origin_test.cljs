(ns ridley.turtle.loft-nm-origin-test
  "EXECUTABLE DIAGNOSIS of the loft non-manifold defect (companion to
   loft_nm_isolation_test, which is the known-defect tripwire). Prints the
   measurements behind the origin report and locks the key conclusions as asserts.

   Findings (all reproduced here):
   - Shared builders: loft.cljs:27,37 re-export build-sweep-mesh /
     generate-tapered-corner-rings from extrude. Same correct ring math.
   - Divergence is ASSEMBLY: extrude feeds ALL rings to ONE build-sweep-mesh
     (extrusion.cljs:1810) → 1 sub-mesh, manifold. loft builds section +
     corner-bridge + caps as SEPARATE sub-meshes (loft.cljs:920,975,812) → 5 for
     one corner / 129 for an arc → concat + merge-vertices(1e-4)
     (operations.cljs:178). The non-manifold is BORN AT THE WELD: pre-weld nm=0
     / oe=128, post-weld oe=0 / nm=186.
   - Two SEPARABLE contributors (part1-cap-isolation): (a) double-capping — each
     true end is capped twice, by do-build AND a separate make-cap-mesh
     (loft.cljs:812-815); dropping the separate :cap sub-meshes removes all 120
     duplicate faces and drops nm 186→64, leaving (b) the corner-bridge seam
     (64 incidence-3) untouched.
   - revolve is NOT affected (part2): it uses its own continuous grid builder
     (revolve-shape, core.cljs:1944), watertight even with hard/concave corners.

   Run: npx shadow-cljs compile test && node out/test.js ; grep '>>>|=='"
  (:require [cljs.test :refer [deftest is]]
            [ridley.editor.sci-harness :as h]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.shape :as shape]
            [ridley.geometry.mesh-utils :as mu]))

;; ── raw combine (concat, offset faces) — NO weld ──
(defn- raw-combine [meshes]
  (reduce (fn [acc m]
            (let [off (count (:vertices acc))]
              {:vertices (into (:vertices acc) (:vertices m))
               :faces (into (:faces acc)
                            (mapv (fn [f] (mapv #(+ % off) f)) (:faces m)))}))
          {:vertices [] :faces []} (filter map? meshes)))

(defn- topo [mesh]
  (let [d (mu/mesh-diagnose mesh)]
    {:f (count (:faces mesh)) :v (count (:vertices mesh))
     :oe (:open-edges d) :nm (:non-manifold-edges d) :wt (:is-watertight? d)}))

;; duplicate faces: same sorted rounded-position triple
(defn- dup-faces [mesh]
  (let [vs (:vertices mesh)
        key (fn [i] (let [p (nth vs i)] [(Math/round (* 1e4 (nth p 0)))
                                         (Math/round (* 1e4 (nth p 1)))
                                         (Math/round (* 1e4 (nth p 2)))]))
        ftri (map (fn [f] (sort (map key f))) (:faces mesh))
        freq (frequencies ftri)]
    {:dup-face-groups (count (filter (fn [[_ n]] (> n 1)) freq))
     :dup-face-total (reduce + 0 (keep (fn [[_ n]] (when (> n 1) n)) freq))}))

;; rail-localization of nm edges (index-keyed, like mesh-diagnose), as midpoints
(defn- nm-mid-spread [mesh]
  (let [vs (:vertices mesh) faces (:faces mesh)
        inc (reduce (fn [acc f]
                      (let [a (nth f 0) b (nth f 1) c (nth f 2)]
                        (reduce (fn [mm [x y]] (update mm (if (< x y) [x y] [y x]) (fnil inc 0)))
                                acc [[a b] [b c] [c a]])))
                    {} faces)
        bad (filter (fn [[_ n]] (> n 2)) inc)
        mids (map (fn [[[i j] _]] (mapv #(/ (+ (nth (nth vs i) %) (nth (nth vs j) %)) 2.0) [0 1 2])) bad)]
    {:nm-count (count bad)
     :by-incidence (frequencies (map second bad))
     :x-range (when (seq mids) (mapv #(/ (Math/round (* 100 %)) 100.0)
                                     [(apply min (map #(nth % 0) mids)) (apply max (map #(nth % 0) mids))]))
     :y-range (when (seq mids) (mapv #(/ (Math/round (* 100 %)) 100.0)
                                     [(apply min (map #(nth % 1) mids)) (apply max (map #(nth % 1) mids))]))}))

(def ^:private init (turtle/make-turtle))
(defn- rail [code] (:result (h/eval-dsl code)))
(def ^:private circ (shape/circle-shape 10 32))
(defn- id [s _t] s)

(deftest origin-one-corner
  (println "\n==== ORIGIN: one hard corner (f 20)(th 90)(f 20), circle 10/32 ====")
  (let [p (rail "(path (f 20) (th 90) (f 20))")
        ;; LOFT path
        lstate (turtle/loft-from-path init circ id p 64)
        lsubs  (:meshes lstate)
        lraw   (raw-combine lsubs)
        lweld  (mu/merge-vertices lraw 1e-4)
        ;; EXTRUDE path
        estate (turtle/extrude-from-path init circ p)
        esubs  (:meshes estate)
        emesh  (last esubs)]
    (println ">>> LOFT  sub-meshes:" (count lsubs)
             " | sub-face-counts:" (mapv #(count (:faces %)) lsubs))
    (println "     pre-weld  (raw concat):" (topo lraw))
    (println "     post-weld (merge 1e-4) :" (topo lweld) "  dup-faces:" (dup-faces lweld))
    (println "     nm spread post-weld    :" (nm-mid-spread lweld))
    (println ">>> EXTRUDE sub-meshes:" (count esubs)
             " | face-count:" (count (:faces emesh)))
    (println "     topo:" (topo emesh) "  dup-faces:" (dup-faces emesh))
    ;; locks: extrude = 1 continuous mesh & manifold; loft = many sub-meshes,
    ;; clean pre-weld but non-manifold post-weld (nm born at the weld).
    (is (= 1 (count esubs)) "extrude builds ONE sub-mesh")
    (is (zero? (:nm (topo emesh))) "extrude is manifold")
    (is (> (count lsubs) 1) "loft builds MANY sub-meshes")
    (is (zero? (:nm (topo lraw))) "loft pre-weld is manifold (clean sub-meshes)")
    (is (pos? (:nm (topo lweld))) "loft post-weld is non-manifold (weld stacks geometry)")))

(deftest part1-cap-isolation
  ;; Diagnostic: disable ONLY the separate make-cap-mesh (loft.cljs:812-815) by
  ;; dropping the :primitive :cap sub-meshes before welding, leaving do-build's
  ;; own :start/:end caps in place. PURELY in-test — production code untouched.
  (println "\n==== PART 1: isolate double-capping (drop separate :cap sub-meshes) ====")
  (let [p (rail "(path (f 20) (th 90) (f 20))")
        lsubs (:meshes (turtle/loft-from-path init circ id p 64))
        caps  (filter #(= :cap (:primitive %)) lsubs)
        full  (mu/merge-vertices (raw-combine lsubs) 1e-4)
        nocap (mu/merge-vertices (raw-combine (remove #(= :cap (:primitive %)) lsubs)) 1e-4)]
    (println ">>> separate :cap sub-meshes:" (count caps) " face-counts:" (mapv #(count (:faces %)) caps))
    (println ">>> FULL (baseline)       :" (topo full)  " dup-faces:" (dup-faces full)
             " incidence:" (:by-incidence (nm-mid-spread full)))
    (println ">>> NO separate caps      :" (topo nocap) " dup-faces:" (dup-faces nocap)
             " incidence:" (:by-incidence (nm-mid-spread nocap)))
    (println ">>> delta nm:" (- (:nm (topo full)) (:nm (topo nocap))))
    ;; locks separability of (a): dropping the double-cap removes ALL duplicate
    ;; faces and a clean nm quota, WITHOUT opening edges or zeroing the residual
    ;; corner-bridge component (b).
    (is (pos? (:dup-face-total (dup-faces full))) "double-capping yields duplicate faces")
    (is (zero? (:dup-face-total (dup-faces nocap))) "removing separate caps removes ALL dup faces")
    (is (< (:nm (topo nocap)) (:nm (topo full))) "double-cap removal drops nm (separable)")
    (is (zero? (:oe (topo nocap))) "do-build caps still close the ends (oe=0)")
    (is (pos? (:nm (topo nocap))) "residual corner-bridge component (b) remains")))

(deftest part2-revolve-corner
  (println "\n==== PART 2: revolve of a profile WITH hard corners ====")
  (let [diag (fn [code] (let [{:keys [result error]} (h/eval-dsl code)]
                          (if error {:err error}
                              (assoc (topo result) :dup (:dup-face-total (dup-faces result))))))
        rect (diag "(revolve (rect 10 20) 360)")
        notch (diag (str "(revolve (path-to-shape (path-2d (move-to [2 -10])"
                         " (f 20) (th 90) (f 10) (th 90) (f 8) (th -90) (f 6) (th 90) (f 12) (th 90) (f 16))) 360)"))
        arc (diag "(revolve (path-to-shape (path-2d (move-to [0 -8]) (arc-h 8 180))) 360)")
        cyl (diag "(revolve (shape (f 8) (th 90) (f 10) (th 90) (f 8)) 360)")]
    (println ">>> revolve rect 10x20 (4 hard 90° corners):" rect)
    (println ">>> revolve L/notch profile (concave corner):" notch)
    (println ">>> revolve arc-h semicircle (guardrail profile, smooth):" arc)
    (println ">>> revolve cylinder (shape (f 8)(th 90)(f 10)(th 90)(f 8)):" cyl)
    ;; revolve uses revolve-shape's continuous grid build, NOT loft's assembly:
    ;; hard AND concave profile corners stay watertight → guardrail is solid.
    (doseq [[label r] [["rect" rect] ["notch" notch] ["arc" arc] ["cyl" cyl]]]
      (is (true? (:wt r)) (str "revolve " label " must be watertight"))
      (is (zero? (:nm r)) (str "revolve " label " must be manifold; nm=" (:nm r)))
      (is (zero? (:dup r)) (str "revolve " label " has no duplicate faces")))))

(deftest origin-arc
  (println "\n==== ORIGIN: arc-h 20 90 (multi-corner), circle 10/32 ====")
  (let [p (rail "(path (arc-h 20 90))")
        lstate (turtle/loft-from-path init circ id p 64)
        lsubs (:meshes lstate)
        lweld (mu/merge-vertices (raw-combine lsubs) 1e-4)
        estate (turtle/extrude-from-path init circ p)
        emesh (last (:meshes estate))]
    (println ">>> LOFT  sub-meshes:" (count lsubs))
    (println "     post-weld:" (topo lweld) "  dup-faces:" (dup-faces lweld))
    (println "     nm spread:" (nm-mid-spread lweld))
    (println ">>> EXTRUDE sub-meshes:" (count (:meshes estate)) " topo:" (topo emesh)))
  (is true))
