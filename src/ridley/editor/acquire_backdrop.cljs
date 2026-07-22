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

(defn set-hfov!
  "Recompute the vertical FOV and resize the backdrop plane for a NEW
   horizontal FOV, reusing the ALREADY-LOADED photo's aspect ratio (no
   re-fetch) — for live recalibration (a focal-length slider) once a photo is
   already showing, so the apparent size of the proxy against the backdrop
   can be tuned with position/rotation untouched (Vincenzo, 2026-07-21: doing
   size and pose at once is confusing — separate them). No-op if no photo has
   loaded yet (photo-aspect unknown). See set-photo!'s docstring for why the
   conversion uses the PHOTO's aspect ratio, not the canvas's."
  [hfov-deg set-vfov!]
  (when-let [{:keys [mesh depth photo-aspect]} @bstate]
    (when photo-aspect
      (let [hfov-rad (* hfov-deg (/ Math/PI 180))
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
             :pixels (.-data (.getImageData ctx 0 0 iw ih))))))

(defn set-photo!
  "Load the photo at `file-path` (absolute, read via the Rust geo_server) as
   the backdrop texture. `hfov-deg` is the session's horizontal FOV (from the
   user's manually-entered focal length — photogrammetry/focal-mm->fov-deg).
   Once the image's own pixel dimensions are known, stores its aspect ratio
   and delegates the FOV conversion + plane sizing to set-hfov! (also the live-
   recalibration entry point, so the two never compute it differently). Also
   caches the raw pixel data (cache-pixels!) for luminance-at, the edge-snap's
   read of the real photo.
   Returns a Promise that resolves once the texture is applied."
  [file-path hfov-deg set-vfov!]
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
                                  (set-hfov! hfov-deg set-vfov!)
                                  (when-let [^js old-map (.-map (.-material mesh))]
                                    (.dispose old-map))
                                  (set! (.-map (.-material mesh)) tex)
                                  (set! (.-needsUpdate (.-material mesh)) true)
                                  (when old-url (js/URL.revokeObjectURL old-url)))))))))
        (.catch (fn [err]
                  (js/console.warn "edit-acquire: failed to load" file-path err))))))

(defn image-size
  "[width height] of the currently loaded photo in pixels, or nil before the
   first photo has loaded."
  []
  (when-let [{:keys [image-width image-height]} @bstate]
    [image-width image-height]))

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
