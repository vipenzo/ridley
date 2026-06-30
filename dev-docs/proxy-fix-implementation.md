# Fix generale — proiezione direzionale del proxy della guardia (implementato)

Branch: `miter-join-corner-construction`. Implementazione del fix generale sorvegliato dalla
rete (proxy-net-accertamento.md). Suite: **373 test, 1065 assert, 0 failures, 0 errors**.

> **Esito.** Lo `shape-radius` (max dal centroide) nel dimensionamento del miter del corner è
> stato sostituito dalla **proiezione direzionale** `corner-inner-extent`: quanto il profilo
> arriva verso la **normale interna** del corner, misurato **nel frame di stamp**, **wall-aware**
> per shell. I tre sintomi sono curati con un solo meccanismo: poligoni centrati ora accettati
> (tri-tri=0), shell ora costruito/rifiutato sulla pelle esterna, asimmetrici ora mitrati
> correttamente. **Due scoperte da segnalare** (sotto): il fix **risolve anche il corner
> `:centered? false`** (era lo stesso difetto del proxy, non separato), e l'escaper off-disk
> **non va rifiutato ma costruito pulito** (la "14" dell'accertamento era misurata
> dall'origine autorata, non dal frame di stamp dove la reach è 8 → ci sta).

---

## I tre pezzi e i criteri raggiunti

### Pezzo 1 — proiezione direzionale per-corner (`analyze-open-path-dir`)

[extrusion.cljs](../src/ridley/turtle/extrusion.cljs): nuove `corner-inner-extent`,
`shell-outer-points-2d`, `corner-rot-angle`, `analyze-open-path-dir`. Per ogni corner estrae
la normale interna `n_in = normalize(cross(normalize(cross h0 h1), h0))` (lo stesso `center-dir`
dei round-corner) dai due heading, e calcola `h(n_in) = max(0, max_P dot(P_stamp, n_in))` nel
frame di sezione (right=heading×up, up). Lo shortening = `h·tan(θ/2)`, con **θ = angolo reale
tra h0 e h1** (`acos(h0·h1)`), non la somma delle rotazioni (conta per i corner compositi).
Wired in `extrude-from-path` ([extrusion.cljs](../src/ridley/turtle/extrusion.cljs), era
`analyze-open-path commands radius`).

**Criterio Pezzo 1 — raggiunto:**
- Famiglia 1 (poligoni centrati) → **accettati + tri-tri=0**: `fam1-square-th` (proj 10 vs
  sr 14.14), `fam1-hexagon-th` (8.66 vs 10), `fam1-rectangle` tv (3 vs 10.44). VERDI.
- `rect/th` (proj 10 > leg 6) → **resta rifiutato** (prova che non è troppo permissivo). VERDE.
- **cerchio di controllo** (proj=sr) → **non si muove** (clean a f30/th90; r21/FIX rifiutato). VERDE.
- Invariante extrude≡loft: Famiglia 2 e composito lo asseriscono su entrambi → reggono.

### Pezzo 2 — proiezione wall-aware della pelle esterna di shell (Complicazione A)

`corner-inner-extent` proietta, per un profilo con `:shell-thickness`, i punti della **pelle
esterna** (`shell-outer-points-2d`: base + thickness/2 verso l'esterno), non i punti base.
Wired sia nella **guardia** del loft ([loft.cljs](../src/ridley/turtle/loft.cljs):
`analyze-open-path-dir commands shape state`) sia nel **build** (`compute-corner-data` usa
`corner-inner-extent shape old-heading new-heading (:up s)`). `shape` porta `:shell-thickness`
(probe a t=0), quindi guardia e build sono wall-aware e coerenti.

**Criterio Pezzo 2 — raggiunto:**
- shell realizzabile (pelle che ci sta) → **costruito tri-tri=0**: `shell-corner-builds-clean`
  (t=3/LONG → 0, t=6/LONG → 0). VERDE.
- shell non realizzabile (t=3/FIX, pelle esterna 11.5 troppo larga) → **rifiutato**, allineato
  a `plain 11.5/FIX` rifiutato: `fam3-shell-outer-too-wide`. VERDE.
- **points-only fallirebbe**: i punti base danno reach 10 (9.83 < leg 10 → accetterebbe a
  torto t=3/FIX); solo la pelle esterna (11.5 → 11.3 > 10) rifiuta. Il test lo cattura.

### Pezzo 3 — copertura compositi

`composite-corner-builds-clean`: `rect 20x6` su `(f40 th35 tv35 f40)` (corner composto,
realizzabile) → **tri-tri=0** su extrude e loft. `analyze-open-path-dir` deriva `n_in` dai due
heading 3D (direzione 2D generale, non un asse) e l'angolo da `acos(h0·h1)` → gestisce il
composto. VERDE.

---

## Criterio di chiusura completo — confermato

1. **Gli 8 rossi → verdi nel verso giusto** (accettati costruiscono tri-tri=0, rifiutati
   sollevano la guardia): Fam1 ×3 accettati-clean; rect/th rifiutato; Fam2 ×2 **costruiti
   clean** (target corretto, vedi scoperta sotto); Fam3 shell clean (LONG) + rifiutato
   (t=3/FIX). ✓
2. **rect/th resta verde** (rifiutato, non ci sta) e **cerchio di controllo resta verde**
   (non si muove). ✓
3. **Invariante extrude≡loft regge** — alimentati dalla stessa `corner-inner-extent` allo
   stesso punto; Fam2 + composito asseriscono entrambi gli operatori. ✓
4. **Composito tri-tri=0**. ✓
5. **Nessuna rete esistente regredisce**: 0 failures su 373 test (corner-assembly,
   spine-drift, shape-fidelity, loft-nm, arc-profile, embroid — tutte verdi). Metro tri-tri +
   guardia, mai mesh-diagnose. ✓ Embroid **invariato** (non ha `:shell-thickness`; la
   wall-awareness non lo tocca; le sue fixture restano clean/watertight).

---

## Diff di produzione (circoscritto)

| file | cosa |
|---|---|
| [extrusion.cljs](../src/ridley/turtle/extrusion.cljs) (+118) | `shell-outer-points-2d`, `corner-inner-extent`, `corner-rot-angle`, `analyze-open-path-dir` (nuovi); `extrude-from-path` usa `analyze-open-path-dir` per il sizing del miter (`radius` resta solo per i round-corner fillet) |
| [loft.cljs](../src/ridley/turtle/loft.cljs) (+13/−9) | guardia usa `analyze-open-path-dir commands shape state`; `compute-corner-data` usa `corner-inner-extent` per r-p/r-n; rimosso `initial-radius` inutilizzato |

`analyze-open-path` (scalare) **non è toccato** — resta per il cammino holed
(`extrude-with-holes-from-path`) e `extrude-closed` (fuori scope). Il sizing del miter è
l'unica cosa cambiata. (Il file `examples/procedural-bowl.clj` modificato nel working tree è
una WIP dell'utente, non parte di questo fix.)

---

## Due scoperte da segnalare (il brief chiedeva di fermarsi e segnalare se la proiezione e il
## :centered? false si toccassero — si sono toccati)

### A — il fix RISOLVE il corner `:centered? false` (era lo stesso difetto, non separato)

Sessioni precedenti tenevano il `:centered? false corner self-intersection` come **secondo
meccanismo distinto, fuori scope**. Misurato ora: **è lo stesso difetto del proxy.**
`compute-stamp-transform` offsetta un profilo `:centered? false` così che il suo **primo
punto** stia sul rail → il centroide va **fuori dal rail** → un overhang che lo `shape-radius`
(dal centroide) sotto-mitrava. La proiezione direzionale misura dal frame di stamp, quindi
dimensiona quell'overhang correttamente, e il corner **ora costruisce pulito (tri-tri=0)**.
Test convertito: `centered-false-corner-now-clean` (era `…-self-intersects`). **Il fix
subsume la famiglia `:centered? false corner`.** Non l'ho risolto "insieme" per scelta — la
proiezione direzionale è semplicemente corretta per i profili off-center, e `:centered? false`
è un piazzamento off-center. Escluderlo avrebbe richiesto di mantenere apposta il proxy
sbagliato per quei profili (= tenere un bug). **Segnalo per ratifica dello sviluppatore.**

### B — l'escaper off-disk va COSTRUITO PULITO, non rifiutato (correzione del numero "14")

L'accertamento del proxy aveva misurato la reach dell'off-disk a **14 dall'origine autorata**
→ premessa "proiezione 14 > leg → rifiutato" (la Famiglia 2 asseriva il rifiuto). **Nel frame
di stamp** (`:centered? false` mette il primo punto sul rail) la reach verso la normale interna
è **8**, e `8·tan(44.5°) ≈ 7.86 < leg 10` → il corner è **realizzabile**. Il fix lo mitra
correttamente e **costruisce pulito (tri-tri=0)** su extrude e loft (invariante). Test
corretto: `fam2-asymmetric-escaper-builds-clean` (era `…-EXPECTED-RED` che asseriva il
rifiuto). La rete ora asserisce il comportamento **misurato-corretto**.

### Nota — il residuo al bordo di realizzabilità (t=0.2/FIX) — CORRETTA 2026-06-30

`shell t=0.2/FIX` (la fixture nominata dal brief) sta al **bordo di realizzabilità**
(eff≈0.07): il fix riduce il fold 126→**52** ma non a 0. Non è un fallimento del *sizing del
proxy* (`plain@outer 10.1/FIX` è pulito, e shell comodamente realizzabile va a 0). **La prima
ipotesi "artefatto discreto" è stata FALSIFICATA in pre-commit (V4):** alzando la risoluzione
del cerchio il tri-tri **cresce** (24→52, 48→60, 96→86), non scende → **non è un artefatto di
discretizzazione, è una self-intersezione REALE** del corner **dual-ring** al bordo (la pelle
interna / il bridge dual-ring si attraversano quando eff→0). È un **buco noto, follow-up
separato** (la costruzione del corner dual-ring all'orlo, non il proxy). La bussola shell-clean
usa casi comodi (t=3/LONG, t=6/LONG, verdi); t=0.2/FIX è **riusato come esempio per la cecità
di mesh-diagnose** (è una mesh auto-intersecante reale che mesh-diagnose dichiara pulita).

---

## Blast-radius (benigno, come atteso)

Argomento + suite verde: **nessun caso prima-pulito viene ora rifiutato.**
- Profili **centrati**: direzionale ≤ shape-radius → **solo più permissivo** (poligoni prima
  over-conservativi ora accettati e costruiti clean). Mai un nuovo rifiuto.
- Profili **off-center**: direzionale può superare shape-radius, ma se un caso costruiva
  **pulito** prima (shape-radius accettava e non piegava) allora la reach reale stava nella
  gamba → il direzionale (=reach) accetta anch'esso. I **nuovi rifiuti** sono solo di casi che
  **prima piegavano** (off-center oltre la gamba) → corretti.
- Il cerchio non si muove (direzionale=shape-radius).
Nessun test della suite regredisce (373 verdi). **Fuori-scope rimasto:** l'**extrude holed**
usa ancora `analyze-open-path` scalare (centroide) — nessuna famiglia/test lo copre; per piena
coerenza dovrebbe passare anch'esso al direzionale (lavoro separato; il loft holed già usa il
direzionale via `compute-corner-data`).

---

## Stato suite

```
Ran 373 tests containing 1065 assertions.
0 failures, 0 errors.
```
Tutte verdi, incluso il composito. Diagnostico shell: `edge residual t=0.2/FIX tri-tri=52
nm=0`, `clean t=3/LONG tri-tri=0`, `over-wide t=3/FIX REFUSED`.

Fuori scope (invariati): miter-join archiviato; A1/tag smooth arc; Classe B planarità embroid;
bezier 3D nel rail distorto; extrude-closed; extrude-holed (proxy scalare, vedi sopra).
