(ns ridley.geometry.simplify
  "Single-pass mesh decimation: collapse all edges shorter than a threshold.

   O(n) per pass — walks every face once, collapses short edges in-place.
   No priority queue, no sorting, no quadrics. Fast enough for 1M+ faces.

   The threshold is auto-calculated from the target ratio and the mesh's
   average edge length: shorter ratio → higher threshold → more collapses.

   Works identically on JVM and (with minor adaptation) CLJS.")

(defn simplify
  "Reduce triangle count by collapsing short edges.

   ratio: target fraction of original triangles (0.0 to 1.0).
   Options:
     :max-passes  maximum number of collapse passes (default 20).
                  Each pass walks all surviving faces once."
  [mesh ratio & {:keys [max-passes] :or {max-passes 20}}]
  (let [orig-verts (:vertices mesh)
        orig-faces (:faces mesh)
        n-verts (count orig-verts)
        n-faces (count orig-faces)
        target-faces (max 4 (int (* ratio n-faces)))

        ;; Mutable vertex positions (flat arrays for speed)
        vx (double-array n-verts)
        vy (double-array n-verts)
        vz (double-array n-verts)
        _ (dotimes [i n-verts]
            (let [v (nth orig-verts i)]
              (aset vx i (double (nth v 0)))
              (aset vy i (double (nth v 1)))
              (aset vz i (double (nth v 2)))))

        ;; Face storage: int-array [a b c] per face, nil = deleted
        face-arr (object-array n-faces)
        _ (dotimes [i n-faces]
            (let [f (nth orig-faces i)]
              (aset face-arr i (int-array [(int (nth f 0))
                                           (int (nth f 1))
                                           (int (nth f 2))]))))

        ;; Remap: vertex i → canonical index (follow collapse chains)
        remap (int-array n-verts)
        _ (dotimes [i n-verts] (aset remap i (int i)))

        resolve-v (fn [v]
                    (loop [v (int v)]
                      (let [r (aget remap v)]
                        (if (= r v) v (recur r)))))

        edge-len-sq (fn [a b]
                      (let [dx (- (aget vx a) (aget vx b))
                            dy (- (aget vy a) (aget vy b))
                            dz (- (aget vz a) (aget vz b))]
                        (+ (* dx dx) (* dy dy) (* dz dz))))

        live-faces (atom n-faces)

        ;; Compute average edge length for threshold calibration
        avg-edge-len
        (let [sum (atom 0.0)
              cnt (atom 0)]
          (dotimes [fi (min n-faces 10000)] ;; sample first 10k faces
            (when-let [^ints f (aget face-arr fi)]
              (let [a (aget f 0) b (aget f 1) c (aget f 2)]
                (swap! sum + (Math/sqrt (edge-len-sq a b)))
                (swap! sum + (Math/sqrt (edge-len-sq b c)))
                (swap! sum + (Math/sqrt (edge-len-sq a c)))
                (swap! cnt + 3))))
          (if (pos? @cnt) (/ @sum @cnt) 1.0))

        ;; Threshold: start at a fraction of average edge length,
        ;; increase each pass if not enough faces were removed.
        ;; The idea: edges much shorter than average are on flat regions
        ;; and safe to collapse.
        base-threshold (* avg-edge-len (- 1.0 ratio))]

    ;; Multi-pass collapse
    (loop [pass 0
           threshold base-threshold]
      (when (and (> @live-faces target-faces) (< pass max-passes))
        (let [threshold-sq (* threshold threshold)
              before @live-faces
              ;; Single pass: walk all faces, collapse short edges
              _ (dotimes [fi n-faces]
                  (when (> @live-faces target-faces)
                    (when-let [^ints f (aget face-arr fi)]
                      ;; Resolve current vertex indices
                      (let [a (resolve-v (aget f 0))
                            b (resolve-v (aget f 1))
                            c (resolve-v (aget f 2))]
                        ;; Update face to resolved indices
                        (aset f 0 (int a))
                        (aset f 1 (int b))
                        (aset f 2 (int c))
                        ;; Check if already degenerate
                        (if (or (= a b) (= b c) (= a c))
                          (do (aset face-arr fi nil)
                              (swap! live-faces dec))
                          ;; Find shortest edge in this face
                          (let [lab (edge-len-sq a b)
                                lbc (edge-len-sq b c)
                                lac (edge-len-sq a c)
                                min-len (min lab lbc lac)]
                            (when (<= min-len threshold-sq)
                              ;; Collapse the shortest edge
                              (let [[va vb] (cond
                                              (= min-len lab) [a b]
                                              (= min-len lbc) [b c]
                                              :else [a c])]
                                ;; Move va to midpoint
                                (aset vx va (* 0.5 (+ (aget vx va) (aget vx vb))))
                                (aset vy va (* 0.5 (+ (aget vy va) (aget vy vb))))
                                (aset vz va (* 0.5 (+ (aget vz va) (aget vz vb))))
                                ;; Remap vb → va
                                (aset remap vb (int va))
                                ;; This face is now degenerate (va appears twice)
                                (aset f 0 (int va))
                                (when (= (aget f 1) vb) (aset f 1 (int va)))
                                (when (= (aget f 2) vb) (aset f 2 (int va)))
                                (when (or (= (aget f 0) (aget f 1))
                                          (= (aget f 1) (aget f 2))
                                          (= (aget f 0) (aget f 2)))
                                  (aset face-arr fi nil)
                                  (swap! live-faces dec))))))))))
              after @live-faces
              progress (- before after)]
          ;; If little progress, increase threshold for next pass
          (when (pos? progress)
            (recur (inc pass)
                   (if (< progress (/ (- before target-faces) 3))
                     (* threshold 1.5) ;; not enough removed, widen threshold
                     threshold))))))

    ;; Resolve all surviving faces and compact
    (let [surviving (java.util.ArrayList.)]
      (dotimes [fi n-faces]
        (when-let [^ints f (aget face-arr fi)]
          (let [a (resolve-v (aget f 0))
                b (resolve-v (aget f 1))
                c (resolve-v (aget f 2))]
            (when (and (not= a b) (not= b c) (not= a c))
              (.add surviving (int-array [a b c]))))))
      (let [used (let [s (java.util.HashSet.)]
                   (dotimes [i (.size surviving)]
                     (let [^ints f (.get surviving i)]
                       (.add s (Integer. (aget f 0)))
                       (.add s (Integer. (aget f 1)))
                       (.add s (Integer. (aget f 2)))))
                   s)
            sorted-v (vec (sort (seq used)))
            old->new (let [m (java.util.HashMap.)]
                       (dotimes [i (count sorted-v)]
                         (.put m (Integer. (int (nth sorted-v i))) (Integer. (int i))))
                       m)
            new-verts (mapv (fn [i] [(aget vx (int i)) (aget vy (int i)) (aget vz (int i))])
                            sorted-v)
            new-faces (let [result (transient [])]
                        (dotimes [i (.size surviving)]
                          (let [^ints f (.get surviving i)]
                            (conj! result [(.get old->new (Integer. (aget f 0)))
                                           (.get old->new (Integer. (aget f 1)))
                                           (.get old->new (Integer. (aget f 2)))])))
                        (persistent! result))]
        (println (str "mesh-simplify: " n-faces " -> " (count new-faces)
                      " faces (" (int (* 100.0 (/ (double (count new-faces)) n-faces))) "%),"
                      " " n-verts " -> " (count new-verts) " verts"))
        (cond-> (assoc mesh :vertices new-verts :faces new-faces)
          (:creation-pose mesh) (assoc :creation-pose (:creation-pose mesh))
          (:material mesh) (assoc :material (:material mesh)))))))
