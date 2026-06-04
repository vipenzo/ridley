(ns ridley.manual.draft-renderer
  "Renders draft manual chapters from raw Markdown files.

   Extracts <!-- example-source: id ... --> blocks before parsing,
   converts Markdown via marked, then walks the result and replaces
   ```clojure``` blocks with CodeMirror read-only views, attaching
   Run/Edit buttons when an example-source is present after the block."
  (:require ["@codemirror/view" :refer [EditorView tooltips]]
            ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/language" :refer [syntaxHighlighting HighlightStyle]]
            ["@lezer/highlight" :refer [tags]]
            ["@nextjournal/clojure-mode" :as clojure-mode]
            ["marked" :as marked]
            [clojure.string :as str]
            [ridley.editor.codemirror :as cm]
            [ridley.manual.reference-index :as ref-index]
            [ridley.manual.structure :as structure]))

;; ── Reference link handler (T-009) ────────────────────────────
;; Set by core.cljs to ridley.manual.reference-browser/open-card!. Invoked
;; when a rendered link points at a reference card. Kept as a callback to
;; avoid a draft-renderer → reference-browser dependency cycle.

(defonce ^:private link-handler (atom nil))

(defn set-link-handler!
  "Wire the handler invoked for reference links (`ref:NAME` or a relative
   card `*.md`) clicked inside rendered guide/card content. Receives the raw
   href and should return truthy when it handled the navigation."
  [f]
  (reset! link-handler f))

(defn- reference-link?
  "True for hrefs that target a reference card: the ref:NAME pseudo-scheme or
   a relative *.md link (not an absolute URL or in-page #anchor)."
  [href]
  (and href
       (or (str/starts-with? href "ref:")
           (and (str/ends-with? href ".md")
                (not (re-find #"^[a-z]+://" href))
                (not (str/starts-with? href "#"))))))

(defn- wire-reference-links!
  "Intercept clicks on reference links inside `container`, routing them to the
   link handler (which opens the matching card). Non-reference links are left
   to default browser behaviour."
  [container]
  (doseq [a (array-seq (.querySelectorAll container "a"))]
    (let [href (.getAttribute a "href")]
      (when (reference-link? href)
        (.addEventListener a "click"
                           (fn [ev]
                             (when-let [h @link-handler]
                               (.preventDefault ev)
                               (h href))))))))

;; ── Reference auto-linking (extends T-009) ─────────────────────
;; At render time, turn every inline backtick code span whose content is
;; exactly a known Reference symbol into a `ref:NAME` link, so deliberate
;; mentions of a function become clickable without hand-written markup. Match
;; is exact against the index keys (which already carry special-char names like
;; `extrude+` / `transform->`), so the generated `ref:NAME` resolves by name.

(defonce ^:private known-symbol-names
  (delay (set (keys ref-index/reference-index))))

(defn- symbol-for-card-url
  "Name of the Reference card served at `url`, matched against the index
   :path basenames. Used to skip auto-linking a card's own symbol to itself."
  [url]
  (when (string? url)
    (let [base (last (str/split url #"/"))]
      (some (fn [e] (when (str/ends-with? (str (:path e)) base) (:name e)))
            (vals ref-index/reference-index)))))

(defn- autolink-references!
  "Wrap inline `<code>` spans whose text is exactly a known Reference symbol in
   a `ref:NAME` anchor. Skips code inside example blocks (<pre>), inside an
   existing anchor (no double-wrapping over manual ref: links), and inside
   headings. `current-symbol`, when set, is left unlinked (a card's own name)."
  [container current-symbol]
  (let [names @known-symbol-names]
    (doseq [code (array-seq (.querySelectorAll container "code"))]
      (let [name (str/trim (.-textContent code))]
        (when (and (contains? names name)
                   (not= name current-symbol)
                   (not (.closest code "pre"))
                   (not (.closest code "a"))
                   (not (.closest code "h1, h2, h3, h4, h5, h6")))
          (let [a (.createElement js/document "a")]
            (.setAttribute a "href" (str "ref:" name))
            (set! (.-className a) "manual-autolink")
            (.replaceWith code a)
            (.appendChild a code)))))))

;; ── Chapter manifest ──────────────────────────────────────────
;;
;; Sourced from ridley.manual.structure (the single source of truth).
;; This thin view keeps the {:id :file :title} shape expected by the
;; legacy content.cljs drafts-section; titles use the source language.

(def draft-chapters
  "Ordered guide chapters, derived from ridley.manual.structure."
  (mapv (fn [c] {:id    (:id c)
                 :file  (:file c)
                 :title (structure/chapter-title c structure/source-lang)})
        (structure/ordered-chapters)))

(defn draft-chapter
  "Return the draft chapter map for a given page id, or nil."
  [page-id]
  (structure/chapter-by-id page-id))

(defn chapter-title
  "Localized title for a draft chapter id, in `lang` (falling back to the
   source language). nil if the id is not a draft chapter."
  [page-id lang]
  (when-let [c (structure/chapter-by-id page-id)]
    (structure/chapter-title c lang)))

(defn draft-page?
  "True if the page id is a draft chapter rendered from markdown."
  [page-id]
  (some? (draft-chapter page-id)))

(defn draft-chapter-ids
  "Page ids of all draft chapters in declaration order."
  []
  (mapv :id (structure/ordered-chapters)))

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
  "Fetch the guide Markdown at `url` (relative to the public web root).
   Returns a promise resolving to the text. Cached by url."
  [url]
  (if-let [cached (get @chapter-cache url)]
    (js/Promise.resolve cached)
    (-> (js/fetch url)
        (.then (fn [resp]
                 (if (.-ok resp)
                   (.text resp)
                   (throw (js/Error. (str "Failed to fetch " url
                                          " (HTTP " (.-status resp) ")"))))))
        (.then (fn [text]
                 (swap! chapter-cache assoc url text)
                 text)))))

;; ── Example-source marker extraction ──────────────────────────
;;
;; Two accepted formats:
;;
;; 1) Single-line marker preceding a ```clojure``` block. The fence's
;;    visible code is also what Run executes — one combined panel.
;;
;;        <!-- example-source: some-id -->
;;        ```clojure
;;        (code)
;;        ```
;;
;; 2) Multi-line marker (anywhere). The marker stands on its own and
;;    becomes a separate runnable panel; its code body is what Run executes
;;    and what's shown in the CodeMirror view. Any preceding ```clojure```
;;    fence stays illustrative-only.
;;
;;        ```clojure
;;        (def m (box 20))           <- illustrative panel (no Run)
;;        (mesh-diagnose m) ;; => {:n-verts 8 ...}
;;        ```
;;
;;        <!-- example-source: some-id   <- runnable panel
;;        (register m (box 20))
;;        (println (mesh-diagnose m))
;;        -->
;;
;; Both forms accept optional attributes on the marker's first line, after
;; the id:
;;
;;   :no-run            Hide the Run button (only Edit is shown).
;;   :warning <value>   Show a small badge next to the buttons. Known values:
;;                      `slow`, `desktop-only`.
;;
;;        <!-- example-source: some-id :no-run :warning slow -->
;;
;; The single-line marker is replaced by an empty sentinel <div> that
;; flags the following fence as runnable. The multi-line marker is
;; replaced by a self-contained sentinel <div> carrying the code in
;; data-runnable-code; enhance-code-blocks! turns it into an independent
;; CodeMirror panel.

(defn- escape-html-attr
  "HTML-escape a string for safe inclusion in an attribute value, encoding
   newlines so the attribute stays on one line in the serialized HTML."
  [s]
  (-> s
      (.replace (js/RegExp. "&" "g") "&amp;")
      (.replace (js/RegExp. "<" "g") "&lt;")
      (.replace (js/RegExp. ">" "g") "&gt;")
      (.replace (js/RegExp. "\"" "g") "&quot;")
      (.replace (js/RegExp. "\n" "g") "&#10;")))

(defn- parse-marker-attrs
  "Parse the optional attribute string that follows the id on a marker's
   first line. Returns {:no-run? bool :warning string-or-nil}."
  [attr-str]
  (let [trimmed (when attr-str (.trim attr-str))
        tokens (if (and trimmed (pos? (.-length trimmed)))
                 (vec (.split trimmed (js/RegExp. "\\s+")))
                 [])]
    (loop [t tokens acc {:no-run? false :warning nil}]
      (cond
        (empty? t) acc
        (= (first t) ":no-run")
        (recur (subvec t 1) (assoc acc :no-run? true))
        (and (= (first t) ":warning") (>= (count t) 2))
        (recur (subvec t 2) (assoc acc :warning (second t)))
        :else
        (recur (subvec t 1) acc)))))

(defn- attr-fragment
  "Emit ` data-foo=\"bar\"` only when val is truthy. Returns empty string otherwise."
  [name val]
  (if val (str " " name "=\"" (escape-html-attr (str val)) "\"") ""))

(defn- replace-example-markers
  "Replace each <!-- example-source: ... --> marker with a block-level sentinel
   div that survives Markdown parsing. Handles both the single-line marker
   (id only, flags the following fence) and the multi-line marker (id + code
   body, becomes a self-contained runnable panel). Marker attributes
   (`:no-run`, `:warning <value>`) flow into data-* attributes on the sentinel.

   Order matters: single-line markers are processed first so that their
   closing `-->` is consumed before the (greedy) multi-line scan runs.
   Otherwise a single-line marker followed somewhere later by a multi-line
   marker would be absorbed into one giant block ending at the multi-line's
   own `-->`."
  [markdown]
  (let [single-pat (js/RegExp. "<!--[ \\t]*example-source:[ \\t]*(\\S+)([^\\n]*?)-->" "g")
        multi-pat  (js/RegExp. "<!--[ \\t]*example-source:[ \\t]*(\\S+)([^\\n]*)\\n([\\s\\S]*?)\\n[ \\t]*-->" "g")
        with-single (.replace markdown single-pat
                              (fn [_match id attrs]
                                (let [{:keys [no-run? warning]} (parse-marker-attrs attrs)]
                                  (str "\n\n<div class=\"draft-example-source\" data-source-id=\""
                                       id "\""
                                       (attr-fragment "data-no-run" (when no-run? "true"))
                                       (attr-fragment "data-warning" warning)
                                       "></div>\n\n"))))]
    (.replace with-single multi-pat
              (fn [_match id attrs code]
                (let [{:keys [no-run? warning]} (parse-marker-attrs attrs)]
                  (str "\n\n<div class=\"draft-runnable-panel\" data-source-id=\""
                       id "\" data-runnable-code=\""
                       (escape-html-attr code) "\""
                       (attr-fragment "data-no-run" (when no-run? "true"))
                       (attr-fragment "data-warning" warning)
                       "></div>\n\n"))))))

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
                        ;; Reference hover tooltips (with "open in manual") on the
                        ;; function names inside example code (shared with the editor).
                        cm/ridley-reference-tooltip
                        ;; Render tooltips into document.body so they aren't clipped
                        ;; by the small, overflow-bounded example code block.
                        (tooltips #js {:parent (.-body js/document) :position "fixed"})
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

(def ^:private warning-labels
  "Display text for known :warning values. Unknown values fall back to the
   raw string with a generic icon."
  {"slow"         {:icon "⏱" :text "Slow"          :title "L'esecuzione richiede diversi secondi"}
   "desktop-only" {:icon "🖥" :text "Desktop only" :title "Richiede l'app desktop"}})

(defn- make-warning-badge [warning]
  (let [{:keys [icon text title]} (get warning-labels warning
                                       {:icon "⚠" :text warning :title nil})
        badge (.createElement js/document "span")]
    (set! (.-className badge) (str "manual-example-warning manual-warning-" warning))
    (set! (.-textContent badge) (str icon " " text))
    (when title (set! (.-title badge) title))
    badge))

(defn- render-code-block!
  "Replace a <pre><code> element with a CodeMirror read-only view. If
   `example-code` is non-nil, append Edit (always) and Run (unless `:no-run?`)
   buttons that funnel `example-code` through the shared callbacks. A
   `:warning` string renders an informational badge after the buttons."
  [pre-el visible-code example-code {:keys [no-run? warning]}]
  (let [block (.createElement js/document "div")
        code-container (.createElement js/document "div")]
    (set! (.-className block) "manual-example")
    (set! (.-className code-container) "manual-example-code")
    (.appendChild block code-container)
    (create-code-view code-container visible-code)
    (when example-code
      (let [buttons (.createElement js/document "div")]
        (set! (.-className buttons) "manual-example-buttons")
        (when-not no-run?
          (.appendChild buttons
                        (make-button "Run" "manual-btn manual-btn-run"
                                     (fn [_]
                                       (when-let [on-run (:on-run @callbacks)]
                                         (on-run example-code))))))
        (.appendChild buttons
                      (make-button "Edit" "manual-btn manual-btn-copy"
                                   (fn [_]
                                     (when-let [on-copy (:on-copy @callbacks)]
                                       (on-copy example-code)))))
        (when warning
          (.appendChild buttons (make-warning-badge warning)))
        (.appendChild block buttons)))
    (.replaceWith pre-el block)))

;; ── TOC (local table of contents) ─────────────────────────────

(defn- slugify
  "Turn heading text into a URL-safe id fragment."
  [text]
  (-> text
      (.toLowerCase)
      (.replace (js/RegExp. "[^\\p{Letter}\\p{Number}\\s-]" "gu") "")
      (.replace (js/RegExp. "\\s+" "g") "-")
      (.replace (js/RegExp. "-+" "g") "-")
      (.replace (js/RegExp. "^-|-$" "g") "")))

(defn- ensure-unique-id!
  "Assign a slug-based id to `el` if it doesn't already have one, avoiding
   collisions against `used` (an atom holding a set of ids already assigned)."
  [el used]
  (let [existing (.getAttribute el "id")]
    (if (and existing (not (empty? existing)))
      (do (swap! used conj existing) existing)
      (let [base (slugify (.-textContent el))
            base (if (empty? base) "section" base)]
        (loop [candidate base
               n 2]
          (if (contains? @used candidate)
            (recur (str base "-" n) (inc n))
            (do (.setAttribute el "id" candidate)
                (swap! used conj candidate)
                candidate)))))))

(defn- collect-headings!
  "Walk h2/h3 inside `root` in document order. Assigns ids when missing and
   returns a vector of {:level :text :id} maps."
  [root]
  (let [used (atom #{})
        nodes (array-seq (.querySelectorAll root "h2, h3"))]
    (vec
     (for [el nodes]
       (let [tag (.-tagName el)
             level (if (= tag "H2") 2 3)
             text (.-textContent el)
             id (ensure-unique-id! el used)]
         {:level level :text text :id id})))))

(defonce ^:private toc-state (atom {:popup nil :doc-handler nil}))

(defn- close-toc-popup! []
  (let [{:keys [popup doc-handler]} @toc-state]
    (when popup
      (.remove popup))
    (when doc-handler
      (.removeEventListener js/document "click" doc-handler true)
      (.removeEventListener js/document "keydown" doc-handler))
    (reset! toc-state {:popup nil :doc-handler nil})))

(defn- scroll-to-heading! [content-root id]
  (when-let [target (or (.getElementById js/document id)
                        (.querySelector content-root (str "[id='" id "']")))]
    (.scrollIntoView target #js {:behavior "smooth" :block "start"})))

(defn- build-toc-popup [headings content-root]
  (let [popup (.createElement js/document "div")
        ul (.createElement js/document "ul")]
    (set! (.-className popup) "manual-toc-popup")
    (set! (.-className ul) "manual-toc-list")
    (doseq [{:keys [level text id]} headings]
      (let [li (.createElement js/document "li")
            a (.createElement js/document "a")]
        (set! (.-className li) (str "manual-toc-item manual-toc-h" level))
        (set! (.-href a) (str "#" id))
        (set! (.-textContent a) text)
        (.addEventListener a "click"
                           (fn [ev]
                             (.preventDefault ev)
                             (close-toc-popup!)
                             (scroll-to-heading! content-root id)))
        (.appendChild li a)
        (.appendChild ul li)))
    (.appendChild popup ul)
    popup))

(defn- open-toc-popup! [button-el wrapper-el headings content-root]
  (close-toc-popup!)
  (let [popup (build-toc-popup headings content-root)
        handler (fn [ev]
                  (cond
                    (= (.-type ev) "keydown")
                    (when (= (.-key ev) "Escape") (close-toc-popup!))
                    :else
                    (when-not (.contains popup (.-target ev))
                      (when-not (.contains button-el (.-target ev))
                        (close-toc-popup!)))))]
    (.appendChild wrapper-el popup)
    (reset! toc-state {:popup popup :doc-handler handler})
    ;; Defer listener attach so the opening click doesn't immediately close it.
    (js/setTimeout
     (fn []
       (.addEventListener js/document "click" handler true)
       (.addEventListener js/document "keydown" handler))
     0)))

(defn- inject-toc-button!
  "If `nav-el` is non-nil and `headings` is non-empty, prepend a TOC button
   into the nav. The button toggles a popup listing the headings."
  [nav-el headings content-root]
  (when (and nav-el (seq headings))
    (let [wrapper (.createElement js/document "div")
          btn (.createElement js/document "button")]
      (set! (.-className wrapper) "manual-toc-wrapper")
      (set! (.-className btn) "manual-btn manual-btn-toc")
      (set! (.-textContent btn) "§")
      (set! (.-title btn) "Sezioni del capitolo")
      (.addEventListener btn "click"
                         (fn [ev]
                           (.stopPropagation ev)
                           (if (:popup @toc-state)
                             (close-toc-popup!)
                             (open-toc-popup! btn wrapper headings content-root))))
      (.appendChild wrapper btn)
      ;; Insert as the first child so it sits to the left of back/up/lang/close.
      (if-let [first-child (.-firstChild nav-el)]
        (.insertBefore nav-el wrapper first-child)
        (.appendChild nav-el wrapper)))))

(defn- read-marker-opts
  "Pull :no-run? and :warning off a sentinel div (data-no-run, data-warning)."
  [el]
  {:no-run? (= (.getAttribute el "data-no-run") "true")
   :warning (.getAttribute el "data-warning")})

(defn- enhance-code-blocks!
  "Walk the rendered manual content and turn the two example-source sentinel
   shapes into CodeMirror panels:

   - <div class='draft-example-source'> placed before a <pre><code> fence
     marks the fence as runnable (the fence's visible code is also what
     Run executes).
   - <div class='draft-runnable-panel' data-runnable-code='...'> becomes a
     self-contained CodeMirror panel with Run+Edit buttons; the code in the
     attribute is both visible and runnable.

   Regular fences with no adjacent marker render as illustrative."
  [root]
  ;; Pass 1: rewrite each standalone runnable-panel sentinel as a CodeMirror panel.
  (doseq [panel (vec (array-seq (.querySelectorAll root ".draft-runnable-panel")))]
    (let [code (.getAttribute panel "data-runnable-code")
          opts (read-marker-opts panel)
          ;; render-code-block! expects a <pre> element to replace; build a
          ;; placeholder so we can reuse the same code path.
          placeholder (.createElement js/document "pre")]
      (.replaceWith panel placeholder)
      (render-code-block! placeholder code code opts)))
  ;; Pass 2: turn each <pre><code class='language-clojure'> into a CodeMirror
  ;; view, attaching Run+Edit if preceded by a single-line example-source
  ;; sentinel.
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
            opts (if marker (read-marker-opts marker) {})
            runnable? (some? marker)]
        (when marker (.remove marker))
        (when clojure?
          (render-code-block! pre visible (when runnable? visible) opts)))))
  ;; Clean up any leftover orphan markers (paranoia).
  (doseq [orphan (array-seq (.querySelectorAll root ".draft-example-source, .draft-runnable-panel"))]
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

(defn- strip-frontmatter
  "Drop a leading YAML frontmatter block (--- … ---). Reference cards carry
   one; guides do not. No-op when the text doesn't start with a fence."
  [md]
  (.replace md (js/RegExp. "^---\\n[\\s\\S]*?\\n---\\n" "") ""))

(defn- strip-example-shortcodes
  "Remove leftover {{example: id}} placeholder lines. Superseded by inline
   example-source blocks (plan §2.4); the real code lives in those, so the
   shortcode line would otherwise render as literal text."
  [md]
  (.replace md (js/RegExp. "^[ \\t]*\\{\\{example:[^}]*\\}\\}[ \\t]*$" "gm") ""))

(defn- render-md-text!
  "Shared pipeline: example-source markers → strip comments → marked → inject
   CodeMirror panels, auto-link Reference symbols, and (optionally) a TOC button
   into `nav-el`. `current-symbol` (a card's own name) is left unlinked."
  ([container-el raw-md nav-el]
   (render-md-text! container-el raw-md nav-el nil))
  ([container-el raw-md nav-el current-symbol]
   (let [with-sentinels (replace-example-markers raw-md)
         cleaned (strip-remaining-comments with-sentinels)
         html (.parse marked cleaned)]
     (set! (.-innerHTML container-el) html)
     (set! (.-className container-el)
           (str (or (.-className container-el) "") " manual-draft-content"))
     (enhance-code-blocks! container-el)
     (autolink-references! container-el current-symbol)
     (wire-reference-links! container-el)
     (let [headings (collect-headings! container-el)]
       (inject-toc-button! nav-el headings container-el)))))

(defn render-chapter!
  "Render the draft chapter identified by `page-id` into `container-el`.
   When `nav-el` is provided, a TOC button is injected into it after parsing.
   Returns the chapter map (or nil if page-id is not a draft chapter)."
  ([container-el page-id]
   (render-chapter! container-el page-id nil structure/source-lang))
  ([container-el page-id nav-el]
   (render-chapter! container-el page-id nav-el structure/source-lang))
  ([container-el page-id nav-el lang]
   (close-toc-popup!)
   (when-let [chap (draft-chapter page-id)]
     (show-loading! container-el)
     (-> (fetch-markdown (structure/chapter-url chap lang))
         (.then (fn [raw-md] (render-md-text! container-el raw-md nav-el)))
         (.catch (fn [err]
                   (show-error! container-el
                                (str "Errore caricamento capitolo: " (.-message err))))))
     chap)))

(defn render-card!
  "Fetch a Reference card Markdown at `url` and render it like a mini-guide:
   full body with runnable example-source panels. Strips the YAML frontmatter
   and stale {{example}} shortcodes first."
  [container-el url]
  (show-loading! container-el)
  (-> (fetch-markdown url)
      (.then (fn [raw-md]
               (let [md (-> raw-md strip-frontmatter strip-example-shortcodes)]
                 (render-md-text! container-el md nil (symbol-for-card-url url)))))
      (.catch (fn [err]
                (show-error! container-el
                             (str "Errore caricamento scheda: " (.-message err)))))))
