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
            [ridley.schema :as schema]
            [ridley.scene.panel :as panel]))

;; All meshes in the scene: [{:mesh data :name nil/keyword :visible true/false} ...]
(defonce ^:private scene-meshes (atom []))

;; Counter for unique mesh IDs
(defonce ^:private mesh-id-counter (atom 0))

;; Lines (geometry) from turtle movements
(defonce ^:private scene-lines (atom []))

;; Stamps (debug shape outlines) from turtle
(defonce ^:private scene-stamps (atom []))

;; Registered paths: [{:path data :name keyword :visible true/false} ...]
(defonce ^:private scene-paths (atom []))

;; Registered panels: [{:panel data :name keyword :visible true/false} ...]
(defonce ^:private scene-panels (atom []))

;; Registered shapes: [{:shape data :name keyword} ...]
;; Shapes have no visibility concept (not directly renderable)
(defonce ^:private scene-shapes (atom []))

;; General-purpose value store: {keyword -> any-value}
;; Stores the raw value passed to register, regardless of type.
(defonce ^:private scene-values (atom {}))

(defn clear-all!
  "Clear all meshes, lines, paths, panels, and shapes. Called on code re-evaluation."
  []
  (reset! scene-meshes [])
  (reset! scene-lines [])
  (reset! scene-stamps [])
  (reset! scene-paths [])
  (reset! scene-panels [])
  (reset! scene-shapes [])
  (reset! scene-values {}))

(defn set-lines!
  "Set the lines (geometry) to display."
  [lines]
  (reset! scene-lines (vec lines)))

(defn add-lines!
  "Add lines to the current set."
  [lines]
  (swap! scene-lines into lines))

(defn set-stamps!
  "Set the stamps (debug shape outlines) to display."
  [stamps]
  (reset! scene-stamps (vec stamps)))

(defn add-stamps!
  "Add stamps to the current set."
  [stamps]
  (swap! scene-stamps into stamps))

;; ============================================================
;; Path registration (abstract, no visibility)
;; ============================================================

(defn- find-path-index
  "Find index of path entry by name."
  [name]
  (first (keep-indexed (fn [i entry] (when (= (:name entry) name) i)) @scene-paths)))

(defn register-path!
  "Register a named path. Returns the path."
  [name path]
  (when (and name path (= :path (:type path)))
    (schema/assert-path! path)
    (if-let [idx (find-path-index name)]
      (swap! scene-paths assoc idx {:path path :name name})
      (swap! scene-paths conj {:path path :name name}))
    path))

(defn get-path
  "Get path data by name."
  [name]
  (:path (first (filter #(= (:name %) name) @scene-paths))))

(defn add-mesh!
  "Add a mesh to the scene. Returns the mesh data with :registry-id assigned."
  ([mesh] (add-mesh! mesh nil true))
  ([mesh name] (add-mesh! mesh name true))
  ([mesh name visible?]
   (when mesh
     (schema/assert-mesh! mesh)
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
      (schema/assert-mesh! m)
      (when-not (some #(identical? % m) existing-meshes)
        (add-mesh! m nil true)))))

(defn- find-mesh-index
  "Find index of mesh entry by name."
  [name]
  (first (keep-indexed (fn [i entry] (when (= (:name entry) name) i)) @scene-meshes)))

(defn register-mesh!
  "Add a named mesh to the scene. If mesh with same name exists, replace it."
  [name mesh]
  (schema/assert-mesh! mesh)
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
  (schema/assert-mesh! new-mesh)
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
  (schema/assert-mesh! new-mesh)
  (let [old-id (get-in @scene-meshes [idx :mesh :registry-id])
        mesh-with-id (if old-id
                       (assoc new-mesh :registry-id old-id)
                       new-mesh)]
    (swap! scene-meshes assoc-in [idx :mesh] mesh-with-id)
    mesh-with-id))

(defn- name-has-prefix?
  "Check if mesh-name starts with prefix.
   E.g. :puppet/r-arm starts with :puppet."
  [mesh-name prefix]
  (let [prefix-str (name prefix)
        mesh-str (name mesh-name)]
    (or (= mesh-str prefix-str)
        (.startsWith mesh-str (str prefix-str "/")))))

(defn show-by-prefix!
  "Show all meshes whose name starts with the given prefix keyword.
   E.g. (show-by-prefix! :puppet) shows :puppet/torso, :puppet/r-arm/upper, etc."
  [prefix]
  (swap! scene-meshes (fn [meshes]
                        (mapv (fn [entry]
                                (if (and (:name entry)
                                         (name-has-prefix? (:name entry) prefix))
                                  (assoc entry :visible true)
                                  entry))
                              meshes)))
  nil)

(defn hide-by-prefix!
  "Hide all meshes whose name starts with the given prefix keyword.
   E.g. (hide-by-prefix! :puppet) hides :puppet/torso, :puppet/r-arm/upper, etc."
  [prefix]
  (swap! scene-meshes (fn [meshes]
                        (mapv (fn [entry]
                                (if (and (:name entry)
                                         (name-has-prefix? (:name entry) prefix))
                                  (assoc entry :visible false)
                                  entry))
                              meshes)))
  nil)

(defn show-mesh!
  "Show a mesh by name. If exact match not found, shows all meshes
   with matching prefix (for assembly hierarchies)."
  [name]
  (if-let [idx (find-mesh-index name)]
    (swap! scene-meshes assoc-in [idx :visible] true)
    (show-by-prefix! name))
  nil)

(defn hide-mesh!
  "Hide a mesh by name. If exact match not found, hides all meshes
   with matching prefix (for assembly hierarchies)."
  [name]
  (if-let [idx (find-mesh-index name)]
    (swap! scene-meshes assoc-in [idx :visible] false)
    (hide-by-prefix! name))
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

(defn visible-meshes-with-names
  "Get visible meshes as [{:mesh data :name kw-or-nil} ...] for viewport tagging."
  []
  (vec (keep (fn [entry] (when (:visible entry) {:mesh (:mesh entry) :name (:name entry)}))
             @scene-meshes)))

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

;; ============================================================
;; Shape registration
;; ============================================================

(defn- find-shape-index
  "Find index of shape entry by name."
  [name]
  (first (keep-indexed (fn [i entry] (when (= (:name entry) name) i)) @scene-shapes)))

(defn register-shape!
  "Register a named shape. Returns the shape."
  [name shape]
  (when (and name shape (map? shape) (= :shape (:type shape)))
    (if-let [idx (find-shape-index name)]
      (swap! scene-shapes assoc idx {:shape shape :name name})
      (swap! scene-shapes conj {:shape shape :name name}))
    shape))

(defn get-shape
  "Get shape data by name."
  [name]
  (:shape (first (filter #(= (:name %) name) @scene-shapes))))

(defn shape-names
  "Get names of all registered shapes."
  []
  (vec (keep :name @scene-shapes)))

;; ============================================================
;; General-purpose value store
;; ============================================================

(defn register-value!
  "Store any value by name. Called by register macro for all types."
  [name value]
  (swap! scene-values assoc name value)
  value)

(defn get-value
  "Get the raw stored value by name. Returns whatever was passed to register."
  [name]
  (get @scene-values name))

;; ============================================================
;; Cross-type query helpers
;; ============================================================

(defn path-names
  "Get names of all registered paths."
  []
  (vec (keep :name @scene-paths)))

;; ============================================================
;; Animation support
;; ============================================================

(defn update-mesh-vertices!
  "Update the vertices of a registered mesh in-place (for animation).
   Does not trigger viewport rebuild."
  [name new-vertices]
  (when-let [idx (find-mesh-index name)]
    (swap! scene-meshes update-in [idx :mesh] assoc :vertices new-vertices)))

(defn get-mesh-data
  "Get the full mesh data map for a registered name."
  [name]
  (:mesh (first (filter #(= (:name %) name) @scene-meshes))))

(defn refresh-viewport!
  "Update the viewport with all visible meshes, lines, and panels.
   reset-camera?: if true (default), fit camera to geometry"
  ([] (refresh-viewport! true))
  ([reset-camera?]
   ;; Include :registry-name in mesh data so viewport can tag Three.js objects
   (let [meshes (vec (keep (fn [entry]
                             (when (:visible entry)
                               (cond-> (:mesh entry)
                                 (:name entry) (assoc :registry-name (:name entry)))))
                           @scene-meshes))]
     (viewport/update-scene {:lines (vec @scene-lines)
                             :stamps (vec @scene-stamps)
                             :meshes meshes
                             :panels (visible-panels)
                             :reset-camera? reset-camera?}))))

(defn update-mesh-material!
  "Update material properties on a named mesh (or all meshes with matching prefix).
   Merges material-updates into existing material, preserving unspecified properties.
   Refreshes the viewport after updating."
  [name material-updates]
  (let [idx (find-mesh-index name)]
    (if idx
      ;; Single mesh — merge material
      (swap! scene-meshes update-in [idx :mesh :material] merge material-updates)
      ;; Try prefix match (for assembly hierarchies / vectors of meshes)
      (swap! scene-meshes (fn [meshes]
                            (mapv (fn [entry]
                                    (if (and (:name entry)
                                             (name-has-prefix? (:name entry) name))
                                      (update-in entry [:mesh :material] merge material-updates)
                                      entry))
                                  meshes)))))
  (refresh-viewport! false))
