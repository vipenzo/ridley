(ns ridley.editor.operations
  "Pure generative operations: extrude, loft, bloft, revolve.
   These functions read turtle-atom for initial state but don't mutate it
   (except legacy implicit-extrude-path)."
  (:require [ridley.editor.state :refer [turtle-atom]]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.transform :as xform]))

(defn ^:export implicit-extrude-closed-path
  "Extrude-closed function - creates closed mesh without side effects.
   Starts from current turtle position/orientation."
  [shape-or-shapes path]
  ;; Handle both single shape and vector of shapes (from text-shape)
  (let [shapes (if (vector? shape-or-shapes) shape-or-shapes [shape-or-shapes])
        ;; Start from current turtle position/orientation
        ;; Also copy joint-mode and resolution settings
        current-turtle @turtle-atom
        initial-state (if current-turtle
                        (-> (turtle/make-turtle)
                            (assoc :position (:position current-turtle))
                            (assoc :heading (:heading current-turtle))
                            (assoc :up (:up current-turtle))
                            (assoc :joint-mode (:joint-mode current-turtle))
                            (assoc :resolution (:resolution current-turtle))
                            (assoc :material (:material current-turtle)))
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
    ;; Return single mesh or vector of meshes
    (if (= 1 (count results))
      (first results)
      results)))

(defn ^:export pure-extrude-path
  "Pure extrude function - creates mesh without side effects.
   Starts from current turtle position/orientation."
  [shape-or-shapes path]
  ;; Handle both single shape and vector of shapes (from text-shape)
  (let [shapes (if (vector? shape-or-shapes) shape-or-shapes [shape-or-shapes])
        ;; Start from current turtle position/orientation (not origin)
        ;; Also copy joint-mode and resolution settings
        current-turtle @turtle-atom
        initial-state (if current-turtle
                        (-> (turtle/make-turtle)
                            (assoc :position (:position current-turtle))
                            (assoc :heading (:heading current-turtle))
                            (assoc :up (:up current-turtle))
                            (assoc :joint-mode (:joint-mode current-turtle))
                            (assoc :resolution (:resolution current-turtle))
                            (assoc :material (:material current-turtle)))
                        (turtle/make-turtle))
        ;; Extrude each shape, collecting results
        results (reduce
                 (fn [acc shape]
                   (let [state (turtle/extrude-from-path initial-state shape path)
                         mesh (last (:meshes state))]
                     (if mesh
                       (conj acc mesh)
                       acc)))
                 []
                 shapes)]
    ;; Return single mesh or vector of meshes
    (if (= 1 (count results))
      (first results)
      results)))

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

(defn ^:export pure-loft-path
  "Pure loft function - creates mesh without side effects.
   Starts from current turtle position/orientation.
   transform-fn: (fn [shape t]) where t goes from 0 to 1
   steps: number of intermediate steps (default 16)

   At corners, generates separate segment meshes and combines them.
   The resulting mesh may have overlapping/intersecting parts."
  ([shape transform-fn path] (pure-loft-path shape transform-fn path 16))
  ([shape transform-fn path steps]
   (let [;; Start from current turtle position/orientation
         ;; Also copy joint-mode and resolution settings
         current-turtle @turtle-atom
         initial-state (if current-turtle
                         (-> (turtle/make-turtle)
                             (assoc :position (:position current-turtle))
                             (assoc :heading (:heading current-turtle))
                             (assoc :up (:up current-turtle))
                             (assoc :joint-mode (:joint-mode current-turtle))
                             (assoc :resolution (:resolution current-turtle))
                             (assoc :material (:material current-turtle)))
                         (turtle/make-turtle))
         ;; Use loft-from-path which generates separate meshes at corners
         result-state (turtle/loft-from-path initial-state shape transform-fn path steps)
         ;; Combine all segment meshes into one
         meshes (:meshes result-state)]
     (combine-meshes meshes))))

(defn ^:export pure-loft-two-shapes
  "Pure loft between two shapes - creates mesh that transitions from shape1 to shape2.
   If shapes have different point counts, they are automatically resampled to match.
   Point arrays are aligned angularly for smooth morphing between different topologies.
   Starts from current turtle position/orientation."
  ([shape1 shape2 path] (pure-loft-two-shapes shape1 shape2 path 16))
  ([shape1 shape2 path steps]
   (let [n1 (count (:points shape1))
         n2 (count (:points shape2))
         ;; Auto-resample to the maximum point count
         [s1 s2] (if (= n1 n2)
                   [shape1 shape2]
                   (let [target-n (max n1 n2)]
                     [(xform/resample shape1 target-n)
                      (xform/resample shape2 target-n)]))
         ;; Align s2's starting point to match s1's angular position
         s2-aligned (xform/align-to-shape s1 s2)
         transform-fn (shape/make-lerp-fn s1 s2-aligned)]
     (pure-loft-path s1 transform-fn path steps))))

(defn ^:export pure-bloft
  "Pure bezier-safe loft - handles self-intersecting paths.
   Uses convex hulls to bridge intersecting ring sections, then unions.
   Better for tight curves like bezier-as paths.

   transform-fn: (fn [shape t]) where t goes from 0 to 1
   steps: number of intermediate steps (default 32)
   threshold: intersection sensitivity 0.0-1.0 (default 0.1)
              Higher = faster but may miss intersections
              Lower = slower but catches more intersections"
  ([shape transform-fn path] (pure-bloft shape transform-fn path 32 0.1))
  ([shape transform-fn path steps] (pure-bloft shape transform-fn path steps 0.1))
  ([shape transform-fn path steps threshold]
   (let [current-turtle @turtle-atom
         initial-state (if current-turtle
                         (-> (turtle/make-turtle)
                             (assoc :position (:position current-turtle))
                             (assoc :heading (:heading current-turtle))
                             (assoc :up (:up current-turtle))
                             (assoc :joint-mode (:joint-mode current-turtle))
                             (assoc :resolution (:resolution current-turtle))
                             (assoc :material (:material current-turtle)))
                         (turtle/make-turtle))
         result-state (turtle/bloft initial-state shape transform-fn path steps threshold)
         meshes (:meshes result-state)]
     (combine-meshes meshes))))

(defn ^:export pure-revolve
  "Pure revolve function - creates mesh without side effects.
   Revolves a 2D profile shape around the turtle's up axis.

   At θ=0 the stamp matches extrude: shape-X → right, shape-Y → up.
   The profile is interpreted as:
   - 2D X = radial distance from axis
   - 2D Y = position along axis (in up direction)

   angle: rotation angle in degrees (default 360 for full revolution)"
  ([shape] (pure-revolve shape 360))
  ([shape angle]
   (let [;; Start from current turtle position/orientation
         ;; Also copy resolution settings
         current-turtle @turtle-atom
         initial-state (if current-turtle
                         (-> (turtle/make-turtle)
                             (assoc :position (:position current-turtle))
                             (assoc :heading (:heading current-turtle))
                             (assoc :up (:up current-turtle))
                             (assoc :resolution (:resolution current-turtle))
                             (assoc :material (:material current-turtle)))
                         (turtle/make-turtle))
         ;; Revolve the shape
         result-state (turtle/revolve-shape initial-state shape angle)
         mesh (last (:meshes result-state))]
     mesh)))

;; Legacy version for backwards compatibility (modifies global state)
(defn ^:export implicit-extrude-path [shape-or-shapes path]
  ;; Handle both single shape and vector of shapes (from text-shape)
  (let [shapes (if (vector? shape-or-shapes) shape-or-shapes [shape-or-shapes])
        start-pos (:position @turtle-atom)]
    (doseq [shape shapes]
      ;; Reset to start position for each shape
      (swap! turtle-atom assoc :position start-pos)
      (swap! turtle-atom turtle/extrude-from-path shape path))
    ;; Return last mesh (or all meshes for multiple shapes)
    (if (= 1 (count shapes))
      (last (:meshes @turtle-atom))
      (vec (take-last (count shapes) (:meshes @turtle-atom))))))
