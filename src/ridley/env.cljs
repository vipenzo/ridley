(ns ridley.env
  "Runtime environment detection.

   The Tauri host injects `window.RIDLEY_ENV = \"desktop\"` into the webview
   via `initialization_script` before any app JS runs. In plain browser
   builds the global is undefined and we fall back to :webapp.")

(defn ^:export env
  "Returns the runtime environment as a keyword.
   :desktop when running inside the Tauri binary, :webapp otherwise."
  []
  (keyword (or (.-RIDLEY_ENV js/window) "webapp")))

(defn ^:export desktop?
  "True if running in the Tauri desktop binary."
  []
  (= :desktop (env)))

(defn ^:export geo-server-url
  "Base URL of the geometry/file server THIS window must talk to. The Tauri
   host injects `window.RIDLEY_GEO_PORT` with the port its own geo-server
   bound (12321, or the next free one when another Ridley holds it); a
   plain browser build has no such global and uses 12321. A desktop window
   whose server bound nothing gets port 0, so every request fails loudly
   instead of silently reaching another instance's server — which is where
   the save dialog used to open (2026-09-28)."
  []
  (let [p (.-RIDLEY_GEO_PORT js/window)]
    (str "http://127.0.0.1:" (if (some? p) p 12321))))
