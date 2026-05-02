(ns ridley.export.gif
  "GIF export for procedural animations — desktop only.

   Lazy-loads gif.js from public/vendor at first call so the web bundle
   stays clean. Composes ridley.export.animation/capture-frames! with a
   gif.js encoder, and writes the final file via the desktop geo_server
   /write-file endpoint to <HOME>/Documents/Ridley/exports/."
  (:require [ridley.env :as env]
            [ridley.export.animation :as capture]))

(def ^:private geo-server-url "http://127.0.0.1:12321")

;; ============================================================
;; Lazy gif.js loader
;; ============================================================

(defonce ^:private gif-load-promise (atom nil))

(defn- load-gif-encoder!
  "Inject vendor/gif.js once and resolve to the global GIF constructor."
  []
  (or @gif-load-promise
      (let [p (js/Promise.
               (fn [resolve reject]
                 (if-let [G (.-GIF js/window)]
                   (resolve G)
                   (let [script (js/document.createElement "script")]
                     (set! (.-src script) "vendor/gif.js")
                     (set! (.-async script) true)
                     (set! (.-onload script)
                           (fn [_]
                             (if-let [G (.-GIF js/window)]
                               (resolve G)
                               (reject (js/Error. "gif.js loaded but window.GIF is undefined")))))
                     (set! (.-onerror script)
                           (fn [_]
                             (reject (js/Error. "Failed to load vendor/gif.js"))))
                     (.appendChild js/document.head script)))))]
        (reset! gif-load-promise p)
        p)))

;; ============================================================
;; Progress overlay (covers the viewport during capture/encoding)
;; ============================================================

(def ^:private overlay-id "ridley-gif-export-overlay")

(defn- ensure-overlay! []
  (or (.getElementById js/document overlay-id)
      (let [el (js/document.createElement "div")
            inner (js/document.createElement "div")]
        (set! (.-id el) overlay-id)
        (set! (.. el -style -cssText)
              (str "position:fixed;inset:0;background:rgba(0,0,0,0.78);"
                   "z-index:99999;display:flex;align-items:center;"
                   "justify-content:center;color:#eaeaea;"
                   "font-family:-apple-system,BlinkMacSystemFont,Helvetica,sans-serif;"
                   "font-size:16px;"))
        (set! (.. inner -style -cssText)
              (str "padding:20px 28px;background:rgba(40,40,46,0.95);"
                   "border-radius:8px;box-shadow:0 8px 32px rgba(0,0,0,0.5);"
                   "text-align:center;min-width:280px;"))
        (.appendChild el inner)
        (.appendChild js/document.body el)
        el)))

(defn- set-overlay-text! [text]
  (let [el (ensure-overlay!)]
    (set! (.-textContent (.-firstChild el)) text)))

(defn- remove-overlay! []
  (when-let [el (.getElementById js/document overlay-id)]
    (.remove el)))

;; ============================================================
;; File path resolution + write via geo_server
;; ============================================================

(defn- resolve-export-path
  "Build <HOME>/Documents/Ridley/exports/<filename>. Returns Promise<string>."
  [filename]
  (js/Promise.
   (fn [resolve reject]
     (let [xhr (js/XMLHttpRequest.)]
       (.open xhr "POST" (str geo-server-url "/home-dir") true)
       (set! (.-onload xhr)
             (fn [_]
               (if (= 200 (.-status xhr))
                 (let [resp (js/JSON.parse (.-responseText xhr))]
                   (resolve (str (.-path resp) "/Documents/Ridley/exports/" filename)))
                 (reject (js/Error. (.-responseText xhr))))))
       (set! (.-onerror xhr)
             (fn [_] (reject (js/Error. "home-dir request failed"))))
       (.send xhr "")))))

(defn- read-dir
  "Ask geo_server to list the directory at `dir`. The Rust handler creates
   the directory if it doesn't exist, so this is also our 'ensure exports
   dir' step. Returns Promise<#js [{name, is_dir, size}, ...]>."
  [dir]
  (js/Promise.
   (fn [resolve reject]
     (let [xhr (js/XMLHttpRequest.)]
       (.open xhr "POST" (str geo-server-url "/read-dir") true)
       (.setRequestHeader xhr "Content-Type" "application/json")
       (set! (.-onload xhr)
             (fn [_]
               (if (= 200 (.-status xhr))
                 (resolve (js/JSON.parse (.-responseText xhr)))
                 (reject (js/Error. (.-responseText xhr))))))
       (set! (.-onerror xhr)
             (fn [_] (reject (js/Error. "read-dir request failed"))))
       (.send xhr (js/JSON.stringify #js {:path dir}))))))

(defn- file-exists?
  "Returns Promise<bool>. Splits path into dir + filename and checks
   the dir listing for filename."
  [path]
  (let [last-slash (.lastIndexOf path "/")
        dir (.substring path 0 last-slash)
        filename (.substring path (inc last-slash))]
    (-> (read-dir dir)
        (.then (fn [^js entries]
                 (.some entries (fn [e] (= filename (.-name e)))))))))

(defn- write-file
  "POST a Blob to geo_server /write-file with X-File-Path header.
   The Rust handler creates parent directories automatically."
  [^js blob path]
  (-> (.arrayBuffer blob)
      (.then (fn [ab]
               (js/Promise.
                (fn [resolve reject]
                  (let [xhr (js/XMLHttpRequest.)]
                    (.open xhr "POST" (str geo-server-url "/write-file") true)
                    (.setRequestHeader xhr "Content-Type" "application/octet-stream")
                    (.setRequestHeader xhr "X-File-Path" path)
                    (set! (.-onload xhr)
                          (fn [_]
                            (if (= 200 (.-status xhr))
                              (resolve nil)
                              (reject (js/Error. (.-responseText xhr))))))
                    (set! (.-onerror xhr)
                          (fn [_] (reject (js/Error. "write-file request failed"))))
                    (.send xhr (js/Uint8Array. ab)))))))))

;; ============================================================
;; Encoding orchestration
;; ============================================================

(defn- encode-blob
  "Drive a configured GIF instance to completion. Returns Promise<Blob>.
   gif.js is an EventEmitter — listen via .on, NOT via property assignment."
  [^js gif]
  (js/Promise.
   (fn [resolve reject]
     (.on gif "progress"
          (fn [p]
            (set-overlay-text! (str "Encoding GIF… "
                                    (Math/round (* 100 p)) "%"))))
     (.on gif "finished" resolve)
     (.on gif "abort" (fn [_] (reject (js/Error. "GIF encoding aborted"))))
     (.render gif))))

(defn- run-capture
  "Run capture-frames! and feed each frame into a gif.js encoder.
   The encoder is created on the first frame so its width/height match
   the canvas that capture-frames! actually renders into.
   Returns Promise<{:gif :info}>."
  [^js GIF {:keys [fps duration width anim]}]
  (let [gif-ref (atom nil)
        delay-ms (max 1 (Math/round (/ 1000.0 fps)))
        on-frame (fn [^js canvas i total]
                   (when (zero? i)
                     (reset! gif-ref
                             (GIF. #js {:workers 2
                                        :quality 10
                                        :workerScript "vendor/gif.worker.js"
                                        :width (.-width canvas)
                                        :height (.-height canvas)})))
                   (.addFrame ^js @gif-ref canvas
                              #js {:copy true :delay delay-ms})
                   (set-overlay-text!
                    (str "Capturing frame " (inc i) " / " total)))]
    (-> (capture/capture-frames!
         {:anim-name anim
          :fps fps
          :duration duration
          :width width
          :on-frame on-frame})
        (.then (fn [info] {:gif @gif-ref :info info})))))

;; ============================================================
;; Main entry
;; ============================================================

(defn- normalize-filename [filename]
  (let [lower (.toLowerCase ^js (str filename))]
    (if (.endsWith lower ".gif")
      filename
      (str filename ".gif"))))

(defn anim-export-gif
  "Render the current procedural (anim-proc!) animation to a GIF file.
   Desktop-only.

   Required:
     filename — string, with or without .gif extension

   Options:
     :fps       — frames per second in the GIF (default 15)
     :duration  — seconds; defaults to the animation's own duration
     :width     — output width in px (default 720); height is derived
                  from the current viewport's aspect ratio
     :anim      — keyword to disambiguate when multiple procedural
                  animations are registered
     :overwrite — boolean (default false); when false, abort with a clear
                  error if the target file already exists

   Saves to <HOME>/Documents/Ridley/exports/<filename>.
   Returns Promise<string> describing the result."
  [filename & {:keys [fps duration width anim overwrite]
               :or {fps 15 width 720 overwrite false}}]
  (when-not (env/desktop?)
    (throw (js/Error. "anim-export-gif: only available in the desktop build")))
  (when-not (and (string? filename) (pos? (count filename)))
    (throw (js/Error. "anim-export-gif: filename must be a non-empty string")))
  (let [filename (normalize-filename filename)
        opts {:fps fps :duration duration :width width :anim anim}
        check-collision
        (fn [path]
          (-> (file-exists? path)
              (.then (fn [exists?]
                       (when (and exists? (not overwrite))
                         (throw (js/Error.
                                 (str "anim-export-gif: file already exists: "
                                      path
                                      " — pass :overwrite true to replace it"))))
                       (set-overlay-text! "Preparing GIF export…")
                       path))))
        do-export
        (fn [^js GIF path]
          (-> (run-capture GIF opts)
              (.then (fn [{:keys [gif]}] (encode-blob gif)))
              (.then (fn [^js blob]
                       (set-overlay-text! "Writing file…")
                       (-> (write-file blob path)
                           (.then (fn [_]
                                    (remove-overlay!)
                                    (str "Wrote "
                                         (Math/round (/ (.-size blob) 1024))
                                         " KB to " path))))))))]
    (-> (resolve-export-path filename)
        (.then check-collision)
        (.then (fn [path]
                 (-> (load-gif-encoder!)
                     (.then (fn [GIF] (do-export GIF path))))))
        (.catch (fn [err]
                  (remove-overlay!)
                  (js/console.error "anim-export-gif failed:" err)
                  (throw err))))))
