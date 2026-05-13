(ns ridley.library.builtin
  "Builtin libraries shipped with Ridley.

   Bundled as static files under /builtin-libraries/ in the app's served
   content. On every editor startup, install-builtins! fetches the manifest,
   downloads each library, saves it (overwriting any existing copy), and
   marks it as :builtin so the panel can lock it from edits."
  (:require [ridley.library.storage :as storage]))

(def ^:private manifest-url "./builtin-libraries/_manifest.json")

(defn- lib-url [lib-name]
  (str "./builtin-libraries/" lib-name ".clj"))

(defn- fetch-text-sync
  "Synchronous GET. Returns the response body string or nil on non-200 / error."
  [url]
  (try
    (let [xhr (js/XMLHttpRequest.)]
      (.open xhr "GET" url false)
      (.send xhr)
      (when (= 200 (.-status xhr))
        (.-responseText xhr)))
    (catch :default _ nil)))

(defn- fetch-manifest []
  (when-let [text (fetch-text-sync manifest-url)]
    (try
      (js->clj (.parse js/JSON text) :keywordize-keys true)
      (catch :default _ nil))))

(defn install-builtins!
  "Install all bundled builtin libraries, overwriting any existing copies.
   Idempotent — safe to call on every startup."
  []
  (when-let [{:keys [libraries]} (fetch-manifest)]
    (doseq [lib-name libraries]
      (when-let [content (fetch-text-sync (lib-url lib-name))]
        (when-let [installed (storage/import-library! content)]
          (storage/mark-builtin! installed))))))
