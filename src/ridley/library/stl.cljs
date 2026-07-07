(ns ridley.library.stl
  "STL import: parse STL files into Ridley 3D meshes.

   Two layers:
   1. Runtime function (SCI binding): decode-mesh
      - Decodes base64-encoded vertex/face data back into a Ridley mesh
      - Used in generated library source code at eval time
   2. Code generation: generate-library-source
      - Used at import time to parse an STL file and produce library source"
  (:require [clojure.string :as str]))

;; ============================================================
;; Base64 helpers
;; ============================================================

(defn- array-buffer->base64
  "Encode an ArrayBuffer as a base64 string."
  [^js array-buffer]
  (let [bytes (js/Uint8Array. array-buffer)
        len (.-length bytes)
        parts (js/Array. len)]
    (loop [i 0]
      (when (< i len)
        (aset parts i (.fromCharCode js/String (aget bytes i)))
        (recur (inc i))))
    (js/btoa (.join parts ""))))

(defn- base64->array-buffer
  "Decode a base64 string to an ArrayBuffer."
  [b64-string]
  (let [binary (js/atob b64-string)
        len (.-length binary)
        bytes (js/Uint8Array. len)]
    (loop [i 0]
      (when (< i len)
        (aset bytes i (.charCodeAt binary i))
        (recur (inc i))))
    (.-buffer bytes)))

;; ============================================================
;; Runtime function (SCI binding)
;; ============================================================

(defn ^:export decode-mesh
  "Decode base64-encoded vertex and face data into a Ridley mesh.
   vertices-b64: base64 of packed Float32Array (x,y,z triples)
   faces-b64: base64 of packed Uint32Array (i,j,k triples)

   The creation-pose position is set to the bbox center of the loaded
   vertices so that operations that move the mesh (e.g. mesh-translate)
   keep the pose anchored to the geometry.

   Returns {:type :mesh :vertices [[x y z]...] :faces [[i j k]...]}"
  [vertices-b64 faces-b64]
  (let [vert-f32 (js/Float32Array. (base64->array-buffer vertices-b64))
        face-u32 (js/Uint32Array. (base64->array-buffer faces-b64))
        vlen (.-length vert-f32)
        vertices (loop [i 0, acc (transient [])]
                   (if (< i vlen)
                     (recur (+ i 3)
                            (conj! acc [(aget vert-f32 i)
                                        (aget vert-f32 (+ i 1))
                                        (aget vert-f32 (+ i 2))]))
                     (persistent! acc)))
        flen (.-length face-u32)
        faces (loop [i 0, acc (transient [])]
                (if (< i flen)
                  (recur (+ i 3)
                         (conj! acc [(aget face-u32 i)
                                     (aget face-u32 (+ i 1))
                                     (aget face-u32 (+ i 2))]))
                  (persistent! acc)))
        center (if (seq vertices)
                 (let [v0 (first vertices)
                       [mn mx] (reduce (fn [[mn mx] v]
                                         [(mapv min mn v) (mapv max mx v)])
                                       [v0 v0]
                                       (rest vertices))]
                   (mapv (fn [a b] (/ (+ a b) 2.0)) mn mx))
                 [0 0 0])]
    {:type :mesh
     :vertices vertices
     :faces faces
     :creation-pose {:position center :heading [1 0 0] :up [0 0 1]}}))

;; ============================================================
;; STL format detection
;; ============================================================

(defn- binary-stl?
  "Detect if an ArrayBuffer is a binary STL.
   Heuristic: byteLength == 84 + num_triangles * 50."
  [^js array-buffer]
  (let [size (.-byteLength array-buffer)]
    (when (>= size 84)
      (let [view (js/DataView. array-buffer)
            num-triangles (.getUint32 view 80 true)
            expected-size (+ 84 (* num-triangles 50))]
        (= size expected-size)))))

;; ============================================================
;; Binary STL parser
;; ============================================================

(defn- parse-binary-stl
  "Parse a binary STL ArrayBuffer.
   Returns {:vertices [[x y z]...] :faces [[i j k]...]}
   with welded (deduplicated) vertices."
  [^js array-buffer]
  (let [view (js/DataView. array-buffer)
        num-triangles (.getUint32 view 80 true)
        vertex-map (js/Map.)
        vertices (js/Array.)
        faces (transient [])]
    (loop [tri 0, offset 84]
      (when (< tri num-triangles)
        ;; Skip normal (12 bytes), read 3 vertices (36 bytes), skip attr (2 bytes)
        (let [v-offset (+ offset 12)
              face-indices (js/Array.)]
          (dotimes [v 3]
            (let [base (+ v-offset (* v 12))
                  x (.getFloat32 view base true)
                  y (.getFloat32 view (+ base 4) true)
                  z (.getFloat32 view (+ base 8) true)
                  key (str x "," y "," z)]
              (if-let [existing-idx (.get vertex-map key)]
                (.push face-indices existing-idx)
                (let [idx (.-length vertices)]
                  (.push vertices #js [x y z])
                  (.set vertex-map key idx)
                  (.push face-indices idx)))))
          (conj! faces [(aget face-indices 0)
                        (aget face-indices 1)
                        (aget face-indices 2)])
          (recur (inc tri) (+ offset 50)))))
    {:vertices (vec (for [i (range (.-length vertices))]
                      (let [v (aget vertices i)]
                        [(aget v 0) (aget v 1) (aget v 2)])))
     :faces (persistent! faces)}))

;; ============================================================
;; ASCII STL parser
;; ============================================================

(defn- parse-ascii-stl
  "Parse an ASCII STL string.
   Returns {:vertices [[x y z]...] :faces [[i j k]...]}
   with welded vertices."
  [text]
  (let [lines (str/split-lines text)
        vertex-map (js/Map.)
        vertices (js/Array.)
        faces (transient [])
        current-face (js/Array.)]
    (doseq [line lines]
      (let [trimmed (str/trim line)]
        (when (str/starts-with? trimmed "vertex ")
          (let [parts (str/split (str/trim (subs trimmed 7)) #"\s+")
                x (js/parseFloat (nth parts 0))
                y (js/parseFloat (nth parts 1))
                z (js/parseFloat (nth parts 2))
                key (str x "," y "," z)
                idx (or (.get vertex-map key)
                        (let [idx (.-length vertices)]
                          (.push vertices #js [x y z])
                          (.set vertex-map key idx)
                          idx))]
            (.push current-face idx)
            (when (= 3 (.-length current-face))
              (conj! faces [(aget current-face 0)
                            (aget current-face 1)
                            (aget current-face 2)])
              (set! (.-length current-face) 0))))))
    {:vertices (vec (for [i (range (.-length vertices))]
                      (let [v (aget vertices i)]
                        [(aget v 0) (aget v 1) (aget v 2)])))
     :faces (persistent! faces)}))

;; ============================================================
;; Mesh encoding for library source
;; ============================================================

(defn- encode-mesh-base64
  "Encode mesh vertices and faces as base64 strings.
   Returns {:vertices-b64 :faces-b64 :num-vertices :num-faces}."
  [{:keys [vertices faces]}]
  (let [vert-f32 (js/Float32Array. (* (count vertices) 3))
        face-u32 (js/Uint32Array. (* (count faces) 3))]
    (reduce (fn [off [x y z]]
              (aset vert-f32 off x)
              (aset vert-f32 (+ off 1) y)
              (aset vert-f32 (+ off 2) z)
              (+ off 3))
            0 vertices)
    (reduce (fn [off [i j k]]
              (aset face-u32 off i)
              (aset face-u32 (+ off 1) j)
              (aset face-u32 (+ off 2) k)
              (+ off 3))
            0 faces)
    {:vertices-b64 (array-buffer->base64 (.-buffer vert-f32))
     :faces-b64 (array-buffer->base64 (.-buffer face-u32))
     :num-vertices (count vertices)
     :num-faces (count faces)}))

;; ============================================================
;; Code generation
;; ============================================================

(defn- sanitize-name
  "Convert a filename stem to a valid Clojure symbol name."
  [s]
  (let [result (-> s
                   (str/replace #"\.[^.]+$" "")
                   (str/replace #"[^a-zA-Z0-9\-_]" "-")
                   (str/replace #"-+" "-")
                   (str/replace #"^-|-$" "")
                   (str/lower-case))]
    (if (empty? result) "model" result)))

(defn- mesh-bounds
  "Compute axis-aligned bounding box from vertex list [[x y z] ...]."
  [vertices]
  (when (seq vertices)
    (reduce (fn [[mn mx] v]
              [(mapv min mn v) (mapv max mx v)])
            [(first vertices) (first vertices)]
            (rest vertices))))

(defn- bbox-center
  "Return the bbox center [cx cy cz] of a vertex list, or nil if empty."
  [vertices]
  (when-let [[mn mx] (mesh-bounds vertices)]
    (mapv (fn [a b] (/ (+ a b) 2.0)) mn mx)))

(defn- scale-warning
  "Return a warning comment if the mesh looks like it uses meters instead of mm."
  [vertices def-name]
  (when-let [[mn mx] (mesh-bounds vertices)]
    (let [size (mapv - mx mn)
          max-dim (apply max size)]
      (when (< max-dim 1.0)
        (let [suggested (cond (< max-dim 0.01) 1000
                              (< max-dim 0.1)  100
                              (< max-dim 1.0)  10
                              :else nil)]
          (str ";; WARNING: mesh is very small (max dimension "
               (.toFixed max-dim 3) " units).\n"
               ";; If your STL uses meters, scale it:\n"
               ";; (mesh-scale " def-name " " suggested ")\n"))))))

(defn- format-coord [n]
  (.toFixed n 3))

;; ============================================================
;; Path-based import (desktop only, SCI binding)
;; ============================================================

(def ^:private geo-server-url "http://127.0.0.1:12321")

(defn- read-file-bytes-sync
  "Synchronously read a file from disk via the desktop geo_server, returning an
   ArrayBuffer. Uses a synchronous XHR with the text/x-user-defined charset so
   raw bytes survive (synchronous XHR cannot set responseType to 'arraybuffer').
   Throws with a readable message when the server is unreachable or the read fails."
  [path]
  (let [xhr (js/XMLHttpRequest.)]
    (try
      (.open xhr "POST" (str geo-server-url "/read-file") false)
      (.setRequestHeader xhr "X-File-Path" path)
      (.overrideMimeType xhr "text/plain; charset=x-user-defined")
      (.send xhr "")
      (catch :default _
        (throw (js/Error. (str "import-stl: desktop file server unavailable"
                               " (this feature is desktop-only). Path: " path)))))
    (if (= 200 (.-status xhr))
      (let [text (.-responseText xhr)
            len (.-length text)
            bytes (js/Uint8Array. len)]
        (dotimes [i len]
          (aset bytes i (bit-and (.charCodeAt text i) 0xff)))
        (.-buffer bytes))
      (throw (js/Error. (str "import-stl: could not read " path
                             " (HTTP " (.-status xhr) ")"))))))

(defn- parsed->mesh
  "Wrap a parsed {:vertices :faces} into a Ridley mesh whose creation-pose sits
   at the bbox center of the geometry (so pose stays anchored when later moved).
   When `recenter` is true, translate the geometry so its bbox center lands at
   the origin (mirroring the menu import's automatic mesh-translate)."
  [{:keys [vertices faces]} recenter]
  (let [center (or (bbox-center vertices) [0 0 0])
        offset (if recenter (mapv - center) [0 0 0])
        verts (if recenter (mapv #(mapv + % offset) vertices) vertices)
        pose-pos (if recenter [0 0 0] center)]
    {:type :mesh
     :vertices verts
     :faces faces
     :creation-pose {:position pose-pos :heading [1 0 0] :up [0 0 1]}}))

(defn ^:export import-stl
  "Read an STL file from disk and return a Ridley mesh (desktop only).

   `path` is a filesystem path to a .stl file (binary or ASCII, auto-detected).
   Options:
     :recenter  when true, translate the mesh so its bounding-box center sits at
                the origin (default false — keep the STL's own coordinates).

   Unlike a base64-inlined `decode-mesh`, the geometry is NOT embedded in the
   script: the .clj only references the path, so a model can be shared even when
   the STL itself may not be redistributed — the recipient re-downloads it from
   the original source. Requires the desktop geo_server (throws in the web build).

   Returns {:type :mesh :vertices [[x y z]...] :faces [[i j k]...] :creation-pose ...}."
  [path & {:keys [recenter] :or {recenter false}}]
  (let [array-buffer (read-file-bytes-sync path)
        mesh-data (if (binary-stl? array-buffer)
                    (parse-binary-stl array-buffer)
                    (parse-ascii-stl (.decode (js/TextDecoder.) array-buffer)))]
    (parsed->mesh mesh-data recenter)))

(defn generate-library-source
  "Generate library source code from an STL ArrayBuffer.
   The generated code uses decode-mesh to reconstruct the mesh at eval time.
   When the parsed mesh sits far from the origin, an explicit mesh-translate
   call is emitted so the user can see, edit, or remove the recentering."
  [^js array-buffer filename]
  (let [mesh-data (if (binary-stl? array-buffer)
                    (parse-binary-stl array-buffer)
                    (let [decoder (js/TextDecoder.)
                          text (.decode decoder array-buffer)]
                      (parse-ascii-stl text)))
        {:keys [vertices-b64 faces-b64 num-vertices num-faces]}
        (encode-mesh-base64 mesh-data)
        def-name (sanitize-name filename)
        warn (scale-warning (:vertices mesh-data) def-name)
        center (bbox-center (:vertices mesh-data))
        offset (when (and center (some #(> (Math/abs %) 0.001) center))
                 (mapv - center))]
    (str (when warn warn)
         ";; " num-vertices " vertices, " num-faces " faces\n"
         (when offset
           (str ";; Imported mesh sat at bbox center ["
                (format-coord (nth center 0)) " "
                (format-coord (nth center 1)) " "
                (format-coord (nth center 2)) "].\n"
                ";; Recentered on origin via mesh-translate — remove or edit if not desired.\n"))
         "(def " def-name "\n"
         (if offset
           (str "  (-> (decode-mesh\n"
                "        \"" vertices-b64 "\"\n"
                "        \"" faces-b64 "\")\n"
                "      (mesh-translate ["
                (format-coord (nth offset 0)) " "
                (format-coord (nth offset 1)) " "
                (format-coord (nth offset 2)) "])))\n")
           (str "  (decode-mesh\n"
                "    \"" vertices-b64 "\"\n"
                "    \"" faces-b64 "\"))\n")))))

(defn filename->lib-name
  "Derive a library name from an STL filename."
  [filename]
  (sanitize-name filename))
