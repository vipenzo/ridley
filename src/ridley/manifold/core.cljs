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
  (:require [ridley.schema :as schema]
            [ridley.sdf.core :as sdf]
            [ridley.geometry.symmetry :as symmetry]
            [ridley.geometry.cut-candidates :as cut-cand]))

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
                 (if-let [Module (unchecked-get js/globalThis "ManifoldModule")]
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
  "Convert a Manifold Mesh back to Ridley mesh format.
   Also stores raw typed arrays as ::raw-arrays for zero-copy rendering."
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
    (-> (schema/assert-mesh!
         {:type :mesh
          :vertices vertices
          :faces faces
          :creation-pose default-creation-pose})
        ;; Store raw typed arrays for zero-copy Three.js rendering
        (assoc ::raw-arrays {:vert-props (.slice vert-props)
                             :tri-verts (.slice tri-verts)
                             :num-prop num-prop}))))

;; ============================================================
;; Manifold operations
;; ============================================================

(defn ^:export mesh->manifold
  "Create a fresh Manifold WASM object from a Ridley mesh.
   Caller is responsible for calling .delete on the returned object
   to free WASM heap memory."
  [ridley-mesh]
  (when-let [^js Manifold (get-manifold-class)]
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
  [^js manifold]
  (when manifold
    (let [^js mesh (.getMesh manifold)]
      (manifold-mesh->ridley-mesh mesh))))

(defn- status->keyword
  "Map Manifold's .status() return value to a Ridley keyword.
   manifold-3d 3.3.2's ErrorStatus is a plain string ('NoError', 'NotManifold',
   …) — NOT the {value: N} enum object earlier versions (3.0.0, loaded from
   the CDN before this was fixed) returned. Reading `.-value` off a string is
   `undefined`, which silently made every mesh look non-manifold; this landed
   as part of the 3.0.0→3.3.2 CDN/lockfile alignment (dev-docs/brief-mesh-split.md
   Part 0) rather than as a version-drift regression left in place."
  [status-str]
  (case status-str
    "NoError"                      :ok
    "NonFiniteVertex"              :non-finite-vertex
    "NotManifold"                  :not-manifold
    "VertexOutOfBounds"            :vertex-index-out-of-bounds
    "PropertiesWrongLength"        :properties-wrong-length
    "MissingPositionProperties"    :missing-position-properties
    "MergeVectorsDifferentLengths" :merge-vectors-different-lengths
    "MergeIndexOutOfBounds"        :merge-index-out-of-bounds
    "TransformWrongLength"         :transform-wrong-length
    "RunIndexWrongLength"          :run-index-wrong-length
    "FaceIDWrongLength"            :face-id-wrong-length
    "InvalidConstruction"          :invalid-construction
    (keyword (str "unknown-" status-str))))

(defn manifold?
  "Check if a Ridley mesh is manifold (watertight, valid solid).
   Returns true if the mesh can be converted to a valid Manifold
   with non-zero volume."
  [ridley-mesh]
  (when-let [manifold (mesh->manifold ridley-mesh)]
    (let [is-ok (= :ok (status->keyword (.status manifold)))
          volume (.volume manifold)
          has-volume (> volume 0)]
      (.delete manifold)
      (and is-ok has-volume))))

(defn get-mesh-status
  "Get detailed status information about a mesh.
   Returns {:manifold? bool :volume number :surface-area number :status keyword}"
  [ridley-mesh]
  (if-let [manifold (mesh->manifold ridley-mesh)]
    (let [status-kw (status->keyword (.status manifold))
          ;; In manifold-3d v3.0+, getProperties() was replaced with volume() and surfaceArea()
          volume (.volume manifold)
          surface-area (.surfaceArea manifold)
          result {:manifold? (and (= status-kw :ok) (> volume 0))
                  :status status-kw
                  :volume volume
                  :surface-area surface-area}]
      (.delete manifold)
      result)
    {:manifold? false
     :status :failed-to-create
     :volume 0
     :surface-area 0}))

(defn- carry-meta
  "Carry a source mesh's pose/material/anchor metadata onto a derived result
   (boolean, hull, refine, …). The result keeps the source's geometry in place,
   so the source's world-space :creation-pose and :anchors stay valid on it —
   together with the :section-anchors/:rail-path that re-derive composed (:on)
   poses from that pose. This is why a boolean no longer drops named anchors."
  [result source]
  (cond-> result
    (:creation-pose source)   (assoc :creation-pose (:creation-pose source))
    (:material source)        (assoc :material (:material source))
    (:anchors source)         (assoc :anchors (:anchors source))
    (:section-anchors source) (assoc :section-anchors (:section-anchors source))
    (:rail-path source)       (assoc :rail-path (:rail-path source))))

(defn- index-tagged-anchors
  "Build index-tagged copies of `meshes`' anchors: operand i's anchor `:name`
   becomes `:i|name`. Anchors are world poses, so every operand's marks stay
   valid on a boolean result (the geometry is left in place) — this lets the
   marks of ALL operands survive a boolean, not just the first.

   The bare names from operand 0 are kept separately by `carry-meta` (so
   `:at :name` lookups still resolve), so these tagged copies are purely
   additive. Select them uniformly with a regex on the name, e.g.
   `(on-anchors result #\"\\|foot\" …)` to hit every operand's :foot."
  [meshes]
  (apply merge
         (map-indexed
          (fn [i m]
            (reduce-kv (fn [acc k v]
                         (assoc acc (keyword (str i "|" (name k))) v))
                       {} (:anchors m)))
          meshes)))

(defn- carry-indexed-anchors
  "Merge index-tagged anchors of all `meshes` onto a boolean `result`,
   alongside the bare operand-0 anchors that `carry-meta` already kept."
  [result meshes]
  (let [tagged (index-tagged-anchors meshes)]
    (cond-> result
      (and result (seq tagged)) (update :anchors merge tagged))))

;; ============================================================
;; Self-union (resolve self-intersections)
;; ============================================================

(defn solidify
  "Resolve self-intersections in a mesh via boolean self-union (A ∪ A).
   Returns a clean mesh with only the outer surface, or the original
   mesh unchanged if Manifold conversion fails.

   Accepts an SDF node too — materialized via sdf/ensure-mesh (auto-bounds
   + budgeted resolution) before the self-union.

   Usage: (solidify (loft ...)) when a loft produces self-intersecting geometry."
  [ridley-mesh]
  (let [ridley-mesh (sdf/ensure-mesh ridley-mesh)]
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
                output (carry-meta output ridley-mesh)]
            (.delete m1)
            (.delete m2)
            (.delete raw-result)
            (.delete clean)
            (schema/assert-mesh! output))
          (catch :default e
            (js/console.warn "solidify failed:" e)
            ridley-mesh))
        ridley-mesh))))

;; ============================================================
;; Boolean operations
;; ============================================================

(defn- describe-bad-arg
  "Return a short human description of why `a` is not a valid mesh argument."
  [a]
  (cond
    (nil? a)         "nil"
    (sequential? a)  (str "a vector/seq of " (count a) " element(s) — did an extrude/loft return multiple meshes? "
                          "If so, that's now a single mesh in this codebase; "
                          "if you assembled it manually, wrap with concat-meshes or mesh-union first")
    (and (map? a)
         (not= :mesh (:type a))
         (some? (:type a))) (str "a " (name (:type a)) " (expected a mesh)")
    (map? a)         "a map without :vertices/:faces (not a valid mesh)"
    :else            (str (pr-str (type a)))))

(def ^:private sdf-space-equivalent
  "op-label → sdf-* equivalent, for ops that have one (the boolean ops).
   hull and concat-meshes have no SDF-space equivalent, so they get no
   suggestion in the all-SDF warning below."
  {"mesh-union" "sdf-union"
   "mesh-difference" "sdf-difference"
   "mesh-intersection" "sdf-intersection"})

(defn- coerce-to-meshes
  "Convert any SDF nodes in `args` to meshes via sdf/ensure-mesh, and reject
   any non-mesh, non-SDF input with an explicit error (rather than silently
   returning nil downstream).

   `op-label` is the full user-facing name used in error/warning text, e.g.
   \"mesh-union\" or \"concat-meshes\" (matches the bound SCI symbol).

   Warns if every arg is an SDF, suggesting the sdf-* equivalent — only for
   ops that have one (see `sdf-space-equivalent`).

   Note: uses 1-arg ensure-mesh (auto-bounds + auto resolution from the
   SDF tree itself). When you need to override either, materialize the
   SDF yourself before calling the mesh op:
     - (sdf-ensure-mesh sdf ref-mesh) → extend bounds to cover ref-mesh
       (needed for 'infinite' SDFs like gyroid/half-space used as cutters)
     - (sdf-ensure-mesh sdf turtle-res) → override resolution
       (e.g. force finer meshing on a small SDF that ends up coarse when
       fused with a much larger one via mesh-union)
     - (sdf-ensure-mesh sdf ref-mesh turtle-res) → both"
  [op-label args]
  (let [sdf-flags (mapv sdf/sdf-node? args)
        bad (keep-indexed
             (fn [i a]
               (when-not (or (nth sdf-flags i)
                             (and (map? a) (:vertices a) (:faces a)))
                 {:idx i :why (describe-bad-arg a)}))
             args)]
    (when (seq bad)
      (let [{:keys [idx why]} (first bad)]
        (throw (js/Error.
                (str op-label ": argument " (inc idx) " is " why ".")))))
    (when (every? identity sdf-flags)
      (js/console.warn
       (str op-label ": all arguments are SDF nodes."
            (when-let [suggestion (get sdf-space-equivalent op-label)]
              (str " Consider " suggestion " to stay in SDF space (no meshing cost, exact precision).")))))
    (mapv (fn [is-sdf? a] (if is-sdf? (sdf/ensure-mesh a) a))
          sdf-flags args)))

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
              output (carry-meta output mesh-a)]
          (.delete ma)
          (.delete mb)
          (.delete raw-result)
          (.delete result)
          (schema/assert-mesh! output))))))

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
                 (into [first-arg] more))
        meshes (coerce-to-meshes "mesh-union" meshes)]
    (case (count meshes)
      0 nil
      1 (first meshes)
      (carry-indexed-anchors (tree-union (vec meshes)) meshes))))

(defn- difference-two
  "Compute the difference of exactly two meshes (A - B).
   Reuses cached Manifold objects from prior CSG results when available."
  [mesh-a mesh-b]
  (when (get-manifold-class)
    (let [ma (mesh->manifold mesh-a)
          mb (mesh->manifold mesh-b)]
      (when (and ma mb)
        (let [status-a (status->keyword (.status ma))
              status-b (status->keyword (.status mb))]
          (when (not= status-a :ok)
            (js/console.warn "mesh-difference: mesh-a is not manifold, status:" status-a))
          (when (not= status-b :ok)
            (js/console.warn "mesh-difference: mesh-b is not manifold, status:" status-b)))
        (let [^js raw-result (.subtract ma mb)
              ^js result (.asOriginal raw-result)
              output (manifold->mesh result)
              output (carry-meta output mesh-a)]
          (.delete ma)
          (.delete mb)
          (.delete raw-result)
          (.delete result)
          (schema/assert-mesh! output))))))

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
                 (into [first-arg] more))
        meshes (coerce-to-meshes "mesh-difference" meshes)]
    (when (>= (count meshes) 2)
      (carry-indexed-anchors (reduce difference-two meshes) meshes))))

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
              output (carry-meta output mesh-a)]
          (.delete ma)
          (.delete mb)
          (.delete raw-result)
          (.delete result)
          (schema/assert-mesh! output))))))

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
                 (into [first-arg] more))
        meshes (coerce-to-meshes "mesh-intersection" meshes)]
    (when (>= (count meshes) 2)
      (carry-indexed-anchors (reduce intersection-two meshes) meshes))))

(defn hull
  "Compute the convex hull of one or more meshes.
   The convex hull is the smallest convex shape that contains all input meshes.

   Usage:
   (hull mesh)                    ; hull of single mesh
   (hull mesh1 mesh2)             ; hull of multiple meshes
   (hull [mesh1 mesh2 mesh3])     ; hull of vector of meshes

   Returns a new Ridley mesh."
  [& args]
  (let [;; Normalize args: accept both (hull a b c) and (hull [a b c])
        meshes (if (and (= 1 (count args))
                        (vector? (first args)))
                 (first args)
                 args)
        meshes (coerce-to-meshes "mesh-hull" meshes)]
    (when-let [Manifold (get-manifold-class)]
      (let [;; Convert all meshes to Manifold objects
            manifolds (keep mesh->manifold meshes)]
        (when (seq manifolds)
          (try
            (let [;; Manifold.hull() is a static method that takes an array
                  manifold-array (clj->js (vec manifolds))
                  ^js raw-result (.call (.-hull ^js Manifold) Manifold manifold-array)
                  ^js result (.asOriginal raw-result)
                  output (manifold->mesh result)
                  first-mesh (first meshes)
                  output (carry-meta output first-mesh)]
              ;; Clean up inputs
              (doseq [m manifolds]
                (.delete m))
              (.delete raw-result)
              (.delete result)
              (schema/assert-mesh! output))
            (catch :default e
              (js/console.error "Hull operation failed:" e)
              ;; Clean up on error
              (doseq [m manifolds]
                (.delete m))
              nil)))))))

;; ============================================================
;; Plane split (guillotine cut)
;; ============================================================

(defn- split-manifold*
  "Core of both split-by-plane and split-live: split a LIVE Manifold `m` by the
   plane {p : normal·p = offset} into {:ahead :behind :ahead-volume
   :behind-volume}. Reads each raw half's .volume before converting it out (so
   callers don't re-convert for volumes), carry-metas from `source-mesh`, and
   deletes the raw halves — but NOT `m` (the caller owns its lifetime)."
  [^js m normal offset source-mesh]
  (let [[nx ny nz] normal
        ^js pair (.splitByPlane m #js [nx ny nz] offset)
        ^js ahead-raw (aget pair 0)
        ^js behind-raw (aget pair 1)
        extract (fn [^js half]
                  (if (.isEmpty half)
                    {:type :mesh :vertices [] :faces []}
                    (let [^js clean (.asOriginal half)
                          out (manifold->mesh clean)]
                      (.delete clean)
                      out)))
        ahead-vol (.volume ahead-raw)
        behind-vol (.volume behind-raw)
        ahead (carry-meta (extract ahead-raw) source-mesh)
        behind (carry-meta (extract behind-raw) source-mesh)]
    (.delete ahead-raw)
    (.delete behind-raw)
    {:ahead (schema/assert-mesh! ahead)
     :behind (schema/assert-mesh! behind)
     :ahead-volume ahead-vol
     :behind-volume behind-vol}))

(defn split-by-plane
  "Split a mesh by the plane {p : normal·p = offset} into two halves:
   {:ahead <mesh> :behind <mesh>}.

   :ahead  = splitByPlane's FIRST result — the half in the normal's direction.
   :behind = splitByPlane's SECOND result — the opposite half.
   This is the one place the :ahead/:behind mapping is decided; callers
   (e.g. implicit-mesh-split) consume it as-is, never re-deriving it.

   Either half may come back as an empty mesh ({:vertices [] :faces []})
   when the plane misses the mesh entirely, or only grazes it — this is a
   legitimate result, not an error.

   Both halves inherit the source mesh's creation-pose/material/anchors via
   carry-meta — same single-source policy as hull/solidify (this is a
   single-input op, so there is no second operand to index-tag anchors
   against)."
  [ridley-mesh normal offset]
  (when (get-manifold-class)
    (when-let [^js m (mesh->manifold ridley-mesh)]
      (try
        (let [r (split-manifold* m normal offset ridley-mesh)]
          (.delete m)
          r)
        (catch :default e
          (js/console.error "split-by-plane failed:" e)
          (.delete m)
          nil)))))

(defn split-live
  "Split a LIVE Manifold — kept alive by the caller, NOT deleted here — by the
   same plane split-by-plane uses, returning the same
   {:ahead :behind :ahead-volume :behind-volume} map. The keep-alive fast path
   for edit-mesh-split's per-tick recompute (accertamento B2): the current
   piece's Manifold is converted once (when it becomes current) and reused every
   plane nudge, so a keystroke no longer pays the ~8ms mesh→manifold conversion.
   `source-mesh` supplies carry-meta (creation-pose/material/anchors)."
  [^js manifold normal offset source-mesh]
  (try
    (split-manifold* manifold normal offset source-mesh)
    (catch :default e
      (js/console.error "split-live failed:" e)
      nil)))

;; ============================================================
;; Convexity predicate
;; ============================================================

(def ^:private default-convexity-epsilon
  "convex? default epsilon: convex iff vol(mesh)/vol(hull(mesh)) >= 1-epsilon.
   Calibrated from live-measured ratios (REPL, real shapes, real Manifold):
     true cases  — box 1.0, sphere 0.99999999 (even at 8x6 coarse tessellation),
                   hex prism 1.0, cylinder 1.0 (fine 64-seg and coarse 6-seg alike)
     false cases — frame (box-minus-box) 0.875, L-shaped prism 0.857, torus 0.655
   A tessellated smooth-convex shape's vertices all lie ON its own hull by
   construction, so its ratio never meaningfully drifts below 1 regardless of
   tessellation coarseness — the true/false clusters are ~0.12 apart at the
   closest point (0.875 vs 0.99999999). 0.01 sits with a wide margin on both
   sides."
  0.01)

(defn convex?
  "Convexity test via hull-ratio: true iff
     volume(mesh) / volume(hull(mesh)) >= 1 - epsilon.
   Optional second arg overrides the default epsilon (mirrors
   auto-face-groups' optional-positional-threshold style).

   An empty mesh (no faces) is convex by definition (the empty set is
   convex) — returns true without touching Manifold.

   Accepts a mesh or an SDF node (auto-materialized).

   (convex? mesh)          ; default epsilon
   (convex? mesh 0.01)     ; custom epsilon"
  ([ridley-mesh] (convex? ridley-mesh default-convexity-epsilon))
  ([ridley-mesh epsilon]
   (let [ridley-mesh (sdf/ensure-mesh ridley-mesh)]
     (if (empty? (:faces ridley-mesh))
       true
       (when-let [^js Manifold (get-manifold-class)]
         (when-let [^js m (mesh->manifold ridley-mesh)]
           (try
             (let [vol-mesh (.volume m)
                   ^js hull-raw (.call (.-hull Manifold) Manifold (clj->js [m]))
                   vol-hull (.volume hull-raw)]
               (.delete hull-raw)
               (.delete m)
               (>= (/ vol-mesh vol-hull) (- 1 epsilon)))
             (catch :default e
               (js/console.error "convex? failed:" e)
               (.delete m)
               false))))))))

(defn split-parts
  "Flatten a mesh-split composite result into its leaves, in order:
   depth-first, :behind before :ahead at each node. A bare mesh (no
   composite structure — the primitive's own single-cut return, or
   whatever a caller passes that isn't a {:behind :ahead} node) returns
   [mesh]. A hand-built tree (a :behind that is itself a node) is walked
   the same way, so this generalizes for free if mesh-split ever stops
   being a linear chain."
  [result]
  (if (= :mesh (:type result))
    [result]
    (into (split-parts (:behind result)) (split-parts (:ahead result)))))

;; ============================================================
;; Connected-component decomposition
;; ============================================================

(defn- mesh-centroid
  "Vertex-mean centroid [cx cy cz] of a Ridley mesh. It is a sum over the
   vertex set divided by its count, so it is INVARIANT to vertex order and
   thus to any construction-order permutation of the source mesh — which is
   what lets it serve as a deterministic tie-break for mesh-components. Empty
   mesh → [0 0 0]."
  [ridley-mesh]
  (let [vs (:vertices ridley-mesh)
        n (count vs)]
    (if (zero? n)
      [0.0 0.0 0.0]
      (let [sums (reduce (fn [acc v]
                           (aset acc 0 (+ (aget acc 0) (v 0)))
                           (aset acc 1 (+ (aget acc 1) (v 1)))
                           (aset acc 2 (+ (aget acc 2) (v 2)))
                           acc)
                         (js/Array. 0.0 0.0 0.0) vs)]
        [(/ (aget sums 0) n) (/ (aget sums 1) n) (/ (aget sums 2) n)]))))

(defn- order-components
  "Sort component entries {:mesh :volume :centroid} into the mesh-components
   contract order: decreasing volume, then lexicographic (x,y,z) on the
   centroid. Both keys are order-independent (volume and vertex-mean centroid),
   so the resulting vector is invariant to construction/vertex-order
   permutation of the source mesh. Pure — unit-testable without Manifold."
  [entries]
  (mapv :mesh
        (sort-by (fn [{:keys [volume centroid]}]
                   (into [(- volume)] centroid))
                 entries)))

(defn mesh-components
  "Decompose a mesh into its connected components via Manifold's Decompose()
   (topological union-find on connected components — exact, no boolean, and
   cheap: <1 ms up to ~12800 tris, ~linear in vertex count).

   Returns a VECTOR of meshes in a DETERMINISTIC contract order: decreasing
   volume, tie-broken by lexicographic comparison of the vertex-mean centroid
   (x, then y, then z). The order is a contract, not an incidental detail:
   tree emission destructures positionally ((let [[a b] (mesh-components x)] …)),
   and Manifold's Decompose() order is implementation-defined — so the ordering
   lives HERE in the DSL, and the docstring declares it. Without this a re-entry
   could silently swap the pieces.

   Cases: a single-component mesh → a one-element vector [mesh] (the whole
   thing); an empty mesh (no faces) → [] (documented degenerate case). Each
   component inherits the source mesh's creation-pose/material/anchors via the
   same single-source carry-meta policy as split-by-plane/hull.

   Accepts a mesh or an SDF node (auto-materialized). On a Manifold failure it
   degrades to [mesh] (treat the source as one whole component) rather than nil,
   so callers can always destructure the result."
  [ridley-mesh]
  (let [ridley-mesh (sdf/ensure-mesh ridley-mesh)]
    (if (empty? (:faces ridley-mesh))
      []
      (if-let [^js m (mesh->manifold ridley-mesh)]
        (try
          (let [^js parts (.decompose m)
                n (.-length parts)
                entries (loop [i 0, acc (transient [])]
                          (if (< i n)
                            (let [^js part (aget parts i)
                                  vol (.volume part)
                                  mesh (carry-meta (manifold->mesh part) ridley-mesh)
                                  entry {:mesh (schema/assert-mesh! mesh)
                                         :volume vol
                                         :centroid (mesh-centroid mesh)}]
                              (.delete part)
                              (recur (inc i) (conj! acc entry)))
                            (persistent! acc)))]
            (.delete m)
            (order-components entries))
          (catch :default e
            (js/console.error "mesh-components failed:" e)
            (.delete m)
            [ridley-mesh]))
        [ridley-mesh]))))

(defn component-report
  "One-pass report for the mesh-split semaphore/badge: decompose once, then
   test each component's convexity. Returns
     {:count N :finished? bool :components [mesh …]}
   where finished? = EVERY connected component is convex (the tree-session
   'green' criterion — accertamento A4: the perennial red was multi-component
   pieces and genuine concavities, never an epsilon problem, so the threshold
   is unchanged). Empty mesh → {:count 0 :finished? true :components []}.

   Budget (A1/A4, live per-keystroke): decompose <1 ms + ~2.6 ms/component."
  ([ridley-mesh] (component-report ridley-mesh default-convexity-epsilon))
  ([ridley-mesh epsilon]
   (let [components (mesh-components ridley-mesh)]
     {:count (count components)
      :finished? (every? #(convex? % epsilon) components)
      :components components})))

(defn finished?
  "The tree-decomposition 'finished' predicate (green light): true iff EVERY
   connected component of the mesh is convex. Replaces the plain convex? test
   for the mesh-split semaphore — a piece that decomposes into several convex
   components (a U cut at its base → two convex prongs in one mesh) is already
   finished; its separation is a matter of emission, not geometry. Empty mesh
   and single convex mesh → true. Uses convex?'s own epsilon."
  ([ridley-mesh] (:finished? (component-report ridley-mesh)))
  ([ridley-mesh epsilon] (:finished? (component-report ridley-mesh epsilon))))

;; ============================================================
;; Mirror & symmetry (dev-docs/brief-mesh-symmetry.md)
;; ============================================================

(defn mirror-by-plane
  "Reflect a mesh through the plane {x : normal·(x−point) = 0} — native Manifold
   .mirror(normal), which is winding-correct (accertamento B6: positive volume,
   NoError, no manual flip). For a plane off the origin it composes
   translate(−point) ∘ mirror(normal) ∘ translate(+point) (validated B7).
   carry-meta from the source (single-input op). Accepts a mesh or an SDF node."
  [ridley-mesh normal point]
  (let [ridley-mesh (sdf/ensure-mesh ridley-mesh)]
    (when (get-manifold-class)
      (when-let [^js m (mesh->manifold ridley-mesh)]
        (try
          (let [[nx ny nz] normal
                [px py pz] point
                ^js m1 (.translate m #js [(- px) (- py) (- pz)])
                ^js m2 (.mirror m1 #js [nx ny nz])
                ^js m3 (.translate m2 #js [px py pz])
                out (carry-meta (manifold->mesh m3) ridley-mesh)]
            (.delete m) (.delete m1) (.delete m2) (.delete m3)
            (schema/assert-mesh! out))
          (catch :default e
            (js/console.error "mirror-by-plane failed:" e)
            (.delete m)
            nil))))))

(def ^:private mirror-volume-gate
  "mirror?/symmetry-planes free pre-gate: the two halves of the split at the plane
   must have volumes equal within this relative tolerance before paying the
   symmetric-difference confirmation. The gate is already computed by the semaphore
   (accertamento B6) and rejects the vast majority of non-mirrors for free."
  0.02)

(def ^:private default-mirror-epsilon
  "mirror? default: symmetric iff the symmetric-difference ratio ≤ epsilon. B6
   measured 0 (true) vs 0.87 (false) — a huge margin — so the threshold is set by
   the NEAR-symmetric case (a symmetric object with a small off-axis feature): a
   feature that removes a fraction f of a half's volume shows a symdiff ratio ≈ 2f,
   so 0.02 rejects an asymmetry above ~1% of a half. CALIBRATION NOTE: measure on
   2–3 near-symmetric shapes live before trusting this number (brief Part 2)."
  0.02)

(defn- mirror-ratio
  "The symmetric-difference ratio of `ridley-mesh` about the plane (normal,point):
   split at the plane, reflect the :behind half through it, and return
   (vol(union) − vol(intersection)) / vol(ahead) — ≈ 0 for a true mirror. Returns
   js/Infinity when the free volumetric gate fails (unequal halves), or nil on a
   degenerate/failed computation."
  [ridley-mesh normal point]
  (when (get-manifold-class)
    (try
      (let [offset (+ (* (nth normal 0) (nth point 0))
                      (* (nth normal 1) (nth point 1))
                      (* (nth normal 2) (nth point 2)))
            {:keys [behind ahead behind-volume ahead-volume]} (split-by-plane ridley-mesh normal offset)
            vb (or behind-volume 0) va (or ahead-volume 0)
            vtot (+ vb va)]
        (cond
          (<= vtot 1e-9) nil                                   ; plane misses the mesh
          (> (/ (js/Math.abs (- vb va)) vtot) mirror-volume-gate) js/Infinity  ; gate
          :else
          (let [behind' (mirror-by-plane behind normal point)
                u (union behind' ahead)
                i (intersection behind' ahead)
                vu (:volume (get-mesh-status u))
                vi (:volume (get-mesh-status i))]
            (/ (- vu vi) (max 1e-9 va)))))
      (catch :default e
        (js/console.error "mirror-ratio failed:" e)
        nil))))

(defn symmetric-about-plane?
  "True iff `ridley-mesh` is mirror-symmetric about the plane {x : normal·(x−point)
   = 0} — the B6 cascade: free volumetric gate (equal halves) then symmetric-
   difference confirmation. Optional epsilon on the ratio (like convex?). An empty
   mesh is symmetric by definition → true. Cost 77–148 ms: on-demand, never
   per-keystroke."
  ([ridley-mesh normal point] (symmetric-about-plane? ridley-mesh normal point default-mirror-epsilon))
  ([ridley-mesh normal point epsilon]
   (let [ridley-mesh (sdf/ensure-mesh ridley-mesh)]
     (if (empty? (:faces ridley-mesh))
       true
       (when-let [r (mirror-ratio ridley-mesh normal point)]
         (<= r epsilon))))))

(defn mirror-difference-ratio
  "The symmetric-difference ratio of `ridley-mesh` about the plane {x : normal·(x−point)=0}
   — 0 for an exact mirror, ~2·(asymmetric fraction of a half) otherwise, js/Infinity when
   the free volumetric gate rejects it (unequal halves), nil on a degenerate/failed
   computation. The number that backs symmetric-about-plane?/symmetry-planes' verdict,
   exposed so a caller can show HOW symmetric a plane is (exact vs 'quasi'), not just
   whether. Same cost as symmetric-about-plane? — on-demand, never per-keystroke."
  [ridley-mesh normal point]
  (mirror-ratio (sdf/ensure-mesh ridley-mesh) normal point))

(defn symmetry-planes
  "Verified mirror-symmetry planes of a mesh (brief Part 3), as a vector of poses
   {:position :heading :up :symmetry-ratio r} (heading = plane normal, ready for
   goto/mark; the extra :symmetry-ratio is the symmetric-difference fraction — 0 for
   an exact mirror, up to `epsilon` — so a caller can show HOW symmetric a plane is,
   e.g. a part that's mirror EXCEPT a small internal feature registers with a small
   non-zero ratio). Ordered by quality (ratio ascending). A pure function of the
   mesh: bbox-axis + area-weighted-PCA candidates (ridley.geometry.symmetry,
   tessellation-invariant — B7), each VERIFIED by the B6 cascade; only the promoted
   are returned (contract: verified planes only). Empty/degenerate mesh → []. Cost
   ~250–450 ms: on-demand."
  ([ridley-mesh] (symmetry-planes ridley-mesh default-mirror-epsilon))
  ([ridley-mesh epsilon]
   (let [ridley-mesh (sdf/ensure-mesh ridley-mesh)]
     (->> (symmetry/candidate-planes ridley-mesh)
          (keep (fn [{:keys [heading position] :as pose}]
                  (when-let [r (mirror-ratio ridley-mesh heading position)]
                    (when (<= r epsilon) (assoc pose :symmetry-ratio r)))))
          (sort-by :symmetry-ratio)
          vec))))

;; ============================================================
;; Smoothing & refinement (Manifold tangent-based subdivision)
;; ============================================================

(defn ^:export mesh-refine
  "Subdivide each triangle of a mesh into n^2 sub-triangles via Manifold's
   refine. With no preceding mesh-smooth call this is a linear (planar)
   subdivision — the shape is unchanged, you just get more vertices.
   When chained after mesh-smooth, the same call uses the stored halfedge
   tangents to interpolate curved vertex positions, which is what produces
   the rounded result.

   (mesh-refine m 2)   ; each triangle -> 4 sub-triangles
   (mesh-refine m 3)   ; each triangle -> 9 sub-triangles"
  [ridley-mesh n]
  (when-let [_ (get-manifold-class)]
    (when (and (:vertices ridley-mesh) (:faces ridley-mesh) (>= n 2))
      (try
        (let [^js m (mesh->manifold ridley-mesh)
              ^js refined (.refine m n)
              output (manifold->mesh refined)
              output (carry-meta output ridley-mesh)]
          (.delete m)
          (.delete refined)
          (schema/assert-mesh! output))
        (catch :default e
          (js/console.error "mesh-refine failed:" e)
          ridley-mesh)))))

(defn ^:export mesh-smooth
  "Round off non-sharp edges of a mesh using Manifold's tangent-based
   smoothing + subdivision. The right tool when a procedurally generated
   mesh has visible staircase aliasing along a regular grid (such as the
   silhouette of a voronoi shell), or when you want to soften every crease
   softer than a given dihedral angle while preserving sharper intentional
   edges.

   How it works: smoothOut stores Bezier-tangent data on each halfedge
   based on the dihedral angle of its two adjacent faces; refine then
   subdivides each triangle into n^2 sub-triangles, placing the new
   vertices along the tangent curves instead of straight lines. The result
   is a denser mesh whose surface is C^1 wherever the dihedral angle was
   <= :sharp-angle.

   Options:
     :sharp-angle  threshold in degrees (default 100). Edges whose dihedral
                   angle is GREATER than this stay sharp; the rest get
                   smoothed. Manifold's stock default is 60, but for
                   typical procedural meshes you'll want 90-120 so that
                   right-angle wall corners (e.g. the hole edges of a
                   voronoi shell) get smoothed instead of preserved. Set
                   to 180 to smooth absolutely everything.
     :smoothness   0..1 (default 0). How much fillet to apply at the edges
                   that survive as sharp. 0 leaves them perfectly sharp;
                   1 turns them fully smooth (equivalent to :sharp-angle 180).
     :refine       subdivision count after smoothing (default 3). Each
                   triangle becomes n^2 sub-triangles. Higher = visually
                   smoother but quadratically more triangles.

   (mesh-smooth m)                                  ; sharp-angle 100, refine 3
   (mesh-smooth m :sharp-angle 120 :refine 4)       ; smooth more, denser
   (mesh-smooth m :sharp-angle 60)                  ; preserve all >60deg corners
   (mesh-smooth m :sharp-angle 180)                 ; round absolutely everything

   Note: vertex/face count grows by n^2 per refine pass — start with the
   defaults and only crank :refine if you still see facets."
  [ridley-mesh & {:keys [sharp-angle smoothness refine]
                  :or {sharp-angle 100 smoothness 0 refine 3}}]
  (when-let [_ (get-manifold-class)]
    (when (and (:vertices ridley-mesh) (:faces ridley-mesh))
      (try
        (let [^js m (mesh->manifold ridley-mesh)
              ;; smoothOut stores tangents but does NOT change geometry —
              ;; you must call refine() afterward to materialize the smoothing.
              ^js smoothed (.smoothOut m sharp-angle smoothness)
              ^js refined  (if (and refine (>= refine 2))
                             (.refine smoothed refine)
                             smoothed)
              output (manifold->mesh refined)
              output (carry-meta output ridley-mesh)]
          (.delete m)
          (when-not (identical? smoothed refined) (.delete smoothed))
          (.delete refined)
          (schema/assert-mesh! output))
        (catch :default e
          (js/console.error "mesh-smooth failed:" e)
          ridley-mesh)))))

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
                output (carry-meta output first-mesh-arg)]
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
   Unlike mesh-union, this does NOT perform a boolean operation — it
   simply merges the geometry in linear time.

   When the input meshes are **disjoint** (no overlap), the result is a
   valid manifold and can be passed directly to mesh-difference/-union/
   -intersection as a single tool, faster than the equivalent N-1
   sequential boolean operations. Typical use: arrays of holes (grids,
   rings, polka dots) where each cutter sits in its own region.

   When inputs overlap, the result has interior faces and is not
   manifold-valid — boolean operations on it produce artefacts (mis-
   oriented faces in the cut region). Use mesh-union instead in that
   case.

   The result is always fine for heightmap sampling and visualization,
   regardless of overlap.

   Usage:
   (concat-meshes [mesh1 mesh2 mesh3])
   (concat-meshes mesh1 mesh2 mesh3)"
  [& args]
  (let [meshes (if (and (= 1 (count args)) (sequential? (first args)))
                 (first args)
                 args)
        meshes (coerce-to-meshes "concat-meshes" meshes)]
    (when (seq meshes)
      (loop [remaining (seq meshes)
             all-verts []
             all-faces []
             all-cap-images []
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
               (:material (first meshes)) (assoc :material (:material (first meshes)))
               ;; Carry reference-image decals (set-image → extrude) through the
               ;; merge: their tris/uv index into :vertices, so shift them by each
               ;; sub-mesh's base offset. Lets many imaged caps (e.g. on-anchors
               ;; :concat over distinct photos) survive as one mesh.
               (seq all-cap-images) (assoc :cap-images all-cap-images))))
          (let [m (first remaining)
                verts (:vertices m)
                faces (:faces m)
                shifted (mapv (fn [face] (mapv #(+ % offset) face)) faces)
                shifted-cap-images
                (mapv (fn [e]
                        (-> e
                            (update :tris (fn [tris]
                                            (mapv (fn [t] (mapv #(+ % offset) t)) tris)))
                            (update :uv (fn [uv]
                                          (persistent!
                                           (reduce-kv (fn [acc k v] (assoc! acc (+ k offset) v))
                                                      (transient {}) uv))))))
                      (:cap-images m))]
            (recur (next remaining)
                   (into all-verts verts)
                   (into all-faces shifted)
                   (into all-cap-images shifted-cap-images)
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
                                (dissoc ::manifold-cache ::raw-arrays))
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

;; ============================================================
;; Cut candidates — section-area reader + translation generator
;; (dev-docs/brief-cut-candidates.md Parts 1-2; accertamento fase 2 B1/B2/B3/B5).
;; The pure, tessellation-exact parts (vertex offsets, coplanar-face steps, profile
;; minima) live in ridley.geometry.cut-candidates; THIS is the WASM orchestration —
;; native CrossSection .area() and the B2 keep-alive (convert once, slice many).
;; ============================================================

(defn- transformed-manifold
  "A live Manifold with `ridley-mesh` moved into the plane frame (point→origin,
   normal→+Z, right→+X, up→+Y), built ONCE so the caller can `.slice(z).area()` at
   many z cheaply (B2: the ~8ms mesh→manifold conversion is the whole cost, so pay it
   once). In this frame a plane at signed offset o along `normal` from `point` is
   Z=o. Caller MUST `.delete` the result. nil if conversion fails."
  [ridley-mesh [nx ny nz] [px py pz] [rx ry rz] [ux uy uz]]
  (let [xform (fn [[vx vy vz]]
                (let [dx (- vx px) dy (- vy py) dz (- vz pz)]
                  [(+ (* rx dx) (* ry dy) (* rz dz))
                   (+ (* ux dx) (* uy dy) (* uz dz))
                   (+ (* nx dx) (* ny dy) (* nz dz))]))]
    (mesh->manifold (-> ridley-mesh
                        (assoc :vertices (mapv xform (:vertices ridley-mesh)))
                        (dissoc ::manifold-cache ::raw-arrays)))))

(defn section-area
  "Area of the cross-section of `ridley-mesh` with the plane {point, normal} — the
   perceptual signal the cut-candidate profile is built from (brief Part 1), via
   Manifold's native CrossSection `.area()` (B1). A plane that misses the mesh → 0
   (documented). Reader of the bounds/area family; accepts a mesh or an SDF node."
  [ridley-mesh normal point]
  (or (when (get-manifold-class)
        (let [mesh (sdf/ensure-mesh ridley-mesh)]
          (when (and (:vertices mesh) (seq (:faces mesh)))
            (let [[right up _] (compute-basis normal)]
              (when-let [^js m (transformed-manifold mesh normal point right up)]
                (try
                  (let [^js cs (.slice m 0)
                        a (.area cs)]
                    (.delete cs) (.delete m)
                    a)
                  (catch :default e
                    (js/console.error "section-area failed:" e)
                    (.delete m)
                    0)))))))
      0))

(defn translation-profile
  "Sample the section area A(offset) as `ridley-mesh` is swept along `heading` from
   `point`, over the vertex offset-range, at `samples` points (ascending). Converts
   once, slices many (B2, ~0.04 ms/sample). Returns [{:offset o :area a} …], or []
   for an empty mesh / failed conversion. Feeds the neck detector and the panel
   strip (Part 4). The in-plane basis is compute-basis's own orthonormal right/up —
   area is rotation-invariant in the plane, so the caller's `up` is irrelevant here
   (and mixing it with compute-basis's `right` would skew the transform)."
  [ridley-mesh heading point samples]
  (or (when (get-manifold-class)
        (let [mesh (sdf/ensure-mesh ridley-mesh)]
          (when-let [[o-min o-max] (and (seq (:vertices mesh))
                                        (cut-cand/offset-range (:vertices mesh) heading point))]
            (let [[right up _] (compute-basis heading)]
              (when-let [^js m (transformed-manifold mesh heading point right up)]
                (try
                  (let [span (- o-max o-min)
                        n (max 2 samples)
                        out (mapv (fn [i]
                                    (let [o (+ o-min (* (/ i (dec n)) span))
                                          ^js cs (.slice m o)
                                          a (.area cs)]
                                      (.delete cs)
                                      {:offset o :area a}))
                                  (range n))]
                    (.delete m)
                    out)
                  (catch :default e
                    (js/console.error "translation-profile failed:" e)
                    (.delete m)
                    [])))))))
      []))

(defn- v-normalize [[x y z]]
  (let [m (js/Math.sqrt (+ (* x x) (* y y) (* z z)))]
    (if (> m 1e-12) [(/ x m) (/ y m) (/ z m)] [0 0 0])))
(defn- v-cross [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by)) (- (* az bx) (* ax bz)) (- (* ax by) (* ay bx))])

(defn rotation-profile
  "Sample the section area A(θ) as the plane pivots about `axis-kw` (∈ {:up :right})
   through `point`, the normal starting at `heading`, over θ ∈ [−π/2, π/2) at `samples`
   points (ascending). Converts to the θ=0 frame ONCE (heading→Z, up→Y, right→X) then
   `.rotate(−θ)`+`.slice(0).area()` per angle (B2, ~0.6 ms/sample — ~15× the
   translational profile, so on-demand/debounced, not per-tick). Returns
   [{:offset θ :area a} …] (θ radians in :offset, sharing profile-minima's shape)."
  [ridley-mesh heading point up axis-kw samples]
  (or (when (get-manifold-class)
        (let [mesh (sdf/ensure-mesh ridley-mesh)]
          (when (seq (:faces mesh))
            ;; right = up × heading so [right, up, heading] is RIGHT-handed — heading×up
            ;; would make the transform a reflection (inside-out manifold → empty slices)
            (let [right (v-normalize (v-cross up heading))]
              (when-let [^js base (transformed-manifold mesh heading point right up)]
                (try
                  (let [n (max 2 samples)
                        deg (/ 180.0 js/Math.PI)
                        out (mapv (fn [i]
                                    (let [theta (+ (- (/ js/Math.PI 2.0)) (* (/ i n) js/Math.PI))
                                          td (* theta deg)
                                          ;; rotate the mesh by −θ about the axis so the
                                          ;; θ-plane maps to the fixed Z=0 slice
                                          ^js rot (case axis-kw
                                                    :up (.rotate base 0 (- td) 0)
                                                    :right (.rotate base (- td) 0 0))
                                          ^js cs (.slice rot 0)
                                          a (.area cs)]
                                      (.delete cs) (.delete rot)
                                      {:offset theta :area a}))
                                  (range n))]
                    (.delete base)
                    out)
                  (catch :default e
                    (js/console.error "rotation-profile failed:" e)
                    (.delete base)
                    [])))))))
      []))

(defn cut-candidates
  "Cut-candidate poses for `ridley-mesh` at a pose (brief Part 2), a PURE function of
   (mesh, pose, opts) — no session state (B5); the DSL wrapper reads the turtle to
   fill the pose. opts:
     :mode      :translation (default) | :rotation
     :heading :position :up   the current pose
     :axis      :up (default) | :right   — rotation axis (:heading would not move the
                plane → readable error)
     :tolerance 0.1   dedup for coplanar step offsets (mm)
     :angle-tol 1.0   how ∥ to heading a face normal must be to count as a step (deg)
     :samples   96    profile samples
     :min-neck-depth   valley-depth floor (default 1% of the profile's peak area)
   Returns [{:pose {:position :heading :up} :kind :step|:neck :salience n} …] sorted
   by salience DESCENDING (B3: off-axis meshes have hundreds of candidates; the caller
   filters by salience). Translation: exact coplanar-face STEPs (|ΔA|) + sampled
   NECKs. Rotation: coplanar-through-axis STEPs (rare) + A(θ)-minimum NECKs (the
   practical 'rotate to the thin section' signal)."
  [ridley-mesh {:keys [mode heading position up axis tolerance angle-tol samples min-neck-depth]
                :or {mode :translation axis :up tolerance 0.1 angle-tol 1.0 samples 96}}]
  (case mode
    :translation
    (let [mesh (sdf/ensure-mesh ridley-mesh)
          steps (cut-cand/step-candidates mesh heading position
                                          {:tol tolerance :angle-tol angle-tol})
          profile (translation-profile mesh heading position samples)
          peak (reduce max 0.0 (map :area profile))
          necks (cut-cand/profile-minima profile (or min-neck-depth (* 0.01 peak)))
          ->cand (fn [kind {:keys [offset salience]}]
                   {:pose (cut-cand/offset->pose offset heading up position)
                    :kind kind :salience salience :at offset})]
      (->> (concat (map (partial ->cand :step) steps)
                   (map (partial ->cand :neck) necks))
           (sort-by :salience >)
           vec))
    :rotation
    (do
      (when (= axis :heading)
        (throw (js/Error. "cut-candidates: :axis :heading non ruota il piano di taglio — usa :up o :right")))
      (when-not (#{:up :right} axis)
        (throw (js/Error. (str "cut-candidates: :axis sconosciuto " (pr-str axis) " — usa :up o :right"))))
      (let [mesh (sdf/ensure-mesh ridley-mesh)
            axis-vec (case axis :up up :right (v-normalize (v-cross heading up)))
            steps (cut-cand/rotation-step-candidates mesh heading position axis-vec
                                                     {:angle-tol angle-tol :pos-tol tolerance})
            profile (rotation-profile mesh heading position up axis samples)
            peak (reduce max 0.0 (map :area profile))
            necks (cut-cand/profile-minima profile (or min-neck-depth (* 0.01 peak)))
            ->cand (fn [kind {:keys [offset salience]}]
                     {:pose (cut-cand/angle->pose offset heading up position axis-vec)
                      :kind kind :salience salience :at offset})]
        (->> (concat (map (partial ->cand :step) steps)
                     (map (partial ->cand :neck) necks))
             (sort-by :salience >)
             vec)))
    (throw (js/Error. (str "cut-candidates: :mode sconosciuto " (pr-str mode))))))

(defn ^:export project-at-plane
  "Project a mesh onto an arbitrary plane, returning the silhouette outline.

   Same arguments as slice-at-plane (point + normal + optional right/up basis).
   Returns a vector of 2D shapes representing the projected silhouette in the
   plane's local coordinates (X = right, Y = up). Holes are preserved.

   Whereas slice-at-plane gives the cross-section AT a plane, this gives the
   shadow OF the mesh as seen looking along the normal direction.

   (project-at-plane mesh [0 0 1] [0 0 0])  ; top-down silhouette
   (project-at-plane mesh [1 0 0] [0 0 0])  ; side-view silhouette"
  ([ridley-mesh normal point]
   (let [[right up _] (compute-basis normal)]
     (project-at-plane ridley-mesh normal point right up)))
  ([ridley-mesh normal point right up]
   (when (get-manifold-class)
     (when (and (:vertices ridley-mesh) (:faces ridley-mesh))
       (try
         (let [[px py pz] point
               [rx ry rz] right
               [ux uy uz] up
               [nx ny nz] normal
               xform-vert (fn [[vx vy vz]]
                            (let [dx (- vx px) dy (- vy py) dz (- vz pz)]
                              [(+ (* rx dx) (* ry dy) (* rz dz))
                               (+ (* ux dx) (* uy dy) (* uz dz))
                               (+ (* nx dx) (* ny dy) (* nz dz))]))
               xformed-verts (mapv xform-vert (:vertices ridley-mesh))
               xformed-mesh (-> ridley-mesh
                                (assoc :vertices xformed-verts)
                                (dissoc ::manifold-cache ::raw-arrays))
               ^js m (mesh->manifold xformed-mesh)]
           (when m
             ;; Manifold 3.3.2's high-level .project() wrapper has a bug — it
             ;; returns an empty CrossSection. We use the low-level ._Project()
             ;; directly. Additionally, on low-poly meshes from libfive the raw
             ;; projection comes out fragmented into many overlapping polygons,
             ;; so we feed those through Module.CrossSection(polys, "Positive")
             ;; which performs the boolean union and gives a single merged
             ;; silhouette.
             (let [^js pv (._Project m)
                   n-polys (.size pv)
                   raw-polys-js (clj->js
                                 (vec (for [i (range n-polys)
                                            :let [^js p (.get pv i)
                                                  sz (.size p)]
                                            :when (>= sz 3)]
                                        (vec (for [j (range sz)
                                                   :let [^js pt (.get p j)]]
                                               [(.-x pt) (.-y pt)])))))
                   ;; Dispose the C++ vec we no longer need.
                   _ (do (dotimes [i n-polys] (.delete (.get pv i)))
                         (.delete pv))
                   ;; High-level CrossSection wrapper performs the union.
                   ^js cs-cls (:CrossSection @manifold-state)
                   ^js cs (cs-cls raw-polys-js "Positive")
                   ^js polys (.toPolygons cs)
                   contours (vec
                             (for [i (range (.-length polys))
                                   :let [^js poly (aget polys i)
                                         pts (vec (for [j (range (.-length poly))
                                                        :let [^js pt (aget poly j)]]
                                                    [(aget pt 0) (aget pt 1)]))]
                                   :when (>= (count pts) 3)]
                               pts))
                   signed-area (fn [pts]
                                 (let [n (count pts)]
                                   (* 0.5
                                      (reduce
                                       (fn [sum i]
                                         (let [[x1 y1] (nth pts i)
                                               [x2 y2] (nth pts (mod (inc i) n))]
                                           (+ sum (- (* x1 y2) (* x2 y1)))))
                                       0 (range n)))))
                   outers (filterv #(pos? (signed-area %)) contours)
                   holes  (filterv #(neg? (signed-area %)) contours)
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
               (.delete cs)
               (.delete m)
               shapes)))
         (catch :default e
           (js/console.error "project-at-plane failed:" e)
           nil))))))
