# Accertamento mesh-split — prerequisiti per il taglio piano interattivo e la conversione convessa

Natura: **accertamento, nessuna modifica al codice di produzione.** Ogni domanda va misurata (REPL, ispezione del binding, test-scratch poi rimosso), non inferita per analogia. Le risposte vanno annotate qui sotto, sotto ogni domanda.

## Contesto

È in discussione una famiglia di strumenti di "acquisizione" STL, con due stadi:

1. **`mesh-split`**: primitiva che taglia una mesh sul piano definito dalla posa corrente della turtle (punto = position, normale = heading) restituendo le due metà. Sopra ci vive **`edit-mesh-split`**, terzo strumento modale della famiglia `edit-bezier`/`pilot`: sessione interattiva in cui l'utente muove la turtle, vede il piano di taglio e le due metà colorate, e alla conferma emette codice dichiarativo. Il modello di emissione previsto è lineare "a ghigliottina": un `path` con un `mark` per taglio, e una `reduce` che a ogni mark fa `(goto mark)` + `(mesh-split remaining)`, accumula `behind` e passa `ahead` al taglio successivo.
2. **Conversione convessa** (`stl->convex-sdf` / ricostruzione snella): per ogni pezzo convesso, facce → piani, dedup dei complanari, ricostruzione come intersezione di `sdf-half-space` (o mesh minimale). Esatta per STL poliedrici. Richiede un predicato `convex?` per non restituire silenziosamente lo hull di una mesh concava, e lo stesso predicato serve al tool interattivo per colorare le metà (verde = convessa, rossa = ancora concava).

L'accertamento verifica i cinque prerequisiti da cui dipende la forma del brief. Nessuna delle risposte è ovvia dal sorgente Ridley da solo: due dipendono dal binding Manifold, una dal comportamento runtime di `mark`/`goto`, due da macchinario esistente la cui riusabilità è da misurare.

---

## Sintesi (TL;DR)

Tutti e cinque i prerequisiti sono **verdi**, con due warning:

| # | Domanda | Esito |
|---|---------|-------|
| 1 | Taglio piano nativo nel binding WASM | **SÌ** — `splitByPlane` restituisce le DUE metà, `trimByPlane` una. Live-compatibile (~6.7 ms per split di ~4600 tri). Il wrapper Ridley non li raggiunge ancora ma il marshalling c'è già. |
| 2 | `mark` posa completa + `goto` ripristino | **SÌ** — mark salva `{:position :heading :up}`, goto ripristina tutti e tre. Pattern path+mark+reduce supportato as-is. |
| 3 | `auto-face-groups` riusabile come facce→piani | **PARZIALE** — hex→8 gruppi ✓, ma niente offset esposto, niente dedup cross-gruppo, e **crolla** su superfici curve (facet < 5° → tutta la parete in un gruppo non-planare). Buono come primo passo solo su poliedri. |
| 4 | Predicato `convex?` | **NON esiste**, ma economico: `hull` c'è (ratio vol misurato: sfera 1.0, frame concavo 0.84) e la via O(V·F) su piani deduplicati costa ~1–2 ms. Entrambe live-compatibili. |
| 5 | Volume raggiungibile dal wrapper | **SÌ** — `.volume()`/`.surfaceArea()` esistono, già usati in `get-mesh-status`. Gratis una volta materializzata la Manifold. |

⚠️ **Warning A (versione Manifold):** `public/index.html` carica `manifold-3d@3.0.0` da CDN, ma il lockfile risolve **3.3.2**. Le misure qui sono su 3.3.2 (il `.wasm` in `node_modules`). `split/trim/hull/volume` esistono da Manifold v2, quindi il 3.0.0 caricato in browser li ha quasi certamente — ma **da riverificare sul modulo effettivamente caricato** quando si implementa (e valutare di allineare le versioni).

⚠️ **Warning B (naming):** `slice-mesh` è già preso ed è un'operazione DIVERSA (sezione 2D, non taglio solido). `mesh-split` è libero. Vedi Domanda 1.

---

## Domanda 1 — Il binding Manifold espone un taglio per piano nativo?

**Perché conta.** Manifold C++ ha `TrimByPlane` (e in alcune versioni `SplitByPlane`) come operazione di prim'ordine: esatta e a buon mercato. Se il binding WASM la espone, `edit-mesh-split` può ricalcolare il taglio a ogni tasto in tempo reale. Se non la espone, il fallback è una booleana contro un box gigante: funziona ma è costosa, e l'interattività live va rivalutata. Questa risposta decide l'architettura dello strumento.

**Cosa misurare.**
- Il pacchetto `manifold-3d` in uso (versione corrente nel lockfile) espone `trimByPlane` / `splitByPlane` sull'oggetto Manifold? Ispezionare il `.d.ts` del pacchetto e `Object.keys` sul manifold a runtime.
- Se sì: prova dal REPL su un cubo, verificare che le due metà (o la metà trimmata) siano manifold valide.
- Timing su una mesh realistica (STL importato da qualche migliaio di triangoli): un `trimByPlane` sta dentro un budget da keystroke (~decine di ms)?
- `slice-mesh` esistente: cosa fa davvero? Si appoggia già a un'operazione di piano del binding (nel qual caso `mesh-split` potrebbe condividerne il substrato) o fa altro (cross-section 2D)? Serve per evitare duplicazione e per decidere il naming rispetto alla famiglia `mesh-*`.

**Esito.** **SÌ — taglio piano nativo disponibile, e restituisce le due metà.**

**Binding (Manifold 3.3.2, `node_modules/manifold-3d/manifold-encapsulated-types.d.ts`).** Sulla classe `Manifold`:
- `splitByPlane(normal: Vec3, originOffset: number): [Manifold, Manifold]` (riga 837) — **restituisce ENTRAMBE le metà**: la prima nel verso della normale, la seconda opposta. È letteralmente la primitiva ghigliottina cercata dal brief.
- `trimByPlane(normal: Vec3, originOffset: number): Manifold` (riga 849) — tiene solo la metà nel verso della normale (rimuove tutto dietro il semispazio).
- `split(cutter: Manifold): [Manifold, Manifold]` (riga 825) — split generico contro un'altra manifold (intersezione + differenza in una call).
- `slice(height): CrossSection` (riga 859) = sezione 2D parallela a X-Y a Z=height — **NON** un taglio solido.
- `hull()` (872), `volume()` (980), `surfaceArea()` (975) presenti (vedi Q4/Q5).

**Prova runtime (scratch Node contro il `.wasm` 3.3.2, poi rimosso).** Cubo centrato (vol 8):
- `splitByPlane([1,0,0], 0)` → due metà vol **4.0 + 4.0**, entrambe manifold valide (`isEmpty` false, `genus` 0, `numTri` 12).
- offset 0.5 → **2.0 + 6.0** (l'`originOffset` funziona come atteso).
- `trimByPlane([1,0,0], 0)` → metà tenuta vol **4.0**.

**Timing (⚠️ l'API JS è LAZY — le operazioni costruiscono un grafo, materializzato solo su un "sink": `numTri`/`volume`/`getMesh`; senza forzare i tempi sono ~0 e falsi).** Forzando la materializzazione con `getMesh` (= ciò che serve al rendering delle due metà colorate):

| mesh | splitByPlane (2 metà) + getMesh | trimByPlane (1 metà) + getMesh | hull + getMesh |
|---|---|---|---|
| 1152 tri | 1.9 ms | 1.3 ms | 0.58 ms |
| **4608 tri** (~STL tipico) | **6.7 ms** | **4.8 ms** | 2.6 ms |
| 12800 tri | 17.8 ms | 13.4 ms | 7.8 ms |

→ **Dentro il budget keystroke** per mesh di qualche migliaio di triangoli (<7 ms per lo split completo); ~1 frame a 12k tri. Ricalcolo live a ogni tasto fattibile.

**Sorpresa che smentisce la premessa del brief:** il fallback `subtract(box gigante)` **non** è più costoso — anzi ~2.0 ms/4608 tri (una metà). Quindi non è vero che "il nativo è a buon mercato e la booleana costosa": sono nello stesso ordine. Il vero vantaggio di `splitByPlane` è **ergonomico** (entrambe le metà coerenti sullo STESSO piano di taglio in una sola call, senza costruire/posizionare box), non prestazionale.

**`slice-mesh` — cosa fa davvero: sezione 2D, NON taglio solido.** SCI symbol `slice-mesh` → `implicit-slice-mesh` ([implicit.cljs:764-789](src/ridley/editor/implicit.cljs#L764-L789)) → `manifold/slice-at-plane` ([manifold/core.cljs:826-920](src/ridley/manifold/core.cljs#L826-L920)): trasforma i vertici nel frame del piano (piano di taglio → Z=0), costruisce una Manifold e chiama `.slice 0` → `CrossSection` → poligoni 2D (contorni classificati outer/hole). Ha anche una modalità `:on <mark|frazione>` che restituisce il profilo generativo 2D senza toccare la mesh. **Nessuna condivisione di substrato con `mesh-split`**: `slice` è cross-section (solido→contorni 2D), `mesh-split` è solido→2 solidi. Il nome `slice-mesh` è quindi già occupato da un'operazione diversa → **usare `mesh-split`** (nome libero, semanticamente distinto).

**Stato del wrapper.** Oggi Ridley **non** raggiunge `splitByPlane`/`trimByPlane` (grep in `src/` = 0 occorrenze); il solo uso di piano è `.slice` per la sezione 2D, e l'unico half-space "solido" è in SDF (`sdf-half-space`/`sdf-clip`, [implicit.cljs:815-851](src/ridley/editor/implicit.cljs#L815-L851)). Ma il marshalling esiste già: `mesh->manifold`/`manifold->mesh` ([manifold/core.cljs:154-174](src/ridley/manifold/core.cljs#L154-L174)). Wrappare è banale: `mesh->manifold` → `.splitByPlane [nx ny nz] d` → `manifold->mesh` su ciascuna metà → `.delete`. (Vedi Warning A sulla versione CDN 3.0.0 vs lockfile 3.3.2.)

---

## Domanda 2 — `mark` registra la posa completa e `goto` la ripristina?

**Perché conta.** Il pattern di emissione si regge sull'idea che un mark codifica già un piano: punto + normale = position + heading. Se `(goto :cut-1)` ripristina solo la posizione e lascia heading/up com'erano, il piano di taglio al momento del `mesh-split` è indefinito e l'intero pattern path+mark+reduce salta (o va integrato con `set-heading` espliciti emessi accanto a ogni goto, che è un design diverso).

**Cosa misurare.** Script minimo: un `path` che ruota la turtle (`tv`/`th`/`rt`) prima di un `mark`, poi da fuori un `goto` su quel mark, e lettura di position, heading e up. Verificare sia il caso path appena valutato sia il caso mark su mesh (anchor), se i due cammini differiscono.

**Esito.** **SÌ — mark salva la posa completa (position + heading + up), goto la ripristina completa.** (Evidenza: codice esatto, non per analogia.)

**Rappresentazione posa.** La turtle è `{:position :heading :up ...}` ([turtle/core.cljs:27-51](src/ridley/turtle/core.cljs#L27-L51)); non c'è `right` memorizzato (derivato via cross product). Il frame completo è **position + heading + up**.

**`mark`.** Dentro un `path` è un recorder: `rec-mark*` ([macros.cljs:32-34](src/ridley/editor/macros.cljs#L32-L34)) registra solo `{:cmd :mark :args [name]}` (nessuna posa al record-time). La posa è catturata al replay, in `run-path` ([turtle/core.cljs:1744-1747](src/ridley/turtle/core.cljs#L1744-L1747)):
```clojure
:mark (assoc-in s [:anchors (first args)]
                {:position (:position s)
                 :heading  (:heading s)
                 :up       (:up s)})
```
→ salva la **posa completa** (position + heading + up), non solo la posizione. Un mark codifica quindi già un piano (punto = position, normale = heading).

**`goto`.** `turtle/goto` ([turtle/core.cljs:2507-2527](src/ridley/turtle/core.cljs#L2507-L2527)) ripristina **tutti e tre**:
```clojure
(-> state'
    (assoc :position (:position anchor))
    (assoc :heading  (:heading anchor))
    (assoc :up       (:up anchor)))
```
Il dubbio del brief ("goto ripristina solo la posizione?") è direttamente smentito dal codice: heading e up vengono ri-assegnati esplicitamente. Nessun `set-heading` accanto al goto è necessario.

**Path appena valutato vs mark su mesh (anchor): stesso schema, stesso resolver.** Entrambi passano per `resolve-marks` ([turtle/core.cljs:1765-1781](src/ridley/turtle/core.cljs#L1765-L1781)) e producono `name → {:position :heading :up}`. L'unica differenza è la **posa di riferimento** con cui si risolvono:
- path via `with-path`/`goto` → risolti alla posa live corrente della turtle ([implicit.cljs:283-287](src/ridley/editor/implicit.cljs#L283-L287));
- anchor bakati su mesh → risolti al `:creation-pose` della mesh (rail-anchors in extrude, [extrusion.cljs:2014-2027](src/ridley/turtle/extrusion.cljs#L2014-L2027); `attach-path`, [implicit.cljs:311-326](src/ridley/editor/implicit.cljs#L311-L326)).

`move-to :at`/`attach` consumano heading+up ([impl.cljs:800-833](src/ridley/editor/impl.cljs#L800-L833)) e con `:align?` ruotano la mesh sul frame. **Conclusione:** il pattern path+mark+`reduce`+goto regge as-is, senza design alternativo.

---

## Domanda 3 — `auto-face-groups` è riusabile come dedup facce→piani?

**Perché conta.** Lo stadio di conversione convessa ha bisogno di collassare i triangoli complanari di una faccia in un unico piano (normale + offset). `auto-face-groups` raggruppa già i triangoli per adiacenza complanare: se la tolleranza è accessibile e i gruppi espongono il piano, il passo facce→piani è quasi gratis; altrimenti va costruito.

**Cosa misurare.**
- Che tolleranza usa il raggruppamento (angolare? distanza? hardcoded o parametrizzabile)?
- I gruppi espongono normale e un punto/offset del piano (via `face-info` o simile), o solo gli id dei triangoli?
- Funziona su una mesh da `decode-mesh` (STL importato, niente face-groups predefiniti)? Prova concreta: un prisma esagonale importato produce esattamente 8 gruppi?
- Comportamento su superficie curva tassellata (cilindro): produce il numero atteso di gruppi-faccetta (tanti), o la tolleranza li fonde in modo inatteso? Serve a documentare il limite del caso poliedrico.

**Esito.** **PARZIALE — buon primo passo su poliedri (hex→8 verificato), ma NON è di per sé l'insieme dei piani, e CROLLA sui curvi.**

**Tolleranza.** `auto-face-groups` ([faces.cljs:307-343](src/ridley/geometry/faces.cljs#L307-L343)): soglia sul **prodotto scalare delle normali unitarie**, default `0.996` = cos(~5°), secondo argomento parametrizzabile (`(auto-face-groups mesh)` / `(auto-face-groups mesh threshold)`, riga 311). Il confronto usa `js/Math.abs` (`normals-similar?`, [faces.cljs:285-287](src/ridley/geometry/faces.cljs#L285-L287)) → **sign-insensitive** (normali antiparallele contano come complanari). È **solo angolare**: nessun controllo di distanza/offset del piano.

**Algoritmo.** BFS flood-fill su **adiacenza-per-spigolo** (`build-tri-adjacency`, [faces.cljs:289-305](src/ridley/geometry/faces.cljs#L289-L305)). Dettaglio cruciale: la normale di confronto `tn` è **ri-letta per ogni triangolo camminato** (faces.cljs:336), non contro una normale-seme fissa → cammina superfici che variano dolcemente, il che è la causa del crollo (sotto).

**Cosa espongono i gruppi.** `auto-face-groups` restituisce **solo `{group-id → [triangle...]}`** (indici; nessuna normale/offset). Normale + punto si ricavano on-demand da `compute-face-info`/`face-info` ([faces.cljs:50-77](src/ridley/geometry/faces.cljs#L50-L77), [226-258](src/ridley/geometry/faces.cljs#L226-L258)): `:normal` (dal **primo** triangolo), `:center` (centroide dei vertici), `:area`, `:vertices`, `:edges` — ma **nessun `:offset`/`:d`**. L'offset va calcolato (d = normale · center).

**Prova runtime (test scratch, `auto-face-groups` reale su mesh `{:vertices :faces}` grezze — nessun `:face-groups` predefinito, cioè il caso `decode-mesh`; poi rimosso):**

| prisma n-gono | giro per facet | tri | GRUPPI | atteso poliedrico (n+2) |
|---|---|---|---|---|
| **n=6 (hex)** | 60° | 20 | **8** ✓ | 8 |
| n=12 | 30° | 44 | 14 ✓ | 14 |
| n=48 | 7.5° | 188 | 50 ✓ | 50 |
| **n=72** | **5.0°** | 284 | **3** ✗ | 74 |
| **n=96** | 3.75° | 380 | **3** ✗ | 98 |

**Il prisma esagonale produce esattamente 8 gruppi** (6 lati + 2 tappe) ✓. Ma sotto la soglia di 5° per facet (n≥72) il flood-fill **fonde l'intera parete curva in UN solo gruppo** (non planare!) + le 2 tappe = 3 gruppi. Questo è il limite del caso poliedrico: `auto-face-groups` è affidabile solo su input **poliedrici** — su qualunque superficie curva finemente tassellata (cilindro, sfera) collassa in gruppi non-planari, e un converter che tratti "un gruppo = un piano" produrrebbe spazzatura (l'intera parete come un piano solo).

**Dettaglio hex (cosa serve costruire sopra).** Ogni gruppo: `:normal` corretta, `:center` corretto, `contains? :offset` = **false**. I due gruppi tappa (g6, g7) hanno **entrambi** normale `[0 0 1]` ma `:center` a z=0 e z=20 → sono **due piani distinti**, correttamente separati perché non adiacenti-per-spigolo.

**Verdetto per il converter convesso.** Riusabile come **primo passo** su poliedri, ma con tre lacune da colmare:
1. **Offset mancante** → calcolare d = `:normal` · `:center` per ogni gruppo.
2. **Nessuna dedup cross-gruppo** → patch complanari-e-coincidenti ma non adiacenti-per-spigolo restano gruppi distinti; il converter deve deduplicare i piani finali per `(normale, d)` con tolleranza (auto-face-groups non lo fa).
3. **Crollo sui curvi** → serve comunque il predicato `convex?` (Q4) a monte per rifiutare i curvi; abbassare la soglia (es. 0.9999) restringe ma non elimina il crollo su tassellazioni fittissime.

Il passo facce→piani è "quasi gratis" **solo** aggiungendo il calcolo dell'offset + la dedup `(normale, d)`; il grouping in sé è la parte facile.

---

## Domanda 4 — Esiste (o quanto costa) un predicato `convex?`

**Perché conta.** Serve due volte: nel tool interattivo (colorare live le metà, avvertire prima di un OK che accetta un `behind` concavo come definitivo) e nel convertitore (rifiutare o avvertire invece di restituire silenziosamente lo hull). `mesh-diagnose` dà topologia, non convessità geometrica, quindi l'ipotesi è che non esista.

**Cosa misurare.**
- Confermare che nulla di esistente risponde già (mesh-diagnose, wrapper, binding).
- Costo della via diretta: una mesh è convessa se nessun vertice sta davanti a nessun piano-faccia oltre epsilon. O(V·F) puro ClojureScript, ma con i piani deduplicati dalla Domanda 3 F crolla. Misurare il tempo su una mesh da qualche migliaio di triangoli: è compatibile con l'uso live (ricalcolo a ogni taglio) o va tenuto su richiesta?
- Via alternativa: il binding espone `hull` (convex hull)? Allora convex? ⟺ vol(hull) ≈ vol(mesh), che dipende dalla Domanda 5.

**Esito.** **NON esiste, ma è economico da costruire in due modi indipendenti, entrambi live-compatibili (<3 ms su ~4600 tri).**

**Conferma: nulla risponde già.** `mesh-diagnose` ([mesh_utils.cljs:73-101](src/ridley/geometry/mesh_utils.cljs#L73-L101)) = **solo topologia** (n-verts/faces/edges, open/non-manifold edges, facce degeneri per area<1e-10, caratteristica di Euler, watertight). Nessuna convessità, nessun volume. Nessun `convex?` in `src/`. (I "signed volume" in `faces`/`extrusion` sono solo test di winding, non misure.)

**Via A — hull-ratio (`convex?` ⟺ vol(hull) ≈ vol(mesh)).** `hull` **esiste**: `manifold/core.cljs` `hull` (statico `Manifold.hull`, bound a `mesh-hull-impl` [bindings.cljs:380](src/ridley/editor/bindings.cljs#L380)), più `hull-from-points` e lo hull 2D `shape-hull`. Misurato:
- `hull()` + getMesh: 0.58 ms (1152 tri) / **2.6 ms (4608)** / 7.8 ms (12800).
- Test del rapporto corretto: sfera → **1.0000** (convessa); frame concavo (cubo − cubo) → **0.840** (<1, concavo correttamente segnalato).
- Costo `convex?`-via-hull ≈ hull + 2×volume ≈ **~2.6 ms/4608 tri** → live-compatibile. Dipende dal volume (Q5).

**Via B — diretta O(V·F)** (nessun vertice davanti a nessun piano-faccia oltre epsilon), con i piani deduplicati da Q3 (F piccolo). Proxy aritmetico:
- poliedri (F ~ 8–100 piani): **~0.5–1.75 ms**.
- caso peggiore mesh curva senza dedup (F ~ #tri): 2306×4608 = 10.6 M dot → 2.1 ms; 6402×12800 = 82 M → 17 ms.
- Nota: per una mesh **convessa** non scatta l'early-exit (V·F pieno) — ed è proprio il caso che conta, comunque ~2 ms/4600 tri.

**Consiglio.** Per il verdetto esatto (colorazione verde/rossa, accettazione OK) usare **O(V·F) sui piani deduplicati** (esatto topologicamente, indipendente dal motore); tenere hull-ratio come guardia/sanity-check. Entrambe girano bene live.

---

## Domanda 5 — Il volume è raggiungibile dal wrapper?

**Perché conta.** Serve al test di convessità via hull (Domanda 4) e in generale come diagnostica (`bounds`, `area`, `distance` esistono; volume no, a quanto risulta dalla Spec). Manifold lo calcola internamente (`getProperties` / `volume` a seconda della versione del binding).

**Cosa misurare.** Il binding in uso espone la proprietà? Con che nome e su che oggetto? Costa un ricalcolo o è memoizzata dal motore? Nessuna richiesta di esporlo al DSL in questo accertamento: solo sapere se c'è e come si legge.

**Esito.** **SÌ — `.volume()` sull'oggetto `Manifold`, già usato nel wrapper; gratis una volta materializzata la mesh.**

**Binding.** Manifold 3.3.2 espone `volume(): number` (manifold-encapsulated-types.d.ts:980) e `surfaceArea(): number` (riga 975) **sull'oggetto `Manifold`**. In v3.0+ questi hanno rimpiazzato `getProperties()` (nota nel wrapper).

**Già raggiungibile dal wrapper.** `get-mesh-status` ([manifold/core.cljs:193-227](src/ridley/manifold/core.cljs#L193-L227)) restituisce `{:manifold? :status :volume :surface-area}` leggendo `(.volume manifold)` (riga 202) e `(.surfaceArea manifold)` (riga 203); `is-manifold?` usa `(.volume manifold)` (riga 188). Consumato da `ai/describe.cljs:57` e `ai/auto_session.cljs:147`. (In CLJS non c'è calcolo di volume-di-mesh: il `volume` in `sdf/core.cljs:847` è volume del bounding-box per il budget voxel.)

**Costo (misurato).** `volume()` su un oggetto Manifold **già materializzato è di fatto gratis e memoizzato**: ~0.002 ms (1152 tri) / 0.008 ms (4608) / 0.023 ms (12800) per call ripetuta. Il costo reale è **costruire la Manifold** da una mesh grezza: `new Manifold(mesh)` + `volume()` = 0.5 ms (1152) / 2.1 ms (4608) / 6.5 ms (12800) — dominato dal build topologico, non dal volume; ma è lo stesso build che ogni operazione CSG paga comunque. `bounds`/`area` esistono già al DSL; `volume` no, ma è disponibile via `get-mesh-status`. Abilita la via hull-ratio di Q4.

---

## Fuori scope

- Nessuna implementazione di `mesh-split`, `edit-mesh-split`, `convex?` o `stl->convex-sdf`: qui si misura, il brief viene dopo.
- La decomposizione convessa automatica (stile VHACD) resta fuori anche dal brief futuro: la decomposizione è manuale e interattiva by design.
- Il "mesh-board" (STL ghosted come riferimento di ricostruzione, analogo 3D di `image-board`+`edit-path-2d`) è l'orizzonte dichiarato della famiglia ma non è oggetto di questo accertamento.

---

## Nota metodologica (come sono state prese le misure)

- **Binding Manifold:** ispezione di `node_modules/manifold-3d/manifold-encapsulated-types.d.ts` (v3.3.2) + benchmark Node standalone che carica il `.wasm` reale (`Module({locateFile})` → `setup()`), forzando la valutazione lazy con `getMesh`/`numTri`/`volume`. Nessun browser necessario per Q1/Q4/Q5.
- **`auto-face-groups` (Q3):** test scratch `*-test` compilato con `shadow-cljs compile test` ed eseguito con `node out/test.js`, su mesh `{:vertices :faces}` costruite a mano (nessun `:face-groups` predefinito = caso `decode-mesh`). File di test rimosso dopo la lettura.
- **`mark`/`goto` (Q2):** lettura del codice esatto che costruisce e ripristina la posa (non inferito per analogia).
- Tutti i file scratch e il test temporaneo sono stati rimossi; **nessuna modifica al codice di produzione.**
