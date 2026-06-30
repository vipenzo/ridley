# Rete di copertura del proxy direzionale — la bussola che rende sicuro il fix generale

Branch: `miter-join-corner-construction`. Natura: **solo la rete (test). Nessuna modifica al
codice di produzione.** Segue [dev-docs/proxy-projection-accertamento.md](proxy-projection-accertamento.md)
(il verdetto LARGO). I test vivono in `test/ridley/turtle/corner_self_intersection_net_test.cljs`,
sezione *DIRECTIONAL-PROXY COVERAGE NET*.

> **Esito.** La bussola è scritta e misurata. Asserisce, su tutta la zona che il fix toccherà,
> il comportamento che la **proiezione direzionale** produrrà — quindi è **rossa oggi
> esattamente dove lo `shape-radius` sbaglia**, e diventerà verde quando il fix sostituirà la
> proiezione. Copertura della zona prima **cieca** (i poligoni centrati: nessun test la
> guardava). Suite: **8 fallimenti attesi (2 shell preesistenti + 6 nuovi della bussola), 0
> errori, tutto il resto verde, nessuna rete esistente regredita** (373 test, 1067 assert).

---

## Principio

Ogni test asserisce il **comportamento corretto secondo la proiezione direzionale**
`h(n_in) = max_P dot(P, n_in)` dalla spine (ciò che il fix produrrà), così che oggi sia rosso
dove lo shape-radius se ne discosta, nei due versi:
- casi trattati male **in senso permissivo** (asimmetrici, shell over-skin → costruiti rotti)
  → asserisco **rifiutato**, rosso ora perché oggi passano;
- casi trattati male **in senso conservativo** (poligoni centrati → rifiutati a torto)
  → asserisco **accettato + tri-tri=0**, rosso ora perché oggi sono rifiutati.

Verifica geometrica finale: "accettato" ⇒ build con **tri-tri=0**; "rifiutato" ⇒ la guardia
solleva. Metro **tri-tri + guardia**, mai mesh-diagnose (cieco all'auto-intersezione).

---

## Elenco dei test per famiglia (proj vs shape-radius · comportamento asserito · stato oggi)

### Famiglia 1 — poligoni centrati (sintomo OVER-conservativo, nuovo; la zona prima cieca)

| test | profilo / rail | proj (n_in) | shape-radius | asserito | stato oggi |
|---|---|---|---|---|---|
| `fam1-square-th-EXPECTED-RED` | square half10 / `(f12)(th90)(f12)` | **10** (±x) | 14.14 | accettato + tri-tri=0 | **ROSSO** (oggi rifiutato a torto) |
| `fam1-hexagon-th-EXPECTED-RED` | hexagon circumR10 / `(f9.5)(th90)(f9.5)` | **8.66** (±x) | 10 | accettato + tri-tri=0 | **ROSSO** (oggi rifiutato a torto) |
| `fam1-rectangle-orientation…` (tv) | rect 20×6 / `(f6)(tv90)(f6)` | **3** (±y) | 10.44 | accettato + tri-tri=0 | **ROSSO** (oggi rifiutato a torto) |
| `fam1-rectangle-orientation…` (th) | rect 20×6 / `(f6)(th90)(f6)` | **10** (±x) | 10.44 | **rifiutato** (10>6, davvero non ci sta) | **VERDE** (giustamente rifiutato, ora e dopo) |

**Misurato (codice attuale):** square refused fino a f14, costruito pulito da f15; hexagon
refused fino a f9.5, pulito da f10; rect/tv refused fino a f10+, ; sopra la soglia
shape-radius i poligoni costruiscono **tri-tri=0** → quando il fix abbasserà la soglia alla
proiezione, costruiranno puliti. **L'orientamento è il cuore del direzionale:** lo *stesso*
rettangolo, *stessa* gamba f6, verdetto corretto opposto per piano — tv (reach 3, deve
costruire) vs th (reach 10, deve restare rifiutato). Oggi lo shape-radius (10.44) li rifiuta
**entrambi**, sbagliando su tv.

> **Conferma Domanda specifica:** i poligoni centrati sono oggi **rifiutati a torto** e la
> bussola lo cattura (3 rossi). Era la zona che **nessun test guardava** (le reti esistenti
> rifiutano solo con cerchi, dove proj=shape-radius): ora coperta. Senza questa copertura il
> fix avrebbe reso costruibili questi corner **in silenzio**.

### Famiglia 2 — asimmetrici off-center (l'escaper / proxy under-size)

| test | profilo / rail | proj (n_in) | shape-radius | asserito | stato oggi |
|---|---|---|---|---|---|
| `fam2-asymmetric-escaper-EXPECTED-RED` (extrude) | off-disk cx10 rr4 `:centered? false` / FIX | **14** (+x, dalla spine) | 4 (dal centroide) | **rifiutato** | **ROSSO** (oggi accettato + ripiegato) |
| `fam2-asymmetric-escaper-EXPECTED-RED` (loft) | idem | 14 | 4 | rifiutato (invariante) | **ROSSO** |

**Conferma:** l'asimmetrico oggi si **auto-interseca** ed è **accettato a torto**, con
mesh-diagnose **cieco** — già stabilito dalle reti esistenti sullo stesso fixture:
`escaper-proxy-miss-is-caught` (asserisce *non rifiutato* + `pos? pairs`) e
`the-blindness-of-mesh-diagnose` (asserisce `nm=0` + watertight su quella mesh trafitta). La
bussola aggiunge il **bersaglio**: deve essere **rifiutato** (reach 14 > gamba 10). Asserito
per **extrude E loft** insieme → l'invariante condiviso è sotto osservazione.

> **Nota tecnica importante (`:centered?`):** l'escaper richiede `:centered? false` — è il
> caso in cui i punti restano dove autorati e sporgono x=14 dalla spine. Con `:centered? true`
> lo stamp ri-centra il profilo (centroide→spine) e l'overhang sparisce (build pulito): NON è
> l'escaper. La proiezione va calcolata nel **frame dello stamp** (per `:centered? false` =
> punti autorati). Il difetto separato `:centered? false corner` (centroide GIÀ all'origine,
> `centered-false-corner-self-intersects`) resta **fuori scope** e distinto: per evitare di
> intrecciarlo, la Famiglia 2 asserisce **solo il rifiuto** dell'overhang (che il fix del
> proxy consegna prevenendo del tutto il build), non un "accettato+pulito" off-center (che
> dipenderebbe anche dal difetto `:centered? false`, ortogonale).

### Famiglia 3 — shell (i due rossi esistenti + il confine, ricontestualizzati)

| test | caso | proj | shape-radius (base) | asserito | stato oggi |
|---|---|---|---|---|---|
| `shell-corner-tri-tri-zero-EXPECTED-RED` (già in suite) | shell t=0.2/FIX, t=3/LONG (pelle che ci sta) | outer fits | base sotto-misura | accettato + tri-tri=0 | **ROSSO** |
| `fam3-shell-outer-too-wide-must-refuse-EXPECTED-RED` | shell t=3/FIX (pelle 11.5 troppo larga) | **11.5** (outer skin) | 10 (base) | **rifiutato** | **ROSSO** (oggi costruito ripiegato, 108 coppie) |
| idem, riferimento | plain loft R=11.5 / FIX | 11.5 | 11.5 | rifiutato | **VERDE** (già rifiutato — la condotta che shell deve eguagliare) |

I due rossi shell restano e sono **ricontestualizzati** alla causa corretta (pelle esterna
sotto-mitrata, non l'assemblaggio — falsificato in [shell-corner-fix-attempt.md](shell-corner-fix-attempt.md)).
Il **confine** aggiunto: shell t=3/FIX deve essere **rifiutato**, allineato a `plain 11.5/FIX`
(già rifiutato, verificato).

> **Complicazione A annotata, e un caso la cui correttezza dipende dal MURO non dai punti:**
> `fam3-shell-outer-too-wide` è proprio quel caso. I **punti** base danno reach 10
> (10·tan44.5 ≈ 9.83 < gamba 10 → un fix che proietta *solo i punti* **accetterebbe**, a
> torto). Solo proiettando la **pelle esterna spazzata** (base + t/2 = 11.5 → 11.3 > 10 →
> rifiuta) il verdetto è giusto. Quindi questo test **resta rosso sotto un fix points-only** e
> diventa verde solo sotto un fix **wall-aware** → è la guardia contro la versione
> elegante-ma-incompleta.

### Controllo — il cerchio non si muove

| test | caso | proj | shape-radius | asserito | stato |
|---|---|---|---|---|---|
| `control-circle-unchanged` | circle r10 / `(f30)(th90)(f30)` | 10 | 10 | accettato + tri-tri=0 | **VERDE ora e dopo** |
| idem | circle r21 / FIX | 21 | 21 | rifiutato | **VERDE ora e dopo** |

Proiezione = shape-radius in ogni direzione → il confine del cerchio **non si muove**. È la
non-regressione sul caso che il fix largo non deve toccare. **Verde oggi, deve restare verde.**

---

## Stato suite

```
Ran 373 tests containing 1067 assertions.
8 failures, 0 errors.
```

- **8 rossi**, tutti in `corner_self_intersection_net_test.cljs`, tutti **attesi**:
  - 2 preesistenti: `shell-corner-tri-tri-zero-EXPECTED-RED` (t=0.2/FIX, t=3/LONG);
  - 6 nuovi della bussola: `fam1-square-th`, `fam1-hexagon-th`, `fam1-rectangle-orientation`
    (parte tv), `fam2-asymmetric-escaper` (extrude + loft), `fam3-shell-outer-too-wide`.
- **0 errori** (nessun test lancia eccezioni non gestite).
- **Nessuna rete esistente regredita**: i fallimenti sono solo nel file della rete; gli altri
  342 deftest restano verdi. Le parti verdi dei nuovi deftest (rect/th rifiutato, plain 11.5
  rifiutato, cerchio di controllo) passano. **Aggiungere i test non ha rotto nulla.**

Stati red/green come progettato (actuals verificati): Fam1 falliscono con `(not (built-clean?
…))` (oggi rifiutati); Fam2/Fam3-confine falliscono con `(not (refused? …))` (oggi accettati);
controllo e parti-verdi passano.

---

## Note per il fix successivo (segnalazioni, non risolte qui)

1. **`n_in` per corner composti (th+tv simultanei).** Per un corner di solo `th` o solo `tv`,
   la normale interna è un asse pulito del frame del profilo (±x o ±y) e i test sopra lo
   sfruttano. Per un corner **composto** (th e tv applicati allo stesso vertice), il piano
   della piega è una combinazione e `n_in` è una direzione **generica** nel piano (x,y) del
   profilo, non un asse. La support function `max_P dot(P,n_in)` resta ben definita per
   qualunque direzione, ma il fix deve **estrarre `n_in` dai due heading 3D e proiettarlo nel
   frame locale del profilo** in generale, non assumere asse-allineato. Non restringe la
   generalità, ma richiede la definizione attenta di `n_in` segnalata dal brief. (Nessun caso
   composto nella rete: i fixture sono th-puri o tv-puri, deliberatamente, per fissare i
   numeri; un caso composto andrà aggiunto quando il fix gestirà `n_in` generico.)
2. **Compl. A (ribadita):** la proiezione va sulla **pelle esterna spazzata** (wall-aware per
   shell), non sui punti base — `fam3-shell-outer-too-wide` lo sorveglia.
3. **Compl. B (non-convessi):** risolta nell'accertamento — la proiezione massima è sempre
   ben definita; nessun caso degenere da coprire.
4. **`:centered?`:** la proiezione va nel frame dello stamp; l'escaper è `:centered? false`.

---

## Verifica / chiusura (richiesta dal brief)

1. **Elenco per famiglia** con proj vs shape-radius, comportamento asserito e stato:
   tabelle sopra (Fam1/2/3 + controllo).
2. **Fam1 poligoni oggi rifiutati a torto, catturati:** 3 rossi (square, hexagon, rect/tv);
   la zona prima cieca ora coperta; l'orientamento (rect th vs tv) dimostra il direzionale.
3. **Fam2 asimmetrico oggi si auto-interseca + accettato a torto + mesh-diagnose cieco:**
   confermato (reti esistenti `escaper-proxy-miss-is-caught` + `the-blindness…`), bersaglio
   = rifiutato (rosso ora), extrude+loft.
4. **Fam3 due shell ricontestualizzati + confine t=3/FIX da rifiutare + Compl. A + caso
   wall-dependent:** presenti; `fam3-shell-outer-too-wide` è il caso che dipende dal muro e
   non dai punti; allineato a `plain 11.5/FIX` rifiutato.
5. **Cerchio di controllo verde-ora-e-dopo:** `control-circle-unchanged`, due assert verdi.
6. **Stato suite:** 8 rossi attesi (2+6), 0 errori, nessuna rete esistente regredita.

Fuori scope (invariati): nessun fix, nessuna modifica di produzione, nessun refactor di
`analyze-open-path`; miter-join archiviato; `:centered? false` corner; A1/tag smooth arc;
Classe B planarità embroid; bezier 3D nel rail distorto; extrude-closed. Il fix generale
(refactor direzionale di `analyze-open-path` + proiezione wall-aware della pelle esterna +
questa rete come copertura) è il brief successivo.
