(ns ridley.fonts.core
  "High-level font registration: orchestrates startup loading of built-in
   and persisted custom fonts, and exposes register/unregister helpers
   for the Settings → Fonts panel.

   The actual registry lives in `ridley.turtle.text`. Persistence lives
   in `ridley.fonts.storage`. This namespace just stitches them together."
  (:require [clojure.string :as str]
            [ridley.turtle.text :as text]
            [ridley.fonts.storage :as storage]))

(defn- normalize-id-str
  "Sanitize a user-typed id string: trim, lowercase, replace whitespace
   with `-`, strip a leading `:` if present."
  [s]
  (-> (or s "")
      str/trim
      (str/replace #"^:" "")
      str/lower-case
      (str/replace #"\s+" "-")))

(defn ^:export id->keyword
  "Coerce an id (keyword or string) to a keyword."
  [id]
  (if (keyword? id) id (keyword (normalize-id-str id))))

(defn load-persisted-fonts!
  "Read `_index.json` from disk and register each persisted custom font.
   Returns a Promise that resolves once all loads complete. No-op on web."
  []
  (let [entries (storage/list-index)]
    (if (seq entries)
      (-> (js/Promise.all
           (clj->js
            (for [{:keys [id label filename]} entries]
              (-> (storage/fetch-font-bytes filename)
                  (.then (fn [bytes]
                           (let [font (text/parse-font-bytes bytes)]
                             (text/register-font!
                              (keyword id) font
                              {:label (or label id)
                               :builtin? false
                               :filename filename})
                             font)))
                  (.catch (fn [err]
                            (js/console.warn
                             (str "Failed to load custom font :" id " — ") err)
                            nil))))))
          (.catch (fn [err]
                    (js/console.warn "Custom font load failed:" err)
                    nil)))
      (js/Promise.resolve nil))))

(defn init!
  "Initialize the whole font system at app startup. Returns a Promise
   that resolves once built-ins (and any persisted custom fonts) are in
   the registry."
  []
  (-> (text/init-builtin-fonts!)
      (.then (fn [_] (load-persisted-fonts!)))))

(defn register-custom-font!
  "Persist `bytes` to disk under a derived filename and register the
   parsed font under `id-kw`. `label` is shown in the Settings panel.
   `source-filename` is used to derive an extension (`.ttf` / `.otf`).
   Returns the resolved entry map, or nil on failure / web mode.

   Throws if the bytes are not a valid font."
  [id-kw label source-filename bytes]
  (when (storage/supported?)
    (let [id-str (name id-kw)
          font (text/parse-font-bytes bytes)
          ext (let [m (re-find #"\.(ttf|otf|woff|woff2)$" (str/lower-case (or source-filename "")))]
                (if m (first m) ".ttf"))
          filename (str id-str ext)
          entry (storage/save-font! id-str label filename bytes)]
      (when entry
        (text/register-font! id-kw font
                             {:label label
                              :builtin? false
                              :filename filename})
        entry))))

(defn unregister-custom-font!
  "Remove a custom font from the registry and from disk. Built-ins are
   refused. Returns true on success."
  [id-kw]
  (let [meta (text/font-info id-kw)]
    (if (:builtin? meta)
      false
      (do (text/unregister-font! id-kw)
          (storage/delete-font! (name id-kw))
          true))))
