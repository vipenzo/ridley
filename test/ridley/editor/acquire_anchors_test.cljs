(ns ridley.editor.acquire-anchors-test
  "`(turtle A :at :piano-1 …)` — an acquisition answers the anchor question.

   Posing the turtle on a measured mark was the most-typed line of a real
   session (Vincenzo 2026-08-03: 'mi trovo spesso a scrivere codice per spostare
   una turtle a un mark'), and it read as a double lookup through the shape of
   the value:

     (def ma (:piano-1 (:marks A)))
     (turtle ma (extrude (rect 18 16) (f 2)))

   No new spelling was needed: `(turtle <target> :at <name> …)` already exists
   for paths and meshes. What was missing is that an `(acquire …)` value knew
   how to say what its named poses are. Now it does — its marks, over the
   proxy's faces — so the acquisition is an anchor carrier like the others."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.turtle.core :as turtle]
            [ridley.editor.sci-harness :as h]))

(defn- approx= [a b] (< (js/Math.abs (- a b)) 0.001))
(defn- v= [a b] (and (= (count a) (count b)) (every? true? (map approx= a b))))

(def ^:private acq
  "An acquire-shaped value: what (acquire …) returns, trimmed to what anchors
   look at — a proxy, the measured marks, the computed faces."
  (str "{:proxy {:vertices [] :faces []}"
       " :dir \"scans/x/\""
       " :marks {:piano-1 {:position [10 20 30] :heading [0 0 1] :up [0 1 0]}"
       "         :piano-h {:position [1 2 3] :heading [1 0 0] :up [0 0 1]}}"
       " :faces {:top {:position [0 0 9] :heading [0 0 1] :up [0 1 0]}"
       "         :piano-1 {:position [-5 -5 -5] :heading [0 1 0] :up [0 0 1]}}}"))

(defn- run [code]
  (let [{:keys [result error]} (h/eval-dsl code)]
    (is (nil? error) (str "DSL error: " error))
    result))

(deftest turtle-at-a-mark-of-an-acquire
  (testing "the mark poses the turtle — position, heading and up"
    (is (v= [10 20 30] (run (str "(def A " acq ") (turtle A :at :piano-1 (turtle-position))"))))
    (is (v= [0 0 1] (run (str "(def A " acq ") (turtle A :at :piano-1 (turtle-heading))")))
        "heading is the surface normal: it is what aims the extrusion out of the face")
    (is (v= [0 1 0] (run (str "(def A " acq ") (turtle A :at :piano-1 (turtle-up))")))))

  (testing "it means exactly what the hand-written double lookup meant"
    (is (v= (run (str "(def A " acq ") (turtle (:piano-h (:marks A)) (turtle-position))"))
            (run (str "(def A " acq ") (turtle A :at :piano-h (turtle-position))")))))

  (testing "movement inside runs in the mark's frame"
    (is (v= [10 20 35] (run (str "(def A " acq ") (turtle A :at :piano-1 (f 5) (turtle-position))")))
        "f 5 goes along the normal, from the mark"))

  (testing "it is still a scope: the turtle comes back"
    (is (v= [0 0 0] (run (str "(def A " acq ") (turtle A :at :piano-1 (turtle-position)) (turtle-position)"))))))

(deftest faces-are-reachable-too-and-marks-win
  (testing "a proxy face is an anchor of the acquisition"
    (is (v= [0 0 9] (run (str "(def A " acq ") (turtle A :at :top (turtle-position))")))))
  (testing "on a name clash the MARK wins over the generated face"
    (is (v= [10 20 30] (run (str "(def A " acq ") (turtle A :at :piano-1 (turtle-position))")))
        "the face named :piano-1 sits at [-5 -5 -5] and must not win")))

(deftest anchors-lists-them
  (is (= #{:piano-1 :piano-h :top}
         (set (run (str "(def A " acq ") (keys (anchors A))"))))))

(deftest the-other-carriers-are-untouched
  (testing "a path still resolves its marks at the world origin"
    (is (= #{:pin :tip}
           (set (run "(keys (anchors (path (mark :pin) (f 50) (mark :tip))))")))))
  (testing "a mesh carrying :faces is NOT read as an acquisition"
    ;; every mesh has :faces; only an acquire has :proxy + :marks
    (is (nil? (turtle/named-poses {:vertices [[0 0 0]] :faces [[0 1 2]]}))
        "no :anchors on it, and its :faces are triangles, not poses")))
