# Task: Fix Missing "value" Field in Wrap Commands

## Problem

When user says "registralo come cubo", Mistral returns:
```json
{"action": "edit", "operation": "wrap", "target": {"type": "form", "ref": "it"}}
```

But it should return:
```json
{"action": "edit", "operation": "wrap", "target": {"type": "form", "ref": "it"}, "value": "(register cubo $)"}
```

The `value` field is missing.

## File to Modify

### `src/ridley/ai/llm.cljs` — Add emphasis on required "value" field

Find the CRITICAL RULES section and add rule 8:

```clojure
8. WRAP operations MUST include \"value\" field with the wrapper code. The $ symbol marks where the original form goes.
   Example: \"registralo come cubo\" → {\"action\": \"edit\", \"operation\": \"wrap\", \"target\": {...}, \"value\": \"(register cubo $)\"}
   NEVER omit the \"value\" field for wrap operations!
```

Also update the EDIT ACTIONS section to make it clearer:

```clojure
## EDIT ACTIONS — WRAP MUST INCLUDE \"value\" FIELD!

Register (wrap current form) — value field is REQUIRED:
- \"registra come cubo\" / \"register as cube\" → {\"action\": \"edit\", \"operation\": \"wrap\", \"target\": {\"type\": \"form\"}, \"value\": \"(register cubo $)\"}
- \"registra come mio-cubo\" / \"register as my cube\" → {\"action\": \"edit\", \"operation\": \"wrap\", \"target\": {\"type\": \"form\"}, \"value\": \"(register mio-cubo $)\"}

Register previous form — value field is REQUIRED:
- \"registralo come cubo\" / \"register it as cube\" → {\"action\": \"edit\", \"operation\": \"wrap\", \"target\": {\"type\": \"form\", \"ref\": \"it\"}, \"value\": \"(register cubo $)\"}
- \"registralo come test\" / \"register it as test\" → {\"action\": \"edit\", \"operation\": \"wrap\", \"target\": {\"type\": \"form\", \"ref\": \"it\"}, \"value\": \"(register test $)\"}
- \"chiamalo pippo\" / \"call it pippo\" → {\"action\": \"edit\", \"operation\": \"wrap\", \"target\": {\"type\": \"form\", \"ref\": \"it\"}, \"value\": \"(register pippo $)\"}

IMPORTANT: For wrap, the \"value\" contains the wrapper with $ as placeholder:
- User says \"registralo come FOO\" → value is \"(register FOO $)\"
- User says \"chiamalo BAR\" → value is \"(register BAR $)\"
- The $ gets replaced with the original form content
```

## Testing

After updating, say:
1. "cubo 50" → inserts `(box 50)`
2. "registralo come test" → should return JSON with `"value": "(register test $)"`

Check the Ollama response in console to verify the `value` field is present.
