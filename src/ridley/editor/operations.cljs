(ns ridley.editor.operations
  "Pure generative operations: extrude, loft, bloft, revolve.
   These functions read turtle-atom for initial state but don't mutate it
   (except legacy implicit-extrude-path)."
  (:require [ridley.editor.state :refer [turtle-atom] :as state]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.transform :as xform]
            [ridley.turtle.loft :as loft]
            [ridley.turtle.extrusion :as extrusion]
            [ridley.clipper.core :as clipper]))

(declare pure-bloft)

(defn- bezier-path-has-self-intersection?
  "Quick check: would extruding this shape along this bezier path cause
   ring self-intersections? Compares the path's minimum radius of curvature
   against the shape radius. Uses 32 samples for accurate curvature estimation."
  [initial-state shape path]
  (let [radius (extrusion/shape-radius shape)
        poses (loft/walk-path-poses initial-state path 32)
        n (count poses)]
    (when (>= n 2)
      (loop [i 0]
        (if (>= i (dec n))
          false
          (let [pa (nth poses i)
                pb (nth poses (inc i))
                dp (mapv - (:position pb) (:position pa))
                d (Math/sqrt (reduce + (mapv * dp dp)))]
            (if (< d 0.0001)
              (recur (inc i))
              (let [dot-hh (reduce + (mapv * (:heading pa) (:heading pb)))
                    angle (Math/acos (min 1.0 (max -1.0 dot-hh)))]
                (if (> (* radius angle) d)
                  true
                  (recur (inc i)))))))))))

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
   Starts from current turtle position/orientation.
   For bezier paths with self-intersecting rings, automatically uses bloft."
  [shape-or-shapes path]
  (let [shapes (if (vector? shape-or-shapes) shape-or-shapes [shape-or-shapes])
        current-turtle @turtle-atom
        initial-state (if current-turtle
                        (-> (turtle/make-turtle)
                            (assoc :position (:position current-turtle))
                            (assoc :heading (:heading current-turtle))
                            (assoc :up (:up current-turtle))
                            (assoc :joint-mode (:joint-mode current-turtle))
                            (assoc :resolution (:resolution current-turtle))
                            (assoc :material (:material current-turtle)))
                        (turtle/make-turtle))]
    (if (and (:bezier path)
             (bezier-path-has-self-intersection? initial-state (first shapes) path))
      ;; Bezier with tight curves: use bloft
      (do (js/console.log "extrude: bezier path has self-intersecting rings, using bloft")
          (pure-bloft shape-or-shapes (fn [s _] s) path nil 0.1))
      ;; Normal path OR gentle bezier: standard extrude
      (let [results (reduce
                     (fn [acc shape]
                       (let [state (turtle/extrude-from-path initial-state shape path)
                             mesh (last (:meshes state))]
                         (if mesh (conj acc mesh) acc)))
                     []
                     shapes)]
        (if (= 1 (count results))
          (first results)
          results)))))

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
  "Return single result or vector based on count."
  [results]
  (if (= 1 (count results))
    (first results)
    results))

(defn ^:export pure-loft-path
  "Pure loft function - creates mesh without side effects.
   Starts from current turtle position/orientation.
   transform-fn: (fn [shape t]) where t goes from 0 to 1
   steps: number of intermediate steps (default 16)

   At corners, generates separate segment meshes and combines them.
   The resulting mesh may have overlapping/intersecting parts."
  ([shape-or-shapes transform-fn path] (pure-loft-path shape-or-shapes transform-fn path 16))
  ([shape-or-shapes transform-fn path steps]
   (let [shapes (unwrap-shapes shape-or-shapes)
         current-turtle @turtle-atom
         initial-state (if current-turtle
                         (-> (turtle/make-turtle)
                             (assoc :position (:position current-turtle))
                             (assoc :heading (:heading current-turtle))
                             (assoc :up (:up current-turtle))
                             (assoc :joint-mode (:joint-mode current-turtle))
                             (assoc :resolution (:resolution current-turtle))
                             (assoc :material (:material current-turtle)))
                         (turtle/make-turtle))]
     (if (and (:bezier path)
              (bezier-path-has-self-intersection? initial-state (first shapes) path))
       ;; Bezier with tight curves: use bloft
       (do (js/console.log "loft: bezier path has self-intersecting rings, using bloft")
           (pure-bloft shape-or-shapes transform-fn path nil 0.1))
       ;; Normal path OR gentle bezier: standard loft
       (let [results (reduce
                      (fn [acc shape]
                        (let [result-state (turtle/loft-from-path initial-state shape transform-fn path steps)
                              mesh (combine-meshes (:meshes result-state))]
                          (if mesh (conj acc mesh) acc)))
                      []
                      shapes)]
         (wrap-results results))))))

(defn ^:export pure-loft-two-shapes
  "Pure loft between two shapes - creates mesh that transitions from shape1 to shape2.
   If shapes have different point counts, they are automatically resampled to match.
   Point arrays are aligned angularly for smooth morphing between different topologies.
   Starts from current turtle position/orientation.
   If shape1 is a vector of shapes, each is independently lofted to shape2."
  ([shape1-or-shapes shape2 path] (pure-loft-two-shapes shape1-or-shapes shape2 path 16))
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

(defn ^:export pure-bloft
  "Pure bezier-safe loft - handles self-intersecting paths.
   Uses convex hulls to bridge intersecting ring sections, then unions.
   Better for tight curves like bezier-as paths.

   transform-fn: (fn [shape t]) where t goes from 0 to 1
   steps: number of intermediate steps (default 32)
   threshold: intersection sensitivity 0.0-1.0 (default 0.1)
              Higher = faster but may miss intersections
              Lower = slower but catches more intersections"
  ([shape-or-shapes transform-fn path] (pure-bloft shape-or-shapes transform-fn path nil 0.1))
  ([shape-or-shapes transform-fn path steps] (pure-bloft shape-or-shapes transform-fn path steps 0.1))
  ([shape-or-shapes transform-fn path steps threshold]
   (let [shapes (unwrap-shapes shape-or-shapes)
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
         results (reduce
                  (fn [acc shape]
                    (let [result-state (turtle/bloft initial-state shape transform-fn path steps threshold)
                          mesh (combine-meshes (:meshes result-state))]
                      (if mesh (conj acc mesh) acc)))
                  []
                  shapes)]
     (wrap-results results))))

(defn- clip-shape-for-revolve
  "Clip a shape to x >= 0 for revolve. Vertices with x < 0 would cross
   the revolution axis and produce self-intersecting geometry.
   Returns the clipped shape, or nil if entirely in x < 0."
  [s]
  (let [pts (:points s)
        min-x (apply min (map first pts))]
    (if (>= min-x 0)
      s  ;; all points already at x >= 0
      (let [max-x (apply max (map first pts))
            max-y (apply max (map #(Math/abs (second %)) pts))
            half-extent (+ (max max-x max-y) 100)
            ;; Large rectangle covering x >= 0
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
         current-turtle @turtle-atom
         initial-state (if current-turtle
                         (-> (turtle/make-turtle)
                             (assoc :position (:position current-turtle))
                             (assoc :heading (:heading current-turtle))
                             (assoc :up (:up current-turtle))
                             (assoc :resolution (:resolution current-turtle))
                             (assoc :material (:material current-turtle)))
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
