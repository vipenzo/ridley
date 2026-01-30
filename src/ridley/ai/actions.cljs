(ns ridley.ai.actions
  "Execute actions returned by LLM."
  (:require [ridley.ai.state :as state]))

;; These will be set by core.cljs to avoid circular deps
(defonce ^:private editor-insert! (atom nil))
(defonce ^:private editor-edit! (atom nil))
(defonce ^:private editor-navigate! (atom nil))
(defonce ^:private editor-execute! (atom nil))
(defonce ^:private speak! (atom nil))

(defn set-handlers!
  "Set handler functions from core.cljs"
  [{:keys [insert edit navigate execute speak]}]
  (reset! editor-insert! insert)
  (reset! editor-edit! edit)
  (reset! editor-navigate! navigate)
  (reset! editor-execute! execute)
  (reset! speak! speak))

(defmulti execute-action :action)

(defmethod execute-action "insert" [{:keys [target code position]}]
  (when @editor-insert!
    (@editor-insert! {:target (keyword target)
                      :code code
                      :position (keyword position)})))

(defmethod execute-action "edit" [{:keys [operation target value  element]}]
  (when @editor-edit!
    (@editor-edit! {:operation (keyword operation)
                    :target target
                    :value value
                    :element element})))

(defmethod execute-action "navigate" [{:keys [direction mode count]}]
  (when @editor-navigate!
    (@editor-navigate! {:direction (keyword direction)
                        :mode (keyword mode)
                        :count (or count 1)})))

(defmethod execute-action "mode" [{:keys [set]}]
  (state/set-mode! (keyword set)))

(defmethod execute-action "target" [{:keys [set]}]
  (state/update-cursor! {:target (keyword set)}))

(defmethod execute-action "execute" [{:keys [target]}]
  (when @editor-execute!
    (@editor-execute! {:target (keyword target)})))

(defmethod execute-action "select" [{:keys [what extend]}]
  ;; TODO: implement selection
  nil)

(defmethod execute-action "speak" [{:keys [text]}]
  (when @speak!
    (@speak! text))
  (state/update-voice! {:pending-speech text}))

(defmethod execute-action :default [action]
  (js/console.warn "Unknown action:" (pr-str action)))

(defn execute! [action-or-actions]
  "Execute one action or a vector of actions."
  (let [actions (if (contains? action-or-actions :actions)
                  (:actions action-or-actions)
                  [action-or-actions])]
    (doseq [action actions]
      (execute-action action))))
