(ns ridley.input.spacemouse-device
  "The webview end of the desktop SpaceMouse reader (desktop/src-tauri/src/
   spacemouse.rs). Desktop only: without `window.__TAURI__` (the browser build)
   every function here is a silent no-op and `status` stays nil.

   The listeners only store the latest state: whoever drives the device (the
   edit-attach frame loop) reads `state` once per frame.")

(defonce state
  ;; Latest raw device state {:t [tx ty tz] :r [rx ry rz] :buttons n}, device
  ;; frame; nil when released or never received.
  (atom nil))

(defonce status
  ;; {:connected bool} after the first acquire; nil before, and in the browser.
  (atom nil))

(defonce ^:private listening? (atom false))

(defn- tauri []
  (when (exists? js/window) (.-__TAURI__ js/window)))

(defn available?
  "True inside the desktop app, where the reader exists."
  []
  (some? (tauri)))

(defn- listen! [^js t]
  (when (compare-and-set! listening? false true)
    (.listen (.-event t) "spacemouse"
             (fn [^js e]
               (let [p (.-payload e)]
                 (reset! state {:t (vec (.-t p)) :r (vec (.-r p)) :buttons (.-buttons p)}))))
    (.listen (.-event t) "spacemouse-status"
             (fn [^js e]
               (reset! status {:connected (boolean (.. e -payload -connected))})))))

(defn- invoke! [^js t cmd]
  (-> (.invoke (.-core t) cmd)
      (.catch (fn [err] (js/console.error (str cmd " failed:") err)))))

(defn acquire!
  "Ask for the device, exclusively, until release! (the reader also lets go while
   the window is not focused). A spacemouse-status answer follows."
  []
  (when-let [t (tauri)]
    (listen! t)
    (invoke! t "spacemouse_acquire")))

(defn release!
  "Give the device back to macOS (it moves the pointer again)."
  []
  (when-let [t (tauri)]
    (reset! state nil)
    (invoke! t "spacemouse_release")))
