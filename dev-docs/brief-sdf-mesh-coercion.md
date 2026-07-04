# Brief: coercizione automatica SDF→mesh nelle operazioni mesh-only

## Contesto

Le operazioni che consumano mesh non hanno un comportamento uniforme quando ricevono un nodo SDF (mappa con `:op` stringa, riconoscibile con `sdf/sdf-node?`). Alcune coercizzano correttamente, altre falliscono in modo criptico, altre — le peggiori — restituiscono un risultato silenziosamente sbagliato senza alcun errore.

Il caso che ha fatto emergere il problema: `(register X (warp UnSdf volume (attract 0.3)))`. `warp` riceve la mappa SDF, `(:vertices mesh)` è nil, itera su zero vertici e restituisce la mappa SDF invariata (con un `:vertices []` appiccicato). `register` vede `:op` stringa, chiama `sdf-ensure-mesh` e mesha **l'SDF originale**: il warp è un no-op perfetto, invisibile all'utente.

Censimento completo (fatto leggendo i sorgenti, non le spec):

**Già SDF-aware — non toccare:**
- `register` (`macros.cljs` ~1123): coercizza con `sdf-ensure-mesh`.
- `mesh-union` / `mesh-difference` / `mesh-intersection` (`manifold/core.cljs`, `coerce-to-meshes` ~320): coercizzano, con warning quando tutti gli operandi sono SDF.
- `translate` / `scale` / `rotate` (`editor/transforms.cljs`): dispatch su `sdf-node?`, restano in SDF space. Comportamento corretto e voluto.
- `attach` / `attach!` (`editor/impl.cljs` ~1145+): supporto SDF esplicito con validazione dei comandi di path.

**Esposti — risultato silenziosamente sbagliato:**
1. `warp` (argomento mesh) — `geometry/warp.cljs`, binding `'warp-impl` in `bindings.cljs` ~606. No-op totale come descritto sopra.
2. `solidify` — `manifold/core.cljs` ~274. Il check `(and (:vertices m) (:faces m))` fallisce e il ramo else restituisce l'input invariato. Stesso pattern del warp: a valle, register mesha l'SDF originale.
3. `mesh-hull` — `manifold/core.cljs` ~485. `mesh->manifold` restituisce nil su una mappa senza vertici (~154) e il `keep` scarta silenziosamente l'operando SDF: l'hull viene calcolato sugli operandi rimanenti.
4. `concat-meshes` — `manifold/core.cljs` ~667. L'operando SDF contribuisce vertici e facce nil e sparisce dal risultato.

**Esposti — errore criptico:**
5. `warp` (argomento volume) — un volume SDF fa esplodere `compute-volume-bounds` con `(apply min [])` su lista vuota.
6. `transform` — binding in `bindings.cljs` ~382 su `turtle/transform-mesh` (`turtle/core.cljs` ~2385), che usa l'attach di `turtle/core`, layer privo di supporto SDF.
7. `export` diretto di un nodo SDF — `bindings.cljs` ~447, cade nel ramo `:else` con messaggio generico.

**Footgun correlato:**
8. `sdf->mesh` (binding ~643 su `sdf/materialize`): l'arità a un argomento è `(materialize node nil 15)` — 15 voxel per unità fissi, senza il budget di `resolution-for-bounds` né il warning >5e8 voxel che `ensure-mesh` applica. Su un oggetto di ~100 unità per asse produce griglie da miliardi di voxel che uccidono il server Rust; la XHR sincrona fallisce e `invoke-sync` riporta il fuorviante "Couldn't reach the geometry server". `register` dello stesso SDF funziona perché passa da `ensure-mesh`.

Decisione di design: la coercizione avviene nel layer bindings/manifold, **non** dentro `geometry/warp.cljs`. Alternativa rigettata: coercizzare dentro `geometry.warp` richiederebbe una dipendenza da `sdf.core` (transport XHR verso il server desktop) in un namespace della libreria core pura; il precedente `coerce-to-meshes` in `manifold/core.cljs` mostra che il posto giusto è il layer che già conosce entrambi i mondi. Seconda alternativa rigettata: rendere `transform` SDF-nativo via `sdf-move`/`sdf-rotate` come attach — rimandato finché non emerge un caso d'uso concreto (nessuna astrazione prima di più casi concreti); per ora `transform` su SDF significa "mesha e trasforma la mesh", documentato.

La risoluzione usata dalla coercizione è sempre quella di `ensure-mesh` (auto-bounds + `*sdf-resolution*` con budget), mai il default a 15 vpu di `materialize`.

## Lavoro richiesto

Commit piccoli e verificati, ognuno verde. Rete prima del codice: i test elencati in Verifica per la parte pura vanno scritti prima dei fix corrispondenti.

1. **`warp` coercizza entrambi gli argomenti.** In `bindings.cljs`, sostituire il binding diretto `'warp-impl warp/warp` con un wrapper che applica `sdf/ensure-mesh` sia al primo argomento (mesh) sia al secondo (volume) prima di delegare a `warp/warp`. Nota per il volume: la mesh materializzata non ha `:primitive`, quindi `compute-volume-bounds` ricade sul box AABB — accettabile, va documentato nel docstring del wrapper.

2. **`solidify` coercizza.** In `manifold/core.cljs`, in testa a `solidify`, coercizzare l'input con `sdf/ensure-mesh` (il namespace già require `ridley.sdf.core`).

3. **`hull` e `concat-meshes` coercizzano.** Riusare `coerce-to-meshes` in entrambe, dopo la normalizzazione vettore/varargs. Attenzione al warning "tutti SDF": il testo attuale suggerisce `sdf-<op>` che per hull e concat non esiste; rendere il suggerimento condizionale (solo per union/difference/intersection) o parametrico.

4. **`transform` coercizza.** In `bindings.cljs` ~382, wrappare `turtle/transform-mesh` con coercizione dell'argomento mesh; se è un vettore, coercizzare ogni elemento.

5. **`export` accetta un SDF.** Nel `cond` di `export-smart` (~447), aggiungere un ramo `sdf/sdf-node?` prima del `:else` che coercizza e esporta come "model".

6. **`sdf->mesh` a un argomento passa dal budget.** Ribindare `'sdf->mesh` a una fn che con un solo argomento delega a `sdf/ensure-mesh` e con due o tre argomenti delega a `sdf/materialize` invariato (chi passa bounds e voxel-per-unità espliciti sa cosa sta facendo). Non toccare `materialize` stesso: il suo contratto bounds+vpu resta per uso interno ed esperto.

## Verifica

Non esistono test SDF nel repo (richiedono il server Rust, assente in CI). Strategia su due livelli:

**Livello puro (automatizzabile, va in test/):** la logica di routing è testabile senza server. Per i punti 2 e 3, costruire una finta mappa SDF `{:op "sphere" :r 5.0}` e verificare che `solidify`, `hull` e `concat-meshes` la instradino verso la coercizione invece di produrre il comportamento attuale (no-op / operando scartato). Se serve, rendere iniettabile il materializzatore (dynamic var o parametro privato) così il test può sostituirlo con una fn che restituisce una mesh nota; in alternativa, testare almeno che l'input SDF non venga più restituito invariato / scartato. Aggiungere anche i test di non-regressione: le stesse operazioni su mesh normali producono risultati identici a prima.

**Livello desktop (manuale, con server attivo):** eseguire nell'app desktop:

```clojure
(sdf-resolution! 120)
(def T (sdf-shell (sdf-blend (sdf-sphere 40) (attach (sdf-sphere 8) (f 40)) 2) 2))
(register Plain T)
(register Warped (warp T (attach (box 80) (f -10)) (twist 90 :z) (attract 0.3)))
```

Verificare che: Warped sia visibilmente deformato rispetto a Plain; `(solidify T)` restituisca una mesh; `(mesh-hull T (box 20))` includa il contributo dell'SDF; `(concat-meshes T (box 20))` contenga i vertici di entrambi; `(transform T (path (f 30)))` restituisca una mesh traslata; `(export T)` scarichi uno stl; `(sdf->mesh T)` completi senza uccidere il server (prima del fix, con bounds ~100 unità, muore).

## Fuori scope

- L'errore `"twist: specify axis for box/sphere volumes"`: è un errore chiaro e corretto, non va toccato.
- Rendere `transform` SDF-nativo (via sdf-move/sdf-rotate): rimandato, vedi Contesto.
- Migliorare la diagnosi di `invoke-sync` quando il server muore sotto una richiesta enorme (distinguere "mai stato su" da "morto durante la richiesta"): utile ma separato.
- Il messaggio di warning di `coerce-to-meshes` oltre la modifica minima descritta al punto 3.
- Qualunque cambiamento a `materialize`, `ensure-mesh`, `resolution-for-bounds`.
