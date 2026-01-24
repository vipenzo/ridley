(ns ridley.manual.core
  "Manual state management - controls the live manual panel."
  (:require [ridley.manual.content :as content]))

;; Manual state atom
(defonce manual-state
  (atom {:open? false
         :current-page :hello-ridley
         :lang :en
         :history []}))

(defn open?
  "Check if manual is currently open."
  []
  (:open? @manual-state))

(defn get-current-page
  "Get the current page ID."
  []
  (:current-page @manual-state))

(defn get-lang
  "Get the current language."
  []
  (:lang @manual-state))

(defn open-manual!
  "Open the manual. Optionally navigate to a specific page."
  ([]
   (swap! manual-state assoc :open? true))
  ([page-id]
   (swap! manual-state assoc
          :open? true
          :current-page page-id)))

(defn close-manual!
  "Close the manual."
  []
  (swap! manual-state assoc :open? false))

(defn toggle-manual!
  "Toggle the manual open/closed."
  []
  (swap! manual-state update :open? not))

(defn navigate-to!
  "Navigate to a specific page, saving current page to history."
  [page-id]
  (let [current (:current-page @manual-state)]
    (when (not= current page-id)
      (swap! manual-state
             (fn [state]
               (-> state
                   (update :history conj current)
                   (assoc :current-page page-id)))))))

(defn has-history?
  "Check if there's navigation history available."
  []
  (seq (:history @manual-state)))

(defn go-back!
  "Go back to the previous page in history."
  []
  (let [history (:history @manual-state)]
    (when (seq history)
      (swap! manual-state
             (fn [state]
               (-> state
                   (assoc :current-page (peek history))
                   (update :history pop)))))))

(defn set-lang!
  "Set the manual language."
  [lang]
  (swap! manual-state assoc :lang lang))

(defn toggle-lang!
  "Toggle between English and Italian."
  []
  (swap! manual-state update :lang #(if (= % :en) :it :en)))

(defn get-page-data
  "Get the merged page data (structure + i18n) for the current page."
  []
  (content/get-page (:current-page @manual-state) (:lang @manual-state)))

(defn add-state-watcher!
  "Add a watcher to the manual state atom."
  [key callback]
  (add-watch manual-state key
             (fn [_ _ old-state new-state]
               (callback old-state new-state))))

(defn remove-state-watcher!
  "Remove a watcher from the manual state atom."
  [key]
  (remove-watch manual-state key))
