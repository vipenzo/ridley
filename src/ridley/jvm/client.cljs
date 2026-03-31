(ns ridley.jvm.client
  "HTTP client for the JVM sidecar at port 12322.
   Sends DSL scripts for server-side evaluation, receives mesh results.")

(def ^:private server-url "http://127.0.0.1:12322")

;; Whether the JVM sidecar is available (detected via ping)
(defonce jvm-available? (atom false))

(defn ping!
  "Check if the JVM sidecar is running. Updates jvm-available? atom.
   Returns true if reachable."
  []
  (try
    (let [xhr (js/XMLHttpRequest.)]
      (.open xhr "GET" (str server-url "/ping") false)
      (.send xhr)
      (let [ok? (= 200 (.-status xhr))]
        (reset! jvm-available? ok?)
        ok?))
    (catch :default _
      (reset! jvm-available? false)
      false)))

(defn- js->mesh
  "Convert a JS mesh object from the JVM response to a Ridley mesh map.
   JSON keys use hyphens (from Clojure keywords), so we use aget for access."
  [^js m]
  (let [verts (aget m "vertices")
        faces (aget m "faces")
        cp    (aget m "creation-pose")]
    {:type :mesh
     :vertices (vec (map (fn [^js v] [(aget v 0) (aget v 1) (aget v 2)]) verts))
     :faces (vec (map (fn [^js f] [(int (aget f 0)) (int (aget f 1)) (int (aget f 2))]) faces))
     :creation-pose (if cp
                      {:position (vec (aget cp "position"))
                       :heading (vec (aget cp "heading"))
                       :up (vec (aget cp "up"))}
                      {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})}))

(defn eval-script
  "Send a DSL script to the JVM sidecar for evaluation.
   Returns {:meshes {name mesh} :print-output str :elapsed-ms number}
   or {:error str}."
  [script-text]
  (try
    (let [xhr (js/XMLHttpRequest.)]
      (.open xhr "POST" (str server-url "/eval") false)
      (.setRequestHeader xhr "Content-Type" "application/json")
      (.send xhr (js/JSON.stringify #js {:script script-text}))
      (if (= 200 (.-status xhr))
        (let [^js result (js/JSON.parse (.-responseText xhr))
              ^js meshes-js (aget result "meshes")
              keys (js/Object.keys meshes-js)
              mesh-map (reduce
                        (fn [acc k]
                          (let [m (aget meshes-js k)]
                            (if m
                              (assoc acc (keyword k) (js->mesh m))
                              (do (js/console.warn "JVM: null mesh for key" k)
                                  acc))))
                        {}
                        keys)]
          {:meshes mesh-map
           :print-output (or (aget result "print_output") "")
           :elapsed-ms (aget result "elapsed_ms")})
        ;; Error response
        (let [^js err (try (js/JSON.parse (.-responseText xhr))
                           (catch :default _ nil))]
          {:error (if err
                    (or (.-error err) (.-responseText xhr))
                    (str "JVM eval failed: HTTP " (.-status xhr)))})))
    (catch :default e
      {:error (str "JVM connection error: " (.-message e))})))
