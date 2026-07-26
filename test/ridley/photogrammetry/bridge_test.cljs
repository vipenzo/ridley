(ns ridley.photogrammetry.bridge-test
  "Round-trip verification for ridley.photogrammetry.bridge — the one
   mathematically risky piece of edit-acquire's edge-snap slice (P2, brief-
   param-acq-v1.md), isolated here so it's checkable headlessly, without the
   flaky browser environment (dev-docs/HANDOVER-edit-acquire-gate.md's
   'Limite ambientale'): editor->solver-pose followed by either inverse must
   return the pose that was held fixed, exactly (pure algebra, no LM)."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.test-helpers :refer [vec-approx=]]
            [ridley.math :as m]
            [ridley.photogrammetry.box-fit :as bf]
            [ridley.photogrammetry.bridge :as bridge]))

(def simple-proxy-pose
  {:position [10.0 5.0 -3.0] :heading [0.0 1.0 0.0] :up [0.0 0.0 1.0]})

(def simple-camera-pose
  {:position [50.0 5.0 -3.0] :heading [-1.0 0.0 0.0] :up [0.0 0.0 1.0]})

(def tilted-proxy-pose
  {:position [-4.0 12.0 8.0] :heading [1.0 0.0 0.0] :up [0.0 0.70710678 0.70710678]})

(def tilted-camera-pose
  {:position [5.0 -20.0 15.0] :heading [0.0 1.0 0.0] :up [0.0 0.0 1.0]})

(deftest box-basis-matches-mesh-convention
  (testing "at a fresh box's default creation-pose (heading=[1 0 0] up=[0 0 1]),
            the box's own local +x (its w/sx argument) lands at world +Y —
            up×heading, not heading×up. Getting this sign wrong silently
            mirrors every pose the solver computes (see namespace docstring)."
    (let [basis (bridge/box-basis {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})]
      (is (vec-approx= [0.0 1.0 0.0] (:ex basis)))
      (is (vec-approx= [0.0 0.0 1.0] (:ey basis)))
      (is (vec-approx= [1.0 0.0 0.0] (:ez basis))))))

(deftest round-trip-camera-simple
  (testing "editor->solver-pose then solver-pose->camera, proxy held fixed,
            recovers the original camera pose (photos 1..N-1's case)"
    (let [solver-pose (bridge/editor->solver-pose simple-camera-pose simple-proxy-pose)
          recovered (bridge/solver-pose->camera solver-pose simple-proxy-pose)]
      (is (vec-approx= (:position simple-camera-pose) (:position recovered) 1e-6))
      (is (vec-approx= (:heading simple-camera-pose) (:heading recovered) 1e-6))
      (is (vec-approx= (:up simple-camera-pose) (:up recovered) 1e-6)))))

(deftest round-trip-proxy-simple
  (testing "editor->solver-pose then solver-pose->proxy, camera held fixed,
            recovers the original proxy pose (photo 0's case)"
    (let [solver-pose (bridge/editor->solver-pose simple-camera-pose simple-proxy-pose)
          recovered (bridge/solver-pose->proxy solver-pose simple-camera-pose)]
      (is (vec-approx= (:position simple-proxy-pose) (:position recovered) 1e-6))
      (is (vec-approx= (:heading simple-proxy-pose) (:heading recovered) 1e-6))
      (is (vec-approx= (:up simple-proxy-pose) (:up recovered) 1e-6)))))

(deftest round-trip-camera-tilted
  (testing "same round trip with a tilted proxy up-vector, so the box-local
            frame isn't axis-aligned with the editor world"
    (let [solver-pose (bridge/editor->solver-pose tilted-camera-pose tilted-proxy-pose)
          recovered (bridge/solver-pose->camera solver-pose tilted-proxy-pose)]
      (is (vec-approx= (:position tilted-camera-pose) (:position recovered) 1e-5))
      (is (vec-approx= (:heading tilted-camera-pose) (:heading recovered) 1e-5))
      (is (vec-approx= (:up tilted-camera-pose) (:up recovered) 1e-5)))))

(deftest round-trip-proxy-tilted
  (testing "same round trip (proxy direction) with a tilted proxy up-vector"
    (let [solver-pose (bridge/editor->solver-pose tilted-camera-pose tilted-proxy-pose)
          recovered (bridge/solver-pose->proxy solver-pose tilted-camera-pose)]
      (is (vec-approx= (:position tilted-proxy-pose) (:position recovered) 1e-5))
      (is (vec-approx= (:heading tilted-proxy-pose) (:heading recovered) 1e-5))
      (is (vec-approx= (:up tilted-proxy-pose) (:up recovered) 1e-5)))))

(def non-orthogonal-camera-pose
  "Reproduces the exact bug found live 2026-07-22: edit_acquire.cljs's
   default-vantage-pose used heading=(normalize [1 -1 1]) and up=[0 0 1]
   VERBATIM — dot ≈ 0.577, nowhere near perpendicular. Harmless for THREE.js's
   own camera.lookAt (which orthogonalizes internally) but this bridge used
   to trust heading⊥up exactly, silently turning solver-pose->proxy's rigid
   transform into a shear (the box coming out non-rectangular)."
  {:position [50.0 5.0 -3.0]
   :heading (m/normalize [1.0 -1.0 1.0])
   :up [0.0 0.0 1.0]})

(deftest box-basis-orthonormal-despite-non-orthogonal-input
  (testing "box-basis Gram-Schmidts up against heading (m/orthogonalize-up)
            rather than trusting the pose's own up field — {:ex :ey :ez} must
            come out mutually orthonormal even when given a pose whose raw
            heading/up aren't (fed simple-proxy-pose's position/heading with
            non-orthogonal-camera-pose's bad up, worst case)"
    (let [{:keys [ex ey ez]}
          (bridge/box-basis {:position [0 0 0] :heading (:heading non-orthogonal-camera-pose)
                             :up (:up non-orthogonal-camera-pose)})]
      (is (< (Math/abs (m/dot ex ey)) 1e-6))
      (is (< (Math/abs (m/dot ey ez)) 1e-6))
      (is (< (Math/abs (m/dot ex ez)) 1e-6))
      (is (< (Math/abs (- 1.0 (m/magnitude ex))) 1e-6))
      (is (< (Math/abs (- 1.0 (m/magnitude ey))) 1e-6))
      (is (< (Math/abs (- 1.0 (m/magnitude ez))) 1e-6)))))

(deftest solver-pose->proxy-orthonormal-despite-non-orthogonal-camera
  (testing "solver-pose->proxy's output heading/up stay exactly perpendicular
            even when the FIXED camera pose it's given (photo 0's case) has
            heading/up merely close to perpendicular, like the real
            default-vantage-pose bug — a non-orthogonal result here is what
            corrupted the proxy mesh into a shear live"
    (let [solver-pose (bridge/editor->solver-pose non-orthogonal-camera-pose simple-proxy-pose)
          recovered (bridge/solver-pose->proxy solver-pose non-orthogonal-camera-pose)]
      (is (< (Math/abs (m/dot (:heading recovered) (:up recovered))) 1e-6))
      (is (< (Math/abs (- 1.0 (m/magnitude (:heading recovered)))) 1e-6))
      (is (< (Math/abs (- 1.0 (m/magnitude (:up recovered)))) 1e-6)))))

(deftest dims-from-mesh-recovers-extents
  (testing "dims-from-mesh reads the box's actual world-space corner vertices
            (built directly from box-basis, not via bridge internals) and
            recovers the [w h d] extents used to place them"
    (let [proxy-pose simple-proxy-pose
          {:keys [ex ey ez]} (bridge/box-basis proxy-pose)
          origin (:position proxy-pose)
          [w h d] [12.0 20.0 8.0]
          v+ (fn [[a b c] [d1 e f]] [(+ a d1) (+ b e) (+ c f)])
          v* (fn [[a b c] s] [(* a s) (* b s) (* c s)])
          corner (fn [sx sy sz]
                   (v+ origin (v+ (v* ex (* sx w 0.5))
                                  (v+ (v* ey (* sy h 0.5)) (v* ez (* sz d 0.5))))))
          verts (vec (for [sx [-1 1] sy [-1 1] sz [-1 1]] (corner sx sy sz)))
          mesh {:vertices verts}
          [w' h' d'] (bridge/dims-from-mesh mesh proxy-pose)]
      (is (< (Math/abs (- w w')) 1e-6))
      (is (< (Math/abs (- h h')) 1e-6))
      (is (< (Math/abs (- d d')) 1e-6)))))

(deftest world->local-is-exact-inverse-of-local->world
  (testing "world->local undoes local->world at a tilted proxy pose"
    (let [pt [7.0 -3.0 11.0]
          w (bridge/local->world tilted-proxy-pose pt)]
      (is (vec-approx= pt (bridge/world->local tilted-proxy-pose w) 1e-9)))))

;; --- pnp-target-points: source-agnostic PnP correspondence targets (fetta B) ---
;; The plate work leans on this: box corners and plate marks must present as the
;; SAME indexed shape, both with :obj in the object/solver frame, so the picking
;; gesture and the solver are identical whatever the proxy is.

(defn- box-mesh-at
  "A box mesh (no :anchors) with [w h d] extents at proxy-pose, built directly
   from box-basis — the same way the real box proxy's vertices are laid out."
  [proxy-pose [w h d]]
  (let [{:keys [ex ey ez]} (bridge/box-basis proxy-pose)
        origin (:position proxy-pose)
        v+ (fn [a b] (mapv + a b))
        v* (fn [a s] (mapv #(* % s) a))
        corner (fn [sx sy sz]
                 (v+ origin (v+ (v* ex (* sx w 0.5))
                                (v+ (v* ey (* sy h 0.5)) (v* ez (* sz d 0.5))))))]
    {:creation-pose proxy-pose
     :vertices (vec (for [sx [-1 1] sy [-1 1] sz [-1 1]] (corner sx sy sz)))}))

(deftest pnp-targets-box-mode-matches-box-corners
  (testing "a proxy with no :anchors yields the 8 box corners in the object
            frame, indexed 0-7 — identical to what box-fit/corners feeds the
            solver, and :world = local->world of :obj"
    (let [dims [12.0 20.0 8.0]
          mesh (box-mesh-at simple-proxy-pose dims)
          targets (bridge/pnp-target-points mesh simple-camera-pose)]
      (is (= 8 (count targets)))
      (is (= (range 8) (map :id targets)))
      (doseq [[t c] (map vector targets (bf/corners dims))]
        (is (vec-approx= c (:obj t) 1e-6))
        (is (vec-approx= (bridge/local->world simple-proxy-pose c) (:world t) 1e-6))))))

(defn- mark [world nrm] {:position world :heading nrm :up [1.0 0.0 0.0]})

(deftest pnp-targets-marks-mode-uses-anchors-object-frame
  (testing "a proxy carrying :anchors yields one target per mark (sorted by id),
            :obj recovering the mark's object-frame coords — and pose-invariant:
            the same coords come back at a different proxy pose (the property
            that keeps a re-posed plate clicking the right point)"
    (let [mesh {:creation-pose simple-proxy-pose
                :vertices [[0 0 0]]
                :anchors {:m1 (mark (bridge/local->world simple-proxy-pose [58.0 0.0 1.5]) [0 0 1])
                          :m0 (mark (bridge/local->world simple-proxy-pose [0.0 58.0 1.5]) [0 0 1])}}
          targets (bridge/pnp-target-points mesh simple-camera-pose)]
      (is (= [:m0 :m1] (map :id targets)) "sorted by mark id")
      (is (vec-approx= [0.0 58.0 1.5] (:obj (first targets)) 1e-6))
      (is (vec-approx= [58.0 0.0 1.5] (:obj (second targets)) 1e-6))
      (testing "same object coords recovered at a tilted proxy pose"
        (let [m2 {:creation-pose tilted-proxy-pose
                  :vertices [[0 0 0]]
                  :anchors {:m0 (mark (bridge/local->world tilted-proxy-pose [0.0 58.0 1.5]) [0 0 1])}}]
          (is (vec-approx= [0.0 58.0 1.5]
                           (:obj (first (bridge/pnp-target-points m2 tilted-camera-pose))) 1e-6)))))))

(deftest pnp-targets-mark-visibility-is-front-facing
  (testing "a mark whose world normal points toward the camera is visible; away is not"
    (let [cp {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}
          cam {:position [0.0 0.0 100.0] :heading [0 0 -1] :up [0 1 0]}
          mesh-with (fn [nrm] {:creation-pose cp :vertices [[0 0 0]]
                               :anchors {:m {:position [0.0 0.0 0.0] :heading nrm :up [1 0 0]}}})
          vis? (fn [nrm] (:visible? (first (bridge/pnp-target-points (mesh-with nrm) cam))))]
      (is (true? (vis? [0.0 0.0 1.0])) "normal toward the camera")
      (is (false? (vis? [0.0 0.0 -1.0])) "normal away from the camera"))))
