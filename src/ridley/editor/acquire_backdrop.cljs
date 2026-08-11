(ns ridley.editor.acquire-backdrop
  "Locked-camera photo backdrop for edit-acquire's gate prototype: a plane
   parented directly to the THREE camera (like viewport/core.cljs's own
   camera-following headlight — `(.add camera headlight)`), sized to exactly
   fill the frustum at a fixed depth. Being a camera child, it stays correctly
   framed as the camera is moved by viewport/set-camera-pose! with no per-frame
   re-position hook needed.

   Photos are read from disk via the Rust geo_server (ridley.export.stl's
   desktop-read-file-blob — the same helper set-image/edit-image-board use),
   not fetched over HTTP: edit-acquire's session-dir is a local folder, and
   this sidesteps any cross-origin canvas-taint risk (blob: URLs are
   same-origin) — the geo_server process must be running (e.g. `cargo tauri
   dev` in the background) even when the app itself is loaded in Chrome for
   REPL/hot-reload, since the two are independent local servers."
  (:require ["three" :as THREE]
            [ridley.photogrammetry.camera :as cam]
            [ridley.export.stl :as stl]))

(defonce ^:private bstate (atom nil)) ;; {:mesh :camera :depth :object-url :photo-aspect
                                       ;;  :image-width :image-height :pixels (Uint8ClampedArray)}

(def default-depth
  "World-unit distance from the camera the backdrop plane sits at. ~250
   matches the taped-block session's real photographic distance (NOTE.md),
   keeping the proxy's on-screen scale plausible against the photo."
  250)

(defn clear!
  "Remove the backdrop plane from the camera and dispose its GPU resources.
   Safe to call when nothing is set up."
  []
  (when-let [{:keys [^js mesh ^js camera object-url]} @bstate]
    (.remove camera mesh)
    (.dispose (.-geometry mesh))
    (when-let [^js map- (.-map (.-material mesh))] (.dispose map-))
    (.dispose (.-material mesh))
    (when object-url (js/URL.revokeObjectURL object-url)))
  (reset! bstate nil))

(defn set-visible!
  "Show/hide the backdrop plane without destroying it (keeps the loaded texture
   and cached pixels — no re-fetch on the way back). edit-acquire's P4b stage
   hides it for free-orbit (so the acquired object reads in the round) and shows
   it again when flying into a photo's pose. No-op when nothing is set up."
  [visible?]
  (when-let [{:keys [^js mesh]} @bstate]
    (set! (.-visible mesh) (boolean visible?))))

(defn ready?
  "True once the backdrop plane exists (create! has run). Lets a caller lazily
   create it before set-photo!/set-visible!, which are silent no-ops otherwise."
  []
  (some? (:mesh @bstate)))

(defn create!
  "Build (or replace) the backdrop plane as a child of `camera`, at local
   [0 0 (- depth)] (THREE cameras look down their own -Z axis, so this is
   directly ahead). No texture until set-photo!"
  ([camera] (create! camera default-depth))
  ([^js camera depth]
   (clear!)
   (let [geom (THREE/PlaneGeometry. 1 1)
         material (THREE/MeshBasicMaterial. #js {:depthWrite false :toneMapped false})
         mesh (THREE/Mesh. geom material)]
     (set! (.-renderOrder mesh) -1000) ; draw first, so nothing behind it can be mistakenly occluded
     (.set (.-position mesh) 0 0 (- depth))
     (.add camera mesh)
     (reset! bstate {:mesh mesh :camera camera :depth depth :object-url nil :photo-aspect nil}))))

(defn- resize-for! [^js mesh depth vfov-deg photo-aspect]
  (let [vfov-rad (* vfov-deg (/ Math/PI 180))
        height (* 2 depth (Math/tan (/ vfov-rad 2)))
        width (* height photo-aspect)]
    (.set (.-scale mesh) width height 1)))

(defn set-focal!
  "Recompute the vertical FOV and resize the backdrop plane for a
   35mm-equivalent focal length (mm), reusing the ALREADY-LOADED photo's
   aspect ratio (no re-fetch) — for live recalibration (a focal-length slider)
   once a photo is already showing, so the apparent size of the proxy against
   the backdrop can be tuned with position/rotation untouched (Vincenzo,
   2026-07-21: doing size and pose at once is confusing — separate them).
   No-op if no photo has loaded yet (photo-aspect unknown).

   Takes the focal length, not a horizontal FOV, precisely BECAUSE the
   horizontal FOV of a 35mm-equivalent focal depends on the photo's own aspect
   ratio (the equivalent focal is referred to the frame diagonal, not its
   width — cam/equiv-focal->hfov-deg): only this namespace knows that aspect
   for certain (the loaded image's pixels), so it is the right place to derive
   the angle. See set-photo!'s docstring for why the conversion uses the
   PHOTO's aspect ratio, not the canvas's."
  [focal-mm set-vfov!]
  (when-let [{:keys [mesh depth photo-aspect]} @bstate]
    (when photo-aspect
      (let [hfov-deg (cam/equiv-focal->hfov-deg focal-mm photo-aspect)
            hfov-rad (* hfov-deg (/ Math/PI 180))
            vfov-rad (* 2 (Math/atan (/ (Math/tan (/ hfov-rad 2)) photo-aspect)))
            vfov-deg (* vfov-rad (/ 180 Math/PI))]
        (set-vfov! vfov-deg)
        (resize-for! mesh depth vfov-deg photo-aspect)))))

(defn- cache-pixels!
  "Draw the already-loaded image onto an offscreen 2D canvas and cache its
   getImageData for luminance-at — the same blob: URL used for the THREE
   texture is same-origin, so this doesn't taint the canvas."
  [^js img iw ih]
  (let [canvas (.createElement js/document "canvas")]
    (set! (.-width canvas) iw)
    (set! (.-height canvas) ih)
    (let [ctx (.getContext canvas "2d" #js {:willReadFrequently true})]
      (.drawImage ctx img 0 0)
      (swap! bstate assoc
             :image-width iw :image-height ih
             ;; keep the canvas too (not just its pixels) so the loupe can
             ;; drawImage a magnified crop straight from it
             :src-canvas canvas
             :pixels (.-data (.getImageData ctx 0 0 iw ih))))))

(defn set-photo!
  "Load the photo at `file-path` (absolute, read via the Rust geo_server) as
   the backdrop texture. `focal-mm` is the session's 35mm-equivalent focal
   length (from EXIF or the manual slider). Once the image's own pixel
   dimensions are known, stores its aspect ratio and delegates the
   focal→FOV conversion + plane sizing to set-focal! (also the live-
   recalibration entry point, so the two never compute it differently). Also
   caches the raw pixel data (cache-pixels!) for luminance-at, the edge-snap's
   read of the real photo.
   Returns a Promise that resolves once the texture is applied."
  [file-path focal-mm set-vfov!]
  (when-let [{:keys [mesh]} @bstate]
    (-> (stl/desktop-read-file-blob file-path)
        (.then (fn [blob]
                 (let [url (js/URL.createObjectURL blob)
                       old-url (:object-url @bstate)]
                   (swap! bstate assoc :object-url url)
                   (-> (.loadAsync (THREE/TextureLoader.) url)
                       (.then (fn [^js tex]
                                (let [img (.-image tex)
                                      iw (.-width img)
                                      ih (.-height img)
                                      photo-aspect (if (pos? ih) (/ iw ih) 1)]
                                  (swap! bstate assoc :photo-aspect photo-aspect)
                                  (cache-pixels! img iw ih)
                                  (set-focal! focal-mm set-vfov!)
                                  (when-let [^js old-map (.-map (.-material mesh))]
                                    (.dispose old-map))
                                  (set! (.-map (.-material mesh)) tex)
                                  (set! (.-needsUpdate (.-material mesh)) true)
                                  (when old-url (js/URL.revokeObjectURL old-url)))))))))
        (.catch (fn [err]
                  (js/console.warn "edit-acquire: failed to load" file-path err))))))

(defn image-size
  "[width height] of the currently loaded photo in pixels, or nil before the
   first photo has loaded. Returns nil (not [nil nil]) when the plane exists but
   no photo has loaded yet — since create! (P4b stage) now builds the plane up
   front, the bstate map is present with image dims still nil, and a naive
   destructure would hand callers [nil nil]; that turned `(/ w h)` into NaN in
   frustum-preview-items (NaN frustum geometry → invisible frustums, Vincenzo
   2026-07-25). Guard on the dims, not just the map."
  []
  (when-let [{:keys [image-width image-height]} @bstate]
    (when (and image-width image-height)
      [image-width image-height])))

(defn pixel-under-pointer
  "Photo pixel [u v] (origin top-left, +v down — matching luminance-at and
   cam/project) under a pointer event, by raycasting the camera ray against the
   backdrop plane and reading the hit's UV. nil when the ray misses the plane
   or no photo has loaded. The plane is a child of `camera`, so its world matrix
   already carries the locked camera pose and the raycaster accounts for it for
   free — this is why a click maps to a stable photo pixel regardless of where
   the camera was orbited to."
  [^js event ^js camera ^js canvas]
  (when-let [{:keys [^js mesh image-width image-height]} @bstate]
    (when (and mesh image-width)
      (let [rect (.getBoundingClientRect canvas)
            nx (- (* (/ (- (.-clientX event) (.-left rect)) (.-width rect)) 2) 1)
            ny (- 1 (* (/ (- (.-clientY event) (.-top rect)) (.-height rect)) 2))
            raycaster (THREE/Raycaster.)]
        (.setFromCamera raycaster (THREE/Vector2. nx ny) camera)
        (let [hits (.intersectObject raycaster mesh false)]
          (when (pos? (.-length hits))
            (let [^js uv (.-uv (aget hits 0))]
              [(* (.-x uv) image-width)
               (* (- 1 (.-y uv)) image-height)])))))))

(defn screen-of-pixel
  "Client [x y] where photo pixel [u v] currently shows — the exact inverse of
   pixel-under-pointer, for positioning an HTML overlay dot on an auto-placed
   (blob-snapped) mark that had no click event to read clientX/Y from. Projects
   the corresponding point on the backdrop plane through the LIVE camera, so it
   is correct even when the photo's aspect differs from the canvas's (a plain
   u/iw→width map is only right when they match — otherwise the horizontal
   placement drifts toward centre) and it tracks the camera as the pose is
   refined. nil before a photo has loaded.

   The plane is a PlaneGeometry(1,1): uv (u/iw, 1-v/ih) sits at local
   (uv.x-0.5, uv.y-0.5, 0); localToWorld carries the locked camera pose, then
   camera.project gives NDC, then NDC → client via the canvas rect."
  [^js canvas ^js camera [u v]]
  (when-let [{:keys [^js mesh image-width image-height]} @bstate]
    (when (and mesh image-width camera)
      (let [p (THREE/Vector3. (- (/ u image-width) 0.5)
                              (- (- 1 (/ v image-height)) 0.5) 0)]
        (.updateWorldMatrix mesh true false) ; fresh mesh world matrix (camera may have just moved)
        (.localToWorld mesh p)
        (.project p camera)
        (let [rect (.getBoundingClientRect canvas)]
          [(+ (.-left rect) (* (/ (+ (.-x p) 1) 2) (.-width rect)))
           (+ (.-top rect) (* (/ (- 1 (.-y p)) 2) (.-height rect)))])))))

(defn draw-loupe!
  "Draw a magnified, nearest-neighbour crop of the loaded photo centred on photo
   pixel (ix,iy) into square `dst-canvas`, with a red crosshair — the same loupe
   as scripts/param-acq-tool.html, so a corner can be placed on the exact pixel
   despite the translucent proxy over it. `zoom` = loupe px per photo px. No-op
   if no photo has loaded."
  [^js dst-canvas ix iy zoom]
  (when-let [{:keys [^js src-canvas]} @bstate]
    (when src-canvas
      (let [R (.-width dst-canvas)
            half (/ R (* 2.0 zoom))
            ctx (.getContext dst-canvas "2d")]
        (.setTransform ctx 1 0 0 1 0 0)
        (set! (.-fillStyle ctx) "#000")
        (.fillRect ctx 0 0 R R)
        (set! (.-imageSmoothingEnabled ctx) false)
        (.drawImage ctx src-canvas (- ix half) (- iy half) (* 2.0 half) (* 2.0 half) 0 0 R R)
        (set! (.-strokeStyle ctx) "#ff6b6b")
        (set! (.-lineWidth ctx) 1)
        (.beginPath ctx)
        (.moveTo ctx (/ R 2) 0) (.lineTo ctx (/ R 2) R)
        (.moveTo ctx 0 (/ R 2)) (.lineTo ctx R (/ R 2))
        (.stroke ctx)
        ;; current magnification, so wheel-zoom is legible
        (set! (.-font ctx) "11px sans-serif")
        (set! (.-textAlign ctx) "center")
        (set! (.-fillStyle ctx) "rgba(0,0,0,0.55)")
        (.fillRect ctx (- (/ R 2) 16) (- R 16) 32 13)
        (set! (.-fillStyle ctx) "#ffd24d")
        (.fillText ctx (str (.toFixed zoom 1) "×") (/ R 2) (- R 5))))))

(defn- load-image
  "Promise of an HTMLImageElement decoded from `url`."
  [url]
  (js/Promise. (fn [resolve reject]
                 (let [img (js/Image.)]
                   (set! (.-onload img) (fn [] (resolve img)))
                   (set! (.-onerror img) (fn [e] (reject e)))
                   (set! (.-src img) url)))))

(defn sampler-of
  "{:size [w h] :data <RGBA> :lum-at (fn [x y] -> 0-255|nil)} for anything
   drawable — a decoded <img>, or a <canvas> a live camera frame was drawn into.
   The pixel side of the acquisition channel is written against this shape and
   nothing else, which is what lets a frame that never touched the disk be
   detected, identified and registered exactly like a photo that did.

   Kept separate from load-luminance-sampler (which is this, plus the file) because
   a grabbed frame must be measurable BEFORE it is written: a frame that fails to
   register never becomes a file at all."
  [^js drawable iw ih]
  (let [canvas (.createElement js/document "canvas")]
    (set! (.-width canvas) iw)
    (set! (.-height canvas) ih)
    (let [ctx (.getContext canvas "2d" #js {:willReadFrequently true})]
      (.drawImage ctx drawable 0 0)
      (let [data (.-data (.getImageData ctx 0 0 iw ih))]
        {:size [iw ih]
         ;; the raw RGBA byte array (4/px), so the global blob detector can
         ;; downsample in one tight loop instead of ~12M lum-at CLOSURE calls per
         ;; photo (the batch-freezes-the-browser fix, fetta C)
         :data data
         :lum-at (fn [x y]
                   (let [px (Math/round x) py (Math/round y)]
                     (when (and (>= px 0) (>= py 0) (< px iw) (< py ih))
                       (let [o (* (+ (* py iw) px) 4)]
                         (+ (* 0.299 (aget data o))
                            (* 0.587 (aget data (+ o 1)))
                            (* 0.114 (aget data (+ o 2))))))))}))))

(defn load-luminance-sampler
  "Load the photo at `file-path` OFF-SCREEN — without disturbing the displayed
   backdrop — and resolve to {:lum-at (fn [x y] -> 0-255|nil) :size [w h]}. Lets a
   batch pass (plate 'f': register the whole ring at once) sample EACH photo's
   pixels while the view stays on the current one. Same blob-URL / same-origin
   offscreen canvas as cache-pixels!, so it never taints. lum-at matches
   luminance-at's rounding + weights exactly."
  [file-path]
  (-> (stl/desktop-read-file-blob file-path)
      (.then (fn [blob]
               (let [url (js/URL.createObjectURL blob)]
                 (-> (load-image url)
                     (.then (fn [^js img]
                              (let [s (sampler-of img (.-naturalWidth img) (.-naturalHeight img))]
                                (js/URL.revokeObjectURL url)
                                s)))))))))

(defn luminance-at
  "Grayscale value (0-255ish) at pixel (x,y) of the currently loaded photo,
   rounded to the nearest pixel — no bilinear interpolation, matching
   scripts/param-acq-tool.html's lum() exactly (the sub-pixel precision comes
   from edge-snap's parabolic peak fit, not from the pixel sampling). nil
   off-image or before any photo has loaded."
  [x y]
  (when-let [{:keys [image-width image-height pixels]} @bstate]
    (let [px (Math/round x) py (Math/round y)]
      (when (and pixels (>= px 0) (>= py 0) (< px image-width) (< py image-height))
        (let [o (* (+ (* py image-width) px) 4)]
          (+ (* 0.299 (aget pixels o))
             (* 0.587 (aget pixels (+ o 1)))
             (* 0.114 (aget pixels (+ o 2)))))))))
