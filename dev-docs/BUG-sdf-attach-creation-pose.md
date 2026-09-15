# Bug: attach su SDF ignora la creation-pose (pivot all'origine del mondo)

Aperto 2026-08-25 (Vincenzo + Claude-docs). Scoperto da Vincenzo su un caso
reale; diagnosi verificata su `src/ridley/editor/impl.cljs` della copia
corrente.

**Stato: FIXATO 2026-08-25** — `sdf-attach-impl` inizializza il loop dalla
creation-pose del nodo (il fix proposto sotto, verbatim). Regressioni in
`test/ridley/editor/sdf_attach_pose_test.cljs`: pivot fuori origine (test 1),
traslazione nel frame dell'SDF (test 2), asse dei cp-* dal frame dell'SDF
(test 3, via ancora-sonda), caso degenere posa-all'origine invariato. La
guardia mesh (test 4 suggerito) è coperta dalla suite esistente
(stretch/gizmo/move-to esercitano attach-impl su mesh); il ramo mesh non è
toccato dal fix. Nota: il fix ripara di riflesso anche `move-to` e `:align`
dentro l'attach SDF, che calcolavano il delta dalla posizione del turtle
(l'origine, col bug).

## Repro minimo

```clojure
(f 30)
(register C1 (cone 10 5 2))
(register S2 (attach (scale (sdf-sphere 2) 3 1 1) (cp-f -5) (tv 80)))
```

Atteso: C1 e S2 con origine nello stesso punto, solo orientati diversamente.
Osservato: il `(tv 80)` dentro l'attach ruota S2 attorno all'**origine del
mondo**, con un braccio di leva di 30. Commentando `(f 30)` il
comportamento torna corretto — perché creation-pose e origine coincidono e
il bug diventa invisibile.

## Diagnosi

Le primitive SDF a livello utente passano per `transform-sdf-to-turtle`
(`src/ridley/editor/implicit.cljs`, sezione "Turtle-aware SDF primitive
wrappers"): la geometria viene portata alla posa corrente della tartaruga
globale e la `:creation-pose` viene stampata lì. Fin qui tutto bene.

Il problema è in `sdf-attach-impl` (`src/ridley/editor/impl.cljs`, ~riga
1412), che inizializza la tartaruga di replay così:

```clojure
(loop [state (-> (turtle/make-turtle)
                 (assoc :material-h-local [1 0 0] :material-u-local [0 1 0]))
       ...]
```

`make-turtle` nudo: posizione all'origine, frame di default. La
`:creation-pose` dell'SDF viene ignorata. Tutti i comandi rotazionali
(`tv`/`th`/`tr`, e i loro `cp-*`) usano `(:position state)` come pivot →
pivot all'origine invece che sull'SDF.

Il ramo mesh fa la cosa giusta: `mesh-attach-impl` chiama
`(turtle/attach-move mesh)`, che (docstring di `attach` in
`src/ridley/turtle/core.cljs`, ~riga 2681) "Moves turtle to the mesh's
creation position, adopts its heading and up vectors". È esattamente la
simmetria mesh/SDF che `scale.md` dichiara intenzionale — l'attach SDF non
la rispetta.

**Il bug non è solo di posizione.** Il frame di partenza è quello di
default, quindi se la tartaruga globale era RUOTATA prima di creare l'SDF
(es. un `th 90`), dentro l'attach anche le direzioni di `f`/`cp-f` e gli
assi di `tv`/`th`/`tr` sono riferiti agli assi mondo invece che al frame
dell'SDF. Un test che copra solo la traslazione non basta.

## Fix proposto

Inizializzare il loop dalla creation-pose del nodo, speculare alla mesh:

```clojure
(loop [state (let [pose (or (:creation-pose sdf-node)
                            sdf/default-creation-pose)]
               (-> (turtle/make-turtle)
                   (assoc :position (:position pose)
                          :heading  (:heading pose)
                          :up       (:up pose))
                   (assoc :material-h-local [1 0 0] :material-u-local [0 1 0])))
       sdf sdf-node
       remaining (turtle/path-micro-commands path)]
  ...)
```

Da verificare in coda: `expose-material-frame` riceve lo state finale — con
la nuova posa iniziale il frame materiale esposto va controllato, non dato
per buono.

## Test di regressione suggeriti

1. Il repro sopra: con e senza `(f 30)`, il risultato deve coincidere a
   meno della traslazione della posa di creazione (niente leva).
2. Variante con rotazione: `(th 90)` prima di creare l'SDF, poi attach con
   `(f 10)` — la traslazione deve seguire l'heading dell'SDF, non l'asse
   mondo.
3. Un caso `cp-tv`/`cp-th` fuori origine (i cp-* rotazionali usano lo
   stesso pivot).
4. Guardia di non-regressione mesh: stesso path su `cone`/`box` mesh, il
   comportamento attuale non deve cambiare.

## Nota collaterale (stesso giro di verifiche, 2026-08-25)

`sdf-blend` e `sdf-blend-difference` validano i nodi (`check-sdf-nodes!`)
ma non `k`: un `k` mancante arriva al backend come `null` e riemerge come
`JSON parse error: invalid type: null, expected f64` — criptico. Due righe
di validazione ("k must be a number") in entrambe chiuderebbero il punto.

**CHIUSA 2026-08-25** (`08b6a74`, poi estesa in `d4e9734` e nel commit di
questo fix): `check-sdf-number!` copre blend/blend-difference, le operazioni
unarie (shell/offset/morph), i transform (move/rotate/scale e varianti
keeping-creation-pose) e tutti i costruttori di primitive (sphere, box, cyl,
cone, rounded-box, torus, TPMS). Respinge anche NaN/Infinity, che JSON
serializza come `null`.
