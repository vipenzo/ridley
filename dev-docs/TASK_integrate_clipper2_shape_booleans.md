# Task: Integrazione Clipper2 per operazioni booleane 2D sulle shape

## Obiettivo

Aggiungere operazioni booleane 2D (union, difference, intersection, offset) sulle shape in Ridley, usando la libreria **clipper2-js** (port TypeScript puro di Clipper2, nessun WASM). Questo permette di creare shape con buchi (es. rondelle, profili cavi) direttamente nel DSL, prima dell'estrusione.

## Contesto importante

### Cosa esiste già

Il codebase ha **già** molto del lavoro preparatorio:

1. **Il tipo shape supporta già `:holes`** — `make-shape` in `turtle/shape.cljs` accetta l'opzione `:holes` e le funzioni di trasformazione (`translate-shape`, `scale-shape`, `reverse-shape`, `transform-shape-with-holes-to-plane`) gestiscono già i holes.

2. **earcut è già importato** — `turtle/extrusion.cljs` importa `["earcut" :default earcut]`, che supporta nativamente la triangolazione con holes.

3. **Le trasformazioni shape gestiscono holes** — `scale-shape`, `translate-shape`, `reverse-shape` applicano già le trasformazioni anche ai holes.

### Cosa manca

1. Le funzioni DSL per creare shape con holes (`shape-difference`, `shape-union`, etc.)
2. La libreria Clipper2 come dipendenza
3. L'aggiornamento dell'estrusione per generare ring laterali anche per i contorni dei holes (i "tunnel" interni)
4. L'aggiornamento dei cap nell'estrusione per triangolare con earcut usando gli holes

## Piano di implementazione — 3 fasi

---

### Fase 1: clipper2-js + wrapper ClojureScript

#### 1.1 Installare clipper2-js

```bash
npm install clipper2-js
```

Nota: NON usare clipper2-wasm. Il port puro JS è sufficiente per le shape 2D con poche centinaia di punti, e non richiede caricamento WASM asincrono.

Riferimento NPM: https://www.npmjs.com/search?q=keywords:clipper (cercare il package "clipper2-js" o "clipper2-ts" di IRobot1 — sono port nativi TypeScript). Se non è disponibile come npm package diretto, un'alternativa è usare `js-angusj-clipper` (Clipper1 WASM, più maturo ma richiede coordinate intere con scaling ×1000).

**IMPORTANTE**: Prima di procedere, verifica quale package è effettivamente disponibile e funzionante con `npm install`. Le opzioni in ordine di preferenza sono:
1. Un port puro JS/TS di Clipper2 (senza WASM)
2. `clipper2-wasm` da CDN (come già fai con Manifold) — pattern noto nel progetto
3. `js-angusj-clipper` (Clipper1, richiede scaling intero)

#### 1.2 Creare `src/ridley/clipper/core.cljs`

Wrapper ClojureScript attorno a Clipper2. Responsabilità:
- Conversione format: shape Ridley `[[x y] ...]` ↔ format Clipper
- Operazioni: union, difference, intersection, XOR, offset
- Gestione dei risultati: Clipper restituisce `{outer: [...], holes: [...]}`, tradurre in shape Ridley

```clojure
(ns ridley.clipper.core
  "Wrapper per Clipper2: operazioni booleane e offset su poligoni 2D."
  (:require ["clipper2-js" :as clipper2]))  ;; adattare l'import al package scelto

;; --- Conversione format ---

(defn- shape->clipper-paths
  "Converti una shape Ridley in paths Clipper.
   Ritorna {:subject [outer-path] :clips [hole-paths...]}"
  [shape]
  ...)

(defn- clipper-result->shape
  "Converti il risultato Clipper in una shape Ridley.
   Clipper ritorna una lista di path: il primo è outer (CCW), gli altri sono holes (CW)."
  [paths]
  ...)

;; --- Operazioni booleane 2D ---

(defn shape-union [shape-a shape-b] ...)
(defn shape-difference [shape-a shape-b] ...)
(defn shape-intersection [shape-a shape-b] ...)
(defn shape-xor [shape-a shape-b] ...)

;; --- Offset ---

(defn shape-offset
  "Espandi (positivo) o contrai (negativo) una shape.
   join-type: :round, :square, :miter (default :round)"
  [shape distance & {:keys [join-type] :or {join-type :round}}]
  ...)
```

**Nota sul winding**: Clipper2 usa floating point nativamente (a differenza di Clipper1 che usa interi). Assicurarsi che l'outer path sia CCW e gli holes CW, coerente con la convenzione già usata da `make-shape` in Ridley.

#### 1.3 Esporre nel DSL (editor/repl.cljs)

Aggiungere al contesto SCI le nuove funzioni:

```clojure
'shape-union       ridley.clipper.core/shape-union
'shape-difference  ridley.clipper.core/shape-difference
'shape-intersection ridley.clipper.core/shape-intersection
'shape-xor         ridley.clipper.core/shape-xor
'shape-offset      ridley.clipper.core/shape-offset
```

---

### Fase 2: Estrusione con holes

Questa è la parte più delicata. Oggi l'estrusione genera:
- Ring laterali dal contorno outer
- Cap (facce di chiusura) triangolate dal contorno outer

Con i holes bisogna aggiungere:
- Ring laterali per ogni hole (i "tunnel" interni)
- Cap triangolate con earcut passando outer + holes

#### 2.1 Aggiornare la generazione dei ring

File: `turtle/extrusion.cljs`

Quando una shape ha `:holes`, per ogni posizione lungo il path bisogna generare:
1. Il ring outer (come oggi)
2. Un ring per ogni hole

I ring dei holes devono avere **winding opposto** rispetto all'outer per generare normali corrette (rivolte verso l'interno del tunnel).

Pseudocodice:
```
Per ogni step dell'estrusione:
  outer-ring = transform-shape-to-plane(shape.points, turtle-pos, heading, up)
  hole-rings = per ogni hole in shape.holes:
                 transform-points-to-plane(hole, turtle-pos, heading, up)
```

**NOTA**: `transform-shape-with-holes-to-plane` in `turtle/shape.cljs` fa già esattamente questo! Ritorna `{:outer <3D-points> :holes [<3D-points> ...]}`.

#### 2.2 Aggiornare la generazione dei cap

La triangolazione dei cap deve usare earcut con gli holes. earcut è già importato in extrusion.cljs.

Earcut signature: `earcut(vertices_flat, hole_indices, dimensions)`

Esempio per un cerchio con un buco circolare:
```javascript
// outer: 16 punti, hole: 8 punti
// vertices_flat = [ox0,oy0, ox1,oy1, ..., hx0,hy0, hx1,hy1, ...]
// hole_indices = [16]  (l'hole inizia all'indice 16)
// dimensions = 2
earcut(vertices_flat, [16], 2)
```

La triangolazione va fatta in coordinate 2D locali (sul piano del cap), non in 3D. I triangoli risultanti vengono poi mappati agli indici 3D dei vertici nella mesh.

#### 2.3 Generare le facce laterali dei tunnel

Per ogni hole, le facce laterali collegano ring consecutivi (come per l'outer), ma con winding invertito così le normali puntano verso l'interno del tunnel:

```
outer: facce con normali verso l'esterno (CCW visto da fuori)
holes: facce con normali verso l'esterno del tunnel = verso l'interno del solido
       (CW visto da fuori, ma le normali puntano nel tunnel)
```

Concettualmente è come estrudere due shape concentriche e poi unire la mesh.

#### 2.4 Impatto su loft, extrude-closed, sweep

- **loft**: la transform function riceve la shape e la modifica. Se la shape ha holes, la funzione deve trasformare anche i holes. Le trasformazioni in `turtle/transform.cljs` (`scale-shape`, `rotate-shape`) gestiscono già i holes. Verificare che `morph`/`lerp-shape` funzionino con holes (probabilmente servono aggiornamenti per interpolare anche i contorni dei holes).

- **extrude-closed**: stessa logica dell'extrude, ma senza cap. Il ring dell'outer e dei holes wrappano dall'ultimo al primo.

- **sweep**: usa la stessa infrastruttura di extrude, dovrebbe funzionare automaticamente.

---

### Fase 3: Test e raffinamenti

#### 3.1 Test fondamentali

```clojure
;; Rondella
(register washer
  (extrude (shape-difference (circle 20) (circle 10)) (f 5)))

;; Profilo a C
(register c-profile
  (extrude (shape-difference (rect 30 20) (rect 20 10)) (f 50)))

;; Tubo piegato con sezione cava
(register hollow-tube
  (extrude (shape-difference (circle 15) (circle 12))
    (f 30) (th 90) (f 30)))

;; Offset: shell
(register thick-star
  (extrude (shape-offset (star 5 20 8) 2) (f 10)))

;; Verifica manifold
(manifold? washer)  ;; deve essere true
```

#### 3.2 Verificare che non si rompa niente

- Shape senza holes devono funzionare esattamente come prima
- Tutti gli esempi esistenti nel progetto devono continuare a funzionare
- I test esistenti devono passare

#### 3.3 Loft con holes

```clojure
;; Torsione di un profilo cavo
(register twisted-tube
  (loft (shape-difference (circle 15) (circle 12))
    #(rotate-shape %1 (* %2 90))
    (f 30)))
```

Verificare che `rotate-shape` (in `turtle/transform.cljs`) applichi la rotazione anche ai holes — **già implementato** nel codice attuale.

---

## File impattati

| File | Tipo modifica | Note |
|------|--------------|-------|
| `package.json` | Nuova dipendenza | clipper2-js (o alternativa) |
| `src/ridley/clipper/core.cljs` | **NUOVO** | Wrapper Clipper2 |
| `src/ridley/turtle/extrusion.cljs` | Modifica | Cap con holes, ring per holes |
| `src/ridley/turtle/loft.cljs` | Modifica | Verificare/aggiornare per holes |
| `src/ridley/editor/repl.cljs` | Modifica | Esporre funzioni DSL |
| `src/ridley/turtle/transform.cljs` | Verifica | lerp-shape/morph con holes |

## File che NON devono cambiare

| File | Motivo |
|------|--------|
| `turtle/shape.cljs` | Già supporta holes! |
| `geometry/primitives.cljs` | Non usa shape 2D |
| `viewport/core.cljs` | Riceve mesh già pronte |
| `manifold/core.cljs` | Opera su mesh 3D |
| `export/stl.cljs` | Opera su mesh 3D |

## Priorità

1. **Fase 1** — wrapper Clipper + DSL (può essere testato subito con `make-shape` manuale)
2. **Fase 2** — estrusione con holes (il vero valore aggiunto)
3. **Fase 3** — test e edge cases

La Fase 1 è indipendente e testabile: anche senza aggiornare l'estrusione, le funzioni `shape-difference` etc. restituiscono shape valide che possono essere ispezionate. L'estrusione nella Fase 2 è dove le shape con holes diventano mesh 3D.

## Note per Code

- Il codice è ClojureScript, compilato con shadow-cljs
- Le librerie JS si importano con `(:require ["lib-name" :as alias])`
- Il progetto usa Three.js, earcut è già disponibile
- Per caricare da CDN (alternativa a npm): seguire il pattern di `manifold/core.cljs` che carica Manifold WASM via `js/import`
- Il DSL è esposto via SCI in `editor/repl.cljs` — le funzioni vanno aggiunte al contesto SCI
- Testare con `npx shadow-cljs watch app` e verificare nel browser
