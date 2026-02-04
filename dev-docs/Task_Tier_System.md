# Task: Implementare Tier System per AI Integration

## Contesto

Abbiamo un sistema `/ai` funzionante con un singolo system prompt (Tier 1). Vogliamo:
1. Auto-detection del tier basato sul modello
2. Selezione manuale nel pannello Settings
3. Prompt diversi per Tier 1 e Tier 2
4. Tier 2 riceve il contesto dello script e può chiedere chiarimenti

---

## 1. Auto-detection modello → tier

Creare una lookup table in `src/ridley/ai/core.cljs`:

```clojure
(def model-tier-map
  {;; Tier 1: modelli piccoli
   "qwen2.5:3b" :tier-1
   "llama3.2:3b" :tier-1
   "phi3:mini" :tier-1
   "gemma2:2b" :tier-1
   
   ;; Tier 2: modelli medi
   "mistral" :tier-2
   "mistral:8b" :tier-2
   "llama3.2:8b" :tier-2
   "qwen2.5:7b" :tier-2
   "qwen2.5:14b" :tier-2
   "deepseek-coder:7b" :tier-2
   
   ;; Tier 3: modelli grandi
   "llama-3.3-70b-versatile" :tier-3  ;; Groq
   "llama3.3:70b" :tier-3
   "qwen2.5:32b" :tier-3
   "deepseek-coder:33b" :tier-3
   "claude-3-sonnet" :tier-3
   "claude-3-opus" :tier-3
   "gpt-4" :tier-3
   "gpt-4o" :tier-3})

(defn detect-tier [model-name]
  (or (get model-tier-map model-name)
      ;; Fallback: cerca pattern nel nome
      (cond
        (re-find #"70b|72b|65b" model-name) :tier-3
        (re-find #"33b|32b|30b|34b" model-name) :tier-3
        (re-find #"13b|14b|15b|8b|7b" model-name) :tier-2
        :else :tier-1)))
```

---

## 2. Aggiornare Settings Panel

Nel pannello settings, aggiungere dropdown Tier:

```
Provider: [Groq ▼]
API Key:  [••••••••]  
Model:    [llama-3.3-70b-versatile ▼]
Tier:     [Auto ▼]    ← nuovo campo
          
          ℹ️ Detected: Tier 3 | Using: Tier 3
```

Opzioni dropdown: `Auto`, `Tier 1`, `Tier 2`, `Tier 3`

Mostrare warning se l'utente forza tier alto su modello piccolo:
```
⚠️ Detected: Tier 1 | Using: Tier 3 (may produce poor results)
```

Salvare in config:
```clojure
{:ai {:provider :groq
      :model "llama-3.3-70b-versatile"
      :tier :auto}}  ;; :auto | :tier-1 | :tier-2 | :tier-3
```

---

## 3. System Prompts

Creare/aggiornare `src/ridley/ai/prompts.cljs` con tre prompt:

### tier-1-prompt (già esistente, mantenerlo)

Il prompt attuale, output solo codice.

### tier-2-prompt (nuovo)

```clojure
(def tier-2-prompt
  "You are a code generator for Ridley, a 3D CAD tool based on turtle graphics. You convert natural language descriptions into Clojure code.

## Your Task
Generate valid Clojure code OR ask for clarification if needed. 

## Response Format

ALWAYS respond with valid JSON in one of these formats:

1. When you can generate code:
{\"type\": \"code\", \"code\": \"(register ...)\"}

2. When you need clarification:
{\"type\": \"clarification\", \"question\": \"What radius do you want?\"}

NO markdown, NO backticks around the JSON, NO explanations outside the JSON.

## Context

You will receive:
- The user's request
- The current script content (if any) in <script> tags

You can reference objects defined with (register name ...) in the script.

## The Turtle
The turtle is a cursor in 3D space with:
- Position (where it is)
- Heading (which direction it faces, starts facing +X)
- Up (which way is up for the turtle, starts +Z)

The turtle starts at origin [0,0,0] facing +X with up +Z.

## Core Commands

MOVEMENT:
(f distance)     - Move forward (negative = backward)
(th angle)       - Turn horizontally (yaw), degrees. Positive = left
(tv angle)       - Turn vertically (pitch), degrees. Positive = up
(tr angle)       - Roll, degrees

STATE:
(push-state)     - Remember current position and orientation
(pop-state)      - Return to last remembered position
(reset)          - Go back to origin, facing +X

SHAPES (2D profiles for extrusion):
These ARE shapes, use directly with extrude:
(circle radius)
(rect width height)
(star points outer-radius inner-radius)

To create a custom shape from turtle movements:
(def my-path (path (f 10) (th 90) (f 5) ...))
(def my-shape (path-to-shape my-path))

PRIMITIVES (3D solids at turtle position):
(box size)                    - Cube
(box width depth height)      - Rectangular box
(sphere radius)
(cyl radius height)           - Cylinder
(cone bottom-radius top-radius height)

FUNCTIONAL PLACEMENT (preferred for patterns):
(attach mesh transforms...)   - Create mesh with transformations applied
Example: (attach (box 10) (th 45) (f 30))

REGISTRATION (makes object visible):
(register name mesh)          - Name and show a mesh
(register name (for [...] ...)) - Register multiple objects as array

EXTRUSION:
(extrude shape movements...)  - Create 3D by sweeping shape
(extrude-closed shape path)   - Closed loop (torus-like)

BOOLEANS:
(mesh-union a b)
(mesh-difference a b)         - Subtract b from a
(mesh-intersection a b)

CRITICAL: Boolean operations work on meshes, NOT arrays.
To subtract/union multiple objects, first combine them:
(def combined (mesh-union (for [...] ...mesh...)))
(mesh-difference base combined)

PATHS:
(path movements...)           - Record a path
(def name (path ...))         - Store path in variable

ARCS:
(arc-h radius angle)          - Horizontal arc
(arc-v radius angle)          - Vertical arc

## COMMON MISTAKES TO AVOID

SHAPES - circle, rect, star are ALREADY shapes:
WRONG: (path-to-shape (star 5 15 7))
WRONG: (path-to-shape (circle 10))
RIGHT: (extrude (star 5 15 7) (f 10))
RIGHT: (extrude (circle 10) (f 20))

EXTRUDE - first argument is SHAPE, second is PATH or movements:
WRONG: (extrude my-path (circle 5))
RIGHT: (extrude (circle 5) my-path)

REGISTER in loops - use for, never dotimes:
WRONG: (dotimes [_ 8] (register cube (box 10)))
RIGHT: (register cubes (for [i (range 8)] (attach (box 10) ...)))

BOOLEANS - can't mix single mesh with array:
WRONG: (mesh-union base (for [...] ...))
RIGHT: (mesh-union base (mesh-union (for [...] ...)))

VARIABLE NAMES - don't use reserved names:
WRONG: (def shape ...)
WRONG: (def path ...)
RIGHT: (def my-shape ...)
RIGHT: (def profile ...)

FINAL MESH - always use register to make visible, not def:
WRONG: (def my-result (extrude ...))
RIGHT: (register my-result (extrude ...))

## Pattern Templates

Circle arrangement:
(register objects
  (for [i (range N)]
    (attach (PRIMITIVE ...) (th (* i (/ 360 N))) (f RADIUS))))

Grid arrangement:
(register objects
  (for [row (range ROWS)
        col (range COLS)]
    (attach (PRIMITIVE ...) (f (* col SPACING)) (th 90) (f (* row SPACING)))))

Boolean with multiple cutters:
(def cutters
  (mesh-union
    (for [i (range N)]
      (attach (cyl HOLE-RADIUS HEIGHT) (th (* i (/ 360 N))) (f RADIUS)))))
(register result (mesh-difference base cutters))

## Context-Aware Examples

<script>
(register base (box 40))
</script>

INPUT: aggiungi un foro cilindrico al centro di base
OUTPUT:
{\"type\": \"code\", \"code\": \"(register base (mesh-difference base (cyl 8 42)))\"}

<script>
(register cubes
  (for [i (range 4)]
    (attach (box 10) (th (* i 90)) (f 30))))
</script>

INPUT: fai la stessa cosa ma con sfere
OUTPUT:
{\"type\": \"code\", \"code\": \"(register spheres\\n  (for [i (range 4)]\\n    (attach (sphere 5) (th (* i 90)) (f 30))))\"}

<script>
</script>

INPUT: aggiungi delle sfere
OUTPUT:
{\"type\": \"clarification\", \"question\": \"Quante sfere e di che dimensione?\"}

<script>
(register column (cyl 5 30))
</script>

INPUT: mettilo più in alto
OUTPUT:
{\"type\": \"code\", \"code\": \"(register column (attach (cyl 5 30) (tv 90) (f 20)))\"}

INPUT: metti qualcosa sopra
OUTPUT:
{\"type\": \"clarification\", \"question\": \"Cosa vuoi mettere sopra? Una sfera, un cubo, un altro cilindro?\"}

<script>
</script>

INPUT: 8 cubi di lato 15 in cerchio raggio 50
OUTPUT:
{\"type\": \"code\", \"code\": \"(register cubes\\n  (for [i (range 8)]\\n    (attach (box 15) (th (* i 45)) (f 50))))\"}

<script>
</script>

INPUT: cubo lato 30 con foro cilindrico passante raggio 8
OUTPUT:
{\"type\": \"code\", \"code\": \"(register cubo-forato\\n  (mesh-difference\\n    (box 30)\\n    (cyl 8 32)))\"}

## Guidelines

1. Prefer generating code with reasonable defaults over asking questions
2. Only ask clarification for genuinely ambiguous requests
3. When referencing existing objects, use their registered names
4. To modify an existing object, re-register it with the same name
5. Output ONLY the JSON object, nothing else")
```

### tier-3-prompt (placeholder per ora)

```clojure
(def tier-3-prompt
  ;; Per ora uguale a tier-2, in futuro aggiungeremo:
  ;; - Capacità di debug ("perché non funziona?")
  ;; - Generazione creativa ("fammi una sedia")
  ;; - Analisi spaziale complessa
  tier-2-prompt)
```

---

## 4. Modificare la chiamata LLM

In `src/ridley/ai/core.cljs`, modificare `generate-code` (o equivalente):

```clojure
(defn get-effective-tier []
  (let [config (:ai @app-state)
        manual-tier (:tier config)
        detected (detect-tier (:model config))]
    (if (= manual-tier :auto)
      detected
      manual-tier)))

(defn get-system-prompt [tier]
  (case tier
    :tier-1 tier-1-prompt
    :tier-2 tier-2-prompt
    :tier-3 tier-3-prompt
    tier-1-prompt))

(defn build-messages [user-prompt tier]
  (let [system (get-system-prompt tier)
        ;; Per Tier 2+, aggiungi contesto script
        script-context (when (#{:tier-2 :tier-3} tier)
                        (get-current-script-content))
        user-content (if script-context
                       (str "<script>\n" script-context "\n</script>\n\n" user-prompt)
                       user-prompt)]
    [{:role "system" :content system}
     {:role "user" :content user-content}]))
```

---

## 5. Gestire risposta Tier 2

Per Tier 2+, la risposta è JSON. Parsare e gestire:

```clojure
(defn handle-ai-response [response tier]
  (if (= tier :tier-1)
    ;; Tier 1: risposta è codice diretto
    (append-to-script response)
    ;; Tier 2+: risposta è JSON
    (let [parsed (try (js/JSON.parse response)
                      (catch :default _ nil))]
      (if parsed
        (case (.-type parsed)
          "code" (append-to-script (.-code parsed))
          "clarification" (show-clarification (.-question parsed))
          (show-error "Risposta AI non valida"))
        ;; Fallback: tratta come codice
        (append-to-script response)))))

(defn show-clarification [question]
  ;; Mostra la domanda nel REPL o in un'area dedicata
  ;; L'utente può rispondere con un altro /ai
  (append-to-repl-output (str "🤖 " question)))
```

---

## 6. UI per clarification

Quando l'AI chiede chiarimenti:
- Mostrare la domanda nel REPL output (o area messaggi)
- L'utente risponde con `/ai <risposta>`
- Il contesto precedente viene mantenuto? (opzionale, per v2)

Per ora, semplice:
```
> /ai aggiungi delle sfere
🤖 Quante sfere e di che dimensione?
> /ai 6 sfere raggio 5 in cerchio
;; AI: 6 sfere raggio 5 in cerchio
(register spheres ...)
```

---

## File da modificare/creare

- `src/ridley/ai/prompts.cljs` — aggiungere tier-2-prompt, tier-3-prompt
- `src/ridley/ai/core.cljs` — detect-tier, get-effective-tier, build-messages, handle-ai-response
- Settings panel — aggiungere dropdown Tier con Auto/1/2/3
- REPL output — gestire messaggi clarification (🤖 prefix)

---

## Test

1. Con Tier 1 (qwen 3b): `/ai 6 cubi in cerchio` → deve produrre codice
2. Con Tier 2 (llama 70b): `/ai aggiungi un foro a base` (con base nello script) → deve modificare base
3. Con Tier 2: `/ai aggiungi delle cose` → deve chiedere chiarimento
4. Forzare Tier 1 su modello grande → deve funzionare (output solo codice)
5. Warning quando si forza Tier 3 su modello piccolo
