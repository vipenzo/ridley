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
                  (persistent! acc)))]
    {:type :mesh
     :vertices vertices
     :faces faces}))

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

(defn generate-library-source
  "Generate library source code from an STL ArrayBuffer.
   The generated code uses decode-mesh to reconstruct the mesh at eval time."
  [^js array-buffer filename]
  (let [mesh-data (if (binary-stl? array-buffer)
                    (parse-binary-stl array-buffer)
                    (let [decoder (js/TextDecoder.)
                          text (.decode decoder array-buffer)]
                      (parse-ascii-stl text)))
        {:keys [vertices-b64 faces-b64 num-vertices num-faces]}
        (encode-mesh-base64 mesh-data)
        def-name (sanitize-name filename)]
    (str ";; Imported from STL: " filename "\n"
         ";; " num-vertices " vertices, " num-faces " faces\n\n"
         "(def " def-name "\n"
         "  (decode-mesh\n"
         "    \"" vertices-b64 "\"\n"
         "    \"" faces-b64 "\"))\n")))

(defn filename->lib-name
  "Derive a library name from an STL filename."
  [filename]
  (sanitize-name filename))
