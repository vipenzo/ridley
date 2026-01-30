# Task: Handle "it/lo" Reference in AI Edit Commands

## Overview

When user says "registralo" (register it), the AI should operate on the previous form (before cursor) rather than the form at cursor. This is needed because after inserting code, the cursor is positioned after the closing parenthesis.

## Files to Modify

### 1. `src/ridley/core.cljs` — Update ai-edit-code to handle "it/lo" reference

Find the `ai-edit-code` function and update it:

```clojure
(defn- ai-edit-code
  "Edit code based on operation and target."
  [{:keys [operation target value]}]
  (when-let [view @editor-view]
    (let [;; Check if target references "it/lo/last" - meaning previous form
          ref (get target "ref")  ; target comes as JS object with string keys
          use-previous? (and (= (get target "type") "form")
                             (contains? #{"it" "lo" "last" "questo" "this"} ref))
          ;; Get the appropriate form
          the-form (if use-previous?
                     (cm/get-previous-form view)
                     (case (get target "type")
                       "form" (cm/get-form-at-cursor view)
                       "word" (cm/get-word-at-cursor view)
                       "selection" (cm/get-selection view)
                       nil))]
      
      (when the-form
        (case operation
          :replace
          (cm/replace-range view (:from the-form) (:to the-form) value)
          
          :delete
          (cm/delete-range view (:from the-form) (:to the-form))
          
          :wrap
          (let [wrapped (clojure.string/replace value "$" (:text the-form))]
            (cm/replace-range view (:from the-form) (:to the-form) wrapped))
          
          :unwrap
          (when (and (clojure.string/starts-with? (:text the-form) "(")
                     (clojure.string/ends-with? (:text the-form) ")"))
            (let [inner (subs (:text the-form) 1 (dec (count (:text the-form))))
                  content (clojure.string/trim (clojure.string/replace-first inner #"^\S+\s*" ""))]
              (cm/replace-range view (:from the-form) (:to the-form) content)))
          
          (js/console.warn "AI edit: unknown operation" operation)))
      
      (when-not the-form
        (js/console.warn "AI edit: no form found" (if use-previous? "(looking for previous)" "")))
      
      ;; Update AI focus after edit
      (cm/update-ai-focus! view)
      
      ;; Auto-save after edit
      (save-to-storage)
      (send-script-debounced))))
```

### 2. `src/ridley/ai/llm.cljs` — Add examples with "ref" field to system prompt

Find the EDIT ACTIONS section in the system-prompt and update the register examples:

```clojure
;; Find this section and update:

## EDIT ACTIONS — Modify existing code

Register (wrap current form):
- \"registra come cubo\" / \"register as cube\" → {\"action\": \"edit\", \"operation\": \"wrap\", \"target\": {\"type\": \"form\"}, \"value\": \"(register cubo $)\"}
- \"registra come mio cubo\" / \"register as my cube\" → {\"action\": \"edit\", \"operation\": \"wrap\", \"target\": {\"type\": \"form\"}, \"value\": \"(register mio-cubo $)\"}

Register previous form (when cursor is after the form):
- \"registralo\" / \"register it\" → {\"action\": \"edit\", \"operation\": \"wrap\", \"target\": {\"type\": \"form\", \"ref\": \"it\"}, \"value\": \"(register nome $)\"}
- \"registralo come cubo\" / \"register it as cube\" → {\"action\": \"edit\", \"operation\": \"wrap\", \"target\": {\"type\": \"form\", \"ref\": \"it\"}, \"value\": \"(register cubo $)\"}
- \"chiamalo test\" / \"call it test\" / \"name it test\" → {\"action\": \"edit\", \"operation\": \"wrap\", \"target\": {\"type\": \"form\", \"ref\": \"it\"}, \"value\": \"(register test $)\"}
- \"wrappalo\" / \"wrap it\" → {\"action\": \"edit\", \"operation\": \"wrap\", \"target\": {\"type\": \"form\", \"ref\": \"it\"}, \"value\": \"($)\"}

Delete previous form:
- \"cancellalo\" / \"delete it\" → {\"action\": \"edit\", \"operation\": \"delete\", \"target\": {\"type\": \"form\", \"ref\": \"it\"}}
- \"eliminalo\" / \"remove it\" → {\"action\": \"edit\", \"operation\": \"delete\", \"target\": {\"type\": \"form\", \"ref\": \"it\"}}
```

Also add a rule in the CRITICAL RULES section:

```
6. When user says "it/lo/questo" (registralo, delete it, wrap it), add "ref": "it" to target
```

## Testing

1. Say "cubo 50" → inserts `(box 50)`, cursor after `)`
2. Say "registralo come test" → should wrap to `(register test (box 50))`
3. Say "cubo 30" → inserts another `(box 30)`
4. Say "cancellalo" → should delete `(box 30)`

## Notes

- The `target` object comes from JSON so keys are strings, not keywords
- Use `(get target "ref")` not `(:ref target)`
- The ref values to check: "it", "lo", "last", "questo", "this"
