(ns ridley.editor.turtle-pose-symbol-test
  "`(turtle <pose> body…)` where the pose is reached through a SYMBOL.

   The documented signature is `(turtle pose-map & body)` and naming a mark
   before using it is the obvious thing to write:

     (def ma (:piano-1 (:marks A)))
     (turtle ma (extrude (circle 10) (f 2)))

   but the macro used to recognise a pose only as a literal, an inline accessor
   `(:piano-1 …)` or after `:pose`. A bare symbol fell through to the BODY, so
   the turtle silently stayed where it was and the geometry appeared at the
   parent pose instead of at the mark — the failure Vincenzo hit on 2026-07-31
   ('il mark sembra essere da tutt'altra parte'). Silent, because evaluating a
   symbol has no side effect to notice.

   What must hold now: a symbol holding a pose poses the turtle, a symbol
   holding anything else (a mesh, a number) is still an ordinary body form, and
   the pre-existing spellings are untouched."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.editor.sci-harness :as h]))

(defn- approx= [a b] (< (js/Math.abs (- a b)) 0.001))
(defn- v= [a b] (and (= (count a) (count b)) (every? true? (map approx= a b))))

(def ^:private mark
  "A plane mark of the shape the acquire stage emits: position + normal + up."
  "{:position [10 20 30] :heading [0 0 1] :up [0 1 0]}")

(defn- run
  "Evaluate DSL code, asserting it did not throw, and return its value."
  [code]
  (let [{:keys [result error]} (h/eval-dsl code)]
    (is (nil? error) (str "DSL error: " error))
    result))

(deftest a-symbol-holding-a-pose-poses-the-turtle
  (testing "the failing spelling from the report now works"
    (is (v= [10 20 30] (run (str "(def ma " mark ") (turtle ma (turtle-position))")))
        "position comes from the mark, not from the parent turtle")
    (is (v= [0 0 1] (run (str "(def ma " mark ") (turtle ma (turtle-heading))")))
        "heading too — this is what aims the extrusion out of the surface")
    (is (v= [0 1 0] (run (str "(def ma " mark ") (turtle ma (turtle-up))")))))

  (testing "it agrees with the inline-accessor spelling that always worked"
    (let [named (run (str "(def A {:marks {:piano-1 " mark "}})"
                          "(def ma (:piano-1 (:marks A)))"
                          "(turtle ma (turtle-position))"))
          inline (run (str "(def A {:marks {:piano-1 " mark "}})"
                           "(turtle (:piano-1 (:marks A)) (turtle-position))"))]
      (is (v= named inline) "the two spellings must mean the same thing")))

  (testing "and with the explicit :pose escape hatch"
    (is (v= [10 20 30]
            (run (str "(def ma " mark ") (turtle :pose ma (turtle-position))")))))

  (testing "the turtle is restored afterwards — it is still a scope"
    (is (v= [0 0 0]
            (run (str "(def ma " mark ") (turtle ma (turtle-position)) (turtle-position)"))))))

(deftest a-symbol-holding-something-else-is-still-a-body-form
  (testing "a number first: body, and the turtle does not move"
    (is (v= [0 0 0] (run "(def n 7) (turtle n (turtle-position))"))
        "no pose in `n`, so the parent pose stands"))

  (testing "a mesh-shaped map is NOT mistaken for a pose"
    ;; a mesh carries :vertices/:faces/:creation-pose — never :heading + :position
    (is (v= [0 0 0]
            (run "(def m {:vertices [[1 2 3]] :faces []
                          :creation-pose {:position [9 9 9] :heading [1 0 0] :up [0 0 1]}})
                  (turtle m (turtle-position))"))
        "a mesh must not drag the turtle to its creation-pose"))

  (testing "a lone symbol with no body still evaluates to its own value"
    (is (= 7 (run "(def n 7) (turtle n)"))
        "the old meaning of (turtle x) is preserved when x is not a pose")))

(deftest a-computed-pose-expression-still-needs-the-pose-keyword
  ;; This is the OLD failure mode, still reachable — and it is what proves the
  ;; mechanism: a first form the parser does not recognise (here a call that is
  ;; neither an accessor nor get/get-in) is body, so the turtle never moves. The
  ;; fix covers the symbol case because a symbol can be evaluated harmlessly and
  ;; inspected; an arbitrary call cannot (it may have side effects), so the
  ;; escape hatch stays.
  (testing "an unrecognised call form is body, and the turtle stays put"
    (is (v= [0 0 0] (run (str "(def ma " mark ") (turtle (identity ma) (turtle-position))")))
        "this is exactly what used to happen to a bare symbol"))
  (testing ":pose makes it work"
    (is (v= [10 20 30]
            (run (str "(def ma " mark ") (turtle :pose (identity ma) (turtle-position))"))))))

(deftest the-other-spellings-are-untouched
  (testing "vector position"
    (is (v= [1 2 3] (run "(turtle [1 2 3] (turtle-position))"))))
  (testing "map literal"
    (is (v= [4 5 6] (run "(turtle {:pos [4 5 6]} (turtle-position))"))))
  (testing "movement inside the pose is relative to it"
    (is (v= [10 20 35] (run (str "(def ma " mark ") (turtle ma (f 5) (turtle-position))")))
        "f 5 runs along the mark's heading (+z), from the mark"))
  (testing "no positional argument at all"
    (is (v= [0 0 0] (run "(turtle (turtle-position))")))))
