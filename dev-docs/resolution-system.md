# Sistema di risoluzione — sintesi unificata

> Documento di lavoro per riallineare il manuale dopo il refactor di unificazione delle "resolution" (2026-05-22). Contiene la versione canonica delle tabelle e degli esempi.

## TL;DR per il lettore del manuale

In Ridley c'è **un solo concetto di "risoluzione delle curve"**: `(resolution :n N)`. Tutti i default di tutte le operazioni che producono curve o sweep ne discendono. Se non hai bisogno di gestione fine, tocchi solo questa.

Il sistema SDF (`*sdf-resolution*`) è separato: ragiona in **voxel per unità di spazio**, non in segmenti per curva. È un mondo a parte, intenzionalmente.

---

## 1. La singola variabile globale: `(resolution …)`

Imposta la granularità di tutte le curve nella scena:

```clojure
(resolution :n 64)    ; fixed segment count per full circle (default)
(resolution :a 5)     ; max 5° per segment
(resolution :s 0.5)   ; max 0.5 units per segment
```

| Modalità | Significato | Quando usarla |
|---|---|---|
| `:n N` | N segmenti per cerchio completo, indipendente dalla taglia | Quando vuoi un look uniforme |
| `:a A` | massimo A° per segmento — i cerchi grandi sono più lisci, quelli piccoli più poveri | Quando vuoi smoothness percepibile costante |
| `:s S` | massimo S unità per segmento — densità di triangoli costante | Quando vuoi densità di mesh costante per print, lavorazione, ecc. |

**Default**: `:n 64`. Configurabile in Settings (in arrivo). Override locale con il comando `resolution` dentro a uno script o un `turtle` scope.

---

## 2. Cosa è influenzato da `(resolution …)`

Tutti i numeri sotto sono calcolati a partire dalla risoluzione corrente con un **ratio fisso**. Il default `:n 64` è la baseline; ogni altra modalità viene tradotta in un "equivalente :n" (vedi sezione 4).

### 2a. Operazioni che già ne consultano la versione locale (turtle state)

| Operazione | Ratio | Default effettivo @ `:n 64` |
|---|---|---|
| `circle r` (no segs explicit) | 1× | 64 |
| `sphere r` (no segs explicit) | 1× | 64 segs, 32 rings |
| `cyl r h` (no segs explicit) | 1× | 64 |
| `cone r1 r2 h` (no segs explicit) | 1× | 64 |
| `arc-h r angle` | 1× | calcolato dall'angolo |
| `arc-v r angle` | 1× | calcolato dall'angolo |
| `bezier-to …` | 1× | calcolato dalla lunghezza |
| `bezier-to-anchor …` | 1× | calcolato dalla lunghezza |
| `joint-mode :round` corner | 1× | calcolato dall'angolo del corner |
| `revolve` (DSL macro) | 1× | calcolato dall'angolo |

### 2b. Operazioni che ora ereditano dal default globale (nuove dopo il refactor)

| Operazione | Ratio | Default effettivo @ `:n 64` | Note |
|---|---|---|---|
| `loft` (step count longitudinale) | **1×** | **64** | prima era 16 fisso |
| `loft-from-path` | 1× | 64 | prima 16 |
| `pure-loft-path`, `pure-loft-two-shapes`, `pure-loft-shape-fn` | 1× | 64 | prima 16 |
| `revolve` (low-level in `geometry/operations`) | 1× | 64 | prima 24 |
| `fillet-shape :segments` | **0.5×** | **32** | prima 8 fisso |
| Voronoi cap `:resolution` | 0.5× | 32 | prima 16 fisso |
| `mesh-to-heightmap :resolution` | **2×** | **128** | prima 128 fisso → identico al default |
| `weave-heightmap :resolution` | 2× | 128 | prima 128 fisso → identico al default |

---

## 3. Cosa NON è influenzato

| Knob | Default | Perché separato |
|---|---|---|
| `chamfer-shape` | nessun knob | Lo smusso è un singolo segmento per corner, niente granularità da tunare |
| `*sdf-resolution*` (SDF voxel) | 15 voxels/unit | Concettualmente diverso: misura voxel per unità di spazio, non segmenti per curva |
| `sphere`, `cyl`, `cone` quando dai segments espliciti | quello che dai | Override esplicito, dominante |
| Override `:steps N` su arc/bezier | quello che dai | Override esplicito, dominante |

### Perché SDF è separato

`(resolution :n 64)` dice "64 segmenti per cerchio". `*sdf-resolution* 15` dice "15 voxel per ogni unità di spazio". Sono unità diverse e applicarle uniformemente avrebbe poco senso:

- Un cerchio di raggio 100 con `:n 64` ha 64 segmenti (densità diversa per cerchi piccoli vs grandi).
- Una mesh SDF di 100×100×100 a `:n 64` voxel/unità sarebbe 64³ = 262144 voxel per unità di volume → 262 miliardi totali. Esplosivo.

SDF lavora in modo diverso ed è giusto che abbia il suo cursore. Quando vuoi mesh SDF più fine usi `(materialize sdf bounds N)` o `*sdf-resolution*`.

---

## 4. Conversione `:a` / `:s` → "equivalente :n"

Per le operazioni dove non c'è una curva specifica da misurare (loft step count, fillet, voronoi cap), la modalità `:a` o `:s` viene convertita in un equivalente `:n` usando un **raggio di riferimento R = 80**.

```
:n N  →  N
:a A  →  max(8, ceil(360 / A))
:s S  →  max(8, ceil(2π·80 / S))
```

Tabella di esempio (col ratio applicato per i casi outlier):

| Settings | Equivalente :n | loft (×1) | fillet (×½) | heightmap (×2) |
|---|---:|---:|---:|---:|
| `:n 32` | 32 | 32 | 16 | 64 |
| `:n 64` (default) | 64 | 64 | 32 | 128 |
| `:n 128` | 128 | 128 | 64 | 256 |
| `:a 5` | 72 | 72 | 36 | 144 |
| `:a 3` | 120 | 120 | 60 | 240 |
| `:s 1` | 503 | 503 | 251 | 1006 |
| `:s 5` | 101 | 101 | 50 | 202 |

> **Nota**: il raggio di riferimento R=80 è una scelta pragmatica (oggetto "tipico" di taglia media). Se cambia il workflow tipico in Ridley (oggetti molto grandi/piccoli), può essere rivisto in `ridley.turtle.extrusion/reference-radius`.

---

## 5. Override esplicito per call

L'utente può sempre forzare un valore esplicito a ogni chiamata, indipendentemente dalla risoluzione globale:

| Funzione | Modo di override | Esempio |
|---|---|---|
| `circle`, `sphere`, `cyl`, `cone` | positional arg | `(circle 5 32)` |
| `arc-h`, `arc-v`, `bezier-to` | `:steps N` | `(arc-h 10 90 :steps 24)` |
| `fillet-shape` | `:segments N` | `(fillet-shape rect 3 :segments 16)` |
| `loft` | (usa `loft-n` con N esplicito) | `(loft-n 32 (tapered (circle 20) :to 0) (f 30))` |
| `revolve` (low-level) | 4° positional arg | `(revolve profile axis 360 24)` |
| Voronoi cap | `:resolution` nel cap-spec | `{:style :voronoi :resolution 12}` |
| `mesh-to-heightmap` | `:resolution N` | `(mesh-to-heightmap m :resolution 256)` |

> **Nomi**: il knob di override si chiama in modi diversi a seconda della funzione (`:steps`, `:segments`, positional arg). Uniformare tutto a `:segments` è un cambio di API separato — non incluso in questo refactor.

---

## 6. Riepilogo delle modifiche di comportamento

Per script esistenti che non usano override espliciti, dopo il refactor cambia:

| Operazione | Prima (segmenti) | Dopo @ `:n 64` (default) | Note |
|---|---:|---:|---|
| `loft` (longitudinale) | 16 | 64 | 4× più triangoli longitudinali |
| `revolve` (low-level) | 24 | 64 | il `revolve` macro era già a 64 |
| `fillet-shape` | 8 | 32 | corner più morbidi |
| Voronoi cap | 16 | 32 | pattern più dettagliato |
| `mesh-to-heightmap` | 128 | 128 | nessun cambio al default |
| `weave-heightmap` | 128 | 128 | nessun cambio al default |
| `circle`/`sphere`/`cyl`/`cone`/`arc-*`/`bezier-*` | dipendeva | dipende | già usavano resolution; il default `:n` è cambiato da 16 a 64 in un PR precedente |

Gli script che già specificano override locali (`(resolution :n N)` o `:steps N`) non cambiano comportamento.

---

## 7. Architettura (per chi tocca il codice)

Il punto unico di verità per il default di curve resolution è in `src/ridley/turtle/extrusion.cljs`:

- `default-resolution` → ritorna `(settings/get-curve-resolution)` o il built-in `{:mode :n :value 64}`
- `get-resolution state` → consulta lo stato (con override locale) o cade su `default-resolution`
- `default-segments` (with optional state and ratio) → per chiunque ha bisogno di "una N ragionevole" senza una curva concreta:
  - `(default-segments)` — settings-based, ratio 1
  - `(default-segments 0.5)` — settings-based, half
  - `(default-segments 2)` — settings-based, double
  - `(default-segments state 1)` — state-aware (per loft/revolve che hanno state)
- `equivalent-n` (privata) → converte `:a` / `:s` in `:n` equivalente usando `reference-radius` (80)

I call site:
- `turtle/loft.cljs` (`stamp-loft`, `loft-from-path`, voronoi cap)
- `editor/operations.cljs` (`pure-loft-*`)
- `geometry/operations.cljs` (`revolve` low-level)
- `turtle/shape.cljs` (`fillet-shape`)
- `turtle/shape_fn.cljs` (`mesh-to-heightmap`, `weave-heightmap`)

---

## 8. Cose da aggiornare nei draft del manuale

I draft seguenti hanno menzioni stale di numeri vecchi o spiegano il sistema in modo che è ora superato:

| File | Contesto |
|---|---|
| `dev-docs/manual-drafts/04-extrusion.md:340` | Dice "default `(:n 64)`" — OK |
| `dev-docs/manual-drafts/04-extrusion.md:450` | "Il default di `(circle 20)` è 16 punti" → ora 64. **Da correggere**. |
| `dev-docs/manual-drafts/04-extrusion.md:454` | "loft ha un suo step count indipendente (16 di default)" → **non più vero**, ora deriva da resolution. Da correggere. |
| `dev-docs/manual-drafts/03-working-with-2d-shapes.md:271` | `fillet-shape` "default 8" → ora 32 (deriva da resolution). Da correggere. |

Un capitolo unificato "Risoluzione delle curve" potrebbe essere aggiunto come capitolo dedicato (es. tra Path e Shape-fn) — questo documento ne è la prima bozza.

---

## 9. Riferimenti rapidi per Settings UI (Step 3 futuro)

Quando si aggiungerà la sezione Settings:

- **Etichetta**: "Curve resolution" (non "Resolution" generico — confonde con SDF)
- **Tipo**: numero intero (slider 8–256?) con scelta modalità `:n` di default
- **Help text**: "Number of segments per full circle for spheres, cylinders, lofts, fillets, and most curve operations. Higher = smoother but more triangles. Default: 64."
- **Avanzato** (collassabile): scelta tra `:n` / `:a` / `:s` se l'utente ha esigenze particolari
- **Da NON esporre** in prima battuta: `reference-radius`, ratio dei singoli outlier
- **Separato**: una voce dedicata a SDF (`*sdf-resolution*`) con spiegazione diversa ("voxels per unit", "affects only SDF-based meshes")
