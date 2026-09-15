# Risposte al brief cap. 19 "Estendere Ridley" — accertamenti, bug, buchi, seguiti

Da: Code · A: Vincenzo + Claude (manuale) · Data: 2026-09-10
Riferimento: `dev-docs/brief-ch19-accertamenti.md`.

Tutto quello che segue è **misurato allo schermo** (SCI vero via
`ridley.editor.repl/evaluate` sul browser di sviluppo, `npm run dev`), non dedotto
dal codice; dove ho cambiato codice, il test è stato ripetuto DOPO la modifica e il
node test suite passa (1014 test, 4844 asserzioni, 0 errori). Le modifiche sono nel
working tree, **non committate** (la bozza del cap. 19 e il cap. 20 sono lì accanto,
non volevo mescolare i due lavori in un commit).

Legenda: **SÌ/NO** = risposta alla domanda del brief; **CAMBIATO** = codice/Spec/
reference toccati da me; **PER CLAUDE** = correzione che spetta alla guida.

---

## Parte A — Accertamenti

### A1. Cosa può fare il codice utente nel context SCI

- **`defmacro` nel buffer: SÌ.** `(defmacro twice [x] \`(do ~x ~x))` funziona nel
  buffer e anche in un comando REPL.
- **`defmacro` in una libreria: funziona DENTRO, NON diventa pubblico.** La regex
  di `library/core.cljs` estrae `def`/`defn` (e da oggi `defonce`); ma anche se lo
  estraesse, il loader consegna *valori* al namespace SCI e una macro non lo è.
  Testato: libreria con `defmacro twice` + `(defn pub [] (twice 1))` → `pub`
  esportata e funzionante, `twice` no.
- **`atom`/`swap!`/`reset!`: SÌ.** Vivono fra un comando REPL e l'altro (1 → 2 →
  5 in tre comandi successivi); a ogni Run il context è ricostruito e l'atom
  riparte dal valore iniziale (dopo un Run che non lo definisce: "Could not
  resolve symbol").
- **`defmulti`/`defmethod`: SÌ**, con un inciampo da scrivere: `defmulti` ha
  semantica `defonce`, quindi `(defmulti area :kind)` su un nome GIÀ bound (`area`
  è una built-in di Ridley) non fa nulla e il `defmethod` successivo esplode con
  «No protocol method IMultiFn.-add-method defined for type function». Con un
  nome nuovo funziona. **`defprotocol`/`defrecord`/`deftype`: SÌ.** Anche `letfn`,
  `try/catch :default`, `ex-info`, `clojure.string/…` e `clojure.set/…` col nome
  intero (nessun alias `str/` predefinito).
- **`js/…`: CHIUSO**, confermato anche per `js/Math`: `js/Math.sqrt`, `Math/sqrt`,
  `js/Date`, `js/parseInt`, `js/JSON`, `js/Object`, `js/Array`, `js/console`
  → "Could not resolve symbol". L'unica eccezione osservata è `js/Error.`
  (default di SCI). `js-obj`, `array`, `aget` ci sono. Il capitolo può dire: «la
  matematica è wrappata (`sqrt`, `atan2`, `PI`…), l'host no».
- **CAMBIATO**: Spec §17, nuovo paragrafo «What the evaluator gives you beyond the
  bindings» con questi tre punti (durata dello stato, `defmulti` su nome esistente,
  niente interop).

### A2. Il valore di un path

- **`path->data` NON restituiva la forma di Architecture.md:504-510: era rotta.**
  Era bindata a `path/path-from-state`, che vuole uno *stato turtle* (era pre-
  recording); dato un path restituiva un guscio:
  `{:type :path, :segments nil, :closed? false, :start [0 0 0], :end nil}`.
- **Il path È già il dato.** `(path (f 30) (th 90) (arc-h 10 90))` stampa
  `{:type :path, :commands [{:cmd :f, :args [30]} {:cmd :th, :args [90]}
  {:cmd :arc-h, :args [10 90], :steps 64}], :micro-commands #object[Delay …]}`.
  Due cose in più rispetto ad Architecture: (1) le curve portano `:steps` (passi di
  tessellazione congelati a record time, come dice Architecture 6.3); (2) ogni
  `(mark …)` compare anche come **chiave top-level** con la posa nel frame del path:
  `(path (f 30) (mark :here) …)` → `{:here {:position [30 0 0], :heading [1 0 0],
  :up [0 0 1]}, :type :path, :commands […]}`. `:micro-commands` è un delay memo
  che i consumer ricalcolano da soli se manca.
- **CAMBIATO**: `path->data` ora è `path/path-data`: su un path restituisce la
  mappa senza `:micro-commands` (stampabile, e ancora usabile: `(path-length
  (path->data p))` = 45.71 come sull'originale); su uno stato turtle fa come prima.
  Forma da mettere nel capitolo così com'è:
  ```clojure
  (path->data (path (f 30) (th 90) (arc-h 10 90)))
  ;; => {:type :path,
  ;;     :commands [{:cmd :f, :args [30]} {:cmd :th, :args [90]} {:cmd :arc-h, :args [10 90], :steps 64}]}
  ```
- **`path?`: SÌ a entrambi** (testa `:type :path`); `false` su una shape.
- **Bug collaterale trovato e corretto**: `(register skel (path (f 30) (mark
  :shoulder)))` — documentato in Spec §13 — falliva con «Invalid path … (contains?
  % :segments)»: lo spec `:ridley/path` in `schema.cljs` conosceva solo la forma a
  segmenti, e `register-path!` lo asserisce. Solo in DEV (`goog.DEBUG`): in
  produzione passava muto. Ora lo schema ammette entrambe le forme; testato.
- **Spec**: §3 «Path utilities» ha `path->data` e un paragrafo «A path is data»
  con la forma stampata e le chiavi dei mark.

### A3. `revolve` e le shape-fn

- **`t` in una rivoluzione = frazione dell'angolo, `t = i/steps`** (steps = segmenti
  della risoluzione per quell'angolo; 64 per un giro intero al default). Sonda:
  giro intero → 64 valori distinti da 0 a **0.984375** (= 63/64): l'ultimo anello
  coinciderebbe col primo, quindi il profilo a `t = 1` non viene MAI costruito;
  180° → 65 valori, da 0 a 1 compreso (l'ultimo anello è un cap). Il profilo a
  `t = 0` viene valutato anche come sonda, prima degli anelli.
- **`*path-length*` in revolve era `nil`**, quindi `capped` scendeva alla frazione
  fissa 0.08 e `heightmap :fit :physical` a `H = 1.0`, entrambi in silenzio.
- **CAMBIATO**: `pure-revolve-shape-fn` (`editor/operations.cljs`) ora binda
  `*path-length*` = **|angolo| · r**, con r la distanza del centroide del profilo
  a `t = 0` dall'asse (la x nel piano del profilo). Sonda: `(circle 5)` traslata a
  x = 20, giro intero → 125.66 = 2π·20; 180° → 62.83. Vale anche per `revolve+`
  (stessa via). Spec §6 «Revolve» aggiornata con `t` e la lunghezza.

### A4. Header delle dipendenze di una libreria

- **Quella vera è `;; Requires: a, b`** (`storage.cljs`, `parse-lib-header`, nomi
  separati da virgola), insieme a `;; Ridley Library: nome`. **`;; :requires
  [my-shapes utils]` (cap. 9 riga 135) non è mai stata letta da nessuno.** Sul
  desktop il file `.clj` si scrive con quell'header; sul web la lista `:requires`
  sta nel JSON e l'header compare solo all'export; il pannello (bottoni Requires)
  scrive lo stesso campo.
- **PER CLAUDE**: correggere il cap. 9 (IT+EN) a `;; Requires: a, b`.
- **CAMBIATO**: Architecture 11.3.1 dice la sintassi esatta e cosa viene
  esportato; Spec nuovo §18.10 «User libraries: what crosses the boundary».

---

## Parte B — Sospetti di bug nelle shape-fn

### B1. `:base` letto a un livello solo in `heightmap` — **CONFERMATO, CORRETTO**

Test: `hm = (text-heightmap "AB" :size 5)`, profilo radiale a `t = 0.5` di
`(-> (circle 20 64) (fluted …) (heightmap hm :fit :physical))` contro la stessa
catena con un `(tapered :to 1)` in mezzo (geometricamente identico). **Diversi.**
Uguali solo forzando `:surface-width (shape-perimeter (circle 20 64))` sulla
catena lunga: la perimetro di una funzione è `nil` → cadeva nel default `1.0` →
testo piazzato a una frazione della sua taglia, senza NaN e senza errore. Ora
`heightmap` scende la catena fino al profilo radice; il test dà «uguali».

### B2. `:point-count` stantio — **CONFERMATO, TOLTO**

Nessuno lo legge (grep su `src/`, `test/`, esempi, librerie, reference: zero
lettori; l'unica riga è un commento in `embroid`). Tolto dai metadata: ora
`(meta sf)` = `{:type :shape-fn :base …}`. Da NON documentare.

### B3. `shape-fn` con base parziale — **CONFERMATO, ORA ERRORE PARLANTE**

`(loft-n 16 (shape-fn (tapered :to 0.5) (fn [s t] s)) (f 30))` restituiva
**`nil`, nessun errore** (la base è `fn?` ma non `shape-fn?` → trattata come shape
statica → il transform riceveva una funzione). Ora il costruttore rifiuta tutto
ciò che non è shape o shape-fn:
`shape-fn: the base must be a 2D shape or a shape-fn, got a bare transform — a
partial form such as (tapered :to 0.5) has no profile; give it one: (tapered
(circle 20) :to 0.5)`. Anche `(shape-fn nil …)` parla. Supportarla non ha senso:
un partial non ha profilo da cui partire.

### B4. Dominio dell'angolo nelle thickness-fn — **CONFERMATO: `(-π, π]`**

Sonda con un `:fn` che accumula `a` in un atom: min −3.043, max 3.1416 su
`(circle 20 64)` (atan2 sul centroide, `shape-centroid`). Il cap. 6 riga 632
sbaglia. **Con `:style :pattern :fn f`, `f` riceve `u ∈ [0, 0.984]`** (frazione di
arco-lunghezza), NON l'angolo: il ramo `pattern?` sceglie la parametrizzazione a
prescindere da `:fn`. Con `:fn` da solo (o con qualunque altro stile) arriva
l'angolo. È utile — è il modo per avere l'arco-lunghezza in una `:fn` custom — e
l'ho documentato così (Spec §4, docstring di `shell`, scheda `shell`).

- **PER CLAUDE**: cap. 6 (IT+EN) riga 632: «in radianti, da −π a π (atan2
  attorno al centroide)»; 19.2 col dominio giusto e la nota su `:pattern`.

### B5. `tapered` dopo `shell` — **la regola del cap. 6 è troppo stretta**

Misurato con `loft-n 32` sulla stessa shell voronoi: shell sola 5528 vertici /
11104 facce; **identici** con `tapered`, `twisted`, `fluted`, `capped` DOPO shell.
Motivo: tutti i combinatori usano `assoc`/`update :points` e conservano le chiavi
extra (`:shell-values` è per-punto e resta allineato ai punti). Le chiavi dopo
`twisted`: `(:centered? :points :shell-level :shell-mode :shell-smooth
:shell-thickness :shell-values :type)`. Quello che NON può seguire una shape-fn
è **`morphed`**, ma per un altro motivo: prende due shape STATICHE e con una
shape-fn come primo argomento esplode («resample expects a 2D shape, got
function») — non è un combinatore componibile affatto. `shell` resta bene in
fondo per un motivo diverso: pattina il profilo che riceve, un `fluted` dopo lo
deforma senza ripatinarlo.

- **PER CLAUDE**: cap. 6 riga 596: non «solo tapered», ma «tutto ciò che
  preserva i punti; `morphed` mai dopo una shape-fn».
- **CAMBIATO**: Spec §4, paragrafo «Order in a chain».

---

## Parte C — Buchi

### C1. Helper privati — **ESPORTATI `smoothstep` e `shape-centroid`**, più uno

Nuovi binding SCI (schede reference scritte, indice ricostruito, harness dei
test allineato):
- `(smoothstep e0 e1 x)` — la rampa Hermite di ogni `:softness`
  (`[0 0.5 1]` su `(smoothstep 0 1 …)` per −1, 0.5, 2; step duro se `e1 ≤ e0`).
- `(shape-centroid shape)` — media dei punti del contorno esterno, quella da cui
  `shell` misura gli angoli (`[15 0]` su un `rect` traslato di 15).
- `(current-path-length)` — vedi E1.
Restano privati `signed-dist-poly`, `perimeter-fractions`, i `v2-*`: il capitolo
li mostra riscritti, se servono.

### C2. `embroid` senza `:fn` — **AGGIUNTO `:fn`**

`(embroid p 3 :fn (fn [u t] …))`, top-level o dentro `:wall`: `u` = frazione di
arco-lunghezza lungo la parete, `t` = sweep; 1 = montante, 0 = apertura, taglio
sull'iso-linea 0.5. `:margin`/`:border`/`:softness` NON si applicano a una `:fn`
(sono affari degli stili): chi vuole il pannello attaccato ai vicini restituisce
1 vicino ai bordi. Le colonne dei cap restano piene. Testato in entrambe le forme
(1356 vertici), lo stile `:honeycomb` invariato (2056).

### C3. `register` a top level in `weave.clj` — **NON voluto, BONIFICATO**

Il loader valuta la libreria in un context SCI proprio ma `register` scrive nel
registry GLOBALE: attivare `weave` metteva `AA` nella scena a ogni Run. Le altre
demo nello stesso file erano già commentate; ora lo è anche quella, con la nota.
Documentato come trappola in Spec §18.10 e Architecture 11.3.1: il 19.6 può
rimandare lì.

### C4. `dev-docs/ShapeFn.md` — **marcato storico e corretto nei tre punti**

Nota in testa (documento di progetto 2025, vincono Spec e manuale), più le
correzioni in loco: `rugged` fBm multi-ottava; costruttore con `shape-fn?` (non
`fn?`) e rifiuto del partial, `:point-count` rimosso; `angle` dall'ORIGINE.

### C5. Reference Internals — nessuna azione

`structure.cljs` resta `:visible? false`; il 19.3 è la casa provvisoria, come
dice il brief.

---

## Parte D — Seguiti del cap. 20

### D1. «Rifinisci insieme (R)» sulla gabbia — **falsa pista, solo il commento**

`cage-proxy?` È un sottoinsieme di `plate-proxy?` (una gabbia ha `:anchors`
nominati: `(seq (:anchors proxy))` è vero), e la `'a'` della gabbia archivia i
dischetti abbinati in `:pnp-picks` (`{:px … :proposed? true}`, righe 5513-5514).
Quindi la condizione «≥2 foto con ≥4 click» era GIÀ vera sulla gabbia e il
bottone GIÀ offerto: il commento «Plate-only» mentiva. Corretto il commento;
nessun cambio di comportamento. Se in una sessione reale il bottone non compare
con due foto registrate, è un'altra cosa e voglio vederla.

### D2. Riga-suggerimento su gabbia — **CAMBIATO**

Nuovo ramo `cage-proxy?` PRIMA di `plate-proxy?` in `session-hint`: posa a
occhio (cursori e cerchi), `'a'`, ripiego `'p'` su 4 dischetti di UN anello e
di nuovo `'a'`, `'R'`, Grab, `'v'`. Niente `'C'`.

### D3. Grab su gabbia — **CAMBIATO**

- Riga info camera: su gabbia «Grab keeps the frame, unregistered: pose the cage
  by eye, then 'a'.» e, senza focale, «The lens is measured by 'R' once the grabs
  are registered.» (il piatto tiene il suo testo).
- Messaggio di stato/log dopo il grab: «tenuta, da registrare — posa a occhio,
  poi 'a' (o 4 dischetti con 'p', poi 'a')».
- Hint di sessione vuota: ramo gabbia («inquadra la gabbia con l'oggetto dentro…
  ogni scatto entra da registrare: posa a occhio, poi 'a'»).

### D4. `reference/en/registration-cage.md` e `print-cage.clj` — **CAMBIATO**

Ordine di incollaggio gen 2 (i due grandi fra loro, il pezzo, il piccolo per
ultimo; nota sul perché il gen 1 era l'inverso); `(measured cage 175.4)` che vive
in `examples/print-cage.clj`; cianoacrilato al posto dell'epossidica in tutte e
tre le occorrenze; docstring di `save-3mf` allineata.

### D5. Etichette UI miste IT/EN — **decisione di Vincenzo, non toccate**

Elenco esatto: inglesi «Add anchor (d)», «Hide cage (v)», «New anchor (n)»,
«Exit (d)», toolbar «Photo / Marks / Plane», riga info camera; italiane
«Registra per punti (p)», «Auto — leggi la gabbia (a)», «Rifinisci insieme (R)»,
«Conferma (OK)», «Chiudi», gli hint e i messaggi di stato. Il resto della UI di
Ridley è in inglese. Se si uniforma, propongo l'inglese per i bottoni e
l'italiano solo nei messaggi lunghi (hint, log), che sono già tutti italiani;
ma il cap. 20 le riporta esatte in due lingue, quindi è un cambio da fare in un
colpo solo e con Claude avvisato.

---

## Parte E — Emersi dalla bozza

### E1. `*path-length*` non leggibile da SCI — **ESPOSTA come `(current-path-length)`**

Una funzione, non una var: `(fn [] sfn/*path-length*)` valutata dentro il
transform vede il binding del loft/revolve (30 su `(f 30)`; 125.66 su un
revolve; `nil` fuori). Una var SCI avrebbe richiesto `sci/binding` in tre punti
del layer editor; così è una riga e nessun accoppiamento. Nota: su `revolve` la
PRIMA chiamata (la sonda a `t = 0`, prima del binding) vede `nil` — innocuo,
serve solo a contare i punti. La bozza può sostituire il parametro
`(path-length spine)` con `(or (current-path-length) 1)`.

### E2. `examples/recursive-tree.clj` — **stantio, RISCRITTO**

`(push-state)` → «Could not resolve symbol» (nessun binding; `ai/prompts.cljs`
lo dice pure: «There is NO push-state»); `(cyl 3 2 20)` costruisce un cilindro
r 3, h 2, 20 segmenti — non il tronco che l'esempio intende. Riscritto con
`cone` (frusto lungo l'heading, raggio → raggio figlio) e scope `turtle` (il
figlio riparte dalla posa del padre e il padre non si muove), `branch` restituisce
il vettore delle sue mesh e alla fine `(register tree (concat-meshes …))`.
Valutato dal vivo: `:tree` registrato, 5200 vertici, nessun errore. La bozza del
19.5 può usarlo così com'è.

### E3. `defonce` in una libreria — **ora esportato**

Regex aggiornata (`def`, `defn`, `defonce`). Test: `(defonce once-v 7)` →
`once-v 7` fra i binding. Il cap. 9 riga 105 diventa vero.

### E4. `^:private` esportato — **ora escluso**

`(def ^:private x)` e `(def ^{:private true} y)` non vengono più esportati; il
secondo prima esportava addirittura il nome «true» (la regex prendeva `true}`
per il nome). `(def ^:export z)` resta esportato. `gears.clj` non cambia:
`standard-modules` diventa privato come chiede il suo tag.

### E5. `:softness` con `:fn` — **`:fn` ha la SUA `:softness`**

Prima: `:fn` + `:softness 0.6` → nessun taglio liscio; `:fn` + `:style :voronoi`
→ liscio (lo stile serviva solo ad accendere il flag). Ora: `:fn` accende
l'isocontorno se e solo se passi `:softness > 0` (il valore in sé conta solo per
le rampe degli stili built-in); `:style` accanto a `:fn` non accende più nulla
(`:style :pattern` continua a cambiare la parametrizzazione, vedi B4). Testato:
`{:shell-smooth true :shell-level 0.5}` con `:softness 0.6`, `{}` senza, loft
con `smoothstep` in una `:fn` liscia → 1676 vertici.

---

## Sonde riusabili

```clojure
;; dominio del primo argomento di una thickness-fn
(def as (atom []))
(loft-n 4 (shell (circle 20 64) :thickness 2 :fn (fn [a t] (swap! as conj a) 1)) (f 30))
[(apply min @as) (apply max @as)]                       ; => [-3.04 3.14]

;; i valori di t che un revolve costruisce davvero
(def ts (atom []))
(revolve (shape-fn (translate-shape (circle 5 8) 20 0) (fn [s t] (swap! ts conj t) s)))
[(count (distinct @ts)) (apply max @ts)]                ; => [64 0.984375]

;; la lunghezza dello sweep vista da dentro
(def pl (atom []))
(revolve (shape-fn (translate-shape (circle 5 8) 20 0)
                   (fn [s t] (swap! pl conj (current-path-length)) s)))
(distinct @pl)                                          ; => (nil 125.66)
```

## File toccati da Code

Codice: `src/ridley/turtle/shape_fn.cljs`, `src/ridley/turtle/path.cljs`,
`src/ridley/editor/bindings.cljs`, `src/ridley/editor/operations.cljs`,
`src/ridley/editor/edit_acquire.cljs`, `src/ridley/library/core.cljs`,
`src/ridley/schema.cljs`, `test/ridley/editor/sci_harness.cljs`,
`public/builtin-libraries/weave.clj`, `examples/recursive-tree.clj`,
`examples/print-cage.clj` (docstring).
Documenti: `docs/Spec.md` (§3, §4, §6, §17, §18.10), `docs/Architecture.md`
(11.3.1), `docs/Roadmap.md` (Current Sprint), `dev-docs/ShapeFn.md`,
`docs/manual/reference/en/{shell,embroid,heightmap,registration-cage}.md`, schede
nuove `current-path-length.md`, `smoothstep.md`, `shape-centroid.md`,
`src/ridley/manual/reference_index.cljs` (rigenerato).

## Cosa resta a Claude (manuale)

1. Cap. 6 IT+EN: dominio `(-π, π]` (riga 632) e la regola dopo `shell` (riga 596).
2. Cap. 9 IT+EN: header `;; Requires: a, b`; `defonce` sì; `^:private` escluso;
   `defmacro` non attraversa; niente `register` a top level.
3. Bozza cap. 19: chiudere i punti marcati A1–A4/B4 con quanto sopra;
   `(current-path-length)` al posto del parametro; `recursive-tree.clj` nuovo.

---

## Aggiornamento 2026-09-11 — D5 CHIUSA: tutta la UI in inglese (decisione di Vincenzo)

«Tutta la UI deve essere sempre in inglese»: fatto, non solo le etichette di
edit-acquire. Tradotte ≈1.100 stringhe utente (bottoni, tooltip, hint, messaggi
di stato, righe di log dell'acquire, errori parlanti) in: `edit_acquire.cljs`,
`acquire_stage.cljs`, `edit_mesh_split.cljs`, `modal_evaluator.cljs`,
`edit_path.cljs`, `mesh_board.cljs`, `macros.cljs` (l'errore di `export`),
`photogrammetry/{cage,plate,bundle,plate_calib,fuse}.cljs`, `export/stl.cljs`,
`viewport/inset.cljs`, `voice/{speech,core}.cljs`, `voice/help/ui.cljs`,
`manual/draft_renderer.cljs`, `manifold/core.cljs`. Commenti e docstring
restano come sono (non sono UI). Lasciati apposta in italiano: il vocabolario
dei comandi vocali (`voice/i18n.cljs`, `voice/help/db.cljs`: dati bilingui, non
testo mostrato), le parole-chiave del feedback AI (`core.cljs`, «sbagliato / non
così»), gli esempi few-shot del prompt AI, la CLI di studio `photogrammetry/cli.cljs`
(strumento da terminale per gli studi, non l'app — se la vuoi inglese anche lei,
sono 83 stringhe: dimmelo), e le etichette del browser del manuale che seguono
il toggle di lingua («Indietro» solo quando il manuale è in italiano).

**Mappa delle etichette per il cap. 20 (IT+EN)** — Claude aggiorna le citazioni:

| Prima | Ora |
|---|---|
| Registra per punti (p) | Register by points (p) |
| Auto — leggi la gabbia (a) | Auto — read the cage (a) |
| Auto — rileva e registra (a) | Auto — detect and register (a) |
| Rifinisci insieme (R) | Refine together (R) |
| Calibra il piatto (C) / Ricalibra il piatto (C) | Calibrate the plate (C) / Recalibrate the plate (C) |
| Conferma (OK) | Confirm (OK) |
| Chiudi | Close |
| Azzera | Reset |
| Assegna (r) · Annulla ultimo · Modalità armata (b) · Senza identità (b) · Risolvi PnP (r) | Assign (r) · Undo last · Armed mode (b) · No identities (b) · Solve PnP (r) |
| Esci (p) / Esci (k) / Esci (Esc) | Exit (p) / Exit (k) / Exit (Esc) |
| Segna punti (k) | Mark points (k) |
| Marca il segno (m) / Segno marcato ✓ — rimarca (m) | Mark the sign (m) / Sign marked ✓ — mark again (m) |
| Rendi attivo | Make active |
| Focale (mm) | Focal (mm) |
| ◀ Esci dal palcoscenico / ▶ Palcoscenico (camera libera) | ◀ Leave the stage / ▶ Stage (free camera) |
| Frustum foto: mostrati (nascondi) / nascosti (mostra) | Photo frustums: shown (hide) / hidden (show) |
| HUD del piano: Accetta · Rifai · Chiudi · Aggiungi punto · Origine al centro · Punto successivo · Crea il piano · Annulla click · Controlla e accetta · PIANO DI LAVORO | Accept · Redo · Close · Add point · Origin at centre · Next point · Create the plane · Undo click · Check and accept · WORKING PLANE |

Le etichette già inglesi (Add anchor (d), Hide cage (v), New anchor (n), Exit (d),
Photo / Marks / Plane, Camera, Grab (g), Delete view N) non cambiano. Anche le
righe del pannello e della pellicola (tooltip delle miniature: «— registered», «—
posed by eye, to be registered: press 'a'») e gli hint di sessione sono in inglese:
se il capitolo ne cita qualcuna alla lettera, va riletta dall'app.
