# Cap. 6 — chiarimento su `::raw-arrays`

**TL;DR — vale (a), con una postilla.** Tre boolean in fila ricostruiscono
effettivamente tre volte l'oggetto `Manifold` nativo *e* tre volte i
typed-array che gli vengono dati in pasto. Il campo `::raw-arrays` esiste
ma è una cache di **sola uscita** (rendering / export / bbox); il path
inverso `mesh→Manifold` non la consulta mai.

---

## 1. Cosa contiene `::raw-arrays`

Una mappa con tre chiavi, vedi [manifold/core.cljs:146-148](../../src/ridley/manifold/core.cljs#L146-L148):

```clojure
{:vert-props (.slice vert-props)   ;; Float32Array, layout [x,y,z, x,y,z, …]
 :tri-verts  (.slice tri-verts)    ;; Uint32Array,  layout [i,j,k, i,j,k, …]
 :num-prop   3}                    ;; numero di componenti per vertice
```

Niente normali, niente UV, nessun altro buffer: solo posizioni e indici.
Il `.slice()` produce una copia indipendente del buffer Manifold (evita
dangling reference dopo `.delete()` sul Mesh nativo).

## 2. Quando viene popolato

In due punti soltanto:

- [manifold/core.cljs:115-148](../../src/ridley/manifold/core.cljs#L115-L148) — `manifold-mesh->ridley-mesh`,
  cioè dopo *qualunque* operazione Manifold che restituisce una mesh
  (`union`/`difference`/`intersection`/`hull`/`refine`/`smooth`/
  `solidify`/`extrude-cross-section`/`manifold->mesh`).
- [sdf/core.cljs:52-58](../../src/ridley/sdf/core.cljs#L52-L58) — quando una SDF viene meshata via libfive
  (i typed-array arrivano già pronti dal lato Rust/WASM).

I costruttori "puri" CLJS — box-mesh, cyl/cone, loft, sweep,
heightmap-to-mesh, le shape-fn — **non** popolano `::raw-arrays`.
Producono solo `:vertices`/`:faces` come PersistentVector di vector.

## 3. `ridley-mesh->manifold-mesh` riusa `::raw-arrays`?

**No.** Vedi [manifold/core.cljs:84-106](../../src/ridley/manifold/core.cljs#L84-L106): la funzione
prende `:vertices` e `:faces` e ricostruisce *sempre* due nuovi
`Float32Array` e `Uint32Array` con due `reduce` su PersistentVector.
Non legge mai `::raw-arrays`, neanche se è popolato.

## 4. `mesh->manifold` ha un costo dipendente dall'input?

**No, è costante (rispetto alla forma di ingresso).** Vedi
[manifold/core.cljs:154-167](../../src/ridley/manifold/core.cljs#L154-L167): la funzione si limita a chiamare
`ridley-mesh->manifold-mesh` (di nuovo, ricostruzione completa) e poi
`new Manifold(mesh-data)`. Il costruttore C++ ricopia comunque i dati nel
suo formato interno (BVH + halfedge), quindi anche se passassimo i
typed-array già pronti risparmieremmo solo lo step CLJS→TypedArray, non
la costruzione del Manifold vero e proprio.

## 5. Ciclo di vita attraverso un boolean

Sequenza di un singolo `(mesh-union A B)`:

1. `A` ha `::raw-arrays` (perché veniva da una CSG precedente). **Ignorato.**
2. `ridley-mesh->manifold-mesh A` → costruisce `Float32Array`+`Uint32Array`
   da `(:vertices A)`/`(:faces A)`.
3. `new Manifold(meshA)` → copia in struttura C++.
4. Idem per `B`.
5. `.add(ma, mb)` → C++ esegue il boolean.
6. `.getMesh()` + `manifold-mesh->ridley-mesh` → ricostruisce
   PersistentVector di vertici e facce, e *contestualmente* ripopola
   `::raw-arrays` con un `.slice()` dei buffer freschi.
7. `.delete()` su `ma`, `mb`, raw-result.

Quindi tre boolean in fila pagano:

- 3× la conversione `PersistentVector → typed-array` per la operanda
  sinistra (anche se è il risultato di quello prima e ha già i typed-array
  buoni in `::raw-arrays`).
- 3× `new Manifold(meshGL)` lato C++.
- 3× il rebuild dei PersistentVector + `.slice()` dei typed-array in uscita.

C'è anche un campo `::_discarded-cache` che le funzioni boolean
assegnano sull'output (vedi [manifold/core.cljs:258](../../src/ridley/manifold/core.cljs#L258),
[302](../../src/ridley/manifold/core.cljs#L302), [364](../../src/ridley/manifold/core.cljs#L364),
[402](../../src/ridley/manifold/core.cljs#L402), [457](../../src/ridley/manifold/core.cljs#L457)),
ma il nome dice tutto: il `Manifold` lì dentro è già stato `.delete()`-ato dalla
chiamata stessa, quindi è solo un cadavere. Niente lo legge mai. Stesso
discorso per `::manifold-cache`: solo `dissoc`, mai `assoc` — vestigia di
un tentativo di caching abbandonato.

## 6. Operazioni che invalidano `::raw-arrays`

Queste funzioni modificano `:vertices`/`:faces` (o ne calcolano di nuovi)
e fanno `(dissoc … ::raw-arrays ::manifold-cache)` correttamente:

- `turtle/attachment.cljs` — `translate-mesh`, `rotate-mesh`, `scale-mesh`,
  `rotate-mesh-around-point`, `replace-mesh-in-state`, `transform-mesh-rigid`
  (linee 41, 58, 77, 91, 129, 677).
- `geometry/warp.cljs:418`.
- `editor/impl.cljs:378-379` (transform interattivo).
- `editor/text_ops.cljs:113`.
- `manifold/core.cljs:758` — dentro `slice-at-plane`, sulla mesh
  trasformata localmente.

Non ho trovato funzioni che mutino vertici/facce *dimenticando* il
dissoc, quindi `::raw-arrays` non dovrebbe risultare stale in pratica.
(Uno scan rapido sui produttori di mesh con `(assoc … :vertices …)` non
trova path che bypassino la convenzione.)

---

## Cosa scriverei nel cap. 6

> Tre boolean in fila ricostruiscono tre volte l'oggetto `Manifold`
> nativo: ogni `mesh-union`/`mesh-difference` parte sempre da
> `:vertices`/`:faces` (PersistentVector di vector) e li ricopia in
> `Float32Array`+`Uint32Array` prima di chiamare il costruttore C++.
> Il campo `::raw-arrays` che le CSG depositano sull'output è solo una
> cache di rendering — lo leggono il viewport Three.js, l'export
> STL/3MF e il bbox sampler — *non* il path inverso verso Manifold,
> che non lo consulta mai.

Cioè: dichiara (a) come stato dell'arte, e usa la postilla per
prevenire l'obiezione "ma allora a cosa serve `::raw-arrays`?". È
l'opzione più onesta: la cache esiste e ha valore, semplicemente non
chiude il loop sulla parte cara (il `new Manifold` C++ + il rebuild dei
typed-array di input).
