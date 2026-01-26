(ns ridley.manifold.core
  "Manifold WASM integration for mesh validation and boolean operations.

   Manifold is a geometry library for creating and operating on manifold
   triangle meshes. A manifold mesh is a watertight mesh representing a
   solid object - essential for 3D printing and CAD operations.

   Key features:
   - Validate if a mesh is manifold (watertight, no self-intersections)
   - Boolean operations: union, difference, intersection
   - Mesh repair/merge for nearly-manifold meshes

   Uses manifold-3d v3.0 loaded from CDN as an ES module.")

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
                 (Module)))
        (.then (fn [wasm]
                 ;; v3.0 requires setup() to be called before using classes
                 (.setup wasm)
                 (reset! manifold-state
                         {:wasm wasm
                          :Manifold (.-Manifold wasm)
                          :Mesh (.-Mesh wasm)
                          :CrossSection (.-CrossSection wasm)})
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
        ;; Flatten vertices to [x,y,z, x,y,z, ...]
        vert-props (js/Float32Array. (clj->js (mapcat identity vertices)))
        ;; Flatten faces to [i,j,k, i,j,k, ...]
        tri-verts (js/Uint32Array. (clj->js (mapcat identity faces)))]
    #js {:numProp 3
         :vertProperties vert-props
         :triVerts tri-verts}))

;; Default creation pose for mesh constructors (centered at origin)
;; Must match turtle's default orientation: facing +X, up +Z
(def ^:private default-creation-pose
  {:position [0 0 0]
   :heading [1 0 0]  ; +X forward (matches turtle default)
   :up [0 0 1]})     ; +Z up

(defn ^:export manifold-mesh->ridley-mesh
  "Convert a Manifold Mesh back to Ridley mesh format."
  [manifold-mesh]
  (let [vert-props (.-vertProperties manifold-mesh)
        tri-verts (.-triVerts manifold-mesh)
        num-prop (.-numProp manifold-mesh)
        ;; Parse vertices (groups of numProp floats)
        vertices (vec (for [i (range 0 (.-length vert-props) num-prop)]
                        [(aget vert-props i)
                         (aget vert-props (+ i 1))
                         (aget vert-props (+ i 2))]))
        ;; Parse faces (groups of 3 indices)
        faces (vec (for [i (range 0 (.-length tri-verts) 3)]
                     [(aget tri-verts i)
                      (aget tri-verts (+ i 1))
                      (aget tri-verts (+ i 2))]))]
    {:type :mesh
     :vertices vertices
     :faces faces
     :creation-pose default-creation-pose}))

;; ============================================================
;; Manifold operations
;; ============================================================

(defn ^:export mesh->manifold
  "Create a Manifold object from a Ridley mesh.
   Returns nil if the mesh is not valid/manifold.

   The Manifold constructor will attempt to create a valid manifold,
   merging nearly-identical vertices. If the input is too broken,
   it may return an empty manifold."
  [ridley-mesh]
  (when-let [Manifold (get-manifold-class)]
    (when (and (:vertices ridley-mesh) (:faces ridley-mesh))
      (try
        (let [mesh-data (ridley-mesh->manifold-mesh ridley-mesh)
              manifold (new Manifold mesh-data)]
          manifold)
        (catch :default e
          (js/console.error "Failed to create manifold:" e)
          nil)))))

(defn ^:export manifold->mesh
  "Extract the mesh from a Manifold object back to Ridley format."
  [manifold]
  (when manifold
    (let [mesh (.getMesh manifold)]
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
;; Boolean operations
;; ============================================================

(defn- union-two
  "Compute the union of exactly two meshes."
  [mesh-a mesh-b]
  (when (get-manifold-class)
    (let [ma (mesh->manifold mesh-a)
          mb (mesh->manifold mesh-b)]
      (when (and ma mb)
        (let [raw-result (.add ma mb)
              result (.asOriginal raw-result)
              output (manifold->mesh result)]
          (.delete ma)
          (.delete mb)
          (.delete raw-result)
          (.delete result)
          output)))))

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
      (reduce union-two meshes))))

(defn- difference-two
  "Compute the difference of exactly two meshes (A - B)."
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
        (let [raw-result (.subtract ma mb)
              result (.asOriginal raw-result)
              output (manifold->mesh result)]
          (.delete ma)
          (.delete mb)
          (.delete raw-result)
          (.delete result)
          output)))))

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
  "Compute the intersection of exactly two meshes."
  [mesh-a mesh-b]
  (when (get-manifold-class)
    (let [ma (mesh->manifold mesh-a)
          mb (mesh->manifold mesh-b)]
      (when (and ma mb)
        (let [raw-result (.intersect ma mb)
              result (.asOriginal raw-result)
              output (manifold->mesh result)]
          (.delete ma)
          (.delete mb)
          (.delete raw-result)
          (.delete result)
          output)))))

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
                raw-result (.hull Manifold manifold-array)
                result (.asOriginal raw-result)
                output (manifold->mesh result)]
            ;; Clean up
            (doseq [m manifolds]
              (.delete m))
            (.delete raw-result)
            (.delete result)
            output)
          (catch :default e
            (js/console.error "Hull operation failed:" e)
            ;; Clean up on error
            (doseq [m manifolds]
              (.delete m))
            nil))))))

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
  (let [CrossSection (get-cross-section-class)]
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
            result)
          (catch :default e
            (js/console.error "CrossSection extrusion failed:" e)
            nil))))))
