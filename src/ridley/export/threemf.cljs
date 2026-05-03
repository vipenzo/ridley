(ns ridley.export.threemf
  "Browser-side 3MF export for Ridley meshes.

   3MF is a ZIP archive containing XML model data. Unlike STL (float32),
   3MF uses indexed vertices with full f64 precision and preserves topology.

   Multi-mesh export emits one <object> per mesh and one <item> per object.
   When at least one mesh carries a :material :color, a <basematerials>
   block is emitted and each colored object gets pid/pindex pointing into
   it. Distinct hex colors are deduplicated so identical colors map to the
   same base material (i.e. same filament slot in the slicer).

   Bambu/Orca compatibility: as of BambuStudio 2.5, <basematerials> alone is
   ignored on import — both slicers load the file but assign every object to
   filament slot 1. To keep the multimaterial workflow alive we additionally
   emit Metadata/model_settings.config (a Bambu/Orca proprietary file) with
   an explicit per-object 'extruder' assignment derived from the same color
   palette. Slot N corresponds to the N-th distinct color seen, 1-based."
  (:require ["jszip" :as JSZip]
            [ridley.manifold.core :as manifold])
  (:import [goog.string StringBuffer]))

(def ^:private basematerials-id 100)

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

(defn- mesh-color
  "Return the color of a mesh as an integer (0xRRGGBB), or nil if absent."
  [mesh]
  (some-> mesh :material :color))

(defn- ^string color->display-hex
  "Convert an integer 0xRRGGBB to a 3MF displaycolor string '#RRGGBBFF'."
  [hex-int]
  (let [r (bit-and (bit-shift-right hex-int 16) 0xFF)
        g (bit-and (bit-shift-right hex-int 8) 0xFF)
        b (bit-and hex-int 0xFF)
        pad (fn [s] (if (= 1 (count s)) (str "0" s) s))]
    (str "#"
         (.toUpperCase (pad (.toString r 16)))
         (.toUpperCase (pad (.toString g 16)))
         (.toUpperCase (pad (.toString b 16)))
         "FF")))

(defn- build-color-index
  "Walk meshes once and build:
     :palette     vector of distinct hex colors in first-seen order
     :mesh->color map mesh-index -> palette-index (only for colored meshes)
   Distinct colors are deduped: two meshes with the same hex share an entry."
  [meshes]
  (loop [i 0
         remaining (seq meshes)
         palette []
         color->idx {}
         mesh->color {}]
    (if-let [m (first remaining)]
      (let [c (mesh-color m)]
        (cond
          (nil? c)
          (recur (inc i) (next remaining) palette color->idx mesh->color)

          (contains? color->idx c)
          (recur (inc i) (next remaining) palette color->idx
                 (assoc mesh->color i (color->idx c)))

          :else
          (let [idx (count palette)]
            (recur (inc i) (next remaining)
                   (conj palette c)
                   (assoc color->idx c idx)
                   (assoc mesh->color i idx)))))
      {:palette palette :mesh->color mesh->color})))

(defn- color-name
  "Pick a name for a basematerial entry: the export-name of the first mesh
   carrying that color, or 'color-N' if no colored mesh has a name."
  [meshes mesh->color color-idx]
  (or (some (fn [[mi m]]
              (when (and (= color-idx (get mesh->color mi))
                         (:export-name m))
                (name (:export-name m))))
            (map-indexed vector meshes))
      (str "color-" (inc color-idx))))

(defn- basematerials-xml
  [^StringBuffer sb meshes palette mesh->color]
  (.append sb "    <basematerials id=\"")
  (.append sb basematerials-id)
  (.append sb "\">\n")
  (doseq [[i hex] (map-indexed vector palette)]
    (.append sb "      <base name=\"")
    (.append sb (color-name meshes mesh->color i))
    (.append sb "\" displaycolor=\"")
    (.append sb (color->display-hex hex))
    (.append sb "\"/>\n"))
  (.append sb "    </basematerials>\n"))

(defn- object-open-tag
  [^StringBuffer sb id mesh color-idx]
  (.append sb "    <object id=\"")
  (.append sb id)
  (.append sb "\" type=\"model\"")
  (when-let [nm (:export-name mesh)]
    (.append sb " name=\"")
    (.append sb (name nm))
    (.append sb "\""))
  (when color-idx
    (.append sb " pid=\"")
    (.append sb basematerials-id)
    (.append sb "\" pindex=\"")
    (.append sb color-idx)
    (.append sb "\""))
  (.append sb ">\n"))

(defn- meshes->model-xml
  "Build the 3D/3dmodel.model XML string for one or more meshes.
   Each mesh becomes its own <object id=\"N\"> with a corresponding <item>.
   When any mesh carries a :material :color, also emits a <basematerials>
   block and pid/pindex on each colored object."
  [meshes palette mesh->color]
  (let [sb (StringBuffer.)
        any-colored? (seq palette)]
    (.append sb "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    (.append sb "<model unit=\"millimeter\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\">\n")
    (.append sb "  <resources>\n")
    (when any-colored?
      (basematerials-xml sb meshes palette mesh->color))
    (doseq [[idx mesh] (map-indexed vector meshes)]
      (object-open-tag sb (inc idx) mesh (get mesh->color idx))
      (.append sb "      <mesh>\n")
      (mesh-vertices-xml mesh sb)
      (mesh-triangles-xml mesh sb)
      (.append sb "      </mesh>\n    </object>\n"))
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
       "  <Default Extension=\"config\" ContentType=\"application/vnd.ms-printing.printticket+xml\"/>\n"
       "</Types>\n"))

(defn- model-settings-config
  "Build the Metadata/model_settings.config XML for Bambu/Orca.
   Emits one <object> entry per colored mesh, with extruder = palette-index + 1.
   Returns nil if no mesh is colored (no need to write the file)."
  [meshes mesh->color]
  (when (seq mesh->color)
    (let [sb (StringBuffer.)]
      (.append sb "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
      (.append sb "<config>\n")
      (doseq [[idx mesh] (map-indexed vector meshes)
              :let [color-idx (get mesh->color idx)]
              :when color-idx]
        (.append sb "  <object id=\"")
        (.append sb (inc idx))
        (.append sb "\">\n")
        (when-let [nm (:export-name mesh)]
          (.append sb "    <metadata key=\"name\" value=\"")
          (.append sb (name nm))
          (.append sb "\"/>\n"))
        (.append sb "    <metadata key=\"extruder\" value=\"")
        (.append sb (inc color-idx))
        (.append sb "\"/>\n")
        (.append sb "  </object>\n"))
      (.append sb "</config>\n")
      (.toString sb))))

(def ^:private rels-xml
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n"
       "  <Relationship Target=\"/3D/3dmodel.model\" Id=\"rel0\" Type=\"http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel\"/>\n"
       "</Relationships>\n"))

(defn meshes->3mf-blob
  "Build a 3MF Blob from one or more meshes. Returns a Promise<Blob>."
  [meshes]
  (let [zip (JSZip.)
        {:keys [palette mesh->color]} (build-color-index meshes)
        model-xml (meshes->model-xml meshes palette mesh->color)
        config-xml (model-settings-config meshes mesh->color)]
    (.file zip "[Content_Types].xml" content-types-xml)
    (.file zip "_rels/.rels" rels-xml)
    (.file zip "3D/3dmodel.model" model-xml)
    (when config-xml
      (.file zip "Metadata/model_settings.config" config-xml))
    (.generateAsync zip #js {:type "blob"
                             :mimeType "model/3mf"
                             :compression "DEFLATE"})))
