# Bozza P4a — `acquire`: emissione + round-trip

Bozza di lavoro (Claude, 2026-07-24) **dentro il perimetro chiuso** in
`brief-param-acq-v1.md` → P4 → "Design del palcoscenico". Serve a fissare le
forme concrete e l'ordine delle fette prima di scrivere codice. Le decisioni di
design NON si riaprono qui; questa bozza le traduce in implementazione e segnala
solo i dettagli-sintassi ancora da chiudere.

## Il perimetro (dal brief, per riferimento)

- **Forma minimale**: `(acquire "scans/lettore-sd/")` (+ `{:label …}` se più d'una).
- **Demarcazione**: nel *sorgente* ciò che descrive l'**oggetto** (proxy con posa,
  ricalchi emessi); nel *file di sessione* ciò che descrive la **fotografia**
  (pose camere, intrinseche, picks, residui, piani dichiarati).
- **"L'impalcatura deve poter cadere"**: la geometria emessa (poly/shape dai
  ricalchi) è Ridley autonomo; cancellata la riga `(acquire …)` il programma resta
  valido; nessuna forma emessa referenzia `acquire`.
- **P4a** = round-trip (emissione `acquire` + write-back proxy + re-entry; gate:
  commit → riapri → stessa sessione con pose, marcature e ricalchi). **P4b** =
  palcoscenico non-modale (frustum/in-posa/pellicola; frustum "da collaudare").

## Come funziona oggi la famiglia edit-X → X (il modello)

1. `X` (es. `image-board`) è una direttiva/shape SCI: valutata, produce la forma
   (cittadinanza da riferimento; mai export/CSG).
2. `edit-X` (es. `edit-image-board`) è un **marcatore** nel buffer. Si apre dal
   pannello definizioni (Cmd+Enter), non dal REPL; il modale trova il proprio
   `(edit-X …)` via `find-form-bounds`/`find-marker`.
3. **OK** → `modal/replace-source! from to code`: sostituisce `(edit-X …)` con la
   forma emessa (di norma `(X …)`).
4. **Annulla** → `modal/strip-head`: `(edit-X …)` → `(X …)`, lasciando il corpo.

**Scarto rispetto a oggi**: `edit-acquire` è legata a `edit-acquire/enter!` come
**funzione** `(edit-acquire proxy-mesh dir)` invocata dal REPL. P4a la porta nel
pattern marcatore, e crea la `acquire` che oggi non esiste.

## Forma-sorgente bersaglio (rivisto 2026-07-27)

Dopo una sessione confermata, il buffer contiene **una sola form autocontenuta**
— niente `(poly …)` sciolti (accoppiamento invisibile, vincoli non scritti) e
niente ricalchi opachi nel solo file di sessione (violerebbe "lo stato è il
sorgente"). Tutto dentro la form, **nominato**:

```clojure
(def A
  (acquire "scans/lettore-sd/"
    {:proxy (box 60.2 20.2 40.1)
     :pose  {:position [...] :heading [...] :up [...]}
     :shapes {:bezel (poly …)}          ; ricalchi nominati (geometria)
     :marks  {:vite-1 {:position [...] :direction [...]}}}))  ; punti nominati
```

- **`acquire` ritorna il valore**: accesso per nome via destrutturazione —
  `(:bezel (:shapes A))`, esattamente il pattern di `split-tree` e di `(:mark-name
  path)`. Nessuna API nuova a valle. Round-trip su una sola form.
- **Demarcazione**: dentro la form (oggetto) stanno proxy+posa, `:shapes`
  (ricalchi), `:marks` (punti); nel file di sessione (fotografia) stanno pose
  camere, intrinseche, osservazioni-di-mark (i raggi per foto), residui, piani
  dichiarati.
- **Proxy con posa → mappa esplicita** `{:proxy (box …) :pose {…}}` (confermato):
  leggibile, e `(acquire "dir")` minimale diventa `(acquire "dir" {…})`.

### Principio dell'impalcatura, raffinato: "caduta per inline deliberato"
La geometria acquisita non "cade" cancellando una riga; **cade sostituendo di
proposito** l'accesso nominato col letterale — `(:bezel (:shapes A))` →
`(poly …)` letterale (eventualmente con un aiuto editor "estrai ricalco"),
coerente con la sostituzione progressiva della guida 18. Finché non lo fai,
la geometria resta figlia della `acquire` (che però NON entra mai in CSG/export:
è cittadinanza da riferimento). Non c'è più un `(poly …)` sciolto che "regge per
costruzione".

## I mark: primitivo unificante dei punti acquisiti (2026-07-27)

"Punto nominato dell'oggetto osservato in più foto" = il `mark` di Ridley
(**posizione + direzione + id**; già esiste come àncora nominata nel sistema
path/shape, `(:mark-name path)`). Questo **unifica retroattivamente**:
- le **corrispondenze PnP** (erano mark su spigoli),
- i **punti di consistenza**,
- i **punti d'interesse / marcature** (incluso il click-freccia del blindato).

Meccanica del mark acquisito: creato su UNA foto è un **raggio** (profondità
ignota, mostrato come tale); la seconda foto **triangola**; le successive
rifiniscono; la direzione viene dal piano dichiarato o da una coppia di punti.
Aprendo ai mark la macchina esistente (attach, path-per-mark, misure) **senza
codice nuovo a valle**.

Conseguenza per il file di sessione: i **mark risolti** (posizione+direzione+id)
sono oggetto → dentro `:marks` nella form; le **osservazioni-di-mark** (i raggi
per-foto che triangolano) sono fotografia → file di sessione. **P4a-2 salva le
osservazioni-di-mark.**

## I tre pezzi da costruire

### 1. La direttiva `acquire` (la `X`)
- Nuovo binding SCI `acquire` + shape reference-citizen (famiglia
  image-board/mesh-board: mai export, mai pick, mai CSG).
- **Ritorna il valore**: `(acquire "dir" {…})` valuta a una struttura nominata
  `{:proxy … :pose … :shapes {…} :marks {…}}`, destrutturabile per nome
  (`(:bezel (:shapes A))`) — pattern `split-tree`/`(:mark-name path)`, nessuna
  API nuova.
- **In P4a, minimale**: valutata, monta il proxy alla sua posa come geometria di
  riferimento (così l'oggetto si vede in scena) e ritorna la struttura (con
  `:shapes`/`:marks` eventualmente vuoti finché non ci sono). Il montaggio completo
  del palcoscenico (pellicola + camere/frustum) è **P4b**. La stringa-cartella
  viene conservata e passata (P4b la userà per la pellicola).
- Riuso: la citizenship "da riferimento" e il non-entrare-in-CSG seguono
  esattamente `image-board`/`mesh-board`; il ritorno-valore-destrutturabile segue
  `split-tree`.

### 2. `edit-acquire` come marcatore (la `edit-X`)
- `(edit-acquire "dir")` diventa un marcatore aperto dal pannello definizioni
  (find-marker), non più una funzione REPL con la mesh come argomento.
- **OK** → `replace-source! from to (emit-acquire)` — vedi §Emissione.
- **Annulla** → `strip-head`: `(edit-acquire "dir")` → `(acquire "dir")`.
- Il proxy alla prima apertura: se il sorgente è `(edit-acquire "dir")` nudo (nessun
  proxy), si parte da un box di default (o dal proxy salvato nel file di sessione,
  in transizione); l'utente lo allinea sulla foto 0 come già fa.
- **Transizione dev**: valutare se tenere l'ingresso REPL `(edit-acquire (box …) "dir")`
  in vita durante lo sviluppo (comodo per iterare in Chrome) accanto al marcatore,
  o migrare subito. Proposta: tenerlo finché il marcatore non è collaudato, poi
  toglierlo.

### 3. Contratto del file di sessione (`acquire-state.json`)
Già esiste e round-trippa quasi tutto. P4a lo **formalizza sulla demarcazione**:
- **Resta nel file di sessione (fotografia)**: pose camere (`camera-poses`,
  `camera-pose-0`), residui/matched, `picks` (oggi NON salvati — vedi nota),
  **osservazioni-di-mark** (i raggi per-foto: PnP, consistenza, marcature),
  piani dichiarati (`:retrace :plane`), intrinseche/focale.
- **Esce dal file di sessione → va nel sorgente (oggetto), dentro la form**:
  `proxy-pose` (→ `:pose`); i ricalchi risolti (→ `:shapes {:nome (poly …)}`); i
  mark risolti posizione+direzione+id (→ `:marks {:nome {…}}`).
- **Nota picks/osservazioni**: oggi `acquire-state.json` salva pose+rms ma **non
  i pick di snap** (gate del blindato: la sessione va ri-agganciata dal vivo). Per
  un vero round-trip "stessa sessione", P4a deve **persistere le osservazioni**
  (pick snap/PnP = raggi-di-mark), così riaprendo non serve ri-cliccare.

## L'emissione (write-back) su OK

`emit-acquire` sostituisce il marcatore `(edit-acquire "dir")` con **una sola
form**:
```clojure
(acquire "dir" {:proxy (box w h d) :pose {…} :shapes {…} :marks {…}})
```
- `:proxy`+`:pose` = write-back del proxy allineato.
- `:shapes` = i ricalchi, nominati (oggi `emit-retrace!` stampa un `(poly …)` in
  frame-oggetto dentro un commento; P4a lo porta a un `(poly …)` world-space
  dentro `:shapes {:nome …}`). Un ricalco → una entry nominata.
- `:marks` = i mark risolti, nominati.
- il file di sessione (fotografia) è scritto a parte (`save-acquire-state!`),
  ripulito dei campi migrati nella form.

Nessun `(poly …)` sciolto: la geometria "cade" solo per **inline deliberato**
(§Principio dell'impalcatura).

## Re-entry / round-trip

- Riaprire `(edit-acquire "dir")` (o ri-editare `(acquire "dir")` → il marcatore):
  legge la form (proxy/pose/shapes/marks) dal sorgente + il file di sessione da
  `dir` (osservazioni, pose camere, piani) → ripristina la sessione completa.
- **Gate P4a**: `(edit-acquire "dir")` → allinea/aggancia/marca/ricalca → OK
  (emette la form) → riapri → **stessa sessione** (posa proxy, marcature, ricalchi).

## Ordine delle fette dentro P4a (con gate ciascuna)

- **P4a-1 — round-trip del proxy.** Crea `acquire` (minimale: ritorna
  `{:proxy :pose :shapes {} :marks {}}`, monta il proxy come riferimento) +
  `edit-acquire` marcatore (open dal pannello, OK/strip-head). Write-back del solo
  proxy+posa. Niente shapes/marks ancora.
  *Gate*: apri `(edit-acquire "dir")`, allinea il proxy sulla foto 0, OK →
  `(acquire "dir" {:proxy … :pose … :shapes {} :marks {}})`; riapri → stessa posa.
- **P4a-2 — osservazioni + restore.** Salva le **osservazioni-di-mark** (pick
  snap/PnP + marcature blindato) nel file di sessione; ripristina pose camere +
  osservazioni + piani alla riapertura.
  *Gate*: registra 2-3 foto (s/m/p), OK, riapri → badge e pose tornano senza
  ri-cliccare.
- **P4a-3 — shapes & marks nella form.** I ricalchi → `:shapes {:nome (poly …)}`
  world-space; i mark risolti → `:marks {:nome {:position :direction}}`;
  destrutturazione per nome funzionante; restore.
  *Gate*: ricalca su una faccia + nomina un mark, OK → form con `:shapes`/`:marks`;
  `(:nome (:shapes A))` dà la geometria; riapri → tutto si rivede.

## Dettagli ancora da chiudere (non design, implementazione)

1. ~~Sintassi proxy-con-posa~~ → **CHIUSO: mappa `{:proxy :pose}`.**
2. Cosa valuta `acquire` in P4a: struttura `{:proxy :pose :shapes :marks}` +
   proxy montato come reference-citizen. (P4b aggiunge pellicola+camere.)
3. ~~Ingresso REPL in transizione~~ → **CHIUSO: marcatore + REPL in parallelo,
   poi si toglie il REPL.**
4. Serializzazione delle osservazioni-di-mark (snap = segmenti per gruppo; PnP/
   blindato = pixel + foto + id-mark) e re-idratazione.
5. Naming dei mark e degli shape (auto `:vite-1`/`:bezel-1` vs input utente) —
   serve un nome per la destrutturazione; probabile auto + rinominabile.

## Fuori perimetro P4a (→ P4b)

Frustum nel mondo / foto in-posa / pellicola-pannello e la loro ergonomia
(frustum "da collaudare, non decisi"); gestione memoria miniature vs full-res;
la fase 2 che esce dal modale e diventa editor normale (edit-path-2d ricco sopra
le viste in posa).
