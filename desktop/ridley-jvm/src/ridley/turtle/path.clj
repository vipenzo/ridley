(ns ridley.turtle.path
  "Path and shape constructors.

   A path is an open sequence of segments.
   A shape is a closed path (auto-closes back to start)."
  (:require [ridley.turtle.core :as turtle]))

(defn path-from-state
  "Convert turtle state to path structure."
  [turtle-state]
  {:type :path
   :segments (:geometry turtle-state)
   :closed? false
   :start (get-in turtle-state [:geometry 0 :from] [0 0 0])
   :end (:position turtle-state)})

(defn shape-from-state
  "Convert turtle state to closed shape structure."
  [turtle-state]
  (let [geometry (:geometry turtle-state)
        start (get-in geometry [0 :from] [0 0 0])
        end (:position turtle-state)
        needs-close? (not= start end)
        final-segments (if needs-close?
                         (conj geometry {:type :line :from end :to start})
                         geometry)]
    {:type :shape
     :segments final-segments
     :closed? true
     :start start}))
