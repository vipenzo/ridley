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

(defn path-data
  "The plain-data view of a path value: the map the `path` macro built —
   {:type :path :commands [{:cmd :f :args [30]} …]} plus one key per recorded
   mark holding its pose — minus :micro-commands, the memoized lowering every
   consumer recomputes on demand when it is absent. What you print, save or
   diff. Anything that is not a path (a turtle state, from before paths were
   recorded) still goes through path-from-state."
  [x]
  (if (and (map? x) (= :path (:type x)))
    (dissoc x :micro-commands)
    (path-from-state x)))

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
