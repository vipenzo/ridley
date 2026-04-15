(ns ridley.export.threemf
  "Browser-side 3MF export for Ridley meshes.

   3MF is a ZIP archive containing XML model data. Unlike STL (float32),
   3MF uses indexed vertices with full f64 precision and preserves topology.

   Multi-mesh export emits one <object> per mesh and one <item> per object."
  (:require ["jszip" :as JSZip]
            [ridley.manifold.core :as manifold])
  (:import [goog.string StringBuffer]))

(defn- mesh-vertices-xml
  "Append <vertices> for one mesh to the buffer. Reads raw arrays when present."
  [mesh ^StringBuffer sb]
  (.append sb "        <vertices>\n")
  (if-let [{:keys [vert-props num-prop]} (::manifold/raw-arrays mesh)]
    (when (and vert-props (>= num-prop 3))
      (let [n (/ (.-length vert-props) num-prop)]
        (loop [i 0]
          (when (< i n)
            (let [base (* i num-prop)]
              (.append sb "          <vertex x=\"")
              (.append sb (aget vert-props base))
              (.append sb "\" y=\"")
              (.append sb (aget vert-props (+ base 1)))
              (.append sb "\" z=\"")
              (.append sb (aget vert-props (+ base 2)))
              (.append sb "\"/>\n"))
            (recur (inc i))))))
    (doseq [[x y z] (:vertices mesh)]
      (.append sb "          <vertex x=\"")
      (.append sb x)
      (.append sb "\" y=\"")
      (.append sb y)
      (.append sb "\" z=\"")
      (.append sb z)
      (.append sb "\"/>\n")))
  (.append sb "        </vertices>\n"))

(defn- mesh-triangles-xml
  [mesh ^StringBuffer sb]
  (.append sb "        <triangles>\n")
  (if-let [{:keys [tri-verts]} (::manifold/raw-arrays mesh)]
    (when tri-verts
      (let [n (/ (.-length tri-verts) 3)]
        (loop [i 0]
          (when (< i n)
            (let [base (* i 3)]
              (.append sb "          <triangle v1=\"")
              (.append sb (aget tri-verts base))
              (.append sb "\" v2=\"")
              (.append sb (aget tri-verts (+ base 1)))
              (.append sb "\" v3=\"")
              (.append sb (aget tri-verts (+ base 2)))
              (.append sb "\"/>\n"))
            (recur (inc i))))))
    (doseq [[v1 v2 v3] (:faces mesh)]
      (.append sb "          <triangle v1=\"")
      (.append sb v1)
      (.append sb "\" v2=\"")
      (.append sb v2)
      (.append sb "\" v3=\"")
      (.append sb v3)
      (.append sb "\"/>\n")))
  (.append sb "        </triangles>\n"))

(defn- meshes->model-xml
  "Build the 3D/3dmodel.model XML string for one or more meshes.
   Each mesh becomes its own <object id=\"N\"> with a corresponding <item>."
  [meshes]
  (let [sb (StringBuffer.)]
    (.append sb "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    (.append sb "<model unit=\"millimeter\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\">\n")
    (.append sb "  <resources>\n")
    (doseq [[idx mesh] (map-indexed vector meshes)]
      (let [id (inc idx)]
        (.append sb "    <object id=\"")
        (.append sb id)
        (.append sb "\" type=\"model\">\n      <mesh>\n")
        (mesh-vertices-xml mesh sb)
        (mesh-triangles-xml mesh sb)
        (.append sb "      </mesh>\n    </object>\n")))
    (.append sb "  </resources>\n  <build>\n")
    (doseq [idx (range (count meshes))]
      (.append sb "    <item objectid=\"")
      (.append sb (inc idx))
      (.append sb "\"/>\n"))
    (.append sb "  </build>\n</model>\n")
    (.toString sb)))

(def ^:private content-types-xml
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n"
       "  <Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n"
       "  <Default Extension=\"model\" ContentType=\"application/vnd.ms-package.3dmanufacturing-3dmodel+xml\"/>\n"
       "</Types>\n"))

(def ^:private rels-xml
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n"
       "  <Relationship Target=\"/3D/3dmodel.model\" Id=\"rel0\" Type=\"http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel\"/>\n"
       "</Relationships>\n"))

(defn meshes->3mf-blob
  "Build a 3MF Blob from one or more meshes. Returns a Promise<Blob>."
  [meshes]
  (let [zip (JSZip.)
        model-xml (meshes->model-xml meshes)]
    (.file zip "[Content_Types].xml" content-types-xml)
    (.file zip "_rels/.rels" rels-xml)
    (.file zip "3D/3dmodel.model" model-xml)
    (.generateAsync zip #js {:type "blob"
                             :mimeType "model/3mf"
                             :compression "DEFLATE"})))
