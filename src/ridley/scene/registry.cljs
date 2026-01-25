(ns ridley.scene.registry
  "Scene registry for tracking meshes, panels, and visibility.

   Usage:
   (register torus (extrude-closed ...))  ; create, def, and register
   (register P1 (panel 40 60))            ; register a text panel
   (show torus)                            ; add to visible scene
   (hide torus)                            ; remove from scene
   (show-all)                              ; show all registered meshes
   (hide-all)                              ; clear the scene
   (visible-meshes)                        ; get meshes currently shown
   (save-stl torus)                        ; export a mesh to STL
   (save-stl (visible-meshes))             ; export all visible meshes"
  (:require [ridley.viewport.core :as viewport]
            [ridley.turtle.core :as turtle]
            [ridley.scene.panel :as panel]))

;; All meshes in the scene: [{:mesh data :name nil/keyword :visible true/false} ...]
(defonce ^:private scene-meshes (atom []))

;; Counter for unique mesh IDs
(defonce ^:private mesh-id-counter (atom 0))

;; Lines (geometry) from turtle movements
(defonce ^:private scene-lines (atom []))

;; Registered paths: [{:path data :name keyword :visible true/false} ...]
(defonce ^:private scene-paths (atom []))

;; Registered panels: [{:panel data :name keyword :visible true/false} ...]
(defonce ^:private scene-panels (atom []))

(defn clear-all!
  "Clear all meshes, lines, paths, and panels. Called on code re-evaluation."
  []
  (reset! scene-meshes [])
  (reset! scene-lines [])
  (reset! scene-paths [])
  (reset! scene-panels []))

(defn set-lines!
  "Set the lines (geometry) to display."
  [lines]
  (reset! scene-lines (vec lines)))

(defn add-lines!
  "Add lines to the current set."
  [lines]
  (swap! scene-lines into lines))

;; ============================================================
;; Path registration (polylines)
;; ============================================================

(defn- path-to-lines
  "Convert a path to line segments for rendering.
   Returns vector of {:type :line :from [x y z] :to [x y z]} segments."
  [path]
  (when (and (map? path) (= :path (:type path)))
    (let [state (turtle/run-path (turtle/make-turtle) path)
          geometry (:geometry state)]
      ;; geometry is [{:type :line :from [...] :to [...]} ...]
      (vec geometry))))

(defn register-path!
  "Register a named path. Returns the path."
  [name path]
  (when (and name path (= :path (:type path)))
    ;; Check if path with this name already exists
    (let [existing-idx (first (keep-indexed
                               (fn [i entry] (when (= (:name entry) name) i))
                               @scene-paths))]
      (if existing-idx
        ;; Update existing
        (swap! scene-paths assoc existing-idx {:path path :name name :visible true})
        ;; Add new
        (swap! scene-paths conj {:path path :name name :visible true})))
    path))

(defn show-path!
  "Show a path by name."
  [name]
  (when-let [idx (first (keep-indexed
                         (fn [i entry] (when (= (:name entry) name) i))
                         @scene-paths))]
    (swap! scene-paths assoc-in [idx :visible] true))
  nil)

(defn hide-path!
  "Hide a path by name."
  [name]
  (when-let [idx (first (keep-indexed
                         (fn [i entry] (when (= (:name entry) name) i))
                         @scene-paths))]
    (swap! scene-paths assoc-in [idx :visible] false))
  nil)

(defn visible-path-lines
  "Get line segments from all visible paths."
  []
  (vec (mapcat (fn [entry]
                 (when (:visible entry)
                   (path-to-lines (:path entry))))
               @scene-paths)))

(defn add-mesh!
  "Add a mesh to the scene. Returns the mesh data with :registry-id assigned."
  ([mesh] (add-mesh! mesh nil true))
  ([mesh name] (add-mesh! mesh name true))
  ([mesh name visible?]
   (when mesh
     (let [id (swap! mesh-id-counter inc)
           mesh-with-id (assoc mesh :registry-id id)]
       (swap! scene-meshes conj {:mesh mesh-with-id :name name :visible visible?})
       mesh-with-id))))

(defn set-definition-meshes!
  "Store meshes created by the definitions panel (anonymous, visible by default).
   Skips meshes that are already in the scene (e.g. from register)."
  [meshes]
  (let [existing-meshes (map :mesh @scene-meshes)]
    (doseq [m meshes]
      (when-not (some #(identical? % m) existing-meshes)
        (add-mesh! m nil true)))))

(defn- find-mesh-index
  "Find index of mesh entry by name."
  [name]
  (first (keep-indexed (fn [i entry] (when (= (:name entry) name) i)) @scene-meshes)))

(defn register-mesh!
  "Add a named mesh to the scene. If mesh with same name exists, replace it."
  [name mesh]
  ;; Check if mesh with this name already exists
  (if-let [idx (find-mesh-index name)]
    ;; Replace existing mesh
    (do
      (swap! scene-meshes assoc-in [idx :mesh] mesh)
      mesh)
    ;; Add new mesh
    (add-mesh! mesh name true)))

(defn- find-mesh-index-by-ref
  "Find index of mesh entry by reference (identical?)."
  [mesh]
  (first (keep-indexed (fn [i entry] (when (identical? (:mesh entry) mesh) i)) @scene-meshes)))

(defn- find-mesh-index-by-id
  "Find index of mesh entry by :registry-id."
  [registry-id]
  (first (keep-indexed (fn [i entry] (when (= (:registry-id (:mesh entry)) registry-id) i)) @scene-meshes)))

(defn update-mesh-by-ref!
  "Update a mesh in the registry by reference. Returns the new mesh or nil if not found."
  [old-mesh new-mesh]
  (when-let [idx (find-mesh-index-by-ref old-mesh)]
    (swap! scene-meshes assoc-in [idx :mesh] new-mesh)
    new-mesh))

(defn get-mesh-index
  "Get the index of a mesh in the registry by :registry-id (preferred) or reference."
  [mesh]
  (if-let [id (:registry-id mesh)]
    (find-mesh-index-by-id id)
    (find-mesh-index-by-ref mesh)))

(defn get-mesh-at-index
  "Get mesh data at a specific index."
  [idx]
  (get-in @scene-meshes [idx :mesh]))

(defn update-mesh-at-index!
  "Update mesh at a specific index. Preserves :registry-id. Returns the new mesh."
  [idx new-mesh]
  (let [old-id (get-in @scene-meshes [idx :mesh :registry-id])
        mesh-with-id (if old-id
                       (assoc new-mesh :registry-id old-id)
                       new-mesh)]
    (swap! scene-meshes assoc-in [idx :mesh] mesh-with-id)
    mesh-with-id))

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

(defn show-mesh-ref!
  "Show a mesh by reference. Returns nil."
  [mesh]
  (when-let [idx (find-mesh-index-by-ref mesh)]
    (swap! scene-meshes assoc-in [idx :visible] true))
  nil)

(defn hide-mesh-ref!
  "Hide a mesh by reference. Returns nil."
  [mesh]
  (when-let [idx (find-mesh-index-by-ref mesh)]
    (swap! scene-meshes assoc-in [idx :visible] false))
  nil)

(defn show-all!
  "Show all meshes (both named and anonymous)."
  []
  (swap! scene-meshes (fn [meshes]
                        (mapv #(assoc % :visible true) meshes)))
  nil)

(defn hide-all!
  "Hide all meshes (both named and anonymous)."
  []
  (swap! scene-meshes (fn [meshes]
                        (mapv #(assoc % :visible false) meshes)))
  nil)

(defn show-only-registered!
  "Show only registered (named) meshes, hide all anonymous ones."
  []
  (swap! scene-meshes (fn [meshes]
                        (mapv #(assoc % :visible (some? (:name %))) meshes)))
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

(defn anonymous-count
  "Get count of anonymous (unnamed) meshes."
  []
  (count (filter #(nil? (:name %)) @scene-meshes)))

(defn all-meshes-info
  "Get info about all meshes in scene.
   Returns vector of {:name :visible :vertices :faces} maps.
   Anonymous meshes have :name as :anon-0, :anon-1, etc."
  []
  (vec (map-indexed
        (fn [idx entry]
          (let [mesh (:mesh entry)
                name-or-idx (or (:name entry) (keyword (str "anon-" idx)))]
            {:name name-or-idx
             :visible (:visible entry)
             :vertices (count (:vertices mesh))
             :faces (count (:faces mesh))}))
        @scene-meshes)))

(defn anonymous-meshes
  "Get all anonymous mesh data as vector."
  []
  (vec (keep (fn [entry] (when (nil? (:name entry)) (:mesh entry))) @scene-meshes)))

;; ============================================================
;; Panel registration
;; ============================================================

(defn- find-panel-index
  "Find index of panel entry by name."
  [name]
  (first (keep-indexed (fn [i entry] (when (= (:name entry) name) i)) @scene-panels)))

(defn register-panel!
  "Add a named panel to the scene. If panel with same name exists, replace it."
  [name panel-data]
  (when (panel/panel? panel-data)
    (if-let [idx (find-panel-index name)]
      ;; Replace existing panel
      (do
        (swap! scene-panels assoc-in [idx :panel] panel-data)
        panel-data)
      ;; Add new panel
      (do
        (swap! scene-panels conj {:panel panel-data :name name :visible true})
        panel-data))))

(defn get-panel
  "Get panel data by name."
  [name]
  (:panel (first (filter #(= (:name %) name) @scene-panels))))

(defn update-panel!
  "Update a panel's data by name. Returns the updated panel or nil."
  [name update-fn]
  (when-let [idx (find-panel-index name)]
    (let [updated (update-fn (get-in @scene-panels [idx :panel]))]
      (swap! scene-panels assoc-in [idx :panel] updated)
      updated)))

(defn show-panel!
  "Show a panel by name. Returns nil."
  [name]
  (when-let [idx (find-panel-index name)]
    (swap! scene-panels assoc-in [idx :visible] true))
  nil)

(defn hide-panel!
  "Hide a panel by name. Returns nil."
  [name]
  (when-let [idx (find-panel-index name)]
    (swap! scene-panels assoc-in [idx :visible] false))
  nil)

(defn visible-panels
  "Get all currently visible panel data."
  []
  (vec (keep (fn [entry] (when (:visible entry) (:panel entry))) @scene-panels)))

(defn all-panels
  "Get all panels (visible and hidden)."
  []
  (vec (map :panel @scene-panels)))

(defn panel-names
  "Get names of all registered panels."
  []
  (vec (keep :name @scene-panels)))

(defn refresh-viewport!
  "Update the viewport with all visible meshes, lines, paths, and panels.
   reset-camera?: if true (default), fit camera to geometry"
  ([] (refresh-viewport! true))
  ([reset-camera?]
   ;; Combine scene-lines with path lines
   (let [all-lines (into (vec @scene-lines) (visible-path-lines))]
     (viewport/update-scene {:lines all-lines
                             :meshes (visible-meshes)
                             :panels (visible-panels)
                             :reset-camera? reset-camera?}))))
