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

(defn- wait-for-manifold-module
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
                 (reset! manifold-state
                         {:wasm wasm
                          :Manifold (.-Manifold wasm)
                          :Mesh (.-Mesh wasm)})
                 @manifold-state)))))

(defn- get-manifold-class
  "Get the Manifold class from initialized state."
  []
  (when-let [state @manifold-state]
    (:Manifold state)))


;; ============================================================
;; Mesh conversion: Ridley -> Manifold
;; ============================================================

(defn ridley-mesh->manifold-mesh
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

(defn manifold-mesh->ridley-mesh
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
     :faces faces}))

;; ============================================================
;; Manifold operations
;; ============================================================

(defn mesh->manifold
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

(defn manifold->mesh
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

(defn union
  "Compute the union of two meshes (A + B).
   Returns a new Ridley mesh."
  [mesh-a mesh-b]
  (when (get-manifold-class)
    (let [ma (mesh->manifold mesh-a)
          mb (mesh->manifold mesh-b)]
      (when (and ma mb)
        (let [result (.add ma mb)
              output (manifold->mesh result)]
          (.delete ma)
          (.delete mb)
          (.delete result)
          output)))))

(defn difference
  "Compute the difference of two meshes (A - B).
   Returns a new Ridley mesh."
  [mesh-a mesh-b]
  (when (get-manifold-class)
    (let [ma (mesh->manifold mesh-a)
          mb (mesh->manifold mesh-b)]
      (when (and ma mb)
        (let [result (.subtract ma mb)
              output (manifold->mesh result)]
          (.delete ma)
          (.delete mb)
          (.delete result)
          output)))))

(defn intersection
  "Compute the intersection of two meshes (A ∩ B).
   Returns a new Ridley mesh."
  [mesh-a mesh-b]
  (when (get-manifold-class)
    (let [ma (mesh->manifold mesh-a)
          mb (mesh->manifold mesh-b)]
      (when (and ma mb)
        (let [result (.intersect ma mb)
              output (manifold->mesh result)]
          (.delete ma)
          (.delete mb)
          (.delete result)
          output)))))
