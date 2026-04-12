(ns ridley.jvm.library
  "JVM library system for Ridley DSL.

   Libraries live on disk as .clj files in ~/.ridley/libraries/.
   Each file is evaluated and its top-level defs become available as
   a namespace, accessible via lib-name/symbol-name in user scripts.

   STL/3MF imports are stored as libraries with the mesh data serialized
   as Clojure vectors (no base64 needed — JVM has full filesystem access).

   The library directory is also scanned for raw .stl files: each one is
   auto-loaded as a library with a single def (the mesh) named after the
   file.")

(def ^:private lib-dir
  (let [dir (java.io.File. (str (System/getProperty "user.home") "/.ridley/libraries"))]
    (.mkdirs dir)
    dir))

(defn sanitize-name
  "Convert a filename to a valid Clojure symbol/namespace name."
  [s]
  (-> s
      (clojure.string/replace #"\.[^.]+$" "")
      (clojure.string/replace #"[^a-zA-Z0-9\-_]" "-")
      (clojure.string/replace #"-+" "-")
      (clojure.string/replace #"^-|-$" "")
      (clojure.string/lower-case)))

(defn list-libraries
  "List all available library names (from .clj and .stl files)."
  []
  (when (.exists lib-dir)
    (vec (keep (fn [^java.io.File f]
                 (when (and (.isFile f)
                            (or (.endsWith (.getName f) ".clj")
                                (.endsWith (.getName f) ".stl")
                                (.endsWith (.getName f) ".3mf")))
                   (sanitize-name (.getName f))))
               (.listFiles lib-dir)))))

(defn- load-stl-as-mesh
  "Load a .stl file and return a Ridley mesh map."
  [^java.io.File file]
  (require 'ridley.io.stl)
  ((resolve 'ridley.io.stl/load-stl) (.getAbsolutePath file)))

(defn load-libraries!
  "Load all libraries from ~/.ridley/libraries/ and create namespaces.
   Returns a map of {ns-symbol {def-symbol value}} for reporting."
  [dsl-bindings macro-sources]
  (when (.exists lib-dir)
    (let [files (sort-by #(.getName ^java.io.File %) (.listFiles lib-dir))
          results (atom {})]
      (doseq [^java.io.File f files
              :when (.isFile f)]
        (let [fname (.getName f)
              lib-name (sanitize-name fname)
              ns-sym (symbol lib-name)]
          (try
            (cond
              ;; .stl files: auto-load as mesh, create namespace with one def
              (.endsWith fname ".stl")
              (let [mesh (load-stl-as-mesh f)
                    lib-ns (create-ns ns-sym)]
                (intern lib-ns (symbol lib-name) mesh)
                (swap! results assoc lib-name {:defs [lib-name] :type :stl})
                (println (str "library: loaded " lib-name " (STL, "
                              (count (:faces mesh)) " faces)")))

              ;; .clj files: eval as Clojure source with DSL bindings
              (.endsWith fname ".clj")
              (let [source (slurp f)
                    lib-ns (create-ns ns-sym)]
                ;; Inject DSL bindings into library namespace
                (binding [*ns* lib-ns]
                  (refer 'clojure.core))
                (doseq [[sym val] dsl-bindings]
                  (intern lib-ns sym val))
                ;; Inject macros
                (binding [*ns* lib-ns]
                  (doseq [macro-src macro-sources]
                    (load-string macro-src)))
                ;; Eval library source
                (binding [*ns* lib-ns]
                  (load-string source))
                ;; Collect public defs
                (let [pub-vars (->> (ns-publics ns-sym)
                                    (map key)
                                    (remove #(contains? (set (keys dsl-bindings)) %))
                                    vec)]
                  (swap! results assoc lib-name {:defs pub-vars :type :clj})
                  (println (str "library: loaded " lib-name " (CLJ, "
                                (count pub-vars) " defs)")))))
            (catch Exception e
              (println (str "library: ERROR loading " lib-name ": " (.getMessage e)))
              (swap! results assoc lib-name {:error (.getMessage e)})))))
      @results)))

(defn import-stl!
  "Import an STL file into the library system. Opens a file picker dialog,
   copies the file to ~/.ridley/libraries/, loads it as a namespace
   immediately (no restart needed). Returns the access path string
   e.g. \"my-part/my-part\" or nil if cancelled."
  []
  (let [chosen-path (atom nil)
        _ (javax.swing.SwingUtilities/invokeAndWait
           (fn []
             (let [frame (doto (javax.swing.JFrame.)
                           (.setUndecorated true)
                           (.setVisible true)
                           (.setAlwaysOnTop true)
                           (.toFront))
                   chooser (doto (javax.swing.JFileChooser.
                                  (java.io.File. (System/getProperty "user.home")))
                             (.setDialogTitle "Import STL to Library")
                             (.setFileFilter
                              (javax.swing.filechooser.FileNameExtensionFilter.
                               "3D mesh files" (into-array String ["stl" "3mf"]))))
                   r (.showOpenDialog chooser frame)]
               (.dispose frame)
               (when (= r javax.swing.JFileChooser/APPROVE_OPTION)
                 (reset! chosen-path (.getAbsolutePath (.getSelectedFile chooser)))))))
        src-path @chosen-path]
    (when src-path
      (let [src-file (java.io.File. src-path)
            fname (.getName src-file)
            dest-file (java.io.File. lib-dir fname)
            lib-name (sanitize-name fname)]
        ;; Copy file to library dir
        (java.nio.file.Files/copy
         (.toPath src-file)
         (.toPath dest-file)
         (into-array java.nio.file.CopyOption
                     [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
        ;; Load immediately into a namespace
        (let [mesh (load-stl-as-mesh dest-file)
              ns-sym (symbol lib-name)
              lib-ns (create-ns ns-sym)]
          (intern lib-ns (symbol lib-name) mesh)
          (println (str "library: imported " lib-name " ("
                        (count (:faces mesh)) " faces) -> "
                        lib-name "/" lib-name)))
        (str lib-name "/" lib-name)))))

(defn import-file!
  "Import an STL/3MF file by path (no dialog). Used for programmatic imports.
   Returns the access path string e.g. \"my-part/my-part\"."
  [path]
  (let [src-file (java.io.File. path)
        fname (.getName src-file)
        dest-file (java.io.File. lib-dir fname)
        lib-name (sanitize-name fname)]
    ;; Copy to library dir
    (java.nio.file.Files/copy
     (.toPath src-file)
     (.toPath dest-file)
     (into-array java.nio.file.CopyOption
                 [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
    ;; Load namespace
    (let [mesh (load-stl-as-mesh dest-file)
          ns-sym (symbol lib-name)
          lib-ns (create-ns ns-sym)]
      (intern lib-ns (symbol lib-name) mesh)
      (println (str "library: imported " lib-name " ("
                    (count (:faces mesh)) " faces) -> "
                    lib-name "/" lib-name)))
    (str lib-name "/" lib-name)))

(defn delete-library!
  "Delete a library: remove file from ~/.ridley/libraries/ and remove namespace."
  [lib-name]
  (let [files (.listFiles lib-dir)
        matching (filter (fn [^java.io.File f]
                           (= lib-name (sanitize-name (.getName f))))
                         files)]
    (doseq [^java.io.File f matching]
      (.delete f)
      (println (str "library: deleted " (.getName f))))
    ;; Remove namespace if it exists
    (let [ns-sym (symbol lib-name)]
      (when (find-ns ns-sym)
        (remove-ns ns-sym)))
    (println (str "library: removed namespace " lib-name))))

(defn inject-library-namespaces!
  "Make library namespaces accessible from the eval namespace.
   Call after creating the eval namespace: aliases each library ns
   so that lib-name/symbol-name resolves correctly."
  [eval-ns-sym]
  (when (.exists lib-dir)
    (let [eval-ns (the-ns eval-ns-sym)]
      (doseq [^java.io.File f (.listFiles lib-dir)
              :when (and (.isFile f)
                         (or (.endsWith (.getName f) ".clj")
                             (.endsWith (.getName f) ".stl")))]
        (let [lib-name (sanitize-name (.getName f))
              lib-sym (symbol lib-name)]
          (when (find-ns lib-sym)
            ;; Create an alias in the eval namespace pointing to the library ns
            (.addAlias ^clojure.lang.Namespace eval-ns lib-sym (the-ns lib-sym))))))))
