(ns ridley.turtle.arc-profile-test
  "Guardrail tests for the historically-fragile zone the :smooth discovery
   flagged as a coverage hole: an ARC used inside a 2D PROFILE (not as a rail).
   arc-h/arc-v emit untagged th/tv corners; these tests pin the CURRENT
   behaviour as a baseline, so a future Class-A fix (tagging arcs :smooth)
   that touched profile construction would be caught.

   Both must pass on the current codebase (documented baseline)."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.editor.sci-harness :as h]
            [ridley.geometry.mesh-utils :as mu]))

(defn- diag [code]
  (let [{:keys [result error]} (h/eval-dsl code)]
    {:error error
     :diag (when (and (nil? error) (map? result) (seq (:vertices result)))
             (mu/mesh-diagnose result))
     :faces (when (map? result) (count (:faces result)))
     :verts (when (map? result) (count (:vertices result)))}))

;; ── Guardrail 1: arc inside a 2D extrude profile ──────────────
;; A rounded-rectangle (stadium) outline traced with four arc-h corners,
;; used as the extrude PROFILE (path-to-shape), then extruded straight.
;; The arc corners are untagged th's *inside the profile contour* — the case
;; path-to-2d-waypoints integrates positionally (ignores :smooth), so it must
;; already be watertight today.
(deftest arc-profile-extrude-is-watertight
  (testing "arc-h corners in a path-2d profile extrude to a watertight solid"
    (let [{:keys [error diag faces]}
          (diag (str "(extrude (path-to-shape "
                     "  (path-2d (move-to [-20 -8])"
                     "    (f 40) (arc-h 8 90) (f 16) (arc-h 8 90)"
                     "    (f 40) (arc-h 8 90) (f 16) (arc-h 8 90)))"
                     "  (f 14))"))]
      (is (nil? error) (str "should not error: " error))
      (is (some? diag) "should produce a mesh")
      (is (pos? faces) "mesh has faces")
      (is (zero? (:non-manifold-edges diag))
          (str "arc-profile extrude must have no non-manifold edges, got "
               (:non-manifold-edges diag)))
      (is (:is-watertight? diag) "arc-profile extrude must be watertight"))))

;; ── Guardrail 2: revolve from an arc profile (portaforbici-like) ──
;; A semicircular profile (single arc-h 180 in a path-2d), revolved 360°.
;; This is the arc-in-revolve-profile case cited in memory / AI prompts.
(deftest arc-profile-revolve-is-watertight
  (testing "an arc-h semicircle profile revolves 360 into a watertight solid"
    (let [{:keys [error diag faces]}
          (diag (str "(revolve (path-to-shape "
                     "  (path-2d (move-to [0 -8]) (arc-h 8 180))) 360)"))]
      (is (nil? error) (str "should not error: " error))
      (is (some? diag) "should produce a mesh")
      (is (pos? faces) "mesh has faces")
      (is (zero? (:non-manifold-edges diag))
          (str "arc-profile revolve must have no non-manifold edges, got "
               (:non-manifold-edges diag)))
      (is (:is-watertight? diag) "arc-profile revolve must be watertight"))))

;; ── Guardrail 3: revolve of a HARD-corner profile (revolve is solid) ──
;; The loft non-manifold defect (see loft_nm_isolation_test) does NOT reach
;; revolve: revolve uses its own continuous grid builder (revolve-shape), so a
;; profile with hard 90° corners revolves watertight. This locks that the smooth
;; arc guardrail above is solid, not green by a lucky parameter combination.
(deftest hard-corner-profile-revolve-is-watertight
  (testing "a rectangular (4 hard 90° corners) profile revolves into a watertight solid"
    (let [{:keys [error diag faces]} (diag "(revolve (rect 10 20) 360)")]
      (is (nil? error) (str "should not error: " error))
      (is (pos? faces) "mesh has faces")
      (is (zero? (:non-manifold-edges diag))
          (str "hard-corner revolve must have no non-manifold edges, got "
               (:non-manifold-edges diag)))
      (is (:is-watertight? diag) "hard-corner revolve must be watertight"))))
