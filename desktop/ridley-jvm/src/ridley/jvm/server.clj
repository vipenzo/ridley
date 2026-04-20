(ns ridley.jvm.server
  "HTTP server for the JVM sidecar. Listens on :12322.
   Receives DSL scripts, evals them, returns mesh JSON."
  (:require [ring.adapter.jetty :as jetty]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [ridley.jvm.eval :as eval-engine]
            [ridley.jvm.library :as library]
            [ridley.jvm.tweak :as tweak])
  (:import [java.nio ByteBuffer ByteOrder])
  (:gen-class))

(defn- cors-headers [response]
  (-> response
      (assoc-in [:headers "Access-Control-Allow-Origin"] "*")
      (assoc-in [:headers "Access-Control-Allow-Headers"] "Content-Type")
      (assoc-in [:headers "Content-Type"] "application/json")))

(def ^:private mesh-file-counter (atom 0))

;; Current eval thread — used by /cancel to interrupt long-running evals
(def ^:private current-eval-thread (atom nil))

(defn- write-mesh-binary
  "Write all meshes to a single binary file. Returns the file path.
   Format: for each mesh, vertices (flat float64 LE) then faces (flat int32 LE),
   packed contiguously in the order of mesh-meta."
  [meshes]
  (let [id (swap! mesh-file-counter inc)
        path (str (System/getProperty "java.io.tmpdir") "/ridley-meshes-" id ".bin")
        total-bytes (reduce-kv
                     (fn [acc _ mesh]
                       (if mesh
                         (+ acc
                            (* (count (:vertices mesh)) 3 8)
                            (* (count (:faces mesh)) 3 4))
                         acc))
                     0 meshes)
        buf (doto (ByteBuffer/allocate total-bytes)
              (.order ByteOrder/LITTLE_ENDIAN))]
    (doseq [[_ mesh] meshes]
      (when mesh
        (doseq [v (:vertices mesh)]
          (.putDouble buf (double (v 0)))
          (.putDouble buf (double (v 1)))
          (.putDouble buf (double (v 2))))
        (doseq [f (:faces mesh)]
          (.putInt buf (int (f 0)))
          (.putInt buf (int (f 1)))
          (.putInt buf (int (f 2))))))
    (with-open [out (java.io.FileOutputStream. path)]
      (.write out (.array buf)))
    path))

(defn- handle-import-stl
  "Import STL: receives {filename, data_base64}, saves to ~/.ridley/libraries/,
   loads namespace immediately. Returns {name, access_path}."
  [body]
  (let [{:keys [filename data_base64]} (json/read-str body :key-fn keyword)
        decoded (java.util.Base64/getDecoder)
        bytes (.decode decoded ^String data_base64)
        lib-name (library/sanitize-name filename)
        dest (java.io.File. (str (System/getProperty "user.home") "/.ridley/libraries/" filename))]
    (.mkdirs (.getParentFile dest))
    (with-open [out (java.io.FileOutputStream. dest)]
      (.write out ^bytes bytes))
    ;; Load namespace immediately
    (let [mesh ((requiring-resolve 'ridley.io.stl/load-stl) (.getAbsolutePath dest))
          ns-sym (symbol lib-name)
          lib-ns (create-ns ns-sym)]
      (intern lib-ns (symbol lib-name) mesh)
      (println (str "import-stl: " lib-name " (" (count (:faces mesh)) " faces)"))
      (cors-headers
       {:status 200
        :body (json/write-str {:name lib-name
                               :access_path (str lib-name "/" lib-name)
                               :faces (count (:faces mesh))
                               :vertices (count (:vertices mesh))})}))))

(defn- handle-eval-bin
  "Eval endpoint: writes mesh data to a binary file, returns metadata JSON.
   Accepts optional active_libraries: [\"name1\", \"name2\"] to alias
   library namespaces into the eval context."
  [body]
  (eval-engine/reset-repl!) ;; Reset REPL namespace when definitions are re-evaluated
  (let [{:keys [script active_libraries]} (json/read-str body :key-fn keyword)
        t0 (System/nanoTime)
        result (eval-engine/eval-script script active_libraries)
        t1 (System/nanoTime)
        elapsed-ms (/ (- t1 t0) 1e6)
        meshes (:meshes result)
        print-output (:print-output result)
        ;; Build metadata (vertex/face counts + creation poses, no geometry data)
        mesh-meta (reduce-kv
                   (fn [m k v]
                     (assoc m k (when v
                                  (cond-> {:vertex_count (count (:vertices v))
                                           :face_count (count (:faces v))
                                           :creation-pose (:creation-pose v)}
                                    (contains? v :visible) (assoc :visible (:visible v))
                                    (contains? v :color)   (assoc :color (:color v))
                                    (contains? v :material) (assoc :material (:material v))))))
                   {} meshes)
        ;; Write binary file
        mesh-file (when (seq meshes) (write-mesh-binary meshes))]
    (println (format "eval-bin: %.1fms, %d mesh(es), file: %s"
                     elapsed-ms (count meshes) (or mesh-file "none")))
    (cors-headers
     {:status 200
      :body (json/write-str
             (cond-> {:meshes mesh-meta
                      :elapsed_ms elapsed-ms
                      :print_output print-output
                      :mesh_file mesh-file
                      :stamps (:stamps result)}
               (:tweak-session result)
               (assoc :tweak_session (:tweak-session result))
               (:pilot-session result)
               (assoc :pilot_session (:pilot-session result))))})))

(defn- serialize-lines
  "Convert pen line data to JSON-safe format."
  [lines]
  (mapv (fn [l]
          {"type"  (:type l)
           "from"  (:from l)
           "to"    (:to l)
           "color" (:color l)})
        lines))

(defn- handle-eval-repl
  "REPL eval endpoint: persistent namespace, preserves turtle state.
   Returns binary meshes + lines/stamps/result as JSON."
  [body]
  (let [{:keys [command active_libraries]} (json/read-str body :key-fn keyword)
        t0 (System/nanoTime)
        result (eval-engine/eval-repl command active_libraries)
        t1 (System/nanoTime)
        elapsed-ms (/ (- t1 t0) 1e6)
        meshes (:meshes result)
        lines  (:lines result)
        stamps (:stamps result)
        print-output (:print-output result)
        eval-result  (:result result)
        ;; Build mesh metadata
        mesh-meta (reduce-kv
                   (fn [m k v]
                     (assoc m k (when v
                                  (cond-> {:vertex_count (count (:vertices v))
                                           :face_count (count (:faces v))
                                           :creation-pose (:creation-pose v)}
                                    (contains? v :visible) (assoc :visible (:visible v))
                                    (contains? v :color)   (assoc :color (:color v))
                                    (contains? v :material) (assoc :material (:material v))))))
                   {} meshes)
        mesh-file (when (seq meshes) (write-mesh-binary meshes))]
    (println (format "eval-repl: %.1fms, %d mesh(es), %d line(s)"
                     elapsed-ms (count meshes) (count lines)))
    (cors-headers
     {:status 200
      :body (json/write-str
             (cond-> {:meshes mesh-meta
                      :mesh_file mesh-file
                      :elapsed_ms elapsed-ms
                      :print_output print-output
                      :result eval-result
                      :lines (serialize-lines lines)
                      :stamps (:stamps result)
                      :turtle_pose (:turtle-pose result)
                      :visibility (:visibility result)}
               (:tweak-session result)
               (assoc :tweak_session (:tweak-session result))))})))

(defn- handle-eval [body]
  (let [{:keys [script]} (json/read-str body :key-fn keyword)
        t0 (System/nanoTime)
        result (eval-engine/eval-script script)
        t1 (System/nanoTime)
        elapsed-ms (/ (- t1 t0) 1e6)
        meshes (:meshes result)
        print-output (:print-output result)]
    (println (format "eval: %.1fms, %d mesh(es) registered" elapsed-ms (count meshes)))
    (when (seq print-output)
      (println "  output:" (.substring print-output 0 (min 200 (count print-output)))))
    (cors-headers
     {:status 200
      :body (json/write-str
             {:meshes meshes
              :elapsed_ms elapsed-ms
              :print_output print-output})})))

(defn handler [request]
  (cond
    ;; CORS preflight
    (= :options (:request-method request))
    (cors-headers {:status 200 :body ""})

    ;; Eval script (binary mesh encoding)
    (and (= :post (:request-method request))
         (= "/eval-bin" (:uri request)))
    (try
      (reset! current-eval-thread (Thread/currentThread))
      (handle-eval-bin (slurp (:body request)))
      (catch Exception e
        (let [root (loop [ex e]
                     (if-let [cause (.getCause ex)]
                       (recur cause)
                       ex))
              msg (str (.getMessage e)
                       (when (not= root e)
                         (str "\nCaused by: " (.getMessage root))))]
          (cors-headers
           {:status 500
            :body (json/write-str {:error msg})}))))

    ;; REPL eval (persistent namespace, preserves turtle state)
    (and (= :post (:request-method request))
         (= "/eval-repl" (:uri request)))
    (try
      (reset! current-eval-thread (Thread/currentThread))
      (handle-eval-repl (slurp (:body request)))
      (catch Exception e
        (let [root (loop [ex e]
                     (if-let [cause (.getCause ex)]
                       (recur cause)
                       ex))
              msg (str (.getMessage e)
                       (when (not= root e)
                         (str "\nCaused by: " (.getMessage root))))]
          (cors-headers
           {:status 500
            :body (json/write-str {:error msg})}))))

    ;; Eval script (JSON mesh encoding, legacy)
    (and (= :post (:request-method request))
         (= "/eval" (:uri request)))
    (try
      (handle-eval (slurp (:body request)))
      (catch Exception e
        (let [root (loop [ex e]
                     (if-let [cause (.getCause ex)]
                       (recur cause)
                       ex))
              msg (str (.getMessage e)
                       (when (not= root e)
                         (str "\nCaused by: " (.getMessage root))))]
          (cors-headers
           {:status 500
            :body (json/write-str {:error msg})}))))

    ;; Serve mesh binary file
    (and (= :get (:request-method request))
         (.startsWith (:uri request) "/mesh-file/"))
    (let [id (subs (:uri request) (count "/mesh-file/"))
          path (str (System/getProperty "java.io.tmpdir") "/ridley-meshes-" id ".bin")]
      (if (.exists (java.io.File. path))
        (-> {:status 200
             :body (java.io.FileInputStream. path)}
            (assoc-in [:headers "Access-Control-Allow-Origin"] "*")
            (assoc-in [:headers "Content-Type"] "application/octet-stream"))
        (cors-headers {:status 404 :body "mesh file not found"})))

    ;; Import STL to library
    (and (= :post (:request-method request))
         (= "/import-stl" (:uri request)))
    (try
      (handle-import-stl (slurp (:body request)))
      (catch Exception e
        (cors-headers {:status 500
                       :body (json/write-str {:error (.getMessage e)})})))

    ;; List libraries
    (and (= :get (:request-method request))
         (= "/libraries" (:uri request)))
    (cors-headers {:status 200
                   :body (json/write-str {:libraries (library/list-libraries)})})

    ;; Delete library
    (and (= :post (:request-method request))
         (= "/delete-library" (:uri request)))
    (try
      (let [{:keys [name]} (json/read-str (slurp (:body request)) :key-fn keyword)]
        (library/delete-library! name)
        (cors-headers {:status 200
                       :body (json/write-str {:ok true})}))
      (catch Exception e
        (cors-headers {:status 500
                       :body (json/write-str {:error (.getMessage e)})})))

    ;; Tweak update: re-eval script with substituted values.
    ;; Returns full mesh JSON (not binary) for WebKit sync XHR compatibility.
    (and (= :post (:request-method request))
         (= "/tweak-update" (:uri request)))
    (try
      (let [{:keys [script form values active_libraries]}
            (json/read-str (slurp (:body request)) :key-fn keyword)
            parsed-form (read-string form)
            values-map (into {} (map (fn [[k v]] [(Integer/parseInt (name k)) v]) values))
            modified-form (tweak/substitute-values parsed-form values-map)
            modified-str (pr-str modified-form)
            ;; Replace (tweak <original-form>) with <modified-form> in the script
            ;; This preserves the (register name ...) wrapper around it
            tweak-pattern (str "(tweak " form ")")
            full-script (clojure.string/replace script tweak-pattern modified-str)
            result (eval-engine/eval-script full-script active_libraries)
            meshes (:meshes result)]
        (cors-headers
         {:status 200
          :body (json/write-str {:meshes meshes})}))
      (catch Exception e
        (cors-headers {:status 500
                       :body (json/write-str {:error (.getMessage e)})})))

    ;; Tweak REPL update: re-eval REPL command with substituted values.
    ;; Uses eval-repl (persistent namespace) instead of eval-script.
    (and (= :post (:request-method request))
         (= "/tweak-repl-update" (:uri request)))
    (try
      (let [{:keys [command form values active_libraries]}
            (json/read-str (slurp (:body request)) :key-fn keyword)
            parsed-form (read-string form)
            values-map (into {} (map (fn [[k v]] [(Integer/parseInt (name k)) v]) values))
            modified-form (tweak/substitute-values parsed-form values-map)
            modified-str (pr-str modified-form)
            tweak-pattern (str "(tweak " form ")")
            full-command (clojure.string/replace command tweak-pattern modified-str)
            result (eval-engine/eval-repl full-command active_libraries)
            meshes (:meshes result)]
        (cors-headers
         {:status 200
          :body (json/write-str {:meshes meshes})}))
      (catch Exception e
        (cors-headers {:status 500
                       :body (json/write-str {:error (.getMessage e)})})))

    ;; Cancel current eval
    (and (= :post (:request-method request))
         (= "/cancel" (:uri request)))
    (let [t @current-eval-thread]
      (if (and t (.isAlive t))
        (do (.interrupt t)
            (println "cancel: interrupted eval thread")
            (cors-headers {:status 200 :body (json/write-str {:cancelled true})}))
        (cors-headers {:status 200 :body (json/write-str {:cancelled false :reason "no active eval"})})))

    ;; Ping
    (= "/ping" (:uri request))
    (cors-headers {:status 200 :body (json/write-str {:status "ok"})})

    :else
    {:status 404 :body "not found"}))

(defn -main [& _args]
  (println "ridley-jvm: starting on http://127.0.0.1:12322")
  ;; Load libraries from ~/.ridley/libraries/ at startup
  (library/load-libraries! eval-engine/dsl-bindings eval-engine/all-macro-sources)
  (jetty/run-jetty handler {:port 12322 :join? true}))
