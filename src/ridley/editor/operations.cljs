(ns ridley.editor.operations
  "Pure generative operations: extrude, loft, revolve.
   These functions read turtle state for initial state but don't mutate it
   (except legacy implicit-extrude-path)."
  (:require [ridley.editor.state :as state]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.transform :as xform]
            [ridley.turtle.extrusion :as extrusion]
            [ridley.turtle.shape-fn :as sfn]
            [ridley.geometry.mesh-utils :as mesh-utils]
            [ridley.clipper.core :as clipper]))

(declare pure-loft-path)
(declare combine-meshes wrap-results)

(defn ^:export implicit-extrude-closed-path
  "Extrude-closed function - creates closed mesh without side effects.
   Starts from current turtle position/orientation."
  [shape-or-shapes path]
  ;; Handle both single shape and vector of shapes (from text-shape)
  (let [shapes (if (vector? shape-or-shapes) shape-or-shapes [shape-or-shapes])
        ;; Start from current turtle position/orientation
        ;; Also copy joint-mode and resolution settings
        current-turtle @@state/turtle-state-var
        initial-state (if current-turtle
                        (-> (turtle/make-turtle)
                            (assoc :position (:position current-turtle))
                            (assoc :heading (:heading current-turtle))
                            (assoc :up (:up current-turtle))
                            (assoc :joint-mode (:joint-mode current-turtle))
                            (assoc :resolution (:resolution current-turtle))
                            (assoc :material (:material current-turtle))
                            (assoc :preserve-up (:preserve-up current-turtle))
                            (assoc :reference-up (:reference-up current-turtle)))
                        (turtle/make-turtle))
        ;; Extrude each shape, collecting results
        results (reduce
                 (fn [acc shape]
                   (let [state (turtle/extrude-closed-from-path initial-state shape path)
                         mesh (last (:meshes state))]
                     (if mesh
                       (conj acc mesh)
                       acc)))
                 []
                 shapes)]
    (wrap-results results)))

(defn ^:export pure-extrude-path
  "Pure extrude function - creates mesh without side effects.
   Starts from current turtle position/orientation.
   For bezier paths, delegates to loft with identity transform (avoids
   shortening artifacts from micro-rotations in bezier walk steps)."
  [shape-or-shapes path]
  (if (:bezier path)
    ;; Bezier paths: use loft with identity transform.
    ;; extrude's analyze-open-path computes shortening from summed absolute
    ;; rotation angles, but bezier micro-rotations (chord→final→chord) cause
    ;; excessive shortening even when the net heading change is small.
    (pure-loft-path shape-or-shapes (fn [s _] s) path)
    ;; Normal path: standard extrude
    (let [shapes (if (vector? shape-or-shapes) shape-or-shapes [shape-or-shapes])
          current-turtle @@state/turtle-state-var
          initial-state (if current-turtle
                          (-> (turtle/make-turtle)
                              (assoc :position (:position current-turtle))
                              (assoc :heading (:heading current-turtle))
                              (assoc :up (:up current-turtle))
                              (assoc :joint-mode (:joint-mode current-turtle))
                              (assoc :resolution (:resolution current-turtle))
                              (assoc :material (:material current-turtle)))
                          (turtle/make-turtle))
          results (reduce
                   (fn [acc shape]
                     (let [state (turtle/extrude-from-path initial-state shape path)
                           mesh (last (:meshes state))]
                       (if mesh (conj acc mesh) acc)))
                   []
                   shapes)]
      (wrap-results results))))

(defn- combine-meshes
  "Combine multiple meshes into one by concatenating vertices and reindexing faces.
   Results in a single mesh with potentially disconnected parts."
  [meshes]
  (when (seq meshes)
    (if (= 1 (count meshes))
      (first meshes)
      (loop [remaining (rest meshes)
             combined-verts (vec (:vertices (first meshes)))
             combined-faces (vec (:faces (first meshes)))]
        (if (empty? remaining)
          (cond-> {:type :mesh
                   :primitive :combined
                   :vertices combined-verts
                   :faces combined-faces
                   :creation-pose (:creation-pose (first meshes))}
            (:material (first meshes)) (assoc :material (:material (first meshes))))
          (let [m (first remaining)
                offset (count combined-verts)
                new-verts (:vertices m)
                new-faces (mapv (fn [face]
                                  (mapv #(+ % offset) face))
                                (:faces m))]
            (recur (rest remaining)
                   (into combined-verts new-verts)
                   (into combined-faces new-faces))))))))

(defn- unwrap-shapes
  "Normalize shape-or-shapes to a vector of individual shapes."
  [shape-or-shapes]
  (if (and (vector? shape-or-shapes)
           (seq shape-or-shapes)
           (map? (first shape-or-shapes)))
    shape-or-shapes
    [shape-or-shapes]))

(defn- wrap-results
  "Return a single mesh from one or more extrusion/loft/revolve results.
   Multiple meshes (from a vector input of shapes) are combined geometry-wise
   so downstream boolean ops receive a single valid mesh, not a vector."
  [results]
  (case (count results)
    0 nil
    1 (first results)
    (combine-meshes results)))

(defn ^:export pure-loft-path
  "Pure loft function - creates mesh without side effects.
   Starts from current turtle position/orientation.
   transform-fn: (fn [shape t]) where t goes from 0 to 1
   steps: number of intermediate steps (defaults to (default-segments state 1))

   At corners, generates separate segment meshes and combines them.
   The resulting mesh may have overlapping/intersecting parts."
  ([shape-or-shapes transform-fn path]
   (pure-loft-path shape-or-shapes transform-fn path nil))
  ([shape-or-shapes transform-fn path steps]
   (let [shapes (unwrap-shapes shape-or-shapes)
         current-turtle @@state/turtle-state-var
         initial-state (if current-turtle
                         (-> (turtle/make-turtle)
                             (assoc :position (:position current-turtle))
                             (assoc :heading (:heading current-turtle))
                             (assoc :up (:up current-turtle))
                             (assoc :joint-mode (:joint-mode current-turtle))
                             (assoc :resolution (:resolution current-turtle))
                             (assoc :material (:material current-turtle)))
                         (turtle/make-turtle))
         steps (or steps (extrusion/default-segments initial-state 1))
         results (reduce
                  (fn [acc shape]
                    (let [result-state (turtle/loft-from-path initial-state shape transform-fn path steps)
                          ;; loft-from-path emits side-wall, corner and cap segments
                          ;; as separate sub-meshes with independent vertex indices.
                          ;; merge-vertices welds coincident positions so the seams
                          ;; close into a watertight mesh.
                          mesh (some-> (combine-meshes (:meshes result-state))
                                       (mesh-utils/merge-vertices 1e-4))]
                      (if mesh (conj acc mesh) acc)))
                  []
                  shapes)]
     (wrap-results results))))

(defn ^:export pure-loft-two-shapes
  "Pure loft between two shapes - creates mesh that transitions from shape1 to shape2.
   If shapes have different point counts, they are automatically resampled to match.
   Point arrays are aligned angularly for smooth morphing between different topologies.
   Starts from current turtle position/orientation.
   If shape1 is a vector of shapes, each is independently lofted to shape2."
  ([shape1-or-shapes shape2 path] (pure-loft-two-shapes shape1-or-shapes shape2 path nil))
  ([shape1-or-shapes shape2 path steps]
   (let [shapes1 (unwrap-shapes shape1-or-shapes)
         results (reduce
                  (fn [acc s1]
                    (let [n1 (count (:points s1))
                          n2 (count (:points shape2))
                          [rs1 rs2] (if (= n1 n2)
                                      [s1 shape2]
                                      (let [target-n (max n1 n2)]
                                        [(xform/resample s1 target-n)
                                         (xform/resample shape2 target-n)]))
                          s2-aligned (xform/align-to-shape rs1 rs2)
                          transform-fn (shape/make-lerp-fn rs1 s2-aligned)
                          mesh (pure-loft-path rs1 transform-fn path steps)]
                      (if mesh (conj acc mesh) acc)))
                  []
                  shapes1)]
     (wrap-results results))))

;; ============================================================
;; Shape-fn adapters
;; ============================================================

(defn ^:export pure-loft-shape-fn
  "Pure loft with a shape-fn. Evaluates shape-fn at each step along the path.
   Bridges shape-fn API to existing loft pipeline."
  ([shape-fn-val path] (pure-loft-shape-fn shape-fn-val path nil))
  ([shape-fn-val path steps]
   (let [path-length (reduce + 0 (keep (fn [cmd]
                                         (when (= :f (:cmd cmd))
                                           (first (:args cmd))))
                                       (:commands path)))]
     (binding [sfn/*path-length* path-length]
       (let [base-shape (shape-fn-val 0)
             transform-fn (fn [_shape t] (shape-fn-val t))]
         (pure-loft-path base-shape transform-fn path steps))))))

(defn- clip-shape-for-revolve
  "Sanitise a shape for revolution. Shapes that cross the revolution axis
   (have both x > 0 and x < 0 vertices) self-intersect when revolved, so
   they are clipped to x >= 0. Shapes entirely on one side of the axis
   pass through unchanged — including x <= 0 shapes used by `revolve+`
   with `:pivot :right`.
   Returns the (possibly clipped) shape, or nil if clipping is empty."
  [s]
  (let [pts (:points s)
        min-x (apply min (map first pts))
        max-x (apply max (map first pts))]
    (cond
      ;; Entirely in x >= 0 or x <= 0: no clip needed.
      (or (>= min-x 0) (<= max-x 0))
      s

      ;; Crosses the axis: clip to x >= 0 (legacy safety behaviour).
      :else
      (let [max-y (apply max (map #(Math/abs (second %)) pts))
            half-extent (+ (max max-x max-y) 100)
            clip-rect (shape/make-shape
                       [[0 (- half-extent)]
                        [half-extent (- half-extent)]
                        [half-extent half-extent]
                        [0 half-extent]]
                       {:centered? true})
            clipped (clipper/shape-intersection s clip-rect)]
        (when clipped
          (state/capture-println "revolve: shape had vertices with x < 0 (crossing revolution axis). Auto-clipped to x >= 0.")
          clipped)))))

(defn ^:export pure-revolve
  "Pure revolve function - creates mesh without side effects.
   Revolves a 2D profile shape around the turtle's up axis.

   At θ=0 the stamp matches extrude: shape-X → right, shape-Y → up.
   The profile is interpreted as:
   - 2D X = radial distance from axis
   - 2D Y = position along axis (in up direction)

   Shapes with vertices at x < 0 are automatically clipped at the
   revolution axis to prevent self-intersecting geometry.

   angle: rotation angle in degrees (default 360 for full revolution)"
  ([shape-or-shapes] (pure-revolve shape-or-shapes 360))
  ([shape-or-shapes angle]
   (let [shapes (unwrap-shapes shape-or-shapes)
         current-turtle @@state/turtle-state-var
         initial-state (if current-turtle
                         (-> (turtle/make-turtle)
                             (assoc :position (:position current-turtle))
                             (assoc :heading (:heading current-turtle))
                             (assoc :up (:up current-turtle))
                             (assoc :resolution (:resolution current-turtle))
                             (assoc :material (:material current-turtle))
                             (assoc :preserve-up (:preserve-up current-turtle))
                             (assoc :reference-up (:reference-up current-turtle)))
                         (turtle/make-turtle))
         results (reduce
                  (fn [acc shape]
                    (let [safe-shape (clip-shape-for-revolve shape)]
                      (if safe-shape
                        (let [result-state (turtle/revolve-shape initial-state safe-shape angle)
                              mesh (last (:meshes result-state))]
                          (if mesh (conj acc mesh) acc))
                        acc)))
                  []
                  shapes)]
     (wrap-results results))))

(defn ^:export pure-revolve-shape-fn
  "Pure revolve with a shape-fn. Evaluates shape-fn at each revolution step.
   Bridges shape-fn API to existing revolve pipeline.
   t=0 at the first ring, t→1 at the last ring."
  ([shape-fn-val] (pure-revolve-shape-fn shape-fn-val 360))
  ([shape-fn-val angle]
   (let [base-shape (shape-fn-val 0)
         current-turtle @@state/turtle-state-var
         initial-state (if current-turtle
                         (-> (turtle/make-turtle)
                             (assoc :position (:position current-turtle))
                             (assoc :heading (:heading current-turtle))
                             (assoc :up (:up current-turtle))
                             (assoc :resolution (:resolution current-turtle))
                             (assoc :material (:material current-turtle))
                             (assoc :preserve-up (:preserve-up current-turtle))
                             (assoc :reference-up (:reference-up current-turtle)))
                         (turtle/make-turtle))
         result-state (turtle/revolve-shape initial-state base-shape angle shape-fn-val)
         mesh (last (:meshes result-state))]
     mesh)))

;; Legacy version for backwards compatibility (modifies global state)
(defn ^:export implicit-extrude-path [shape-or-shapes path]
  ;; Handle both single shape and vector of shapes (from text-shape)
  (let [shapes (if (vector? shape-or-shapes) shape-or-shapes [shape-or-shapes])
        start-pos (:position @@state/turtle-state-var)]
    (doseq [shape shapes]
      ;; Reset to start position for each shape
      (swap! @state/turtle-state-var assoc :position start-pos)
      (swap! @state/turtle-state-var turtle/extrude-from-path shape path))
    ;; Return last mesh (or all meshes for multiple shapes)
    (if (= 1 (count shapes))
      (last (:meshes @@state/turtle-state-var))
      (vec (take-last (count shapes) (:meshes @@state/turtle-state-var))))))
