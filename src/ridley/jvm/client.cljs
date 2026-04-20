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

(defn cancel!
  "Cancel the current eval on the JVM sidecar."
  []
  (try
    (let [xhr (js/XMLHttpRequest.)]
      (.open xhr "POST" (str server-url "/cancel") true) ;; async
      (.send xhr))
    (catch :default _)))

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

(defn- parse-lines
  "Parse pen line data from JSON response."
  [lines-js]
  (when (and lines-js (pos? (.-length lines-js)))
    (vec (map (fn [l]
                {:type  (keyword (aget l "type"))
                 :from  (vec (aget l "from"))
                 :to    (vec (aget l "to"))
                 :color (aget l "color")})
              lines-js))))

(defn- parse-stamps
  "Parse stamp data from JSON response.
   Each stamp is {:vertices [[x y z]...] :faces [[i j k]...] :color hex-int?}."
  [stamps-js]
  (when (and stamps-js (pos? (.-length stamps-js)))
    (vec (map (fn [s]
                (let [verts-js (aget s "vertices")
                      faces-js (aget s "faces")
                      color    (aget s "color")]
                  (cond-> {:vertices (vec (map vec verts-js))
                           :faces    (vec (map (fn [f] [(aget f 0) (aget f 1) (aget f 2)]) faces-js))}
                    color (assoc :color color))))
              stamps-js))))

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
                      elapsed-ms (aget result "elapsed_ms")
                      tweak-session-js (aget result "tweak_session")
                      tweak-session (when tweak-session-js
                                      (js->clj tweak-session-js :keywordize-keys true))
                      pilot-session-js (aget result "pilot_session")
                      pilot-session (when pilot-session-js
                                      (js->clj pilot-session-js :keywordize-keys true))
                      stamps (parse-stamps (aget result "stamps"))]
                  (if mesh-file
                    (let [file-id (second (re-find #"ridley-meshes-(\d+)\.bin" mesh-file))]
                      (-> (js/fetch (str server-url "/mesh-file/" file-id))
                          (.then (fn [resp]
                                   (if (.-ok resp)
                                     (.arrayBuffer resp)
                                     (throw (js/Error. (str "Mesh file fetch failed: " (.-status resp)))))))
                          (.then (fn [buf]
                                   (let [keys (js/Object.keys meshes-meta-js)
                                         pairs (map (fn [k] [k (aget meshes-meta-js k)]) keys)
                                         meshes (parse-meshes-from-buffer buf pairs)]
                                     (on-result (cond-> {:meshes meshes
                                                         :stamps stamps
                                                         :print-output print-output
                                                         :elapsed-ms elapsed-ms}
                                                  tweak-session (assoc :tweak-session tweak-session)
                                                  pilot-session (assoc :pilot-session pilot-session))))))
                          (.catch (fn [e]
                                    (on-result {:error (str "Mesh file error: " (.-message e))})))))
                    (on-result (cond-> {:meshes {}
                                        :stamps stamps
                                        :print-output print-output
                                        :elapsed-ms elapsed-ms}
                                 tweak-session (assoc :tweak-session tweak-session)
                                 pilot-session (assoc :pilot-session pilot-session)))))
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

(defn eval-repl
  "Send a REPL command to the JVM sidecar for evaluation (async).
   Uses persistent namespace — turtle state survives between calls.
   Calls on-result with {:meshes ... :lines ... :print-output ... :result ... :elapsed-ms ...}
   or {:error ...}."
  [command-text active-library-names on-result]
  (let [xhr (js/XMLHttpRequest.)]
    (.open xhr "POST" (str server-url "/eval-repl") true)
    (.setRequestHeader xhr "Content-Type" "application/json")
    (set! (.-timeout xhr) 300000)
    (set! (.-onload xhr)
          (fn []
            (if (= 200 (.-status xhr))
              (try
                (let [^js resp (js/JSON.parse (.-responseText xhr))
                      ^js meshes-meta-js (aget resp "meshes")
                      mesh-file (aget resp "mesh_file")
                      print-output (or (aget resp "print_output") "")
                      elapsed-ms (aget resp "elapsed_ms")
                      eval-result (aget resp "result")
                      lines (parse-lines (aget resp "lines"))
                      stamps (parse-stamps (aget resp "stamps"))
                      turtle-pose-js (aget resp "turtle_pose")
                      turtle-pose (when turtle-pose-js
                                    {:position (vec (aget turtle-pose-js "position"))
                                     :heading  (vec (aget turtle-pose-js "heading"))
                                     :up       (vec (aget turtle-pose-js "up"))})
                      visibility-js (aget resp "visibility")
                      visibility (when visibility-js
                                   (into {} (map (fn [k] [(keyword k) (aget visibility-js k)])
                                                 (js/Object.keys visibility-js))))
                      tweak-session-js (aget resp "tweak_session")
                      tweak-session (when tweak-session-js
                                      (js->clj tweak-session-js :keywordize-keys true))]
                  (if mesh-file
                    (let [file-id (second (re-find #"ridley-meshes-(\d+)\.bin" mesh-file))]
                      (-> (js/fetch (str server-url "/mesh-file/" file-id))
                          (.then (fn [r]
                                   (if (.-ok r)
                                     (.arrayBuffer r)
                                     (throw (js/Error. (str "Mesh file fetch failed: " (.-status r)))))))
                          (.then (fn [buf]
                                   (let [keys (js/Object.keys meshes-meta-js)
                                         pairs (map (fn [k] [k (aget meshes-meta-js k)]) keys)
                                         meshes (parse-meshes-from-buffer buf pairs)]
                                     (on-result (cond-> {:meshes meshes
                                                         :lines lines
                                                         :stamps stamps
                                                         :print-output print-output
                                                         :result eval-result
                                                         :elapsed-ms elapsed-ms
                                                         :turtle-pose turtle-pose
                                                         :visibility visibility}
                                                  tweak-session (assoc :tweak-session tweak-session))))))
                          (.catch (fn [e]
                                    (on-result {:error (str "Mesh file error: " (.-message e))})))))
                    (on-result (cond-> {:meshes {}
                                        :lines lines
                                        :stamps stamps
                                        :print-output print-output
                                        :result eval-result
                                        :elapsed-ms elapsed-ms
                                        :turtle-pose turtle-pose
                                        :visibility visibility}
                                 tweak-session (assoc :tweak-session tweak-session)))))
                (catch :default e
                  (on-result {:error (str "Parse error: " (.-message e))})))
              (let [^js err (try (js/JSON.parse (.-responseText xhr))
                                 (catch :default _ nil))]
                (on-result {:error (if err
                                     (or (.-error err) (.-responseText xhr))
                                     (str "JVM REPL failed: HTTP " (.-status xhr)))})))))
    (set! (.-onerror xhr) (fn [] (on-result {:error "JVM connection error"})))
    (set! (.-ontimeout xhr) (fn [] (on-result {:error "JVM REPL timed out"})))
    (.send xhr (js/JSON.stringify
                #js {:command command-text
                     :active_libraries (clj->js (or active-library-names []))}))))

(defn eval-script-with-libraries
  "Like eval-script but includes active library names so the sidecar
   aliases only those namespaces into the eval context."
  [script-text active-library-names on-result]

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
                      elapsed-ms (aget result "elapsed_ms")
                      tweak-session-js (aget result "tweak_session")
                      tweak-session (when tweak-session-js
                                      (js->clj tweak-session-js :keywordize-keys true))
                      pilot-session-js2 (aget result "pilot_session")
                      pilot-session2 (when pilot-session-js2
                                       (js->clj pilot-session-js2 :keywordize-keys true))
                      stamps (parse-stamps (aget result "stamps"))]

                  (if mesh-file
                    (let [file-id (second (re-find #"ridley-meshes-(\d+)\.bin" mesh-file))]

                      (-> (js/fetch (str server-url "/mesh-file/" file-id))
                          (.then (fn [resp]

                                   (if (.-ok resp)
                                     (.arrayBuffer resp)
                                     (throw (js/Error. (str "Mesh file fetch failed: " (.-status resp)))))))
                          (.then (fn [buf]
                                   (let [keys (js/Object.keys meshes-meta-js)
                                         pairs (map (fn [k] [k (aget meshes-meta-js k)]) keys)
                                         meshes (parse-meshes-from-buffer buf pairs)]

                                     (on-result (cond-> {:meshes meshes
                                                         :stamps stamps
                                                         :print-output print-output
                                                         :elapsed-ms elapsed-ms}
                                                  tweak-session (assoc :tweak-session tweak-session)
                                                  pilot-session2 (assoc :pilot-session pilot-session2))))))
                          (.catch (fn [e]
                                    (js/console.error "[eval-with-libs] fetch error:" e)
                                    (on-result {:error (str "Mesh file error: " (.-message e))})))))
                    (on-result (cond-> {:meshes {}
                                        :stamps stamps
                                        :print-output print-output
                                        :elapsed-ms elapsed-ms}
                                 tweak-session (assoc :tweak-session tweak-session)
                                 pilot-session2 (assoc :pilot-session pilot-session2)))))
                (catch :default e
                  (js/console.error "[eval-with-libs] parse error:" e)
                  (on-result {:error (str "Parse error: " (.-message e))})))
              (let [^js err (try (js/JSON.parse (.-responseText xhr))
                                 (catch :default _ nil))]
                (on-result {:error (if err
                                     (or (.-error err) (.-responseText xhr))
                                     (str "JVM eval failed: HTTP " (.-status xhr)))})))))
    (set! (.-onerror xhr) (fn [] (on-result {:error "JVM connection error"})))
    (set! (.-ontimeout xhr) (fn [] (on-result {:error "JVM eval timed out"})))
    (let [body (js/JSON.stringify
                #js {:script script-text
                     :active_libraries (clj->js (or active-library-names []))})]
      (.send xhr body))))

(defn import-stl-file
  "Upload an STL file (as ArrayBuffer) to the JVM sidecar's library system.
   The sidecar saves it to ~/.ridley/libraries/ and creates the namespace.
   Calls on-result with {:name :access_path :faces :vertices} or {:error}."
  [^js array-buffer filename on-result]
  (let [;; Convert ArrayBuffer to base64
        bytes (js/Uint8Array. array-buffer)
        len (.-length bytes)
        parts (js/Array. len)
        _ (dotimes [i len] (aset parts i (.fromCharCode js/String (aget bytes i))))
        b64 (js/btoa (.join parts ""))
        xhr (js/XMLHttpRequest.)]
    (.open xhr "POST" (str server-url "/import-stl") true)
    (.setRequestHeader xhr "Content-Type" "application/json")
    (set! (.-timeout xhr) 60000)
    (set! (.-onload xhr)
          (fn []
            (let [^js result (try (js/JSON.parse (.-responseText xhr))
                                  (catch :default _ nil))]
              (if (= 200 (.-status xhr))
                (on-result (js->clj result :keywordize-keys true))
                (on-result {:error (or (and result (.-error result))
                                       (str "Import failed: HTTP " (.-status xhr)))})))))
    (set! (.-onerror xhr) (fn [] (on-result {:error "JVM connection error"})))
    (set! (.-ontimeout xhr) (fn [] (on-result {:error "Import timed out"})))
    (.send xhr (js/JSON.stringify #js {:filename filename
                                       :data_base64 b64}))))

(defn tweak-update
  "Send a tweak slider update to the JVM sidecar (sync for interactive feel).
   Uses /eval (JSON) instead of /eval-bin (binary) because WebKit doesn't
   support responseType on synchronous XHR.
   Returns {:meshes {name mesh}} or nil on error."
  [script form values-map active-libraries]
  (try
    (let [xhr (js/XMLHttpRequest.)]
      (.open xhr "POST" (str server-url "/tweak-update") false) ;; synchronous
      (.setRequestHeader xhr "Content-Type" "application/json")
      (.send xhr (js/JSON.stringify
                  #js {:script script
                       :form form
                       :values (clj->js values-map)
                       :active_libraries (clj->js (or active-libraries []))}))
      (when (= 200 (.-status xhr))
        (let [^js result (js/JSON.parse (.-responseText xhr))
              meshes-js (aget result "meshes")
              mesh-keys (when meshes-js (js/Object.keys meshes-js))]
          (when (and mesh-keys (pos? (.-length mesh-keys)))
            {:meshes (into {}
                           (map (fn [k]
                                  (let [^js m (aget meshes-js k)
                                        verts-js (aget m "vertices")
                                        faces-js (aget m "faces")]
                                    (when (and verts-js faces-js)
                                      [(keyword k)
                                       {:type :mesh
                                        :vertices (vec (map (fn [v] [(aget v 0) (aget v 1) (aget v 2)]) verts-js))
                                        :faces (vec (map (fn [f] [(int (aget f 0)) (int (aget f 1)) (int (aget f 2))]) faces-js))
                                        :creation-pose {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}}])))
                                mesh-keys))}))))
    (catch :default e
      (js/console.warn "tweak-update error:" e)
      nil)))

(defn tweak-repl-update
  "Send a tweak slider update for a REPL command (sync).
   Uses /tweak-repl-update which re-evals via eval-repl (persistent namespace).
   Returns {:meshes {name mesh}} or nil on error."
  [command form values-map active-libraries]
  (try
    (let [xhr (js/XMLHttpRequest.)]
      (.open xhr "POST" (str server-url "/tweak-repl-update") false)
      (.setRequestHeader xhr "Content-Type" "application/json")
      (.send xhr (js/JSON.stringify
                  #js {:command command
                       :form form
                       :values (clj->js values-map)
                       :active_libraries (clj->js (or active-libraries []))}))
      (when (= 200 (.-status xhr))
        (let [^js result (js/JSON.parse (.-responseText xhr))
              meshes-js (aget result "meshes")
              mesh-keys (when meshes-js (js/Object.keys meshes-js))]
          (when (and mesh-keys (pos? (.-length mesh-keys)))
            {:meshes (into {}
                           (map (fn [k]
                                  (let [^js m (aget meshes-js k)
                                        verts-js (aget m "vertices")
                                        faces-js (aget m "faces")]
                                    (when (and verts-js faces-js)
                                      [(keyword k)
                                       {:type :mesh
                                        :vertices (vec (map (fn [v] [(aget v 0) (aget v 1) (aget v 2)]) verts-js))
                                        :faces (vec (map (fn [f] [(int (aget f 0)) (int (aget f 1)) (int (aget f 2))]) faces-js))
                                        :creation-pose {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}}])))
                                mesh-keys))}))))
    (catch :default e
      (js/console.warn "tweak-repl-update error:" e)
      nil)))

(defn delete-library
  "Delete a library from the JVM sidecar. Removes file + namespace.
   Calls on-result with {:ok true} or {:error ...}."
  [lib-name on-result]
  (let [xhr (js/XMLHttpRequest.)]
    (.open xhr "POST" (str server-url "/delete-library") true)
    (.setRequestHeader xhr "Content-Type" "application/json")
    (set! (.-timeout xhr) 10000)
    (set! (.-onload xhr)
          (fn []
            (let [^js result (try (js/JSON.parse (.-responseText xhr))
                                  (catch :default _ nil))]
              (if (= 200 (.-status xhr))
                (on-result {:ok true})
                (on-result {:error (or (and result (.-error result))
                                       (str "Delete failed: HTTP " (.-status xhr)))})))))
    (set! (.-onerror xhr) (fn [] (on-result {:error "Connection error"})))
    (set! (.-ontimeout xhr) (fn [] (on-result {:error "Timeout"})))
    (.send xhr (js/JSON.stringify #js {:name lib-name}))))

(defn list-libraries
  "Fetch library names from the JVM sidecar. Returns a Promise<vector>."
  []
  (js/Promise.
   (fn [resolve _reject]
     (let [xhr (js/XMLHttpRequest.)]
       (.open xhr "GET" (str server-url "/libraries") true)
       (set! (.-timeout xhr) 5000)
       (set! (.-onload xhr)
             (fn []
               (if (= 200 (.-status xhr))
                 (let [^js result (js/JSON.parse (.-responseText xhr))]
                   (resolve (vec (aget result "libraries"))))
                 (resolve []))))
       (set! (.-onerror xhr) (fn [] (resolve [])))
       (set! (.-ontimeout xhr) (fn [] (resolve [])))
       (.send xhr)))))
