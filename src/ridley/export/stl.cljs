(ns ridley.export.stl
  "Binary STL export for Ridley meshes.

   STL binary format:
   - 80 bytes header (arbitrary text)
   - 4 bytes: number of triangles (uint32 little-endian)
   - For each triangle:
     - 12 bytes: normal vector (3x float32 LE)
     - 36 bytes: 3 vertices (3x 3x float32 LE)
     - 2 bytes: attribute byte count (usually 0)"
  (:require [ridley.manifold.core :as manifold]))

(defn- compute-normal
  "Compute normal for a triangle from three vertices."
  [[x0 y0 z0] [x1 y1 z1] [x2 y2 z2]]
  (let [;; Edge vectors
        ux (- x1 x0) uy (- y1 y0) uz (- z1 z0)
        vx (- x2 x0) vy (- y2 y0) vz (- z2 z0)
        ;; Cross product
        nx (- (* uy vz) (* uz vy))
        ny (- (* uz vx) (* ux vz))
        nz (- (* ux vy) (* uy vx))
        ;; Normalize
        len (js/Math.sqrt (+ (* nx nx) (* ny ny) (* nz nz)))]
    (if (> len 0)
      [(/ nx len) (/ ny len) (/ nz len)]
      [0 0 1])))

(defn- write-float32-le
  "Write a float32 to DataView at offset in little-endian."
  [^js data-view offset value]
  (.setFloat32 data-view offset value true))

(defn- write-uint32-le
  "Write a uint32 to DataView at offset in little-endian."
  [^js data-view offset value]
  (.setUint32 data-view offset value true))

(defn- write-uint16-le
  "Write a uint16 to DataView at offset in little-endian."
  [^js data-view offset value]
  (.setUint16 data-view offset value true))

(defn- write-stl-header!
  "Write the 80-byte STL header and triangle count to a DataView."
  [^js view num-triangles]
  (let [header "Ridley STL Export"
        header-bytes (.from js/Uint8Array (map #(.charCodeAt % 0) header))]
    (doseq [i (range (min 80 (.-length header-bytes)))]
      (.setUint8 view i (aget header-bytes i)))
    (write-uint32-le view 80 num-triangles)))

(defn- mesh->stl-binary-raw
  "Fast path: write STL directly from typed arrays (no CLJS vector access)."
  [^js vert-props ^js tri-verts num-triangles]
  (let [buffer-size (+ 80 4 (* num-triangles 50))
        buffer (js/ArrayBuffer. buffer-size)
        view (js/DataView. buffer)]
    (write-stl-header! view num-triangles)
    (loop [face-idx 0, offset 84]
      (when (< face-idx num-triangles)
        (let [fi (* face-idx 3)
              i0 (aget tri-verts fi)
              i1 (aget tri-verts (+ fi 1))
              i2 (aget tri-verts (+ fi 2))
              vi0 (* i0 3) vi1 (* i1 3) vi2 (* i2 3)
              x0 (aget vert-props vi0) y0 (aget vert-props (+ vi0 1)) z0 (aget vert-props (+ vi0 2))
              x1 (aget vert-props vi1) y1 (aget vert-props (+ vi1 1)) z1 (aget vert-props (+ vi1 2))
              x2 (aget vert-props vi2) y2 (aget vert-props (+ vi2 1)) z2 (aget vert-props (+ vi2 2))
              ;; Normal: cross product of edges
              ux (- x1 x0) uy (- y1 y0) uz (- z1 z0)
              vx (- x2 x0) vy (- y2 y0) vz (- z2 z0)
              nx (- (* uy vz) (* uz vy))
              ny (- (* uz vx) (* ux vz))
              nz (- (* ux vy) (* uy vx))
              len (js/Math.sqrt (+ (* nx nx) (* ny ny) (* nz nz)))
              nx (if (> len 0) (/ nx len) 0)
              ny (if (> len 0) (/ ny len) 0)
              nz (if (> len 0) (/ nz len) 1)]
          (write-float32-le view offset nx)
          (write-float32-le view (+ offset 4) ny)
          (write-float32-le view (+ offset 8) nz)
          (write-float32-le view (+ offset 12) x0)
          (write-float32-le view (+ offset 16) y0)
          (write-float32-le view (+ offset 20) z0)
          (write-float32-le view (+ offset 24) x1)
          (write-float32-le view (+ offset 28) y1)
          (write-float32-le view (+ offset 32) z1)
          (write-float32-le view (+ offset 36) x2)
          (write-float32-le view (+ offset 40) y2)
          (write-float32-le view (+ offset 44) z2)
          (write-uint16-le view (+ offset 48) 0)
          (recur (inc face-idx) (+ offset 50)))))
    buffer))

(defn mesh->stl-binary
  "Convert a Ridley mesh to STL binary format.
   Returns an ArrayBuffer containing the STL data.
   Fast path when ::manifold/raw-arrays available (skips CLJS vector access)."
  [mesh]
  (if-let [{:keys [vert-props tri-verts num-prop]} (::manifold/raw-arrays mesh)]
    (when (= num-prop 3)
      (mesh->stl-binary-raw vert-props tri-verts (/ (.-length tri-verts) 3)))
    ;; Slow path: CLJS vectors
    (let [{:keys [vertices faces]} mesh
          num-triangles (count faces)
          buffer-size (+ 80 4 (* num-triangles 50))
          buffer (js/ArrayBuffer. buffer-size)
          view (js/DataView. buffer)]
      (write-stl-header! view num-triangles)
      (loop [face-idx 0
             offset 84]
        (when (< face-idx num-triangles)
          (let [[i0 i1 i2] (nth faces face-idx)
                v0 (nth vertices i0)
                v1 (nth vertices i1)
                v2 (nth vertices i2)
                [nx ny nz] (compute-normal v0 v1 v2)
                [x0 y0 z0] v0
                [x1 y1 z1] v1
                [x2 y2 z2] v2]
            (write-float32-le view offset nx)
            (write-float32-le view (+ offset 4) ny)
            (write-float32-le view (+ offset 8) nz)
            (write-float32-le view (+ offset 12) x0)
            (write-float32-le view (+ offset 16) y0)
            (write-float32-le view (+ offset 20) z0)
            (write-float32-le view (+ offset 24) x1)
            (write-float32-le view (+ offset 28) y1)
            (write-float32-le view (+ offset 32) z1)
            (write-float32-le view (+ offset 36) x2)
            (write-float32-le view (+ offset 40) y2)
            (write-float32-le view (+ offset 44) z2)
            (write-uint16-le view (+ offset 48) 0)
            (recur (inc face-idx) (+ offset 50)))))
      buffer)))

(defn meshes->stl-binary
  "Convert multiple meshes to a single STL binary.
   Combines all meshes into one STL file.
   For single meshes with raw arrays, delegates to fast path directly."
  [meshes]
  (if (= 1 (count meshes))
    (mesh->stl-binary (first meshes))
    ;; Multiple meshes: merge into one then export
    (let [merged (reduce
                  (fn [{:keys [vertices faces vertex-offset]} mesh]
                    (let [mesh-verts (:vertices mesh)
                          mesh-faces (:faces mesh)
                          offset-faces (mapv (fn [[i0 i1 i2]]
                                               [(+ i0 vertex-offset)
                                                (+ i1 vertex-offset)
                                                (+ i2 vertex-offset)])
                                             mesh-faces)]
                      {:vertices (into vertices mesh-verts)
                       :faces (into faces offset-faces)
                       :vertex-offset (+ vertex-offset (count mesh-verts))}))
                  {:vertices [] :faces [] :vertex-offset 0}
                  meshes)]
      (mesh->stl-binary {:vertices (:vertices merged)
                         :faces (:faces merged)}))))

(defn- download-blob-fallback
  "Download a blob using the traditional createElement('a') method."
  [blob filename]
  (let [url (js/URL.createObjectURL blob)
        link (js/document.createElement "a")]
    (set! (.-href link) url)
    (set! (.-download link) filename)
    (.click link)
    (js/URL.revokeObjectURL url)))

(defn download-stl
  "Download mesh(es) as an STL file.
   mesh-or-meshes: single mesh or vector of meshes
   filename: name for the downloaded file (default 'model.stl')"
  ([mesh-or-meshes]
   (download-stl mesh-or-meshes "model.stl"))
  ([mesh-or-meshes filename]
   (let [meshes (if (vector? (first (:vertices mesh-or-meshes)))
                  [mesh-or-meshes]
                  mesh-or-meshes)
         buffer (meshes->stl-binary meshes)
         blob (js/Blob. #js [buffer] #js {:type "application/octet-stream"})]
     (if (exists? js/window.showSaveFilePicker)
       (-> (js/window.showSaveFilePicker
             #js {:suggestedName filename
                  :types #js [#js {:description "STL files"
                                   :accept #js {"application/octet-stream" #js [".stl"]}}]})
           (.then (fn [handle] (.createWritable handle)))
           (.then (fn [writable]
                    (-> (.write writable blob)
                        (.then #(.close writable)))))
           (.then (fn [_] (str "Exported " (count meshes) " mesh(es) to " filename)))
           (.catch (fn [_err] nil)))
       (do (download-blob-fallback blob filename)
           (str "Exported " (count meshes) " mesh(es) to " filename))))))
