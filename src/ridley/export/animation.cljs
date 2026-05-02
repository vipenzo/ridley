(ns ridley.export.animation
  "Off-realtime frame-by-frame capture from procedural (anim-proc!) animations.

   The Three.js render loop is suspended for the duration of the capture; we
   then step through t ∈ [0,1] in N = round(fps × duration) increments, call
   the animation's gen-fn for each step, apply the resulting mesh, and force a
   synchronous render. The encoder receives each frame's canvas via on-frame
   and can copy/encode it as it sees fit. Encoder-agnostic by design.

   Rendering happens at user-requested width and the viewport's current
   aspect ratio. The renderer's pixel ratio is forced to 1 during capture so
   the drawing buffer matches the requested width exactly."
  (:require [clojure.string :as str]
            [ridley.anim.core :as anim]
            [ridley.anim.playback :as playback]
            [ridley.viewport.core :as viewport]))

(defn- pick-procedural-anim
  "Resolve the animation to capture. If anim-name is nil, auto-pick when
   exactly one procedural animation is registered. Throws otherwise."
  [anim-name]
  (let [reg @anim/anim-registry]
    (if anim-name
      (let [a (get reg anim-name)]
        (cond
          (nil? a)
          (throw (js/Error. (str "anim-export-gif: no animation registered as " anim-name)))
          (not= :procedural (:type a))
          (throw (js/Error. (str "anim-export-gif: " anim-name " is not a procedural"
                                 " animation (only anim-proc! is supported)")))
          :else [anim-name a]))
      (let [procs (filterv (fn [[_ a]] (= :procedural (:type a))) reg)]
        (case (count procs)
          0 (throw (js/Error. (str "anim-export-gif: no procedural animation registered."
                                   " Define one with anim-proc!")))
          1 (first procs)
          (throw (js/Error. (str "anim-export-gif: multiple procedural animations ("
                                 (str/join ", " (map first procs))
                                 "); pass :anim <name> to disambiguate"))))))))

(defn- save-render-state
  "Snapshot dimensions and pixel ratio so we can restore after capture."
  [{:keys [^js renderer ^js camera ^js canvas]}]
  (let [parent (.-parentElement canvas)]
    {:width (.-clientWidth parent)
     :height (.-clientHeight parent)
     :pixel-ratio (.getPixelRatio renderer)
     :aspect (.-aspect camera)}))

(defn- apply-capture-size!
  "Force renderer to the requested pixel size with pixelRatio=1 so the
   drawing buffer is exactly width×height regardless of HiDPI."
  [{:keys [^js renderer ^js camera]} width height]
  (.setPixelRatio renderer 1)
  (.setSize renderer width height false)
  (set! (.-aspect camera) (/ width height))
  (.updateProjectionMatrix camera))

(defn- restore-render-state!
  [{:keys [^js renderer ^js camera]} {:keys [width height pixel-ratio aspect]}]
  (.setPixelRatio renderer pixel-ratio)
  (.setSize renderer width height false)
  (set! (.-aspect camera) aspect)
  (.updateProjectionMatrix camera))

(defn- save-anim-state [anim-name]
  (let [a (get @anim/anim-registry anim-name)]
    {:state (:state a)
     :current-time (:current-time a)
     :current-span-idx (:current-span-idx a)}))

(defn- restore-anim-state! [anim-name saved]
  ;; Re-apply the mesh at the saved t (capture left it at t=1) and write
  ;; the timing fields back into the registry.
  (let [a (get @anim/anim-registry anim-name)
        dur (:duration a)
        t (if (and dur (pos? dur)) (/ (:current-time saved) dur) 0)]
    (playback/seek-and-apply! anim-name t)
    (swap! anim/anim-registry update anim-name merge saved)))

(defn- capture-loop
  "Drive the per-frame capture using rAF between frames so the UI thread
   can repaint progress overlays. Resolves on completion or rejects on error."
  [{:keys [^js renderer ^js scene ^js camera ^js canvas]}
   anim-name total-frames on-frame on-progress]
  (js/Promise.
   (fn [resolve reject]
     (let [i (atom 0)]
       (letfn [(step []
                 (try
                   (if (< @i total-frames)
                     (let [frac (if (<= total-frames 1)
                                  0.0
                                  (/ @i (- total-frames 1)))]
                       (playback/seek-and-apply! anim-name frac)
                       (.render renderer scene camera)
                       (on-frame canvas @i total-frames)
                       (when on-progress (on-progress :capture @i total-frames))
                       (swap! i inc)
                       (js/requestAnimationFrame step))
                     (resolve true))
                   (catch :default e
                     (reject e))))]
         (js/requestAnimationFrame step))))))

(defn capture-frames!
  "Capture frames off-realtime from a procedural animation.

   Options:
     :anim-name   — keyword (optional). When omitted, auto-picks the only
                    registered procedural animation, or throws.
     :duration    — seconds (default = animation's own duration)
     :fps         — frames per second (default 15)
     :width       — output width in px (default 720). Height is derived
                    from the current viewport's aspect ratio.
     :on-frame    — (fn [canvas i total]). Required. Called synchronously
                    per frame with the live capture canvas.
     :on-progress — (fn [stage i total]) optional progress callback

   Returns Promise<{:width :height :total-frames :fps :anim-name}>.

   The render loop is suspended for the duration of the capture and the
   animation's playback state is restored at the end (whether the capture
   succeeded or threw)."
  [{:keys [anim-name duration fps width on-frame on-progress]
    :or {fps 15 width 720}}]
  (when-not on-frame
    (throw (js/Error. "capture-frames!: :on-frame callback is required")))
  (let [[anim-name anim-data] (pick-procedural-anim anim-name)
        duration (or duration (:duration anim-data))
        ctx (or (viewport/get-capture-context)
                (throw (js/Error. "anim-export-gif: viewport not initialized")))
        ^js canvas (:canvas ctx)
        parent (.-parentElement canvas)
        viewport-w (.-clientWidth parent)
        viewport-h (.-clientHeight parent)
        aspect (if (and (pos? viewport-w) (pos? viewport-h))
                 (/ viewport-w viewport-h)
                 (/ 16.0 9.0))
        height (max 1 (Math/round (/ width aspect)))
        total-frames (max 1 (Math/round (* fps duration)))
        saved-render (save-render-state ctx)
        saved-anim (save-anim-state anim-name)
        peak-bytes (* width height total-frames 4)
        cleanup! (fn []
                   (restore-render-state! ctx saved-render)
                   (restore-anim-state! anim-name saved-anim)
                   (viewport/resume-render-loop!))]
    (when (> peak-bytes (* 500 1024 1024))
      (js/console.warn (str "anim-export-gif: estimated "
                            (Math/round (/ peak-bytes 1024.0 1024.0))
                            "MB raw frame data — lower fps, duration, or width"
                            " if you run out of memory")))
    (viewport/pause-render-loop!)
    (apply-capture-size! ctx width height)
    (-> (capture-loop ctx anim-name total-frames on-frame on-progress)
        (.then (fn [_]
                 (cleanup!)
                 #js {:width width :height height
                      :total-frames total-frames :fps fps
                      :anim-name (name anim-name)}))
        (.catch (fn [err]
                  (cleanup!)
                  (throw err))))))
