(ns ridley.measure.core
  "Measurement functions for 3D models.

   Point specifications can be:
   - [x y z]         vector of numbers → direct point
   - :mesh-name      keyword → mesh centroid
   - :mesh-name :face-id  → face center (consumed as pair)

   API:
   (distance :box1 :box2)                    ; centroid to centroid
   (distance :box1 :top :box2 :bottom)       ; face center to face center
   (distance [0 0 0] [100 0 0])             ; point to point
   (ruler ...)                               ; same as distance but shows visual overlay
   (bounds :box1)                            ; bounding box
   (area :box1 :top)                         ; face area
   (clear-rulers)                            ; remove visual rulers"
  (:require [ridley.scene.registry :as registry]
            [ridley.geometry.faces :as faces]
            [ridley.math :as math]
            [ridley.turtle.shape :as shape]
            [ridley.viewport.core :as viewport]))

;; ============================================================
;; Mesh overrides (for tweak live refresh)
;; ============================================================

;; During tweak, maps {:mesh-name mesh-data} to override registry lookups.
;; Rulers re-resolve against these so they follow slider changes.
(defonce ^:private ruler-mesh-overrides (atom {}))

(defn set-ruler-overrides!
  "Set mesh overrides for ruler resolution (used by tweak mode)."
  [overrides]
  (reset! ruler-mesh-overrides overrides))

(defn clear-ruler-overrides!
  "Clear mesh overrides."
  []
  (reset! ruler-mesh-overrides {}))

;; ============================================================
;; Point resolution
;; ============================================================

(defn- resolve-mesh
  "Resolve a mesh by keyword name, checking overrides first."
  [kw]
  (or (get @ruler-mesh-overrides kw)
      (registry/get-mesh kw)))

(defn- mesh-centroid
  "Compute centroid of a mesh from its vertices."
  [mesh]
  (let [verts (:vertices mesh)
        n (count verts)]
    (when (pos? n)
      (math/v* (reduce math/v+ [0 0 0] verts) (/ 1.0 n)))))

(defn- consume-point-spec
  "Consume a point specification from the front of args.
   Returns [point remaining-args] or nil if invalid.
   Greedily tries mesh+face pair before falling back to mesh centroid."
  [args]
  (when (seq args)
    (let [first-arg (first args)]
      (cond
        ;; Vector of 3 numbers → direct point
        (and (vector? first-arg)
             (= 3 (count first-arg))
             (every? number? first-arg))
        [first-arg (rest args)]

        ;; Keyword → mesh name, possibly followed by face-id
        (keyword? first-arg)
        (when-let [mesh (resolve-mesh first-arg)]
          (let [second-arg (second args)]
            (if (and (keyword? second-arg)
                     (get-in mesh [:face-groups second-arg]))
              ;; Mesh + face → face center
              (let [info (faces/compute-face-info
                           (:vertices mesh)
                           (get-in mesh [:face-groups second-arg]))]
                [(:center info) (drop 2 args)])
              ;; Just mesh name → centroid
              [(mesh-centroid mesh) (rest args)])))

        :else nil))))

(defn- parse-two-points
  "Parse arguments into two [x y z] points. Returns [p1 p2] or nil."
  [args]
  (when-let [[p1 remaining] (consume-point-spec args)]
    (when-let [[p2 _] (consume-point-spec remaining)]
      [p1 p2])))

;; ============================================================
;; Measurement functions
;; ============================================================

(defn ^:export distance
  "Calculate distance between two point specifications.
   (distance :box1 :box2)                — between mesh centroids
   (distance :box1 :top :box2 :bottom)   — between face centers
   (distance [0 0 0] [100 0 0])         — between points
   Returns the distance as a number, or nil if points cannot be resolved."
  [& args]
  (when-let [[p1 p2] (parse-two-points args)]
    (math/magnitude (math/v- p2 p1))))

(defn ^:export bounds
  "Get bounding box of a mesh, shape, or path.
   (bounds :box1)        — registered mesh by name
   (bounds mesh-data)    — mesh with :vertices
   (bounds my-shape)     — 2D shape {:type :shape}
   (bounds my-path)      — 2D path {:type :path}
   Returns {:min [...] :max [...] :size [...] :center [...]}."
  [obj]
  (let [obj (if (keyword? obj) (registry/get-mesh obj) obj)]
    (cond
      ;; 2D shape or path → delegate to bounds-2d
      (and (map? obj) (#{:shape :path} (:type obj)))
      (shape/bounds-2d obj)

      ;; 3D mesh → compute from vertices
      (and (map? obj) (:vertices obj))
      (when-let [verts (seq (:vertices obj))]
        (let [xs (map #(nth % 0) verts)
              ys (map #(nth % 1) verts)
              zs (map #(nth % 2) verts)
              min-pt [(apply min xs) (apply min ys) (apply min zs)]
              max-pt [(apply max xs) (apply max ys) (apply max zs)]]
          {:min min-pt
           :max max-pt
           :size (math/v- max-pt min-pt)
           :center (math/v* (math/v+ min-pt max-pt) 0.5)})))))

(defn ^:export area
  "Get the area of a named face.
   (area :box1 :top) or (area mesh-data :top)
   Returns the area as a number, or nil."
  [mesh-or-name face-id]
  (let [mesh (if (keyword? mesh-or-name)
               (registry/get-mesh mesh-or-name)
               mesh-or-name)]
    (when-let [info (faces/face-info mesh face-id)]
      (:area info))))

(defn ^:export ruler
  "Show a visual ruler between two points and return the distance.
   Same argument forms as distance.
   (ruler :box1 :box2)
   (ruler :box1 :top [0 0 50])
   (ruler [0 0 0] [100 0 0])
   Returns the distance as a number."
  [& args]
  (when-let [[p1 p2] (parse-two-points args)]
    (let [dist (math/magnitude (math/v- p2 p1))]
      (viewport/add-ruler! p1 p2 dist (vec args))
      dist)))

(defn ^:export clear-rulers
  "Remove all ruler overlays from the viewport."
  []
  (viewport/clear-rulers!))

;; ============================================================
;; Live refresh (for tweak integration)
;; ============================================================

(defn refresh-rulers!
  "Re-resolve all ruler specs and rebuild visuals.
   Called by tweak mode after each slider change."
  []
  (let [all-args (viewport/get-ruler-args)]
    (when (seq all-args)
      (viewport/clear-rulers!)
      (doseq [args all-args]
        (when args
          (when-let [[p1 p2] (parse-two-points args)]
            (let [dist (math/magnitude (math/v- p2 p1))]
              (viewport/add-ruler! p1 p2 dist args))))))))
