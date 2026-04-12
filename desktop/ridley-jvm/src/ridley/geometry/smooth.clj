(ns ridley.geometry.smooth
  "Selective Taubin mesh smoothing — moves only vertices at sharp creases
   (staircase edges) while preserving flat wall surfaces.

   Unlike Manifold's smoothOut (which fits Bezier tangents and can overshoot),
   this uses iterative vertex averaging with a dihedral-angle mask: only vertices
   where at least one adjacent edge has a dihedral angle below :feature-angle
   are moved. Flat coplanar regions stay perfectly still.

   Works on any mesh topology — manifold or not.")

(defn- build-adjacency
  "Build vertex adjacency: vertex-index -> set of neighbor indices."
  [n-verts faces]
  (let [adj (object-array n-verts)]
    (dotimes [i n-verts]
      (aset adj i (transient #{})))
    (doseq [face faces]
      (let [a (int (nth face 0))
            b (int (nth face 1))
            c (int (nth face 2))]
        (aset adj a (conj! (aget adj a) b))
        (aset adj a (conj! (aget adj a) c))
        (aset adj b (conj! (aget adj b) a))
        (aset adj b (conj! (aget adj b) c))
        (aset adj c (conj! (aget adj c) a))
        (aset adj c (conj! (aget adj c) b))))
    (let [result (object-array n-verts)]
      (dotimes [i n-verts]
        (aset result i (persistent! (aget adj i))))
      result)))

(defn- face-normal
  "Compute face normal (unnormalized) from vertex coords array."
  [^doubles coords [a b c]]
  (let [ab (* (int a) 3) bb (* (int b) 3) cb (* (int c) 3)
        ux (- (aget coords bb) (aget coords ab))
        uy (- (aget coords (+ bb 1)) (aget coords (+ ab 1)))
        uz (- (aget coords (+ bb 2)) (aget coords (+ ab 2)))
        vx (- (aget coords cb) (aget coords ab))
        vy (- (aget coords (+ cb 1)) (aget coords (+ ab 1)))
        vz (- (aget coords (+ cb 2)) (aget coords (+ ab 2)))
        nx (- (* uy vz) (* uz vy))
        ny (- (* uz vx) (* ux vz))
        nz (- (* ux vy) (* uy vx))
        len (Math/sqrt (+ (* nx nx) (* ny ny) (* nz nz)))]
    (if (> len 1e-12)
      [(/ nx len) (/ ny len) (/ nz len)]
      [0.0 0.0 1.0])))

(defn- build-smoothable-mask
  "Return a boolean array: true for vertices that should be smoothed.
   A vertex is smoothable if at least one of its adjacent edges has a
   dihedral angle (between the two faces sharing the edge) below
   feature-angle-deg. Flat coplanar vertices (all edges ~180°) are frozen."
  [^doubles coords faces n-verts ^double feature-angle-deg]
  (let [cos-threshold (Math/cos (Math/toRadians feature-angle-deg))
        ;; Build edge -> list-of-normals map via reduce
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
        ;; For each edge with 2 faces, compute dihedral via dot product
        ;; Mark BOTH vertices of any edge below threshold
        mask (boolean-array n-verts false)]
    (doseq [[[v1 v2] normals] edge-normals]
      (when (= 2 (count normals))
        (let [[n1x n1y n1z] (first normals)
              [n2x n2y n2z] (second normals)
              dot (+ (* n1x n2x) (* n1y n2y) (* n1z n2z))]
          ;; dot = cos(dihedral): 1 = folded back, 0 = 90°, -1 = flat (180°)
          ;; Smooth vertices where dihedral < feature-angle, i.e. dot > cos(feature-angle)
          ;; This freezes nearly-flat edges (close to 180°) and smooths everything sharper
          (when (> dot cos-threshold)
            (aset mask (int v1) true)
            (aset mask (int v2) true)))))
    mask))

(defn- laplacian-pass!
  "One Laplacian pass with mask: only moves vertices where mask[i] is true."
  [^doubles coords ^objects adj ^booleans mask n-verts factor]
  (let [factor (double factor)
        deltas (double-array (* n-verts 3))]
    (dotimes [i n-verts]
      (when (aget mask i)
        (let [neighbors ^clojure.lang.IPersistentSet (aget adj i)
              n-neighbors (count neighbors)
              base (* i 3)
              cx (aget coords base)
              cy (aget coords (+ base 1))
              cz (aget coords (+ base 2))]
          (when (pos? n-neighbors)
            (let [inv (/ 1.0 (double n-neighbors))
                  [sx sy sz]
                  (reduce (fn [[sx sy sz] j]
                            (let [jb (* (int j) 3)]
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
    ;; Apply deltas (only masked vertices have non-zero deltas)
    (dotimes [i (* n-verts 3)]
      (aset coords i (+ (aget coords i) (aget deltas i))))
    coords))

(defn taubin-smooth
  "Selective Taubin λ|μ smoothing: only moves vertices at sharp creases.

   Options:
     :iterations     number of λ+μ pass pairs (default 10)
     :lambda         positive smoothing factor (default 0.5)
     :mu             negative inflation factor (default -0.53)
     :feature-angle  threshold in degrees (default 150). Vertices where ALL
                     adjacent edges have dihedral > this are frozen. Flat
                     wall surfaces (~180°) stay still; staircase edges
                     (~90°) get smoothed. Lower = fewer vertices moved."
  [mesh & {:keys [iterations lambda mu feature-angle]
           :or {iterations 10 lambda 0.5 mu -0.53 feature-angle 150}}]
  (let [verts (:vertices mesh)
        faces (:faces mesh)
        n-verts (count verts)
        coords (double-array (* n-verts 3))
        _ (dotimes [i n-verts]
            (let [v (nth verts i)
                  base (* i 3)]
              (aset coords base (double (nth v 0)))
              (aset coords (+ base 1) (double (nth v 1)))
              (aset coords (+ base 2) (double (nth v 2)))))
        adj (build-adjacency n-verts faces)
        mask (build-smoothable-mask coords faces n-verts (double feature-angle))]
    ;; Report how many vertices will be smoothed
    (let [n-smoothable (count (filter true? (seq mask)))]
      (println (str "mesh-laplacian: " n-smoothable "/" n-verts
                    " vertices smoothable (feature-angle " feature-angle "°)")))
    ;; Run Taubin passes
    (dotimes [_ iterations]
      (laplacian-pass! coords adj mask n-verts lambda)
      (laplacian-pass! coords adj mask n-verts mu))
    ;; Unpack
    (let [new-verts (vec (for [i (range n-verts)]
                           (let [base (* i 3)]
                             [(aget coords base)
                              (aget coords (+ base 1))
                              (aget coords (+ base 2))])))]
      (assoc mesh :vertices new-verts))))
