(ns ridley.library.storage
  "Library persistence — filesystem (desktop mode via geo_server) with
   localStorage fallback (web-only mode).

   Desktop: libraries live as .clj files in ~/.ridley/libraries/
   Web:     libraries stored in localStorage (original behaviour)"
  (:require [clojure.string :as str]))

;; ============================================================
;; Geo-server filesystem backend
;; ============================================================

(def ^:private geo-server-url "http://127.0.0.1:12321")

(defonce ^:private desktop-mode-cache (atom nil))

(defn- desktop-mode? []
  (if-some [v @desktop-mode-cache]
    v
    (let [result (try
                   (let [xhr (js/XMLHttpRequest.)]
                     (.open xhr "POST" (str geo-server-url "/ping") false)
                     (.send xhr "{}")
                     (= 200 (.-status xhr)))
                   (catch :default _ false))]
      (reset! desktop-mode-cache result)
      result)))

;; We'll resolve the actual lib dir lazily on first use
(defonce ^:private lib-dir-cache (atom nil))

(defn- lib-dir []
  (or @lib-dir-cache
      (let [dir (try
                  (let [xhr (js/XMLHttpRequest.)]
                    (.open xhr "POST" (str geo-server-url "/home-dir") false)
                    (.send xhr "")
                    (if (= 200 (.-status xhr))
                      (let [resp (js->clj (js/JSON.parse (.-responseText xhr)) :keywordize-keys true)]
                        (str (:path resp) "/.ridley/libraries"))
                      "/tmp/.ridley/libraries"))
                  (catch :default _
                    "/tmp/.ridley/libraries"))]
        (reset! lib-dir-cache dir)
        dir)))

(defn- fs-write-text!
  "Write a text string to a file via geo_server."
  [path content]
  (let [xhr (js/XMLHttpRequest.)
        encoder (js/TextEncoder.)
        bytes (.encode encoder content)]
    (.open xhr "POST" (str geo-server-url "/write-file") false)
    (.setRequestHeader xhr "X-File-Path" path)
    (.send xhr bytes)
    (= 200 (.-status xhr))))

(defn- fs-read-text
  "Read a text file via geo_server. Returns string or nil."
  [path]
  (try
    (let [xhr (js/XMLHttpRequest.)]
      (.open xhr "POST" (str geo-server-url "/read-file") false)
      (.setRequestHeader xhr "X-File-Path" path)
      ;; Set response type to arraybuffer so we can decode UTF-8
      (set! (.-responseType xhr) "arraybuffer")
      (.send xhr "")
      (when (= 200 (.-status xhr))
        (let [decoder (js/TextDecoder. "utf-8")]
          (.decode decoder (.-response xhr)))))
    (catch :default _ nil)))

(defn- fs-read-json
  "Read and parse a JSON file. Returns clj map/vec or nil."
  [path]
  (when-let [text (fs-read-text path)]
    (try (js->clj (js/JSON.parse text) :keywordize-keys true)
         (catch :default _ nil))))

(defn- fs-write-json!
  "Write a clj value as JSON to a file."
  [path value]
  (fs-write-text! path (js/JSON.stringify (clj->js value) nil 2)))

(defn- fs-delete!
  "Delete a file via geo_server."
  [path]
  (try
    (let [xhr (js/XMLHttpRequest.)]
      (.open xhr "POST" (str geo-server-url "/delete-file") false)
      (.setRequestHeader xhr "X-File-Path" path)
      (.send xhr "")
      (= 200 (.-status xhr)))
    (catch :default _ false)))

;; ── Filesystem library paths ──────────────────────────────────

(defn- lib-file [name]
  (str (lib-dir) "/" name ".clj"))

(defn- index-file []
  (str (lib-dir) "/_index.json"))

(defn- active-file []
  (str (lib-dir) "/_active.json"))

;; ============================================================
;; localStorage backend (fallback for web mode)
;; ============================================================

(defn- ls-lib-key [name]
  (str "ridley:lib:" name))

(defn- ls-read-json [key]
  (when-let [raw (.getItem js/localStorage key)]
    (try (js->clj (.parse js/JSON raw) :keywordize-keys true)
         (catch :default _ nil))))

(defn- ls-write-json! [key value]
  (.setItem js/localStorage key (.stringify js/JSON (clj->js value))))

(defn- ls-remove-key! [key]
  (.removeItem js/localStorage key))

(defn- ls-read-index []
  (or (ls-read-json "ridley:libs:index") []))

(defn- ls-write-index! [names]
  (ls-write-json! "ridley:libs:index" names))

(defn- ls-read-active []
  (or (ls-read-json "ridley:libs:active") []))

(defn- ls-write-active! [names]
  (ls-write-json! "ridley:libs:active" names))

;; ============================================================
;; Helpers
;; ============================================================

(defn- now-iso []
  (.toISOString (js/Date.)))

(defn- parse-lib-header
  "Extract name and requires from .clj header comments."
  [source]
  (let [lines (str/split-lines source)
        name-line (first (filter #(str/starts-with? % ";; Ridley Library:") lines))
        name (when name-line
               (str/trim (subs name-line (count ";; Ridley Library:"))))
        req-line (first (filter #(str/starts-with? % ";; Requires:") lines))
        requires (if req-line
                   (vec (map str/trim
                             (str/split (str/trim (subs req-line (count ";; Requires:"))) #",")))
                   [])
        source-lines (drop-while #(or (str/starts-with? % ";;") (str/blank? %)) lines)
        source-body (str/join "\n" source-lines)]
    {:name name :requires requires :source source-body}))

(defn- lib->file-content
  "Serialize library to .clj file content with header."
  [name source requires]
  (str ";; Ridley Library: " name "\n"
       (when (seq requires)
         (str ";; Requires: " (str/join ", " requires) "\n"))
       "\n"
       source))

;; ============================================================
;; Public API — dispatches to filesystem or localStorage
;; ============================================================

(defn list-libraries
  "Returns vector of library names, ordered."
  []
  (if (desktop-mode?)
    (or (fs-read-json (index-file)) [])
    (ls-read-index)))

(defn get-library
  "Returns {:name :requires :source :created :modified} or nil."
  [name]
  (if (desktop-mode?)
    (when-let [text (fs-read-text (lib-file name))]
      (let [{:keys [requires source]} (parse-lib-header text)]
        ;; Metadata is stored in _index.json alongside the .clj file
        (let [idx (or (fs-read-json (index-file)) [])
              meta-file (str (lib-dir) "/_meta.json")
              all-meta (or (fs-read-json meta-file) {})]
          (merge {:name name
                  :requires requires
                  :source source}
                 (get all-meta (keyword name))))))
    (ls-read-json (ls-lib-key name))))

(defn save-library!
  "Save/update library."
  [name source requires]
  (let [now (now-iso)]
    (if (desktop-mode?)
      (let [;; Write .clj file
            _ (fs-write-text! (lib-file name) (lib->file-content name source requires))
            ;; Update index
            idx (or (fs-read-json (index-file)) [])
            new-idx (if (some #{name} idx) idx (conj idx name))
            _ (fs-write-json! (index-file) new-idx)
            ;; Update metadata
            meta-file (str (lib-dir) "/_meta.json")
            all-meta (or (fs-read-json meta-file) {})
            existing-meta (get all-meta (keyword name))
            new-meta (assoc all-meta (keyword name)
                            {:created (or (:created existing-meta) now)
                             :modified now})]
        (fs-write-json! meta-file new-meta)
        {:name name :requires (vec requires) :source source
         :created (or (:created existing-meta) now) :modified now})
      ;; localStorage mode
      (let [existing (ls-read-json (ls-lib-key name))
            lib {:name name
                 :requires (vec requires)
                 :source source
                 :created (or (:created existing) now)
                 :modified now}]
        (ls-write-json! (ls-lib-key name) lib)
        (when-not (some #{name} (ls-read-index))
          (ls-write-index! (conj (ls-read-index) name)))
        lib))))

(defn delete-library!
  "Remove library from storage, index, and active list."
  [name]
  (if (desktop-mode?)
    (do (fs-delete! (lib-file name))
        (fs-write-json! (index-file) (vec (remove #{name} (or (fs-read-json (index-file)) []))))
        (fs-write-json! (active-file) (vec (remove #{name} (or (fs-read-json (active-file)) []))))
        ;; Clean metadata
        (let [meta-file (str (lib-dir) "/_meta.json")
              all-meta (or (fs-read-json meta-file) {})]
          (fs-write-json! meta-file (dissoc all-meta (keyword name)))))
    (do (ls-remove-key! (ls-lib-key name))
        (ls-write-index! (vec (remove #{name} (ls-read-index))))
        (ls-write-active! (vec (remove #{name} (ls-read-active)))))))

(defn rename-library!
  "Rename library. Updates index, active list, and other libraries' requires."
  [old-name new-name]
  (when-let [lib (get-library old-name)]
    (if (desktop-mode?)
      (do
        ;; Write new file, delete old
        (fs-write-text! (lib-file new-name)
                        (lib->file-content new-name (:source lib) (:requires lib)))
        (fs-delete! (lib-file old-name))
        ;; Update index
        (fs-write-json! (index-file)
                        (mapv #(if (= % old-name) new-name %) (or (fs-read-json (index-file)) [])))
        ;; Update active
        (fs-write-json! (active-file)
                        (mapv #(if (= % old-name) new-name %) (or (fs-read-json (active-file)) [])))
        ;; Update metadata
        (let [meta-file (str (lib-dir) "/_meta.json")
              all-meta (or (fs-read-json meta-file) {})
              meta-entry (get all-meta (keyword old-name))]
          (fs-write-json! meta-file
                          (-> all-meta
                              (dissoc (keyword old-name))
                              (assoc (keyword new-name) (assoc meta-entry :modified (now-iso))))))
        ;; Update other libraries' requires
        (doseq [lib-name (or (fs-read-json (index-file)) [])]
          (when-not (= lib-name new-name)
            (when-let [other (get-library lib-name)]
              (when (some #{old-name} (:requires other))
                (save-library! lib-name (:source other)
                               (mapv #(if (= % old-name) new-name %) (:requires other))))))))
      ;; localStorage mode
      (do
        (let [renamed (assoc lib :name new-name :modified (now-iso))]
          (ls-write-json! (ls-lib-key new-name) renamed)
          (ls-remove-key! (ls-lib-key old-name)))
        (ls-write-index! (mapv #(if (= % old-name) new-name %) (ls-read-index)))
        (ls-write-active! (mapv #(if (= % old-name) new-name %) (ls-read-active)))
        (doseq [lib-name (ls-read-index)]
          (when-not (= lib-name new-name)
            (when-let [other (ls-read-json (ls-lib-key lib-name))]
              (when (some #{old-name} (:requires other))
                (save-library! lib-name (:source other)
                               (mapv #(if (= % old-name) new-name %) (:requires other)))))))))))

(defn get-active-libraries
  "Returns ordered vector of active library names."
  []
  (if (desktop-mode?)
    (or (fs-read-json (active-file)) [])
    (ls-read-active)))

(defn set-active-libraries!
  "Set the ordered list of active libraries."
  [names]
  (if (desktop-mode?)
    (fs-write-json! (active-file) (vec names))
    (ls-write-active! (vec names))))

(defn activate-library!
  "Add library to end of active list (if not already there)."
  [name]
  (let [active (get-active-libraries)]
    (when-not (some #{name} active)
      (set-active-libraries! (conj active name)))))

(defn deactivate-library!
  "Remove library from active list."
  [name]
  (set-active-libraries! (vec (remove #{name} (get-active-libraries)))))

(defn reorder-active!
  "Replace active list with new ordering."
  [names]
  (set-active-libraries! (vec names)))

(defn export-library
  "Returns string in .clj format with header comments."
  [name]
  (when-let [lib (get-library name)]
    (lib->file-content name (:source lib) (:requires lib))))

(defn import-library!
  "Parse .clj string, extract name and requires from header, save.
   Returns library name or nil on failure."
  [clj-string]
  (let [{:keys [name requires source]} (parse-lib-header clj-string)]
    (when (and name (seq (str/trim source)))
      (save-library! name source requires)
      name)))
