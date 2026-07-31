(ns ridley.editor.source-edit-test
  "Source surgery for the acquire stage's plane-mark write-back
   (dev-docs/brief-plane-marks.md): appending an entry to the `:marks {…}` block
   of an already-emitted `(acquire …)` form.

   What must hold is narrow but load-bearing: the edit is BOUNDED — everything
   outside the :marks braces stays byte-identical, including the user's own
   ricalchi, comments and hand-tuning — and the located block must be the right
   one even though the form is full of nested vectors, nested maps and strings."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.editor.source-edit :as src]))

(def emitted
  "A realistic emitted acquire form: nested (poly …) shapes each carrying their
   own :mark {…} sub-map, then the :marks block we amend."
  (str "(def A\n"
       "  (acquire \"test-assets/param-plate-paper\"\n"
       "    {:proxy (registration-plate :d 130)\n"
       "     :pose {:position [0 0 0] :heading [0 0 1] :up [0 1 0]}\n"
       "     :shapes {:bordo {:shape (poly [1 2 3] [4 5 6])\n"
       "                      :mark {:position [1 1 1] :heading [0 0 1] :up [0 1 0]}}}\n"
       "     :marks {:cima {:position [0 0 60] :heading [0 0 1] :up [0 1 0]}}}))\n"))

(defn- marks-block [text]
  (let [[o e _] (src/map-value-bounds text 0 (count text) ":marks")]
    (subs text o e)))

;; ---------------------------------------------------------------------------

(deftest matches-brackets-of-every-kind
  (testing "nested maps inside a map value"
    (let [s "{:a {:b [1 2]} :c 3}END"]
      (is (= 20 (src/find-matching-bracket s 0)))
      (is (= "{:a {:b [1 2]} :c 3}" (subs s 0 (src/find-matching-bracket s 0))))))
  (testing "braces inside a string are not brackets"
    (let [s "{:a \"}}}\" :b 1}"]
      (is (= (count s) (src/find-matching-bracket s 0)))))
  (testing "brackets inside a line comment are skipped"
    (let [s "{:a 1 ; }}} not real\n :b 2}"]
      (is (= (count s) (src/find-matching-bracket s 0)))))
  (testing "unbalanced and mismatched input is refused, not guessed"
    (is (neg? (src/find-matching-bracket "{:a [1 2}" 0)) "] expected, } found")
    (is (neg? (src/find-matching-bracket "{:a 1" 0)) "never closed")
    (is (neg? (src/find-matching-bracket "abc" 0)) "not an opener at all")))

(deftest finds-the-marks-block-not-a-lookalike
  (let [[o e i] (src/map-value-bounds emitted 0 (count emitted) ":marks")]
    (is (= "{:cima {:position [0 0 60] :heading [0 0 1] :up [0 1 0]}}"
           (subs emitted o e))
        "the :marks value, closing on ITS brace — not the nested :mark's, and not
         the enclosing opts map's")
    (is (= 5 (src/column-of emitted i)) "the key's column drives the indent"))
  (testing ":mark inside :shapes must not be mistaken for :marks"
    ;; the naive search finds ":mark" first; the key token includes the s
    (let [[o _ _] (src/map-value-bounds emitted 0 (count emitted) ":marks")]
      (is (< (.indexOf emitted ":shapes") o)
          "the block found starts AFTER the shapes block, i.e. it is the real one")))
  (testing "a form with no :marks slot yields nil rather than a wrong range"
    (is (nil? (src/map-value-bounds "(acquire \"d\" {:proxy (box 1 2 3)})"
                                    0 33 ":marks")))))

(deftest appending-a-mark-touches-nothing-else
  (let [[o e i] (src/map-value-bounds emitted 0 (count emitted) ":marks")
        entry ":piano-1 {:position [1 2 3] :heading [0 0 1] :up [0 1 0]}"
        updated (src/append-map-entry (subs emitted o e) entry ":marks"
                                      (src/column-of emitted i))
        result (str (subs emitted 0 o) updated (subs emitted e))]
    (println "\n=== write-back del mark-piano ===")
    (println result)
    (testing "everything outside the block is byte-identical"
      (is (= (subs emitted 0 o) (subs result 0 o)))
      (is (= (subs emitted e) (subs result (- (count result) (- (count emitted) e))))))
    (testing "the old entry survives and the new one is there"
      (is (re-find #":cima \{:position \[0 0 60\]" result))
      (is (re-find #":piano-1 \{:position \[1 2 3\]" result)))
    (testing "the result is still balanced source"
      (is (pos? (src/find-matching-bracket result (.indexOf result "(acquire")))))
    (testing "the new entry aligns under the first, as the emitter lays them out"
      (let [lines (.split result "\n")
            marks-line (first (filter #(re-find #":marks \{" %) lines))
            new-line (first (filter #(re-find #":piano-1" %) lines))]
        (is (= (.indexOf marks-line ":cima") (.indexOf new-line ":piano-1"))
            "second entry starts in the same column as the first")))))

(deftest appending-to-an-empty-marks-block
  (let [text "(acquire \"d\" {:proxy (box 1 2 3)\n              :marks {}})"
        [o e i] (src/map-value-bounds text 0 (count text) ":marks")
        updated (src/append-map-entry (subs text o e) ":piano-1 {:position [0 0 1]}"
                                      ":marks" (src/column-of text i))]
    (is (= "{:piano-1 {:position [0 0 1]}}" updated)
        "a single entry stays on one line — no gratuitous newline")))

(deftest unwrapping-a-wrapper-form-gives-back-what-it-wrapped
  ;; The cancel path of (edit-plane-mark …): unlike the rest of the edit-* family
  ;; there is no call to rename the head to, because what is wrapped is a
  ;; LITERAL. Giving up must therefore restore that literal exactly — anything
  ;; less would quietly damage a mark the user chose NOT to change.
  (let [head "(edit-plane-mark"
        lit "{:position [0 0 40] :heading [0 0 1] :up [0 1 0] :from [[10 0 40] [-5 8 40]]}"
        wrapped (str head " " lit ")")]
    (is (= lit (src/form-inner wrapped 0 (count wrapped) head))
        "round-trip: what comes out is what went in")
    (testing "inside a larger buffer"
      (let [text (str "(acquire \"d\" {:marks {:piano-2 " wrapped "}})")
            [from to] [(.indexOf text head) (src/find-matching-bracket text (.indexOf text head))]]
        (is (= lit (src/form-inner text from to head)))
        (is (= "(acquire \"d\" {:marks {:piano-2 {:position [0 0 40] :heading [0 0 1] :up [0 1 0] :from [[10 0 40] [-5 8 40]]}}})"
               (str (subs text 0 from) (src/form-inner text from to head) (subs text to)))
            "splicing the inner text back leaves valid, unchanged source"))))
  (testing "the empty creation spelling has nothing inside"
    (is (= "" (src/form-inner "(edit-plane-mark)" 0 17 "(edit-plane-mark"))))
  (testing "a range that does not start with the head is refused"
    (is (nil? (src/form-inner "(something-else 1)" 0 18 "(edit-plane-mark")))))

(deftest compact-numbers
  (is (= "3" (src/fmt-number 3.0)) "integers as ints")
  (is (= "-0.5" (src/fmt-number -0.5)))
  (is (= "1.2346" (src/fmt-number 1.23456789)) "4 decimals")
  (is (= "0" (src/fmt-number 1e-9)) "noise rounds away rather than printing 1e-9")
  (is (= "[1 -2.5 0]" (src/fmt-vec3 [1.0 -2.5 0.0]))))
