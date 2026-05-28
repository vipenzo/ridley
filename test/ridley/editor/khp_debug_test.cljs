(ns ridley.editor.khp-debug-test
  "Trace where the 0.5 offsets in (register D ...) come from."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.editor.sci-harness :as h]))

(deftest socket-up-anchor-z
  (testing "where does :socket-up sit relative to KP origin?"
    (let [code "
(def label-length 40)
(def label-h 25)
(def trace-w 5)
(def socket-h (+ label-h (/ trace-w 2)))

(def KP (path
          (mark :key-label)
          (side-trip (u (/ label-length 2)) (mark :key-circle))
          (side-trip (u (- (/ label-length 2))) (mark :key-ring))
          (side-trip (th 180) (u (* label-length 0.2)) (mark :socket-up)
            (u (- (/ label-length 2))) (mark :socket-body)
            (rt (- (/ socket-h 2) (/ trace-w 4))) (mark :socket-bar-1)
            (rt (- (- socket-h (/ trace-w 2)))) (mark :socket-bar-2))))

[(:position (:socket-up (pin-path KP)))
 (* label-length 0.2)
 (/ trace-w 2)
 (- (* label-length 0.2) (/ trace-w 2))]"
          {:keys [result error]} (h/eval-dsl code)]
      (is (nil? error) (str "Should not error: " error))
      (println "Result:")
      (println "  :socket-up position    =" (nth result 0))
      (println "  label-length * 0.2     =" (nth result 1))
      (println "  trace-w / 2            =" (nth result 2))
      (println "  (label*0.2) - (tw/2)   =" (nth result 3)))))
