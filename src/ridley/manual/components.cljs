(ns ridley.manual.components
  "Manual UI components - renders the manual panel."
  (:require [ridley.manual.core :as manual]
            [ridley.manual.content :as content]
            ["@codemirror/view" :as view :refer [EditorView]]
            ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/language" :refer [syntaxHighlighting HighlightStyle]]
            ["@lezer/highlight" :refer [tags]]
            ["@nextjournal/clojure-mode" :as clojure-mode]))

;; Callbacks - set by core.cljs
(defonce ^:private callbacks (atom {:on-run nil
                                    :on-copy nil}))

(defn set-callbacks!
  "Set the callbacks for Run and Copy buttons.
   on-run: (fn [code]) - called when Run is clicked
   on-copy: (fn [code]) - called when Copy is clicked"
  [{:keys [on-run on-copy]}]
  (reset! callbacks {:on-run on-run :on-copy on-copy}))

;; Create highlight style for code blocks (same as main editor)
(defn- create-highlight-style []
  (.define HighlightStyle
    #js [#js {:tag (.-lineComment tags) :color "#6a9955" :fontStyle "italic"}
         #js {:tag (.-atom tags) :color "#4fc1ff"}
         #js {:tag (.-string tags) :color "#ce9178"}
         #js {:tag (.-number tags) :color "#b5cea8"}
         #js {:tag (.-keyword tags) :color "#c586c0"}
         #js {:tag (.definition tags (.-variableName tags)) :color "#dcdcaa"}
         #js {:tag (.-variableName tags) :color "#9cdcfe"}
         #js {:tag (.-bool tags) :color "#569cd6"}
         #js {:tag (.-null tags) :color "#569cd6"}
         #js {:tag (.-punctuation tags) :color "#d4d4d4"}
         #js {:tag (.-bracket tags) :color "#d4d4d4"}]))

;; Create read-only theme
(defn- create-readonly-theme []
  (.theme EditorView
    #js {"&" #js {:backgroundColor "#1a1a2e"
                  :fontSize "13px"}
         ".cm-scroller" #js {:fontFamily "'SF Mono', 'Monaco', 'Menlo', 'Consolas', monospace"
                             :lineHeight "1.5"
                             :overflow "auto"}
         ".cm-content" #js {:padding "12px"
                            :caretColor "transparent"}
         ".cm-line" #js {:padding "0 4px"}
         "&.cm-focused" #js {:outline "none"}
         ".cm-gutters" #js {:display "none"}
         ".cm-cursor" #js {:display "none"}}
    #js {:dark true}))

;; Create a read-only CodeMirror view for code display
(defn- create-code-view [parent code]
  (let [extensions #js [(syntaxHighlighting (create-highlight-style))
                        clojure-mode/default_extensions
                        (create-readonly-theme)
                        (.of (.-editable EditorView) false)]
        state (.create EditorState
                #js {:doc code
                     :extensions extensions})]
    (EditorView. #js {:state state :parent parent})))

;; Render a single example block
(defn- render-example [container example]
  (let [block (.createElement js/document "div")
        header (.createElement js/document "div")
        code-container (.createElement js/document "div")
        buttons (.createElement js/document "div")
        run-btn (.createElement js/document "button")
        copy-btn (.createElement js/document "button")
        description-el (when (:description example)
                         (.createElement js/document "p"))
        code (:code example)]
    ;; Setup block
    (set! (.-className block) "manual-example")
    ;; Header with caption
    (set! (.-className header) "manual-example-header")
    (set! (.-textContent header) (or (:caption example) "Example"))
    (.appendChild block header)
    ;; Code container
    (set! (.-className code-container) "manual-example-code")
    (.appendChild block code-container)
    ;; Create CodeMirror view
    (create-code-view code-container code)
    ;; Buttons
    (set! (.-className buttons) "manual-example-buttons")
    (set! (.-className run-btn) "manual-btn manual-btn-run")
    (set! (.-textContent run-btn) "Run")
    (set! (.-className copy-btn) "manual-btn manual-btn-copy")
    (set! (.-textContent copy-btn) "Edit")
    ;; Button handlers
    (.addEventListener run-btn "click"
      (fn [_]
        (when-let [on-run (:on-run @callbacks)]
          (on-run code))))
    (.addEventListener copy-btn "click"
      (fn [_]
        (when-let [on-copy (:on-copy @callbacks)]
          (on-copy code))))
    (when-not (:no-run example)
      (.appendChild buttons run-btn))
    (.appendChild buttons copy-btn)
    (.appendChild block buttons)
    ;; Description
    (when description-el
      (set! (.-className description-el) "manual-example-description")
      (set! (.-textContent description-el) (:description example))
      (.appendChild block description-el))
    (.appendChild container block)))

;; Render page content (handles simple markdown-like formatting)
(defn- render-content [container content]
  (let [;; Split by code blocks
        parts (.split content #"```(?:\w*\n)?")
        in-code (atom false)]
    (doseq [part parts]
      (if @in-code
        ;; Code block
        (let [pre (.createElement js/document "pre")
              code-el (.createElement js/document "code")]
          (set! (.-className pre) "manual-code-block")
          (set! (.-textContent code-el) (.trim part))
          (.appendChild pre code-el)
          (.appendChild container pre))
        ;; Regular text - split by paragraphs (double newline)
        (let [paragraphs (.split part #"\n\n+")]
          (doseq [para paragraphs]
            (when (seq (.trim para))
              ;; Check if this is a list (lines starting with -)
              (if (re-find #"(?m)^- " para)
                ;; It's a list
                (let [ul (.createElement js/document "ul")
                      items (->> (.split para #"\n")
                                 (filter #(re-find #"^- " %))
                                 (map #(.replace % #"^- " "")))]
                  (set! (.-className ul) "manual-list")
                  (doseq [item items]
                    (let [li (.createElement js/document "li")]
                      ;; Apply inline formatting to list items
                      (set! (.-innerHTML li)
                            (-> item
                                (.replace (js/RegExp. "\\*\\*([^*]+)\\*\\*" "g") "<strong>$1</strong>")
                                (.replace (js/RegExp. "`([^`]+)`" "g") "<code>$1</code>")
                                (.replace (js/RegExp. "\\[([^\\]]+)\\]\\(([^)]+)\\)" "g") "<a href=\"$2\" target=\"_blank\">$1</a>")))
                      (.appendChild ul li)))
                  (.appendChild container ul))
                ;; Regular paragraph
                (let [p (.createElement js/document "p")]
                  (set! (.-className p) "manual-paragraph")
                  ;; Handle bold (**text**) and inline code (`code`)
                  ;; Use JS RegExp with global flag for multiple replacements
                  (set! (.-innerHTML p)
                        (-> para
                            (.replace (js/RegExp. "\\*\\*([^*]+)\\*\\*" "g") "<strong>$1</strong>")
                            (.replace (js/RegExp. "`([^`]+)`" "g") "<code>$1</code>")
                            (.replace (js/RegExp. "\\[([^\\]]+)\\]\\(([^)]+)\\)" "g") "<a href=\"$2\" target=\"_blank\">$1</a>")))
                  (.appendChild container p)))))))
      (swap! in-code not))))

;; Render the "See Also" section
(defn- render-see-also [container page-id lang]
  (when-let [see-also-ids (content/get-see-also page-id)]
    (let [section (.createElement js/document "div")
          title (.createElement js/document "h3")
          links (.createElement js/document "div")]
      (set! (.-className section) "manual-see-also")
      (set! (.-className title) "manual-see-also-title")
      (set! (.-textContent title) (if (= lang :en) "See Also" "Vedi Anche"))
      (.appendChild section title)
      (set! (.-className links) "manual-see-also-links")
      (doseq [link-id see-also-ids]
        (when-let [link-data (content/get-page link-id lang)]
          (let [link (.createElement js/document "a")]
            (set! (.-className link) "manual-see-also-link")
            (set! (.-textContent link) (:title link-data))
            (set! (.-href link) "#")
            (.addEventListener link "click"
              (fn [e]
                (.preventDefault e)
                (manual/navigate-to! link-id)))
            (.appendChild links link))))
      (.appendChild section links)
      (.appendChild container section))))

;; Render the Table of Contents page
(defn- render-toc [container lang]
  (let [sections (content/get-toc-structure)]
    ;; Clear container
    (set! (.-innerHTML container) "")
    ;; Header
    (let [header (.createElement js/document "div")
          title (.createElement js/document "h1")
          back-btn (.createElement js/document "button")
          lang-btn (.createElement js/document "button")
          close-btn (.createElement js/document "button")
          nav (.createElement js/document "div")]
      (set! (.-className header) "manual-header")
      ;; Title
      (set! (.-className title) "manual-title")
      (set! (.-textContent title) (if (= lang :en) "Table of Contents" "Indice"))
      (.appendChild header title)
      ;; Nav buttons
      (set! (.-className nav) "manual-nav")
      ;; Back button (if there's history)
      (when (manual/has-history?)
        (set! (.-className back-btn) "manual-btn manual-btn-back")
        (set! (.-textContent back-btn) "←")
        (set! (.-title back-btn) (if (= lang :en) "Back" "Indietro"))
        (.addEventListener back-btn "click" (fn [_] (manual/go-back!)))
        (.appendChild nav back-btn))
      ;; Language toggle
      (set! (.-className lang-btn) "manual-btn manual-btn-lang")
      (set! (.-textContent lang-btn) (if (= lang :en) "IT" "EN"))
      (set! (.-title lang-btn) "Switch language")
      (.addEventListener lang-btn "click" (fn [_] (manual/toggle-lang!)))
      (.appendChild nav lang-btn)
      ;; Close button
      (set! (.-className close-btn) "manual-btn manual-btn-close")
      (set! (.-textContent close-btn) "×")
      (set! (.-title close-btn) "Close manual")
      (.addEventListener close-btn "click" (fn [_] (manual/close-manual!)))
      (.appendChild nav close-btn)
      (.appendChild header nav)
      (.appendChild container header))
    ;; TOC content
    (let [toc-div (.createElement js/document "div")]
      (set! (.-className toc-div) "manual-toc")
      (doseq [section sections]
        (let [section-div (.createElement js/document "div")
              section-title (.createElement js/document "h2")
              pages-list (.createElement js/document "ul")]
          (set! (.-className section-div) "manual-toc-section")
          (set! (.-className section-title) "manual-toc-section-title")
          (set! (.-textContent section-title) (content/get-section-title (:id section) lang))
          (.appendChild section-div section-title)
          (set! (.-className pages-list) "manual-toc-pages")
          (doseq [page (:pages section)]
            (let [page-item (.createElement js/document "li")
                  page-link (.createElement js/document "a")
                  page-data (content/get-page (:id page) lang)]
              (set! (.-className page-link) "manual-toc-link")
              (set! (.-textContent page-link) (:title page-data))
              (set! (.-href page-link) "#")
              (.addEventListener page-link "click"
                (fn [e]
                  (.preventDefault e)
                  (manual/navigate-to! (:id page))))
              (.appendChild page-item page-link)
              (.appendChild pages-list page-item)))
          (.appendChild section-div pages-list)
          (.appendChild toc-div section-div)))
      (.appendChild container toc-div))))

;; Main render function - renders the entire manual panel
(defn render-manual-panel
  "Render the manual panel into the given container element.
   Returns a cleanup function."
  [container]
  (let [current-page (manual/get-current-page)
        lang (manual/get-lang)]
    ;; Handle TOC page specially
    (if (content/toc-page? current-page)
      (render-toc container lang)
      ;; Regular page rendering
      (when-let [page-data (manual/get-page-data)]
        ;; Clear container
        (set! (.-innerHTML container) "")
        ;; Header
        (let [header (.createElement js/document "div")
              title (.createElement js/document "h1")
              back-btn (.createElement js/document "button")
              up-btn (.createElement js/document "button")
              lang-btn (.createElement js/document "button")
              close-btn (.createElement js/document "button")
              nav (.createElement js/document "div")
              up-dest (content/get-up-destination current-page)]
          (set! (.-className header) "manual-header")
          ;; Title
          (set! (.-className title) "manual-title")
          (set! (.-textContent title) (:title page-data))
          (.appendChild header title)
          ;; Nav buttons
          (set! (.-className nav) "manual-nav")
          ;; Back button (if there's history)
          (when (manual/has-history?)
            (set! (.-className back-btn) "manual-btn manual-btn-back")
            (set! (.-textContent back-btn) "←")
            (set! (.-title back-btn) (if (= lang :en) "Back" "Indietro"))
            (.addEventListener back-btn "click" (fn [_] (manual/go-back!)))
            (.appendChild nav back-btn))
          ;; Up button (if there's a destination)
          (when up-dest
            (set! (.-className up-btn) "manual-btn manual-btn-up")
            (set! (.-textContent up-btn) "↑")
            (set! (.-title up-btn) (if (= lang :en) "Table of Contents" "Indice"))
            (.addEventListener up-btn "click" (fn [_] (manual/navigate-to! up-dest)))
            (.appendChild nav up-btn))
          ;; Language toggle
          (set! (.-className lang-btn) "manual-btn manual-btn-lang")
          (set! (.-textContent lang-btn) (if (= lang :en) "IT" "EN"))
          (set! (.-title lang-btn) "Switch language")
          (.addEventListener lang-btn "click" (fn [_] (manual/toggle-lang!)))
          (.appendChild nav lang-btn)
          ;; Close button
          (set! (.-className close-btn) "manual-btn manual-btn-close")
          (set! (.-textContent close-btn) "×")
          (set! (.-title close-btn) "Close manual")
          (.addEventListener close-btn "click" (fn [_] (manual/close-manual!)))
          (.appendChild nav close-btn)
          (.appendChild header nav)
          (.appendChild container header))
        ;; Content
        (let [content-div (.createElement js/document "div")]
          (set! (.-className content-div) "manual-content")
          (render-content content-div (:content page-data))
          (.appendChild container content-div))
        ;; Examples
        (when (seq (:examples page-data))
          (let [examples-div (.createElement js/document "div")]
            (set! (.-className examples-div) "manual-examples")
            (doseq [example (:examples page-data)]
              (render-example examples-div example))
            (.appendChild container examples-div)))
        ;; See Also links
        (render-see-also container current-page lang)
        ;; Page navigation (prev/next)
        (let [page-nav (.createElement js/document "div")
              prev-page (content/get-adjacent-page (:id page-data) :prev)
              next-page (content/get-adjacent-page (:id page-data) :next)]
          (set! (.-className page-nav) "manual-page-nav")
          (when prev-page
            (let [prev-btn (.createElement js/document "button")
                  prev-data (content/get-page prev-page lang)]
              (set! (.-className prev-btn) "manual-btn manual-btn-nav")
              (set! (.-textContent prev-btn) (str "← " (:title prev-data)))
              (.addEventListener prev-btn "click"
                (fn [_] (manual/navigate-to! prev-page)))
              (.appendChild page-nav prev-btn)))
          (when next-page
            (let [next-btn (.createElement js/document "button")
                  next-data (content/get-page next-page lang)]
              (set! (.-className next-btn) "manual-btn manual-btn-nav manual-btn-next")
              (set! (.-textContent next-btn) (str (:title next-data) " →"))
              (.addEventListener next-btn "click"
                (fn [_] (manual/navigate-to! next-page)))
              (.appendChild page-nav next-btn)))
          (.appendChild container page-nav))))))

;; Create the manual panel element
(defn create-manual-panel
  "Create the manual panel DOM element.
   Returns {:element el :cleanup fn}."
  []
  (let [panel (.createElement js/document "div")]
    (set! (.-id panel) "manual-panel")
    (set! (.-className panel) "manual-panel")
    {:element panel
     :render (fn [] (render-manual-panel panel))}))
