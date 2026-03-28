(ns ridley.manifold.native
  "Native Manifold CSG operations via Tauri IPC backend.
   These call the Rust backend instead of WASM, for performance comparison.
   Only works when running inside Tauri desktop app.")

(defn- tauri-available? []
  (and (exists? js/window)
       (exists? js/window.__TAURI__)))

(defn- mesh->js
  "Convert a Ridley mesh to plain JS for JSON serialization via Tauri IPC."
  [mesh]
  (let [vertices (mapv (fn [v] #js [(v 0) (v 1) (v 2)]) (:vertices mesh))
        faces (mapv (fn [f] #js [(f 0) (f 1) (f 2)]) (:faces mesh))]
    #js {:vertices (into-array vertices)
         :faces (into-array faces)}))

(defn- js->mesh
  "Convert JS result back to a Ridley mesh map."
  [^js result]
  (let [verts-js (.-vertices result)
        faces-js (.-faces result)]
    {:type :mesh
     :vertices (vec (map (fn [^js v] [(aget v 0) (aget v 1) (aget v 2)]) verts-js))
     :faces (vec (map (fn [^js f] [(aget f 0) (aget f 1) (aget f 2)]) faces-js))}))

(defn- invoke-sync
  "Call a Tauri command synchronously by blocking on the promise.
   WARNING: This blocks the UI thread. For interactive use only."
  [cmd args]
  (when-not (tauri-available?)
    (throw (js/Error. "Native backend only available in Tauri desktop app")))
  (let [result (atom nil)
        error (atom nil)
        done (atom false)]
    (-> (js/window.__TAURI__.core.invoke cmd args)
        (.then (fn [r] (reset! result r) (reset! done true)))
        (.catch (fn [e] (reset! error e) (reset! done true))))
    ;; Spin-wait (only viable for fast ops, not ideal)
    ;; Better approach: use async invoke and return promise
    ;; For now, return the promise and let the caller await
    (js/window.__TAURI__.core.invoke cmd args)))

(defn native-union
  "Union meshes using native Rust Manifold backend.
   Returns a JS Promise that resolves to a Ridley mesh."
  [& meshes-or-vec]
  (let [meshes (if (and (= 1 (count meshes-or-vec))
                        (sequential? (first meshes-or-vec)))
                 (first meshes-or-vec)
                 meshes-or-vec)
        js-meshes (into-array (map mesh->js meshes))]
    (-> (js/window.__TAURI__.core.invoke "manifold_union" #js {:meshes js-meshes})
        (.then js->mesh))))

(defn native-difference
  "Subtract meshes using native Rust Manifold backend.
   Returns a JS Promise that resolves to a Ridley mesh."
  [base & cutters]
  (let [js-base (mesh->js base)
        js-cutters (into-array (map mesh->js cutters))]
    (-> (js/window.__TAURI__.core.invoke "manifold_difference"
          #js {:base js-base :cutters js-cutters})
        (.then js->mesh))))

(defn native-intersection
  "Intersect meshes using native Rust Manifold backend.
   Returns a JS Promise that resolves to a Ridley mesh."
  [& meshes-or-vec]
  (let [meshes (if (and (= 1 (count meshes-or-vec))
                        (sequential? (first meshes-or-vec)))
                 (first meshes-or-vec)
                 meshes-or-vec)
        js-meshes (into-array (map mesh->js meshes))]
    (-> (js/window.__TAURI__.core.invoke "manifold_intersection" #js {:meshes js-meshes})
        (.then js->mesh))))

(defn native-hull
  "Convex hull using native Rust Manifold backend.
   Returns a JS Promise that resolves to a Ridley mesh."
  [& meshes-or-vec]
  (let [meshes (if (and (= 1 (count meshes-or-vec))
                        (sequential? (first meshes-or-vec)))
                 (first meshes-or-vec)
                 meshes-or-vec)
        js-meshes (into-array (map mesh->js meshes))]
    (-> (js/window.__TAURI__.core.invoke "manifold_hull" #js {:meshes js-meshes})
        (.then js->mesh))))
