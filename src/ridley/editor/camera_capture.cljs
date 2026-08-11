(ns ridley.editor.camera-capture
  "A live camera as a source of views — the thin, boring half of 'scatta e
   registra'. Open a video device, show what it sees, and hand back a still frame
   as pixels. Everything that makes a frame MEAN something (identifying the plate,
   measuring the lens, solving the pose, deciding whether it earns a place in the
   session) belongs to edit_acquire and photogrammetry; nothing of it is here.

   Three constraints, established before writing any of this:

   - **The phone arrives as an ordinary camera.** On recent macOS an iPhone offers
     itself to the system through Continuity Camera, so `enumerateDevices` lists it
     next to the built-in one and `getUserMedia` opens it. No app, no network, no
     pairing step of our own — which is why this is a `mediaDevices` wrapper and
     not a protocol.
   - **A secure context is required, and we have one.** `getUserMedia` refuses on a
     plain-http origin; `localhost:9000` counts as secure, and so does the desktop
     app's own origin. What is NOT available is the same page served to a phone
     over the LAN at `http://<ip>:9000` — that would need a certificate, and it is
     the reason the phone-as-client route was set aside.
   - **Labels are empty until permission is granted.** `enumerateDevices` will list
     the devices of a browser that has never been granted the camera, but with
     blank labels — useless for a picker. So the order is: open a stream first (any
     device), THEN enumerate to build the list with real names.

   Resolution is asked for, not assumed: a measurement's precision scales with its
   pixels, so the constraints request 4K and take whatever the device actually
   gives (`:width`/`:height` come back from the track, never from the request).")

(defonce ^:private cam
  ;; {:stream MediaStream :video <video> :device-id str :label str :size [w h]}
  (atom nil))

(defn supported?
  "True when this browser exposes a camera API at all. False in a plain-http page
   served over the LAN and in any context without mediaDevices — the Grab control
   must be able to say 'not here' rather than fail on click."
  []
  (boolean (and (exists? js/navigator)
                (.-mediaDevices js/navigator)
                (.-getUserMedia (.-mediaDevices js/navigator)))))

(defn active? [] (some? @cam))

(defn current
  "{:device-id :label :size [w h]} of the open camera, or nil."
  []
  (when-let [c @cam] (select-keys c [:device-id :label :size])))

(defn ^js video-el
  "The <video> element the stream plays into — the caller positions it. nil when
   no camera is open."
  []
  (:video @cam))

(defn- make-video []
  (let [^js v (.createElement js/document "video")]
    (set! (.-autoplay v) true)
    (set! (.-muted v) true)
    ;; playsInline keeps a mobile webview from hijacking the video full-screen;
    ;; harmless on desktop
    (.setAttribute v "playsinline" "")
    (set! (.-id v) "eaq-cam-preview")
    v))

(defn list-cameras
  "Promise of [{:id :label} …] for the video inputs. Labels are only real once a
   stream has been granted at least once in this origin (see the ns docstring), so
   an entry with a blank label is reported as 'Camera N' rather than as nothing."
  []
  (if-not (supported?)
    (js/Promise.resolve [])
    (-> (.enumerateDevices (.-mediaDevices js/navigator))
        (.then (fn [^js devices]
                 (->> (array-seq devices)
                      (filter (fn [^js d] (= "videoinput" (.-kind d))))
                      (map-indexed (fn [i ^js d]
                                     {:id (.-deviceId d)
                                      :label (let [l (.-label d)]
                                               (if (and l (seq l)) l (str "Camera " (inc i))))}))
                      vec)))
        (.catch (fn [_] [])))))

(defn stop!
  "Close the stream and drop the element. Idempotent."
  []
  (when-let [{:keys [^js stream ^js video]} @cam]
    (doseq [^js t (array-seq (.getTracks stream))] (.stop t))
    (when (.-parentNode video) (.remove video))
    (set! (.-srcObject video) nil))
  (reset! cam nil))

(defn- track-size
  "[w h] the device is actually delivering. Read from the track's settings, with
   the video element's own dimensions as the fallback — the request is a wish, the
   track is the fact."
  [^js stream ^js video]
  (let [^js track (aget (.getVideoTracks stream) 0)
        s (when track (.getSettings track))
        w (or (some-> s .-width) (.-videoWidth video))
        h (or (some-> s .-height) (.-videoHeight video))]
    [w h]))

(defn start!
  "Open `device-id` (or the default camera when nil) and resolve to
   {:device-id :label :size [w h]}. Rejects with the browser's own error — a
   refused permission and an absent camera are different problems and the caller
   must be able to say which. Closes any camera already open first, so switching
   devices is just another start!."
  [device-id]
  (if-not (supported?)
    (js/Promise.reject (js/Error. "this build has no camera API (needs a secure context)"))
    (do
      (stop!)
      (let [^js video (make-video)
            constraints (clj->js
                         {:audio false
                          :video (cond-> {:width {:ideal 3840}
                                          :height {:ideal 2160}}
                                   device-id (assoc :deviceId {:exact device-id}))})]
        (-> (.getUserMedia (.-mediaDevices js/navigator) constraints)
            (.then (fn [^js stream]
                     (set! (.-srcObject video) stream)
                     ;; wait for the first frame's metadata: before it, videoWidth
                     ;; is 0 and a grab would draw nothing
                     (js/Promise.
                      (fn [resolve _reject]
                        (let [done (fn []
                                     (let [[w h] (track-size stream video)
                                           ^js track (aget (.getVideoTracks stream) 0)
                                           label (or (some-> track .-label) "camera")]
                                       (reset! cam {:stream stream :video video
                                                    :device-id device-id :label label
                                                    :size [w h]})
                                       (resolve {:device-id device-id :label label :size [w h]})))]
                          (if (pos? (.-videoWidth video))
                            (done)
                            (set! (.-onloadedmetadata video) (fn [_] (done))))))))))))))

(defn grab-frame
  "The frame on screen right now, as an offscreen canvas at the device's own pixel
   size. Returns {:canvas :size [w h]}, or nil when no camera is open or the first
   frame has not arrived. Drawing rather than ImageCapture.takePhoto: takePhoto is
   unevenly supported and can hand back a differently-framed still, and what the
   user aimed is what is on the preview."
  []
  (when-let [{:keys [^js video]} @cam]
    (let [w (.-videoWidth video)
          h (.-videoHeight video)]
      (when (and (pos? w) (pos? h))
        (let [^js canvas (.createElement js/document "canvas")]
          (set! (.-width canvas) w)
          (set! (.-height canvas) h)
          (.drawImage (.getContext canvas "2d" #js {:willReadFrequently true}) video 0 0 w h)
          {:canvas canvas :size [w h]})))))

(defn canvas->jpeg
  "Promise of a JPEG Blob for `canvas`. Quality is high on purpose: this frame is
   about to be MEASURED on, and compression noise lands on the disc edges the
   sub-pixel centroid is computed from."
  ([canvas] (canvas->jpeg canvas 0.95))
  ([^js canvas quality]
   (js/Promise.
    (fn [resolve reject]
      (.toBlob canvas
               (fn [blob] (if blob (resolve blob) (reject (js/Error. "the frame did not encode"))))
               "image/jpeg" quality)))))
