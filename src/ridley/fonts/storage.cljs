(ns ridley.fonts.storage
  "Persistence for custom user-registered fonts.

   Desktop: fonts live as binary files in `~/.ridley/fonts/<id>.ttf` with
   an `_index.json` listing `[{:id :label :filename}]`. Web is not
   supported — custom fonts are a desktop-only feature.

   Built-in fonts (`:roboto`, `:roboto-mono`) are bundled with the app
   and registered directly by `ridley.turtle.text/init-builtin-fonts!`;
   this module only deals with user-added customs."
  (:require [ridley.env :as env]))

(def ^:private geo-server-url "http://127.0.0.1:12321")

(defonce ^:private fonts-dir-cache (atom nil))

(defn- fonts-dir
  "Resolve `~/.ridley/fonts` lazily on first use. Returns nil if we are
   not running in desktop mode."
  []
  (when (env/desktop?)
    (or @fonts-dir-cache
        (let [dir (try
                    (let [xhr (js/XMLHttpRequest.)]
                      (.open xhr "POST" (str geo-server-url "/home-dir") false)
                      (.send xhr "")
                      (when (= 200 (.-status xhr))
                        (let [resp (js->clj (js/JSON.parse (.-responseText xhr))
                                            :keywordize-keys true)]
                          (str (:path resp) "/.ridley/fonts"))))
                    (catch :default _ nil))]
          (when dir
            (reset! fonts-dir-cache dir))
          dir))))

(defn- index-file [] (str (fonts-dir) "/_index.json"))

(defn- fs-read-text
  [path]
  (try
    (let [xhr (js/XMLHttpRequest.)]
      (.open xhr "POST" (str geo-server-url "/read-file") false)
      (.setRequestHeader xhr "X-File-Path" path)
      (.send xhr "")
      (when (= 200 (.-status xhr))
        (.-responseText xhr)))
    (catch :default _ nil)))

(defn- fs-read-json
  [path]
  (when-let [text (fs-read-text path)]
    (try (js->clj (js/JSON.parse text) :keywordize-keys true)
         (catch :default _ nil))))

(defn- fs-write-text!
  [path content]
  (let [xhr (js/XMLHttpRequest.)]
    (.open xhr "POST" (str geo-server-url "/write-file") false)
    (.setRequestHeader xhr "X-File-Path" path)
    (.send xhr content)
    (= 200 (.-status xhr))))

(defn- fs-write-json!
  [path value]
  (fs-write-text! path (js/JSON.stringify (clj->js value) nil 2)))

(defn- fs-write-bytes!
  "Write an ArrayBuffer or Uint8Array to `path`."
  [path bytes]
  (let [xhr (js/XMLHttpRequest.)]
    (.open xhr "POST" (str geo-server-url "/write-file") false)
    (.setRequestHeader xhr "X-File-Path" path)
    (.send xhr bytes)
    (= 200 (.-status xhr))))

(defn- fs-delete!
  [path]
  (try
    (let [xhr (js/XMLHttpRequest.)]
      (.open xhr "POST" (str geo-server-url "/delete-file") false)
      (.setRequestHeader xhr "X-File-Path" path)
      (.send xhr "")
      (= 200 (.-status xhr)))
    (catch :default _ false)))

(defn- fs-read-bytes
  "Async read of binary bytes via XHR with responseType=arraybuffer.
   Returns a Promise resolving to an ArrayBuffer, or rejecting with an
   error message string."
  [path]
  (js/Promise.
   (fn [resolve reject]
     (let [xhr (js/XMLHttpRequest.)]
       (.open xhr "POST" (str geo-server-url "/read-file") true)
       (.setRequestHeader xhr "X-File-Path" path)
       (set! (.-responseType xhr) "arraybuffer")
       (set! (.-onload xhr)
             (fn []
               (if (= 200 (.-status xhr))
                 (resolve (.-response xhr))
                 (reject (str "read-file failed: HTTP " (.-status xhr))))))
       (set! (.-onerror xhr) (fn [] (reject "read-file network error")))
       (.send xhr "")))))

;; ============================================================
;; Public API
;; ============================================================

(defn supported?
  "True if custom font persistence is available (desktop only)."
  []
  (boolean (fonts-dir)))

(defn list-index
  "Return the `_index.json` contents as a vector of
   `{:id \"...\" :label \"...\" :filename \"...\"}`, or `[]` if missing.
   Returns `[]` on web."
  []
  (if (supported?)
    (or (fs-read-json (index-file)) [])
    []))

(defn save-font!
  "Write `bytes` (ArrayBuffer or Uint8Array) to `<fonts-dir>/<filename>`
   and add/update the `_index.json` entry. Returns the entry map or nil
   on failure."
  [id-str label filename bytes]
  (when (supported?)
    (let [path (str (fonts-dir) "/" filename)]
      (when (fs-write-bytes! path bytes)
        (let [existing (list-index)
              entry {:id id-str :label label :filename filename}
              next-idx (conj (vec (remove #(= (:id %) id-str) existing)) entry)]
          (fs-write-json! (index-file) next-idx)
          entry)))))

(defn delete-font!
  "Remove the font file and its `_index.json` entry. Returns true on
   success."
  [id-str]
  (when (supported?)
    (let [existing (list-index)
          target (some #(when (= (:id %) id-str) %) existing)]
      (when target
        (fs-delete! (str (fonts-dir) "/" (:filename target)))
        (fs-write-json! (index-file)
                        (vec (remove #(= (:id %) id-str) existing)))
        true))))

(defn fetch-font-bytes
  "Read the bytes for one indexed font. Returns a Promise of an
   ArrayBuffer."
  [filename]
  (fs-read-bytes (str (fonts-dir) "/" filename)))
