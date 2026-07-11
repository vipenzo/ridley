# Brief: mesh-split — taglio piano nativo, predicato di convessità, allineamento versione Manifold

## Contesto

Questo è il primo di due brief per lo strumento di decomposizione interattiva delle mesh (famiglia "acquisizione STL"). Qui si costruisce lo strato senza UI: la primitiva `mesh-split` e il predicato `convex?`. Il secondo brief (`edit-mesh-split`, sessione modale con emissione path+mark+reduce) arriverà dopo, e il converter `stl->convex-sdf` più avanti ancora.

Tutti i prerequisiti sono stati misurati in `dev-docs/mesh-split-accertamento.md` (tutti verdi, due warning). I fatti su cui questo brief si appoggia:

- Manifold 3.3.2 espone `splitByPlane(normal, originOffset) → [Manifold, Manifold]` (prima metà nel verso della normale, seconda opposta) e il marshalling `mesh->manifold`/`manifold->mesh` esiste già. Timing live-compatibile (~6.7 ms per split completo di ~4600 tri, forzando la valutazione lazy con `getMesh`).
- `mark` salva la posa completa e `goto` la ripristina: il pattern di emissione del brief 2 regge as-is, e la convenzione dei lati fissata qui verrà consumata lì.
- `hull` e `.volume()` sono raggiungibili (hull ~2.6 ms/4600 tri; volume memoizzato sull'oggetto Manifold): la via hull-ratio per `convex?` è percorribile e live-compatibile.
- ⚠️ Warning A dell'accertamento: `public/index.html` carica `manifold-3d@3.0.0` da CDN mentre il lockfile risolve 3.3.2; tutte le misure sono su 3.3.2. Va chiarito e allineato prima di costruirci sopra (Parte 0).
- ⚠️ Warning B: il nome `slice-mesh` è occupato da un'operazione diversa (cross-section 2D via `.slice`). Il nome della primitiva è `mesh-split`.

Convenzione dei lati, fissata su Spec §11: `sdf-half-space` di default tiene la metà *dietro* l'heading, il lato da cui la turtle proviene, coerente con `extrude` (dopo l'estrusione il materiale è dietro la turtle). `mesh-split` adotta la stessa semantica, così c'è una sola verità su quale lato è quale in tutto il sistema.

## Lavoro richiesto

### Parte 0 — Chiarimento e allineamento versione Manifold (prerequisito, commit separato)

Prima di tutto, chiarire chi carica cosa: quali ambienti (webapp servita, binario desktop Tauri, suite di test) usano il modulo CDN 3.0.0 di `index.html` e quali il 3.3.2 di `node_modules`. Se risulta che il runtime visto dall'utente usa il 3.0.0 mentre la suite gira contro il 3.3.2, riportarlo esplicitamente nel report: significa che oggi i test non testano il modulo che gira, ed è un fatto che va a verbale indipendentemente da mesh-split.

Poi allineare: lockfile e canale runtime sulla stessa versione (3.3.2, salvo controindicazioni emerse dal chiarimento). Suite completa verde, commit piccolo e separato dal resto del brief. Se l'allineamento fa emergere regressioni (differenze di tessellazione, comportamenti CSG cambiati tra 3.0.0 e 3.3.2), fermarsi e riportare in stile accertamento: niente fix al volo dentro questo brief.

### Parte 1 — Wrapper: split-by-plane

In `manifold/core.cljs`, seguendo il marshalling esistente: `mesh->manifold` → `.splitByPlane` → `manifold->mesh` su ciascuna metà → `.delete` degli oggetti intermedi (stessa disciplina di cleanup delle operazioni esistenti).

La funzione wrapper prende mesh, normale e offset e restituisce le due metà. La mappatura ai nomi `:behind`/`:ahead` può vivere qui o nel binding DSL, ma in un solo posto, documentato: con normale = heading, il primo elemento di `splitByPlane` (verso della normale) è `:ahead`, il secondo è `:behind`.

Metà vuota (piano che non interseca la mesh, o la sfiora): Manifold restituisce una manifold `isEmpty`. Decidere la rappresentazione lato Ridley (mesh vuota o `nil`), documentarla, e mantenerla coerente ovunque. Non è un errore: è un risultato legittimo, e il brief 2 ci costruirà sopra ("il taglio non tocca il pezzo" è uno stato normale della sessione interattiva).

### Parte 2 — Binding DSL: mesh-split

`(mesh-split mesh)`: taglia sul piano definito dalla posa corrente della turtle — punto = position, normale = heading, quindi `originOffset` = heading · position. Funziona dentro `turtle`/`with-path` scope come ogni altra operazione che legge la posa.

Ritorna la mappa `{:behind <mesh> :ahead <mesh>}`, dove `:behind` è la metà dietro l'heading (il lato del materiale, semantica `sdf-half-space`/`extrude`). Il docstring deve enunciare la convenzione e il suo aggancio a `sdf-half-space`, perché è un contratto che il brief 2 e il converter consumeranno.

Metadati delle metà (creation-pose, anchors, attributi come il colore): allineare la policy a quella delle booleane Manifold esistenti (`mesh-union`/`mesh-difference`/`mesh-intersection`), qualunque essa sia — nessuna policy nuova inventata qui. Se le booleane esistenti risultano incoerenti tra loro su questo punto, riportare la difformità e adottare la variante più coerente, documentando la scelta.

### Parte 3 — Predicato convex?

`(convex? mesh)` via hull-ratio: convessa se `volume(mesh) / volume(hull(mesh)) ≥ 1 - epsilon`. Secondo argomento opzionale per l'epsilon, coerente con lo stile di `auto-face-groups` (soglia come argomento posizionale opzionale).

L'epsilon di default va calibrato con misure, non scelto a tavolino: la suite di verifica sotto elenca forme note con esito atteso, e il report deve motivare il valore scelto sulla base dei ratio effettivamente misurati (l'accertamento dà già due punti: sfera tassellata → 1.0000, frame concavo → 0.840).

Mesh vuota: `(convex? empty)` → `true` (l'insieme vuoto è convesso per definizione), con nota nel docstring; il trattamento speciale della metà vuota nella UI è affare del brief 2.

Il costo (~2.6 ms/4600 tri) è compatibile con l'uso live previsto dal brief 2; nessuna ottimizzazione richiesta qui.

## Verifica

- Replica come test permanenti delle misure dell'accertamento: cubo centrato vol 8, split alla posa d'origine → metà 4+4; posa spostata di 0.5 lungo l'heading → 2+6.
- Test di convenzione dei lati: estrudere un solido, poi `mesh-split` alla posa finale della turtle → il materiale sta interamente in `:behind` (e `:ahead` è vuoto o quasi). È il test che salda la convenzione a `extrude`/`sdf-half-space` e la documenta in modo eseguibile.
- Validità delle metà: `mesh-diagnose` su entrambe (watertight, nessun edge non-manifold) su almeno un caso non banale (mesh da `decode-mesh` o CSG result, non solo primitive).
- Caso degenere: piano che non interseca la mesh → mappa con metà vuota nella rappresentazione decisa, nessuna eccezione.
- `convex?`: box, sfera tassellata, prisma esagonale, cilindro tassellato fine → `true`; frame (cubo − cubo), prisma a L, toro → `false`. Epsilon di default motivato nel report con i ratio misurati.
- Metadati: un test che documenta la policy adottata per creation-pose/anchors delle metà (qualunque sia la policy, il test la rende esplicita).
- Suite completa verde. Il commit di Parte 0 verde per conto suo, prima del resto.

## Fuori scope

- `edit-mesh-split` (sessione modale, rendering del piano, colorazione delle metà, emissione): brief 2.
- Il converter `stl->convex-sdf` e il test di convessità diretto O(V·F) sui piani deduplicati: il suo habitat è il converter, dove i piani deduplicati esistono per costruzione.
- Esposizione di `trimByPlane` al DSL: nessun caller concreto oggi; si aggiunge quando emerge un caso.
- Esposizione di `mesh-volume` al DSL: internamente il volume è già raggiungibile (`get-mesh-status`); l'esposizione pubblica aspetta un caso concreto.
- Decomposizione convessa automatica (VHACD) e "mesh-board": orizzonti dichiarati della famiglia, non oggetto di questo brief.

## Alternative considerate e scartate

- **Booleana `subtract(box gigante)` al posto di `splitByPlane`.** L'accertamento ha falsificato la premessa che fosse più costosa: i tempi sono comparabili. `splitByPlane` resta la scelta per ergonomia, non per performance: due metà coerenti sullo stesso piano in una sola call, nessun box da dimensionare e posizionare, nessun rischio di box troppo piccolo.
- **Ritorno a coppia `[behind ahead]`.** Scartato per leggibilità: la mappa è auto-documentante e in linea con `bounds`/`mesh-diagnose`. La `reduce` del pattern di emissione (brief 2) destruttura la mappa altrettanto bene.
- **`convex?` via test diretto O(V·F) anche nel contesto live.** Scartato qui: le metà prodotte da un taglio sono mesh arbitrarie, anche curve, senza piani deduplicati disponibili; il caso peggiore della via diretta (~17 ms a 12800 tri) è più caro di hull-ratio (~7.8 ms). La via diretta vivrà nel converter, dove è esatta e i piani deduplicati esistono già.
- **Riuso di `slice-mesh` come substrato.** È un'operazione diversa (solido → contorni 2D via `.slice`, non solido → due solidi); nessuna condivisione possibile (accertamento, Q1).
