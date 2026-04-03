(ns ridley.jvm.eval-test
  "Test suite for the JVM DSL eval engine.
   Tests cover: extrude, loft, bloft, revolve, SDF tree construction,
   path recording, attach, and register."
  (:require [clojure.test :refer [deftest testing is are]]
            [ridley.jvm.eval :as eval]))

;; ── Helpers ─────────────────────────────────────────────────────

(defn- run
  "Evaluate a DSL script and return the registered meshes map."
  [script]
  (:meshes (eval/eval-script script)))

(defn- mesh
  "Evaluate script, return the single registered mesh by name symbol."
  [script name-sym]
  (get (run script) name-sym))

(defn- verts
  "Return vertex count of a registered mesh."
  [script name-sym]
  (count (:vertices (mesh script name-sym))))

(defn- faces
  "Return face count of a registered mesh."
  [script name-sym]
  (count (:faces (mesh script name-sym))))

(defn- has-mesh?
  "True if the script produces a mesh with > 0 vertices."
  [script name-sym]
  (pos? (verts script name-sym)))

(defn- output
  "Return captured print output from script."
  [script]
  (:print-output (eval/eval-script script)))

;; ============================================================
;; Extrude
;; ============================================================

(deftest extrude-basic
  (testing "extrude circle along forward"
    (is (has-mesh? "(register T (extrude (circle 10 8) (f 20)))" 'T))
    (is (= 16 (verts "(register T (extrude (circle 10 8) (f 20)))" 'T)))))

(deftest extrude-with-turns
  (testing "extrude with th produces geometry"
    (is (has-mesh? "(register T (extrude (circle 5 6) (f 10) (th 45) (f 10)))" 'T))))

(deftest extrude-closed
  (testing "extrude-closed produces a closed mesh (no caps)"
    (let [m (mesh "(register T (extrude-closed (circle 5 8) (f 30) (th 90) (f 30) (th 90) (f 30) (th 90) (f 30) (th 90)))" 'T)]
      (is (some? m))
      (is (pos? (count (:vertices m))))
      (is (pos? (count (:faces m)))))))

(deftest extrude-rect
  (testing "extrude with rect shape"
    (is (has-mesh? "(register T (extrude (rect 10 5) (f 15)))" 'T))))

(deftest extrude-with-arc
  (testing "extrude along arc path"
    (is (has-mesh? "(register T (extrude (circle 3 6) (arc-h 20 90)))" 'T))))

(deftest extrude-preserves-creation-pose
  (testing "extruded mesh has creation-pose"
    (let [m (mesh "(f 10) (th 45) (register T (extrude (circle 5 8) (f 20)))" 'T)]
      (is (some? (:creation-pose m)))
      (is (vector? (:position (:creation-pose m)))))))

;; ============================================================
;; Loft
;; ============================================================

(deftest loft-shape-fn
  (testing "loft with tapered shape-fn"
    (is (has-mesh? "(register T (loft (tapered (circle 10 8) :to 0) (f 30)))" 'T)))
  (testing "loft with twisted shape-fn"
    (is (has-mesh? "(register T (loft (twisted (circle 10 8) :rate 2) (f 30)))" 'T))))

(deftest loft-two-shapes
  (testing "loft morphing circle to rect"
    (let [v (verts "(register T (loft (circle 10 8) (rect 10 10) (f 30)))" 'T)]
      (is (pos? v)))))

(deftest loft-transform-fn
  (testing "loft with lambda transform"
    (is (has-mesh? "(register T (loft (circle 10 8) #(scale %1 (- 1 %2)) (f 30)))" 'T))))

(deftest loft-multi-segment
  (testing "loft along path with turns"
    (is (has-mesh? "(register T (loft (tapered (circle 10 8) :to 0.5) (f 20) (th 45) (f 20)))" 'T))))

;; ============================================================
;; Loft-n (custom step count)
;; ============================================================

(deftest loft-n-shape-fn
  (testing "loft-n produces more verts with more steps"
    (let [v16 (verts "(register T (loft (tapered (circle 10 8) :to 0) (f 30)))" 'T)
          v32 (verts "(register T (loft-n 32 (tapered (circle 10 8) :to 0) (f 30)))" 'T)]
      (is (> v32 v16) "32 steps should produce more vertices than default 16"))))

(deftest loft-n-two-shapes
  (testing "loft-n with two shapes and custom steps"
    (let [v8  (verts "(register T (loft-n 8 (circle 10 8) (rect 10 10) (f 30)))" 'T)
          v32 (verts "(register T (loft-n 32 (circle 10 8) (rect 10 10) (f 30)))" 'T)]
      (is (> v32 v8)))))

;; ============================================================
;; Bloft (bezier-safe loft)
;; ============================================================

(deftest bloft-shape-fn
  (testing "bloft with shape-fn"
    (is (has-mesh? "(register T (bloft (tapered (circle 10 8) :to 0.5) (f 30)))" 'T))))

(deftest bloft-two-shapes
  (testing "bloft morphing between shapes"
    (is (has-mesh? "(register T (bloft (circle 10 8) (rect 10 10) (f 30)))" 'T))))

(deftest bloft-n
  (testing "bloft-n with custom step count"
    (is (has-mesh? "(register T (bloft-n 64 (tapered (circle 10 8) :to 0.5) (f 30)))" 'T))))

(deftest bloft-with-transform-fn
  (testing "bloft with legacy transform-fn"
    (is (has-mesh? "(register T (bloft (circle 10 8) #(scale %1 (- 1 (* 0.5 %2))) (f 30)))" 'T))))

;; ============================================================
;; Revolve
;; ============================================================

(deftest revolve-full
  (testing "revolve circle 360° (torus)"
    (let [m (mesh "(register T (revolve (circle 3 8)))" 'T)]
      (is (some? m))
      (is (pos? (count (:vertices m)))))))

(deftest revolve-partial
  (testing "revolve rect 180° (half solid)"
    (is (has-mesh? "(register T (revolve (rect 5 10) 180))" 'T))))

(deftest revolve-shape-fn
  (testing "revolve with tapered shape-fn"
    (is (has-mesh? "(register T (revolve (tapered (circle 5 8) :to 2)))" 'T))))

(deftest revolve-profile
  (testing "revolve a drawn profile using shape macro"
    (is (has-mesh?
          "(register T (revolve (shape (f 8) (th 90) (f 10) (th 90) (f 8))))"
          'T))))

;; ============================================================
;; SDF tree construction (pure data, no Rust server needed)
;; ============================================================

(deftest sdf-primitives
  (testing "SDF primitives produce correct tree nodes"
    (let [r (eval/eval-script "
              (def s (sdf-sphere 5))
              (def b (sdf-box 10 10 10))
              (def c (sdf-cyl 3 8))
              (println (:op s) (:op b) (:op c))")]
      (is (= "sphere box cyl\n" (:print-output r))))))

(deftest sdf-booleans
  (testing "SDF boolean ops build nested trees"
    (let [r (eval/eval-script "
              (def u (sdf-union (sdf-sphere 5) (sdf-box 4 4 4)))
              (def d (sdf-difference (sdf-sphere 5) (sdf-cyl 2 10)))
              (def i (sdf-intersection (sdf-sphere 5) (sdf-box 3 3 3)))
              (println (:op u) (:op d) (:op i))")]
      (is (= "union difference intersection\n" (:print-output r))))))

(deftest sdf-blend
  (testing "SDF blend produces blend node"
    (let [r (eval/eval-script "
              (def b (sdf-blend (sdf-sphere 5) (sdf-box 4 4 4) 2.0))
              (println (:op b) (:k b))")]
      (is (= "blend 2.0\n" (:print-output r))))))

(deftest sdf-shell-offset
  (testing "SDF shell and offset"
    (let [r (eval/eval-script "
              (def s (sdf-shell (sdf-sphere 10) 1.0))
              (def o (sdf-offset (sdf-sphere 10) 0.5))
              (println (:op s) (:thickness s) (:op o) (:amount o))")]
      (is (= "shell 1.0 offset 0.5\n" (:print-output r))))))

(deftest sdf-morph
  (testing "SDF morph between two SDFs"
    (let [r (eval/eval-script "
              (def m (sdf-morph (sdf-sphere 5) (sdf-box 5 5 5) 0.5))
              (println (:op m) (:t m))")]
      (is (= "morph 0.5\n" (:print-output r))))))

(deftest sdf-move
  (testing "SDF move translates node"
    (let [r (eval/eval-script "
              (def m (sdf-move (sdf-sphere 5) 10 20 30))
              (println (:op m) (:dx m) (:dy m) (:dz m))")]
      (is (= "move 10.0 20.0 30.0\n" (:print-output r))))))

(deftest sdf-complex-tree
  (testing "Complex SDF tree with multiple operations"
    (let [r (eval/eval-script "
              (def tree (sdf-difference
                          (sdf-blend (sdf-sphere 10) (sdf-box 8 8 8) 1.5)
                          (sdf-union
                            (sdf-cyl 3 20)
                            (sdf-move (sdf-cyl 3 20) 0 0 0))))
              (println (:op tree))
              (println (:op (:a tree)))
              (println (:op (:b tree)))")]
      (is (= "difference\nblend\nunion\n" (:print-output r))))))

(deftest sdf-attach-moves
  (testing "SDF attach captures displacement as sdf-move"
    (let [r (eval/eval-script "
              (def s (sdf-sphere 5))
              (def moved (attach s (f 10) (u 5)))
              (println (:op moved))
              (println (:dx moved) (:dz moved))")]
      ;; f moves along heading (x), u moves along up (z)
      (is (= "move\n10.0 5.0\n" (:print-output r))))))

;; ============================================================
;; Shape macro (2D turtle recorder)
;; ============================================================

(deftest shape-basic
  (testing "shape macro creates a 2D shape"
    (let [r (eval/eval-script "
              (def s (shape (f 10) (th 90) (f 10) (th 90) (f 10) (th 90) (f 10)))
              (println (shape? s))
              (println (count (:points s)))")]
      (is (= "true\n4\n" (:print-output r))))))

(deftest shape-in-revolve
  (testing "shape macro works inside revolve"
    (is (has-mesh?
          "(register T (revolve (shape (f 8) (th 90) (f 10) (th 90) (f 8))))"
          'T))))

(deftest shape-in-extrude
  (testing "shape macro works inside extrude"
    (is (has-mesh?
          "(register T (extrude (shape (f 5) (th 90) (f 10) (th 90) (f 5)) (f 20)))"
          'T))))

(deftest shape-rejects-tv
  (testing "shape macro rejects tv (2D only)"
    (is (thrown? Exception
          (eval/eval-script "(shape (f 5) (tv 45) (f 5))")))))

;; ============================================================
;; Attach-face / Clone-face
;; ============================================================

(deftest attach-face-basic
  (testing "attach-face moves face vertices"
    (let [r (eval/eval-script "
              (def b (box 10 10 10))
              (def modified (attach-face b :top (f 5)))
              (println (some? (:vertices modified)))
              (println (= (count (:vertices b)) (count (:vertices modified))))")]
      (is (= "true\ntrue\n" (:print-output r))))))

(deftest clone-face-extrudes
  (testing "clone-face creates new vertices (extrusion)"
    (let [r (eval/eval-script "
              (def b (box 10 10 10))
              (def extruded (clone-face b :top (f 10)))
              (println (> (count (:vertices extruded)) (count (:vertices b))))")]
      (is (= "true\n" (:print-output r))))))

(deftest attach-face-with-inset
  (testing "attach-face with inset modifies mesh"
    (is (some?
          (mesh "(register T (attach-face (box 20 20 20) :top (inset 3) (f 5)))" 'T)))))

;; ============================================================
;; Turtle scoping macro
;; ============================================================

(deftest turtle-scope-isolates-state
  (testing "turtle macro isolates state changes"
    (let [r (eval/eval-script "
              (f 10)
              (def pos-before (turtle-position))
              (turtle (f 100) (th 90))
              (def pos-after (turtle-position))
              (println (= pos-before pos-after))")]
      (is (= "true\n" (:print-output r))))))

(deftest turtle-scope-inherits-position
  (testing "turtle scope inherits parent position"
    (let [r (eval/eval-script "
              (f 20)
              (turtle
                (println (turtle-position)))")]
      ;; Should print the position after f 20, not origin
      (is (re-find #"20" (:print-output r))))))

(deftest turtle-scope-reset
  (testing "turtle :reset starts from origin"
    (let [r (eval/eval-script "
              (f 100)
              (turtle :reset
                (println (turtle-position)))")]
      (is (re-find #"\[0" (:print-output r))))))

(deftest turtle-scope-nested
  (testing "nested turtle scopes work correctly"
    (let [r (eval/eval-script "
              (f 10)
              (turtle
                (f 20)
                (turtle
                  (f 30)
                  (println (first (turtle-position)))))")]
      ;; 10 + 20 + 30 = 60
      (is (re-find #"60" (:print-output r))))))

(deftest turtle-scope-with-register
  (testing "register inside turtle scope works"
    (is (has-mesh? "
      (turtle
        (f 10)
        (register T (extrude (circle 5 8) (f 20))))" 'T))))

;; ============================================================
;; Path recording
;; ============================================================

(deftest path-basic
  (testing "path macro records commands"
    (let [r (eval/eval-script "
              (def p (path (f 10) (th 90) (f 20)))
              (println (count (:commands p)))
              (println (:type p))")]
      (is (= "3\n:path\n" (:print-output r))))))

(deftest path-with-arc
  (testing "path records arcs as decomposed th+f steps"
    (let [r (eval/eval-script "
              (def p (path (arc-h 20 90)))
              (println (pos? (count (:commands p))))")]
      (is (= "true\n" (:print-output r))))))

(deftest path-as-value
  (testing "pre-built path can be passed to extrude"
    (is (has-mesh? "
      (def p (path (f 30)))
      (register T (extrude (circle 10 8) p))" 'T))))

(deftest path-follow
  (testing "follow splices path inside path"
    (let [r (eval/eval-script "
              (def p1 (path (f 10)))
              (def p2 (path (follow p1) (th 90) (f 10)))
              (println (count (:commands p2)))")]
      ;; follow splices p1's commands + th + f = 3
      (is (= "3\n" (:print-output r))))))

;; ============================================================
;; Attach
;; ============================================================

(deftest attach-mesh-translation
  (testing "attach translates mesh"
    (let [r (eval/eval-script "
              (def b (box 10 10 10))
              (def moved (attach b (f 50)))
              (println (some? (:vertices moved)))")]
      (is (= "true\n" (:print-output r))))))

(deftest attach-mesh-rotation
  (testing "attach rotates mesh"
    (is (has-mesh? "
      (def b (box 10 10 10))
      (register T (attach b (th 45) (tv 30)))" 'T))))

(deftest attach-preserves-vertex-count
  (testing "attach doesn't change vertex count"
    (let [r (eval/eval-script "
              (def b (box 10 10 10))
              (def moved (attach b (f 20) (th 45)))
              (println (= (count (:vertices b)) (count (:vertices moved))))")]
      (is (= "true\n" (:print-output r))))))

;; ============================================================
;; Register
;; ============================================================

(deftest register-basic
  (testing "register stores mesh in registry"
    (let [meshes (run "(register Foo (box 10 10 10))")]
      (is (contains? meshes 'Foo))
      (is (pos? (count (:vertices (get meshes 'Foo))))))))

(deftest register-multiple
  (testing "multiple registers all stored"
    (let [meshes (run "
            (register A (box 10 10 10))
            (register B (sphere 5))
            (register C (cyl 3 8))")]
      (is (= 3 (count meshes)))
      (is (every? #(pos? (count (:vertices (val %)))) meshes)))))

(deftest register-creates-def
  (testing "register creates a def so mesh is reusable"
    (is (has-mesh? "
      (register Base (box 10 10 10))
      (register Moved (attach Base (f 30)))" 'Moved))))

;; ============================================================
;; Turtle state isolation
;; ============================================================

(deftest turtle-state-resets-between-evals
  (testing "turtle state resets between eval-script calls"
    (eval/eval-script "(f 100) (th 90)")
    (let [r (eval/eval-script "(println (turtle-position))")]
      (is (re-find #"\[0" (:print-output r))
          "position should be at origin after reset"))))

;; ============================================================
;; Shape-fn composition
;; ============================================================

(deftest shape-fn-tapered-twisted
  (testing "composed shape-fns work in loft"
    (is (has-mesh? "
      (register T (loft (-> (circle 10 8) (tapered :to 0.5) (twisted :rate 3))
                        (f 30)))" 'T))))

(deftest shape-fn-morphed
  (testing "morphed shape-fn (circle → rect)"
    (is (has-mesh? "
      (register T (loft (morphed (circle 10 8) (rect 10 10))
                        (f 30)))" 'T))))

;; ============================================================
;; Geometry correctness
;; ============================================================

(deftest box-dimensions
  (testing "box has 8 vertices and 12 faces"
    (let [m (mesh "(register T (box 10 10 10))" 'T)]
      (is (= 8 (count (:vertices m))))
      (is (= 12 (count (:faces m)))))))

(deftest sphere-vertex-count
  (testing "sphere vertex count scales with segments"
    (let [v16 (verts "(register T (sphere 5 16 12))" 'T)
          v8  (verts "(register T (sphere 5 8 6))" 'T)]
      (is (> v16 v8)))))

(deftest extrude-vertex-formula
  (testing "extrude of n-gon along straight line has 2n vertices"
    (are [n] (= (* 2 n) (verts (str "(register T (extrude (circle 10 " n ") (f 20)))") 'T))
      4 6 8 16 32)))

(deftest revolve-closed-topology
  (testing "full 360° revolve produces watertight mesh"
    (let [m (mesh "(register T (revolve (rect 3 8)))" 'T)
          ;; For a watertight mesh: every edge shared by exactly 2 faces
          edges (into {}
                  (map (fn [[e c]] [e c])
                    (frequencies
                      (mapcat (fn [face]
                                (let [n (count face)]
                                  (for [i (range n)]
                                    (let [a (nth face i)
                                          b (nth face (mod (inc i) n))]
                                      (if (< a b) [a b] [b a])))))
                              (:faces m)))))]
      ;; All edges should be shared by exactly 2 faces
      (is (every? #(= 2 (val %)) edges)
          "every edge should be shared by exactly 2 faces"))))

;; ============================================================
;; Print capture
;; ============================================================

(deftest println-captured
  (testing "println output captured"
    (is (= "hello world\n" (output "(println \"hello world\")")))))

(deftest bench-captures-output
  (testing "bench prints timing and returns value"
    (let [r (eval/eval-script "(register T (bench \"test\" #(box 10 10 10)))")]
      (is (has-mesh? "(register T (bench \"test\" #(box 10 10 10)))" 'T))
      (is (re-find #"test:" (:print-output r))))))

;; ============================================================
;; Math bindings
;; ============================================================

(deftest math-bindings
  (testing "math functions available"
    (let [r (eval/eval-script "
              (println (round (* 1000 (sin (/ PI 6)))))
              (println (round (* 1000 (cos (/ PI 3)))))
              (println (round (sqrt 144)))")]
      (is (= "500\n500\n12\n" (:print-output r))))))

;; ============================================================
;; 2D shapes
;; ============================================================

(deftest shape-constructors
  (testing "2D shape constructors produce valid shapes"
    (let [r (eval/eval-script "
              (println (shape? (circle 10 8)))
              (println (shape? (rect 10 5)))
              (println (shape? (poly 0 0 10 0 10 10 0 10)))
              (println (shape? (star 10 5 5)))")]
      (is (= "true\ntrue\ntrue\ntrue\n" (:print-output r))))))

;; ============================================================
;; Edge cases
;; ============================================================

(deftest extrude-zero-distance
  (testing "extrude with (f 0) doesn't crash"
    ;; Should return nil or a degenerate mesh, not throw
    (is (some? (eval/eval-script "(register T (extrude (circle 10 8) (f 0)))")))))

(deftest empty-script
  (testing "empty script returns empty meshes"
    (let [r (eval/eval-script "")]
      (is (empty? (:meshes r))))))

(deftest script-with-error
  (testing "script with error throws"
    (is (thrown? Exception
          (eval/eval-script "(undefined-fn 42)")))))

;; ============================================================
;; Misc bindings (solidify, concat, extrude-z/y, helpers)
;; ============================================================

(deftest quick-path-test
  (testing "quick-path creates a path from compact notation"
    (let [r (eval/eval-script "
              (def p (quick-path 30 90 20))
              (println (path? p))
              (println (count (:commands p)))")]
      (is (= "true\n3\n" (:print-output r))))))

(deftest extrude-z-test
  (testing "extrude-z is available as a legacy binding"
    ;; extrude-z/y are legacy ops that take turtle geometry, not shapes
    (let [r (eval/eval-script "(println (fn? extrude-z))")]
      (is (= "true\n" (:print-output r))))))

(deftest extrude-y-test
  (testing "extrude-y is available as a legacy binding"
    (let [r (eval/eval-script "(println (fn? extrude-y))")]
      (is (= "true\n" (:print-output r))))))

(deftest set-creation-pose-test
  (testing "set-creation-pose stamps current turtle pose onto mesh"
    (let [r (eval/eval-script "
              (def b (box 10 10 10))
              (f 50) (th 90)
              (def b2 (set-creation-pose b))
              (println (= (:creation-pose b2) (:creation-pose b)))")]
      ;; b was created at origin, then we moved; set-creation-pose uses new position
      (is (= "false\n" (:print-output r))))))

(deftest last-mesh-test
  (testing "last-mesh returns nil for pure extrude (no turtle state mutation)"
    ;; Pure extrude doesn't add to turtle state meshes — this is expected
    (let [r (eval/eval-script "
              (extrude (circle 5 8) (f 10))
              (println (nil? (last-mesh)))")]
      (is (= "true\n" (:print-output r))))))

(deftest get-turtle-resolution-test
  (testing "resolution getter works"
    (let [r (eval/eval-script "
              (resolution :n 32)
              (println (get-turtle-resolution))")]
      (is (= "32\n" (:print-output r))))))

(deftest concat-meshes-test
  (testing "concat-meshes merges meshes without boolean"
    (let [r (eval/eval-script "
              (def a (box 10 10 10))
              (def b (attach (box 5 5 5) (f 20)))
              (register T (concat-meshes [a b]))")]
      (is (has-mesh? "(def a (box 10 10 10))
                       (def b (attach (box 5 5 5) (f 20)))
                       (register T (concat-meshes [a b]))" 'T))
      ;; concat should have 8+8=16 vertices
      (is (= 16 (verts "(def a (box 10 10 10))
                         (def b (attach (box 5 5 5) (f 20)))
                         (register T (concat-meshes [a b]))" 'T))))))

(deftest solidify-test
  (testing "solidify self-unions a mesh"
    (is (has-mesh? "(register T (solidify (box 10 10 10)))" 'T))))

(deftest pen-macro-test
  (testing "pen macro changes pen mode"
    (let [r (eval/eval-script "
              (pen :off)
              (f 10)
              (pen :on)
              (f 10)
              (println :ok)")]
      (is (= ":ok\n" (:print-output r))))))

(deftest find-sharp-edges-test
  (testing "find-sharp-edges on a box"
    (let [r (eval/eval-script "
              (def b (box 10 10 10))
              (def edges (find-sharp-edges b))
              (println (pos? (count edges)))")]
      (is (= "true\n" (:print-output r))))))

(deftest mesh-status-test
  (testing "mesh-status returns status map"
    (let [r (eval/eval-script "
              (def b (box 10 10 10))
              (def s (mesh-status b))
              (println (:manifold? s))")]
      (is (= "true\n" (:print-output r))))))

(deftest face-ops-integration
  (testing "list-faces + face-info on box"
    (let [r (eval/eval-script "
              (def b (box 10 10 10))
              (println (count (face-ids b)))
              (println (some? (face-info b :top)))")]
      (is (= "6\ntrue\n" (:print-output r))))))

;; ============================================================
;; Warp — spatial mesh deformation
;; ============================================================

(deftest warp-inflate
  (testing "inflate increases vertex distance from center"
    (let [r (eval/eval-script "
              (def b (box 10 10 10))
              (def vol (sphere 20))
              (def warped (warp b vol (inflate 5)))
              ;; Warped vertices should be farther from origin than original
              (let [orig-max (apply max (map (fn [[x y z]] (+ (* x x) (* y y) (* z z))) (:vertices b)))
                    warp-max (apply max (map (fn [[x y z]] (+ (* x x) (* y y) (* z z))) (:vertices warped)))]
                (println (> warp-max orig-max)))")]
      (is (= "true\n" (:print-output r))))))

(deftest warp-dent
  (testing "dent decreases vertex distance from center"
    (let [r (eval/eval-script "
              (def b (box 10 10 10))
              (def vol (sphere 20))
              (def warped (warp b vol (dent 3)))
              (let [orig-max (apply max (map (fn [[x y z]] (+ (* x x) (* y y) (* z z))) (:vertices b)))
                    warp-max (apply max (map (fn [[x y z]] (+ (* x x) (* y y) (* z z))) (:vertices warped)))]
                (println (< warp-max orig-max)))")]
      (is (= "true\n" (:print-output r))))))

(deftest warp-attract
  (testing "attract pulls vertices toward center"
    (let [r (eval/eval-script "
              (def b (box 10 10 10))
              (def vol (sphere 20))
              (def warped (warp b vol (attract 0.5)))
              (let [orig-max (apply max (map (fn [[x y z]] (+ (* x x) (* y y) (* z z))) (:vertices b)))
                    warp-max (apply max (map (fn [[x y z]] (+ (* x x) (* y y) (* z z))) (:vertices warped)))]
                (println (< warp-max orig-max)))")]
      (is (= "true\n" (:print-output r))))))

(deftest warp-twist
  (testing "twist rotates vertices around axis"
    (let [r (eval/eval-script "
              (def b (extrude (rect 5 5) (f 30)))
              (def vol (attach (cyl 20 40) (f 15)))
              (def warped (warp b vol (twist 90)))
              ;; Vertex count should stay the same
              (println (= (count (:vertices b)) (count (:vertices warped))))
              ;; But positions should differ
              (println (not= (:vertices b) (:vertices warped)))")]
      (is (= "true\ntrue\n" (:print-output r))))))

(deftest warp-squash
  (testing "squash flattens along an axis"
    (let [r (eval/eval-script "
              (def b (sphere 10 8 6))
              (def vol (sphere 20))
              (def warped (warp b vol (squash :z)))
              ;; Z range should be smaller after squash
              (let [zs-orig (map #(nth % 2) (:vertices b))
                    zs-warp (map #(nth % 2) (:vertices warped))
                    range-orig (- (apply max zs-orig) (apply min zs-orig))
                    range-warp (- (apply max zs-warp) (apply min zs-warp))]
                (println (< range-warp range-orig)))")]
      (is (= "true\n" (:print-output r))))))

(deftest warp-roughen
  (testing "roughen displaces vertices along normals"
    (let [r (eval/eval-script "
              (def b (sphere 10 16 12))
              (def vol (sphere 20))
              (def warped (warp b vol (roughen 2 5)))
              (println (= (count (:vertices b)) (count (:vertices warped))))
              (println (not= (:vertices b) (:vertices warped)))")]
      (is (= "true\ntrue\n" (:print-output r))))))

(deftest warp-preserves-vertex-count
  (testing "warp doesn't change vertex count (without subdivide)"
    (let [r (eval/eval-script "
              (def b (box 10 10 10))
              (def vol (sphere 20))
              (def warped (warp b vol (inflate 3)))
              (println (= (count (:vertices b)) (count (:vertices warped))))
              (println (= (count (:faces b)) (count (:faces warped))))")]
      (is (= "true\ntrue\n" (:print-output r))))))

(deftest warp-with-subdivide
  (testing "warp with :subdivide increases vertex count"
    (let [r (eval/eval-script "
              (def b (box 10 10 10))
              (def vol (sphere 20))
              (def warped (warp b vol :subdivide 2 (inflate 3)))
              (println (> (count (:vertices warped)) (count (:vertices b))))")]
      (is (= "true\n" (:print-output r))))))

(deftest warp-multiple-deformations
  (testing "warp with multiple deform functions"
    (let [r (eval/eval-script "
              (def b (sphere 10 8 6))
              (def vol (sphere 20))
              (def warped (warp b vol (inflate 2) (roughen 1 3)))
              (println (= (count (:vertices b)) (count (:vertices warped))))
              (println (not= (:vertices b) (:vertices warped)))")]
      (is (= "true\ntrue\n" (:print-output r))))))

(deftest warp-outside-volume-unchanged
  (testing "vertices outside volume are unchanged"
    (let [r (eval/eval-script "
              (def b (attach (box 10 10 10) (f 100)))
              (def vol (sphere 5))  ;; small sphere at origin
              (def warped (warp b vol (inflate 50)))
              ;; Box is at f=100, sphere radius=5 at origin — no overlap
              (println (= (:vertices b) (:vertices warped)))")]
      (is (= "true\n" (:print-output r))))))
