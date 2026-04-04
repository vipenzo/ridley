(ns ridley.jvm.client
  "HTTP client for the JVM sidecar at port 12322.
   Sends DSL scripts for server-side evaluation, receives mesh results.
   Uses binary file transfer for mesh data — JVM writes binary to disk,
   frontend fetches it as ArrayBuffer.")

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

(defn- parse-meshes-from-buffer
  "Parse mesh data from an ArrayBuffer using metadata (vertex/face counts).
   meshes-meta is a seq of [name {:vertex_count n :face_count m :creation-pose cp}].
   Data layout: for each mesh, flat float64 LE vertices then flat int32 LE faces."
  [^js buffer meshes-meta]
  (let [dv (js/DataView. buffer)]
    (loop [pairs meshes-meta
           offset 0
           result {}]
      (if-let [[name meta] (first pairs)]
        (if (nil? meta)
          (recur (rest pairs) offset result)
          (let [nv (aget meta "vertex_count")
                nf (aget meta "face_count")
                cp (aget meta "creation-pose")
                ;; Read vertices: nv * 3 float64 LE
                verts (loop [i 0 off offset out (transient [])]
                        (if (< i nv)
                          (let [x (.getFloat64 dv off true)
                                y (.getFloat64 dv (+ off 8) true)
                                z (.getFloat64 dv (+ off 16) true)]
                            (recur (inc i) (+ off 24) (conj! out [x y z])))
                          [(persistent! out) off]))
                vert-data (first verts)
                offset-after-verts (second verts)
                ;; Read faces: nf * 3 int32 LE
                faces (loop [i 0 off offset-after-verts out (transient [])]
                        (if (< i nf)
                          (let [a (.getInt32 dv off true)
                                b (.getInt32 dv (+ off 4) true)
                                c (.getInt32 dv (+ off 8) true)]
                            (recur (inc i) (+ off 12) (conj! out [a b c])))
                          [(persistent! out) off]))
                face-data (first faces)
                offset-after-faces (second faces)
                visible (aget meta "visible")
                color (aget meta "color")
                mat-js (aget meta "material")
                mat (when mat-js
                      (let [m {}]
                        (cond-> m
                          (some? (aget mat-js "color"))        (assoc :color (aget mat-js "color"))
                          (some? (aget mat-js "metalness"))    (assoc :metalness (aget mat-js "metalness"))
                          (some? (aget mat-js "roughness"))    (assoc :roughness (aget mat-js "roughness"))
                          (some? (aget mat-js "opacity"))      (assoc :opacity (aget mat-js "opacity"))
                          (some? (aget mat-js "double-sided")) (assoc :double-sided (aget mat-js "double-sided")))))
                mesh (cond-> {:type :mesh
                              :vertices vert-data
                              :faces face-data
                              :creation-pose (if cp
                                               {:position (vec (aget cp "position"))
                                                :heading (vec (aget cp "heading"))
                                                :up (vec (aget cp "up"))}
                                               {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})}
                       (some? visible) (assoc :visible visible)
                       (some? color)   (assoc :color color)
                       (some? mat)     (assoc :material mat))]
            (recur (rest pairs) offset-after-faces (assoc result (keyword name) mesh))))
        result))))

(defn eval-script
  "Send a DSL script to the JVM sidecar for evaluation (async).
   Calls on-result with {:meshes ... :print-output ... :elapsed-ms ...}
   or {:error ...}."
  [script-text on-result]
  (let [xhr (js/XMLHttpRequest.)]
    (.open xhr "POST" (str server-url "/eval-bin") true)
    (.setRequestHeader xhr "Content-Type" "application/json")
    (set! (.-timeout xhr) 300000)
    (set! (.-onload xhr)
      (fn []
        (if (= 200 (.-status xhr))
          (try
            (let [^js result (js/JSON.parse (.-responseText xhr))
                  ^js meshes-meta-js (aget result "meshes")
                  mesh-file (aget result "mesh_file")
                  print-output (or (aget result "print_output") "")
                  elapsed-ms (aget result "elapsed_ms")]
              (if mesh-file
                ;; Fetch binary file from JVM server
                (let [;; Extract file ID from path like /tmp/ridley-meshes-42.bin
                      file-id (second (re-find #"ridley-meshes-(\d+)\.bin" mesh-file))]
                  (-> (js/fetch (str server-url "/mesh-file/" file-id))
                      (.then (fn [resp]
                               (if (.-ok resp)
                                 (.arrayBuffer resp)
                                 (throw (js/Error. (str "Mesh file fetch failed: " (.-status resp)))))))
                      (.then (fn [buf]
                               (let [keys (js/Object.keys meshes-meta-js)
                                     pairs (map (fn [k] [k (aget meshes-meta-js k)]) keys)
                                     meshes (parse-meshes-from-buffer buf pairs)]
                                 (on-result {:meshes meshes
                                             :print-output print-output
                                             :elapsed-ms elapsed-ms}))))
                      (.catch (fn [e]
                                (on-result {:error (str "Mesh file error: " (.-message e))})))))
                ;; No mesh file (empty result)
                (on-result {:meshes {}
                            :print-output print-output
                            :elapsed-ms elapsed-ms})))
            (catch :default e
              (on-result {:error (str "Parse error: " (.-message e))})))
          ;; Error response
          (let [^js err (try (js/JSON.parse (.-responseText xhr))
                             (catch :default _ nil))]
            (on-result {:error (if err
                                 (or (.-error err) (.-responseText xhr))
                                 (str "JVM eval failed: HTTP " (.-status xhr)))})))))
    (set! (.-onerror xhr) (fn [] (on-result {:error "JVM connection error"})))
    (set! (.-ontimeout xhr) (fn [] (on-result {:error "JVM eval timed out"})))
    (.send xhr (js/JSON.stringify #js {:script script-text}))))
