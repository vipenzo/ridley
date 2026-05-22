(ns ridley.manual.draft-renderer
  "Renders draft manual chapters from raw Markdown files.

   Extracts <!-- example-source: id ... --> blocks before parsing,
   converts Markdown via marked, then walks the result and replaces
   ```clojure``` blocks with CodeMirror read-only views, attaching
   Run/Edit buttons when an example-source is present after the block."
  (:require ["@codemirror/view" :refer [EditorView]]
            ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/language" :refer [syntaxHighlighting HighlightStyle]]
            ["@lezer/highlight" :refer [tags]]
            ["@nextjournal/clojure-mode" :as clojure-mode]
            ["marked" :as marked]))

;; ── Chapter manifest ──────────────────────────────────────────

(def draft-chapters
  "Ordered list of draft chapters to render via this pipeline."
  [{:id :draft-about :file "about-ridley.md"             :title "About Ridley"}
   {:id :draft-02    :file "02-modeling-with-primitives.md" :title "2. Modellare per primitive"}
   {:id :draft-03    :file "03-working-with-2d-shapes.md"   :title "3. Lavorare con le forme 2D"}
   {:id :draft-04    :file "04-extrusion.md"             :title "4. Estrusione"}
   {:id :draft-05    :file "05-paths.md"                 :title "5. Path"}
   {:id :draft-06    :file "06-shape-fn.md"              :title "6. Shape-fn"}
   {:id :draft-07    :file "07-mesh.md"                  :title "7. Mesh"}
   {:id :draft-08    :file "08-assemblaggio.md"          :title "8. Assemblaggio"}
   {:id :draft-09    :file "09-librerie.md"              :title "9. Librerie"}
   {:id :draft-10    :file "10-analizzare-e-misurare.md" :title "10. Analizzare e misurare"}
   {:id :draft-11    :file "11-curve-avanzate.md"        :title "11. Curve avanzate"}
   {:id :draft-12    :file "12-sdf.md"                   :title "12. SDF"}
   {:id :draft-13    :file "13-testo.md"                 :title "13. Testo"}
   {:id :draft-14    :file "14-colore-e-materiali.md"    :title "14. Colore e materiali"}
   {:id :draft-15    :file "15-debug.md"                 :title "15. Debug"}
   {:id :draft-16    :file "16-clojure-per-ridley.md"    :title "16. Clojure per Ridley"}
   {:id :draft-17    :file "17-esportare-e-stampare.md"  :title "17. Esportare e stampare"}])

(defn draft-chapter
  "Return the draft chapter map for a given page id, or nil."
  [page-id]
  (some #(when (= (:id %) page-id) %) draft-chapters))

(defn draft-page?
  "True if the page id is a draft chapter rendered from markdown."
  [page-id]
  (some? (draft-chapter page-id)))

(defn draft-chapter-ids
  "Page ids of all draft chapters in declaration order."
  []
  (mapv :id draft-chapters))

;; ── Callbacks (shared with the main manual via set-callbacks!) ─

(defonce ^:private callbacks (atom {:on-run nil :on-copy nil}))

(defn set-callbacks!
  "Wire Run / Edit callbacks. Same signature as manual.components.
   on-run: (fn [code]) — invoked when Run is clicked
   on-copy: (fn [code]) — invoked when Edit is clicked"
  [{:keys [on-run on-copy]}]
  (reset! callbacks {:on-run on-run :on-copy on-copy}))

;; ── Markdown content cache ────────────────────────────────────

(defonce ^:private chapter-cache (atom {}))

(defn- fetch-markdown
  "Fetch the .md file from /manual-drafts/. Returns a promise resolving to the text."
  [filename]
  (if-let [cached (get @chapter-cache filename)]
    (js/Promise.resolve cached)
    (-> (js/fetch (str "manual-drafts/" filename))
        (.then (fn [resp]
                 (if (.-ok resp)
                   (.text resp)
                   (throw (js/Error. (str "Failed to fetch " filename
                                          " (HTTP " (.-status resp) ")"))))))
        (.then (fn [text]
                 (swap! chapter-cache assoc filename text)
                 text)))))

;; ── Example-source marker extraction ──────────────────────────
;;
;; Format (single-line marker preceding a ```clojure``` block):
;;
;;     <!-- example-source: some-id -->
;;     ```clojure
;;     (code)
;;     ```
;;
;; The code is read from the visible block itself; the marker just flags
;; the block as runnable and carries the id (for future cross-references).
;;
;; Any other HTML comment — including the old multi-line example-source
;; format still present in unconverted chapters — is stripped silently.
;; In that transitional state, those code blocks render as illustrative
;; (no Run button), which is the desired behaviour.

(defn- replace-example-markers
  "Replace each <!-- example-source: id --> marker with a block-level sentinel
   div that survives Markdown parsing. Returns the rewritten markdown."
  [markdown]
  (let [pattern (js/RegExp. "<!--[ \\t]*example-source:[ \\t]*(\\S+)[ \\t]*-->" "g")]
    (.replace markdown pattern
              (fn [_match id]
                (str "\n\n<div class=\"draft-example-source\" data-source-id=\""
                     id "\"></div>\n\n")))))

(defn- strip-remaining-comments
  "Strip any HTML comments left over after marker replacement. Catches author
   notes at the top of files and any old-format <!-- example-source: id\\n code -->
   blocks still present in unconverted chapters."
  [markdown]
  (.replace markdown (js/RegExp. "<!--[\\s\\S]*?-->" "g") ""))

;; ── CodeMirror read-only views (duplicated from components.cljs to keep this namespace self-contained) ─

(defn- create-highlight-style []
  (.define HighlightStyle
           #js [#js {:tag (.-lineComment tags)  :color "#6a9955" :fontStyle "italic"}
                #js {:tag (.-atom tags)         :color "#4fc1ff"}
                #js {:tag (.-string tags)       :color "#ce9178"}
                #js {:tag (.-number tags)       :color "#b5cea8"}
                #js {:tag (.-keyword tags)      :color "#c586c0"}
                #js {:tag (.definition tags (.-variableName tags)) :color "#dcdcaa"}
                #js {:tag (.-variableName tags) :color "#9cdcfe"}
                #js {:tag (.-bool tags)         :color "#569cd6"}
                #js {:tag (.-null tags)         :color "#569cd6"}
                #js {:tag (.-punctuation tags)  :color "#d4d4d4"}
                #js {:tag (.-bracket tags)      :color "#d4d4d4"}]))

(defn- create-readonly-theme []
  (.theme EditorView
          #js {"&" #js {:backgroundColor "#1a1a2e" :fontSize "13px"}
               ".cm-scroller" #js {:fontFamily "'SF Mono', 'Monaco', 'Menlo', 'Consolas', monospace"
                                   :lineHeight "1.5"
                                   :overflow "auto"}
               ".cm-content" #js {:padding "12px" :caretColor "transparent"}
               ".cm-line"    #js {:padding "0 4px"}
               "&.cm-focused" #js {:outline "none"}
               ".cm-gutters" #js {:display "none"}
               ".cm-cursor"  #js {:display "none"}}
          #js {:dark true}))

(defn- create-code-view [parent code]
  (let [extensions #js [(syntaxHighlighting (create-highlight-style))
                        clojure-mode/default_extensions
                        (create-readonly-theme)
                        (.of (.-editable EditorView) false)]
        state (.create EditorState
                       #js {:doc code :extensions extensions})]
    (EditorView. #js {:state state :parent parent})))

;; ── Code block + Run/Edit button rendering ────────────────────

(defn- make-button [label classes click-fn]
  (let [btn (.createElement js/document "button")]
    (set! (.-className btn) classes)
    (set! (.-textContent btn) label)
    (.addEventListener btn "click" click-fn)
    btn))

(defn- render-code-block!
  "Replace a <pre><code> element with a CodeMirror read-only view. If
   `example-code` is non-nil, append Run + Edit buttons that funnel
   `example-code` through the shared callbacks."
  [pre-el visible-code example-code]
  (let [block (.createElement js/document "div")
        code-container (.createElement js/document "div")]
    (set! (.-className block) "manual-example")
    (set! (.-className code-container) "manual-example-code")
    (.appendChild block code-container)
    (create-code-view code-container visible-code)
    (when example-code
      (let [buttons (.createElement js/document "div")]
        (set! (.-className buttons) "manual-example-buttons")
        (.appendChild buttons
                      (make-button "Run" "manual-btn manual-btn-run"
                                   (fn [_]
                                     (when-let [on-run (:on-run @callbacks)]
                                       (on-run example-code)))))
        (.appendChild buttons
                      (make-button "Edit" "manual-btn manual-btn-copy"
                                   (fn [_]
                                     (when-let [on-copy (:on-copy @callbacks)]
                                       (on-copy example-code)))))
        (.appendChild block buttons)))
    (.replaceWith pre-el block)))

(defn- enhance-code-blocks!
  "Walk the rendered manual content. For each <pre><code class='language-clojure'>:
   - if its previousElementSibling is the marker <div class='draft-example-source'>,
     attach Run+Edit buttons (the code in the block is both visible and runnable);
   - otherwise render as illustrative (no buttons)."
  [root]
  ;; Snapshot the list before mutating to avoid live-NodeList surprises.
  (let [pres (vec (array-seq (.querySelectorAll root "pre")))]
    (doseq [pre pres]
      (let [code-el (.querySelector pre "code")
            visible (when code-el (.-textContent code-el))
            cls (when code-el (.-className code-el))
            clojure? (and cls (re-find #"language-clojure" cls))
            prev (.-previousElementSibling pre)
            marker (when (and prev
                              (.. prev -classList (contains "draft-example-source")))
                     prev)
            runnable? (some? marker)]
        (when marker (.remove marker))
        (when clojure?
          (render-code-block! pre visible (when runnable? visible))))))
  ;; Clean up any leftover orphan markers (paranoia).
  (doseq [orphan (array-seq (.querySelectorAll root ".draft-example-source"))]
    (.remove orphan)))

;; ── Public render entry point ─────────────────────────────────

(defn- show-loading! [container]
  (set! (.-innerHTML container) "")
  (let [p (.createElement js/document "p")]
    (set! (.-className p) "manual-paragraph")
    (set! (.-textContent p) "Loading…")
    (.appendChild container p)))

(defn- show-error! [container msg]
  (set! (.-innerHTML container) "")
  (let [p (.createElement js/document "p")]
    (set! (.-className p) "manual-paragraph manual-draft-error")
    (set! (.-textContent p) msg)
    (.appendChild container p)))

(defn render-chapter!
  "Render the draft chapter identified by `page-id` into `container-el`.
   Returns the chapter map (or nil if page-id is not a draft chapter)."
  [container-el page-id]
  (when-let [chap (draft-chapter page-id)]
    (show-loading! container-el)
    (-> (fetch-markdown (:file chap))
        (.then (fn [raw-md]
                 (let [with-sentinels (replace-example-markers raw-md)
                       cleaned (strip-remaining-comments with-sentinels)
                       html (.parse marked cleaned)]
                   (set! (.-innerHTML container-el) html)
                   (set! (.-className container-el)
                         (str (or (.-className container-el) "")
                              " manual-draft-content"))
                   (enhance-code-blocks! container-el))))
        (.catch (fn [err]
                  (show-error! container-el
                               (str "Errore caricamento capitolo: " (.-message err))))))
    chap))
