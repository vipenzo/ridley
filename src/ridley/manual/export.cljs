(ns ridley.manual.export
  "Generate markdown manual files from the online manual content.
   Evaluates each example, captures a screenshot, and produces
   Manual_en.md and Manuale_it.md with inline base64 images.

   Note: does NOT depend on ridley.editor.repl to avoid circular deps.
   Uses js/eval to call reset-ctx and evaluate at runtime.
   Processing is async (setTimeout between examples) to avoid browser freeze."
  (:require [ridley.manual.content :as content]
            [ridley.viewport.capture :as capture]
            [ridley.scene.registry :as registry]))

(defn- reset-scene! []
  (js/eval "ridley.editor.repl.reset_ctx_BANG_()")
  (registry/clear-all!))

(defn- evaluate-code
  "Evaluate Ridley code via the SCI evaluator (accessed via js/eval to avoid circular dep)."
  [code]
  (js/eval (str "ridley.editor.repl.evaluate(null, " (pr-str code) ")")))

(defn- evaluate-example
  "Evaluate an example code string and return the rendered image as data URL.
   Returns nil if evaluation or rendering fails."
  [code]
  (try
    (reset-scene!)
    (let [result (evaluate-code code)]
      (when-not (.-error result)
        (try
          (capture/render-view :perspective :width 600 :height 400)
          (catch :default e
            (js/console.warn "render-view failed for example:" e)
            nil))))
    (catch :default e
      (js/console.warn "evaluate failed:" e)
      nil)))

(defn- get-i18n
  "Get i18n content for a page/section in a given language."
  [lang section-or-page-id]
  (or (get-in content/i18n [lang :pages section-or-page-id])
      (get-in content/i18n [lang :sections section-or-page-id])))

(defn- flatten-examples
  "Flatten the manual structure into a sequence of
   {:section-id :page-id :example example :section-i18n :page-i18n}
   Also includes page headers (with :header? true, no :example)."
  [lang]
  (let [sections (get-in content/structure [:sections])
        items (atom [])]
    (doseq [section sections]
      ;; Section header marker
      (swap! items conj {:type :section-header
                         :id (:id section)
                         :i18n (get-i18n lang (:id section))})
      (doseq [page (:pages section)]
        ;; Page header + content
        (swap! items conj {:type :page-header
                           :id (:id page)
                           :i18n (get-i18n lang (:id page))})
        ;; Examples
        (doseq [example (:examples page)]
          (swap! items conj {:type :example
                             :id (:id example)
                             :code (:code example)
                             :page-id (:id page)
                             :page-i18n (get-i18n lang (:id page))}))))
    @items))

(defn- download-text
  "Trigger a browser download of a text string as a file."
  [text filename]
  (let [blob (js/Blob. #js [text] #js {:type "text/markdown;charset=utf-8"})
        url (.createObjectURL js/URL blob)
        link (.createElement js/document "a")]
    (set! (.-href link) url)
    (set! (.-download link) filename)
    (.click link)
    (.revokeObjectURL js/URL url)
    (js/console.log (str "Downloaded " filename))))

(defn- process-items-async
  "Process manual items one at a time with setTimeout between each.
   Calls done-fn with the accumulated markdown string when complete."
  [items lang done-fn]
  (let [sb (atom [(str "# " (if (= lang :en) "Ridley Manual" "Manuale Ridley")) "\n\n"])
        total-examples (count (filter #(= (:type %) :example) items))
        example-idx (atom 0)
        idx (atom 0)]
    (letfn [(process-next []
              (if (>= @idx (count items))
                ;; Done
                (done-fn (apply str @sb))
                ;; Process current item
                (let [item (nth items @idx)]
                  (swap! idx inc)
                  (case (:type item)
                    :section-header
                    (do (swap! sb conj (str "## " (or (:title (:i18n item))
                                                       (name (:id item))))
                                       "\n\n")
                        ;; Sections are fast, process next immediately
                        (js/setTimeout process-next 0))

                    :page-header
                    (do (swap! sb conj (str "### " (or (:title (:i18n item))
                                                        (name (:id item))))
                                       "\n\n")
                        ;; Page content
                        (when-let [c (:content (:i18n item))]
                          (swap! sb conj c "\n\n"))
                        (js/setTimeout process-next 0))

                    :example
                    (let [example-id (:id item)
                          code (:code item)
                          page-i18n (:page-i18n item)
                          example-i18n (get-in page-i18n [:examples example-id])
                          caption (:caption example-i18n)
                          description (:description example-i18n)]
                      (swap! example-idx inc)
                      (js/console.log (str "[" @example-idx "/" total-examples "] "
                                           (or caption (name example-id))))
                      ;; Caption
                      (when caption
                        (swap! sb conj (str "#### " caption) "\n\n"))
                      ;; Code block
                      (swap! sb conj "```clojure\n" code "\n```\n\n")
                      ;; Description
                      (when description
                        (swap! sb conj description "\n\n"))
                      ;; Screenshot
                      (let [img (evaluate-example code)]
                        (when img
                          (swap! sb conj (str "![" (or caption (name example-id))
                                              "](" img ")") "\n\n")))
                      ;; Page separator after last example? Add --- after each page
                      ;; Check if next item is not an example of same page
                      (let [next-item (when (< @idx (count items)) (nth items @idx))]
                        (when (or (nil? next-item)
                                  (not= (:type next-item) :example))
                          (swap! sb conj "---\n\n")))
                      ;; Delay before next to let browser breathe
                      (js/setTimeout process-next 50))))))]
      (process-next))))

(defn- generate-markdown-text
  "Generate markdown manual for a language WITHOUT screenshots (synchronous, fast).
   Returns the markdown string."
  [lang]
  (let [items (flatten-examples lang)
        sb (atom [(str "# " (if (= lang :en) "Ridley Manual" "Manuale Ridley")) "\n\n"])]
    (doseq [item items]
      (case (:type item)
        :section-header
        (swap! sb conj (str "## " (or (:title (:i18n item)) (name (:id item)))) "\n\n")

        :page-header
        (do (swap! sb conj (str "### " (or (:title (:i18n item)) (name (:id item)))) "\n\n")
            (when-let [c (:content (:i18n item))]
              (swap! sb conj c "\n\n")))

        :example
        (let [example-id (:id item)
              page-i18n (:page-i18n item)
              example-i18n (get-in page-i18n [:examples example-id])
              caption (:caption example-i18n)
              description (:description example-i18n)]
          (when caption
            (swap! sb conj (str "#### " caption) "\n\n"))
          (swap! sb conj "```clojure\n" (:code item) "\n```\n\n")
          (when description
            (swap! sb conj description "\n\n"))
          ;; Check if next item is not an example (end of page)
          (let [idx (.indexOf items item)
                next-item (when (< (inc idx) (count items)) (nth items (inc idx)))]
            (when (or (nil? next-item) (not= (:type next-item) :example))
              (swap! sb conj "---\n\n"))))))
    (apply str @sb)))

(defn ^:export generate-manual-en
  "Generate and download Manual_en.md (text only, no screenshots).
   Fast and synchronous."
  []
  (let [md (generate-markdown-text :en)]
    (download-text md "Manual_en.md")))

(defn ^:export generate-manual-it
  "Generate and download Manuale_it.md (text only, no screenshots).
   Fast and synchronous."
  []
  (let [md (generate-markdown-text :it)]
    (download-text md "Manuale_it.md")))

(defn ^:export generate-both
  "Generate and download both Manual_en.md and Manuale_it.md (text only)."
  []
  (generate-manual-en)
  (generate-manual-it))

(defn ^:export generate-manual-with-images
  "Generate manual WITH screenshots (async, slow).
   lang: :en or :it, filename: output filename."
  [lang filename]
  (js/console.log (str "Generating " filename " with screenshots..."))
  (let [items (flatten-examples lang)]
    (process-items-async items lang
      (fn [md]
        (download-text md filename)
        (js/console.log (str filename " complete!"))))))
