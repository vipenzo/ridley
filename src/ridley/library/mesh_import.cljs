(ns ridley.library.mesh-import
  "Format-dispatching mesh import.

   `import-mesh` is the single name to remember: it reads a file from disk and
   picks the parser from the extension. `import-obj` is the explicit form for
   callers who want to name the format.

   The scanner channel needs this because KIRI Engine's free tier exports OBJ
   (plus an .mtl and a .jpg that are deliberately ignored — the channel carries
   geometry, not appearance), while the existing library path only spoke STL."
  (:require [clojure.string :as str]
            [ridley.library.stl :as stl]
            [ridley.library.obj :as obj]))

(defn- extension [path]
  (let [i (.lastIndexOf path ".")]
    (if (neg? i) "" (str/lower-case (subs path (inc i))))))

(defn ^:export import-obj
  "Read a Wavefront OBJ file from disk and return a Ridley mesh (desktop only).

   Only geometry is read: an accompanying .mtl or texture is ignored, and its
   absence is not an error.

   Options:
     :recenter  when true, translate the mesh so its bounding-box center sits
                at the origin (default false — keep the file's own coordinates).

   Returns {:type :mesh :vertices [[x y z]...] :faces [[i j k]...] :creation-pose ...}."
  [path & {:keys [recenter] :or {recenter false}}]
  (let [array-buffer (stl/read-file-bytes-sync path "import-obj")
        text (.decode (js/TextDecoder.) array-buffer)]
    (stl/parsed->mesh (obj/parse-obj text) recenter)))

(defn ^:export import-mesh
  "Read a mesh file from disk, choosing the parser by extension (desktop only).

   Supported: .stl (binary or ASCII) and .obj. Same options as the
   format-specific importers.

   Options:
     :recenter  when true, translate the mesh so its bounding-box center sits
                at the origin (default false — keep the file's own coordinates).

   Returns {:type :mesh :vertices [[x y z]...] :faces [[i j k]...] :creation-pose ...}."
  [path & {:keys [recenter] :or {recenter false}}]
  (let [ext (extension path)]
    (case ext
      "obj" (import-obj path :recenter recenter)
      "stl" (stl/import-stl path :recenter recenter)
      (throw (js/Error.
              (str "import-mesh: unsupported file type " (pr-str ext)
                   " for " path ". Supported: .stl, .obj"))))))
