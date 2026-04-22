(ns ridley.geometry.mesh-utils
  "Mesh utility functions ported from the JVM sidecar:
   merge-vertices, mesh-diagnose, mesh-simplify, mesh-laplacian.
   Pure CLJS — no external dependencies.")

;; ── Merge duplicate vertices ────────────────────────────────────

(defn merge-vertices
  "Merge duplicate vertices in a mesh (same position within epsilon).
   Remaps face indices to use the first occurrence of each position.
   Fixes non-manifold issues from CSG operations with coincident vertices."
  ([mesh] (merge-vertices mesh 1e-6))
  ([mesh epsilon]
   (let [vs (:vertices mesh)
         scale (/ 1.0 epsilon)
         pos->idx (volatile! {})
         remap (mapv (fn [i]
                       (let [v (nth vs i)
                             key [(js/Math.round (* (v 0) scale))
                                  (js/Math.round (* (v 1) scale))
                                  (js/Math.round (* (v 2) scale))]]
                         (if-let [existing (get @pos->idx key)]
                           existing
                           (do (vswap! pos->idx assoc key i)
                               i))))
                     (range (count vs)))
         new-faces (mapv (fn [[a b c]]
                           [(nth remap a) (nth remap b) (nth remap c)])
                         (:faces mesh))
         clean-faces (filterv (fn [[a b c]]
                                (and (not= a b) (not= b c) (not= a c)))
                              new-faces)
         used (set (mapcat identity clean-faces))
         old->new (into {} (map-indexed (fn [new-i old-i] [old-i new-i])
                                        (sort used)))
         final-verts (mapv #(nth vs %) (sort used))
         final-faces (mapv (fn [[a b c]]
                             [(old->new a) (old->new b) (old->new c)])
                           clean-faces)]
     (assoc mesh
            :vertices final-verts
            :faces final-faces))))

;; ── Mesh diagnosis ──────────────────────────────────────────────

(defn- edge-key [a b]
  (if (< a b) [a b] [b a]))

(defn- triangle-area [verts [a b c]]
  (let [[ax ay az] (nth verts a)
        [bx by bz] (nth verts b)
        [cx cy cz] (nth verts c)
        ux (- bx ax) uy (- by ay) uz (- bz az)
        vx (- cx ax) vy (- cy ay) vz (- cz az)
        nx (- (* uy vz) (* uz vy))
        ny (- (* uz vx) (* ux vz))
        nz (- (* ux vy) (* uy vx))]
    (* 0.5 (js/Math.sqrt (+ (* nx nx) (* ny ny) (* nz nz))))))

(defn- build-edge-incidence [faces]
  (persistent!
   (reduce
    (fn [m face]
      (let [a (nth face 0) b (nth face 1) c (nth face 2)
            e1 (edge-key a b) e2 (edge-key b c) e3 (edge-key c a)]
        (-> m
            (assoc! e1 (inc (get m e1 0)))
            (assoc! e2 (inc (get m e2 0)))
            (assoc! e3 (inc (get m e3 0))))))
    (transient {})
    faces)))

(defn mesh-diagnose
  "Compute topological invariants of a mesh. Returns a map; never mutates.
   Keys: :n-verts :n-faces :n-edges :edge-incidence-distribution
         :open-edges :non-manifold-edges :degenerate-faces
         :euler-characteristic :is-watertight?"
  [mesh]
  (let [verts (:vertices mesh)
        faces (:faces mesh)
        n-verts (count verts)
        n-faces (count faces)
        edge-counts (build-edge-incidence faces)
        n-edges (count edge-counts)
        edge-distribution (frequencies (vals edge-counts))
        open-edges (get edge-distribution 1 0)
        non-manifold-edges (->> edge-distribution
                                (filter (fn [[k _]] (> k 2)))
                                (map second)
                                (reduce + 0))
        degenerate (count (filter #(< (triangle-area verts %) 1e-10) faces))
        euler (+ n-verts (- n-edges) n-faces)]
    {:n-verts n-verts
     :n-faces n-faces
     :n-edges n-edges
     :edge-incidence-distribution edge-distribution
     :open-edges open-edges
     :non-manifold-edges non-manifold-edges
     :degenerate-faces degenerate
     :euler-characteristic euler
     :is-watertight? (and (zero? open-edges) (zero? non-manifold-edges))}))

;; ── Mesh simplification (edge-collapse decimation) ──────────────

(defn mesh-simplify
  "Reduce triangle count by collapsing short edges.
   ratio: target fraction of original triangles (0.0 to 1.0).
   Options: :max-passes (default 20)."
  [mesh ratio & {:keys [max-passes] :or {max-passes 20}}]
  (let [orig-verts (:vertices mesh)
        orig-faces (:faces mesh)
        n-verts (count orig-verts)
        n-faces (count orig-faces)
        target-faces (max 4 (int (* ratio n-faces)))

        ;; Mutable vertex positions (JS typed arrays)
        vx (js/Float64Array. n-verts)
        vy (js/Float64Array. n-verts)
        vz (js/Float64Array. n-verts)
        _ (dotimes [i n-verts]
            (let [v (nth orig-verts i)]
              (aset vx i (nth v 0))
              (aset vy i (nth v 1))
              (aset vz i (nth v 2))))

        ;; Face storage: JS arrays [a, b, c], nil = deleted
        face-arr (make-array n-faces)
        _ (dotimes [i n-faces]
            (let [f (nth orig-faces i)]
              (aset face-arr i (array (nth f 0) (nth f 1) (nth f 2)))))

        ;; Remap: vertex i → canonical index (follow collapse chains)
        remap (js/Int32Array. n-verts)
        _ (dotimes [i n-verts] (aset remap i i))

        resolve-v (fn [v]
                    (loop [v v]
                      (let [r (aget remap v)]
                        (if (== r v) v (recur r)))))

        edge-len-sq (fn [a b]
                      (let [dx (- (aget vx a) (aget vx b))
                            dy (- (aget vy a) (aget vy b))
                            dz (- (aget vz a) (aget vz b))]
                        (+ (* dx dx) (* dy dy) (* dz dz))))

        live-faces (volatile! n-faces)

        ;; Average edge length from sample
        avg-edge-len
        (let [sample-n (min n-faces 10000)
              sum (volatile! 0.0)
              cnt (volatile! 0)]
          (dotimes [fi sample-n]
            (when-let [f (aget face-arr fi)]
              (let [a (aget f 0) b (aget f 1) c (aget f 2)]
                (vswap! sum + (js/Math.sqrt (edge-len-sq a b)))
                (vswap! sum + (js/Math.sqrt (edge-len-sq b c)))
                (vswap! sum + (js/Math.sqrt (edge-len-sq a c)))
                (vswap! cnt + 3))))
          (if (pos? @cnt) (/ @sum @cnt) 1.0))

        base-threshold (* avg-edge-len (- 1.0 ratio))]

    ;; Multi-pass collapse
    (loop [pass 0
           threshold base-threshold]
      (when (and (> @live-faces target-faces) (< pass max-passes))
        (let [threshold-sq (* threshold threshold)
              before @live-faces
              _ (dotimes [fi n-faces]
                  (when (> @live-faces target-faces)
                    (when-let [f (aget face-arr fi)]
                      (let [a (resolve-v (aget f 0))
                            b (resolve-v (aget f 1))
                            c (resolve-v (aget f 2))]
                        (aset f 0 a)
                        (aset f 1 b)
                        (aset f 2 c)
                        (if (or (== a b) (== b c) (== a c))
                          (do (aset face-arr fi nil)
                              (vswap! live-faces dec))
                          (let [lab (edge-len-sq a b)
                                lbc (edge-len-sq b c)
                                lac (edge-len-sq a c)
                                min-len (min lab lbc lac)]
                            (when (<= min-len threshold-sq)
                              (let [[va vb] (cond
                                              (= min-len lab) [a b]
                                              (= min-len lbc) [b c]
                                              :else [a c])]
                                (aset vx va (* 0.5 (+ (aget vx va) (aget vx vb))))
                                (aset vy va (* 0.5 (+ (aget vy va) (aget vy vb))))
                                (aset vz va (* 0.5 (+ (aget vz va) (aget vz vb))))
                                (aset remap vb va)
                                (aset f 0 (if (== (aget f 0) vb) va (aget f 0)))
                                (aset f 1 (if (== (aget f 1) vb) va (aget f 1)))
                                (aset f 2 (if (== (aget f 2) vb) va (aget f 2)))
                                (when (or (== (aget f 0) (aget f 1))
                                          (== (aget f 1) (aget f 2))
                                          (== (aget f 0) (aget f 2)))
                                  (aset face-arr fi nil)
                                  (vswap! live-faces dec))))))))))
              after @live-faces
              progress (- before after)]
          (when (pos? progress)
            (recur (inc pass)
                   (if (< progress (/ (- before target-faces) 3))
                     (* threshold 1.5)
                     threshold))))))

    ;; Resolve all surviving faces and compact
    (let [surviving (volatile! [])
          _ (dotimes [fi n-faces]
              (when-let [f (aget face-arr fi)]
                (let [a (resolve-v (aget f 0))
                      b (resolve-v (aget f 1))
                      c (resolve-v (aget f 2))]
                  (when (and (not= a b) (not= b c) (not= a c))
                    (vswap! surviving conj [a b c])))))
          surv @surviving
          used (into #{} (mapcat identity) surv)
          sorted-v (vec (sort used))
          old->new (into {} (map-indexed (fn [i v] [v i])) sorted-v)
          new-verts (mapv (fn [i] [(aget vx i) (aget vy i) (aget vz i)]) sorted-v)
          new-faces (mapv (fn [[a b c]]
                            [(old->new a) (old->new b) (old->new c)])
                          surv)]
      (println (str "mesh-simplify: " n-faces " -> " (count new-faces)
                    " faces (" (int (* 100.0 (/ (count new-faces) n-faces))) "%),"
                    " " n-verts " -> " (count new-verts) " verts"))
      (cond-> (assoc mesh :vertices new-verts :faces new-faces)
        (:creation-pose mesh) (assoc :creation-pose (:creation-pose mesh))
        (:material mesh) (assoc :material (:material mesh))))))

;; ── Taubin mesh smoothing ───────────────────────────────────────

(defn- build-adjacency
  "Build vertex adjacency: vertex-index -> set of neighbor indices."
  [n-verts faces]
  (let [adj (make-array n-verts)]
    (dotimes [i n-verts] (aset adj i (transient #{})))
    (doseq [face faces]
      (let [a (nth face 0) b (nth face 1) c (nth face 2)]
        (aset adj a (conj! (aget adj a) b))
        (aset adj a (conj! (aget adj a) c))
        (aset adj b (conj! (aget adj b) a))
        (aset adj b (conj! (aget adj b) c))
        (aset adj c (conj! (aget adj c) a))
        (aset adj c (conj! (aget adj c) b))))
    (let [result (make-array n-verts)]
      (dotimes [i n-verts]
        (aset result i (persistent! (aget adj i))))
      result)))

(defn- face-normal
  "Compute normalized face normal from flat coords array."
  [^js coords [a b c]]
  (let [ab (* a 3) bb (* b 3) cb (* c 3)
        ux (- (aget coords bb) (aget coords ab))
        uy (- (aget coords (+ bb 1)) (aget coords (+ ab 1)))
        uz (- (aget coords (+ bb 2)) (aget coords (+ ab 2)))
        vx (- (aget coords cb) (aget coords ab))
        vy (- (aget coords (+ cb 1)) (aget coords (+ ab 1)))
        vz (- (aget coords (+ cb 2)) (aget coords (+ ab 2)))
        nx (- (* uy vz) (* uz vy))
        ny (- (* uz vx) (* ux vz))
        nz (- (* ux vy) (* uy vx))
        len (js/Math.sqrt (+ (* nx nx) (* ny ny) (* nz nz)))]
    (if (> len 1e-12)
      [(/ nx len) (/ ny len) (/ nz len)]
      [0.0 0.0 1.0])))

(defn- build-smoothable-mask
  "Boolean array: true for vertices that should be smoothed.
   Vertices where at least one adjacent edge has dihedral angle below
   feature-angle-deg are smoothable."
  [coords faces n-verts feature-angle-deg]
  (let [cos-threshold (js/Math.cos (* feature-angle-deg (/ js/Math.PI 180)))
        edge-normals
        (persistent!
         (reduce
          (fn [em face]
            (let [n (face-normal coords face)
                  a (nth face 0) b (nth face 1) c (nth face 2)
                  add (fn [em e1 e2]
                        (let [k (if (< e1 e2) [e1 e2] [e2 e1])]
                          (assoc! em k (conj (get em k []) n))))]
              (-> em (add a b) (add b c) (add a c))))
          (transient {})
          faces))
        mask (make-array n-verts)]
    (dotimes [i n-verts] (aset mask i false))
    (doseq [[[v1 v2] normals] edge-normals]
      (when (== 2 (count normals))
        (let [[n1x n1y n1z] (first normals)
              [n2x n2y n2z] (second normals)
              dot (+ (* n1x n2x) (* n1y n2y) (* n1z n2z))]
          (when (> dot cos-threshold)
            (aset mask v1 true)
            (aset mask v2 true)))))
    mask))

(defn- laplacian-pass!
  "One Laplacian pass: only moves vertices where mask[i] is true."
  [coords adj mask n-verts factor]
  (let [deltas (js/Float64Array. (* n-verts 3))]
    (dotimes [i n-verts]
      (when (aget mask i)
        (let [neighbors (aget adj i)
              n-neighbors (count neighbors)
              base (* i 3)
              cx (aget coords base)
              cy (aget coords (+ base 1))
              cz (aget coords (+ base 2))]
          (when (pos? n-neighbors)
            (let [inv (/ 1.0 n-neighbors)
                  [sx sy sz]
                  (reduce (fn [[sx sy sz] j]
                            (let [jb (* j 3)]
                              [(+ sx (aget coords jb))
                               (+ sy (aget coords (+ jb 1)))
                               (+ sz (aget coords (+ jb 2)))]))
                          [0.0 0.0 0.0]
                          neighbors)
                  lx (- (* sx inv) cx)
                  ly (- (* sy inv) cy)
                  lz (- (* sz inv) cz)]
              (aset deltas base (* factor lx))
              (aset deltas (+ base 1) (* factor ly))
              (aset deltas (+ base 2) (* factor lz)))))))
    (dotimes [i (* n-verts 3)]
      (aset coords i (+ (aget coords i) (aget deltas i))))
    coords))

(defn mesh-laplacian
  "Selective Taubin λ|μ smoothing: only moves vertices at sharp creases.
   Options:
     :iterations     number of λ+μ pass pairs (default 10)
     :lambda         positive smoothing factor (default 0.5)
     :mu             negative inflation factor (default -0.53)
     :feature-angle  threshold in degrees (default 150)"
  [mesh & {:keys [iterations lambda mu feature-angle]
           :or {iterations 10 lambda 0.5 mu -0.53 feature-angle 150}}]
  (let [verts (:vertices mesh)
        faces (:faces mesh)
        n-verts (count verts)
        coords (js/Float64Array. (* n-verts 3))
        _ (dotimes [i n-verts]
            (let [v (nth verts i)
                  base (* i 3)]
              (aset coords base (nth v 0))
              (aset coords (+ base 1) (nth v 1))
              (aset coords (+ base 2) (nth v 2))))
        adj (build-adjacency n-verts faces)
        mask (build-smoothable-mask coords faces n-verts feature-angle)]
    (let [n-smoothable (count (filter true? (array-seq mask)))]
      (println (str "mesh-laplacian: " n-smoothable "/" n-verts
                    " vertices smoothable (feature-angle " feature-angle "°)")))
    (dotimes [_ iterations]
      (laplacian-pass! coords adj mask n-verts lambda)
      (laplacian-pass! coords adj mask n-verts mu))
    (let [new-verts (vec (for [i (range n-verts)]
                           (let [base (* i 3)]
                             [(aget coords base)
                              (aget coords (+ base 1))
                              (aget coords (+ base 2))])))]
      (assoc mesh :vertices new-verts))))
