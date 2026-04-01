(ns ridley.jvm.client
  "HTTP client for the JVM sidecar at port 12322.
   Sends DSL scripts for server-side evaluation, receives mesh results.
   Uses binary transfer (base64 float64/int32) for mesh data.")

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

(defn- json->mesh
  "Decode a JSON-encoded mesh (legacy /eval endpoint)."
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

(defn- b64->arraybuffer
  "Decode base64 string → ArrayBuffer using charCodeAt (safe for 0-255 bytes)."
  [^js b64-str]
  (let [binary (js/atob b64-str)
        len (.-length binary)
        buf (js/ArrayBuffer. len)
        u8 (js/Uint8Array. buf)]
    (dotimes [i len]
      (aset u8 i (bit-and (.charCodeAt binary i) 0xff)))
    buf))

(defn- b64->float64-vec
  "Decode base64 string → array of [x y z] float64 triplets."
  [^js b64-str n]
  (let [buf (b64->arraybuffer b64-str)
        f64 (js/Float64Array. buf)]
    (loop [i 0 out (transient [])]
      (if (< i n)
        (let [off (* i 3)]
          (recur (inc i)
                 (conj! out [(aget f64 off) (aget f64 (+ off 1)) (aget f64 (+ off 2))])))
        (persistent! out)))))

(defn- b64->int32-vec
  "Decode base64 string → array of [a b c] int32 triplets."
  [^js b64-str n]
  (let [buf (b64->arraybuffer b64-str)
        i32 (js/Int32Array. buf)]
    (loop [i 0 out (transient [])]
      (if (< i n)
        (let [off (* i 3)]
          (recur (inc i)
                 (conj! out [(aget i32 off) (aget i32 (+ off 1)) (aget i32 (+ off 2))])))
        (persistent! out)))))

(defn- bin->mesh
  "Decode a binary-encoded mesh from JVM response."
  [^js m]
  (let [nv (aget m "vertex_count")
        nf (aget m "face_count")
        cp (aget m "creation-pose")]
    {:type :mesh
     :vertices (b64->float64-vec (aget m "vertices_b64") nv)
     :faces (b64->int32-vec (aget m "faces_b64") nf)
     :creation-pose (if cp
                      {:position (vec (aget cp "position"))
                       :heading (vec (aget cp "heading"))
                       :up (vec (aget cp "up"))}
                      {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})}))

(defn eval-script
  "Send a DSL script to the JVM sidecar for evaluation.
   Uses /eval-bin for binary mesh transfer.
   Returns {:meshes {name mesh} :print-output str :elapsed-ms number}
   or {:error str}."
  [script-text]
  (try
    (let [xhr (js/XMLHttpRequest.)]
      (.open xhr "POST" (str server-url "/eval-bin") false)
      (.setRequestHeader xhr "Content-Type" "application/json")
      (.send xhr (js/JSON.stringify #js {:script script-text}))
      (if (= 200 (.-status xhr))
        (let [^js result (js/JSON.parse (.-responseText xhr))
              ^js meshes-js (aget result "meshes")
              keys (js/Object.keys meshes-js)
              binary? (aget result "binary")
              mesh-map (reduce
                        (fn [acc k]
                          (let [m (aget meshes-js k)]
                            (if m
                              (assoc acc (keyword k) (if binary? (bin->mesh m) (json->mesh m)))
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
