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

;; Registered meshes: {name -> mesh}
(defonce ^:private registered (atom {}))

;; Visible mesh names: #{name1 name2 ...}
(defonce ^:private visible (atom #{}))

;; Meshes from definitions panel (non-registered, "anonymous" meshes)
(defonce ^:private definition-meshes (atom []))

(defn clear-all!
  "Clear both registry and visible set. Called on code re-evaluation."
  []
  (reset! registered {})
  (reset! visible #{})
  (reset! definition-meshes []))

(defn set-definition-meshes!
  "Store meshes created by the definitions panel (non-registered meshes).
   Automatically excludes meshes that are already in the registry."
  [meshes]
  (let [registry-mesh-set (set (vals @registered))
        non-registered (remove #(contains? registry-mesh-set %) meshes)]
    (reset! definition-meshes (vec non-registered))))

(defn get-definition-meshes
  "Get meshes stored from definitions panel."
  []
  @definition-meshes)

(defn register-mesh!
  "Add a named mesh to the registry. Returns the mesh."
  [name mesh]
  (when (and name mesh)
    (swap! registered assoc name mesh))
  mesh)

(defn show-mesh!
  "Add a mesh name to the visible set. Returns nil."
  [name]
  (when name
    (swap! visible conj name))
  nil)

(defn hide-mesh!
  "Remove a mesh name from the visible set. Returns nil."
  [name]
  (when name
    (swap! visible disj name))
  nil)

(defn show-all!
  "Show all registered meshes."
  []
  (reset! visible (set (keys @registered)))
  nil)

(defn hide-all!
  "Hide all meshes (clear visible set)."
  []
  (reset! visible #{})
  nil)

(defn visible-names
  "Get names of currently visible meshes as a vector."
  []
  (vec @visible))

(defn visible-meshes
  "Get all currently visible mesh data as a vector."
  []
  (let [reg @registered
        vis @visible]
    (vec (keep (fn [name] (get reg name)) vis))))

(defn registered-names
  "Get all registered mesh names as a vector."
  []
  (vec (keys @registered)))

(defn get-mesh
  "Get mesh data by name."
  [name]
  (get @registered name))

(defn visible-count
  "Get count of visible meshes."
  []
  (count @visible))

(defn registered-count
  "Get count of registered meshes."
  []
  (count @registered))

(defn refresh-viewport!
  "Update the viewport with current visible meshes and definition meshes."
  []
  (let [registry-meshes (visible-meshes)
        def-meshes @definition-meshes
        all-meshes (concat def-meshes registry-meshes)]
    (viewport/update-scene {:lines [] :meshes (vec all-meshes)})))
