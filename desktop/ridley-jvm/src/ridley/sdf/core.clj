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

(defn sdf-rounded-box
  "Box with rounded corners as a true SDF (better field than sdf-offset of sdf-box).
   sx, sy, sz: dimensions; r: corner radius."
  [sx sy sz r]
  {:op "rounded-box" :sx (double sx) :sy (double sy) :sz (double sz) :r (double r)})

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
   period: center-to-center distance
   radius: bar radius
   phase-a, phase-b (optional): offsets along the two perpendicular axes
   (e.g. for :z bars, phase-a = X offset, phase-b = Y offset)"
  ([axis period radius] (sdf-bars axis period radius 0 0))
  ([axis period radius phase-a phase-b]
   (let [hp (/ (double period) 2)
         r (double radius)
         pa (double phase-a)
         pb (double phase-b)
         rep (fn [v phase]
               (list '- (list 'mod (list '+ v (- hp phase)) (double period)) hp))
         ;; Use (* v v) instead of (pow v 2) — libfive's pow uses
         ;; exp(b·log(a)) which returns NaN for negative bases.
         sq (fn [e] (list '* e e))]
     (case (keyword (name axis))
       :z (compile-expr (list '- (list 'sqrt (list '+ (sq (rep 'x pa)) (sq (rep 'y pb)))) r))
       :x (compile-expr (list '- (list 'sqrt (list '+ (sq (rep 'y pa)) (sq (rep 'z pb)))) r))
       :y (compile-expr (list '- (list 'sqrt (list '+ (sq (rep 'x pa)) (sq (rep 'z pb)))) r))))))

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
  (case (:op node)
    "sphere" (let [r (:r node) m (* r 1.2)]
               [[(- m) m] [(- m) m] [(- m) m]])
    "box" (let [hx (* (:sx node) 0.6) hy (* (:sy node) 0.6) hz (* (:sz node) 0.6)]
            [[(- hx) hx] [(- hy) hy] [(- hz) hz]])
    "rounded-box" (let [hx (* (:sx node) 0.6) hy (* (:sy node) 0.6) hz (* (:sz node) 0.6)]
                    [[(- hx) hx] [(- hy) hy] [(- hz) hz]])
    "cyl" (let [r (* (:r node) 1.2) hh (* (:h node) 0.6)]
            [[(- r) r] [(- r) r] [(- hh) hh]])
    "move" (let [b (auto-bounds (:a node))
                 dx (:dx node) dy (:dy node) dz (:dz node)]
             [[(+ (get-in b [0 0]) dx) (+ (get-in b [0 1]) dx)]
              [(+ (get-in b [1 0]) dy) (+ (get-in b [1 1]) dy)]
              [(+ (get-in b [2 0]) dz) (+ (get-in b [2 1]) dz)]])
    "scale" (let [b (auto-bounds (:a node))
                  sx (Math/abs (:sx node)) sy (Math/abs (:sy node)) sz (Math/abs (:sz node))]
              [[(* (get-in b [0 0]) sx) (* (get-in b [0 1]) sx)]
               [(* (get-in b [1 0]) sy) (* (get-in b [1 1]) sy)]
               [(* (get-in b [2 0]) sz) (* (get-in b [2 1]) sz)]])
    "rotate" (let [b (auto-bounds (:a node))
                   ;; Conservative: use bounding sphere of the AABB
                   r (Math/sqrt (+ (Math/pow (apply max (map #(Math/abs %) (b 0))) 2)
                                   (Math/pow (apply max (map #(Math/abs %) (b 1))) 2)
                                   (Math/pow (apply max (map #(Math/abs %) (b 2))) 2)))]
               [[(- r) r] [(- r) r] [(- r) r]])
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
        ;; Cap: never exceed 200 voxels on the longest axis
        max-vpu (if (pos? max-span) (/ 200.0 max-span) 10.0)]
    (min (max base-vpu min-vpu) max-vpu)))

(defn ensure-mesh
  "If x is an SDF node, materialize it. If it's already a mesh, return as-is.
   When a reference mesh is provided, use its bounds to guide SDF meshing.
   Resolution comes from *sdf-resolution* (set by the eval engine from turtle state)."
  ([x] (if (sdf-node? x)
         (let [bounds (auto-bounds x)
               res (resolution-for-bounds bounds *sdf-resolution* x)]
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
