# Accertamento — extrude-holed e il proxy direzionale

Branch: `miter-join-corner-construction`. Data: 2026-07-01. Segue il follow-up
prioritario della mappa dei territori rotti ([broken-territories.clj](broken-territories.clj),
Territorio 2). **Esito: la cura non è il trapianto banale — è un adattamento.** Il fix
di dimensionamento è stato scritto, misurato, e **rimosso** perché regredisce un caso
sano. Il codice resta sul proxy scalare; questo documento è la bussola del follow-up.

## La domanda preliminare — trapianto o adattamento?

**Verdetto: sembra un trapianto, è un adattamento.**

La chiamata di dimensionamento si trapianta *verbatim* dal fratello non-forato:

- `extrude-with-holes-from-path` ([extrusion.cljs:1531](../src/ridley/turtle/extrusion.cljs))
  usava `(analyze-open-path commands radius)` — proxy **scalare** (shape-radius dal
  centroide).
- `extrude-from-path` (no-holes, [extrusion.cljs:1677](../src/ridley/turtle/extrusion.cljs))
  usa `(analyze-open-path-dir commands shape state)` — proxy **direzionale**.
- I due rami condividono la stessa struttura di assemblaggio (ring-loop continuo,
  stesso consumo di `:dist`/`:shorten-start`/`:shorten-end`/`:rotations-after`,
  stessa `validate-corner-realizability!`). `analyze-open-path-dir` produce output
  di forma identica. La sostituzione è una riga.

**Il modello NON è il loft-holed.** Il loft usa `analyze-open-path-dir` solo per la
**guardia** ([loft.cljs:591-592](../src/ridley/turtle/loft.cljs)) e dimensiona il miter
con un meccanismo separato (`analyze-loft-path` + `compute-corner-data`). Il modello
corretto è il fratello non-forato `extrude-from-path`, che dimensiona E rifiuta dallo
stesso analizzatore. La trappola "loft-holed è fatto quindi extrude-holed si fa uguale"
è doppiamente sbagliata: il trapianto viene dal fratello non-forato, non dal loft; e —
il punto — non basta.

## Perché non basta: il criterio doppio e la regressione

Il criterio: il caso off-center holed rotto deve costruirsi pulito se realizzabile, ed
essere rifiutato se no. Fixture: `off-disk cx10 rr4` (disco off-center) + foro centrale,
= gemello forato dell'escaper già fidato (Family 2). reach verso la normale interna = 8,
miter 8·tan44.5 ≈ 7.86.

Numeri **misurati nel node-harness** (metro tri-tri strict-interior):

| caso | pre-fix (scalare) | post-fix (direzionale) | plain (riferimento) |
|---|---|---|---|
| INNER-side realizzabile (FIX `f10 th89`) | **79 folds** | **0 pulito** ✓ | 0 |
| INNER-side non-realizzabile (`f6 th89`)  | **75 folds** | **rifiutato** ✓ | rifiutato |
| OUTER-side (`th-89` / frame, miter≈0)    | **0 pulito**  | **21–30 folds** ✗ | 0 |

Il fix di dimensionamento **cura il lato interno** (79→0, e rifiuta il non-realizzabile:
criterio doppio raggiunto su quel lato) **ma regredisce il lato esterno** (0→21): un
corner off-center che sfora LONTANO dalla piega ha miter direzionale ≈0, e a quel punto
il **builder del corner tapered forato** (`generate-tapered-corner-ring-data`,
[extrusion.cljs:1187](../src/ridley/turtle/extrusion.cljs)) ripiega.

### La causa della regressione (accertata)

- La regressione è **specifica di `:tapered`** (il joint-mode di default). Con `:round`
  e `:flat` lo stesso corner esterno si costruisce pulito (misurato: `holed[tapered]=21`
  vs `holed[round]=0` `holed[flat]=0`).
- `generate-tapered-corner-ring-data` delega a `generate-tapered-corner-rings` per ogni
  foro. Quel builder genera **un solo** anello-ponte, ruotato attorno a `corner-pos` di
  metà angolo e scalato di `1/cos(θ/2)` lungo la bisettrice. La scala è corretta per un
  anello **centrato sul pivot**. Un foro lontano dal pivot (profilo off-center: il foro
  sta ~10 unità dal pivot) con quel singolo ponte **sfora** — la parete del foro
  attraversa se stessa.
- Il proxy **scalare** applicava un miter non-nullo (dal centroide) anche sul lato
  esterno, che allontanava le sezioni quanto basta a **mascherare** il fold del foro.
  Il miter direzionale corretto (≈0) toglie quello spazio e il difetto latente emerge.
- Il plain path non ha fori → non vede mai questo fold (per questo `plain=0` sempre).
- I fori **centrati** (vicini al pivot) non sforano (`fam4-holed-control-stays-clean`
  verde): il difetto è dei fori **lontani dal pivot**, cioè i profili off-center.

## Invariante extrude-holed ≡ loft-holed — e una scoperta

**loft-holed condivide GIÀ il difetto del corner tapered forato.** Misurato (node,
oggi, senza alcun fix): sul lato esterno off-center (`off-disk cx10 rr4`, `th-89`,
miter≈0) **loft-holed = 77 folds**, plain = 0. loft-holed è già direzionale (guardia +
build) e usa lo **stesso** `generate-tapered-corner-ring-data` per i fori — quindi
sforava già da prima. Il fold del corner tapered forato **non è introdotto** dalla
sostituzione direzionale: è un difetto **preesistente e condiviso**.

Conseguenze:

- **L'invariante REGGE sotto la sostituzione direzionale.** Oggi (extrude-holed scalare)
  è rotto: sul lato interno extrude-holed folds (79) dove loft-holed è pulito (0); e sul
  lato esterno extrude-holed è pulito (0, per mascheramento scalare) dove loft-holed
  folds (77). La sostituzione direzionale porta extrude-holed a **coincidere** con
  loft-holed su ENTRAMBI i lati (interno: 0/0; esterno: folds/folds). I due operatori
  rifiutano/costruiscono identicamente — l'invariante è soddisfatto, ma su un builder
  ancora difettoso al lato esterno.
- **Il fix del builder cura entrambi.** Correggere il corner tapered forato risana sia
  loft-holed (che oggi sfora il lato esterno) sia extrude-holed (dopo la sostituzione).
- **Sequenza corretta:** builder PRIMA (risana loft-holed subito + apre la strada), poi
  la sostituzione direzionale a extrude-holed (che a quel punto è un trapianto pulito).

La "regressione" 0→21 di extrude-holed è reale rispetto al suo passato scalare, ma è il
passaggio a un difetto che loft-holed ha già: non un nuovo difetto, l'eredità di uno
esistente.

## Pezzo 3 — dove resta lo scalare

Dopo che il fix del builder sbloccherà la sostituzione direzionale, lo scalare
`analyze-open-path` non avrà **nessun** consumatore di produzione (oggi il suo unico
uso residuo è proprio `extrude-with-holes-from-path`; sopravvive come `def ^:export`
pubblico + test). Il proxy scalare per il corner-sizing vivrà solo in
`analyze-closed-path` → `extrude-closed-from-path`
([extrusion.cljs:1259/1932](../src/ridley/turtle/extrusion.cljs)) — il fronte
extrude-closed, già dichiarato fuori scope. Cioè: **extrude-closed erediterà l'ultimo
uso dello scalare**, come previsto dal brief.

## Raccomandazione

Il follow-up è un **mini-accertamento sul builder**, non un trapianto di
dimensionamento:

1. Correggere `generate-tapered-corner-rings` (o il suo uso per i fori) perché un anello
   **offset dal pivot** giri pulito a un corner netto — probabilmente scalando/ruotando
   attorno al pivot corretto, o suddividendo in sub-anelli round-style quando
   offset×angolo supera una soglia. `:round`/`:flat` già puliti sono la prova che il
   fold è del ponte singolo tapered, non della geometria.
2. Aggiungere una rete per il corner tapered forato attraverso offset×angolo (il fold è
   invisibile a mesh-diagnose: solo tri-tri).
3. Solo allora fare la sostituzione direzionale a [extrusion.cljs:1531](../src/ridley/turtle/extrusion.cljs)
   (una riga), e riasserire i target verdi già presenti in `corner_self_intersection_net_test`
   (`fam4-territory-boundary-references`) come regression-guard.

## Stato del codice

- `extrude-with-holes-from-path`: **sul proxy scalare** (invariato), con una NOTA che
  spiega perché e rimanda a questo documento.
- `corner_self_intersection_net_test`: Family 4 aggiunta — `fam4-holed-control-stays-clean`
  (verde), `fam4-territory-boundary-references` (verde, i target che il fix dovrà
  colpire), `fam4-holed-territory-diagnostic` (stampato: la mappa del difetto).
  Suite: 376 test, 0 fallimenti.
