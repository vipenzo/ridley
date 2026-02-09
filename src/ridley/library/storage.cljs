(ns ridley.library.storage
  "localStorage persistence for user-defined libraries.

   Schema:
   - ridley:lib:<name>  → JSON {name, requires, source, created, modified}
   - ridley:libs:active → JSON [name, ...] (ordered = load order)
   - ridley:libs:index  → JSON [name, ...] (all known libraries)"
  (:require [clojure.string :as str]))

;; ============================================================
;; Internal helpers
;; ============================================================

(defn- lib-key [name]
  (str "ridley:lib:" name))

(defn- read-json [key]
  (when-let [raw (.getItem js/localStorage key)]
    (try (js->clj (.parse js/JSON raw) :keywordize-keys true)
         (catch :default _ nil))))

(defn- write-json! [key value]
  (.setItem js/localStorage key (.stringify js/JSON (clj->js value))))

(defn- remove-key! [key]
  (.removeItem js/localStorage key))

(defn- read-index []
  (or (read-json "ridley:libs:index") []))

(defn- write-index! [names]
  (write-json! "ridley:libs:index" names))

(defn- read-active []
  (or (read-json "ridley:libs:active") []))

(defn- write-active! [names]
  (write-json! "ridley:libs:active" names))

(defn- now-iso []
  (.toISOString (js/Date.)))

;; ============================================================
;; Public API
;; ============================================================

(defn list-libraries
  "Returns vector of library names (from index), ordered."
  []
  (read-index))

(defn get-library
  "Returns {:name :requires :source :created :modified} or nil."
  [name]
  (read-json (lib-key name)))

(defn save-library!
  "Save/update library. Creates if new, updates modified timestamp.
   Adds to index if not present."
  [name source requires]
  (let [existing (get-library name)
        now (now-iso)
        lib {:name name
             :requires (vec requires)
             :source source
             :created (or (:created existing) now)
             :modified now}]
    (write-json! (lib-key name) lib)
    ;; Ensure in index
    (let [idx (read-index)]
      (when-not (some #{name} idx)
        (write-index! (conj idx name))))
    lib))

(defn delete-library!
  "Remove library from storage, index, and active list."
  [name]
  (remove-key! (lib-key name))
  (write-index! (vec (remove #{name} (read-index))))
  (write-active! (vec (remove #{name} (read-active)))))

(defn rename-library!
  "Rename library. Updates index, active list, and other libraries' requires."
  [old-name new-name]
  (when-let [lib (get-library old-name)]
    ;; Save under new name
    (let [renamed (assoc lib :name new-name :modified (now-iso))]
      (write-json! (lib-key new-name) renamed)
      (remove-key! (lib-key old-name)))
    ;; Update index
    (write-index! (mapv #(if (= % old-name) new-name %) (read-index)))
    ;; Update active list
    (write-active! (mapv #(if (= % old-name) new-name %) (read-active)))
    ;; Update other libraries' requires
    (doseq [lib-name (read-index)]
      (when-not (= lib-name new-name)
        (when-let [lib (get-library lib-name)]
          (when (some #{old-name} (:requires lib))
            (save-library! lib-name
                           (:source lib)
                           (mapv #(if (= % old-name) new-name %) (:requires lib)))))))))

(defn get-active-libraries
  "Returns ordered vector of active library names."
  []
  (read-active))

(defn set-active-libraries!
  "Set the ordered list of active libraries."
  [names]
  (write-active! (vec names)))

(defn activate-library!
  "Add library to end of active list (if not already there)."
  [name]
  (let [active (read-active)]
    (when-not (some #{name} active)
      (write-active! (conj active name)))))

(defn deactivate-library!
  "Remove library from active list."
  [name]
  (write-active! (vec (remove #{name} (read-active)))))

(defn reorder-active!
  "Replace active list with new ordering."
  [names]
  (write-active! (vec names)))

(defn export-library
  "Returns string in .clj format with header comments."
  [name]
  (when-let [lib (get-library name)]
    (str ";; Ridley Library: " name "\n"
         (when (seq (:requires lib))
           (str ";; Requires: " (str/join ", " (:requires lib)) "\n"))
         "\n"
         (:source lib))))

(defn import-library!
  "Parse .clj string, extract name and requires from header, save.
   Returns library name or nil on failure."
  [clj-string]
  (let [lines (str/split-lines clj-string)
        ;; Parse header: ;; Ridley Library: NAME
        name-line (first (filter #(str/starts-with? % ";; Ridley Library:") lines))
        name (when name-line
               (str/trim (subs name-line (count ";; Ridley Library:"))))
        ;; Parse requires: ;; Requires: A, B
        req-line (first (filter #(str/starts-with? % ";; Requires:") lines))
        requires (if req-line
                   (vec (map str/trim
                             (str/split (str/trim (subs req-line (count ";; Requires:"))) #",")))
                   [])
        ;; Source is everything that's not a header comment or blank prefix
        source-lines (drop-while #(or (str/starts-with? % ";;") (str/blank? %)) lines)
        source (str/join "\n" source-lines)]
    (when (and name (seq (str/trim source)))
      (save-library! name source requires)
      name)))
