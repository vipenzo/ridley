(ns ridley.jvm.server
  "HTTP server for the JVM sidecar. Listens on :12322.
   Receives DSL scripts, evals them, returns mesh JSON."
  (:require [ring.adapter.jetty :as jetty]
            [clojure.data.json :as json]
            [ridley.jvm.eval :as eval-engine])
  (:gen-class))

(defn- cors-headers [response]
  (-> response
      (assoc-in [:headers "Access-Control-Allow-Origin"] "*")
      (assoc-in [:headers "Access-Control-Allow-Headers"] "Content-Type")
      (assoc-in [:headers "Content-Type"] "application/json")))

(defn- handle-eval [body]
  (let [{:keys [script]} (json/read-str body :key-fn keyword)
        t0 (System/nanoTime)
        result (eval-engine/eval-script script)
        t1 (System/nanoTime)
        elapsed-ms (/ (- t1 t0) 1e6)]
    (println (format "eval: %.1fms, %d mesh(es) registered" elapsed-ms (count result)))
    (cors-headers
      {:status 200
       :body (json/write-str
               {:meshes result
                :elapsed_ms elapsed-ms})})))

(defn handler [request]
  (cond
    ;; CORS preflight
    (= :options (:request-method request))
    (cors-headers {:status 200 :body ""})

    ;; Eval script
    (and (= :post (:request-method request))
         (= "/eval" (:uri request)))
    (try
      (handle-eval (slurp (:body request)))
      (catch Exception e
        (cors-headers
          {:status 500
           :body (json/write-str {:error (.getMessage e)})})))

    ;; Ping
    (= "/ping" (:uri request))
    (cors-headers {:status 200 :body (json/write-str {:status "ok"})})

    :else
    {:status 404 :body "not found"}))

(defn -main [& _args]
  (println "ridley-jvm: starting on http://127.0.0.1:12322")
  (jetty/run-jetty handler {:port 12322 :join? true}))
