# Voice Input System — Specifica Completa

## Obiettivo

Implementare un sistema di voice input deterministico che non richiede LLM. Il riconoscimento vocale (speech-to-text del browser) viene processato da un parser a pattern matching che esegue comandi immediati.

L'LLM diventa opt-in esplicito (`modo AI`), non il default.

## Architettura

```
┌─────────────────────────────────────────────────────────────┐
│                      VOICE INPUT                            │
│                          │                                  │
│                    Speech-to-Text                           │
│                          │                                  │
│                          ▼                                  │
│                 ┌────────────────┐                          │
│                 │  MODE ROUTER   │                          │
│                 └───────┬────────┘                          │
│                         │                                   │
│     ┌───────────┬───────┼───────┬───────────┐              │
│     ▼           ▼       ▼       ▼           ▼              │
│ ┌───────┐ ┌─────────┐ ┌─────┐ ┌──────┐ ┌────────┐         │
│ │ TEXT  │ │ STRUCT  │ │TURTLE│ │ HELP │ │   AI   │         │
│ │(chars)│ │(paredit)│ │(DSL) │ │(nav) │ │ (LLM)  │         │
│ └───────┘ └─────────┘ └─────┘ └──────┘ └────────┘         │
│                                                             │
│  + DETTATURA (sotto-modo di TEXT)                          │
│  + SELEZIONE (sotto-modo di TEXT e STRUCT)                 │
└─────────────────────────────────────────────────────────────┘
```

## Principi

1. **Un comando per push-to-talk** — niente parsing di frasi multiple
2. **Modale** — il modo corrente determina come interpretare l'input
3. **Deterministico** — stesso input = stesso output, sempre
4. **i18n** — supporto italiano/inglese, estendibile

## Modi

| Modo | Comando attivazione | Unità base | Uso |
|------|---------------------|------------|-----|
| `struttura` | `modo struttura` | elemento Clojure | editing strutturale paredit |
| `testo` | `modo testo` | carattere | editing testuale vi-like |
| `turtle` | `modo turtle` | comandi DSL | inserimento comandi Ridley |
| `help` | `help [X]` | navigazione lista | ricerca e inserimento template |
| `AI` | `modo AI` | frase libera | passa tutto all'LLM |
| `dettatura` | `scrivi` | testo libero | inserimento testo letterale |
| `selezione` | `seleziona` | estensione range | selezione con movimento |

### Sotto-modi

- **Dettatura**: attivo dentro `testo`, tutto l'input diventa testo letterale. Esce con input vuoto.
- **Selezione**: attivo dentro `testo` o `struttura`, i movimenti estendono la selezione. Esce con azione (cancella/copia/taglia) o `esci`.

---

# MODO STRUTTURA

L'elemento corrente è sempre evidenziato. Può essere: form, simbolo, numero, keyword, stringa, vettore, mappa.

## Navigazione

| IT | EN | Azione |
|----|-----|--------|
| `prossimo [N]` | `next [N]` | N fratelli avanti (default 1) |
| `precedente [N]` | `previous [N]` | N fratelli indietro |
| `dentro` | `into` | primo figlio (noop su atomi) |
| `fuori` | `out` | genitore (noop al top-level) |
| `primo` | `first` | primo fratello |
| `ultimo` | `last` | ultimo fratello |
| `radice` | `top` / `root` | primo elemento top-level |

### Comportamento ai bordi

- `next` su ultimo fratello → noop (o beep)
- `previous` su primo fratello → noop
- `into` su atomo → noop
- `out` al top-level → noop

## Editing

| IT | EN | Azione |
|----|-----|--------|
| `cancella` | `delete` | rimuovi elemento corrente |
| `copia` | `copy` | copia elemento |
| `taglia` | `cut` | taglia elemento |
| `incolla` | `paste` | incolla dopo elemento corrente |
| `incolla prima` | `paste before` | incolla prima |
| `cambia in X` | `change to X` | sostituisci elemento con X |

## Strutturale

| IT | EN | Azione |
|----|-----|--------|
| `slurp` | `slurp` | ingloba fratello successivo nel form |
| `barf` | `barf` | espelli ultimo figlio dal form |
| `avvolgi` | `wrap` | avvolgi elemento in `()` |
| `unwrap` | `unwrap` | rimuovi parentesi del form corrente |
| `estrai` | `raise` | sostituisci genitore con elemento corrente |

### Pronuncia termini inglesi

- `wrap` → "rap" o "vrap"
- `unwrap` → "anvrap" o "anrap"
- `slurp` → "slurp" o "slarp"
- `barf` → "barf"

## Inserimento

| IT | EN | Azione |
|----|-----|--------|
| `inserisci X` | `insert X` | inserisci atomo X dopo corrente |
| `inserisci prima X` | `insert before X` | inserisci atomo X prima |
| `aggiungi X` | `append X` | aggiungi X come ultimo figlio |
| `nuova form [X]` | `new form [X]` | inserisci `(X \|)` o `(\|)`, cursore dentro |
| `nuova form prima [X]` | `new form before [X]` | come sopra, prima del corrente |
| `nuova lista [X ...]` | `new list [X ...]` | inserisci `[X ... \|]` |
| `nuova mappa [X]` | `new map [X]` | inserisci `{X \|}` |

## Meta

| IT | EN | Azione |
|----|-----|--------|
| `annulla [N]` | `undo [N]` | undo N volte |
| `ripeti [N]` | `redo [N]` | redo N volte |
| `esegui` | `run` / `eval` | valuta buffer |

---

# MODO TESTO

Navigazione e editing a livello di carattere/parola/riga.

## Unità

| IT | EN | Default per |
|----|-----|-------------|
| (omesso) | (omitted) | carattere |
| `parola` | `word` | parola |
| `riga` | `line` | riga |

## Navigazione

| IT | EN | Azione |
|----|-----|--------|
| `prossimo [N] [unità]` | `next [N] [unit]` | avanti N caratteri/parole/righe |
| `precedente [N] [unità]` | `previous [N] [unit]` | indietro |
| `primo` | `first` / `home` | inizio riga |
| `ultimo` | `last` / `end` | fine riga |
| `radice` | `top` | inizio documento |
| `fondo` | `bottom` | fine documento |
| `riga N` | `line N` | vai a riga N |
| `cerca X` | `find X` | vai a prossima occorrenza di X |

## Selezione

| IT | EN | Azione |
|----|-----|--------|
| `seleziona` | `select` | entra in modalità selezione |
| `seleziona parola` | `select word` | seleziona parola corrente |
| `seleziona riga` | `select line` | seleziona riga corrente |
| `seleziona tutto` | `select all` | seleziona tutto |
| `esci` | `exit` / `cancel` | annulla selezione |

In modalità selezione, tutti i comandi di navigazione estendono la selezione.

## Editing

| IT | EN | Azione |
|----|-----|--------|
| `cancella [unità]` | `delete [unit]` | cancella selezione o unità |
| `copia [unità]` | `copy [unit]` | copia |
| `taglia [unità]` | `cut [unit]` | taglia |
| `incolla` | `paste` | incolla alla posizione cursore |
| `cambia in X` | `change to X` | sostituisci selezione/carattere con X |

## Dettatura

| IT | EN | Azione |
|----|-----|--------|
| `scrivi` | `type` / `dictate` | entra in modo dettatura |

**In modo dettatura:**
- Tutto l'input vocale viene inserito come testo letterale
- Esce con input vuoto (rilascio push-to-talk senza parlare)

## Meta

| IT | EN | Azione |
|----|-----|--------|
| `annulla [N]` | `undo [N]` | undo |
| `ripeti [N]` | `redo [N]` | redo |

---

# MODO TURTLE

Inserimento diretto di comandi DSL Ridley.

## Movimento

| IT | EN | Genera |
|----|-----|--------|
| `avanti N` | `forward N` | `(f N)` |
| `indietro N` | `back N` | `(f -N)` |
| `destra N` | `right N` | `(th -N)` |
| `sinistra N` | `left N` | `(th N)` |
| `su N` | `up N` | `(tv N)` |
| `giù N` | `down N` | `(tv -N)` |
| `ruota N` | `roll N` | `(tr N)` |

Parole opzionali ignorate: "di", "gradi", "unità"

Esempio: `avanti di 30` → `(f 30)`

## Primitive 3D

| IT | EN | Genera |
|----|-----|--------|
| `cubo N` | `box N` / `cube N` | `(box N)` |
| `cubo N M P` | `box N M P` | `(box N M P)` |
| `sfera N` | `sphere N` | `(sphere N)` |
| `cilindro R H` | `cylinder R H` | `(cyl R H)` |
| `cono R1 R2 H` | `cone R1 R2 H` | `(cone R1 R2 H)` |

Parole opzionali ignorate: "di", "raggio", "altezza", "per"

## Forme 2D

| IT | EN | Genera |
|----|-----|--------|
| `cerchio N` | `circle N` | `(circle N)` |
| `rettangolo N M` | `rectangle N M` / `rect N M` | `(rect N M)` |

## Operazioni

| IT | EN | Genera |
|----|-----|--------|
| `estrudi forma di N` | `extrude shape by N` | `(extrude (forma) (f N))` |
| `registra come X` | `register as X` | wrap con `(register X ...)` |
| `chiamalo X` | `call it X` | wrap precedente con `(register X ...)` |

## Visibilità

| IT | EN | Genera + Esegue |
|----|-----|-----------------|
| `mostra X` | `show X` | `(show! :X)` |
| `nascondi X` | `hide X` | `(hide! :X)` |
| `mostra tutto` | `show all` | `(show-all!)` |
| `nascondi tutto` | `hide all` | `(hide-all!)` |

---

# MODO HELP

Sistema di ricerca e inserimento template.

## Attivazione

| IT | EN | Azione |
|----|-----|--------|
| `help` | `help` | mostra categorie |
| `help X` | `help X` | cerca simboli che iniziano/contengono X |

## Navigazione risultati

| IT | EN | Azione |
|----|-----|--------|
| `uno` / `due` / ... | `one` / `two` / ... | seleziona dalla lista |
| `prossimo` | `next` | pagina successiva |
| `precedente` | `previous` | pagina precedente |
| `esci` | `exit` | chiudi help |

## Selezione

Quando l'utente seleziona un simbolo, viene inserito il template con placeholders:

```clojure
;; help dotimes → seleziona → inserisce:
(dotimes [i n] |)

;; help defn → seleziona → inserisce:
(defn name [params] |)
```

Il cursore si posiziona sul primo placeholder.

## Categorie (help senza argomento)

1. **Clojure core** — defn, let, if, when, cond, for, doseq, dotimes, loop, fn...
2. **Ridley DSL** — f, th, tv, box, sphere, circle, extrude, register, path...
3. **Comandi vocali** — lista dei comandi disponibili nel modo corrente

---

# MODO AI

Fallback esplicito all'LLM.

| IT | EN | Azione |
|----|-----|--------|
| `modo AI` | `AI mode` | attiva modo AI |

In modo AI, tutto l'input viene passato all'LLM (Ollama o altro).

Per uscire: `modo struttura`, `modo testo`, `modo turtle`.

---

# CAMBIO MODO

| IT | EN | Modo attivato |
|----|-----|---------------|
| `modo struttura` | `structure mode` | STRUTTURA |
| `modo testo` | `text mode` | TESTO |
| `modo carattere` | `character mode` | TESTO (alias) |
| `modo turtle` | `turtle mode` | TURTLE |
| `modo tartaruga` | (alias) | TURTLE |
| `modo AI` | `AI mode` | AI |
| `help [X]` | `help [X]` | HELP |
| `scrivi` | `type` / `dictate` | DETTATURA |
| `seleziona` | `select` | SELEZIONE |

---

# i18n — Struttura

```clojure
(def voice-commands
  {:modes
   {:structure {:it ["modo struttura" "struttura"]
                :en ["structure mode" "structure"]}
    :text      {:it ["modo testo" "testo" "modo carattere"]
                :en ["text mode" "text" "character mode"]}
    :turtle    {:it ["modo turtle" "modo tartaruga" "tartaruga"]
                :en ["turtle mode" "turtle"]}
    :ai        {:it ["modo AI" "modo intelligenza artificiale"]
                :en ["AI mode"]}
    :help      {:it ["aiuto" "help"]
                :en ["help"]}}
   
   :navigation
   {:next     {:it ["prossimo" "avanti" "successivo"]
               :en ["next" "forward"]}
    :previous {:it ["precedente" "indietro"]
               :en ["previous" "back"]}
    :into     {:it ["dentro" "entra"]
               :en ["into" "down" "enter"]}
    :out      {:it ["fuori" "esci" "su"]
               :en ["out" "up" "exit"]}
    :first    {:it ["primo" "inizio"]
               :en ["first" "start" "home"]}
    :last     {:it ["ultimo" "fine"]
               :en ["last" "end"]}
    :top      {:it ["radice" "cima" "inizio documento"]
               :en ["top" "root" "beginning"]}}
   
   :editing
   {:delete {:it ["cancella" "elimina"]
             :en ["delete" "remove"]}
    :copy   {:it ["copia"]
             :en ["copy"]}
    :cut    {:it ["taglia"]
             :en ["cut"]}
    :paste  {:it ["incolla"]
             :en ["paste"]}
    :change {:it ["cambia" "sostituisci" "metti"]
             :en ["change" "replace" "set"]}}
   
   :structural
   {:slurp  {:it ["slurp" "slarp" "ingloba"]
             :en ["slurp"]}
    :barf   {:it ["barf" "espelli"]
             :en ["barf"]}
    :wrap   {:it ["wrap" "rap" "vrap" "avvolgi"]
             :en ["wrap"]}
    :unwrap {:it ["unwrap" "anvrap" "anrap"]
             :en ["unwrap"]}
    :raise  {:it ["estrai" "alza"]
             :en ["raise" "extract"]}}
   
   :insertion
   {:insert   {:it ["inserisci"]
               :en ["insert"]}
    :append   {:it ["aggiungi"]
               :en ["append" "add"]}
    :new-form {:it ["nuova form" "nuova funzione"]
               :en ["new form" "new function"]}
    :new-list {:it ["nuova lista" "nuovo vettore"]
               :en ["new list" "new vector"]}
    :new-map  {:it ["nuova mappa"]
               :en ["new map"]}}
   
   :meta
   {:undo {:it ["annulla"]
           :en ["undo"]}
    :redo {:it ["ripeti" "rifai"]
           :en ["redo"]}
    :run  {:it ["esegui" "valuta" "vai"]
           :en ["run" "eval" "execute" "go"]}}
   
   :units
   {:word {:it ["parola" "parole"]
           :en ["word" "words"]}
    :line {:it ["riga" "righe" "linea" "linee"]
           :en ["line" "lines"]}
    :char {:it ["carattere" "caratteri"]
           :en ["character" "characters" "char" "chars"]}}
   
   :numbers
   {:ordinals {:it {"primo" 1 "secondo" 2 "terzo" 3 "quarto" 4
                    "quinto" 5 "sesto" 6 "settimo" 7 "ottavo" 8
                    "nono" 9 "decimo" 10 "ultimo" -1}
               :en {"first" 1 "second" 2 "third" 3 "fourth" 4
                    "fifth" 5 "sixth" 6 "seventh" 7 "eighth" 8
                    "ninth" 9 "tenth" 10 "last" -1}}}
   
   :fillers
   {:it #{"di" "per" "un" "una" "il" "la" "lo" "del" "della" "in" "a"}
    :en #{"of" "by" "the" "a" "an" "to" "in"}}})
```

---

# Templates per Help

```clojure
(def help-templates
  {:clojure
   {:defn     {:template "(defn name [params] |)"
               :doc "Define a function"}
    :defn-doc {:template "(defn name docstring [params] |)"
               :doc "Define a function with docstring"}
    :let      {:template "(let [binding value] |)"
               :doc "Local bindings"}
    :if       {:template "(if condition then else)"
               :doc "Conditional"}
    :when     {:template "(when condition |)"
               :doc "When condition is true"}
    :cond     {:template "(cond condition result |)"
               :doc "Multiple conditions"}
    :case     {:template "(case expr val result |)"
               :doc "Match exact values"}
    :for      {:template "(for [x coll] |)"
               :doc "List comprehension"}
    :doseq    {:template "(doseq [x coll] |)"
               :doc "Side-effect iteration"}
    :dotimes  {:template "(dotimes [i n] |)"
               :doc "Repeat n times"}
    :loop     {:template "(loop [binding init] |)"
               :doc "Loop with recur"}
    :fn       {:template "(fn [params] |)"
               :doc "Anonymous function"}
    :map      {:template "(map f coll)"
               :doc "Transform each element"}
    :filter   {:template "(filter pred coll)"
               :doc "Keep matching elements"}
    :reduce   {:template "(reduce f init coll)"
               :doc "Reduce collection"}}
   
   :ridley
   {:f        {:template "(f |)"
               :doc "Move forward"}
    :th       {:template "(th |)"
               :doc "Turn horizontal (yaw)"}
    :tv       {:template "(tv |)"
               :doc "Turn vertical (pitch)"}
    :tr       {:template "(tr |)"
               :doc "Turn roll"}
    :box      {:template "(box |)"
               :doc "Create box"}
    :sphere   {:template "(sphere |)"
               :doc "Create sphere"}
    :cyl      {:template "(cyl radius height)"
               :doc "Create cylinder"}
    :cone     {:template "(cone r1 r2 height)"
               :doc "Create cone/frustum"}
    :circle   {:template "(circle |)"
               :doc "2D circle shape"}
    :rect     {:template "(rect width height)"
               :doc "2D rectangle shape"}
    :extrude  {:template "(extrude shape |)"
               :doc "Extrude shape along path"}
    :register {:template "(register name |)"
               :doc "Register named mesh"}
    :path     {:template "(path |)"
               :doc "Record path"}
    :mark     {:template "(mark :name)"
               :doc "Mark anchor point"}
    :goto     {:template "(goto :name)"
               :doc "Go to anchor"}}})
```

---

# Implementazione

## File da creare/modificare

```
src/ridley/voice/
├── core.cljs          # Entry point, mode router
├── parser.cljs        # Pattern matching parser
├── modes/
│   ├── structure.cljs # Modo struttura (paredit)
│   ├── text.cljs      # Modo testo (vi-like)
│   ├── turtle.cljs    # Modo turtle (DSL)
│   └── help.cljs      # Modo help (navigazione)
├── i18n.cljs          # Traduzioni e varianti pronuncia
├── templates.cljs     # Template per help
└── state.cljs         # Stato corrente (modo, selezione, etc.)
```

## Stato globale

```clojure
(defonce voice-state
  (atom {:mode :structure           ; :structure :text :turtle :help :ai
         :sub-mode nil              ; :selection :dictation nil
         :language :it              ; :it :en (auto-detect?)
         :selection nil             ; range selezionato
         :help-results nil          ; risultati ricerca help
         :help-page 0}))            ; pagina corrente help
```

## Parser

```clojure
(defn parse-command [text mode language]
  "Parsa il testo e ritorna {:action :keyword :params {...}} o nil"
  (let [tokens (tokenize text language)
        commands (get-commands mode language)]
    (match-command tokens commands)))

(defn execute-command [parsed-command]
  "Esegue il comando parsato"
  (case (:action parsed-command)
    :next (navigate-next (:params parsed-command))
    :delete (delete-current)
    :insert (insert-text (:params parsed-command))
    ...))
```

## Integrazione con editor

Il sistema voice deve integrarsi con CodeMirror:
- Leggere posizione cursore
- Navigare strutturalmente (usa clojure-mode)
- Modificare testo
- Gestire selezione

---

# Test Plan

## Test unitari per parser

```clojure
;; Modo struttura - IT
(parse-command "prossimo" :structure :it)
;; => {:action :next :params {:count 1}}

(parse-command "tre prossimo" :structure :it)
;; => {:action :next :params {:count 3}}

(parse-command "dentro" :structure :it)
;; => {:action :into :params {}}

;; Modo turtle - IT
(parse-command "avanti di 30" :turtle :it)
;; => {:action :insert :params {:text "(f 30)"}}

(parse-command "cubo 20 30 40" :turtle :it)
;; => {:action :insert :params {:text "(box 20 30 40)"}}

;; Modo testo - EN
(parse-command "next 5 words" :text :en)
;; => {:action :next :params {:count 5 :unit :word}}
```

## Test integrazione

1. Aprire buffer con codice Clojure
2. Modo struttura: navigare con next/previous/into/out
3. Verificare highlight elemento corrente
4. Cancellare elemento, verificare undo
5. Modo turtle: inserire comandi, verificare syntax
6. Modo help: cercare, inserire template
7. Modo testo: navigare per carattere/parola
8. Dettatura: inserire testo libero

---

# Priorità implementazione

## Fase 1: Core
1. [ ] Struttura file e stato globale
2. [ ] Parser base con i18n
3. [ ] Modo struttura: navigazione (next/prev/into/out)
4. [ ] Integrazione CodeMirror per highlight elemento

## Fase 2: Editing strutturale
5. [ ] Modo struttura: editing (delete/copy/cut/paste)
6. [ ] Modo struttura: strutturale (slurp/barf/wrap/unwrap/raise)
7. [ ] Modo struttura: inserimento (insert/new form/new list)

## Fase 3: Modo testo
8. [ ] Modo testo: navigazione
9. [ ] Modo testo: selezione
10. [ ] Modo testo: editing
11. [ ] Sotto-modo dettatura

## Fase 4: Modo turtle
12. [ ] Movimento turtle
13. [ ] Primitive e forme
14. [ ] Operazioni (extrude, register)

## Fase 5: Help
15. [ ] Ricerca simboli
16. [ ] Navigazione risultati
17. [ ] Inserimento template

## Fase 6: Polish
18. [ ] Feedback visivo (indicatore modo corrente)
19. [ ] Suoni/beep per errori
20. [ ] Auto-detect lingua
21. [ ] Documentazione utente
