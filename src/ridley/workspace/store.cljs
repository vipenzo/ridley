(ns ridley.workspace.store
  "First-class editor documents ('workspaces').

   A workspace is the script you *edit and run*. Workspaces are multi-document,
   switchable, and session-persisted in localStorage. They are a DIFFERENT axis
   from libraries: a library (ridley.library.*) is a dependency loaded into SCI
   as a prefixed namespace, while a workspace is the top-level script. Workspaces
   never enter the active-libraries list and are never loaded into SCI.

   Persistence has two levels:
   - Session (this module): localStorage, survives reload/restart. Session wins.
   - Disk (desktop only): a workspace may be bound to a file via :file-path;
     :disk-content records the content at the last sync so we can show a dirty
     indicator. The bound file is not re-read on startup, so the session content
     always wins; the indicator shows when content != disk-content."
  (:require [clojure.string :as str]))

;; ============================================================
;; localStorage keys (own namespace, separate from ridley:libs:*)
;; ============================================================

(def ^:private ws-key "ridley:workspaces")
(def ^:private current-key "ridley:current-workspace")
(def ^:private counter-key "ridley:workspace-counter")
(def ^:private legacy-key "ridley-definitions") ;; pre-workspace single-doc key

;; ============================================================
;; localStorage helpers
;; ============================================================

(defn- now-iso [] (.toISOString (js/Date.)))

(defn- read-json [k]
  (when-let [raw (.getItem js/localStorage k)]
    (try (js->clj (.parse js/JSON raw) :keywordize-keys true)
         (catch :default _ nil))))

(defn- write-json! [k v]
  (.setItem js/localStorage k (.stringify js/JSON (clj->js v))))

(defn- next-counter! []
  (let [n (inc (or (read-json counter-key) 0))]
    (write-json! counter-key n)
    n))

;; ============================================================
;; Read API
;; ============================================================

(defn list-workspaces
  "Ordered vector of workspace maps."
  []
  (or (read-json ws-key) []))

(defn- write-workspaces! [ws]
  (write-json! ws-key (vec ws)))

(defn current-id []
  (.getItem js/localStorage current-key))

(defn get-workspace [id]
  (first (filter #(= (:id %) id) (list-workspaces))))

(defn current
  "The current workspace map (falls back to the first one if the pointer is stale)."
  []
  (let [all (list-workspaces)]
    (or (first (filter #(= (:id %) (current-id)) all))
        (first all))))

(defn current-content []
  (:content (current)))

(defn current-libraries
  "The active-library names recorded on the current workspace (or nil if never
   set — e.g. a legacy workspace before the first projection)."
  []
  (:libraries (current)))

(defn dirty?
  "True when a file-bound workspace diverges from the last disk sync — either in
   its code (content) or in its recorded library list (which is written into the
   file header)."
  [w-or-id]
  (let [w (if (map? w-or-id) w-or-id (get-workspace w-or-id))]
    (boolean (and (:file-path w)
                  (or (not= (:content w) (:disk-content w))
                      (not= (vec (:libraries w)) (vec (:disk-libraries w))))))))

;; ============================================================
;; Write API
;; ============================================================

(defn set-current!
  "Set the current workspace pointer (does not touch editor content)."
  [id]
  (.setItem js/localStorage current-key id))

(defn- update-ws! [id f]
  (write-workspaces! (mapv (fn [w] (if (= (:id w) id) (f w) w))
                           (list-workspaces))))

(defn set-current-content!
  "Persist editor content into the current workspace. Called on every change."
  [content]
  (when-let [id (:id (current))]
    (update-ws! id #(assoc % :content content :modified (now-iso)))))

(defn set-current-libraries!
  "Record the active-library names on the current workspace (captured from the
   global active list whenever the user toggles libraries in the panel)."
  [names]
  (when-let [id (:id (current))]
    (update-ws! id #(assoc % :libraries (vec names) :modified (now-iso)))))

(defn- next-name
  "Smallest unused 'workspace-N' label (fills holes so numbers don't grow
   unbounded). User-renamed workspaces don't match the pattern, so they free
   their slot."
  []
  (let [used (into #{}
                   (keep (fn [w]
                           (when-let [[_ d] (re-matches #"workspace-(\d+)" (str (:name w)))]
                             (js/parseInt d 10)))
                         (list-workspaces)))]
    (loop [n 1]
      (if (contains? used n) (recur (inc n)) (str "workspace-" n)))))

(defn new-workspace!
  "Create a new ephemeral workspace with the given content (default empty) and
   library set (default: inherit the current workspace's libraries). Returns its
   id. Does NOT change the current pointer."
  ([] (new-workspace! "" nil))
  ([content] (new-workspace! content nil))
  ([content libraries]
   (let [id (str "ws-" (next-counter!))   ;; id: monotonic, internal, stable
         now (now-iso)
         libs (vec (or libraries (current-libraries)))  ;; inherit current
         ws {:id id :name (next-name)      ;; name: smallest free workspace-N
             :content (or content "") :ephemeral true
             :file-path nil :disk-content nil
             :libraries libs :disk-libraries nil
             :created now :modified now}]
     (write-workspaces! (conj (list-workspaces) ws))
     id)))

(defn rename!
  "Rename a workspace. Renaming promotes it out of ephemeral state."
  [id new-name]
  (update-ws! id #(assoc % :name new-name :ephemeral false :modified (now-iso))))

(defn duplicate!
  "Create a new ephemeral workspace copying the given workspace's content and
   library set. Returns the new id, or nil if the source is missing."
  [id]
  (when-let [w (get-workspace id)]
    (new-workspace! (:content w) (:libraries w))))

(defn bind-file!
  "Bind a workspace to a disk file and record the synced content + library list
   (both are written into the file). `name` (a display name, usually the file
   basename) is optional."
  [id path synced-content synced-libraries name]
  (update-ws! id #(cond-> (assoc % :file-path path
                                 :disk-content synced-content
                                 :disk-libraries (vec synced-libraries)
                                 :ephemeral false
                                 :modified (now-iso))
                    name (assoc :name name))))

(defn mark-synced!
  "Record that the workspace's current content + library list are now in sync
   with its disk file."
  [id content libraries]
  (update-ws! id #(assoc % :disk-content content
                         :disk-libraries (vec libraries))))

(defn remove-workspace!
  "Delete a workspace from the session store (does not delete any disk file)."
  [id]
  (write-workspaces! (vec (remove #(= (:id %) id) (list-workspaces)))))

;; ============================================================
;; Disk-file serialization (library list as a stripped header comment)
;; ============================================================

(def ^:private ws-header-prefix ";; Ridley Workspace")

(defn serialize-file
  "Serialize a workspace to disk-file text: a one-line metadata header carrying
   the library list (stripped on load) followed by the code body verbatim."
  [libraries body]
  (str ws-header-prefix " — Libraries: " (str/join ", " (vec libraries)) "\n"
       (or body "")))

(defn parse-file
  "Parse disk-file text written by serialize-file. Returns {:libraries :body}.
   :libraries is nil when there is no Ridley Workspace header (an external file),
   so callers can fall back to inheriting the current set. Only our own first
   line is stripped, so user comments are preserved; the body is verbatim."
  [text]
  (let [text (or text "")
        nl (.indexOf text "\n")
        first-line (if (neg? nl) text (subs text 0 nl))]
    (if (str/starts-with? first-line ws-header-prefix)
      (let [body (if (neg? nl) "" (subs text (inc nl)))
            i (.indexOf first-line "Libraries:")
            after (when (>= i 0) (subs first-line (+ i (count "Libraries:"))))
            libs (->> (str/split (str/trim (or after "")) #",")
                      (map str/trim)
                      (remove str/blank?)
                      vec)]
        {:libraries libs :body body})
      {:libraries nil :body text})))

;; ============================================================
;; Initialization / migration
;; ============================================================

(defn ensure-initialized!
  "One-shot migration: if no workspaces exist yet, seed one from the legacy
   single-document key `ridley-definitions` (or `default-content`), make it
   current. Returns the current workspace map."
  [default-content]
  (when (empty? (list-workspaces))
    (let [legacy (.getItem js/localStorage legacy-key)
          content (or legacy default-content "")
          id (str "ws-" (next-counter!))
          now (now-iso)
          ws {:id id :name (next-name)
              :content content :ephemeral true
              :file-path nil :disk-content nil
              :created now :modified now}]
      (write-workspaces! [ws])
      (set-current! id)))
  (current))
