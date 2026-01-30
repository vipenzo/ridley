# Task: Implement Structured Replace for Form Elements

## Overview

Add ability to replace specific elements within a Clojure form by index:
- Element 0 = function/command name
- Element 1 = first argument
- Element 2 = second argument
- etc.

Example: `(register cubo (box 30))`
- Element 0: `register`
- Element 1: `cubo`
- Element 2: `(box 30)`

## Voice Commands

- "cambia primo argomento in base" / "change first argument to base" → element: 1
- "cambia secondo argomento in 50" / "change second argument to 50" → element: 2
- "cambia funzione in def" / "change function to def" → element: 0
- "cambia nome in pippo" / "change name to pippo" → element: 1 (alias for first arg)
- "cambia valore in 100" / "change value to 100" → element: -1 (last element)

## Files to Modify

### 1. `src/ridley/editor/codemirror.cljs` — Add form parsing function

```clojure
(defn parse-form-elements
  "Parse a form string into its elements, respecting nested parentheses.
   Returns vector of element strings.
   Example: '(register cubo (box 30))' → ['register' 'cubo' '(box 30)']"
  [form-text]
  (when (and form-text
             (str/starts-with? form-text "(")
             (str/ends-with? form-text ")"))
    (let [inner (subs form-text 1 (dec (count form-text)))  ; remove outer parens
          len (count inner)]
      (loop [i 0
             depth 0
             current ""
             elements []]
        (if (>= i len)
          ;; End of string — add last element if non-empty
          (if (seq (str/trim current))
            (conj elements (str/trim current))
            elements)
          (let [ch (nth inner i)]
            (cond
              ;; Opening paren — increase depth
              (= ch \()
              (recur (inc i) (inc depth) (str current ch) elements)
              
              ;; Closing paren — decrease depth
              (= ch \))
              (recur (inc i) (dec depth) (str current ch) elements)
              
              ;; Space at depth 0 — element boundary
              (and (= ch \space) (zero? depth))
              (if (seq (str/trim current))
                (recur (inc i) depth "" (conj elements (str/trim current)))
                (recur (inc i) depth "" elements))
              
              ;; Any other char — accumulate
              :else
              (recur (inc i) depth (str current ch) elements))))))))

(defn replace-form-element
  "Replace element at index in a form string.
   Index 0 = function name, 1 = first arg, etc.
   Negative index counts from end (-1 = last element).
   Returns new form string or nil if index out of bounds."
  [form-text element-index new-value]
  (when-let [elements (parse-form-elements form-text)]
    (let [idx (if (neg? element-index)
                (+ (count elements) element-index)
                element-index)]
      (when (and (>= idx 0) (< idx (count elements)))
        (let [new-elements (assoc elements idx new-value)]
          (str "(" (str/join " " new-elements) ")"))))))
```

### 2. `src/ridley/core.cljs` — Add replace-structured handler in ai-edit-code

Update the `ai-edit-code` function to handle the new operation:

```clojure
(defn- ai-edit-code
  "Edit code based on operation and target."
  [{:keys [operation target value element]}]
  (when-let [view @editor-view]
    (let [ref (get target "ref")
          use-previous? (and (= (get target "type") "form")
                             (contains? #{"it" "lo" "last" "questo" "this"} ref))
          the-form (if use-previous?
                     (cm/get-previous-form view)
                     (cm/get-form-at-cursor view))]
      
      (when the-form
        (case operation
          ;; ... existing operations ...
          
          :replace-structured
          (when (and element value)
            (let [new-form (cm/replace-form-element (:text the-form) element value)]
              (when new-form
                (cm/replace-range view (:from the-form) (:to the-form) new-form))))
          
          ;; ... rest of cases
          ))
      
      ;; Update AI focus after edit
      (cm/update-ai-focus! view)
      (save-to-storage)
      (send-script-debounced))))
```

Note: The `element` field comes from the LLM action JSON, needs to be extracted. Update the destructuring to handle it:

```clojure
;; At top of function, element may come as string key from JSON
(let [element-idx (or (get action "element") (:element action))
      ;; ... rest
```

### 3. `src/ridley/ai/llm.cljs` — Add examples to system prompt

Add a new section after EDIT ACTIONS:

```
## STRUCTURED EDIT — Change specific elements in a form

Elements are numbered: 0=function, 1=first-arg, 2=second-arg, etc.
Use -1 for last element.

- \"cambia primo argomento in base\" / \"change first argument to base\" → {\"action\": \"edit\", \"operation\": \"replace-structured\", \"target\": {\"type\": \"form\"}, \"element\": 1, \"value\": \"base\"}
- \"cambia secondo argomento in 50\" / \"change second argument to 50\" → {\"action\": \"edit\", \"operation\": \"replace-structured\", \"target\": {\"type\": \"form\"}, \"element\": 2, \"value\": \"50\"}
- \"cambia la funzione in def\" / \"change function to def\" → {\"action\": \"edit\", \"operation\": \"replace-structured\", \"target\": {\"type\": \"form\"}, \"element\": 0, \"value\": \"def\"}
- \"cambia il nome in pippo\" / \"change name to pippo\" → {\"action\": \"edit\", \"operation\": \"replace-structured\", \"target\": {\"type\": \"form\"}, \"element\": 1, \"value\": \"pippo\"}
- \"cambia l'ultimo argomento in 100\" / \"change last argument to 100\" → {\"action\": \"edit\", \"operation\": \"replace-structured\", \"target\": {\"type\": \"form\"}, \"element\": -1, \"value\": \"100\"}

With \"it/lo\" reference (previous form):
- \"cambia il suo primo argomento in test\" / \"change its first argument to test\" → {\"action\": \"edit\", \"operation\": \"replace-structured\", \"target\": {\"type\": \"form\", \"ref\": \"it\"}, \"element\": 1, \"value\": \"test\"}
```

Also add to CRITICAL RULES:

```
10. For replace-structured: element 0=function, 1=first-arg, 2=second-arg, -1=last. MUST include both \"element\" and \"value\" fields.
```

## Testing

1. Write `(register cubo (box 30))` in editor
2. Position cursor inside the form
3. Say "cambia primo argomento in base"
4. Should become `(register base (box 30))`

More tests:
- "cambia secondo argomento in (sphere 20)" → `(register cubo (sphere 20))`
- "cambia la funzione in def" → `(def cubo (box 30))`
- "cambia l'ultimo argomento in 50" → `(register cubo 50)`

## Notes

- The parser handles nested forms: `(foo (bar baz))` → `["foo", "(bar baz)"]`
- Whitespace is normalized (multiple spaces → single space)
- Invalid indices return nil and operation is skipped
- Works with "it/lo" reference for previous form
