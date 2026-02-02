(ns ridley.voice.help.ui
  "Help panel rendering: search results, tier browsing, category view."
  (:require [clojure.string :as str]
            [ridley.voice.state :as state]
            [ridley.voice.help.db :as db]))

(def ^:private page-size 7)

(def help-css
  ".help-results {
     margin-top: 6px;
     max-height: 320px;
     overflow-y: auto;
   }
   .help-title {
     color: #4fc1ff;
     font-weight: bold;
     margin-bottom: 4px;
   }
   .help-item {
     display: flex;
     gap: 6px;
     padding: 2px 0;
     border-bottom: 1px solid #2a2a2a;
     cursor: pointer;
     transition: background 0.1s;
   }
   .help-item:hover, .help-tier:hover, .help-item.highlight {
     background: #2a2a2a;
   }
   .help-item.highlight .help-num {
     color: #fff;
   }
   .help-num {
     color: #dcdcaa;
     min-width: 16px;
   }
   .help-sym {
     color: #9cdcfe;
     font-weight: bold;
     min-width: 100px;
   }
   .help-doc {
     color: #888;
     flex: 1;
   }
   .help-template {
     color: #ce9178;
     font-size: 10px;
   }
   .help-tier {
     padding: 4px 0;
     border-bottom: 1px solid #2a2a2a;
     cursor: pointer;
   }
   .help-tier-name {
     color: #4fc1ff;
   }
   .help-tier-count {
     color: #666;
   }
   .help-footer {
     color: #666;
     margin-top: 4px;
     font-size: 10px;
   }
   .help-cat {
     color: #c586c0;
     font-size: 10px;
     margin-top: 4px;
   }
   .help-nav-btn {
     background: none;
     border: 1px solid #444;
     color: #888;
     cursor: pointer;
     padding: 0 6px;
     border-radius: 3px;
     font-size: 10px;
     margin: 0 2px;
   }
   .help-nav-btn:hover {
     background: #333;
     color: #ccc;
   }
   .help-nav-btn:disabled {
     opacity: 0.3;
     cursor: default;
   }
   .help-back-btn {
     background: none;
     border: none;
     color: #888;
     cursor: pointer;
     padding: 0 4px 0 0;
     font-size: 11px;
   }
   .help-back-btn:hover {
     color: #4fc1ff;
   }")

(defn- escape-html [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- render-item [entry page-idx lang highlight]
  (let [sym-name (name (:symbol entry))
        doc-text (get-in entry [:doc lang] "")
        hl-class (if (= page-idx highlight) " highlight" "")]
    (str "<div class='help-item" hl-class "' data-action='select-item' data-index='" page-idx "'>"
         "<span class='help-num'>" (inc page-idx) "</span>"
         "<span class='help-sym'>" (escape-html sym-name) "</span>"
         "<span class='help-doc'>" (escape-html doc-text) "</span>"
         "</div>")))

(defn- render-nav-buttons [page total-pages]
  (str "<button class='help-nav-btn' data-action='help-prev'"
       (when (zero? page) " disabled") ">\u25C0</button>"
       "<button class='help-nav-btn' data-action='help-next'"
       (when (>= (inc page) total-pages) " disabled") ">\u25B6</button>"))

(defn- render-items-page [results page lang highlight]
  (let [start (* page page-size)
        page-items (->> results (drop start) (take page-size))
        total (count results)
        total-pages (js/Math.ceil (/ total page-size))]
    (str
     (str/join "" (map-indexed (fn [i entry]
                                 (render-item entry i lang highlight))
                               page-items))
     "<div class='help-footer'>"
     (when (> total-pages 1)
       (str (render-nav-buttons page total-pages) " Pag " (inc page) "/" total-pages " "))
     (str total " risultati — ")
     "\u2191\u2193 Enter | Bksp \u25C0 | Esc"
     "</div>")))

(defn- render-categories [lang]
  (str
   "<div class='help-title'>Help — Categorie</div>"
   (str/join ""
     (map-indexed
      (fn [i [tier-key tier-info]]
        (let [tier-name (get-in tier-info [:name lang])
              cnt (count (filter (fn [[_ e]] (= (:tier e) tier-key)) db/help-entries))]
          (str "<div class='help-tier' data-action='browse-tier' data-tier-index='" i "'>"
               "<span class='help-num'>" (inc i) "</span> "
               "<span class='help-tier-name'>" (escape-html tier-name) "</span>"
               " <span class='help-tier-count'>(" cnt ")</span>"
               "</div>")))
      (sort-by (comp :order val) db/tiers)))
   "<div class='help-footer'>1-3 o click | cerca: dì il nome | Esc esci</div>"))

(defn- render-search [help-state lang]
  (let [{:keys [results page query highlight]} help-state]
    (str
     "<div class='help-title'>"
     "<button class='help-back-btn' data-action='help-back'>\u25C0</button>"
     "Help: \"" (escape-html (or query "")) "\"</div>"
     (if (seq results)
       (render-items-page results page lang highlight)
       "<div class='help-doc'>Nessun risultato</div>"))))

(defn- render-browse [help-state lang]
  (let [{:keys [results page query highlight]} help-state
        tier-key (keyword (or query ""))
        tier-name (get-in db/tiers [tier-key :name lang] (or query ""))]
    (str
     "<div class='help-title'>"
     "<button class='help-back-btn' data-action='help-back'>\u25C0</button>"
     (escape-html tier-name) "</div>"
     (if (seq results)
       (let [start (* page page-size)
             page-items (->> results (drop start) (take page-size))
             prev-cat (atom nil)]
         (str
          (str/join ""
            (map-indexed
             (fn [i entry]
               (let [cat (:category entry)
                     cat-header (when (not= cat @prev-cat)
                                  (reset! prev-cat cat)
                                  (str "<div class='help-cat'>" (name cat) "</div>"))]
                 (str cat-header (render-item entry i lang highlight))))
             page-items))
          (let [total (count results)
                total-pages (js/Math.ceil (/ total page-size))]
            (str "<div class='help-footer'>"
                 (when (> total-pages 1)
                   (str (render-nav-buttons page total-pages) " Pag " (inc page) "/" total-pages " "))
                 total " simboli — "
                 "\u2191\u2193 Enter | Bksp \u25C0 | Esc"
                 "</div>"))))
       "<div class='help-doc'>Nessun simbolo</div>"))))

(defn render-html
  "Render help panel content. Called when mode = :help."
  [lang]
  (let [help-state (state/get-help)]
    (str "<div class='help-results'>"
         (case (:view help-state)
           :categories (render-categories lang)
           :search     (render-search help-state lang)
           :browse     (render-browse help-state lang)
           ;; Default: show categories
           (render-categories lang))
         "</div>")))
