(ns ridley.sdf.core
  "SDF operations via libfive (Rust backend).
   SDF nodes are pure data — descriptions of implicit surfaces.
   Meshing is lazy: happens automatically at register/boolean/export boundaries.
   This is the CLJS version — calls the Rust server via synchronous XMLHttpRequest.")

(def ^:private server-url "http://127.0.0.1:12321")

;; ── HTTP transport (same pattern as manifold/native) ────────────

(defn- invoke-sync
  "Synchronous HTTP POST to the Rust geometry server. Returns parsed JS object."
  [endpoint body-js]
  (let [xhr (js/XMLHttpRequest.)]
    (.open xhr "POST" (str server-url endpoint) false)
    (.setRequestHeader xhr "Content-Type" "application/json")
    (.send xhr (js/JSON.stringify body-js))
    (if (= 200 (.-status xhr))
      (js/JSON.parse (.-responseText xhr))
      (throw (js/Error. (str "SDF error: " (.-responseText xhr)))))))

(defn- js->mesh
  "Convert parsed JSON to Ridley mesh with typed arrays for zero-copy Three.js rendering.
   Builds both typed arrays and Clojure vectors in a single pass."
  [^js result]
  (let [js-verts (.-vertices result)
        js-faces (.-faces result)
        nv (.-length js-verts)
        nf (.-length js-faces)
        vert-props (js/Float32Array. (* nv 3))
        tri-verts (js/Uint32Array. (* nf 3))
        vertices (loop [i 0, acc (transient [])]
                   (if (< i nv)
                     (let [^js v (aget js-verts i)
                           x (aget v 0) y (aget v 1) z (aget v 2)
                           off (* i 3)]
                       (aset vert-props off x)
                       (aset vert-props (+ off 1) y)
                       (aset vert-props (+ off 2) z)
                       (recur (inc i) (conj! acc [x y z])))
                     (persistent! acc)))
        faces (loop [i 0, acc (transient [])]
                (if (< i nf)
                  (let [^js f (aget js-faces i)
                        a (aget f 0) b (aget f 1) c (aget f 2)
                        off (* i 3)]
                    (aset tri-verts off a)
                    (aset tri-verts (+ off 1) b)
                    (aset tri-verts (+ off 2) c)
                    (recur (inc i) (conj! acc [(int a) (int b) (int c)])))
                  (persistent! acc)))]
    {:type :mesh
     :vertices vertices
     :faces faces
     :creation-pose {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}
     :ridley.manifold.core/raw-arrays {:vert-props vert-props
                                       :tri-verts tri-verts
                                       :num-prop 3}}))

;; ── SDF detection ───────────────────────────────────────────────

(defn sdf-node?
  "Returns true if x is an SDF tree node (not a mesh)."
  [x]
  (and (map? x) (string? (:op x))))

;; ── SDF node constructors (pure data) ───────────────────────────

(defn sdf-sphere [r] {:op "sphere" :r r})

(defn sdf-box
  "Create an SDF box. Parameters match mesh box convention:
   (sdf-box a b c) — a→Y(right), b→Z(up), c→X(heading)."
  [a b c] {:op "box" :sx c :sy a :sz b})

(defn sdf-cyl [r h] {:op "cyl" :r r :h h})

(defn sdf-rounded-box
  "Box with rounded corners as a true SDF.
   Parameters match mesh box convention: (sdf-rounded-box a b c r)."
  [a b c r]
  {:op "rounded-box" :sx c :sy a :sz b :r r})

(declare compile-expr)

(defn sdf-torus
  "Torus in the XY plane around the Z axis.
   R = major radius (center of tube to torus axis), r = minor radius (tube)."
  [R r]
  (-> (compile-expr
       (list '- (list 'sqrt
                      (list '+
                            (list 'square
                                  (list '-
                                        (list 'sqrt (list '+ (list 'square 'x) (list 'square 'y)))
                                        R))
                            (list 'square 'z)))
             r))
      ;; Annotate bounds so auto-bounds gives the right meshing region.
      ;; auto-bounds reads :x-range as radial extent and :y-range as height extent
      ;; (revolve convention: X=radius, Y=height).
      (assoc :x-range [(- (+ R r)) (+ R r)]
             :y-range [(- r) r])))

;; ── SDF boolean operations ──────────────────────────────────────

(defn- variadic-args
  "Normalize variadic SDF boolean args: accept (op a b c) or (op [a b c])."
  [first-arg more]
  (if (and (empty? more) (sequential? first-arg))
    (vec first-arg)
    (into [first-arg] more)))

(defn sdf-union
  "Union of one or more SDF nodes.
   Usage: (sdf-union a), (sdf-union a b c), (sdf-union [a b c])."
  [first-arg & more]
  (let [nodes (variadic-args first-arg more)]
    (case (count nodes)
      0 nil
      1 (first nodes)
      (reduce (fn [acc n] {:op "union" :a acc :b n})
              (first nodes) (rest nodes)))))

(defn sdf-difference
  "Difference of SDF nodes: (((a - b) - c) - d).
   Usage: (sdf-difference a b), (sdf-difference a b c), (sdf-difference [a b c])."
  [first-arg & more]
  (let [nodes (variadic-args first-arg more)]
    (case (count nodes)
      0 nil
      1 (first nodes)
      (reduce (fn [acc n] {:op "difference" :a acc :b n})
              (first nodes) (rest nodes)))))

(defn sdf-intersection
  "Intersection of one or more SDF nodes.
   Usage: (sdf-intersection a), (sdf-intersection a b c), (sdf-intersection [a b c])."
  [first-arg & more]
  (let [nodes (variadic-args first-arg more)]
    (case (count nodes)
      0 nil
      1 (first nodes)
      (reduce (fn [acc n] {:op "intersection" :a acc :b n})
              (first nodes) (rest nodes)))))

(declare compile-expr)

;; ── SDF-only operations ─────────────────────────────────────────

(defn sdf-blend [a b k] {:op "blend" :a a :b b :k k})

(defn sdf-blend-difference
  "Smooth subtraction: removes b from a with a soft transition of radius k.
   Analogous to sdf-blend but for difference. k has length units (mm in
   typical Ridley scenes); higher k → wider, smoother concavity."
  [a b k]
  {:op "blend-difference" :a a :b b :k k})
(defn sdf-shell [a thickness] {:op "shell" :a a :thickness thickness})
(defn sdf-offset [a amount] {:op "offset" :a a :amount amount})
(defn sdf-morph [a b t] {:op "morph" :a a :b b :t t})

(defn sdf-displace
  "Displace an SDF surface by a spatial formula.
   The formula is a quoted expression using x, y, z."
  [node formula-expr]
  {:op "binary" :fn_name "add" :a node :b (compile-expr formula-expr)})

;; ── SDF transforms ──────────────────────────────────────────────

(defn sdf-move [node dx dy dz]
  {:op "move" :a node :dx dx :dy dy :dz dz})

(declare sdf-rotate-axis)

(defn sdf-rotate
  "Rotate an SDF node around an axis by angle in degrees.
   axis: keyword (:x :y :z) for cardinal axes (uses libfive's optimized rotate),
         or vector [ax ay az] for arbitrary axes (uses Rodrigues remap)."
  [node axis angle]
  (cond
    (keyword? axis)
    {:op "rotate" :a node :axis (name axis) :angle angle}

    (sequential? axis)
    (sdf-rotate-axis node axis angle)

    :else
    (throw (js/Error. (str "sdf-rotate: axis must be a keyword (:x :y :z) or a [ax ay az] vector")))))

(defn- substitute-vars
  "Walk an SDF tree and replace variable nodes according to a substitution map."
  [node subs-map]
  (cond
    (and (= (:op node) "var") (contains? subs-map (:name node)))
    (get subs-map (:name node))

    (or (= (:op node) "const") (= (:op node) "var"))
    node

    (= (:op node) "unary")
    (update node :a #(substitute-vars % subs-map))

    (= (:op node) "binary")
    (-> node
        (update :a #(substitute-vars % subs-map))
        (update :b #(substitute-vars % subs-map)))

    :else
    (cond-> node
      (:a node) (update :a #(substitute-vars % subs-map))
      (:b node) (update :b #(substitute-vars % subs-map)))))

(defn sdf-revolve
  "Revolve a 2D SDF (in the X/Y plane) around the Z axis to produce a 3D solid."
  [node-2d & {:keys [x-range y-range]}]
  (let [rho (compile-expr '(sqrt (+ (square x) (square y))))
        z-var {:op "var" :name "z"}
        revolved (substitute-vars node-2d {"x" rho "y" z-var})]
    (cond-> revolved
      x-range (assoc :x-range (vec x-range))
      y-range (assoc :y-range (vec y-range)))))

(defn sdf-scale
  "Scale an SDF node. Uniform or per-axis."
  ([node s] (sdf-scale node s s s))
  ([node sx sy sz]
   {:op "scale" :a node :sx sx :sy sy :sz sz}))

(defn sdf-rotate-axis
  "Rotate an SDF around an arbitrary axis [ax ay az] by angle (degrees).
   The axis is normalized internally.

   Implemented as ZYX (extrinsic) Tait-Bryan decomposition into three
   cardinal-axis rotations, dispatched to libfive's rotate_x/y/z. We can't
   do this via JSON-level variable substitution because primitives like
   sdf-box / sdf-sphere don't expose `var` nodes in their JSON tree —
   their dependence on x/y/z is implicit inside the libfive backend."
  [node axis angle-deg]
  (let [[ax-r ay-r az-r] axis
        mag (Math/sqrt (+ (* ax-r ax-r) (* ay-r ay-r) (* az-r az-r)))]
    (when-not (pos? mag)
      (throw (js/Error. "sdf-rotate-axis: axis must be a non-zero vector")))
    (let [ax (/ ax-r mag) ay (/ ay-r mag) az (/ az-r mag)
          rad (* angle-deg (/ Math/PI 180))
          c (Math/cos rad)
          s (Math/sin rad)
          t (- 1 c)
          ;; Rodrigues rotation matrix (rotates points by angle around axis)
          r00 (+ c (* ax ax t))
          r10 (+ (* ax ay t) (* az s))
          r20 (- (* ax az t) (* ay s))
          r21 (+ (* ay az t) (* ax s))
          r22 (+ c (* az az t))
          ;; Decompose into ZYX extrinsic Tait-Bryan: R = Rz(yaw) * Ry(pitch) * Rx(roll)
          ;; Applied as (rotate :x roll) → (rotate :y pitch) → (rotate :z yaw):
          ;; each libfive rotate_* call rotates around its WORLD axis, so the
          ;; composition order (innermost first) matches the matrix product.
          ;;
          ;; libfive sign quirk: rotate_x and rotate_z use right-hand convention,
          ;; but rotate_y uses LEFT-hand around +Y (i.e. its visible effect is
          ;; Ry(-α) standard). We flip the pitch sign when calling rotate_y so
          ;; the composition matches the standard right-hand decomposition.
          pitch-rad (- (Math/asin (max -1.0 (min 1.0 r20))))
          yaw-rad   (Math/atan2 r10 r00)
          roll-rad  (Math/atan2 r21 r22)
          to-deg    (fn [r] (* r (/ 180 Math/PI)))]
      (-> node
          (sdf-rotate :x (to-deg roll-rad))
          (sdf-rotate :y (to-deg (- pitch-rad)))
          (sdf-rotate :z (to-deg yaw-rad))))))

;; ── SDF formula (expression compiler) ───────────────────────────

(def ^:private unary-ops
  #{'sin 'cos 'tan 'asin 'acos 'atan 'sqrt 'abs 'exp 'log 'neg 'square})

(def ^:private binary-ops
  {'+ "add" '- "sub" '* "mul" '/ "div"
   'min "min" 'max "max" 'pow "pow" 'mod "mod" 'atan2 "atan2"})

(defn compile-expr
  "Walk a Clojure expression form and produce an SDF expression tree.
   Symbols x, y, z become coordinate variables. Numbers become constants."
  [form]
  (cond
    (= form 'x) {:op "var" :name "x"}
    (= form 'y) {:op "var" :name "y"}
    (= form 'z) {:op "var" :name "z"}

    ;; Spherical/cylindrical synthetic variables
    (= form 'r) (compile-expr '(sqrt (+ (* x x) (* y y) (* z z))))
    (= form 'rho) (compile-expr '(sqrt (+ (* x x) (* y y))))
    (= form 'theta) (compile-expr '(atan2 y x))
    (= form 'phi) (compile-expr '(atan2 (sqrt (+ (* x x) (* y y))) z))

    (number? form) {:op "const" :value form}

    (seq? form)
    (let [[op-raw & args] form
          ;; Strip namespace (syntax-quote inside SCI qualifies +, -, min, max, etc. to clojure.core/*).
          op (if (symbol? op-raw) (symbol (name op-raw)) op-raw)]
      (cond
        (unary-ops op)
        (do (assert (= 1 (count args)) (str op " expects 1 argument"))
            {:op "unary" :fn_name (name op) :a (compile-expr (first args))})

        (and (= op '-) (= 1 (count args)))
        {:op "unary" :fn_name "neg" :a (compile-expr (first args))}

        (binary-ops op)
        (do (assert (>= (count args) 2) (str op " expects at least 2 arguments"))
            (reduce (fn [acc expr]
                      {:op "binary" :fn_name (binary-ops op)
                       :a acc :b (compile-expr expr)})
                    (compile-expr (first args))
                    (rest args)))

        :else (throw (js/Error. (str "sdf-formula: unknown op '" op-raw "'")))))

    :else (throw (js/Error. (str "sdf-formula: cannot compile '" form "'")))))

;; ── TPMS (Triply Periodic Minimal Surfaces) ─────────────────────

(defn sdf-gyroid
  "Gyroid TPMS. period = cell size, thickness = wall thickness."
  [period thickness]
  (let [s (/ (* 2 js/Math.PI) period)]
    (sdf-shell
     (compile-expr
      (list '+ (list '* (list 'sin (list '* 'x s)) (list 'cos (list '* 'y s)))
            (list '+ (list '* (list 'sin (list '* 'y s)) (list 'cos (list '* 'z s)))
                  (list '* (list 'sin (list '* 'z s)) (list 'cos (list '* 'x s))))))
     thickness)))

(defn sdf-schwarz-p
  "Schwarz-P TPMS. period = cell size, thickness = wall thickness."
  [period thickness]
  (let [s (/ (* 2 js/Math.PI) period)]
    (sdf-shell
     (compile-expr
      (list '+ (list 'cos (list '* 'x s))
            (list '+ (list 'cos (list '* 'y s))
                  (list 'cos (list '* 'z s)))))
     thickness)))

(defn sdf-diamond
  "Diamond (Schwarz-D) TPMS. period = cell size, thickness = wall thickness."
  [period thickness]
  (let [s (/ (* 2 js/Math.PI) period)]
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
  "Helper: build SDF expression for infinite parallel slats perpendicular to axis."
  [axis period thickness phase]
  (let [hp (/ period 2)
        ht (/ thickness 2)
        offset (- hp phase)]
    (compile-expr
     (list '- (list 'abs (list '- (list 'mod (list '+ axis offset) period) hp)) ht))))

(defn sdf-slats
  "Infinite parallel flat walls (slats).
   axis: :x, :y, or :z — slats are perpendicular to this axis
   period: center-to-center distance
   thickness: wall thickness
   phase (optional): offset along axis (default 0)"
  ([axis period thickness] (sdf-slats axis period thickness 0))
  ([axis period thickness phase]
   (case (keyword (name axis))
     :x (slats-expr 'x period thickness phase)
     :y (slats-expr 'y period thickness phase)
     :z (slats-expr 'z period thickness phase))))

(defn sdf-bars
  "Infinite parallel cylindrical bars.
   axis: :x, :y, or :z — bars run along this axis
   period: center-to-center distance (number, or [pa pb] for different periods)
   radius: bar radius"
  ([axis period radius] (sdf-bars axis period radius 0 0))
  ([axis period radius phase-a phase-b]
   (let [[pa-period pb-period] (if (vector? period) period [period period])
         hpa (/ pa-period 2)
         hpb (/ pb-period 2)
         r radius
         pa phase-a
         pb phase-b
         rep-a (fn [v] (list '- (list 'mod (list '+ v (- hpa pa)) pa-period) hpa))
         rep-b (fn [v] (list '- (list 'mod (list '+ v (- hpb pb)) pb-period) hpb))
         sq (fn [e] (list '* e e))]
     (case (keyword (name axis))
       :z (compile-expr (list '- (list 'sqrt (list '+ (sq (rep-a 'x)) (sq (rep-b 'y)))) r))
       :x (compile-expr (list '- (list 'sqrt (list '+ (sq (rep-a 'y)) (sq (rep-b 'z)))) r))
       :y (compile-expr (list '- (list 'sqrt (list '+ (sq (rep-a 'x)) (sq (rep-b 'z)))) r))))))

(defn sdf-bar-cage
  "Cage of cylindrical bars aligned to a centered box."
  [sx sy sz n radius & {:keys [axes blend] :or {axes [:x :y :z]}}]
  (let [hx (/ sx 2) hy (/ sy 2) hz (/ sz 2)
        nn (max 2 n)
        px (/ sx (dec nn))
        py (/ sy (dec nn))
        pz (/ sz (dec nn))
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
  "3D grid lattice: union of three orthogonal slat sets."
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
    (let [xr (:x-range node) yr (:y-range node)
          rmax (* 1.3 (max (js/Math.abs (xr 0)) (js/Math.abs (xr 1))))]
      [[(- rmax) rmax] [(- rmax) rmax] [(yr 0) (yr 1)]])
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
              sx (js/Math.abs (:sx node)) sy (js/Math.abs (:sy node)) sz (js/Math.abs (:sz node))]
          [[(* (get-in b [0 0]) sx) (* (get-in b [0 1]) sx)]
           [(* (get-in b [1 0]) sy) (* (get-in b [1 1]) sy)]
           [(* (get-in b [2 0]) sz) (* (get-in b [2 1]) sz)]])

        (= op "rotate")
        (let [b (auto-bounds (:a node))
              r (js/Math.sqrt (+ (js/Math.pow (apply max (map js/Math.abs (b 0))) 2)
                                 (js/Math.pow (apply max (map js/Math.abs (b 1))) 2)
                                 (js/Math.pow (apply max (map js/Math.abs (b 2))) 2)))]
          [[(- r) r] [(- r) r] [(- r) r]])

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

;; ── Meshing ─────────────────────────────────────────────────────

(defn- min-feature-size
  "Walk the SDF tree and return the smallest feature size that needs resolving."
  ([node] (min-feature-size node 1.0))
  ([node scale]
   (case (:op node)
     "shell" (let [child-min (min-feature-size (:a node) scale)
                   thickness (* (js/Math.abs (:thickness node)) scale)]
               (if child-min (min child-min thickness) thickness))
     "offset" (let [child-min (min-feature-size (:a node) scale)
                    amt (* (js/Math.abs (:amount node)) scale)]
                (when (< amt 2.0)
                  (if child-min (min child-min amt) amt)))
     "scale" (let [min-s (min (js/Math.abs (:sx node))
                              (js/Math.abs (:sy node))
                              (js/Math.abs (:sz node)))]
               (min-feature-size (:a node) (* scale min-s)))
     ;; Recurse into children
     (let [kids (keep #(get node %) [:a :b])
           child-mins (keep #(min-feature-size % scale) kids)]
       (when (seq child-mins) (apply min child-mins))))))

(def ^:private base-max-voxels
  "Maximum total voxel budget for SDF meshing at default turtle-res=15.
   Scales linearly with turtle-res so bumping resolution actually allows finer meshes."
  4e6)

(defn- resolution-for-bounds
  "Convert turtle-style resolution to voxels-per-unit for SDF meshing.
   The base resolution is capped by a voxel budget that scales with turtle-res,
   so bumping *sdf-resolution* has real effect. The thin-feature boost is also
   capped by the same scaled cap to avoid OOM from tiny shells/offsets."
  [bounds turtle-res node]
  (let [spans (mapv (fn [[lo hi]] (- hi lo)) bounds)
        max-span (apply max spans)
        volume (reduce * spans)
        ;; Base resolution from turtle-res (maps 10-80 → 50-300 grid on longest axis)
        grid-size (+ 50 (* 3 (max 0 (- turtle-res 10))))
        base-vpu (if (pos? max-span)
                   (/ grid-size max-span)
                   2.0)
        ;; Voxel budget scales with turtle-res so the user can effectively bump it.
        budget (* base-max-voxels (max 1.0 (/ turtle-res 15.0)))
        max-vpu (if (pos? volume)
                  (js/Math.pow (/ budget volume) (/ 1 3))
                  base-vpu)
        capped-base (min base-vpu max-vpu)
        ;; Thin-feature boost: at least 2.5 voxels across thinnest part
        ;; Capped by the scaled budget rather than a hard 200/max-span.
        min-feat (min-feature-size node)
        feat-vpu (if (and min-feat (pos? min-feat))
                   (/ 2.5 min-feat)
                   0.0)
        ;; Hard cap on longest axis: scales with grid-size so user can override.
        abs-max-vpu (if (pos? max-span) (/ (* 3 grid-size) max-span) 10.0)]
    (min (max capped-base feat-vpu) abs-max-vpu)))

(def ^:dynamic *sdf-resolution*
  "Default SDF meshing resolution (voxels per unit)."
  15)

(defn materialize
  "Materialize an SDF tree into a triangle mesh via libfive.
   bounds: [[xmin xmax] ...] — if nil, auto-computed from tree
   resolution: voxels per unit (default 15)"
  ([node] (materialize node nil 15))
  ([node bounds] (materialize node bounds 15))
  ([node bounds resolution]
   (let [bounds (or bounds (auto-bounds node))
         payload (clj->js {:tree node :bounds bounds :resolution resolution})
         result (invoke-sync "/sdf-mesh" payload)]
     (js->mesh result))))

(defn ensure-mesh
  "If x is an SDF node, materialize it. If already a mesh, return as-is."
  ([x] (if (sdf-node? x)
         (let [bounds (auto-bounds x)
               res (resolution-for-bounds bounds *sdf-resolution* x)
               spans (map (fn [[lo hi]] (- hi lo)) bounds)
               voxels (reduce * (map #(* res %) spans))]
           (when (> voxels 5e8)
             (println (str "[warn] SDF meshing: " (.toFixed (/ voxels 1e6) 0) "M voxels — try lower resolution.")))
           (materialize x bounds res))
         x))
  ([x reference-mesh]
   (if (sdf-node? x)
     (let [sdf-bounds (auto-bounds x)
           bounds (if reference-mesh
                    (let [ref-b (expand-bounds (mesh-bounds reference-mesh) 1.3)]
                      (mapv (fn [[slo shi] [rlo rhi]]
                              [(min slo rlo) (max shi rhi)])
                            sdf-bounds ref-b))
                    sdf-bounds)
           res (resolution-for-bounds bounds *sdf-resolution* x)]
       (materialize x bounds res))
     x)))
