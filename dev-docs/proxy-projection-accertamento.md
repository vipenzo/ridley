# Accertamento — raffinamento del proxy della guardia: proiezione sulla normale interna, chirurgico o largo?

Branch: `miter-join-corner-construction`. Natura: **accertamento, nessuna modifica al codice
di produzione.** Misure fatte con scratch (rimosso) + calcolo standalone. Segue
[dev-docs/shell-corner-fix-attempt.md](shell-corner-fix-attempt.md).

> **Verdetto: LARGO, non chirurgico.** Sostituire lo `shape-radius` (max dal **centroide**,
> direction-independent) con la **proiezione sulla normale interna** (support function dal
> **vertice del rail/spine**, direzionale) **cambia il confine di rifiuto per quasi tutti i
> profili non-circolari**, non solo per quelli che sporgono. Solo il **cerchio** resta
> identico. Per i profili centrati non-circolari (quadrato, rettangolo, esagono, stella) la
> proiezione è **< shape-radius** nella maggior parte degli orientamenti del corner → la
> guardia diventa **più permissiva** (oggi è conservativa perché usa la circonferenza-dal-
> centroide). Per i profili off-center (asimmetrici) è **> shape-radius** → più conservativa,
> correttamente (è l'escaper). Quindi la scelta mirato-vs-generale è una **vera decisione di
> rischio**, non un drop-in. **Due complicazioni emerse**, sotto.

---

## Domanda 1 — come si calcola la proiezione sulla normale interna

**La grandezza giusta** che deve sostituire lo shape-radius nel dimensionamento del miter è
la **support function del profilo lungo la normale interna del corner, misurata dal vertice
del rail (lo spine, = il punto locale `[0 0]`)**:

```
h(n_in) = max  over profile points P  of  dot(P, n_in)
```

dove `P` è in coordinate locali del profilo con origine **sullo spine** (non sul centroide).

**Normale interna `n_in` dai due heading.** Il corner ruota l'heading da `h0` a `h1`. La
piega vive nel piano `(h0, h1)`; l'asse di rotazione è `a = h0 × h1`. La normale interna è la
direzione **nel piano del profilo (perpendicolare a `h0`), nel piano della piega, verso il
lato interno della curva** (dove le sezioni si avvicinano). In pratica, nel frame locale del
profilo `(right, up)`:
- corner **`th`** (heading ruota attorno a `up`) → `n_in = ±right` (asse x del profilo);
- corner **`tv`** (heading ruota attorno a `right`) → `n_in = ±up` (asse y del profilo);
- il **segno** è dato dal verso della svolta (verso quale lato `h1` chiude).

È la stessa geometria del bisettore già esplorata (il piano del miter ha normale
`normalize(h1 − h0)`; `n_in` è la sua proiezione nel piano del profilo). Il miter accorcia
ogni segmento di `h(n_in) · tan(θ/2)`: esattamente quanto basta perché il punto più sporgente
verso l'interno non oltrepassi il bisettore.

**Cosa dà per i tre casi** (numeri misurati, `sr` = shape-radius, `sup` = support; profilo
circolare r=10, asse-allineato):

| profilo | shape-radius (centroide) | support x+ | support y+ | nota |
|---|---|---|---|---|
| cerchio r=10 | 10.00 | 10.00 | 10.00 | coincide in ogni direzione |
| **asimmetrico** off-disk cx=10 rr=4 | **4.00** | **14.00** | 4.00 | l'escaper: dal centroide 4, dallo spine 14 |
| **shell** base r=10, t=3 — **punti BASE** | 10.00 | 10.00 | 10.00 | **manca il muro** |
| **shell** — **pelle ESTERNA** (base+1.5) | 11.50 | 11.50 | 11.50 | cattura base+t/2 |

**Conferma per shell — con una complicazione (sotto): la proiezione cattura `base + t/2`
SOLO se calcolata sulla pelle ESTERNA, non sui punti base.** I punti base del profilo shell
sono il cerchio r=10 → proiezione = 10 = shape-radius, **manca lo spessore**. La sporgenza di
shell vive nei metadati `:shell-thickness`, non nei punti. Vedi Complicazione A.

---

## Domanda 2 — chirurgico o largo? (la matrice che decide)

Proiezione (support, dallo spine) **vs** shape-radius (dal centroide), per profilo:

```
=== CENTERED ROUND (coincide → surgical per questi) ===
circle r10            sr= 10.00  supMax= 10.00  (same)   x+10.00 x-10.00 y+10.00 y-10.00
=== CENTERED NON-CIRCULAR (il confine si muove? → broad) ===
square half10         sr= 14.14  supMax= 10.00 <DIVERGE> x+10.00 x-10.00 y+10.00 y-10.00
rect 20x6             sr= 10.44  supMax= 10.00 <DIVERGE> x+10.00 x-10.00 y+ 3.00 y- 3.00
hexagon circumR10     sr= 10.00  supMax= 10.00 (same)    x+ 8.66 x- 8.66 y+10.00 y-10.00
pentagon circumR10    sr= 10.00  supMax= 10.00 (same)    x+ 9.51 x- 9.51 y+10.00 y- 8.09
5-star out10 in4      sr= 10.00  supMax= 10.00 (same)    x+10.00 x- 8.09 y+ 9.51 y- 9.51
=== OVERHANG off-centre (asimmetrico) — l'escaper ===
off-disk cx10 rr4     sr=  4.00  supMax= 14.00 <DIVERGE> x+14.00 x- 0.00 y+ 4.00 y- 4.00
off-disk cx5  rr4     sr=  4.00  supMax=  9.00 <DIVERGE> x+ 9.00 x- 0.00 y+ 4.00 y- 4.00
=== OVERHANG shell ===
shell BASE            sr= 10.00  supMax= 10.00 (same)    (guard sees this → MISSES wall)
shell OUTER skin +1.5 sr= 11.50  supMax= 11.50 (same)    (captures base+t/2)
```

**Lettura corretta — il `supMax` (max sui 4 assi) INGANNA; conta la direzione del corner.**
Esagono/pentagono/stella mostrano `supMax = sr = 10` solo perché *uno* dei 4 assi colpisce
un vertice. Ma il corner reale ha **una** normale interna specifica, e in quella direzione il
support è spesso **< shape-radius**:
- **quadrato, corner `th`** (n_in = x): support **10** vs sr **14.14** → −29%;
- **esagono, corner `th`** (n_in = x, verso una faccia): support **8.66** vs sr **10** → −13%;
- **rettangolo 20×6, corner `tv`** (n_in = y): support **3** vs sr **10.44** → −71%;
- esagono/quadrato corner che guarda un vertice: support = sr → invariato.

**Fatto generale:** per ogni profilo **centrato**, `support(n_in) ≤ shape-radius` sempre
(shape-radius = max in qualunque direzione dal centroide; il support in UNA direzione non può
superarlo), e **strettamente minore** ogni volta che la normale interna non punta al vertice
più lontano. Quindi:

| classe | proiezione vs shape-radius | effetto sul confine di rifiuto |
|---|---|---|
| **cerchio** | identico in ogni direzione | **nessun cambiamento** (chirurgico) |
| **centrato non-circolare** (quad/rett/esag/stella) | **support < shape-radius** quasi sempre | **più permissivo** (oggi over-conservativo via circonferenza) → **confine si muove** |
| **off-center / asimmetrico** | support > shape-radius | **più conservativo** (rifiuta l'escaper, correttamente) |
| **shell** (su pelle esterna) | support = base+t/2 > shape-radius-base | più conservativo (rifiuta la pelle troppo larga) |

**VERDETTO: LARGO.** Il raffinamento **non** tocca solo i casi che sporgono: cambia il
confine di rifiuto per **tutti i profili non-circolari**. Per i centrati non-circolari (che
sono casi comuni di extrude/loft pieno: quadrati, rettangoli, esagoni, stelle) lo sposta in
direzione **più permissiva** — perché lo shape-radius odierno è **conservativo** (usa la
circonferenza dal centroide, ignorando sia la direzione sia il raggio reale verso la piega).
Solo il cerchio resta identico.

**Sulle reti esistenti, in pratica, il blast-radius è limitato** perché quasi tutte le
asserzioni di rifiuto usano **cerchi** (guard-refuses-overmitre, extrude-very-short-segments,
mech-7, ecc. → invariati), e gli unici profili non-circolari nelle reti (star in mech-4) sono
su corner già **costruiti** (eff > 0), non rifiutati → non flippano. Quindi: **teoricamente
largo, ma le reti attuali non lo esercitano** — il che significa che il cambiamento di
comportamento sui non-circolari sarebbe **silenzioso** (nessuna rete lo cattura oggi), e
andrebbe **coperto con nuovi casi del tri-tri net** prima di shippare il generale.

---

## Domanda 3 — l'invariante extrude≡loft regge?

**Sì.** Entrambi gli operatori dimensionano il miter nello **stesso punto** e con la **stessa
grandezza**:
- extrude: `extrusion.cljs:1419` / `:1561` → `(analyze-open-path commands (shape-radius shape))` → `validate-corner-realizability!` (`:1429`/`:1571`);
- loft: `loft.cljs:591` → `(analyze-open-path commands (shape-radius shape))` → `validate-corner-realizability!`.

Se la proiezione si calcola con la **stessa funzione** sullo **stesso profilo** e con le
**stesse direzioni interne per-corner** (derivate dai medesimi heading del path), extrude e
loft restano **alimentati identicamente** → **rifiutano identicamente**. L'invariante regge,
a condizione che il refactor di `analyze-open-path` (da scalare `radius` a proiezione
direzionale per-corner) sia condiviso da entrambi i call site — come lo è oggi `shape-radius`.

---

## Due complicazioni emerse (da segnalare, non risolvere)

**A — Shell non è chiuso dalla proiezione-sui-punti.** La sporgenza dell'asimmetrico è **nei
punti** del profilo → proiettando i punti la si cattura. La sporgenza di shell è nel
**muro** (`:shell-thickness`), non nei punti: i punti base sono il cerchio r=10, la cui
proiezione = 10 = shape-radius, **manca il +t/2**. Quindi un fix generale che proietta i
*punti del profilo* chiude l'asimmetrico **ma NON shell**. Per chiudere entrambi, la
proiezione va calcolata sul **contorno materiale reale spazzato** (la pelle esterna per
shell = base offset +t/2). La grandezza unificante è "support del contorno esterno reale
verso n_in", ma l'implementazione **deve conoscere lo spessore di shell** (proiettare la
pelle esterna, non i punti base). Il fix generale non è "proietta-il-profilo" puro.

**B — Profili non-convessi: nessun problema per il miter.** Il brief temeva che "il punto
più sporgente verso l'interno" non fosse ben definito per profili concavi. Per la grandezza
che serve al miter — la **massima** estensione verso `n_in` (support function = max della
proiezione) — **è sempre ben definita**, anche per concavi: è il punto più lontano in quella
direzione (sempre un punto del contorno esterno). La concavità non la intacca (la rientranza
sta "dietro", non vincola il miter). La preoccupazione del brief **non si materializza** per
lo shortening. (Conterebbe solo se servisse l'intera silhouette interna, che al miter non
serve.)

---

## Verifica / chiusura

1. **Come si calcola:** support function `h(n_in)=max_P dot(P,n_in)` dallo **spine**; `n_in`
   dai due heading (±right per `th`, ±up per `tv`). Dà: cerchio 10 (=sr), asimmetrico 14
   (>>sr=4), shell **11.5 sulla pelle esterna** (sr-base=10 la manca → Complicazione A).
2. **Verdetto:** **LARGO** — la matrice mostra `support < shape-radius` per i centrati
   non-circolari (quad 10 vs 14.14, esag-th 8.66 vs 10, rett-tv 3 vs 10.44) → confine più
   permissivo; `support > shape-radius` per off-center/shell → più conservativo; solo il
   cerchio invariato. Le reti attuali non lo esercitano (rifiuti su cerchi) → cambiamento
   altrimenti **silenzioso**.
3. **Invariante extrude≡loft:** **regge**, se la proiezione è condivisa ai due call site
   (come oggi lo shape-radius). Confermato: `extrusion.cljs:1419/1561` e `loft.cljs:591`.
4. **Raccomandazione (informata dai numeri):** poiché è **largo**, è una decisione di rischio
   esplicita.

### Raccomandazione

**Generale (proiezione direzionale)** — la cura corretta e unificante, ma:
- **rischio:** sposta (in modo silenzioso oggi) il confine per *tutti* i profili
  non-circolari, verso il **più permissivo** per i centrati. Direzione per lo più **sicura**
  (oggi over-conservativo; con la proiezione *corretta* lo shortening ridotto è esattamente
  sufficiente → niente fold), ma **va verificata caso per caso col tri-tri net** (i corner
  non-circolari che diventano costruibili devono leggere tri-tri=0).
- **costo:** (a) refactor di `analyze-open-path` da scalare `radius` a proiezione direzionale
  per-corner (heading-pair → n_in → proietta); (b) **Complicazione A**: proiettare la pelle
  esterna per shell (thickness-aware), non i punti base; (c) sweep di nuovi casi-net su
  profili non-circolari per rendere **non silenzioso** il cambiamento.
- **chiude:** shell (i due rossi) **e** l'escaper asimmetrico, con un solo meccanismo.

**Mirato (shell: `base + thickness/2`)** — toppa locale:
- **rischio:** basso (cambia solo lo scalare passato per shell: `shape-radius + t/2`; resta
  direction-independent; non tocca gli altri profili). Invariante OK.
- **costo:** minimo.
- **lascia aperto:** l'escaper asimmetrico (seconda toppa più avanti = frammentazione, ciò
  che l'indagine ha sempre evitato) **e** non corregge l'over-conservatività sui centrati
  non-circolari (che però non è un bug, solo sub-ottimale).

**Indicazione:** il generale è la strada giusta *di principio* (unifica, anti-frammentazione)
**ma non è un drop-in**: è largo, richiede il refactor direzionale, la consapevolezza dello
spessore di shell (Compl. A), e una **copertura net nuova** sui non-circolari prima di
shippare (altrimenti cambia comportamenti comuni in silenzio — lo stesso tipo di rischio non
misurato che ci ha già ingannato). Se si vuole rischio minimo e progresso subito, il mirato
chiude i due rossi di shell senza toccare nessun altro; ma lascia l'escaper, che è lo stesso
difetto. **La decisione (mirato ora vs generale staged+net-guarded) torna allo sviluppatore,
ora con i numeri.**

---

## Criterio di chiusura del fix (fissato per il brief successivo)

Il fix — mirato o generale — è giusto quando **tutti** valgono (più ricco di "i due rossi
diventano verdi", che possono ingannare):
1. shell realizzabili (t=0.2/FIX, t=3/LONG) → **verdi, tri-tri=0**;
2. shell non realizzabile (t=3/FIX, pelle troppo larga) → **rifiutato** dalla guardia,
   allineato a `plain 11.5/FIX` (già rifiutato);
3. extrude e loft pieno sui casi comuni → comportamento di rifiuto **invariato**, **o** se
   cambia (i non-circolari, col generale), **verificato corretto** (tri-tri=0 sui nuovi
   costruibili) e **non** una regressione — coperto da nuovi casi-net (NON silenzioso);
4. reti esistenti verdi, **embroid invariato**.

Per il **generale** vale anche: la proiezione deve essere calcolata sul **contorno materiale
spazzato** (pelle esterna per shell), non sui punti base (Compl. A), altrimenti il punto 1
non si chiude.

---

Fuori scope (invariati): nessuna implementazione qui; miter-join archiviato; `:centered?
false` corner; A1/tag smooth arc; Classe B planarità embroid; bezier 3D nel rail distorto;
extrude-closed.
