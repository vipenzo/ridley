(ns ridley.io.stl
  "Binary STL import/export for Ridley meshes.

   STL binary format:
   - 80 bytes header
   - 4 bytes: number of triangles (uint32 LE)
   - Per triangle:
     - 12 bytes: normal (3x float32 LE)
     - 36 bytes: 3 vertices (3x 3x float32 LE)
     - 2 bytes: attribute byte count (uint16 LE, usually 0)"
  (:import [java.io DataOutputStream DataInputStream
            FileOutputStream FileInputStream
            BufferedOutputStream BufferedInputStream
            ByteArrayOutputStream]
           [java.nio ByteBuffer ByteOrder]))

;; ── Mesh cleanup for export ──────────────────────────────────────

(defn clean-for-export
  "Prepare a mesh for STL export: snap vertices to float32, merge duplicates,
   and remove degenerate faces. This prevents non-manifold edges that slicers
   flag when near-coincident float64 vertices collapse in float32."
  [mesh]
  (let [verts (:vertices mesh)
        ;; Snap to float32 (STL precision) and build dedup map
        dedup (java.util.HashMap.)
        new-verts (java.util.ArrayList.)
        index-map (int-array (count verts))
        _ (dotimes [i (count verts)]
            (let [[x y z] (verts i)
                  key [(float x) (float y) (float z)]]
              (if-let [idx (.get dedup key)]
                (aset index-map i (int idx))
                (let [idx (.size new-verts)]
                  (.put dedup key idx)
                  (.add new-verts [(double (float x)) (double (float y)) (double (float z))])
                  (aset index-map i idx)))))
        ;; Remap faces, removing degenerate and duplicate triangles
        seen-faces (java.util.HashSet.)
        new-faces (into []
                        (comp (map (fn [[i j k]]
                                     [(aget index-map i) (aget index-map j) (aget index-map k)]))
                              (remove (fn [[i j k]] (or (= i j) (= j k) (= i k))))
                              (remove (fn [[i j k]]
                                        ;; Canonical key: sorted vertex indices
                                        (let [key (if (<= i j k) [i j k]
                                                      (if (<= i k j) [i k j]
                                                          (if (<= j i k) [j i k]
                                                              (if (<= j k i) [j k i]
                                                                  (if (<= k i j) [k i j]
                                                                      [k j i])))))]
                                          (not (.add seen-faces key))))))
                        (:faces mesh))]
    (assoc mesh
           :vertices (vec (.toArray new-verts))
           :faces new-faces)))

;; ── Export ───────────────────────────────────────────────────────

(defn- compute-normal
  "Compute unit normal for a triangle from three vertices."
  [[x0 y0 z0] [x1 y1 z1] [x2 y2 z2]]
  (let [ux (- x1 x0) uy (- y1 y0) uz (- z1 z0)
        vx (- x2 x0) vy (- y2 y0) vz (- z2 z0)
        nx (- (* uy vz) (* uz vy))
        ny (- (* uz vx) (* ux vz))
        nz (- (* ux vy) (* uy vx))
        len (Math/sqrt (+ (* nx nx) (* ny ny) (* nz nz)))]
    (if (> len 0)
      [(/ nx len) (/ ny len) (/ nz len)]
      [0.0 0.0 1.0])))

(defn- write-float-le
  "Write a little-endian float32 to a ByteBuffer."
  [^ByteBuffer buf ^double v]
  (.putFloat buf (float v)))

(defn mesh->stl-bytes
  "Convert a Ridley mesh to binary STL as a byte array."
  [mesh]
  (let [vertices (:vertices mesh)
        faces (:faces mesh)
        n-tris (count faces)
        buf-size (+ 80 4 (* n-tris 50))
        buf (doto (ByteBuffer/allocate buf-size)
              (.order ByteOrder/LITTLE_ENDIAN))]
    ;; Header (80 bytes)
    (let [header (.getBytes "Ridley STL Export" "ASCII")
          pad (byte-array 80)]
      (System/arraycopy header 0 pad 0 (min 80 (alength header)))
      (.put buf pad))
    ;; Triangle count
    (.putInt buf n-tris)
    ;; Triangles
    (doseq [[i j k] faces]
      (let [v0 (nth vertices i)
            v1 (nth vertices j)
            v2 (nth vertices k)
            [nx ny nz] (compute-normal v0 v1 v2)]
        ;; Normal
        (write-float-le buf nx)
        (write-float-le buf ny)
        (write-float-le buf nz)
        ;; Vertices
        (doseq [[x y z] [v0 v1 v2]]
          (write-float-le buf x)
          (write-float-le buf y)
          (write-float-le buf z))
        ;; Attribute byte count
        (.putShort buf (short 0))))
    (.array buf)))

(defn save-stl
  "Save a mesh to a binary STL file.
   (save-stl mesh \"/path/to/file.stl\")"
  [mesh path]
  (let [bytes (mesh->stl-bytes mesh)]
    (with-open [out (BufferedOutputStream. (FileOutputStream. path))]
      (.write out bytes))
    (println (str "Saved " path " (" (count (:faces mesh)) " triangles, "
                  (quot (alength bytes) 1024) " KB)"))
    path))

;; ── Import ───────────────────────────────────────────────────────

(defn- read-float-le
  "Read a little-endian float32 from a ByteBuffer."
  [^ByteBuffer buf]
  (double (.getFloat buf)))

(defn load-stl
  "Load a binary STL file into a Ridley mesh.
   (load-stl \"/path/to/file.stl\")
   Returns {:type :mesh :vertices [...] :faces [...]}"
  [path]
  (let [file (java.io.File. path)
        bytes (byte-array (.length file))
        _ (with-open [in (BufferedInputStream. (FileInputStream. file))]
            (.read in bytes))
        buf (doto (ByteBuffer/wrap bytes)
              (.order ByteOrder/LITTLE_ENDIAN))]
    ;; Skip 80-byte header
    (.position buf 80)
    (let [n-tris (.getInt buf)
          vert-map (atom {})
          vert-list (atom [])
          get-or-add (fn [v]
                       (if-let [idx (get @vert-map v)]
                         idx
                         (let [idx (count @vert-list)]
                           (swap! vert-list conj v)
                           (swap! vert-map assoc v idx)
                           idx)))
          faces (vec (for [_ (range n-tris)]
                       (do
                         ;; Skip normal
                         (read-float-le buf) (read-float-le buf) (read-float-le buf)
                         (let [v0 [(read-float-le buf) (read-float-le buf) (read-float-le buf)]
                               v1 [(read-float-le buf) (read-float-le buf) (read-float-le buf)]
                               v2 [(read-float-le buf) (read-float-le buf) (read-float-le buf)]
                               _ (.getShort buf)]
                           [(get-or-add v0) (get-or-add v1) (get-or-add v2)]))))]
      (println (str "Loaded " path " (" n-tris " triangles, "
                    (count @vert-list) " unique vertices)"))
      {:type :mesh
       :vertices @vert-list
       :faces faces
       :creation-pose {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}})))
