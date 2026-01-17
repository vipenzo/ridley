(ns ridley.scene.registry
  "Named object registry for the scene.

   Allows naming meshes and controlling their visibility:
   - (register-mesh 'my-obj mesh) - register a mesh with a name
   - (show 'my-obj) - make visible
   - (hide 'my-obj) - make invisible
   - (hide-all) - hide all registered objects
   - (show-all) - show all registered objects
   - (visible-meshes) - get list of visible meshes for export")

;; Registry: name -> {:mesh mesh-data :visible bool}
(defonce ^:private registry (atom {}))

(defn clear-registry!
  "Clear all registered objects."
  []
  (reset! registry {}))

(defn register-mesh
  "Register a mesh with a name. Returns the mesh.
   If mesh is nil, does nothing and returns nil."
  [name mesh]
  (when mesh
    (swap! registry assoc name {:mesh mesh :visible true}))
  mesh)

(defn unregister
  "Remove a named object from the registry."
  [name]
  (swap! registry dissoc name)
  nil)

(defn show
  "Make a named object visible."
  [name]
  (when (contains? @registry name)
    (swap! registry assoc-in [name :visible] true))
  nil)

(defn hide
  "Make a named object invisible."
  [name]
  (when (contains? @registry name)
    (swap! registry assoc-in [name :visible] false))
  nil)

(defn show-all
  "Make all registered objects visible."
  []
  (swap! registry
         (fn [reg]
           (into {} (map (fn [[k v]] [k (assoc v :visible true)]) reg))))
  nil)

(defn hide-all
  "Make all registered objects invisible."
  []
  (swap! registry
         (fn [reg]
           (into {} (map (fn [[k v]] [k (assoc v :visible false)]) reg))))
  nil)

(defn visible-meshes
  "Get all visible meshes as a vector."
  []
  (->> @registry
       vals
       (filter :visible)
       (map :mesh)
       vec))

(defn get-mesh
  "Get a mesh by name (regardless of visibility)."
  [name]
  (get-in @registry [name :mesh]))

(defn is-visible?
  "Check if a named object is visible."
  [name]
  (get-in @registry [name :visible] false))

(defn list-objects
  "List all registered object names with their visibility."
  []
  (->> @registry
       (map (fn [[name {:keys [visible]}]]
              {:name name :visible visible}))
       vec))

(defn get-registry-state
  "Get current registry state (for rendering)."
  []
  @registry)
