(ns ridley.viewport.xr
  "WebXR VR support for Ridley viewport."
  (:require ["three" :as THREE]
            [clojure.string :as str]))

;; Callbacks for actions that need access to other modules (avoid circular deps)
(defonce ^:private action-callbacks (atom {}))

(defonce ^:private xr-state (atom {:supported false
                                    :ar-supported false
                                    :session nil
                                    :session-mode nil ;; :vr or :ar
                                    :button nil
                                    :ar-button nil
                                    :controller nil
                                    :input-source nil
                                    :renderer nil
                                    :camera nil
                                    :scene nil
                                    :world-group nil
                                    :controller-group nil
                                    :control-panel nil
                                    :panel-buttons []
                                    :panel-visible false
                                    :grip-held false
                                    :trigger-held false
                                    :drag-pending false ;; true when trigger just pressed, wait for pose update
                                    :drag-start-ray-point nil ;; ray point at drag start
                                    :drag-world-start nil ;; world-group position when drag started
                                    :drag-distance nil ;; distance from controller to drag point
                                    :a-button-was-pressed false
                                    :b-button-was-pressed false
                                    :hovered-button nil
                                    :mode :move
                                    :original-background nil
                                    :show-all-view true  ;; All/Obj toggle state
                                    :grid-visible true   ;; Grid visibility
                                    :axes-visible true})) ;; Axes visibility

;; Movement settings
(def ^:private move-speed 0.4)
(def ^:private rotate-speed 0.025)
(def ^:private deadzone 0.15)
(defonce ^:private debug-counter (atom 0))

;; Raycaster for button interaction
(defonce ^:private raycaster (THREE/Raycaster.))
(defonce ^:private temp-matrix (THREE/Matrix4.))

;; ============================================================
;; XR Detection
;; ============================================================

(defn check-xr-support
  "Check if WebXR VR is available. Returns a Promise resolving to boolean."
  []
  (if js/navigator.xr
    (.isSessionSupported js/navigator.xr "immersive-vr")
    (js/Promise.resolve false)))

(defn check-ar-support
  "Check if WebXR AR (passthrough) is available. Returns a Promise resolving to boolean."
  []
  (if js/navigator.xr
    (.isSessionSupported js/navigator.xr "immersive-ar")
    (js/Promise.resolve false)))

;; ============================================================
;; Session Management
;; ============================================================

(defn- on-session-started [^js session ^js renderer session-mode] 
  (let [scene (:scene @xr-state)]
    ;; Save original background for restoration
    (when (and scene (= session-mode :ar))
      (swap! xr-state assoc :original-background (.-background scene))
      ;; Set transparent background for passthrough
      (set! (.-background scene) nil))
    (swap! xr-state assoc
           :session session
           :session-mode session-mode
           :panel-visible false
           :a-button-was-pressed false)
    (.setSession ^js (.-xr renderer) session)
    ;; Disable foveated rendering to avoid visible rectangular artifacts
    (when (.-setFoveation (.-xr renderer))
      (.setFoveation (.-xr renderer) 0))
    (.setReferenceSpaceType (.-xr renderer) "local-floor")
    ;; Show controller group when entering XR
    (when-let [controller-group (:controller-group @xr-state)]
      (set! (.-visible controller-group) true))
    (when-let [panel (:control-panel @xr-state)]
      (set! (.-visible panel) false))
    (when-let [btn (:button @xr-state)]
      (set! (.-textContent btn) "Exit VR"))
    (when-let [ar-btn (:ar-button @xr-state)]
      (set! (.-textContent ar-btn) "Exit AR"))))

(defn- on-session-ended []
  ;; Restore original background if we were in AR mode
  (when-let [{:keys [scene original-background session-mode]} @xr-state]
    (when (and scene (= session-mode :ar) original-background)
      (set! (.-background scene) original-background)))
  ;; Hide controller group when exiting XR
  (when-let [controller-group (:controller-group @xr-state)]
    (set! (.-visible controller-group) false))
  (swap! xr-state assoc :session nil :session-mode nil :original-background nil)
  (when-let [btn (:button @xr-state)]
    (set! (.-textContent btn) "Enter VR"))
  (when-let [ar-btn (:ar-button @xr-state)]
    (set! (.-textContent ar-btn) "Passthrough")))

(defn enter-vr
  "Request immersive VR session."
  [^js renderer]
  (-> (.requestSession ^js js/navigator.xr "immersive-vr"
                       #js {:optionalFeatures #js ["local-floor" "bounded-floor"]})
      (.then (fn [^js session]
               (.addEventListener session "end" on-session-ended)
               (on-session-started session renderer :vr)))
      (.catch (fn [err]
                (js/console.error "Failed to start VR session:" err)))))

(defn enter-ar
  "Request immersive AR session (passthrough mode)."
  [^js renderer]
  (-> (.requestSession ^js js/navigator.xr "immersive-ar"
                       #js {:optionalFeatures #js ["local-floor" "bounded-floor"]})
      (.then (fn [^js session]
               (.addEventListener session "end" on-session-ended)
               (on-session-started session renderer :ar)))
      (.catch (fn [err]
                (js/console.error "Failed to start AR session:" err)))))

(defn exit-vr
  "End current VR session."
  []
  (when-let [^js session (:session @xr-state)]
    (.end session)))

(defn toggle-vr
  "Toggle VR session on/off."
  [^js renderer]
  (if (:session @xr-state)
    (exit-vr)
    (enter-vr renderer)))

(defn toggle-ar
  "Toggle AR (passthrough) session on/off."
  [^js renderer]
  (if (:session @xr-state)
    (exit-vr)
    (enter-ar renderer)))

;; ============================================================
;; Initialization
;; ============================================================

(defn register-action-callback!
  "Register a callback for XR panel actions.
   Supported actions: :toggle-all-obj (fn [show-all?] ...)"
  [action-key callback-fn]
  (swap! action-callbacks assoc action-key callback-fn))

(defn enable-xr
  "Enable WebXR on the renderer. Call after renderer creation."
  [^js renderer]
  (set! (.-enabled ^js (.-xr renderer)) true))

(defn create-vr-button
  "Create a VR button element. Returns the button."
  [renderer on-click-fn]
  (let [btn (js/document.createElement "button")]
    (.add (.-classList btn) "action-btn" "vr-btn")
    (set! (.-textContent btn) "Enter VR")
    (set! (.-disabled btn) true)
    (set! (.-title btn) "Checking VR support...")
    (.addEventListener btn "click" on-click-fn)
    (-> (check-xr-support)
        (.then (fn [supported]
                 (swap! xr-state assoc :supported supported :button btn)
                 (if supported
                   (do
                     (set! (.-disabled btn) false)
                     (set! (.-title btn) "Enter VR mode"))
                   (do
                     (set! (.-textContent btn) "VR N/A")
                     (set! (.-title btn) "WebXR not available"))))))
    btn))

(defn create-ar-button
  "Create an AR/Passthrough button element. Returns the button."
  [renderer on-click-fn]
  (let [btn (js/document.createElement "button")]
    (.add (.-classList btn) "action-btn" "ar-btn")
    (set! (.-textContent btn) "Passthrough")
    (set! (.-disabled btn) true)
    (set! (.-title btn) "Checking AR support...")
    (.addEventListener btn "click" on-click-fn)
    (-> (check-ar-support)
        (.then (fn [supported]
                 (swap! xr-state assoc :ar-supported supported :ar-button btn)
                 (if supported
                   (do
                     (set! (.-disabled btn) false)
                     (set! (.-title btn) "Enter passthrough mode"))
                   (do
                     (set! (.-textContent btn) "AR N/A")
                     (set! (.-title btn) "WebXR AR not available"))))))
    btn))

(defn xr-presenting?
  "Check if currently in XR presentation mode."
  [^js renderer]
  (and renderer (.-isPresenting ^js (.-xr renderer))))

;; ============================================================
;; VR Controller Support
;; ============================================================

(defn- create-controller-ray
  "Create a visible ray for the controller."
  []
  (let [group (THREE/Group.)
        points #js [(THREE/Vector3. 0 0 0)
                    (THREE/Vector3. 0 0 -3)]
        geometry (.setFromPoints (THREE/BufferGeometry.) points)
        material (THREE/LineBasicMaterial. #js {:color 0x00ffff
                                                 :linewidth 2
                                                 :depthTest false
                                                 :depthWrite false})
        line (THREE/Line. geometry material)
        tip-geo (THREE/SphereGeometry. 0.01 8 8)
        tip-mat (THREE/MeshBasicMaterial. #js {:color 0x00ffff :depthTest false})
        tip (THREE/Mesh. tip-geo tip-mat)
        end-geo (THREE/SphereGeometry. 0.015 8 8)
        end-mat (THREE/MeshBasicMaterial. #js {:color 0xff00ff :depthTest false})
        end-sphere (THREE/Mesh. end-geo end-mat)]
    (.set (.-position end-sphere) 0 0 -3)
    (set! (.-frustumCulled line) false)
    (set! (.-frustumCulled tip) false)
    (set! (.-frustumCulled end-sphere) false)
    (set! (.-frustumCulled group) false)
    (set! (.-renderOrder line) 999)
    (set! (.-renderOrder tip) 999)
    (set! (.-renderOrder end-sphere) 999)
    (.add group line)
    (.add group tip)
    (.add group end-sphere)
    group))

(defn- create-mode-indicator
  "Create a small sphere to indicate current mode."
  []
  (let [geometry (THREE/SphereGeometry. 0.02 8 8)
        material (THREE/MeshBasicMaterial. #js {:color 0x00ff00
                                                 :depthTest false
                                                 :depthWrite false})
        mesh (THREE/Mesh. geometry material)]
    (set! (.-frustumCulled mesh) false)
    (set! (.-renderOrder mesh) 999)
    mesh))

(defn- create-debug-text-sprite
  "Create a text sprite for debug info."
  [text]
  (let [canvas (js/document.createElement "canvas")
        ctx (.getContext canvas "2d")
        width 256
        height 64]
    (set! (.-width canvas) width)
    (set! (.-height canvas) height)
    (set! (.-fillStyle ctx) "rgba(0,0,0,0.7)")
    (.fillRect ctx 0 0 width height)
    (set! (.-fillStyle ctx) "#00ff00")
    (set! (.-font ctx) "bold 20px monospace")
    (set! (.-textAlign ctx) "left")
    (set! (.-textBaseline ctx) "top")
    (.fillText ctx text 5 5)
    (let [texture (THREE/CanvasTexture. canvas)
          material (THREE/SpriteMaterial. #js {:map texture
                                                :depthTest false
                                                :depthWrite false})
          sprite (THREE/Sprite. material)]
      (set! (.-frustumCulled sprite) false)
      (set! (.-renderOrder sprite) 1000)
      (.set (.-scale sprite) 0.15 0.04 1)
      sprite)))

(defn- update-debug-sprite
  "Update debug sprite text."
  [sprite text]
  (let [canvas (js/document.createElement "canvas")
        ctx (.getContext canvas "2d")
        width 256
        height 64]
    (set! (.-width canvas) width)
    (set! (.-height canvas) height)
    (set! (.-fillStyle ctx) "rgba(0,0,0,0.7)")
    (.fillRect ctx 0 0 width height)
    (set! (.-fillStyle ctx) "#00ff00")
    (set! (.-font ctx) "bold 20px monospace")
    (set! (.-textAlign ctx) "left")
    (set! (.-textBaseline ctx) "top")
    (.fillText ctx text 5 5)
    (let [texture (THREE/CanvasTexture. canvas)]
      (set! (.-map (.-material sprite)) texture)
      (set! (.-needsUpdate (.-map (.-material sprite))) true))))

;; ============================================================
;; Ray-based drag helpers
;; ============================================================

(defn- get-ray-point-at-distance
  "Get the point along the controller ray at a given distance."
  [^js controller-group distance]
  (let [ray-origin (THREE/Vector3.)
        ray-dir (THREE/Vector3. 0 0 -1)]
    (.getWorldPosition controller-group ray-origin)
    (.identity temp-matrix)
    (.extractRotation temp-matrix (.-matrixWorld controller-group))
    (.applyMatrix4 ray-dir temp-matrix)
    (let [point (THREE/Vector3.)]
      (.copy point ray-origin)
      (.addScaledVector point ray-dir distance)
      point)))

;; Default drag distance in meters (larger = more movement for same wrist angle)
(def ^:private default-drag-distance 10.0)

;; ============================================================
;; VR Control Panel
;; ============================================================

(defn- create-text-texture
  "Create a canvas texture with text for a button. Set bg-alpha to 0 for transparent."
  [text width height bg-color text-color & [bg-alpha]]
  (let [canvas (js/document.createElement "canvas")
        ctx (.getContext canvas "2d")
        alpha (or bg-alpha 0.8)]
    (set! (.-width canvas) width)
    (set! (.-height canvas) height)
    ;; Background with configurable alpha
    (set! (.-fillStyle ctx) (str "rgba(50, 50, 50, " alpha ")"))
    (.fillRect ctx 0 0 width height)
    (when (> alpha 0)
      (set! (.-strokeStyle ctx) "#ffffff")
      (set! (.-lineWidth ctx) 2)
      (.strokeRect ctx 2 2 (- width 4) (- height 4)))
    (set! (.-fillStyle ctx) text-color)
    (set! (.-font ctx) "bold 24px Arial")
    (set! (.-textAlign ctx) "center")
    (set! (.-textBaseline ctx) "middle")
    (.fillText ctx text (/ width 2) (/ height 2))
    (THREE/CanvasTexture. canvas)))

(defn- create-panel-button
  "Create a 3D button mesh for the control panel."
  [text width height action-id]
  (let [geometry (THREE/PlaneGeometry. width height)
        ;; Create with visible background (will be hidden when panel is hidden)
        texture (create-text-texture text 128 64 "#333333" "#ffffff" 0.8)
        material (THREE/MeshBasicMaterial. #js {:map texture :transparent true})
        mesh (THREE/Mesh. geometry material)]
    (set! (.-userData mesh) #js {:actionId action-id :isButton true :text text})
    mesh))

(defn- create-control-panel
  "Create a floating control panel with buttons (no background)."
  [scene]
  (let [panel (THREE/Group.)
        ;; Row 1: VR/AR mode switch
        btn-vr (create-panel-button "VR" 0.10 0.05 :switch-vr)
        btn-ar (create-panel-button "AR" 0.10 0.05 :switch-ar)
        ;; Row 2: View controls
        btn-grid (create-panel-button "Grid" 0.10 0.05 :toggle-grid)
        btn-axes (create-panel-button "Axes" 0.10 0.05 :toggle-axes)
        ;; Row 3: All/Obj and Reset
        btn-all-obj (create-panel-button "All" 0.10 0.05 :toggle-all-obj)
        btn-reset (create-panel-button "Reset" 0.10 0.05 :reset-view)
        ;; Row 4: Exit
        btn-exit (create-panel-button "Exit" 0.10 0.05 :exit)
        buttons [btn-vr btn-ar btn-grid btn-axes btn-all-obj btn-reset btn-exit]]
    ;; Row 1 (top): VR / AR
    (.set (.-position btn-vr) -0.06 0.10 0.01)
    (.set (.-position btn-ar) 0.06 0.10 0.01)
    ;; Row 2: Grid / Axes
    (.set (.-position btn-grid) -0.06 0.04 0.01)
    (.set (.-position btn-axes) 0.06 0.04 0.01)
    ;; Row 3: All-Obj / Reset
    (.set (.-position btn-all-obj) -0.06 -0.02 0.01)
    (.set (.-position btn-reset) 0.06 -0.02 0.01)
    ;; Row 4 (bottom): Exit
    (.set (.-position btn-exit) 0 -0.08 0.01)
    (set! (.-frustumCulled panel) false)
    (doseq [btn buttons]
      (set! (.-frustumCulled btn) false))
    (doseq [btn buttons]
      (.add panel btn))
    (.add scene panel)
    (set! (.-visible panel) false)
    {:panel panel :buttons buttons}))

(defn- position-panel-in-front-of-controller
  "Position the control panel in front of the controller."
  []
  (when-let [{:keys [control-panel controller-group camera]} @xr-state]
    (when (and control-panel controller-group)
      (let [^js ctrl-grp controller-group
            ^js ctrl-pos (.-position ctrl-grp)
            ^js panel control-panel
            ^js cam camera
            cam-pos (THREE/Vector3.)]
        (.getWorldPosition cam cam-pos)
        ;; Position panel in front and below controller (arm's length away)
        (.set (.-position panel)
              (.-x ctrl-pos)
              (- (.-y ctrl-pos) 0.1)
              (- (.-z ctrl-pos) 0.4))
        ;; Make panel face the camera
        (.lookAt panel cam-pos)))))

(defn- toggle-panel-visibility
  "Toggle control panel visibility."
  []
  (when-let [{:keys [control-panel panel-visible]} @xr-state]
    (when control-panel
      (let [new-visible (not panel-visible)]
        (swap! xr-state assoc :panel-visible new-visible)
        (set! (.-visible control-panel) new-visible)
        (when new-visible
          (position-panel-in-front-of-controller))))))

(defn- switch-to-vr
  "Switch from AR to VR mode."
  []
  (let [{:keys [session renderer]} @xr-state]
    (when session
      (js/console.log "Switching to VR...")
      ;; Exit current session, then enter VR
      (.end ^js session)
      (js/setTimeout #(enter-vr renderer) 500))))

(defn- switch-to-ar
  "Switch from VR to AR mode."
  []
  (let [{:keys [session renderer]} @xr-state]
    (when session
      (js/console.log "Switching to AR...")
      ;; Exit current session, then enter AR
      (.end ^js session)
      (js/setTimeout #(enter-ar renderer) 500))))

(defn- find-object-by-name
  "Find a child object by name in the world-group."
  [name]
  (when-let [{:keys [world-group]} @xr-state]
    (.getObjectByName world-group name)))

(defn- toggle-grid-vr
  "Toggle grid visibility in VR."
  []
  (when-let [grid (find-object-by-name "grid")]
    (swap! xr-state update :grid-visible not)
    (set! (.-visible grid) (:grid-visible @xr-state))))

(defn- toggle-axes-vr
  "Toggle axes visibility in VR."
  []
  (when-let [axes (find-object-by-name "axes")]
    (swap! xr-state update :axes-visible not)
    (set! (.-visible axes) (:axes-visible @xr-state))))

(defn- toggle-all-obj-vr
  "Toggle between All and Objects-only view in VR."
  []
  (swap! xr-state update :show-all-view not)
  (when-let [callback (:toggle-all-obj @action-callbacks)]
    (callback (:show-all-view @xr-state))))

(defn- reset-view-vr
  "Reset camera/world position in VR."
  []
  (when-let [{:keys [world-group camera-rig]} @xr-state]
    ;; Reset world-group position and rotation
    (.set (.-position world-group) 0 0 0)
    (.set (.-rotation world-group) 0 0 0)
    (.set (.-scale world-group) 1 1 1)
    ;; Reset camera rig position
    (when camera-rig
      (.set (.-position camera-rig) 0 1.6 2))))

(defn- handle-button-click
  "Handle VR button click action."
  [action-id]
  (case action-id
    :move (do
            (swap! xr-state assoc :mode :move)
            (js/console.log "Mode: move"))
    :rotate (do
              (swap! xr-state assoc :mode :rotate)
              (js/console.log "Mode: rotate"))
    :toggle-grid (toggle-grid-vr)
    :toggle-axes (toggle-axes-vr)
    :toggle-all-obj (toggle-all-obj-vr)
    :reset-view (reset-view-vr)
    :switch-vr (switch-to-vr)
    :switch-ar (switch-to-ar)
    :exit (exit-vr)
    nil))

(defn- set-button-hover
  "Set button visual hover state."
  [btn hovered?]
  (when-let [material (.-material btn)]
    (let [btn-text (.-text (.-userData btn))
          texture (if hovered?
                    (create-text-texture btn-text 128 64 "#555555" "#00ffff" 0.9)
                    (create-text-texture btn-text 128 64 "#333333" "#ffffff" 0.8))]
      (set! (.-map material) texture)
      (set! (.-needsUpdate material) true))))

(defn- get-hovered-button
  "Get button intersected by controller ray, or nil."
  [buttons]
  (when-let [{:keys [controller-group]} @xr-state]
    (when controller-group
      (let [^js ctrl-grp controller-group]
        (.identity temp-matrix)
        (.extractRotation temp-matrix (.-matrixWorld ctrl-grp))
        (.setFromMatrixPosition (.-origin (.-ray raycaster)) (.-matrixWorld ctrl-grp))
        (.set (.-direction (.-ray raycaster)) 0 0 -1)
        (.applyMatrix4 (.-direction (.-ray raycaster)) temp-matrix)
        (let [intersects (.intersectObjects raycaster (clj->js buttons) false)]
          (when (> (.-length intersects) 0)
            (let [^js hit (aget intersects 0)
                  ^js obj (.-object hit)
                  ^js user-data (.-userData obj)]
              (when (.-isButton user-data)
                obj))))))))

(defn- update-button-hover
  "Update button hover visual feedback."
  [buttons]
  (let [hovered-btn (get-hovered-button buttons)
        prev-hovered (:hovered-button @xr-state)]
    ;; Clear previous hover
    (when (and prev-hovered (not= prev-hovered hovered-btn))
      (set-button-hover prev-hovered false))
    ;; Set new hover
    (when (and hovered-btn (not= hovered-btn prev-hovered))
      (set-button-hover hovered-btn true))
    (swap! xr-state assoc :hovered-button hovered-btn)))

(defn- check-button-intersection
  "Check if controller ray intersects any button and handle click."
  [buttons]
  (when-let [^js btn (get-hovered-button buttons)]
    (let [^js user-data (.-userData btn)
          action-id (.-actionId user-data)
          ;; actionId is stored as keyword, convert properly
          action-kw (if (keyword? action-id)
                      action-id
                      (keyword (str/replace (str action-id) #"^:" "")))]
      ;; Show action in debug sprite
      (when-let [sprite (:debug-sprite @xr-state)]
        (update-debug-sprite sprite (str "Click:" (name action-kw))))
      (handle-button-click action-kw)
      true)))

(defn setup-controller
  "Setup VR controller with input handling."
  [^js renderer scene camera-rig camera world-group]
  (let [^js xr (.-xr renderer)
        controller-0 (.getController xr 0)
        controller-1 (.getController xr 1)
        controller-group (THREE/Group.)
        ray (create-controller-ray)
        indicator (create-mode-indicator)
        debug-sprite (create-debug-text-sprite "Waiting...")
        {:keys [panel buttons]} (create-control-panel scene)]
    (.set (.-position ray) 0 0 0)
    (.set (.-position indicator) 0 0.02 -0.05)
    (.set (.-position debug-sprite) 0 0.08 -0.05)
    (.add controller-group ray)
    (.add controller-group indicator)
    (.add controller-group debug-sprite)
    ;; Hide controller group initially - only show in XR mode
    (set! (.-visible controller-group) false)
    (.add scene controller-group)
    (swap! xr-state assoc
           :controller nil
           :controller-0 controller-0
           :controller-1 controller-1
           :controller-group controller-group
           :ray ray
           :indicator indicator
           :debug-sprite debug-sprite
           :camera-rig camera-rig
           :camera camera
           :scene scene
           :world-group world-group
           :control-panel panel
           :panel-buttons buttons
           :renderer renderer)
    (doseq [[ctrl idx] [[controller-0 0] [controller-1 1]]]
      (.addEventListener ctrl "connected"
        (fn [^js event]
          (let [^js input-source (.-data event)
                handedness (.-handedness input-source)]
            (js/console.log "Controller" idx "connected, hand:" handedness)
            (when (or (nil? (:controller @xr-state))
                      (= handedness "right"))
              (swap! xr-state assoc
                     :controller ctrl
                     :input-source input-source)
              (update-debug-sprite (:debug-sprite @xr-state) (str "Hand:" handedness))))))
      (.addEventListener ctrl "disconnected"
        (fn [_]
          (js/console.log "Controller" idx "disconnected")
          (when (= ctrl (:controller @xr-state))
            (swap! xr-state assoc :controller nil :input-source nil)))))
    (.add scene controller-0)
    (.add scene controller-1)
    (doseq [ctrl [controller-0 controller-1]]
      (.addEventListener ctrl "squeezestart"
        (fn [_] (swap! xr-state assoc :grip-held true)))
      (.addEventListener ctrl "squeezeend"
        (fn [_] (swap! xr-state assoc :grip-held false)))
      (.addEventListener ctrl "selectstart"
        (fn [_]
          (let [active-ctrl (:controller @xr-state)
                panel-btns (:panel-buttons @xr-state)
                panel-visible (:panel-visible @xr-state)]
            (when (= ctrl active-ctrl)
              ;; First check if panel is visible and we clicked a button
              (let [button-clicked (and panel-visible (check-button-intersection panel-btns))]
                (when-not button-clicked
                  ;; Mark drag as pending - actual initialization happens in update-controller
                  ;; after pose has been updated from XRFrame
                  (when-let [sprite (:debug-sprite @xr-state)]
                    (update-debug-sprite sprite "DRAG-PENDING"))
                  (swap! xr-state assoc
                         :trigger-held true
                         :drag-pending true)))))))
      (.addEventListener ctrl "selectend"
        (fn [_]
          (let [active-ctrl (:controller @xr-state)]
            (when (= ctrl active-ctrl)
              (swap! xr-state assoc
                     :trigger-held false
                     :drag-pending false
                     :drag-start-ray-point nil
                     :drag-world-start nil
                     :drag-distance nil))))))
    controller-0))

(defn update-controller
  "Update controller input each frame. Call from render loop when in VR."
  [^js xr-frame ^js renderer] 
  
  (when-let [{:keys [controller-group debug-sprite camera-rig camera mode world-group grip-held a-button-was-pressed b-button-was-pressed input-source]} @xr-state]
                                 (when (and controller-group camera-rig camera world-group xr-frame renderer input-source)
                                   ;; Cast Three.js objects to preserve property names in advanced compilation
                                   (let [^js ctrl-grp controller-group
                                         ^js world-grp world-group
                                         ^js xr (.-xr renderer)
                                         ^js ref-space (.getReferenceSpace xr)
                                         ^js target-space (when input-source (.-targetRaySpace ^js input-source))
                                         ^js pose (when (and ref-space target-space)
                                                    (.getPose xr-frame target-space ref-space))]
                                     ;; Update controller-group position from XRFrame pose
                                     (when pose
                                       (let [^js transform (.-transform pose)
                                             ^js pos (.-position transform)
                                             ^js ori (.-orientation transform)]
                                         (.set (.-position ctrl-grp) (.-x pos) (.-y pos) (.-z pos))
                                         (.set (.-quaternion ctrl-grp) (.-x ori) (.-y ori) (.-z ori) (.-w ori))
                                         (.updateMatrixWorld ctrl-grp true)))
                                     ;; Update button hover feedback when panel is visible
                                     (when (:panel-visible @xr-state)
                                       (update-button-hover (:panel-buttons @xr-state)))
                                     ;; Handle ray-based drag-to-move when trigger is held
                                     (let [{:keys [trigger-held drag-pending drag-start-ray-point drag-world-start drag-distance]} @xr-state]
                                       ;; Initialize drag on first frame after trigger press (pose is now updated)
                                       (when (and trigger-held drag-pending)
                                         (let [ray-point (get-ray-point-at-distance ctrl-grp default-drag-distance)
                                               ^js world-pos (.-position world-grp)]
                                           (when-let [sprite (:debug-sprite @xr-state)]
                                             (update-debug-sprite sprite "DRAG-START"))
                                           (swap! xr-state assoc
                                                  :drag-pending false
                                                  :drag-start-ray-point ray-point
                                                  :drag-world-start (.clone world-pos)
                                                  :drag-distance default-drag-distance)))
                                       ;; Apply drag movement
                                       (when (and trigger-held (not drag-pending) drag-start-ray-point drag-world-start drag-distance)
                                         ;; Get current ray point at the same distance as when drag started
                                         (let [^js current-ray-point (get-ray-point-at-distance ctrl-grp drag-distance)
                                               ;; Calculate how much the ray point moved
                                               ^js start-ray-point drag-start-ray-point
                                               delta-x (- (.-x current-ray-point) (.-x start-ray-point))
                                               delta-y (- (.-y current-ray-point) (.-y start-ray-point))
                                               delta-z (- (.-z current-ray-point) (.-z start-ray-point))
                                               ;; Move world WITH ray movement - when you point right, world moves right
                                               ;; This makes the scene follow where you're pointing
                                               ^js world-pos (.-position world-grp)
                                               ^js drag-start drag-world-start]
                                           (set! (.-x world-pos) (+ (.-x drag-start) delta-x))
                                           (set! (.-y world-pos) (+ (.-y drag-start) delta-y))
                                           (set! (.-z world-pos) (+ (.-z drag-start) delta-z)))))
                                     ;; Process gamepad input
                                     (when-let [^js gamepad (.-gamepad ^js input-source)]
                                       (swap! debug-counter inc)
                                       (let [^js buttons (.-buttons gamepad)
                                             n-buttons (when buttons (.-length buttons))
                                             ^js axes (.-axes gamepad)]
                                         ;; Update debug sprite (every 10 frames) - show DRAG when trigger held
                                         (when (and debug-sprite (zero? (mod @debug-counter 10)))
                                           (let [{:keys [trigger-held]} @xr-state
                                                 x-val (if (>= (.-length axes) 4) (aget axes 2) 0)
                                                 y-val (if (>= (.-length axes) 4) (aget axes 3) 0)
                                                 ^js world-pos (.-position world-grp)]
                                             (if trigger-held
                                               (update-debug-sprite debug-sprite
                                                                    (str "DRAG W:" (.toFixed (.-x world-pos) 0) "," (.toFixed (.-z world-pos) 0)))
                                               (update-debug-sprite debug-sprite
                                                                    (str (name mode) " "
                                                                         (.toFixed x-val 1) "," (.toFixed y-val 1)
                                                                         " W:" (.toFixed (.-x world-pos) 0))))))
                                         ;; A button = toggle panel, B button = switch move/rotate mode
                                         (when (and buttons (> n-buttons 5))
                                           (let [^js a-button (aget buttons 4)
                                                 ^js b-button (aget buttons 5)
                                                 a-pressed (and a-button (.-pressed a-button))
                                                 b-pressed (and b-button (.-pressed b-button))]
                                             ;; A button - toggle panel
                                             (when (and a-pressed (not a-button-was-pressed))
                                               (swap! xr-state assoc :a-button-was-pressed true)
                                               (toggle-panel-visibility))
                                             (when (and (not a-pressed) a-button-was-pressed)
                                               (swap! xr-state assoc :a-button-was-pressed false))
                                             ;; B button - switch move/rotate mode
                                             (when (and b-pressed (not b-button-was-pressed))
                                               (swap! xr-state assoc :b-button-was-pressed true)
                                               (let [new-mode (if (= mode :move) :rotate :move)]
                                                 (swap! xr-state assoc :mode new-mode)
                                                 (when-let [indicator (:indicator @xr-state)]
                                                   (let [color (if (= new-mode :move) 0x00ff00 0x6666ff)]
                                                     (set! (.-color (.-material indicator)) (THREE/Color. color))))))
                                             (when (and (not b-pressed) b-button-was-pressed)
                                               (swap! xr-state assoc :b-button-was-pressed false))))
                                         ;; Thumbstick axes
                                         (when (and axes (>= (.-length axes) 2))
                                           (let [raw-x (if (>= (.-length axes) 4) (aget axes 2) (aget axes 0))
                                                 raw-y (if (>= (.-length axes) 4) (aget axes 3) (aget axes 1))
                                                 x (if (< (js/Math.abs raw-x) deadzone) 0 raw-x)
                                                 y (if (< (js/Math.abs raw-y) deadzone) 0 raw-y)]
                                             (when (or (not= x 0) (not= y 0))
                                               (if (= mode :move)
                                                 ;; Move mode - move world in opposite direction (so it looks like we move)
                                                 ;; Default: X/Z movement (horizontal plane), Grip + Y = Y movement (vertical)
                                                 (let [world-pos (.-position world-grp)]
                                                   (set! (.-x world-pos) (- (.-x world-pos) (* x move-speed)))
                                                   (if grip-held
                                                     (set! (.-y world-pos) (+ (.-y world-pos) (* y move-speed)))
                                                     (set! (.-z world-pos) (- (.-z world-pos) (* y move-speed)))))
                                                 ;; Rotate mode
                                                 (do
                                                   (when (not= x 0)
                                                     (.rotateOnWorldAxis world-grp (THREE/Vector3. 0 0 1) (* (- x) rotate-speed)))
                                                   (when (not= y 0)
                                                     (if grip-held
                                                       (.rotateOnWorldAxis world-grp (THREE/Vector3. 0 1 0) (* (- y) rotate-speed))
                                                       (.rotateOnWorldAxis world-grp (THREE/Vector3. 1 0 0) (* y rotate-speed)))))))))))))))

(defn update-mode-indicator
  "Update the mode indicator color based on current mode."
  []
  (when-let [{:keys [indicator mode]} @xr-state]
    (when indicator
      (let [color (if (= mode :move) 0x00ff00 0x6666ff)]
        (set! (.-color (.-material indicator)) (THREE/Color. color))))))

(defn get-camera-rig
  "Get the camera rig group for VR positioning."
  []
  (:camera-rig @xr-state))
