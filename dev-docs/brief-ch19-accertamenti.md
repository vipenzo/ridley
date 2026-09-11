# Brief — Cap. 19 "Estendere Ridley": accertamenti, sospetti di bug e buchi; seguiti del cap. 20

**Per:** Code
**Da:** Vincenzo + Claude
**Data:** 2026-09-10
**Contesto:** il cap. 20 (acquisire dalle foto, gabbia) è inserito nel manuale dal 2026-09-06 (IT+EN). Ora si scrive il cap. 19 "Estendere Ridley", con la scaletta decisa da Vincenzo il 2026-09-10: 19.1 il contratto delle shape-fn; 19.2 il contratto delle thickness-fn; 19.3 registrare e ispezionare (il materiale "Internals" del piano §6.3-6.5: registry, hook, collezioni); 19.4 il path come dato; 19.5 architettura di uno script parametrico; 19.6 le librerie sotto il cofano. Claude scrive la bozza con quello che il codice dice già; le cose qui sotto sono quelle che il codice NON dice, o dice in modo sospetto, e che vanno accertate allo schermo o corrette. Ruoli come da 2026-07-30: Code accerta e corregge codice/Spec/Architecture/reference, Claude aggiorna guida ed esempi.

Le righe citate sono del working tree al 2026-09-09.

## Parte A — Accertamenti per il cap. 19 (rispondere con sì/no + una riga)

### A1. Cosa può fare il codice utente nel context SCI

Nessun binding esplicito, nessuna menzione in Spec/Architecture, zero usi in librerie ed esempi. Il capitolo li cita solo se confermati:

1. `defmacro` nel buffer utente e in una libreria: funziona? Un `defmacro` in una libreria diventa pubblico col prefisso (la regex di `library/core.cljs:13-21` estrae solo `def`/`defn` a inizio riga)?
2. `atom` / `swap!` / `reset!`: disponibili? Sopravvivono fra un Run e l'altro (no, il context è ricostruito: `Architecture.md:1719`) e fra comandi REPL (sì?)?
3. `defmulti`/`defmethod`, `defprotocol`/`defrecord`: disponibili? Se sì, il capitolo NON li documenta comunque (nessun caso d'uso in Ridley), ma serve saperlo per la frase "cosa manca rispetto al Clojure vero".
4. `js/…` interop: confermare che è chiuso anche per `js/Math` (Spec.md:2616 dice che manca `js/parseInt`; la math è wrappata, Spec.md:4020-4065).

### A2. Il valore di un path

`path->data` (`bindings.cljs:234` → `path/path-from-state`) restituisce esattamente `{:type :path :commands [...]}` come in `Architecture.md:504-510`? Altre chiavi (marks, anchors, tag `:smooth` ecc.)? Serve la forma stampata di un path piccolo, per esempio `(path->data (path (f 30) (th 90) (arc-h 10 90)))`, da mettere nel capitolo così com'è. E `path?` risponde `true` sia a un path registrato sia al valore di `path->data`?

### A3. `revolve` e le shape-fn

`revolve` accetta una shape-fn (Spec.md:1374-1380). Domande: come è definito `t` in una rivoluzione (frazione dell'angolo?); `*path-length*` è bindata anche lì, e a cosa (lunghezza dell'arco al raggio del centroide? nil?). Se è nil, `capped` e `heightmap :fit :physical` degradano ai default in silenzio: da dire nel capitolo. `extrusion.cljs` / il consumer del loft non erano fra i file letti.

### A4. Header delle dipendenze di una libreria

`storage.cljs:126-142` parsa `;; Requires: a, b`; il cap. 9 (`09-librerie.md:135`) documenta `;; :requires [my-shapes utils]`. Qual è quella vera? Se entrambe, dirlo; se una sola, Claude corregge il cap. 9.

## Parte B — Sospetti di bug nelle shape-fn (`src/ridley/turtle/shape_fn.cljs`)

Trovati leggendo il codice per il 19.1/19.2. Nessuno è verificato allo schermo; per ciascuno serve un test minimo e, se confermato, o la correzione o una riga di Spec che dichiari il limite.

1. **`:base` letto a un livello solo in `heightmap`** (riga 772: `(if (shape-fn? shape-or-fn) (:base (meta shape-or-fn)) shape-or-fn)`). Su una catena a due livelli, per esempio `(-> (circle 20) (fluted 8 2) (heightmap hm :fit :physical))`, `base` è la shape-fn di `fluted`, non il cerchio, e finisce in `(shape/shape-perimeter base)` (riga 796). Attesa: NaN o perimetro sbagliato. Test: la catena sopra dentro un `loft`, con e senza `fluted` in mezzo.
2. **`:point-count` stantio** (righe 69-71: calcolato una volta alla costruzione; riga 70: se `base` è shape-fn viene ereditato). `morphed` ricampiona (righe 220-230): una catena `(-> A (morphed B) (altro))` porta il `:point-count` di A. Chi legge quel metadata a valle e cosa succede se è sbagliato? Se nessuno lo legge, si toglie dalla documentazione; se qualcuno lo legge, serve l'assert o il ricalcolo.
3. **`shape-fn` con base parziale**: il costruttore testa `(shape-fn? base)` (riga 63) mentre `dev-docs/ShapeFn.md:212-220` documenta `(fn? base)`. Una forma parziale come `(tapered :to 0.5)` è `fn?` ma non `shape-fn?` (Spec.md:722): passata come `base` a `shape-fn` viene trattata come shape statica e si rompe in silenzio? Test: `(shape-fn (tapered :to 0.5) (fn [s t] s))` in un loft. Esito atteso: o errore parlante, o supporto.
4. **Dominio dell'angolo nelle thickness-fn**: il codice usa `atan2` sul centroide (righe 1343-1347), quindi `a ∈ [-π, π]`; il cap. 6 riga 632 dice `[0, 2π]`. Confermare il codice; Claude corregge il cap. 6 (IT+EN) e scrive il 19.2 con il dominio giusto. Nota collegata: con `:style :pattern` il primo argomento non è un angolo ma la frazione di arco-lunghezza `u ∈ [0,1)` (righe 933-951, 1335-1337): vale solo per lo stile built-in? Un `:fn` custom riceve sempre l'angolo?
5. **`tapered` dopo `shell`**: il cap. 6 (riga 596) dice che `tapered` è l'unica shape-fn che può seguire `shell` perché preserva i metadata. Perché `twisted` (che usa `xform/rotate`) no? Se è solo una questione di `assoc` che preserva le chiavi extra, o si generalizza o si documenta la lista esatta di ciò che preserva.

## Parte C — Buchi (decisioni di prodotto, non bug)

1. **Helper privati** che chi scrive una thickness-fn o una shape-fn deve riscrivere a mano: `smoothstep` (riga 920), `signed-dist-poly` (1124), `shape-centroid`, `perimeter-fractions` (933), i `v2-*`. Proposta: esportare almeno `smoothstep` e `shape-centroid` in `bindings.cljs`; il capitolo li documenta se esportati, altrimenti mostra come riscriverli.
2. **`embroid` non accetta `:fn`** (usa `panel-field` interno, righe 1136-1147): il pattern custom su parete sottile non è esprimibile. O si aggiunge `:fn`, o il capitolo lo dichiara come limite.
3. **`register` a top level in una libreria** (`weave.clj:52-56`): attivare la libreria aggiunge geometria alla scena a ogni Run. È voluto? Se no, `weave.clj` va pulita; in ogni caso il 19.6 lo documenta come trappola.
4. **`dev-docs/ShapeFn.md` è obsoleto** in almeno tre punti: `angle` "relativo al centroide" (riga 284; il codice, righe 101-105, usa l'origine, e il cap. 6 riga 196 dice giusto), `fn?` invece di `shape-fn?` (riga 212), `rugged` descritta come sinusoide singola (riga 115; oggi è fBm multi-ottava, righe 149-184). Da aggiornare o da marcare come storico in testa.
5. **Reference Internals**: `structure.cljs` la dichiara `:visible? false` perché non esistono schede. Il cap. 19 sarà per ora l'unica casa di questo materiale; quando le schede arriveranno, il capitolo rimanderà lì.

## Parte D — Seguiti del cap. 20 (già segnalati a voce, qui per la coda)

1. **`Rifinisci insieme (R)`**: su gabbia funziona il tasto ma il bottone è offerto solo sul piatto (`edit_acquire.cljs:8128-8142`, `(and (plate-proxy?) …)` con commento "plate-only"). Offrirlo anche su gabbia, con la stessa condizione (≥2 foto con ≥4 click) o con quella giusta per le registrazioni dai dischetti.
2. **Riga-suggerimento del pannello su gabbia** (`session-hint`, 8719-8744): una gabbia cade nel ramo `plate-proxy?` e legge il testo del piatto, che cita `'C'` (nascosto su gabbia). Serve un ramo `cage-proxy?`.
3. **Grab su gabbia**: la riga info dice "Grab keeps the frame ONLY if it registers" (8652) ma su gabbia il frame è tenuto comunque (6768-6774); il messaggio "tenuta, da registrare — 4 dischetti con 'p', poi 'a'" (6741) ignora la via della posa a occhio + `a`, che è quella documentata. Anche l'hint di sessione vuota (8730) è piatto-centrico.
4. **`reference/en/registration-cage.md`**: ordine di incollaggio ancora gen 1 (righe 186-190: piccolo e medio prima, grande per ultimo) mentre `print-cage.clj` gen 2 dice grandi, pezzo, piccolo; cita `acquire-cage/measured` (la libreria non esiste più, `measured` sta in `print-cage.clj`); prescrive l'epossidica (Vincenzo incolla col cianoacrilato senza problemi, e il cap. 20 dice così). Idem la docstring di `save-3mf` in `print-cage.clj:395` ("epoxy").
5. **Etichette UI miste** in edit-acquire (`Add anchor (d)`, `Hide cage (v)`, `New anchor (n)`, `Exit (d)` in inglese; `Registra per punti (p)`, `Conferma (OK)`, `Chiudi` in italiano) e toolbar del palcoscenico in inglese (`Photo`, `Marks`, `Plane`). Il manuale le riporta esatte in entrambe le lingue; se si uniformano, Claude aggiorna il cap. 20 IT+EN.

## Parte E — Emersi dalla verifica della bozza del cap. 19 (2026-09-10)

1. **`*path-length*` non è nei binding** (`bindings.cljs` la ignora): una shape-fn utente non può leggerla. La bozza aggira il buco passando `(path-length spine)` come parametro. Decisione: esporla (una riga in bindings, ma va bindata dal loft sulla stessa var che SCI vede) o lasciare così e dirlo in Spec.
2. **`examples/recursive-tree.clj`** usa `(cyl r r2 len)` a tre argomenti (che per la Spec è `(cyl radius height segments)`, il tronco è `cone`) e `push-state`/`pop-state`, che la Spec dà per sostituiti da `turtle` e che non risultano nei binding. O l'esempio è stantio o i binding esistono altrove (`macros.cljs`, `implicit.cljs`): accertare, e se stantio riscriverlo con `cone` e `turtle` (la bozza del 19.5 lo fa già).
3. **Cap. 9 riga 105** dice che `defonce` funziona in una libreria; la regex di `library/core.cljs:20` lo esclude dai nomi pubblici. Claude allinea il cap. 9 all'esito.
4. **Nomi pubblici e `^:private`**: `(def ^:private x ...)` viene esportato dalla regex (il tag di metadata è ammesso, `gears.clj:319-320` espone così `standard-modules`). Se è indesiderato, escludere il tag nella regex.
5. **`:softness` con `:fn`**: `eff-soft` è 0 senza uno `:style` in `#{:voronoi :lattice :pattern}`, quindi una `:fn` custom non ha mai il taglio isocontorno; passando `:style :voronoi :fn f` il `:fn` vince ma `smooth?` si accende. Voluto? Se sì, documentare che `:fn` + `:style` è il modo per avere aperture lisce da una funzione; se no, dare a `:fn` la sua `:softness`.

## Cosa fa Claude nel frattempo

Scrive la bozza del cap. 19 con il materiale già verificabile nel codice, marcando nel commento in testa i punti che dipendono da A1-A4 e B4; alla risposta di Code si chiudono con edit puntuali. Corregge il cap. 6 (dominio dell'angolo, rimandi in avanti alle 19.1/19.2) e il cap. 9 (header dipendenze) quando A4 e B4 sono accertati.
