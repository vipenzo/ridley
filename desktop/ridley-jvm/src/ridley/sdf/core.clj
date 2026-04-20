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
(defn sdf-box
  "Create an SDF box. Parameters match mesh box convention:
   (sdf-box a b c) gives same proportions as (box a b c).
   Internally remaps to match apply-transform: a→Y(right), b→Z(up), c→X(heading)."
  [a b c] {:op "box" :sx (double c) :sy (double a) :sz (double b)})
(defn sdf-cyl [r h] {:op "cyl" :r (double r) :h (double h)})

(defn sdf-rounded-box
  "Box with rounded corners as a true SDF (better field than sdf-offset of sdf-box).
   Parameters match mesh box convention: (sdf-rounded-box a b c r).
   a→Y(right), b→Z(up), c→X(heading), r=corner radius."
  [a b c r]
  {:op "rounded-box" :sx (double c) :sy (double a) :sz (double b) :r (double r)})

;; ── SDF boolean operations ──────────────────────────────────────

(defn sdf-union [a b] {:op "union" :a a :b b})
(defn sdf-difference [a b] {:op "difference" :a a :b b})
(defn sdf-intersection [a b] {:op "intersection" :a a :b b})

(declare compile-expr)

;; ── SDF-only operations ─────────────────────────────────────────

(defn sdf-blend [a b k] {:op "blend" :a a :b b :k (double k)})
(defn sdf-shell [a thickness] {:op "shell" :a a :thickness (double thickness)})
(defn sdf-offset [a amount] {:op "offset" :a a :amount (double amount)})
(defn sdf-morph [a b t] {:op "morph" :a a :b b :t (double t)})

(defn sdf-displace
  "Displace an SDF surface by a spatial formula.
   The formula is a quoted expression using x, y, z — it gets added
   to the distance field, pushing the surface inward (positive) or
   outward (negative)."
  [node formula-expr]
  {:op "binary" :fn_name "add" :a node :b (compile-expr formula-expr)})

;; ── SDF transforms ──────────────────────────────────────────────

(defn sdf-move [node dx dy dz]
  {:op "move" :a node :dx (double dx) :dy (double dy) :dz (double dz)})

(defn sdf-rotate
  "Rotate an SDF node around an axis (:x :y :z) by angle in degrees."
  [node axis angle]
  {:op "rotate" :a node :axis (name axis) :angle (double angle)})

(defn- substitute-vars
  "Walk an SDF tree and replace variable nodes according to a substitution map.
   subs-map: {\"x\" <sdf-node>, \"y\" <sdf-node>, ...}"
  [node subs-map]
  (cond
    ;; Leaf: variable → substitute if in map
    (and (= (:op node) "var") (contains? subs-map (:name node)))
    (get subs-map (:name node))

    ;; Leaf: const or unmatched var → pass through
    (or (= (:op node) "const") (= (:op node) "var"))
    node

    ;; Unary: recurse into :a
    (= (:op node) "unary")
    (update node :a #(substitute-vars % subs-map))

    ;; Binary: recurse into :a and :b
    (= (:op node) "binary")
    (-> node
        (update :a #(substitute-vars % subs-map))
        (update :b #(substitute-vars % subs-map)))

    ;; Other ops with :a and/or :b children
    :else
    (cond-> node
      (:a node) (update :a #(substitute-vars % subs-map))
      (:b node) (update :b #(substitute-vars % subs-map)))))

(defn sdf-revolve
  "Revolve a 2D SDF (in the X/Y plane) around the Z axis to produce a 3D solid.
   The 2D SDF should treat X as radius and Y as height.

   Works by substituting variables in the SDF tree:
     x → sqrt(x² + y²)   (cylindrical radius)
     y → z                (height)
   This produces a single expression tree with correct interval arithmetic
   (no libfive remap, which can cause axis-aligned artifacts).

   Optional :x-range [xmin xmax] and :y-range [ymin ymax] specify the
   bounding region of the 2D profile (needed for formulas which have
   no intrinsic bounds). If omitted, auto-bounds of the child are used."
  [node-2d & {:keys [x-range y-range]}]
  (let [;; rho = sqrt(x² + y²) using square for correct interval arithmetic
        rho (compile-expr '(sqrt (+ (square x) (square y))))
        z-var {:op "var" :name "z"}
        ;; Substitute x → rho, y → z in the 2D tree
        revolved (substitute-vars node-2d {"x" rho "y" z-var})]
    (cond-> revolved
      x-range (assoc :x-range (mapv double x-range))
      y-range (assoc :y-range (mapv double y-range)))))

(defn sdf-scale
  "Scale an SDF node. Can be called with uniform scale or per-axis."
  ([node s] (sdf-scale node s s s))
  ([node sx sy sz]
   {:op "scale" :a node :sx (double sx) :sy (double sy) :sz (double sz)}))

;; ── SDF formula (expression compiler) ───────────────────────────

(def ^:private unary-ops
  #{'sin 'cos 'tan 'asin 'acos 'atan 'sqrt 'abs 'exp 'log 'neg 'square})

(def ^:private binary-ops
  {'+ "add" '- "sub" '* "mul" '/ "div"
   'min "min" 'max "max" 'pow "pow" 'mod "mod" 'atan2 "atan2"})

(defn compile-expr
  "Walk a Clojure expression form and produce an SDF expression tree.
   Symbols x, y, z become coordinate variables. Numbers become constants.
   Math ops (+, -, *, /, sin, cos, etc.) become unary/binary nodes."
  [form]
  (cond
    ;; Coordinate variables
    (= form 'x) {:op "var" :name "x"}
    (= form 'y) {:op "var" :name "y"}
    (= form 'z) {:op "var" :name "z"}

    ;; Spherical/cylindrical synthetic variables — expand to x,y,z sub-trees
    ;; r = sqrt(x² + y² + z²)
    (= form 'r) (compile-expr '(sqrt (+ (* x x) (* y y) (* z z))))
    ;; rho = sqrt(x² + y²)  — cylindrical radius
    (= form 'rho) (compile-expr '(sqrt (+ (* x x) (* y y))))
    ;; theta = atan2(y, x)  — azimuthal angle around Z
    (= form 'theta) (compile-expr '(atan2 y x))
    ;; phi = atan2(sqrt(x²+y²), z)  — polar angle from Z
    (= form 'phi) (compile-expr '(atan2 (sqrt (+ (* x x) (* y y))) z))

    ;; Numbers → constants
    (number? form) {:op "const" :value (double form)}

    ;; List expression → operation
    (seq? form)
    (let [[op & args] form]
      (cond
        ;; Unary ops: (sin expr)
        (unary-ops op)
        (do (assert (= 1 (count args)) (str op " expects 1 argument"))
            {:op "unary" :fn_name (name op) :a (compile-expr (first args))})

        ;; Negate: (- expr) with single arg
        (and (= op '-) (= 1 (count args)))
        {:op "unary" :fn_name "neg" :a (compile-expr (first args))}

        ;; Binary ops: (+ a b), variadic (+ a b c) folds left
        (binary-ops op)
        (do (assert (>= (count args) 2) (str op " expects at least 2 arguments"))
            (reduce (fn [acc expr]
                      {:op "binary" :fn_name (binary-ops op)
                       :a acc :b (compile-expr expr)})
                    (compile-expr (first args))
                    (rest args)))

        ;; Unknown — error
        :else (throw (ex-info (str "sdf-formula: unknown op '" op "'") {:op op}))))

    ;; Unknown form
    :else (throw (ex-info (str "sdf-formula: cannot compile '" form "'") {:form form}))))

;; ── TPMS (Triply Periodic Minimal Surfaces) ─────────────────────

(defn sdf-gyroid
  "Gyroid TPMS: sin(x/s*2π)·cos(y/s*2π) + sin(y/s*2π)·cos(z/s*2π) + sin(z/s*2π)·cos(x/s*2π).
   period = cell size, thickness = wall thickness (shell around the isosurface)."
  [period thickness]
  (let [s (/ (* 2 Math/PI) (double period))]
    (sdf-shell
     (compile-expr
      (list '+ (list '* (list 'sin (list '* 'x s)) (list 'cos (list '* 'y s)))
            (list '+ (list '* (list 'sin (list '* 'y s)) (list 'cos (list '* 'z s)))
                  (list '* (list 'sin (list '* 'z s)) (list 'cos (list '* 'x s))))))
     thickness)))

(defn sdf-schwarz-p
  "Schwarz-P TPMS: cos(x/s*2π) + cos(y/s*2π) + cos(z/s*2π).
   period = cell size, thickness = wall thickness."
  [period thickness]
  (let [s (/ (* 2 Math/PI) (double period))]
    (sdf-shell
     (compile-expr
      (list '+ (list 'cos (list '* 'x s))
            (list '+ (list 'cos (list '* 'y s))
                  (list 'cos (list '* 'z s)))))
     thickness)))

(defn sdf-diamond
  "Diamond (Schwarz-D) TPMS.
   period = cell size, thickness = wall thickness."
  [period thickness]
  (let [s (/ (* 2 Math/PI) (double period))]
    (sdf-shell
     (compile-expr
      (list '-
            (list '+
                  (list '* (list 'sin (list '* 'x s))
                        (list '* (list 'sin (list '* 'y s))
                              (list 'sin (list '* 'z s))))
                  (list '* (list 'cos (list '* 'x s))
                        (list '* (list 'cos (list '* 'y s))
                              (list 'cos (list '* 'z s)))))
            (list '+
                  (list '* (list 'sin (list '* 'x s 2.0))
                        (list 'cos (list '* 'z s 2.0)))
                  (list '* (list 'sin (list '* 'y s 2.0))
                        (list 'cos (list '* 'x s 2.0)))
                  (list '* (list 'sin (list '* 'z s 2.0))
                        (list 'cos (list '* 'y s 2.0))))))
     thickness)))

;; ── Periodic patterns ───────────────────────────────────────────

(defn- slats-expr
  "Helper: build SDF expression for infinite parallel slats perpendicular to axis.
   abs(mod(axis + period/2 - phase, period) - period/2) - thickness/2"
  [axis period thickness phase]
  (let [hp (/ (double period) 2)
        ht (/ (double thickness) 2)
        offset (- hp (double phase))]
    (compile-expr
     (list '- (list 'abs (list '- (list 'mod (list '+ axis offset) (double period)) hp)) ht))))

(defn sdf-slats
  "Infinite parallel flat walls (slats).
   axis: :x, :y, or :z — slats are perpendicular to this axis
   period: center-to-center distance
   thickness: wall thickness
   phase (optional): offset along axis (default 0 = slat centered at origin)"
  ([axis period thickness] (sdf-slats axis period thickness 0))
  ([axis period thickness phase]
   (case (keyword (name axis))
     :x (slats-expr 'x period thickness phase)
     :y (slats-expr 'y period thickness phase)
     :z (slats-expr 'z period thickness phase))))

(defn sdf-bars
  "Infinite parallel cylindrical bars.
   axis: :x, :y, or :z — bars run along this axis
   period: center-to-center distance (number, or [pa pb] for different periods
           on the two perpendicular axes — useful for non-square sections)
   radius: bar radius
   phase-a, phase-b (optional): offsets along the two perpendicular axes
   (e.g. for :z bars, phase-a = X offset, phase-b = Y offset)"
  ([axis period radius] (sdf-bars axis period radius 0 0))
  ([axis period radius phase-a phase-b]
   (let [[pa-period pb-period] (if (vector? period) period [period period])
         hpa (/ (double pa-period) 2)
         hpb (/ (double pb-period) 2)
         r (double radius)
         pa (double phase-a)
         pb (double phase-b)
         rep-a (fn [v] (list '- (list 'mod (list '+ v (- hpa pa)) (double pa-period)) hpa))
         rep-b (fn [v] (list '- (list 'mod (list '+ v (- hpb pb)) (double pb-period)) hpb))
         ;; Use (* v v) instead of (pow v 2) — libfive's pow uses
         ;; exp(b·log(a)) which returns NaN for negative bases.
         sq (fn [e] (list '* e e))]
     (case (keyword (name axis))
       :z (compile-expr (list '- (list 'sqrt (list '+ (sq (rep-a 'x)) (sq (rep-b 'y)))) r))
       :x (compile-expr (list '- (list 'sqrt (list '+ (sq (rep-a 'y)) (sq (rep-b 'z)))) r))
       :y (compile-expr (list '- (list 'sqrt (list '+ (sq (rep-a 'x)) (sq (rep-b 'z)))) r))))))

(defn sdf-bar-cage
  "Cage of cylindrical bars aligned to a centered box.
   The outermost bars touch the box edges (so corners get bars on all 3 axes).
   sx, sy, sz: outer box dimensions (centered at origin)
   n: bars per side along each axis (>= 2). Total bars per direction = n × n.
   radius: bar radius
   :axes (default [:x :y :z]) — which directions get bars
   :blend (default nil) — if positive, smooth-blend the bar joints.
          Note: blend uses libfive's exponential blend (not a true SDF),
          which can cause inverted normals when combined with other booleans.
          Omit for printable parts."
  [sx sy sz n radius & {:keys [axes blend] :or {axes [:x :y :z]}}]
  (let [hx (/ (double sx) 2)
        hy (/ (double sy) 2)
        hz (/ (double sz) 2)
        ;; period along each axis: side / (n - 1) so that bars span the full side
        ;; with the outermost bars at ±side/2
        nn (max 2 n)
        px (/ (double sx) (dec nn))
        py (/ (double sy) (dec nn))
        pz (/ (double sz) (dec nn))
        ;; Phase = -side/2 places the first bar exactly at the box edge
        bar-for (fn [a]
                  (case a
                    :x (sdf-bars :x [py pz] radius (- hy) (- hz))
                    :y (sdf-bars :y [px pz] radius (- hx) (- hz))
                    :z (sdf-bars :z [px py] radius (- hx) (- hy))))
        bars (mapv bar-for axes)
        combine (if (and blend (pos? blend))
                  #(sdf-blend %1 %2 blend)
                  sdf-union)]
    (reduce combine bars)))

(defn sdf-grid
  "3D grid lattice: union of three orthogonal slat sets.
   period: cell size
   thickness: wall thickness
   Optional blend-k argument: if positive, uses smooth blend at joints
   (produces rounded corners but can cause normal inversion in booleans;
   prefer the 2-arg sharp-edge version for printable parts)."
  ([period thickness]
   (sdf-union
    (sdf-slats :x period thickness)
    (sdf-union
     (sdf-slats :y period thickness)
     (sdf-slats :z period thickness))))
  ([period thickness blend-k]
   (if (and blend-k (pos? blend-k))
     (sdf-blend
      (sdf-slats :x period thickness)
      (sdf-blend
       (sdf-slats :y period thickness)
       (sdf-slats :z period thickness)
       blend-k)
      blend-k)
     (sdf-grid period thickness))))

;; ── Bounds estimation ───────────────────────────────────────────

(defn auto-bounds
  "Estimate spatial bounds from an SDF tree. Returns [[xmin xmax] [ymin ymax] [zmin zmax]]."
  [node]
  (if (and (:x-range node) (:y-range node))
    ;; Revolve bounds hints (sdf-revolve attaches :x-range/:y-range)
    (let [xr (:x-range node) yr (:y-range node)
          rmax (* 1.3 (max (Math/abs (double (xr 0))) (Math/abs (double (xr 1)))))]
      [[(- rmax) rmax] [(- rmax) rmax] [(yr 0) (yr 1)]])
    ;; Normal dispatch by op
    (let [op (:op node)]
      (cond
        (= op "sphere")
        (let [r (:r node) m (* r 1.2)]
          [[(- m) m] [(- m) m] [(- m) m]])

        (or (= op "box") (= op "rounded-box"))
        (let [hx (* (:sx node) 0.6) hy (* (:sy node) 0.6) hz (* (:sz node) 0.6)]
          [[(- hx) hx] [(- hy) hy] [(- hz) hz]])

        (= op "cyl")
        (let [r (* (:r node) 1.2) hh (* (:h node) 0.6)]
          [[(- r) r] [(- r) r] [(- hh) hh]])

        (= op "move")
        (let [b (auto-bounds (:a node))
              dx (:dx node) dy (:dy node) dz (:dz node)]
          [[(+ (get-in b [0 0]) dx) (+ (get-in b [0 1]) dx)]
           [(+ (get-in b [1 0]) dy) (+ (get-in b [1 1]) dy)]
           [(+ (get-in b [2 0]) dz) (+ (get-in b [2 1]) dz)]])

        (= op "scale")
        (let [b (auto-bounds (:a node))
              sx (Math/abs (:sx node)) sy (Math/abs (:sy node)) sz (Math/abs (:sz node))]
          [[(* (get-in b [0 0]) sx) (* (get-in b [0 1]) sx)]
           [(* (get-in b [1 0]) sy) (* (get-in b [1 1]) sy)]
           [(* (get-in b [2 0]) sz) (* (get-in b [2 1]) sz)]])

        (= op "rotate")
        (let [b (auto-bounds (:a node))
              r (Math/sqrt (+ (Math/pow (apply max (map #(Math/abs %) (b 0))) 2)
                              (Math/pow (apply max (map #(Math/abs %) (b 1))) 2)
                              (Math/pow (apply max (map #(Math/abs %) (b 2))) 2)))]
          [[(- r) r] [(- r) r] [(- r) r]])

        ;; Default: union of child bounds
        :else
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
            [[-10 10] [-10 10] [-10 10]]))))))

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
                          :throw-exceptions false
                          :socket-timeout 60000      ;; 60s read timeout
                          :connection-timeout 5000})] ;; 5s connect timeout
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

(defn- min-feature-size
  "Walk the SDF tree and return the smallest feature size that needs resolving,
   or nil if there are no thin features. Accounts for scale nodes."
  ([node] (min-feature-size node 1.0))
  ([node scale]
   (case (:op node)
     "shell" (let [child-min (min-feature-size (:a node) scale)
                   thickness (* (Math/abs (:thickness node)) scale)]
               (if child-min (min child-min thickness) thickness))
     "offset" (let [child-min (min-feature-size (:a node) scale)
                    amt (* (Math/abs (:amount node)) scale)]
                ;; Only count small offsets as thin features (insets)
                (when (< amt 2.0)
                  (if child-min (min child-min amt) amt)))
     "scale" (let [min-s (min (Math/abs (:sx node))
                              (Math/abs (:sy node))
                              (Math/abs (:sz node)))]
               (min-feature-size (:a node) (* scale min-s)))
     ;; Recurse into children
     (let [kids (keep #(get node %) [:a :b])
           child-mins (keep #(min-feature-size % scale) kids)]
       (when (seq child-mins) (apply min child-mins))))))

(defn- resolution-for-bounds
  "Convert turtle-style resolution (segments per circle, ~20 default) to
   voxels-per-unit for SDF meshing, capped so the longest axis gets at most
   grid-size voxels. This keeps mesh size reasonable.
   When the tree has thin features (shell, small offset), resolution is
   boosted to guarantee at least 3 voxels across the thinnest feature."
  [bounds turtle-res node]
  (let [spans (map (fn [[lo hi]] (- hi lo)) bounds)
        max-span (apply max spans)
        ;; Map turtle res 10-80 → grid 50-300 voxels on longest axis
        grid-size (+ 50 (* 3 (max 0 (- turtle-res 10))))
        base-vpu (if (pos? max-span)
                   (/ (double grid-size) max-span)
                   2.0)
        ;; Boost for thin features: at least 3 voxels across thinnest part
        min-feat (min-feature-size node)
        min-vpu (if (and min-feat (pos? min-feat))
                  (/ 3.0 min-feat)
                  0.0)
        ;; Cap the auto-boost only (don't override user-requested resolution).
        ;; The boost (min-vpu) is capped at 200 voxels to prevent OOM.
        ;; The user's base resolution (base-vpu) is never capped.
        max-boost-vpu (if (pos? max-span) (/ 200.0 max-span) 10.0)
        capped-boost (min min-vpu max-boost-vpu)]
    (max base-vpu capped-boost)))

(defn ensure-mesh
  "If x is an SDF node, materialize it. If it's already a mesh, return as-is.
   When a reference mesh is provided, use its bounds to guide SDF meshing.
   Resolution comes from *sdf-resolution* (set by the eval engine from turtle state)."
  ([x] (if (sdf-node? x)
         (let [bounds (auto-bounds x)
               res (resolution-for-bounds bounds *sdf-resolution* x)
               spans (map (fn [[lo hi]] (- hi lo)) bounds)
               voxels (reduce * (map #(* res %) spans))]
           (when (> voxels 5e8)
             (println (format "[warn] SDF meshing: %.0fM voxels (resolution may be too high for this object size). Try (resolution :n %d)."
                              (/ voxels 1e6)
                              (max 16 (int (* 0.5 *sdf-resolution*))))))
           (materialize x bounds res))
         x))
  ([x reference-mesh]
   (if (sdf-node? x)
     (let [bounds (if reference-mesh
                    (expand-bounds (mesh-bounds reference-mesh) 1.3)
                    (auto-bounds x))
           res (resolution-for-bounds bounds *sdf-resolution* x)]
       (materialize x bounds res))
     x)))
