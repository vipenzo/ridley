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
