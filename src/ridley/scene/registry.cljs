(ns ridley.scene.registry
  "Scene registry for tracking meshes and visibility.

   Usage:
   (register torus (extrude-closed ...))  ; create, def, and register
   (show torus)                            ; add to visible scene
   (hide torus)                            ; remove from scene
   (show-all)                              ; show all registered meshes
   (hide-all)                              ; clear the scene
   (visible-meshes)                        ; get meshes currently shown
   (save-stl torus)                        ; export a mesh to STL
   (save-stl (visible-meshes))             ; export all visible meshes"
  (:require [ridley.viewport.core :as viewport]))

;; All meshes in the scene: [{:mesh data :name nil/keyword :visible true/false} ...]
(defonce ^:private scene-meshes (atom []))

(defn clear-all!
  "Clear all meshes. Called on code re-evaluation."
  []
  (reset! scene-meshes []))

(defn add-mesh!
  "Add a mesh to the scene. Returns the mesh data (not the wrapper)."
  ([mesh] (add-mesh! mesh nil true))
  ([mesh name] (add-mesh! mesh name true))
  ([mesh name visible?]
   (when mesh
     (swap! scene-meshes conj {:mesh mesh :name name :visible visible?}))
   mesh))

(defn set-definition-meshes!
  "Store meshes created by the definitions panel (anonymous, visible by default).
   Skips meshes that are already in the scene (e.g. from register)."
  [meshes]
  (let [existing-meshes (map :mesh @scene-meshes)]
    (doseq [m meshes]
      (when-not (some #(identical? % m) existing-meshes)
        (add-mesh! m nil true)))))

(defn register-mesh!
  "Add a named mesh to the scene. Returns the mesh data."
  [name mesh]
  (add-mesh! mesh name true))

(defn- find-mesh-index
  "Find index of mesh entry by name."
  [name]
  (first (keep-indexed (fn [i entry] (when (= (:name entry) name) i)) @scene-meshes)))

(defn show-mesh!
  "Show a mesh by name. Returns nil."
  [name]
  (when-let [idx (find-mesh-index name)]
    (swap! scene-meshes assoc-in [idx :visible] true))
  nil)

(defn hide-mesh!
  "Hide a mesh by name. Returns nil."
  [name]
  (when-let [idx (find-mesh-index name)]
    (swap! scene-meshes assoc-in [idx :visible] false))
  nil)

(defn show-all!
  "Show all named meshes."
  []
  (swap! scene-meshes (fn [meshes]
                        (mapv #(if (:name %) (assoc % :visible true) %) meshes)))
  nil)

(defn hide-all!
  "Hide all named meshes."
  []
  (swap! scene-meshes (fn [meshes]
                        (mapv #(if (:name %) (assoc % :visible false) %) meshes)))
  nil)

(defn visible-names
  "Get names of currently visible named meshes."
  []
  (vec (keep (fn [entry] (when (and (:name entry) (:visible entry)) (:name entry))) @scene-meshes)))

(defn visible-meshes
  "Get all currently visible mesh data."
  []
  (vec (keep (fn [entry] (when (:visible entry) (:mesh entry))) @scene-meshes)))

(defn registered-names
  "Get all named mesh names."
  []
  (vec (keep :name @scene-meshes)))

(defn get-mesh
  "Get mesh data by name."
  [name]
  (:mesh (first (filter #(= (:name %) name) @scene-meshes))))

(defn visible-count
  "Get count of visible meshes."
  []
  (count (filter :visible @scene-meshes)))

(defn registered-count
  "Get count of named meshes."
  []
  (count (filter :name @scene-meshes)))

(defn refresh-viewport!
  "Update the viewport with all visible meshes."
  []
  (viewport/update-scene {:lines [] :meshes (visible-meshes)}))
