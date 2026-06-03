(ns ridley.manual.reference-browser
  "In-manual Reference browser: browse functions by category and search.

   Fed entirely by the build-time bundled indexes — no Markdown parsing at
   runtime (brief §6/§11). Shows the compact card: name, signature(s),
   description, status. Sections come from ridley.manual.structure
   (Functions + Clojure core; Internals stays hidden until it has cards)."
  (:require [clojure.string :as str]
            [ridley.manual.core :as manual]
            [ridley.manual.structure :as structure]
            [ridley.manual.draft-renderer :as draft]
            [ridley.manual.reference-index :as ref-index]
            [ridley.manual.clojure-core-index :as cc-index]))

;; ── Internal view state ───────────────────────────────────────
;; Local to the browser so category/search/selection don't round-trip
;; through manual-state (which drives whole-panel page navigation).

(def ^:private blank-state
  {:view :overview   ; :overview | :category | :detail
   :category nil      ; category slug or :clojure-core
   :query ""
   :selected nil      ; selected entry name
   :history []})      ; stack of prior {:view :category :selected :query} snapshots

(defonce ^:private state (atom blank-state))

;; Where the browser is mounted, so any nav handler can re-render the whole
;; browser (header included, so the ← button reflects the current history).
(defonce ^:private mount (atom {:container nil :lang nil}))

(declare render! go!)

(defn- rerender! []
  (let [{:keys [container lang]} @mount]
    (when container (render! container lang))))

(defn reset-view!
  "Reset to the category overview. Call when entering the Reference from the
   TOC so a fresh open starts at the top (a panel re-render — e.g. a language
   toggle — preserves the current view instead)."
  []
  (reset! state blank-state))

(defn- resolve-symbol
  "Resolve a link target to an indexed symbol name, or nil. Accepts a bare
   name, a ref:NAME pseudo-link, or a relative card file (…/foo.md) matched
   against the card paths."
  [target]
  (when (string? target)
    (let [t (str/trim target)
          t (if (str/starts-with? t "ref:") (subs t 4) t)]
      (cond
        (contains? ref-index/reference-index t) t
        (contains? cc-index/clojure-core-index t) t
        (str/ends-with? t ".md")
        (let [base (last (str/split t #"/"))]
          (some (fn [e] (when (str/ends-with? (str (:path e)) base) (:name e)))
                (vals ref-index/reference-index)))
        :else nil))))

(defn open-card!
  "Open the manual's Reference directly at a symbol's detail card (T-009).
   `target` may be a symbol name, a ref:NAME link, or a relative card .md
   path. Returns true if it resolved and opened, false otherwise so the
   caller can fall back to default link behaviour."
  [target]
  (if-let [nm (resolve-symbol target)]
    (do
      (if (and (manual/open?) (= :reference (manual/get-current-page)))
        ;; Already inside the open browser (e.g. a card 'See also' link): an
        ;; internal navigation, recorded in the in-Reference history.
        (go! {:view :detail :selected nm :query ""})
        ;; Entering from elsewhere (a guide link or the editor tooltip): start
        ;; a fresh browser state and navigate via the manual, so the page we
        ;; came from is pushed onto the manual history and ← returns to it.
        (do (reset! state (assoc blank-state :view :detail :selected nm))
            (when-not (manual/open?) (manual/open-manual!))
            (manual/navigate-to! :reference)))
      true)
    false))

;; ── Navigation (history for ←, containing level for ↑) ────────

(defn- snapshot [st]
  (select-keys st [:view :category :selected :query]))

(defn- go!
  "Navigate to a new view, pushing the current one onto the history stack."
  [partial-state]
  (swap! state (fn [st] (merge (update st :history conj (snapshot st)) partial-state)))
  (rerender!))

(defn- back!
  "← : return to the previous screen. Falls back to the manual's own history
   (e.g. the table of contents) once the in-Reference history is empty."
  []
  (if (seq (:history @state))
    (do (swap! state (fn [st]
                       (let [prev (peek (:history st))]
                         (merge st prev {:history (pop (:history st))}))))
        (rerender!))
    (manual/go-back!)))

(defn- entry-category [nm]
  (cond
    (contains? ref-index/reference-index nm) (:category (get ref-index/reference-index nm))
    (contains? cc-index/clojure-core-index nm) :clojure-core
    :else nil))

(defn- up!
  "↑ : go to the containing level. Card → its category; category → overview;
   overview (or active search) → leave the Reference for the table of contents."
  []
  (let [{:keys [view selected query]} @state]
    (cond
      (seq (str/trim query)) (go! {:view :overview :category nil :selected nil :query ""})
      (= view :detail) (if-let [cat (entry-category selected)]
                         (go! {:view :category :category cat :selected nil})
                         (go! {:view :overview :category nil :selected nil}))
      (= view :category) (go! {:view :overview :category nil})
      :else (manual/navigate-to! :toc))))

;; ── DOM helpers ───────────────────────────────────────────────

(defn- el [tag class text]
  (let [e (.createElement js/document tag)]
    (when class (set! (.-className e) class))
    (when text (set! (.-textContent e) text))
    e))

(defn- clear! [node] (set! (.-innerHTML node) ""))

;; ── Data access ───────────────────────────────────────────────

(defn- functions-in-category
  "Reference-index entries for a category slug, sorted by name."
  [slug]
  (->> (vals ref-index/reference-index)
       (filter #(= (:category %) slug))
       (sort-by :name)))

(defn- clojure-core-entries []
  (->> (vals cc-index/clojure-core-index)
       (sort-by :name)))

(defn- matches?
  "Case-insensitive query match over name, signature and description."
  [q entry]
  (let [hay (str/lower-case (str (:name entry) " "
                                 (:signature entry) " "
                                 (:description entry)))]
    (str/includes? hay q)))

;; ── Rendering: entry detail (compact card) ────────────────────

(defn- entry-by-name
  "Look up an entry by name in the function index, else Clojure core."
  [nm]
  (or (get ref-index/reference-index nm)
      (get cc-index/clojure-core-index nm)))

(defn- card-url
  "Served URL of a card's Markdown, derived from its index :path
   (docs/manual/reference/en/x.md → manual/reference/en/x.md)."
  [entry]
  (when-let [p (:path entry)]
    (str/replace p #"^docs/" "")))

(defn- render-compact! [body e]
  (let [card (el "div" "ref-card" nil)
        head (el "div" "ref-card-head" nil)
        name-el (el "h2" "ref-card-name" (:name e))]
    (.appendChild head name-el)
    (when-let [status (:status e)]
      (when (and (seq status) (not= status "stable"))
        (.appendChild head (el "span" "ref-card-status" status))))
    (.appendChild card head)
    (when-let [sig (:signature e)]
      (when (seq sig)
        (let [sig-box (el "pre" "ref-card-sig" nil)]
          (doseq [line (str/split-lines sig)]
            (.appendChild sig-box (el "code" nil line))
            (.appendChild sig-box (.createTextNode js/document "\n")))
          (.appendChild card sig-box))))
    (when-let [desc (:description e)]
      (when (seq desc)
        (.appendChild card (el "p" "ref-card-desc" desc))))
    (.appendChild body card)))

(defn- render-detail!
  "Detail view for an entry. Function cards (with a Markdown file) render in
   full via the guide pipeline — runnable examples included. Clojure core
   entries have no card file, so they fall back to the compact view.
   Does not clear `body`: the caller owns the container and the back button."
  [body lang nm]
  (if-let [e (entry-by-name nm)]
    (if-let [u (card-url e)]
      (let [card-div (el "div" "ref-card-full manual-content" nil)]
        (.appendChild body card-div)
        (draft/render-card! card-div u))
      (render-compact! body e))
    (.appendChild body (el "p" "ref-empty"
                           (if (= lang :it) "Voce non trovata." "Entry not found.")))))

;; ── Rendering: list of entries ────────────────────────────────

(defn- render-entry-list! [body lang entries on-pick]
  (if (seq entries)
    (let [ul (el "ul" "ref-list" nil)]
      (doseq [e entries]
        (let [li (el "li" "ref-list-item" nil)
              a (el "a" "ref-list-link" nil)
              nm (el "span" "ref-list-name" (:name e))
              sig (el "span" "ref-list-sig" (first (str/split-lines (str (:signature e)))))]
          (set! (.-href a) "#")
          (.appendChild a nm)
          (when (seq (.-textContent sig)) (.appendChild a sig))
          (.addEventListener a "click" (fn [ev] (.preventDefault ev) (on-pick (:name e))))
          (.appendChild li a)
          (.appendChild ul li)))
      (.appendChild body ul))
    (.appendChild body (el "p" "ref-empty"
                           (if (= lang :it) "Nessun risultato." "No results.")))))

;; ── Rendering: category overview ──────────────────────────────

(declare render-body!)

(defn- render-overview! [body lang]
  ;; Functions: one clickable row per category (with count)
  (let [fn-section (el "div" "ref-section" nil)]
    (.appendChild fn-section
                  (el "h2" "ref-section-title"
                      (let [s (some #(when (= (:id %) :functions) %)
                                    (structure/visible-reference-sections))]
                        (get-in s [:label lang] "Functions"))))
    (let [grid (el "div" "ref-cat-grid" nil)]
      (doseq [c (structure/ordered-function-categories)]
        (let [n (count (functions-in-category (:slug c)))]
          (when (pos? n)
            (let [btn (el "button" "ref-cat-btn" nil)]
              (.appendChild btn (el "span" "ref-cat-label" (structure/category-label (:slug c) lang)))
              (.appendChild btn (el "span" "ref-cat-count" (str n)))
              (.addEventListener btn "click"
                                 (fn [_] (go! {:view :category :category (:slug c)})))
              (.appendChild grid btn)))))
      (.appendChild fn-section grid))
    (.appendChild body fn-section))
  ;; Clojure core
  (when (some #(= (:id %) :clojure-core) (structure/visible-reference-sections))
    (let [cc-section (el "div" "ref-section" nil)]
      (.appendChild cc-section (el "h2" "ref-section-title" "Clojure core"))
      (let [btn (el "button" "ref-cat-btn" nil)]
        (.appendChild btn (el "span" "ref-cat-label" "Clojure core"))
        (.appendChild btn (el "span" "ref-cat-count" (str (count (clojure-core-entries)))))
        (.addEventListener btn "click"
                           (fn [_] (go! {:view :category :category :clojure-core})))
        (.appendChild cc-section btn))
      (.appendChild body cc-section))))

;; ── Body dispatch ─────────────────────────────────────────────

(defn- render-body! [body lang]
  (clear! body)
  (let [{:keys [view category query]} @state
        ;; Picking clears the search query so the detail view shows; the prior
        ;; query is preserved in history, so ← returns to the search results.
        pick (fn [nm] (go! {:view :detail :selected nm :query ""}))]
    (cond
      ;; Active search overrides view: flat results across everything
      (seq (str/trim query))
      (let [q (str/lower-case (str/trim query))
            hits (->> (concat (vals ref-index/reference-index) (clojure-core-entries))
                      (filter #(matches? q %))
                      (sort-by :name))]
        (.appendChild body (el "div" "ref-result-count"
                               (str (count hits) (if (= lang :it) " risultati" " results"))))
        (render-entry-list! body lang hits pick))

      ;; Up-navigation lives on the top-bar ↑ (contextual): a card or a
      ;; category list returns to the category overview, the overview to the
      ;; table of contents. No redundant in-body back button.
      (= view :detail)
      (render-detail! body lang (:selected @state))

      (= view :category)
      (do
        (let [label (if (= category :clojure-core) "Clojure core" (structure/category-label category lang))]
          (.appendChild body (el "h2" "ref-section-title" label)))
        (render-entry-list! body lang
                            (if (= category :clojure-core)
                              (clojure-core-entries)
                              (functions-in-category category))
                            pick))

      :else
      (render-overview! body lang))))

;; ── Public entry point ────────────────────────────────────────

(defn render!
  "Render the Reference browser into `container` for the given language.
   Builds a native-looking header plus a self-managed body."
  [container lang]
  (reset! mount {:container container :lang lang})
  (clear! container)
  ;; Header (mirrors components/render-toc markup so it looks native)
  (let [header (el "div" "manual-header" nil)
        title (el "h1" "manual-title" (if (= lang :it) "Reference" "Reference"))
        nav (el "div" "manual-nav" nil)
        back-btn (el "button" "manual-btn manual-btn-back" "←")
        up-btn (el "button" "manual-btn manual-btn-up" "↑")
        lang-btn (el "button" "manual-btn manual-btn-lang" (if (= lang :en) "IT" "EN"))
        close-btn (el "button" "manual-btn manual-btn-close" "×")]
    (.appendChild header title)
    ;; ← returns to the previous screen (in-Reference history, then the manual's).
    (set! (.-title back-btn) (if (= lang :en) "Back" "Indietro"))
    (.addEventListener back-btn "click" (fn [_] (back!)))
    (.appendChild nav back-btn)
    ;; ↑ goes up to the containing level: card → its category → overview → TOC.
    (set! (.-title up-btn) (if (= lang :en) "Up" "Su"))
    (.addEventListener up-btn "click" (fn [_] (up!)))
    (.appendChild nav up-btn)
    (.addEventListener lang-btn "click" (fn [_] (manual/toggle-lang!)))
    (.appendChild nav lang-btn)
    (set! (.-title close-btn) "Close manual")
    (.addEventListener close-btn "click" (fn [_] (manual/close-manual!)))
    (.appendChild nav close-btn)
    (.appendChild header nav)
    (.appendChild container header))
  ;; Search box
  (let [search-wrap (el "div" "ref-search-wrap" nil)
        input (el "input" "ref-search-input" nil)
        body (el "div" "manual-content ref-body" nil)]
    (set! (.-type input) "search")
    (set! (.-placeholder input) (if (= lang :it) "Cerca funzioni…" "Search functions…"))
    (set! (.-value input) (:query @state))
    (.addEventListener input "input"
                       (fn [ev] (swap! state assoc :query (.. ev -target -value))
                         (render-body! body lang)))
    (.appendChild search-wrap input)
    (.appendChild container search-wrap)
    (.appendChild container body)
    (render-body! body lang)))
