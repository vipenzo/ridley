# Ridley — AI Voice Extension

## Overview

L'estensione AI aggiunge un'interfaccia vocale a Ridley, permettendo di editare codice, navigare la struttura, eseguire query e controllare il viewport tramite comandi vocali. Progettata per funzionare sia su desktop che in VR.

## Architettura

```
                    ┌─────────────────────────────────────────────┐
                    │              SHARED STATE                   │
                    │                                             │
                    │  buffer, cursor, mode, scene, voice...     │
                    │                                             │
                    └──────┬──────────────┬──────────────┬───────┘
                           │              │              │
              ┌────────────▼───┐    ┌─────▼────┐   ┌────▼─────────┐
              │      LLM       │    │ VR Panel │   │    Editor    │
              │                │    │          │   │              │
              │  legge stato   │    │  legge   │   │ applica      │
              │  emette azioni │    │  stato   │   │ azioni       │
              └────────┬───────┘    │  render  │   │ aggiorna     │
                       │            └──────────┘   │ stato        │
                       │                           └──────────────┘
                       │                                  ▲
                       │           Actions                │
                       └──────────────────────────────────┘

Voice Input ──► LLM ──► Actions ──► Editor ──► State ──► VR Panel
                 ▲                               │
                 └───────────────────────────────┘
                        LLM legge nuovo stato
```

### Principi

1. **Single Source of Truth**: uno stato condiviso che tutti i componenti leggono
2. **Unidirectional Data Flow**: solo l'Editor modifica buffer/cursor, tramite Actions
3. **LLM come Interpreter**: trasforma voce in Actions strutturate
4. **VR Panel come View**: renderizza lo stato, nessuna logica

---

## Shared State

```clojure
(def ai-state
  (atom
    {;; ===== BUFFER =====
     :buffer
     {:script ""              ; testo completo definitions panel
      :repl ""}               ; testo corrente REPL input

     ;; ===== CURSOR =====
     :cursor
     {:target :script         ; :script | :repl
      :line 1
      :col 0
      :form-path []           ; path nell'AST [top-level-idx child-idx ...]
      :current-form nil       ; stringa del form sotto cursore
      :parent-form nil}       ; stringa del form contenitore

     ;; ===== SELECTION =====
     :selection nil           ; nil | {:start {:line :col} :end {:line :col}}

     ;; ===== MODE =====
     :mode :structure         ; :structure | :text | :dictation

     ;; ===== SCENE INFO =====
     :scene
     {:registered []          ; [:torus :pippo :base]
      :visible []             ; [:torus :pippo]
      :last-mentioned nil}    ; keyword dell'ultimo oggetto menzionato

     ;; ===== REPL =====
     :repl
     {:last-input nil         ; ultimo comando eseguito
      :last-result nil        ; risultato (stringa)
      :history []}            ; storia comandi

     ;; ===== VOICE =====
     :voice
     {:listening? false
      :partial-transcript ""  ; testo mentre l'utente parla
      :pending-speech nil     ; testo da pronunciare (TTS)
      :last-utterance nil}})) ; ultimo comando riconosciuto
```

### Chi legge/scrive cosa

| Componente | Legge | Scrive |
|------------|-------|--------|
| Voice Input | — | `:voice/partial-transcript`, `:voice/last-utterance` |
| LLM | tutto | emette Actions (non scrive direttamente) |
| Editor | Actions | `:buffer`, `:cursor`, `:selection`, `:mode` |
| REPL Executor | Actions | `:repl/*` |
| Scene Observer | eventi Three.js | `:scene/*` |
| VR Panel | tutto | `:voice/pending-speech` (dopo TTS completato) |
| TTS Engine | `:voice/pending-speech` | `:voice/pending-speech` (clear dopo lettura) |

---

## Action Protocol

Tutte le azioni seguono questo schema:

```typescript
interface Action {
  action: string;
  [key: string]: any;
}
```

L'LLM può emettere una singola azione o un array di azioni:

```json
{"action": "insert", ...}

// oppure

{"actions": [
  {"action": "insert", ...},
  {"action": "execute", ...}
]}
```

---

### InsertAction

Inserisce codice nel buffer (script o REPL).

```json
{
  "action": "insert",
  "code": "(f 20)",
  "target": "script" | "repl",
  "position": "cursor" | "end" | "after-current-form"
}
```

| Campo | Tipo | Default | Descrizione |
|-------|------|---------|-------------|
| `code` | string | required | Codice Clojure da inserire |
| `target` | string | from state | "script" o "repl" |
| `position` | string | "cursor" | Dove inserire |

**Posizioni:**
- `cursor`: alla posizione corrente del cursore
- `end`: alla fine del buffer
- `after-current-form`: dopo il form corrente (new line)

**Esempi:**

| Voce | Action |
|------|--------|
| "vai avanti di 20" | `{"action": "insert", "target": "script", "code": "(f 20)", "position": "after-current-form"}` |
| "scrivi sulla repl manifold di pippo" | `{"action": "insert", "target": "repl", "code": "(manifold? pippo)"}` |
| "aggiungi cerchio 5" | `{"action": "insert", "code": "(circle 5)", "position": "cursor"}` |

---

### EditAction

Modifica codice esistente.

```json
{
  "action": "edit",
  "operation": "replace" | "delete" | "wrap" | "unwrap",
  "target": Target,
  "value": "..."
}
```

```typescript
type Target =
  | { type: "word" }                      // parola sotto cursore
  | { type: "form" }                      // form corrente
  | { type: "selection" }                 // selezione attiva
  | { type: "range", from: Pos, to: Pos } // range esplicito
  | { type: "match", pattern: string }    // cerca nel buffer
```

| Operation | Descrizione | Richiede `value` |
|-----------|-------------|------------------|
| `replace` | Sostituisce target con value | sì |
| `delete` | Cancella target | no |
| `wrap` | Avvolge target, `$` = placeholder per contenuto | sì |
| `unwrap` | Rimuove form esterno, mantiene contenuto | no |

**Esempi:**

| Voce | Action |
|------|--------|
| "cambia in 30" | `{"action": "edit", "operation": "replace", "target": {"type": "word"}, "value": "30"}` |
| "cancella questa forma" | `{"action": "edit", "operation": "delete", "target": {"type": "form"}}` |
| "avvolgi in register pippo" | `{"action": "edit", "operation": "wrap", "target": {"type": "form"}, "value": "(register pippo $)"}` |
| "scarta le parentesi" | `{"action": "edit", "operation": "unwrap", "target": {"type": "form"}}` |

---

### NavigateAction

Muove il cursore o cambia selezione.

```json
{
  "action": "navigate",
  "direction": Direction,
  "mode": "text" | "structure",
  "count": 1
}
```

```typescript
type Direction =
  // Strutturale
  | "parent"     // form contenitore
  | "child"      // primo figlio (entra nel form)
  | "next"       // prossimo sibling
  | "prev"       // sibling precedente
  // Testuale
  | "left"       // carattere/parola a sinistra
  | "right"      // carattere/parola a destra
  | "up"         // riga sopra
  | "down"       // riga sotto
  // Assolute
  | "start"      // inizio buffer/form
  | "end"        // fine buffer/form
```

**Mapping vocale suggerito:**

| Voce | Direction | Mode |
|------|-----------|------|
| "esci" / "fuori" | parent | structure |
| "entra" / "dentro" | child | structure |
| "prossimo" / "giù" | next | structure |
| "precedente" / "su" | prev | structure |
| "parola avanti" | right | text |
| "parola indietro" | left | text |
| "inizio" | start | — |
| "fine" | end | — |

**Esempi:**

| Voce | Action |
|------|--------|
| "prossimo" | `{"action": "navigate", "direction": "next", "mode": "structure"}` |
| "esci" | `{"action": "navigate", "direction": "parent", "mode": "structure"}` |
| "tre parole avanti" | `{"action": "navigate", "direction": "right", "mode": "text", "count": 3}` |

---

### ModeAction

Cambia modalità di editing.

```json
{
  "action": "mode",
  "set": "structure" | "text" | "dictation"
}
```

| Mode | Comportamento |
|------|---------------|
| `structure` | Navigazione per form (paredit-like) |
| `text` | Navigazione per caratteri/parole |
| `dictation` | Input vocale diretto → testo |

**Esempi:**

| Voce | Action |
|------|--------|
| "modalità testo" | `{"action": "mode", "set": "text"}` |
| "modalità struttura" | `{"action": "mode", "set": "structure"}` |
| "dettatura" | `{"action": "mode", "set": "dictation"}` |

---

### ExecuteAction

Esegue il codice.

```json
{
  "action": "execute",
  "target": "script" | "repl"
}
```

| Target | Comportamento |
|--------|---------------|
| `script` | Rivaluta tutto il definitions panel (Cmd+Enter) |
| `repl` | Esegue il contenuto corrente del REPL |

**Esempi:**

| Voce | Action |
|------|--------|
| "esegui" | `{"action": "execute", "target": "script"}` |
| "esegui repl" / "vai" | `{"action": "execute", "target": "repl"}` |

---

### TargetAction

Cambia il buffer target (dove si scrive).

```json
{
  "action": "target",
  "set": "script" | "repl"
}
```

**Esempi:**

| Voce | Action |
|------|--------|
| "vai alla repl" | `{"action": "target", "set": "repl"}` |
| "torna allo script" | `{"action": "target", "set": "script"}` |

---

### SelectAction

Crea o modifica selezione.

```json
{
  "action": "select",
  "what": "form" | "word" | "line" | "all" | "none",
  "extend": false
}
```

**Esempi:**

| Voce | Action |
|------|--------|
| "seleziona forma" | `{"action": "select", "what": "form"}` |
| "seleziona tutto" | `{"action": "select", "what": "all"}` |
| "deseleziona" | `{"action": "select", "what": "none"}` |

---

### QueryAction (Tier 2+)

Richiede informazioni o spiegazioni.

```json
{
  "action": "query",
  "type": "help" | "explain" | "suggest" | "check",
  "subject": "current" | "selection" | "function:name" | "object:name"
}
```

Queste azioni non modificano il buffer, ma popolano `:voice/pending-speech` con la risposta.

**Esempi:**

| Voce | Action |
|------|--------|
| "cos'è questo?" | `{"action": "query", "type": "explain", "subject": "current"}` |
| "come funziona loft?" | `{"action": "query", "type": "help", "subject": "function:loft"}` |
| "pippo è manifold?" | Tier 1: `insert` + `execute` su REPL; Tier 2+: `query` con risposta vocale |

---

### SpeakAction

Fa pronunciare un testo (TTS).

```json
{
  "action": "speak",
  "text": "Inserito forward 20"
}
```

L'LLM può usarlo per confermare azioni o rispondere a query.

---

## LLM Context

Quando l'LLM riceve un comando vocale, gli viene passato:

```json
{
  "utterance": "vai avanti di 20",
  "state": {
    "mode": "structure",
    "target": "script",
    "cursor": {
      "line": 3,
      "col": 5,
      "current_form": "(th 45)",
      "parent_form": "(extrude (circle 5) (f 10) (th 45))"
    },
    "selection": null,
    "registered": ["torus", "pippo"],
    "visible": ["torus", "pippo"],
    "last_mentioned": "pippo",
    "last_repl_result": null
  },
  "tier": "basic"
}
```

L'LLM risponde con una o più Actions.

---

## Model Tiers

### Tier 1: Basic (Ollama 7B, local)

**Capacità**: parsing deterministico, nessun ragionamento

**Funzionalità:**
- ✅ Comandi movimento → codice (`"avanti 20"` → `(f 20)`)
- ✅ Navigazione strutturale
- ✅ Editing vi-like
- ✅ Cambio modalità
- ✅ Execute
- ❌ Dialogo guidato
- ❌ Spiegazioni
- ❌ Ragionamento spaziale

**Prompt style**: few-shot rigido, output JSON only

### Tier 2: Guided (Haiku, GPT-4o-mini, Llama 70B)

**Capacità**: comprensione semantica, dialogo

**Funzionalità aggiuntive:**
- ✅ Help interattivo ("come faccio X?" → guida passo-passo)
- ✅ Clarification ("che raggio?")
- ✅ Spiegazioni parametri
- ✅ Correzione errori con suggerimenti

### Tier 3: Full (Sonnet, Opus, GPT-4)

**Capacità**: ragionamento spaziale, creatività

**Funzionalità aggiuntive:**
- ✅ Generazione da descrizioni vaghe ("fammi una maniglia")
- ✅ Debug ("questo sembra storto, perché?")
- ✅ Ottimizzazione codice
- ✅ Analisi geometrica

---

## VR Panel

Il pannello VR legge direttamente dallo shared state e renderizza:

```
┌─────────────────────────────────────────────────┐
│  [MODE] target                    🎤            │
├─────────────────────────────────────────────────┤
│                                                 │
│    (register pippo                              │
│      (extrude                                   │
│        ▶ (circle 5) ◀                           │
│        (f 20)))                                 │
│                                                 │
├─────────────────────────────────────────────────┤
│  🎤 "vai avanti..."                             │
├─────────────────────────────────────────────────┤
│  ✓ Inserito forward 20                          │
└─────────────────────────────────────────────────┘
```

| Zona | Source |
|------|--------|
| Header | `:mode`, `:cursor/target`, `:voice/listening?` |
| Code view | `:buffer/*`, `:cursor/*`, `:selection` |
| Transcript | `:voice/partial-transcript` |
| Feedback | `:voice/pending-speech` |

### Code View Logic

1. Prendi il buffer corretto (`:buffer/script` o `:buffer/repl`)
2. Estrai un excerpt intorno al cursore (±5 righe)
3. Evidenzia `:cursor/current-form`
4. Se c'è `:selection`, evidenzia il range

---

## Response Format

Dopo l'esecuzione di un'azione, l'Editor può emettere una risposta per il feedback:

```json
{
  "status": "ok" | "error" | "need_clarification",
  "message": "Inserito (f 20)",
  "speak": "Inserito forward 20",
  "result": "...",
  "error": "..."
}
```

Per REPL execute:

```json
{
  "status": "ok",
  "message": "REPL: (manifold? pippo) => true",
  "speak": "Sì, pippo è manifold",
  "result": "true"
}
```

---

## Configuration

```clojure
{:ai
 {:provider :ollama           ; :ollama | :anthropic | :openai
  :model "llama3.2:7b"
  :tier :basic                ; :basic | :guided | :full (auto or manual)
  :endpoint "http://localhost:11434"}

 :voice
 {:input :web-speech          ; :web-speech | :whisper-local | :whisper-api
  :output :web-speech         ; :none | :web-speech | :elevenlabs
  :language "it-IT"
  :continuous true}           ; ascolto continuo o push-to-talk

 :ui
 {:show-transcript true
  :show-code-panel true
  :code-panel-lines 10}}      ; righe visibili nel VR panel
```

---

## Implementation Phases

### Phase 1: Foundation
- [ ] Definire `ai-state` atom
- [ ] Editor: subscribe a state, applica Actions
- [ ] Wire: voice input → state update

### Phase 2: LLM Integration (Tier 1)
- [ ] System prompt per comandi base
- [ ] Action parser e validator
- [ ] Ollama integration

### Phase 3: VR Panel
- [ ] Three.js text panel che legge state
- [ ] Evidenziazione cursor/form
- [ ] Transcript live

### Phase 4: Tier 2+ Features
- [ ] Anthropic/OpenAI integration
- [ ] Guided dialogs
- [ ] Query handling

### Phase 5: Polish
- [ ] TTS feedback
- [ ] Error recovery
- [ ] Continuous listening mode

---

## Open Questions

1. **Undo**: come gestiamo undo vocale? Stack di stati?
2. **Multimodal**: pointing controller + voce ("estudi questo" + point)?
3. **Macros**: permettere all'utente di definire comandi vocali custom?
4. **Offline**: Tier 1 completamente offline con Whisper + Ollama locale?
