# Brief: Search contestuale in CodeMirror

**Contesto**: Flusso C del piano (§9.3). L'indice `reference-index.cljs` è pronto (259 entry, prodotto dallo script `scripts/build_reference_index.bb`). Questo brief costruisce la superficie utente in CodeMirror.

**Scope ridotto per questa iterazione**: solo completion e tooltip con signature + description. Niente link a schede complete, niente apertura del pannello manuale. Queste funzionalità arriveranno quando il pannello sarà ristrutturato (Flusso B residuo).

---

## 1. Completion source

### Obiettivo

Digitando un simbolo nell'editor CodeMirror, un popup suggerisce i simboli Ridley che matchano il prefisso. Selezionando un suggerimento, il simbolo viene completato.

### Implementazione

Registrare una completion source custom nell'estensione `autocompletion()` già attiva. La source legge da `ridley.manual.reference-index/reference-index`.

Per ogni entry dell'indice, il suggerimento mostra:

- **label**: il nome del simbolo (`loft`, `mesh-union`, `box`...)
- **detail**: la signature (`(loft shape-fn & path-commands)`)
- **info**: la description breve (primo paragrafo, max 200 char)
- **type**: `"function"` per default (CodeMirror usa questo per l'icona nel popup)

### Filtraggio

Il matching è per prefisso sul campo `:name`. CodeMirror gestisce il fuzzy matching internamente se configurato; per ora il prefisso esatto è sufficiente.

### Attivazione

Il popup si attiva dopo aver digitato almeno 2 caratteri (per evitare di scattare su ogni parentesi o spazio). L'utente può anche invocarlo manualmente con la shortcut standard di autocompletion (Ctrl+Space / Cmd+Space).

## 2. Tooltip al hover

### Obiettivo

Hovering con il mouse su un simbolo nell'editor mostra un tooltip con signature e description.

### Implementazione

Usare il pattern AI Focus già esistente nel codebase (StateField + StateEffect + Decoration + UpdateListener) come template. I riferimenti utili sono in `§8.5 Infrastruttura editor` del piano:

- `get-word-at-cursor` e `get-element-at-cursor` per identificare il simbolo sotto il cursore/mouse
- `coordsAtPos` per posizionare il tooltip

Il tooltip mostra:

```
loft
(loft shape-fn & path-commands)
Extrude with shape transformation based on progress.
```

Formato: nome in grassetto (o heading), signature in monospace, description in testo normale. Stile minimale, coerente con il look dell'editor.

### Lifecycle

- Il tooltip appare dopo ~300ms di hover stazionario su un simbolo (debounce per evitare flickering)
- Scompare quando il mouse si sposta via dal simbolo
- Non interferisce con altri tooltip esistenti (errori, AI Focus)

## 3. Clojure core annotati

### Obiettivo

I ~20 simboli Clojure più usati negli esempi Ridley hanno una descrizione breve nel tooltip, come i simboli Ridley. I simboli Clojure non in questa lista mostrano un tooltip con link a ClojureDocs.

### Lista dei simboli annotati

```clojure
["let" "def" "defn" "dotimes" "loop" "for" "map" "reduce"
 "if" "when" "cond" "case" "range" "vec" "first" "rest"
 "last" "concat" "count" "assoc" "get" "get-in"]
```

22 simboli. Ognuno ha bisogno di una signature e una description breve. Queste definizioni vanno in un file separato, non nell'indice generato dallo script (che copre solo i simboli Ridley).

File suggerito: `src/ridley/manual/clojure_core_index.cljs`

```clojure
(ns ridley.manual.clojure-core-index)

(def clojure-core-index
  {"let"   {:signature "(let [bindings] body)"
            :description "Bind values to names within a local scope."}
   "def"   {:signature "(def name value)"
            :description "Define a global variable."}
   "defn"  {:signature "(defn name [params] body)"
            :description "Define a named function."}
   ;; ... etc
   })
```

### Comportamento nel completion e nel tooltip

- I simboli Clojure annotati appaiono nel completion popup con **type** `"keyword"` (icona diversa dai simboli Ridley, per distinguerli visivamente).
- Nel tooltip, mostrano signature + description come i simboli Ridley.
- I simboli Clojure **non** nella lista annotata: nel tooltip, mostrano solo il nome e un link testuale "→ ClojureDocs" (il link è `https://clojuredocs.org/clojure.core/{symbol-name}`). Non appaiono nel completion popup.

### Riconoscimento dei simboli Clojure non annotati

Euristica semplice: se il simbolo sotto il cursore non è nell'indice Ridley e non è nell'indice Clojure core annotato, ma è una parola valida (lettere, cifre, `-`, `?`, `!`, `*`), mostra il tooltip ClojureDocs. Non serve una lista di tutti i simboli Clojure: il link a ClojureDocs funziona anche se il simbolo non esiste (la pagina 404 di ClojureDocs è chiara).

In realtà questa euristica è troppo aggressiva: mostrerebbe un link ClojureDocs anche per variabili utente come `my-box`. Un'alternativa più conservativa: mostrare il link ClojureDocs solo se il simbolo non contiene cifre e non inizia con maiuscola (euristica per escludere nomi utente tipici). Se l'euristica non è soddisfacente, è accettabile partire senza il fallback ClojureDocs e aggiungerlo dopo. La priorità è completion + tooltip per i simboli Ridley e i 22 Clojure annotati.

## 4. Cosa NON fare in questo brief

- Non aprire schede complete nel pannello manuale
- Non ristrutturare il pannello manuale
- Non toccare `content.cljs` o `structure.cljs`
- Non gestire il bilinguismo nei tooltip (oggi tutto EN)
- Non aggiungere tooltip negli editor CodeMirror read-only del manuale (è un obiettivo futuro "gratuito" descritto in §8.3 del piano, ma per ora solo l'editor principale)

## 5. Criteri di accettazione

1. Digitando `lo` nell'editor, il popup mostra `loft`, `loft-n`, `loft-between`, `loop`, `look-at` (e altri match)
2. Hovering su `box` mostra signature e description
3. Hovering su `let` mostra signature e description dal Clojure core index
4. Il completion non interferisce con la funzionalità esistente dell'editor (eval, AI Focus, syntax highlighting)
5. Il tooltip scompare pulitamente e non lascia artefatti visivi

## 6. Dry-run consigliato

Prima di implementare, verificare:

1. Che `ridley.manual.reference-index/reference-index` sia accessibile dal namespace dell'editor
2. Che `autocompletion()` accetti una source custom senza conflitti con la configurazione attuale
3. Che il pattern AI Focus (StateField + StateEffect) sia riusabile per il tooltip hover, o se serve un approccio diverso (ad esempio `hoverTooltip` di CodeMirror, che è l'API nativa per questo caso d'uso)

Il dry-run sul punto 3 è il più importante: se `hoverTooltip` funziona, è la via diretta. Se ci sono conflitti con tooltip esistenti, serve un adattamento.
