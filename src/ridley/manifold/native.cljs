(ns ridley.manifold.native
  "Native Manifold CSG operations via local Rust HTTP server.
   Synchronous calls via XMLHttpRequest — results are immediate meshes.
   Only works when running inside Tauri desktop app.")

(def ^:private server-url "http://127.0.0.1:12321")

(defn- mesh->js
  "Convert a Ridley mesh to plain JS object for JSON serialization."
  [mesh]
  #js {:vertices (into-array (map (fn [v] #js [(v 0) (v 1) (v 2)]) (:vertices mesh)))
       :faces (into-array (map (fn [f] #js [(f 0) (f 1) (f 2)]) (:faces mesh)))})

(defn- js->mesh
  "Convert JS result back to a Ridley mesh map."
  [^js result]
  {:type :mesh
   :vertices (vec (map (fn [^js v] [(aget v 0) (aget v 1) (aget v 2)]) (.-vertices result)))
   :faces (vec (map (fn [^js f] [(int (aget f 0)) (int (aget f 1)) (int (aget f 2))]) (.-faces result)))
   :creation-pose {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}})

(defn- invoke-sync
  "Synchronous HTTP POST to the Rust geometry server. Returns parsed JS object."
  [endpoint body-js]
  (let [xhr (js/XMLHttpRequest.)]
    (.open xhr "POST" (str server-url endpoint) false)  ;; false = synchronous
    (.setRequestHeader xhr "Content-Type" "application/json")
    (.send xhr (js/JSON.stringify body-js))
    (if (= 200 (.-status xhr))
      (js/JSON.parse (.-responseText xhr))
      (throw (js/Error. (str "Native manifold error: " (.-responseText xhr)))))))

(defn native-union
  "Union meshes using native Rust Manifold backend. Synchronous — returns a mesh."
  [& meshes-or-vec]
  (let [meshes (if (and (= 1 (count meshes-or-vec))
                        (sequential? (first meshes-or-vec)))
                 (first meshes-or-vec)
                 meshes-or-vec)]
    (js->mesh (invoke-sync "/union" (into-array (map mesh->js meshes))))))

(defn native-difference
  "Subtract meshes using native Rust Manifold backend. Synchronous — returns a mesh."
  [base & cutters]
  (js->mesh (invoke-sync "/difference"
                         #js {:base (mesh->js base)
                              :cutters (into-array (map mesh->js cutters))})))

(defn native-intersection
  "Intersect meshes using native Rust Manifold backend. Synchronous — returns a mesh."
  [& meshes-or-vec]
  (let [meshes (if (and (= 1 (count meshes-or-vec))
                        (sequential? (first meshes-or-vec)))
                 (first meshes-or-vec)
                 meshes-or-vec)]
    (js->mesh (invoke-sync "/intersection" (into-array (map mesh->js meshes))))))

(defn bench
  "Benchmark: run a zero-arg function, print elapsed time, return result.
   (bench \"wasm union\" #(mesh-union a b))"
  [label f]
  (let [t0 (.now js/performance)
        result (if (fn? f) (f) f)
        t1 (.now js/performance)]
    (println (str label ": " (.toFixed (- t1 t0) 1) "ms"))
    result))

(defn native-hull
  "Convex hull using native Rust Manifold backend. Synchronous — returns a mesh."
  [& meshes-or-vec]
  (let [meshes (if (and (= 1 (count meshes-or-vec))
                        (sequential? (first meshes-or-vec)))
                 (first meshes-or-vec)
                 meshes-or-vec)]
    (js->mesh (invoke-sync "/hull" (into-array (map mesh->js meshes))))))
