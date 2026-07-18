(ns ridley.library.obj
  "Wavefront OBJ parsing.

   Scope is deliberately geometry-only, per the scanner-channel brief: `v`
   and `f` are read, everything else (`vt`, `vn`, `usemtl`, `mtllib`, `o`,
   `g`, `s`) is skipped. An OBJ whose .mtl or texture is missing must import
   cleanly — materials are simply never consulted, so their absence cannot
   be an error.

   Unlike STL, OBJ is already an indexed format: vertices are shared by
   index, so no welding pass is needed and the vertex list maps straight
   through. This matters for scans, where welding hundreds of thousands of
   triangles by string key is the slowest part of the STL path.")

(defn- parse-index
  "Resolve one OBJ face index to a 0-based vertex index.
   OBJ indices are 1-based; negative values are relative to the end of the
   vertex list so far (-1 = most recent vertex)."
  [token n-verts]
  (let [slash (.indexOf token "/")
        v-part (if (neg? slash) token (.substring token 0 slash))
        idx (js/parseInt v-part 10)]
    (when-not (js/isNaN idx)
      (cond
        (pos? idx) (dec idx)
        (neg? idx) (+ n-verts idx)
        :else nil))))

(defn parse-obj
  "Parse OBJ text into {:vertices [[x y z]...] :faces [[i j k]...]}.

   Polygons with more than three vertices are fan-triangulated, which is
   correct for the convex faces OBJ exporters emit and is what every mesh
   consumer downstream expects."
  [text]
  (let [lines (.split text #"\r?\n")
        n-lines (alength lines)]
    (loop [i 0
           verts (transient [])
           faces (transient [])]
      (if (>= i n-lines)
        (let [v (persistent! verts)
              f (persistent! faces)]
          (when (empty? v)
            (throw (js/Error. "import-obj: no vertices found — is this an OBJ file?")))
          {:vertices v :faces f})
        (let [line (.trim (aget lines i))]
          (cond
            ;; vertex: "v x y z [w]"
            (and (= "v" (.charAt line 0))
                 (let [c (.charAt line 1)] (or (= " " c) (= "\t" c))))
            (let [parts (.split (.trim (.substring line 1)) #"\s+")]
              (recur (inc i)
                     (conj! verts [(js/parseFloat (aget parts 0))
                                   (js/parseFloat (aget parts 1))
                                   (js/parseFloat (aget parts 2))])
                     faces))

            ;; face: "f a b c ..." with a/b/c possibly "v", "v/vt",
            ;; "v//vn" or "v/vt/vn"
            (and (= "f" (.charAt line 0))
                 (let [c (.charAt line 1)] (or (= " " c) (= "\t" c))))
            (let [parts (.split (.trim (.substring line 1)) #"\s+")
                  n-parts (alength parts)
                  n-verts (count verts)
                  idxs (loop [k 0 acc []]
                         (if (>= k n-parts)
                           acc
                           (let [idx (parse-index (aget parts k) n-verts)]
                             (recur (inc k) (if idx (conj acc idx) acc)))))
                  n-idx (count idxs)]
              (recur (inc i)
                     verts
                     ;; fan triangulation from the first vertex
                     (loop [k 1 fs faces]
                       (if (>= k (dec n-idx))
                         fs
                         (recur (inc k)
                                (conj! fs [(nth idxs 0)
                                           (nth idxs k)
                                           (nth idxs (inc k))]))))))

            ;; everything else — vt, vn, usemtl, mtllib, o, g, s, comments,
            ;; blank lines — is not geometry and is skipped in silence
            :else
            (recur (inc i) verts faces)))))))
