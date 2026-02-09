(ns ridley.editor.state
  "Shared atoms and state accessors for the editor subsystem.
   All modules that need turtle-atom or print-buffer require this namespace."
  (:require [ridley.turtle.core :as turtle]))

;; Global turtle state for implicit mode
(defonce turtle-atom (atom nil))

;; Atom to capture print output during evaluation
(defonce print-buffer (atom []))

(defn reset-print-buffer! []
  (reset! print-buffer []))

(defn ^:export capture-print [& args]
  (swap! print-buffer conj (apply str args)))

(defn ^:export capture-println [& args]
  (swap! print-buffer conj (str (apply str args) "\n")))

(defn get-print-output []
  (let [output (apply str @print-buffer)]
    (when (seq output) output)))

(defn reset-turtle! []
  (reset! turtle-atom (turtle/make-turtle)))

(defn get-turtle []
  @turtle-atom)

(defn ^:export get-turtle-resolution
  "Get current turtle resolution settings for use in path macro."
  []
  (:resolution @turtle-atom))

(defn ^:export get-turtle-joint-mode
  "Get current turtle joint-mode for use in path macro."
  []
  (:joint-mode @turtle-atom))

(defn get-turtle-pose
  "Get current turtle pose for indicator display.
   Returns {:position [x y z] :heading [x y z] :up [x y z]} or nil."
  []
  (when-let [t @turtle-atom]
    {:position (:position t)
     :heading (:heading t)
     :up (:up t)}))

(defn last-mesh []
  (last (:meshes @turtle-atom)))
