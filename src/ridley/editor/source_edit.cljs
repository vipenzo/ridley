(ns ridley.editor.source-edit
  "Pure string surgery on Clojure source text — bracket matching, locating a
   map value inside a form, appending an entry to it, and the compact number
   rendering emitted forms use.

   Deliberately dependency-free (no CodeMirror, no DOM, no state), for two
   reasons: it is exercised by the node test suite, and it is shared by two very
   different writers of the same form — edit-acquire's confirm, which emits the
   whole `(acquire …)`, and the acquire STAGE, which later appends a plane mark
   to that form's `:marks` block in place (dev-docs/brief-plane-marks.md). Both
   must format numbers and indent entries identically or the form would drift
   into two dialects depending on who last touched it.")

(defn fmt-number
  "Round to 4 decimals, drop trailing zeros, integers as ints — compact enough
   that an emitted pose stays readable on one line."
  [x]
  (let [r (/ (js/Math.round (* (double x) 10000)) 10000)]
    (if (= r (js/Math.floor r)) (str (long r)) (str r))))

(defn fmt-vec3 [[a b c]]
  (str "[" (fmt-number a) " " (fmt-number b) " " (fmt-number c) "]"))

(defn skip-string
  "Given text and the index of an opening quote, return the index after the
   closing quote, or -1 if unterminated."
  [text start]
  (let [len (count text)]
    (loop [j (inc start)]
      (cond
        (>= j len) -1
        (= (.charAt text j) "\\") (recur (+ j 2))
        (= (.charAt text j) "\"") (inc j)
        :else (recur (inc j))))))

(defn commented?
  "Is the character at `idx` inside a LINE COMMENT? Scans its own line from the
   start, honouring strings, so a `;` inside a string literal does not count.

   A commented-out form is not part of the program, so it must never be the
   target of a write-back. This bit for real (2026-08-07): an earlier
   `(def A (acquire …))` had been commented out to start over, and the stage's
   edge write-back landed in THAT one — it was merely the first occurrence in
   the file.

   Line comments only: a form disabled with the reader discard `#_` still looks
   live from here. That is the rarer gesture, and catching it properly means
   parsing, not scanning."
  [text idx]
  (let [start (inc (.lastIndexOf (.substring text 0 idx) "\n"))]
    (loop [i start]
      (if (>= i idx)
        false
        (let [ch (.charAt text i)]
          (cond
            (= ch ";") true
            (= ch "\"") (let [after (skip-string text i)]
                          (if (neg? after) false (recur after)))
            :else (recur (inc i))))))))

(def ^:private closer-of {"(" ")" "[" "]" "{" "}"})
(def ^:private closer? #{")" "]" "}"})

(defn find-matching-bracket
  "Given text and the index of an opening bracket — `(`, `[` or `{` — return the
   index one past its matching closer, honouring nesting of ALL THREE kinds plus
   strings and line comments. Returns -1 if unbalanced, mismatched, or if
   `start` is not an opener.

   modal-evaluator's find-matching-paren tracks parens only, which is enough to
   bound a FORM; this is what you need to bound a MAP VALUE inside one, since
   such a block contains both vectors and nested maps."
  [text start]
  (let [len (count text)
        open (.charAt text start)]
    (if-not (closer-of open)
      -1
      (loop [i (inc start) stack (list (closer-of open))]
        (if (>= i len)
          -1
          (let [ch (.charAt text i)]
            (cond
              (closer-of ch) (recur (inc i) (conj stack (closer-of ch)))
              (= ch (first stack)) (if (nil? (next stack)) (inc i) (recur (inc i) (rest stack)))
              (closer? ch) -1
              (= ch "\"") (let [after (skip-string text i)]
                            (if (neg? after) -1 (recur after stack)))
              (= ch ";") (let [nl (.indexOf text "\n" i)]
                           (if (neg? nl) -1 (recur (inc nl) stack)))
              :else (recur (inc i) stack))))))))

(defn map-value-bounds
  "Locate the `{…}` value of `kw` (a key token such as \":marks\") within
   text[from to). Returns [open end key-idx] — the index of the opening brace,
   one past its matching closer, and the index of the key itself (whose column
   sets the block's indentation) — or nil when the key or its map isn't there.

   Only the FIRST occurrence in the range is considered, which is what the
   caller wants: the acquire form has exactly one :marks slot, and refusing
   rather than guessing is the right answer for anything stranger."
  [text from to kw]
  (let [i (.indexOf text kw from)]
    (when (and (>= i 0) (< i to))
      (let [o (.indexOf text "{" (+ i (count kw)))]
        (when (and (>= o 0) (< o to))
          (let [e (find-matching-bracket text o)]
            (when (and (pos? e) (<= e to)) [o e i])))))))

(defn column-of
  "0-based column of `idx` in `text` — the width of the indent its line carries."
  [text idx]
  (- idx (inc (.lastIndexOf (.substring text 0 idx) "\n"))))

(defn form-inner
  "The text INSIDE `(head …)` occupying [from to): everything after the head
   token and before the closing paren, trimmed. `(edit-plane-mark {…})` → `{…}`.

   This is the cancel path of a wrapper form whose strip-head has no call to
   rename it to — the acquire stage's plane-mark editor wraps a LITERAL, so
   giving up on the edit means removing the wrapper entirely rather than
   rewriting its head (modal-evaluator/strip-head). Returns nil when the range
   does not start with `head`."
  [text from to head]
  (when (= head (subs text from (min (count text) (+ from (count head)))))
    (let [body (subs text (+ from (count head)) (dec to))]
      (.trim body))))

(defn- skip-blanks
  "Index of the next meaningful character from `i`, stepping over whitespace,
   commas and line comments."
  [text i]
  (let [len (count text)]
    (loop [j i]
      (cond
        (>= j len) j
        (re-find #"[\s,]" (.charAt text j)) (recur (inc j))
        (= ";" (.charAt text j)) (let [nl (.indexOf text "\n" j)]
                                   (if (neg? nl) len (recur (inc nl))))
        :else j))))

(defn map-entries
  "The TOP-LEVEL entries of a `{…}` block, as [{:key \":id\" :text \":id value\"} …]
   with each :text the source's own bytes for that entry.

   Needed to merge two writers into one map without either trampling the other:
   edit-acquire's confirm regenerates the entries IT owns and must leave every
   other one exactly as written — a plane mark the stage put there, or anything
   the user hand-edited. A regex cannot do this (a `(poly …)` value contains
   `:mark {…}` of its own), so the values are skipped with the bracket matcher.
   Returns nil if `block` is not a brace-delimited map."
  [block]
  (when (and (seq block) (= "{" (.charAt block 0)))
    (let [end (dec (count block))]
      (loop [i (skip-blanks block 1) out []]
        (if (>= i end)
          out
          (let [k-end (or (some (fn [j] (when (re-find #"[\s,{}\[\]()]" (.charAt block j)) j))
                                (range i end))
                          end)
                v-start (skip-blanks block k-end)
                v-end (cond
                        (>= v-start end) end
                        (closer-of (.charAt block v-start))
                        (find-matching-bracket block v-start)
                        (= "\"" (.charAt block v-start)) (skip-string block v-start)
                        :else (or (some (fn [j] (when (re-find #"[\s,{}\[\]()]" (.charAt block j)) j))
                                        (range v-start end))
                                  end))]
            (if (or (neg? v-end) (<= v-end i))
              out       ; malformed: stop rather than guess
              (recur (skip-blanks block v-end)
                     (conj out {:key (subs block i k-end)
                                :text (.trim (subs block i v-end))})))))))))

(defn entry-bounds-before
  "Given the index where a map ENTRY'S VALUE starts, return [key-start
   value-start] — key-start being the beginning of the `:key` token that
   introduces it, including the whitespace and newline in front of it. Removing
   [key-start, value-end) therefore removes the whole entry and the blank line it
   would otherwise leave behind. Returns [nil value-start] when no key precedes.

   Used to withdraw an abandoned `:piano-4 (edit-plane-mark)` entirely, rather
   than leaving a `nil` value for the user to sweep up."
  [text value-start]
  (let [before (subs text 0 value-start)]
    (if-let [m (re-find #"(\s*):([A-Za-z0-9*+!_'?<>=/.-]+)\s*$" before)]
      [(- value-start (count (first m))) value-start]
      [nil value-start])))

(defn append-map-entry
  "The `{…}` block text with `entry` added, laid out the way the emitted forms
   already are: entries one per line, aligned under the first, which sits right
   after `<kw> {`. `key-col` is the column the key token starts at. An empty
   block gets its single entry inline."
  [block entry kw key-col]
  (let [inner (.trim (subs block 1 (dec (count block))))
        align (apply str (repeat (+ key-col (count kw) 2) " "))]
    (if (empty? inner)
      (str "{" entry "}")
      (str "{" (subs block 1 (dec (count block))) "\n" align entry "}"))))
