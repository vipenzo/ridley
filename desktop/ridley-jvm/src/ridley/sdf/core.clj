(ns ridley.sdf.core
  "SDF operations via libfive (Rust backend).
   SDF nodes are pure data — descriptions of implicit surfaces.
   Meshing is lazy: happens automatically at register/boolean/export boundaries."
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]))

(def ^:private server-url "http://127.0.0.1:12321")

;; ── SDF detection ───────────────────────────────────────────────

(defn sdf-node?
  "Returns true if x is an SDF tree node (not a mesh)."
  [x]
  (and (map? x) (string? (:op x))))

;; ── SDF node constructors (pure data) ───────────────────────────

(defn sdf-sphere [r] {:op "sphere" :r (double r)})
(defn sdf-box [sx sy sz] {:op "box" :sx (double sx) :sy (double sy) :sz (double sz)})
(defn sdf-cyl [r h] {:op "cyl" :r (double r) :h (double h)})

;; ── SDF boolean operations ──────────────────────────────────────

(defn sdf-union [a b] {:op "union" :a a :b b})
(defn sdf-difference [a b] {:op "difference" :a a :b b})
(defn sdf-intersection [a b] {:op "intersection" :a a :b b})

;; ── SDF-only operations ─────────────────────────────────────────

(defn sdf-blend [a b k] {:op "blend" :a a :b b :k (double k)})
(defn sdf-shell [a thickness] {:op "shell" :a a :thickness (double thickness)})
(defn sdf-offset [a amount] {:op "offset" :a a :amount (double amount)})
(defn sdf-morph [a b t] {:op "morph" :a a :b b :t (double t)})

;; ── SDF transforms ──────────────────────────────────────────────

(defn sdf-move [node dx dy dz]
  {:op "move" :a node :dx (double dx) :dy (double dy) :dz (double dz)})

;; ── Bounds estimation ───────────────────────────────────────────

(defn auto-bounds
  "Estimate spatial bounds from an SDF tree. Returns [[xmin xmax] [ymin ymax] [zmin zmax]]."
  [node]
  (case (:op node)
    "sphere" (let [r (:r node) m (* r 1.2)]
               [[(- m) m] [(- m) m] [(- m) m]])
    "box" (let [hx (* (:sx node) 0.6) hy (* (:sy node) 0.6) hz (* (:sz node) 0.6)]
             [[(- hx) hx] [(- hy) hy] [(- hz) hz]])
    "cyl" (let [r (* (:r node) 1.2) hh (* (:h node) 0.6)]
             [[(- r) r] [(- r) r] [(- hh) hh]])
    "move" (let [b (auto-bounds (:a node))
                  dx (:dx node) dy (:dy node) dz (:dz node)]
              [[(+ (get-in b [0 0]) dx) (+ (get-in b [0 1]) dx)]
               [(+ (get-in b [1 0]) dy) (+ (get-in b [1 1]) dy)]
               [(+ (get-in b [2 0]) dz) (+ (get-in b [2 1]) dz)]])
    ;; Boolean/transform ops: union of child bounds
    (let [kids (keep #(get node %) [:a :b])
          child-bounds (map auto-bounds kids)]
      (if (seq child-bounds)
        (reduce (fn [acc cb]
                  [[(min (get-in acc [0 0]) (get-in cb [0 0]))
                    (max (get-in acc [0 1]) (get-in cb [0 1]))]
                   [(min (get-in acc [1 0]) (get-in cb [1 0]))
                    (max (get-in acc [1 1]) (get-in cb [1 1]))]
                   [(min (get-in acc [2 0]) (get-in cb [2 0]))
                    (max (get-in acc [2 1]) (get-in cb [2 1]))]])
                child-bounds)
        [[-10 10] [-10 10] [-10 10]]))))

(defn mesh-bounds
  "Get bounds from a mesh's vertices."
  [mesh]
  (let [vs (:vertices mesh)
        xs (map #(% 0) vs) ys (map #(% 1) vs) zs (map #(% 2) vs)]
    [[(apply min xs) (apply max xs)]
     [(apply min ys) (apply max ys)]
     [(apply min zs) (apply max zs)]]))

(defn expand-bounds
  "Expand bounds by a factor."
  [bounds factor]
  (mapv (fn [[lo hi]]
          (let [center (* 0.5 (+ lo hi))
                half-size (* 0.5 (- hi lo) factor)]
            [(- center half-size) (+ center half-size)]))
        bounds))

;; ── Meshing (called implicitly at boundaries) ───────────────────

(defn materialize
  "Materialize an SDF tree into a triangle mesh via libfive.
   bounds: [[xmin xmax] ...] — if nil, auto-computed from tree
   resolution: voxels per unit (default 15)"
  ([node] (materialize node nil 15))
  ([node bounds] (materialize node bounds 15))
  ([node bounds resolution]
   (let [bounds (or bounds (auto-bounds node))
         payload {:tree node :bounds bounds :resolution resolution}
         resp (http/post (str server-url "/sdf-mesh")
                {:body (json/write-str payload)
                 :content-type :json
                 :as :string
                 :throw-exceptions false})]
     (if (= 200 (:status resp))
       (let [data (json/read-str (:body resp) :key-fn keyword)]
         {:type :mesh
          :vertices (mapv vec (:vertices data))
          :faces (mapv (fn [f] (mapv int f)) (:faces data))
          :creation-pose {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}})
       (throw (Exception. (str "SDF mesh failed: " (:body resp))))))))

(def ^:dynamic *sdf-resolution*
  "Default SDF meshing resolution (voxels per unit).
   Bound from turtle state's :resolution :value."
  15)

(defn- resolution-for-bounds
  "Convert turtle-style resolution (segments per circle, ~20 default) to
   voxels-per-unit for SDF meshing, capped so the longest axis gets at most
   grid-size voxels. This keeps mesh size reasonable."
  [bounds turtle-res]
  (let [spans (map (fn [[lo hi]] (- hi lo)) bounds)
        max-span (apply max spans)
        ;; Map turtle res 10-80 → grid 50-300 voxels on longest axis
        grid-size (+ 50 (* 3 (max 0 (- turtle-res 10))))
        voxels-per-unit (if (pos? max-span)
                          (/ (double grid-size) max-span)
                          2.0)]
    voxels-per-unit))

(defn ensure-mesh
  "If x is an SDF node, materialize it. If it's already a mesh, return as-is.
   When a reference mesh is provided, use its bounds to guide SDF meshing.
   Resolution comes from *sdf-resolution* (set by the eval engine from turtle state)."
  ([x] (if (sdf-node? x)
         (let [bounds (auto-bounds x)
               res (resolution-for-bounds bounds *sdf-resolution*)]
           (materialize x bounds res))
         x))
  ([x reference-mesh]
   (if (sdf-node? x)
     (let [bounds (if reference-mesh
                    (expand-bounds (mesh-bounds reference-mesh) 1.3)
                    (auto-bounds x))
           res (resolution-for-bounds bounds *sdf-resolution*)]
       (materialize x bounds res))
     x)))
