# Brief: mesh-split ad albero + emit nudo per edit-mesh-split

## Contesto

Decisione del 2026-07-15 (Vincenzo). Principio guida: **round-trip totale** — il codice emesso da `edit-mesh-split` deve poter essere ri-editato riaggiungendo `edit-` davanti a `mesh-split`, in ogni caso, non solo in quello lineare. La let-chain emessa oggi (`mesh_split_tree.cljs:452-482`) lo impedisce, e i nomi intermedi (`piece-N`) sono un artefatto della let, non della struttura: l'albero dei tagli si esprime tutto nei parametri di `mesh-split`.

Fatti verificati sul sorgente prima di questo brief:

- Il log dell'editor ha tre soli gesti: `:cut`, `:separate`, `:accept` (`mesh_split_tree.cljs:32-34`). Il **mirror non è un'operazione**: è un flag `:mirror?` su un gesto `:cut` (`:147-148`) che in emissione diventa il commento `;; piano di simmetria`. I tagli di simmetria sono normali tagli di piano — esprimibili nella chiamata nuda senza notazione dedicata.
- `mesh-split` ritorna il composito `{:behind m :ahead {…}}` (`editor/implicit.cljs:872-919`); oggi solo `:ahead` può essere un nodo.
- `split-parts` (`manifold/core.cljs:675`) **già ricorre su entrambi i rami** (`:686`): la ramificazione su `:behind` non richiede modifiche.
- `leaves-of` in `mesh-board` (`editor/mesh_board.cljs:42-70`) su un composito grezzo multi-taglio passa la mappa annidata di `:ahead` come "mesh" agli scaffold — rottura oscura, non un errore leggibile.
- La separazione è **senza parametri**: `mesh-components` non ha pose da scegliere, quindi il gesto SEPARATE non ha nulla di intrinsecamente interattivo. E il round-trip richiede indirizzamento stabile: i mark sono nomi nel path, le componenti no (ordine geometrico → un indice posizionale nel codice emesso è fragile per costruzione).

## Lavoro richiesto

### Parte 1 — mesh-split: spec ad albero

Il terzo argomento si generalizza. Grammatica ricorsiva:

```
spec     := [mark …]                     ; selezione lineare (forma attuale, invariata)
          | {mark sub-spec, …}           ; ramificazione
sub-spec := nil                          ; il mark taglia e basta
          | path                         ; il pezzo staccato al mark viene ritagliato
                                         ;   linearmente a tutti i mark del path
          | [path spec]                  ; forma piena ricorsiva (rami nei rami)
```

Esempio (il caso che oggi richiede la let):

```clojure
(mesh-split mount
  (path … (mark :cut-1) … (mark :cut-2))
  {:cut-1 (path … (mark :cut-1-1))   ; il pezzo staccato a :cut-1 viene ritagliato
   :cut-2 nil})                       ; :cut-2 taglia e basta
```

Regole:

- **Ordine dei tagli**: lo dà il path (l'ordine dei mark), mai l'ordine delle chiavi della mappa (non affidabile).
- **Frame dei sotto-path**: risolti dalla **cut-pose** del mark, non dalla pose corrente della turtle — è il frame naturale (accertamento grande, Q4).
- **Ritorno**: il composito generalizza — anche `:behind` può essere un nodo `{:behind … :ahead …}`. `split-parts` funziona invariato (ricorre già su entrambi i rami); ordine foglie = DFS, `:behind` prima di `:ahead`.

### Parte 2 — emit sempre nudo + re-entry

- `emit` produce **sempre** una singola chiamata nuda che codifica l'intero albero di tagli nella spec. La let-chain muore (con lei `nested-destructure`, il body-mappa e i nomi `piece-N` nell'emissione; `name-map`/`piece-name` restano per le etichette di scena nel pannello).
- I commenti `;; :cut-N: piano di simmetria` restano, attaccati alla riga del loro mark (invariante round-trip: i commenti non toccano l'eval; attenzione al newline finale già documentato in `run-binding:439-444`).
- **Re-entry**: `build-reentry-tree` deve saper rileggere la spec annidata e ricostruire l'albero interno. È la parte grossa del lavoro. Il `:mirror?` non si ricostruisce al re-entry (comportamento già documentato a `:440`, invariato).

### Parte 3 — :separate esce dall'editor

- Rimuovere il gesto SEPARATE da `edit-mesh-split` (gesture handler in `edit_mesh_split.cljs:861` circa, `:separate` in `mesh_split_tree.cljs`, `separation-binding`, e la gestione delle separazioni in `name-map`/`runs`).
- `mesh-components`/`split-parts`/`split-tree` restano **DSL normale** che l'utente usa a mano sul risultato della chiamata nuda: `(mesh-components (:behind AA))`. Per rieditare una componente: nuova sessione, `(edit-mesh-split (nth (mesh-components …) 0))` — componibile senza notazioni nuove.
- `:accept` e l'assist di simmetria (Part 4: candidati, badge, flag `:mirror?`) **restano invariati**.

### Parte 4 — split-tree + guard in leaves-of

Nuova funzione accanto a `split-parts` in `manifold/core.cljs`, esposta nei bindings:

```clojure
(defn split-tree
  "Composito di mesh-split → mappa {:piece-1 m1 :piece-2 m2 …}, foglie in ordine DFS."
  [result]
  (into {}
        (map-indexed (fn [i m] [(keyword (str "piece-" (inc i))) m])
                     (split-parts result))))
```

È il ponte verso il tree-value di `mesh-board`: `(mesh-board (split-tree AA))` funziona con `:only`. Con le separazioni fuori dall'editor il composito contiene solo `:behind`/`:ahead` — nessun caso `:components` da gestire.

Guard: `leaves-of` riconosce il composito grezzo (mappa con chiavi `:behind`/`:ahead`) e lancia un errore leggibile che indica `split-tree` — mai il fallimento oscuro ("Never a silent no-op"). Stessa verifica per `attach` sul composito grezzo.

## Documentazione da aggiornare

- `docs/manual/reference/en/mesh-split.md` — la spec ad albero (grammatica, ordine, cut-pose).
- `docs/manual/reference/en/edit-mesh-split.md` — la forma emessa cambia; il round-trip come contratto.
- `docs/manual/reference/en/mesh-board.md`, `attach.md`, `split-parts.md` — riferimenti a `piece-1/2` e al body-mappa emesso; rimando a `split-tree`.
- Nuova entry `split-tree`: `docs/manual/reference/en/split-tree.md` + `manual/reference_index.cljs`.
- `docs/Spec.md` — sezione mesh-split/emit.
- I brief in `dev-docs/` (`brief-mesh-board.md`, `brief-edit-mesh-split.md` + addendum) restano come storia: nota in testa che rimanda a questo brief per la forma emessa attuale.

## Verifica

- **Round-trip**: emit nudo → `edit-` davanti → sessione riaperta → emit identico. Anche con alberi ramificati e con commenti di simmetria.
- Spec ramificata valuta: conteggio pezzi giusto con 1, 2, 3 tagli e con rami su `:behind`; `split-parts`/`split-tree` sul composito ramificato in ordine DFS.
- `(mesh-board (split-tree AA))` con `:only` sui nomi `:piece-N`; composito grezzo a `mesh-board`/`attach` → errore leggibile che nomina `split-tree`.
- Gesto SEPARATE assente; flusso manuale `mesh-components` + re-edit di una componente funziona.
- Il commento `;; piano di simmetria` sopravvive a emit e re-entry senza rompere la lettura del sorgente (delimitatori).
