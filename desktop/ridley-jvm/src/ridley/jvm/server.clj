(ns ridley.jvm.server
  "HTTP server for the JVM sidecar. Listens on :12322.
   Receives DSL scripts, evals them, returns mesh JSON."
  (:require [ring.adapter.jetty :as jetty]
            [clojure.data.json :as json]
            [ridley.jvm.eval :as eval-engine])
  (:import [java.nio ByteBuffer ByteOrder])
  (:gen-class))

(defn- cors-headers [response]
  (-> response
      (assoc-in [:headers "Access-Control-Allow-Origin"] "*")
      (assoc-in [:headers "Access-Control-Allow-Headers"] "Content-Type")
      (assoc-in [:headers "Content-Type"] "application/json")))

(def ^:private mesh-file-counter (atom 0))

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

(defn- handle-eval-bin
  "Eval endpoint: writes mesh data to a binary file, returns metadata JSON."
  [body]
  (let [{:keys [script]} (json/read-str body :key-fn keyword)
        t0 (System/nanoTime)
        result (eval-engine/eval-script script)
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
                                    (contains? v :color)   (assoc :color (:color v))))))
                   {} meshes)
        ;; Write binary file
        mesh-file (when (seq meshes) (write-mesh-binary meshes))]
    (println (format "eval-bin: %.1fms, %d mesh(es), file: %s"
                     elapsed-ms (count meshes) (or mesh-file "none")))
    (cors-headers
      {:status 200
       :body (json/write-str
               {:meshes mesh-meta
                :elapsed_ms elapsed-ms
                :print_output print-output
                :mesh_file mesh-file})})))

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

    ;; Ping
    (= "/ping" (:uri request))
    (cors-headers {:status 200 :body (json/write-str {:status "ok"})})

    :else
    {:status 404 :body "not found"}))

(defn -main [& _args]
  (println "ridley-jvm: starting on http://127.0.0.1:12322")
  (jetty/run-jetty handler {:port 12322 :join? true}))
