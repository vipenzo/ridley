(ns ridley.io.threemf
  "3MF export for Ridley meshes.

   3MF is a ZIP archive containing XML model data.
   Uses indexed vertices (no float32 precision loss like STL).
   Preserves exact vertex positions and topology."
  (:require [clojure.string :as str])
  (:import [java.util.zip ZipOutputStream ZipEntry]
           [java.io FileOutputStream BufferedOutputStream]))

(defn- escape-xml [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- mesh->3mf-xml
  "Generate the 3D Model XML content for a mesh."
  [mesh]
  (let [vs (:vertices mesh)
        fs (:faces mesh)
        sb (StringBuilder.)]
    (.append sb "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    (.append sb "<model unit=\"millimeter\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\">\n")
    (.append sb "  <resources>\n")
    (.append sb "    <object id=\"1\" type=\"model\">\n")
    (.append sb "      <mesh>\n")
    ;; Vertices
    (.append sb "        <vertices>\n")
    (doseq [[x y z] vs]
      (.append sb (str "          <vertex x=\"" (double x)
                       "\" y=\"" (double y)
                       "\" z=\"" (double z) "\"/>\n")))
    (.append sb "        </vertices>\n")
    ;; Triangles
    (.append sb "        <triangles>\n")
    (doseq [[v1 v2 v3] fs]
      (.append sb (str "          <triangle v1=\"" v1
                       "\" v2=\"" v2
                       "\" v3=\"" v3 "\"/>\n")))
    (.append sb "        </triangles>\n")
    (.append sb "      </mesh>\n")
    (.append sb "    </object>\n")
    (.append sb "  </resources>\n")
    (.append sb "  <build>\n")
    (.append sb "    <item objectid=\"1\"/>\n")
    (.append sb "  </build>\n")
    (.append sb "</model>\n")
    (.toString sb)))

(defn- content-types-xml []
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">
  <Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>
  <Default Extension=\"model\" ContentType=\"application/vnd.ms-package.3dmanufacturing-3dmodel+xml\"/>
</Types>")

(defn- rels-xml []
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">
  <Relationship Target=\"/3D/3dmodel.model\" Id=\"rel0\" Type=\"http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel\"/>
</Relationships>")

(defn save-3mf
  "Save a mesh to a 3MF file (ZIP archive with XML model).
   (save-3mf mesh \"/path/to/file.3mf\")"
  [mesh path]
  (let [model-xml (mesh->3mf-xml mesh)]
    (with-open [fos (FileOutputStream. path)
                bos (BufferedOutputStream. fos)
                zos (ZipOutputStream. bos)]
      ;; [Content_Types].xml
      (.putNextEntry zos (ZipEntry. "[Content_Types].xml"))
      (.write zos (.getBytes (content-types-xml) "UTF-8"))
      (.closeEntry zos)
      ;; _rels/.rels
      (.putNextEntry zos (ZipEntry. "_rels/.rels"))
      (.write zos (.getBytes (rels-xml) "UTF-8"))
      (.closeEntry zos)
      ;; 3D/3dmodel.model
      (.putNextEntry zos (ZipEntry. "3D/3dmodel.model"))
      (.write zos (.getBytes model-xml "UTF-8"))
      (.closeEntry zos))
    (println (str "Saved " path " (" (count (:faces mesh)) " triangles, "
                  (count (:vertices mesh)) " vertices)"))
    path))
