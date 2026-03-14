# Spec: Prompt Editor & Explicit AI History

## Overview

Two related features that rendono più trasparente e controllabile l'interazione con i modelli AI in Ridley:

1. **Prompt Editor** — pannello per visualizzare e modificare i system prompt usati dalle feature AI, con supporto per macro espandibili.
2. **Explicit AI History** — il codice generato nei passi precedenti viene mantenuto nel file sorgente come commenti, visibili sia all'utente che all'AI al passo successivo.

Le due feature condividono la stessa motivazione: togliere opacità ai meccanismi AI e dare all'utente il controllo diretto su cosa viene passato ai modelli.

---

## Feature 1: Prompt Editor

### Motivazione

Il system prompt di `(describe)` include attualmente il codice sorgente del modello, il che può causare bias nell'AI: se il codice contiene nomi descrittivi (es. `easter-shade`), il modello può farsi influenzare dal nome invece di descrivere oggettivamente la geometria. Non è possibile prevedere tutti i casi d'uso: meglio dare all'utente la possibilità di sperimentare direttamente.

### Prompt editabili

Ogni prompt ha un identificatore, un nome leggibile, e appartiene a una categoria:

| ID | Nome | Categoria |
|----|------|-----------|
| `describe/system` | Describe — System Prompt | Accessibility |
| `describe/user` | Describe — User Prompt | Accessibility |
| `codegen/tier1` | Code Generation — Tier 1 | AI Assistant |
| `codegen/tier2` | Code Generation — Tier 2 | AI Assistant |
| `codegen/tier3` | Code Generation — Tier 3 | AI Assistant |

I prompt possono avere varianti per provider e per modello specifico. L'ordine di risoluzione, dal più specifico al più generico, è:

1. `<id>:<provider>:<model>` — es. `describe/system:google:gemini-2.5-pro`
2. `<id>:<provider>` — es. `describe/system:google`
3. `<id>` — variante default, usata se non esiste nessuna variante più specifica

Questo permette di differenziare il comportamento tra modelli dello stesso provider (es. Flash vs Pro di Gemini, che reagiscono diversamente a prompt molto dettagliati).

### Macro

Nel testo dei prompt si possono usare macro nella forma `{{nome-macro}}`. Prima di inviare il prompt all'AI, le macro vengono espanse con i dati effettivi. Le macro disponibili dipendono dal contesto:

| Macro | Disponibile in | Espansione |
|-------|----------------|------------|
| `{{source-code}}` | describe, codegen | Codice sorgente attuale dell'editor |
| `{{screenshots}}` | describe | Lista descrittiva delle 7 immagini allegate (front, back, left, right, top, bottom, perspective) |
| `{{metadata}}` | describe | JSON con bounding box, dimensioni, volumi, conteggio vertici/facce |
| `{{slices}}` | describe | Eventuali slice aggiuntive richieste dall'AI o pre-configurate |
| `{{history}}` | codegen | Codice dei passi precedenti (vedi Feature 2) |
| `{{query}}` | codegen | Il testo della query utente corrente |
| `{{object-name}}` | describe | Nome del registered object descritto |

Le macro non riconosciute vengono lasciate invariate e viene mostrato un warning nel REPL.

### UI: pannello Prompt Editor

Il pannello si apre dal menu Settings (o da un'icona dedicata nella toolbar), con la stessa logica del pannello Libraries.

**Layout:**

```
┌─────────────────────────────────────────────────────────┐
│  Prompt Editor                               [×]        │
│─────────────────────────────────────────────────────────│
│  [Accessibility ▼]  describe/system          [↩ Reset]  │
│  ● describe/system                                      │
│  ○ describe/user                                        │
│  ─────────────────                                      │
│  [AI Assistant ▼]                                       │
│  ○ codegen/tier1                                        │
│  ○ codegen/tier2                                        │
│  ○ codegen/tier3                                        │
│─────────────────────────────────────────────────────────│
│  [Provider: default ▼]          [● modificato]          │
│                                                         │
│  ┌───────────────────────────────────────────────────┐  │
│  │ You are a 3D model analyst for blind users.       │  │
│  │ The user cannot see the model.                    │  │
│  │                                                   │  │
│  │ {{screenshots}}                                   │  │
│  │ {{metadata}}                                      │  │
│  │                                                   │  │
│  │ Describe the 3D model in detail...                │  │
│  └───────────────────────────────────────────────────┘  │
│                                                         │
│  Macro disponibili: {{screenshots}} {{metadata}}        │
│  {{object-name}} {{source-code}}                        │
└─────────────────────────────────────────────────────────┘
```

**Comportamento:**

- L'editor è una istanza CodeMirror in modalità plain text (no syntax highlighting, o eventualmente un mode minimale che evidenzia `{{macro}}`).
- Il selettore mostra `default`, poi i provider configurati in Settings, poi — se il provider selezionato ha più modelli configurati — anche le varianti per modello specifico (es. `google / gemini-2.5-flash`, `google / gemini-2.5-pro`).
- Il badge `● modificato` appare quando il prompt corrente diverge dal default di sistema.
- Il pulsante `↩ Reset` ripristina il prompt di sistema originale per quel prompt ID e provider, dopo conferma.
- Le modifiche vengono salvate automaticamente in localStorage alla perdita di focus dell'editor.

### Persistenza

Chiavi localStorage, in ordine dal più specifico al più generico:

```
ridley:prompt:<id>                        → variante default
ridley:prompt:<id>:<provider>             → variante per provider
ridley:prompt:<id>:<provider>:<model>     → variante per modello specifico
```

Esempi:
```
ridley:prompt:describe/system
ridley:prompt:describe/system:google
ridley:prompt:describe/system:google:gemini-2.5-pro
```

Al momento dell'invio all'AI, il sistema cerca le chiavi nell'ordine `provider:model` → `provider` → default, usando la prima trovata in localStorage; se nessuna esiste, usa il valore hard-coded.

### Export / Import

Icona nel pannello per esportare tutti i prompt customizzati come JSON:

```json
{
  "ridley-prompts": "1.0",
  "exported": "2025-03-12T10:00:00Z",
  "prompts": {
    "describe/system": "...",
    "describe/system:google": "...",
    "describe/system:google:gemini-2.5-pro": "..."
  }
}
```

Import accetta lo stesso formato, con dialog di conferma prima di sovrascrivere.

### Modifica al comportamento di `describe`

Il comportamento attuale invia sia le immagini che il source code. Con il Prompt Editor, il source code viene inviato solo se la macro `{{source-code}}` è presente nel prompt. Il prompt di default verrà aggiornato per rimuovere il source code, che può introdurre bias quando il codice contiene nomi semanticamente carichi.

Il nuovo default per `describe/system` sarà:

> *"I am assisting a blind user who cannot see the 3D model. Please describe the model in as much detail as possible: its shape, proportions, features, spatial relationships, symmetry, and likely function. Do not describe anything other than the 3D model itself. Describe only what is visible in the images. {{metadata}}"*

Il source code non è incluso nel default. Chi vuole darlo all'AI può aggiungere `{{source-code}}` manualmente.

---

## Feature 2: Explicit AI History

### Motivazione

Attualmente il sistema `/ai` tiene traccia dei passi precedenti in modo opaco (in un atom interno), passandoli all'AI nel prompt senza che l'utente sappia cosa viene inviato o possa intervenire. Rendere questo processo esplicito nel codice sorgente ha tre vantaggi:

1. L'utente vede esattamente cosa l'AI ha generato in precedenza.
2. L'utente può cancellare step che non gli interessano, tornare a uno step precedente, o modificare un vecchio step prima che influenzi quello nuovo.
3. L'AI riceve un contesto più ricco e coerente su come si è arrivati allo stato attuale.

### Formato nel codice

Ogni volta che l'AI genera del codice con `/ai`, il sistema:

1. Prende il contenuto corrente dell'editor che è stato prodotto dall'AI al passo precedente.
2. Lo commenta e lo inserisce sopra il nuovo codice come blocco history.
3. Inserisce il nuovo codice generato sotto.

Il risultato nel file sorgente sarà:

```clojure
;; ── AI step 2 ──────────────────────────────────────────
;; (register vase
;;   (extrude (circle 20) (f 80)))
;; ────────────────────────────────────────────────────────

;; ── AI step 3 (current) ─────────────────────────────────
(register vase
  (loft-n 32
    (circle 20)
    #(scale-shape %1 (+ 1 (* 0.5 %2)))
    (f 80)))
```

Se ci sono più step, si accumulano verso l'alto:

```clojure
;; ── AI step 1 ──────────────────────────────────────────
;; (register vase (cyl 20 80))
;; ────────────────────────────────────────────────────────

;; ── AI step 2 ──────────────────────────────────────────
;; (register vase
;;   (extrude (circle 20) (f 80)))
;; ────────────────────────────────────────────────────────

;; ── AI step 3 (current) ─────────────────────────────────
(register vase
  (loft-n 32 ...))
```

### Regole di gestione

**L'AI non tocca i commenti di history.** L'output atteso dall'AI è solo il nuovo codice, senza commenti di history. Il sistema (non l'AI) si occupa di costruire il blocco history prima di inserire il nuovo codice nell'editor.

**L'utente può modificare liberamente i commenti.** Può cancellare step che non gli servono, decommmentare uno step precedente, o editare un vecchio step. Il sistema si limita ad aggiungere in cima al successivo invio all'AI.

**Step non-AI non vengono tracciati.** Se l'utente edita manualmente il codice tra un `/ai` e il successivo, il codice manuale diventa parte del "current" che verrà commentato al passo successivo. Non c'è distinzione tra codice AI e codice manuale nella history.

**Il `(current)` marker si sposta.** Al momento della generazione di un nuovo step, il marker `(current)` viene rimosso dal blocco precedente e aggiunto al nuovo.

### Cosa viene passato all'AI

Nella macro `{{history}}` (usata dal prompt `codegen/tier2` e `codegen/tier3`), viene inviato il contenuto dei blocchi history presenti nell'editor al momento dell'invio. I blocchi vengono estratti e de-commentati prima di essere inclusi nel prompt.

Formato inviato all'AI:

```
Previous steps:

[step 1]
(register vase (cyl 20 80))

[step 2]
(register vase
  (extrude (circle 20) (f 80)))

Current code:
(register vase
  (loft-n 32 ...))
```

### Parsing dei blocchi history

Il sistema deve saper identificare i blocchi history nel sorgente per due operazioni:

1. **Estrazione** per la macro `{{history}}`: trova tutti i blocchi `AI step N`, de-commenta il contenuto.
2. **Aggiunta** di un nuovo blocco: trova il blocco `(current)`, lo ri-etichetta con il numero di step, poi inserisce il nuovo blocco `(current)` con il nuovo codice.

Pattern di riconoscimento (regex sul sorgente):

```
;; ── AI step \d+ .*\n(;; .*\n)*;; ─+\n
```

Il parsing è best-effort: se i commenti sono stati modificati dall'utente in modo da non corrispondere al pattern, il sistema ignora quella parte di history senza errori.

### Integrazione con l'atom interno

L'atom `ai-history` attuale (che mantiene i passi per la sessione corrente) viene deprecato. Lo stato della history ora vive nel sorgente. Questo significa che la history è persistente tra sessioni (se l'utente salva il file) e visibile/modificabile direttamente.

Il Tier 1 (modelli piccoli, output solo codice) non usa `{{history}}` nel suo prompt — comportamento invariato.

---

## Implementazione: file coinvolti

### Prompt Editor

| File | Modifica |
|------|----------|
| `ai/prompts.cljs` | I prompt diventano valori di default; aggiunta funzione `resolve-prompt` che legge da localStorage e fa expand delle macro |
| `ai/describe.cljs` | Usa `resolve-prompt` invece di prompt hard-coded; rimuove source code dal default |
| `ai/core.cljs` | Usa `resolve-prompt` per i prompt codegen |
| `editor/storage.cljs` | Aggiunge funzioni CRUD per `ridley:prompt:*` |
| `ui/prompt_panel.cljs` | Nuovo componente: pannello con lista prompt + CodeMirror + reset + export/import |
| Toolbar / Settings | Aggiunge entry point al pannello |

### Explicit AI History

| File | Modifica |
|------|----------|
| `ai/core.cljs` | Dopo aver ricevuto il nuovo codice dall'AI, chiama `insert-history-step!` prima di aggiornare l'editor |
| `ai/history.cljs` | **Nuovo file.** Funzioni: `parse-history-blocks`, `insert-history-step!`, `extract-history-for-prompt` |
| `ai/prompts.cljs` | La macro `{{history}}` usa `extract-history-for-prompt` |

---

## Note aperte

- **Accessibilità del Prompt Editor**: il pannello deve essere navigabile da tastiera per utenti che usano screen reader. CodeMirror è già accessibile; assicurarsi che i controlli (selector provider, pulsante reset, export/import) siano raggiungibili con Tab.
- **Lunghezza della history nel prompt**: se la sessione è molto lunga, `{{history}}` potrebbe diventare molto grande. Per ora non c'è un limite; se si rivela un problema in pratica, si potrà aggiungere un parametro `{{history:3}}` per limitare agli ultimi N step.
- **Tier 1 e history**: i modelli Tier 1 ricevono un contesto minimo per tenere bassi i costi. `{{history}}` non è nel loro prompt default, ma un utente esperto può aggiungerla manualmente tramite il Prompt Editor.
