(ns ridley.manual.clojure-core-index
  "Annotated subset of clojure.core symbols most used in Ridley examples.
   Same shape as ridley.manual.reference-index entries (subset of fields).
   Editable by hand; not generated.")

(def clojure-core-index
  {"let"     {:name        "let"
              :signature   "(let [name value …] & body)"
              :description "Bind values to local names and evaluate the body with those bindings in scope. Binding form is a vector of name/value pairs; later bindings can refer to earlier ones."}
   "def"     {:name        "def"
              :signature   "(def name value)"
              :description "Create (or update) a global var in the current namespace bound to the given value."}
   "defn"    {:name        "defn"
              :signature   "(defn name [params*] & body)"
              :description "Define a named function. Combines `def` and `fn` — binds the var `name` to a function with the given parameter list and body."}
   "dotimes" {:name        "dotimes"
              :signature   "(dotimes [i n] & body)"
              :description "Execute the body `n` times, with `i` bound to 0, 1, …, n-1. Returns nil. Used for side effects; for value collection use `for` or `mapv`."}
   "loop"    {:name        "loop"
              :signature   "(loop [name value …] & body)"
              :description "Like `let`, but establishes a recursion target — `recur` jumps back to the loop with new bindings. Used for explicit tail-recursive iteration."}
   "for"     {:name        "for"
              :signature   "(for [name coll …] expr)"
              :description "List comprehension. Evaluates `expr` for each combination of bindings drawn from the collections. Returns a lazy sequence. Supports `:when` and `:while` modifier clauses."}
   "map"     {:name        "map"
              :signature   "(map f coll & colls)"
              :description "Apply `f` to each element of `coll` (or to corresponding elements of multiple colls) and return a lazy sequence of the results."}
   "reduce"  {:name        "reduce"
              :signature   "(reduce f coll) / (reduce f init coll)"
              :description "Combine the elements of `coll` left-to-right with `f`, an accumulating two-arg function. Two-arity form starts from `(f (first coll) (second coll))`; three-arity form starts from `init`."}
   "if"      {:name        "if"
              :signature   "(if test then else?)"
              :description "Branch on the truthiness of `test`. Evaluates and returns `then` if truthy, `else` otherwise. Without `else` the false branch returns nil. Only `false` and `nil` are falsy."}
   "when"    {:name        "when"
              :signature   "(when test & body)"
              :description "If `test` is truthy, evaluate the body and return the last value; otherwise return nil. Use for one-armed conditionals with side effects."}
   "cond"    {:name        "cond"
              :signature   "(cond test1 expr1 test2 expr2 …)"
              :description "Multi-branch conditional. Evaluates each `test` in order and returns the matching `expr`. Use `:else` as the final test for a default branch."}
   "case"    {:name        "case"
              :signature   "(case expr v1 r1 v2 r2 … default?)"
              :description "Constant-time dispatch on the value of `expr`. Compares with `=` against literal `v_i` (no evaluation); returns matching `r_i`. Optional trailing default expression. Throws if no match and no default."}
   "range"   {:name        "range"
              :signature   "(range) / (range end) / (range start end step?)"
              :description "Return a lazy sequence of numbers. `(range n)` produces 0…n-1; `(range a b)` produces a…b-1; `(range a b s)` steps by `s`. Zero-arity is infinite."}
   "vec"     {:name        "vec"
              :signature   "(vec coll)"
              :description "Coerce a collection (seq, list, lazy-seq, array) into a vector."}
   "first"   {:name        "first"
              :signature   "(first coll)"
              :description "Return the first element of `coll`, or nil if empty."}
   "rest"    {:name        "rest"
              :signature   "(rest coll)"
              :description "Return a (possibly lazy) seq of all elements after the first. Returns an empty seq for empty or single-element input — never nil."}
   "last"    {:name        "last"
              :signature   "(last coll)"
              :description "Return the last element of `coll`, or nil if empty. Linear time on non-counted collections."}
   "concat"  {:name        "concat"
              :signature   "(concat & colls)"
              :description "Return a lazy seq of the concatenation of the given collections."}
   "count"   {:name        "count"
              :signature   "(count coll)"
              :description "Return the number of items in `coll` (or characters in a string). Constant time for vectors, maps, sets, and strings; linear for seqs."}
   "assoc"   {:name        "assoc"
              :signature   "(assoc m k v & kvs)"
              :description "Return a new associative collection with one or more key/value pairs added or updated. Works on maps and vectors (with integer keys)."}
   "get"     {:name        "get"
              :signature   "(get m k) / (get m k not-found)"
              :description "Look up the value associated with `k` in `m`. Returns nil (or `not-found`) when missing. Works on maps, vectors, sets, and strings."}
   "get-in"  {:name        "get-in"
              :signature   "(get-in m ks) / (get-in m ks not-found)"
              :description "Walk nested associative structures by the key path `ks` (vector of keys). Returns the value or `not-found` (default nil) if any step is missing."}})
