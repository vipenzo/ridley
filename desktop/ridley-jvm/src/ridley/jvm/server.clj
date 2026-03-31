(ns ridley.jvm.server
  "HTTP server for the JVM sidecar. Listens on :12322.
   Receives DSL scripts, evals them, returns mesh JSON."
  (:require [ring.adapter.jetty :as jetty]
            [clojure.data.json :as json]
            [ridley.jvm.eval :as eval-engine])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.util Base64])
  (:gen-class))

(defn- cors-headers [response]
  (-> response
      (assoc-in [:headers "Access-Control-Allow-Origin"] "*")
      (assoc-in [:headers "Access-Control-Allow-Headers"] "Content-Type")
      (assoc-in [:headers "Content-Type"] "application/json")))

(defn- mesh->binary
  "Encode mesh vertices as base64 float64 LE, faces as base64 int32 LE.
   Returns map with :vertex_count, :face_count, :vertices_b64, :faces_b64, :creation-pose."
  [mesh]
  (when mesh
    (let [verts (:vertices mesh)
          faces (:faces mesh)
          encoder (Base64/getEncoder)
          ;; Vertices: flat float64 little-endian
          vbuf (doto (ByteBuffer/allocate (* (count verts) 3 8))
                 (.order ByteOrder/LITTLE_ENDIAN))
          _ (doseq [v verts]
              (.putDouble vbuf (double (v 0)))
              (.putDouble vbuf (double (v 1)))
              (.putDouble vbuf (double (v 2))))
          vb64 (.encodeToString encoder (.array vbuf))
          ;; Faces: flat int32 little-endian
          fbuf (doto (ByteBuffer/allocate (* (count faces) 3 4))
                 (.order ByteOrder/LITTLE_ENDIAN))
          _ (doseq [f faces]
              (.putInt fbuf (int (f 0)))
              (.putInt fbuf (int (f 1)))
              (.putInt fbuf (int (f 2))))
          fb64 (.encodeToString encoder (.array fbuf))]
      {:vertex_count (count verts)
       :face_count (count faces)
       :vertices_b64 vb64
       :faces_b64 fb64
       :creation-pose (:creation-pose mesh)})))

(defn- handle-eval-bin
  "Eval endpoint with binary mesh encoding (base64)."
  [body]
  (let [{:keys [script]} (json/read-str body :key-fn keyword)
        t0 (System/nanoTime)
        result (eval-engine/eval-script script)
        t1 (System/nanoTime)
        elapsed-ms (/ (- t1 t0) 1e6)
        meshes (:meshes result)
        print-output (:print-output result)
        bin-meshes (reduce-kv (fn [m k v] (assoc m k (mesh->binary v)))
                              {} meshes)]
    (println (format "eval-bin: %.1fms, %d mesh(es)" elapsed-ms (count meshes)))
    (cors-headers
      {:status 200
       :body (json/write-str
               {:meshes bin-meshes
                :elapsed_ms elapsed-ms
                :print_output print-output
                :binary true})})))

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

    ;; Ping
    (= "/ping" (:uri request))
    (cors-headers {:status 200 :body (json/write-str {:status "ok"})})

    :else
    {:status 404 :body "not found"}))

(defn -main [& _args]
  (println "ridley-jvm: starting on http://127.0.0.1:12322")
  (jetty/run-jetty handler {:port 12322 :join? true}))
