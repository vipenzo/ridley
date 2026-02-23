(ns ridley.manifold.core
  "Manifold WASM integration for mesh validation and boolean operations.

   Manifold is a geometry library for creating and operating on manifold
   triangle meshes. A manifold mesh is a watertight mesh representing a
   solid object - essential for 3D printing and CAD operations.

   Key features:
   - Validate if a mesh is manifold (watertight, no self-intersections)
   - Boolean operations: union, difference, intersection
   - Mesh repair/merge for nearly-manifold meshes

   Uses manifold-3d v3.0 loaded from CDN as an ES module."
  (:require [ridley.schema :as schema]))

;; Manifold WASM module state
(defonce ^:private manifold-state (atom nil))

(defn initialized?
  "Check if Manifold WASM has been initialized."
  []
  (some? @manifold-state))

(defn ^:export wait-for-manifold-module
  "Poll for window.ManifoldModule to become available.
   Returns a promise that resolves with the Module or rejects after timeout."
  [max-attempts interval-ms]
  (js/Promise.
   (fn [resolve reject]
     (let [attempts (atom 0)]
       (letfn [(check []
                 (if-let [Module js/window.ManifoldModule]
                   (resolve Module)
                   (do
                     (swap! attempts inc)
                     (if (< @attempts max-attempts)
                       (js/setTimeout check interval-ms)
                       (reject (js/Error. "Manifold module load timeout"))))))]
         (check))))))

(defn init!
  "Initialize Manifold WASM module. Returns a promise.
   Must be called before any other manifold operations.
   Uses the global ManifoldModule loaded from CDN script."
  []
  (if (initialized?)
    (js/Promise.resolve @manifold-state)
    ;; Wait for ManifoldModule to be available (ES module loads async)
    (-> (wait-for-manifold-module 50 100)  ; 50 attempts, 100ms each = 5 seconds max
        (.then (fn [Module]
                 (^js Module)))
        (.then (fn [^js wasm]
                 ;; v3.0 requires setup() to be called before using classes
                 (.setup wasm)
                 (reset! manifold-state
                         {:wasm wasm
                          :Manifold (.-Manifold ^js wasm)
                          :Mesh (.-Mesh ^js wasm)
                          :CrossSection (.-CrossSection ^js wasm)})
                 @manifold-state)))))

(defn ^:export get-manifold-class
  "Get the Manifold class from initialized state."
  []
  (when-let [state @manifold-state]
    (:Manifold state)))


;; ============================================================
;; Mesh conversion: Ridley -> Manifold
;; ============================================================

(defn ^:export ridley-mesh->manifold-mesh
  "Convert a Ridley mesh to Manifold Mesh format.

   Ridley mesh format:
   {:vertices [[x y z] [x y z] ...]
    :faces [[i j k] [i j k] ...]}  ; triangles

   Manifold Mesh format:
   {numProp: 3,
    vertProperties: Float32Array of [x,y,z, x,y,z, ...],
    triVerts: Uint32Array of [i,j,k, i,j,k, ...]}"
  [ridley-mesh]
  (let [vertices (:vertices ridley-mesh)
        faces (:faces ridley-mesh)
        vert-arr (js/Float32Array. (* (count vertices) 3))
        face-arr (js/Uint32Array. (* (count faces) 3))]
    ;; Fill vertices via reduce: sequential iteration on PersistentVector
    ;; is much faster than random-access nth (uses internal chunked paths)
    (reduce (fn [off v]
              (aset vert-arr off (v 0))
              (aset vert-arr (+ off 1) (v 1))
              (aset vert-arr (+ off 2) (v 2))
              (+ off 3))
            0 vertices)
    ;; Fill faces via reduce
    (reduce (fn [off f]
              (aset face-arr off (f 0))
              (aset face-arr (+ off 1) (f 1))
              (aset face-arr (+ off 2) (f 2))
              (+ off 3))
            0 faces)
    #js {:numProp 3
         :vertProperties vert-arr
         :triVerts face-arr}))

;; Default creation pose for mesh constructors (centered at origin)
;; Must match turtle's default orientation: facing +X, up +Z
(def ^:private default-creation-pose
  {:position [0 0 0]
   :heading [1 0 0]  ; +X forward (matches turtle default)
   :up [0 0 1]})     ; +Z up

(defn ^:export manifold-mesh->ridley-mesh
  "Convert a Manifold Mesh back to Ridley mesh format."
  [^js manifold-mesh]
  (let [vert-props (.-vertProperties manifold-mesh)
        tri-verts (.-triVerts manifold-mesh)
        num-prop (.-numProp manifold-mesh)
        vlen (.-length vert-props)
        flen (.-length tri-verts)
        ;; Parse vertices: tight loop with transient vector
        vertices (loop [i 0, acc (transient [])]
                   (if (< i vlen)
                     (recur (+ i num-prop)
                            (conj! acc [(aget vert-props i)
                                        (aget vert-props (+ i 1))
                                        (aget vert-props (+ i 2))]))
                     (persistent! acc)))
        ;; Parse faces: tight loop with transient vector
        faces (loop [i 0, acc (transient [])]
                (if (< i flen)
                  (recur (+ i 3)
                         (conj! acc [(aget tri-verts i)
                                     (aget tri-verts (+ i 1))
                                     (aget tri-verts (+ i 2))]))
                  (persistent! acc)))]
    (schema/assert-mesh!
     {:type :mesh
      :vertices vertices
      :faces faces
      :creation-pose default-creation-pose})))

;; ============================================================
;; Manifold operations
;; ============================================================

(defn ^:export mesh->manifold
  "Create a Manifold object from a Ridley mesh.
   Returns cached Manifold if available (from a previous CSG operation),
   otherwise creates a new one. Returns nil if the mesh is not valid/manifold.

   The Manifold constructor will attempt to create a valid manifold,
   merging nearly-identical vertices. If the input is too broken,
   it may return an empty manifold."
  [ridley-mesh]
  (or (::manifold-cache ridley-mesh)
      (when-let [^js Manifold (get-manifold-class)]
        (when (and (:vertices ridley-mesh) (:faces ridley-mesh))
          (try
            (let [mesh-data (ridley-mesh->manifold-mesh ridley-mesh)
                  manifold (new Manifold mesh-data)]
              manifold)
            (catch :default e
              (js/console.error "Failed to create manifold:" e)
              nil))))))

(defn ^:export manifold->mesh
  "Extract the mesh from a Manifold object back to Ridley format."
  [^js manifold]
  (when manifold
    (let [^js mesh (.getMesh manifold)]
      (manifold-mesh->ridley-mesh mesh))))

(defn manifold?
  "Check if a Ridley mesh is manifold (watertight, valid solid).
   Returns true if the mesh can be converted to a valid Manifold
   with non-zero volume."
  [ridley-mesh]
  (when-let [manifold (mesh->manifold ridley-mesh)]
    (let [status-obj (.status manifold)
          ;; In v3.0+, status() returns enum object with .value property
          status-code (.-value status-obj)
          ;; Status 0 = OK, non-zero = error
          is-ok (zero? status-code)
          ;; Also check it's not empty - use volume() method (v3.0+ API)
          volume (.volume manifold)
          has-volume (> volume 0)]
      (.delete manifold)
      (and is-ok has-volume))))

(defn get-mesh-status
  "Get detailed status information about a mesh.
   Returns {:manifold? bool :volume number :surface-area number :status keyword}"
  [ridley-mesh]
  (if-let [manifold (mesh->manifold ridley-mesh)]
    (let [status-obj (.status manifold)
          ;; In manifold-3d v3.0+, status() returns an enum object with .value property
          status-code (.-value status-obj)
          ;; In manifold-3d v3.0+, getProperties() was replaced with volume() and surfaceArea()
          volume (.volume manifold)
          surface-area (.surfaceArea manifold)
          ;; Manifold status: NoError=0, NonFiniteVertex=1, NotManifold=2, VertexOutOfBounds=3, etc.
          status-kw (case status-code
                      0 :ok
                      1 :non-finite-vertex
                      2 :not-manifold
                      3 :vertex-index-out-of-bounds
                      4 :properties-wrong-length
                      5 :missing-position-properties
                      6 :merge-vectors-different-lengths
                      7 :merge-index-out-of-bounds
                      8 :transform-wrong-length
                      9 :run-index-wrong-length
                      10 :face-id-wrong-length
                      (keyword (str "unknown-" status-code)))
          result {:manifold? (and (zero? status-code) (> volume 0))
                  :status status-kw
                  :volume volume
                  :surface-area surface-area}]
      (.delete manifold)
      result)
    {:manifold? false
     :status :failed-to-create
     :volume 0
     :surface-area 0}))

;; ============================================================
;; Self-union (resolve self-intersections)
;; ============================================================

(defn solidify
  "Resolve self-intersections in a mesh via boolean self-union (A ∪ A).
   Returns a clean mesh with only the outer surface, or the original
   mesh unchanged if Manifold conversion fails.

   Usage: (solidify (loft ...)) when a loft produces self-intersecting geometry."
  [ridley-mesh]
  (when-let [Manifold (get-manifold-class)]
    (if (and (:vertices ridley-mesh) (:faces ridley-mesh))
      (try
        (let [mesh-data (ridley-mesh->manifold-mesh ridley-mesh)
              ^js m1 (new Manifold mesh-data)
              ^js m2 (new Manifold mesh-data)
              ;; Self-union: A ∪ A resolves self-intersections
              ^js raw-result (.add m1 m2)
              ^js clean (.asOriginal raw-result)
              output (manifold->mesh clean)
              output (cond-> output
                       (:creation-pose ridley-mesh) (assoc :creation-pose (:creation-pose ridley-mesh))
                       (:material ridley-mesh) (assoc :material (:material ridley-mesh)))]
          (.delete m1)
          (.delete m2)
          (.delete raw-result)
          ;; Cache result for potential next CSG op in chain
          (-> (schema/assert-mesh! output)
              (assoc ::manifold-cache clean)))
        (catch :default e
          (js/console.warn "solidify failed:" e)
          ridley-mesh))
      ridley-mesh)))

;; ============================================================
;; Boolean operations
;; ============================================================

(defn- union-two
  "Compute the union of exactly two meshes.
   Reuses cached Manifold objects from prior CSG results when available."
  [mesh-a mesh-b]
  (when (get-manifold-class)
    (let [ma (mesh->manifold mesh-a)
          mb (mesh->manifold mesh-b)]
      (when (and ma mb)
        (let [^js raw-result (.add ma mb)
              ^js result (.asOriginal raw-result)
              output (manifold->mesh result)
              output (cond-> output
                       (:creation-pose mesh-a) (assoc :creation-pose (:creation-pose mesh-a))
                       (:material mesh-a) (assoc :material (:material mesh-a)))]
          (.delete ma)
          (.delete mb)
          (.delete raw-result)
          ;; Cache result Manifold for potential next CSG op in chain
          (-> (schema/assert-mesh! output)
              (assoc ::manifold-cache result)))))))

(defn- tree-union
  "Union meshes using balanced binary tree strategy.
   Much faster than sequential reduce for large mesh counts."
  [meshes]
  (case (count meshes)
    0 nil
    1 (first meshes)
    2 (union-two (first meshes) (second meshes))
    ;; Split in half, recurse, union the two halves
    (let [mid (quot (count meshes) 2)
          left (tree-union (subvec meshes 0 mid))
          right (tree-union (subvec meshes mid))]
      (if (and left right)
        (union-two left right)
        (or left right)))))

(defn union
  "Compute the union of one or more meshes.
   Returns a new Ridley mesh.

   Usage:
   (union a)             ; returns a unchanged (no-op)
   (union a b)           ; union of two meshes
   (union a b c d)       ; union of multiple meshes
   (union [a b c d])     ; union of a vector of meshes"
  [first-arg & more]
  (let [;; Normalize: accept both (union a b c) and (union [a b c])
        meshes (if (and (empty? more) (sequential? first-arg))
                 (vec first-arg)
                 (into [first-arg] more))]
    (case (count meshes)
      0 nil
      1 (first meshes)
      (tree-union (vec meshes)))))

(defn- difference-two
  "Compute the difference of exactly two meshes (A - B).
   Reuses cached Manifold objects from prior CSG results when available."
  [mesh-a mesh-b]
  (when (get-manifold-class)
    (let [ma (mesh->manifold mesh-a)
          mb (mesh->manifold mesh-b)]
      (when (and ma mb)
        (let [status-a (.-value (.status ma))
              status-b (.-value (.status mb))]
          (when (not (zero? status-a))
            (js/console.warn "mesh-difference: mesh-a is not manifold, status:" status-a))
          (when (not (zero? status-b))
            (js/console.warn "mesh-difference: mesh-b is not manifold, status:" status-b)))
        (let [^js raw-result (.subtract ma mb)
              ^js result (.asOriginal raw-result)
              output (manifold->mesh result)
              output (cond-> output
                       (:creation-pose mesh-a) (assoc :creation-pose (:creation-pose mesh-a))
                       (:material mesh-a) (assoc :material (:material mesh-a)))]
          (.delete ma)
          (.delete mb)
          (.delete raw-result)
          ;; Cache result Manifold for potential next CSG op in chain
          (-> (schema/assert-mesh! output)
              (assoc ::manifold-cache result)))))))

(defn difference
  "Compute the difference of meshes (A - B - C - ...).
   Returns a new Ridley mesh.

   Usage:
   (difference a b)         ; subtract B from A
   (difference a b c d)     ; subtract B, C, D from A
   (difference [a b c d])   ; subtract B, C, D from A (first is base)"
  [first-arg & more]
  (let [;; Normalize: accept both (difference a b c) and (difference [a b c])
        meshes (if (and (empty? more) (sequential? first-arg))
                 (vec first-arg)
                 (into [first-arg] more))]
    (when (>= (count meshes) 2)
      (reduce difference-two meshes))))

(defn- intersection-two
  "Compute the intersection of exactly two meshes.
   Reuses cached Manifold objects from prior CSG results when available."
  [mesh-a mesh-b]
  (when (get-manifold-class)
    (let [ma (mesh->manifold mesh-a)
          mb (mesh->manifold mesh-b)]
      (when (and ma mb)
        (let [^js raw-result (.intersect ma mb)
              ^js result (.asOriginal raw-result)
              output (manifold->mesh result)
              output (cond-> output
                       (:creation-pose mesh-a) (assoc :creation-pose (:creation-pose mesh-a))
                       (:material mesh-a) (assoc :material (:material mesh-a)))]
          (.delete ma)
          (.delete mb)
          (.delete raw-result)
          ;; Cache result Manifold for potential next CSG op in chain
          (-> (schema/assert-mesh! output)
              (assoc ::manifold-cache result)))))))

(defn intersection
  "Compute the intersection of two or more meshes (A ∩ B ∩ C ∩ ...).
   Returns a new Ridley mesh.

   Usage:
   (intersection a b)         ; intersection of two meshes
   (intersection a b c d)     ; intersection of multiple meshes
   (intersection [a b c d])   ; intersection of a vector of meshes"
  [first-arg & more]
  (let [;; Normalize: accept both (intersection a b c) and (intersection [a b c])
        meshes (if (and (empty? more) (sequential? first-arg))
                 (vec first-arg)
                 (into [first-arg] more))]
    (when (>= (count meshes) 2)
      (reduce intersection-two meshes))))

(defn hull
  "Compute the convex hull of one or more meshes.
   The convex hull is the smallest convex shape that contains all input meshes.

   Usage:
   (hull mesh)                    ; hull of single mesh
   (hull mesh1 mesh2)             ; hull of multiple meshes
   (hull [mesh1 mesh2 mesh3])     ; hull of vector of meshes

   Returns a new Ridley mesh."
  [& args]
  (when-let [Manifold (get-manifold-class)]
    (let [;; Normalize args: accept both (hull a b c) and (hull [a b c])
          meshes (if (and (= 1 (count args))
                          (vector? (first args)))
                   (first args)
                   args)
          ;; Convert all meshes to Manifold objects
          manifolds (keep mesh->manifold meshes)]
      (when (seq manifolds)
        (try
          (let [;; Manifold.hull() is a static method that takes an array
                manifold-array (clj->js (vec manifolds))
                ^js raw-result (.call (.-hull ^js Manifold) Manifold manifold-array)
                ^js result (.asOriginal raw-result)
                output (manifold->mesh result)
                first-mesh (first meshes)
                output (cond-> output
                         (:creation-pose first-mesh) (assoc :creation-pose (:creation-pose first-mesh))
                         (:material first-mesh) (assoc :material (:material first-mesh)))]
            ;; Clean up inputs
            (doseq [m manifolds]
              (.delete m))
            (.delete raw-result)
            ;; Cache result for potential next CSG op in chain
            (-> (schema/assert-mesh! output)
                (assoc ::manifold-cache result)))
          (catch :default e
            (js/console.error "Hull operation failed:" e)
            ;; Clean up on error
            (doseq [m manifolds]
              (.delete m))
            nil))))))

(defn hull-from-points
  "Compute the convex hull from raw vertex points.
   Unlike `hull`, this doesn't require manifold input meshes.

   Usage:
   (hull-from-points [[x1 y1 z1] [x2 y2 z2] ...])
   (hull-from-points mesh1 mesh2)  ; extracts vertices from meshes

   Returns a new Ridley mesh."
  [& args]
  (when-let [Manifold (get-manifold-class)]
    (let [;; Collect all vertices from args
          all-points (cond
                       ;; Single vector of points
                       (and (= 1 (count args))
                            (vector? (first args))
                            (vector? (first (first args))))
                       (first args)

                       ;; Multiple meshes - extract vertices
                       :else
                       (vec (mapcat (fn [arg]
                                      (if (and (map? arg) (:vertices arg))
                                        (:vertices arg)
                                        (when (vector? arg) arg)))
                                    (if (and (= 1 (count args)) (vector? (first args)))
                                      (first args)
                                      args))))]
      (when (>= (count all-points) 4)  ;; Need at least 4 points for 3D hull
        (try
          (let [;; Manifold.hull takes array of Vec3
                points-array (js/Array.)
                _ (doseq [[x y z] all-points]
                    (.push points-array #js [x y z]))
                ^js raw-result (.call (.-hull ^js Manifold) Manifold points-array)
                ^js result (.asOriginal raw-result)
                output (manifold->mesh result)
                ;; Inherit creation-pose/material from first mesh arg if present
                first-mesh-arg (first (filter #(and (map? %) (:vertices %))
                                              (if (and (= 1 (count args)) (vector? (first args)))
                                                (first args) args)))
                output (cond-> output
                         (:creation-pose first-mesh-arg) (assoc :creation-pose (:creation-pose first-mesh-arg))
                         (:material first-mesh-arg) (assoc :material (:material first-mesh-arg)))]
            (.delete raw-result)
            (.delete result)
            (schema/assert-mesh! output))
          (catch :default e
            (js/console.error "Hull from points failed:" e)
            nil)))))) 

;; ============================================================
;; Simple mesh concatenation (no Manifold required)
;; ============================================================

(defn concat-meshes
  "Concatenate multiple meshes into one by combining vertices and faces.
   Unlike mesh-union, this does NOT perform boolean operations — it simply
   merges the geometry. The result is not manifold-valid but works for
   heightmap sampling, visualization, etc.

   Usage:
   (concat-meshes [mesh1 mesh2 mesh3])
   (concat-meshes mesh1 mesh2 mesh3)"
  [& args]
  (let [meshes (if (and (= 1 (count args)) (sequential? (first args)))
                 (first args)
                 args)]
    (when (seq meshes)
      (loop [remaining (seq meshes)
             all-verts []
             all-faces []
             offset 0]
        (if (not remaining)
          (let [base-pose (or (:creation-pose (first meshes)) default-creation-pose)
                n (count all-verts)
                centroid (if (pos? n)
                           (mapv #(/ % n)
                                 (reduce (fn [acc v] (mapv + acc v))
                                         [0 0 0] all-verts))
                           (:position base-pose))]
            (schema/assert-mesh!
             (cond-> {:type :mesh
                      :vertices all-verts
                      :faces all-faces
                      :creation-pose (assoc base-pose :position centroid)}
               (:material (first meshes)) (assoc :material (:material (first meshes))))))
          (let [m (first remaining)
                verts (:vertices m)
                faces (:faces m)
                shifted (mapv (fn [face] (mapv #(+ % offset) face)) faces)]
            (recur (next remaining)
                   (into all-verts verts)
                   (into all-faces shifted)
                   (+ offset (count verts)))))))))

;; ============================================================
;; CrossSection extrusion (handles holes natively)
;; ============================================================

(defn ^:export get-cross-section-class
  "Get the CrossSection class from initialized state."
  []
  (when-let [state @manifold-state]
    (:CrossSection state)))

(defn ^:export extrude-cross-section
  "Extrude a 2D cross-section with optional holes to create a 3D manifold.

   contours: vector of contours, each contour is a vector of [x y] points.
             First contour is the outer boundary, rest are holes.
             Outer should be counter-clockwise, holes clockwise.
   height: extrusion height

   Returns a Ridley mesh."
  [contours height]
  (let [^js CrossSection (get-cross-section-class)]
    (if-not CrossSection
      nil
      (if-not (seq contours)
        nil
        (try
          (let [;; Convert contours to the format expected by CrossSection
                ;; CrossSection constructor takes an array of SimplePolygons
                ;; where each polygon is an array of Vec2 (just [x,y] arrays)
                polygons (clj->js (mapv (fn [contour]
                                          (mapv (fn [[x y]] #js [x y]) contour))
                                        contours))
                cross-section (new CrossSection polygons)
                ;; Extrude to 3D
                manifold (.extrude cross-section height)
                result (manifold->mesh manifold)]
            ;; Clean up
            (.delete cross-section)
            (.delete manifold)
            (schema/assert-mesh! result))
          (catch :default e
            (js/console.error "CrossSection extrusion failed:" e)
            nil))))))

;; ============================================================
;; Mesh slicing — cross-section at arbitrary plane
;; ============================================================

(defn- compute-basis
  "Build an orthonormal basis where Z aligns with the given normal.
   Returns [right up normal] as 3-element vectors."
  [[nx ny nz]]
  (let [;; Pick a non-parallel seed vector for cross product
        seed (if (> (Math/abs nz) 0.9) [1 0 0] [0 0 1])
        ;; right = normalize(seed × normal)
        [sx sy sz] seed
        rx (- (* sy nz) (* sz ny))
        ry (- (* sz nx) (* sx nz))
        rz (- (* sx ny) (* sy nx))
        rlen (Math/sqrt (+ (* rx rx) (* ry ry) (* rz rz)))
        right [(/ rx rlen) (/ ry rlen) (/ rz rlen)]
        ;; up = normalize(normal × right)
        [rrx rry rrz] right
        ux (- (* ny rrz) (* nz rry))
        uy (- (* nz rrx) (* nx rrz))
        uz (- (* nx rry) (* ny rrx))
        ulen (Math/sqrt (+ (* ux ux) (* uy uy) (* uz uz)))]
    [right [(/ ux ulen) (/ uy ulen) (/ uz ulen)] [nx ny nz]]))

(defn ^:export slice-at-plane
  "Slice a mesh at an arbitrary plane, returning cross-section contours.

   The plane is defined by a point and a normal vector.
   Optionally accepts explicit right and up vectors for the plane's
   local coordinate system; if omitted, a basis is auto-computed.

   Returns a vector of shapes (2D contours in the plane's local coordinates,
   where X = right, Y = up relative to the normal).
   Shapes have :preserve-position? true so stamp renders them at absolute
   coordinates in the plane.

   (slice-at-plane mesh [0 0 1] [0 0 90])              ; horizontal slice at Z=90
   (slice-at-plane mesh [1 0 0] [0 0 0])               ; vertical slice at X=0
   (slice-at-plane mesh [1 0 0] [0 0 0] [0 1 0] [0 0 1]) ; explicit basis"
  ([ridley-mesh normal point]
   (let [[right up _] (compute-basis normal)]
     (slice-at-plane ridley-mesh normal point right up)))
  ([ridley-mesh normal point right up]
   (when (get-manifold-class)
     (when (and (:vertices ridley-mesh) (:faces ridley-mesh))
       (try
         (let [[px py pz] point
               [rx ry rz] right
               [ux uy uz] up
               [nx ny nz] normal
               ;; Transform vertices: local = R * (v - point)
               ;; R rows are: right, up, normal
               xform-vert (fn [[vx vy vz]]
                            (let [dx (- vx px) dy (- vy py) dz (- vz pz)]
                              [(+ (* rx dx) (* ry dy) (* rz dz))
                               (+ (* ux dx) (* uy dy) (* uz dz))
                               (+ (* nx dx) (* ny dy) (* nz dz))]))
               xformed-verts (mapv xform-vert (:vertices ridley-mesh))
               xformed-mesh (-> ridley-mesh
                                (assoc :vertices xformed-verts)
                                (dissoc ::manifold-cache))
               ;; Create Manifold from transformed mesh and slice at Z=0
               ^js m (mesh->manifold xformed-mesh)]
           (when m
             (let [^js cs (.slice m 0)
                   ^js polys (.toPolygons cs)
                   ;; Convert JS polygons to contour vectors
                   contours (vec
                             (for [i (range (.-length polys))
                                   :let [^js poly (aget polys i)
                                         pts (vec (for [j (range (.-length poly))
                                                        :let [^js pt (aget poly j)]]
                                                    [(aget pt 0) (aget pt 1)]))]
                                   :when (>= (count pts) 3)]
                               pts))
                   ;; Signed area: positive = CCW (outer), negative = CW (hole)
                   signed-area (fn [pts]
                                 (let [n (count pts)]
                                   (* 0.5
                                      (reduce
                                       (fn [sum i]
                                         (let [[x1 y1] (nth pts i)
                                               [x2 y2] (nth pts (mod (inc i) n))]
                                           (+ sum (- (* x1 y2) (* x2 y1)))))
                                       0 (range n)))))
                   ;; Classify contours
                   outers (filterv #(pos? (signed-area %)) contours)
                   holes  (filterv #(neg? (signed-area %)) contours)
                   ;; Point-in-polygon test (ray casting)
                   point-in-poly? (fn [[px py] poly]
                                    (let [n (count poly)]
                                      (loop [i 0, inside? false]
                                        (if (>= i n)
                                          inside?
                                          (let [[xi yi] (nth poly i)
                                                [xj yj] (nth poly (mod (inc i) n))
                                                crosses? (and (not= (> yi py) (> yj py))
                                                              (< px (+ xi (* (/ (- py yi) (- yj yi))
                                                                             (- xj xi)))))]
                                            (recur (inc i) (if crosses? (not inside?) inside?)))))))
                   ;; Assign each hole to its containing outer contour
                   shapes (mapv
                           (fn [outer]
                             (let [my-holes (filterv
                                            (fn [hole]
                                              (point-in-poly? (first hole) outer))
                                            holes)]
                               (cond-> {:type :shape
                                        :points outer
                                        :centered? false
                                        :preserve-position? true}
                                 (seq my-holes) (assoc :holes my-holes))))
                           outers)]
               (.delete m)
               (.delete cs)
               shapes)))
         (catch :default e
           (js/console.error "slice-at-plane failed:" e)
           nil))))))
