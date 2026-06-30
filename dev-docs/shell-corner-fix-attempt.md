# Shell corner fix — tentativo, falsificazione, e causa corretta

Branch: `miter-join-corner-construction`. Natura: **tentativo di fix + accertamento
correttivo. Nessuna modifica al codice di produzione lasciata** (la modifica tentata è
stata **rivertita** perché misurata come no-op). Segue
[dev-docs/shell-corner-accertamento.md](shell-corner-accertamento.md).

> **Esito.** Il fix proposto dal brief — *estendere il build continuo (tappa-2) al cammino
> shell rimuovendo shell dal gate `continuous?`* — è stato applicato in modo chirurgico e
> **misurato: NON cambia nulla.** Il tri-tri di shell resta **identico** (126/FIX, 16/LONG,
> 108/t3-FIX), invariato dalla modifica. Il motivo è geometrico e definitivo: **build
> continuo e build per-segmento producono la STESSA geometria** (stessi anelli → stesse
> bande → stesse cappe), e il tri-tri è una misura **geometrica**, cieca al raggruppamento
> in sotto-mesh. La tappa-2 cura difetti **topologici** (le cappe doppie / le cuciture a
> T del loft pieno, viste da mesh-diagnose); il difetto di shell è **geometrico** (la
> posizione degli anelli), invisibile a mesh-diagnose. **Firma diversa → cura diversa** —
> esattamente l'indizio che l'accertamento aveva annotato e a cui aveva dato troppo poco
> peso. **L'accertamento aveva mis-attribuito la causa**: non è l'assemblaggio per-segmento.
>
> **Causa corretta (misurata):** il miter del corner è dimensionato sul **raggio BASE**
> (`shape-radius` del profilo centerline = 10), ma la **pelle ESTERNA** di shell sta a
> `base + spessore/2`. La pelle esterna è quindi **sotto-mitrata** e si ripiega al corner.
> È la famiglia del **proxy shape-radius** (la stessa radice dell'escaper off-centre: il
> miter non conosce la sporgenza esterna reale del profilo) — il *secondo meccanismo* che
> il brief stesso aveva indicato come fuori scope, **ma che si rivela essere LA causa, non
> un di più.** Contrariamente alla premessa del brief, i due test rossi **dipendono** da
> questo gap.

---

## La modifica tentata (poi rivertita)

### Lo stato del gate condiviso (l'accertamento richiesto prima di toccarlo)

`continuous?` ([loft.cljs:969](../src/ridley/turtle/loft.cljs#L969)) =
```clojure
(and (not dual-ring?) (not has-holes?))
```
con `dual-ring? (or shell-mode? embroid-mode?)` ([loft.cljs:622](../src/ridley/turtle/loft.cljs#L622))
e `has-holes? (and (not dual-ring?) (boolean (:holes shape)))` ([loft.cljs:640](../src/ridley/turtle/loft.cljs#L640)).

Il gate tratta shell ed embroid **in blocco** (via `dual-ring?`), e holed a parte (via
`has-holes?`). → **Scenario (2)** del brief: per isolare shell serve introdurre una
distinzione. La modifica chirurgica sarebbe:
```clojure
continuous? (and (not embroid-mode?) (not has-holes?))   ; shell continuo, embroid+holed per-segmento
```
che lascia **embroid** (embroid-mode? true → escluso) e **holed** (has-holes? true →
escluso) sul per-segmento, spostando **solo** shell al continuo. Chirurgica e isolata,
come richiesto. Applicata e compilata pulita.

### Il risultato: no-op

| caso | tri-tri PRIMA | tri-tri DOPO (shell continuo) |
|---|---|---|
| shell t=0.2 / FIX  | 126 | **126** |
| shell t=3   / LONG | 16  | **16** |
| shell t=3   / FIX  | 108 | **108** |

Rebuild pulito da zero (`rm out/test.js`), nessuna differenza. **Il fix è un no-op per il
tri-tri.**

### Perché — l'argomento geometrico

Al corner, le due branche assemblano gli **stessi anelli** nelle **stesse bande**:
- **continuo** ([loft.cljs:971-983](../src/ridley/turtle/loft.cljs#L971)): splice
  `section-rings ++ bridge ++ [next-start-ring]` in **una** sequenza, una sola
  `build-shell-sweep-mesh` alla flush finale, cappe solo ai due veri estremi.
- **per-segmento** ([loft.cljs:984-1002](../src/ridley/turtle/loft.cljs#L984)): `section-mesh`
  = build(section-rings); `corner-mesh` = build(`[end-ring] ++ bridge ++ [next-start-ring]`);
  poi `next-start-ring` riparte la sezione successiva.

In entrambi: banda `end-ring→next-start-ring` (il corner), bande interne delle sezioni,
cappe ai veri estremi. `build-shell-sweep-mesh` triangola coppie di anelli consecutivi con
diagonale fissa → **facce identiche**. `pure-loft-path` poi fa `merge-vertices` sui
sotto-mesh per-segmento → set di vertici/facce identico al continuo. **Geometria identica →
tri-tri identico.** La differenza tra le due branche è **solo topologica** (raggruppamento +
cuciture), e il tri-tri non la vede.

Nota: shell sul per-segmento era **già** topologicamente pulito (nm=0, watertight) — non
aveva il difetto topologico che la tappa-2 cura sul pieno. Quindi non c'era nulla da curare
sul versante topologico, e il versante geometrico la tappa-2 non lo tocca: **no-op completo.**

### Conclusione: rivertita

La modifica è stata **rivertita** ([loft.cljs](../src/ridley/turtle/loft.cljs) torna
byte-identico a HEAD). Lasciare un cambio comportamentale che non raggiunge l'obiettivo e
porta un commento ormai falsificato sarebbe fuorviante. **Nessuna modifica di produzione su
questo branch.**

---

## La causa corretta — misurata, non inferita

Il controllo `shell-corner-outer-geometry-fits-control` è il perno: un **loft pieno al raggio
esterno** di shell costruisce **pulito (0)** perché mitra **su quel raggio**; lo shell allo
**stesso raggio esterno** si ripiega perché mitra sul **raggio base** e quindi **sotto-mitra
la pelle esterna**. La differenza non è l'assemblaggio né la larghezza grezza: è la **base
del miter** (base vs esterno).

- shell t=0.2 (outer 10.1): sotto-mitra di `0.1·tan(44.5°) ≈ 0.1` → la pelle esterna si
  sovrappone di poco al corner. A FIX (al limite di realizzabilità) bastano per 126 coppie;
  a LONG 4.
- shell t=3 (outer 11.5): sotto-mitra di `1.5·tan(44.5°) ≈ 1.47`. A LONG (la pelle esterna
  ci sta, plain 11.5=0) → 16 coppie da puro sotto-miter. A FIX la pelle esterna 11.5 **non
  ci sta affatto** (plain 11.5/FIX è RIFIUTATO) → 108, caso misto larghezza+miter.

È la famiglia del **proxy shape-radius**: il miter è dimensionato su `shape-radius` (max dal
centroide = la centerline), che non conosce la sporgenza esterna di shell. Stessa radice
dell'escaper off-centre già in rete.

---

## Verifica (chiusura, adattata all'esito negativo)

- **Condizione del gate + scenario:** `(and (not dual-ring?) (not has-holes?))`
  ([loft.cljs:969](../src/ridley/turtle/loft.cljs#L969)); shell+embroid in blocco via
  `dual-ring?` → **scenario (2)**. La distinzione chirurgica `(not embroid-mode?)` è stata
  identificata e provata.
- **Diff circoscritto al gate:** sì (una riga), ma **rivertito** perché no-op. Diff di
  produzione finale: **nessuno**.
- **I due rossi shell:** **NON** passano a verde (restano 126, 16). Il fix non li tocca.
- **Embroid invariato:** sì — la modifica lo teneva esplicitamente sul per-segmento, e
  comunque è rivertita; le fixture embroid restano identiche (tri-tri=0, watertight).
- **Tutte le reti esistenti verdi:** sì (produzione invariata); solo i 2 EXPECTED-RED shell
  restano rossi, come bussola.
- **Coincidenza di forma su shell sano:** garantita per costruzione (produzione invariata; e
  comunque su rail dritto il ramo del corner non viene mai raggiunto).
- **Gap del proxy:** **non** mascherato — anzi, **identificato come LA causa**, non come un
  secondo meccanismo accessorio. I due rossi **dipendono** da esso (la premessa del brief che
  non vi dipendessero è risultata errata).

---

## Raccomandazione e nota sul branch

**Il fix vero** è dimensionare il miter del corner di shell sulla **sporgenza esterna**
(`base + spessore/2`) invece che sul raggio base — cioè il **raffinamento del proxy
shape-radius** della guardia/shortening. È un lavoro a sé, da fare con quel raffinamento,
con la sua bussola: i due EXPECTED-RED shell di questa rete sono già la bussola giusta (vanno
a 0 quando il miter conosce la pelle esterna; il caso t=3/FIX verrà invece **rifiutato** da
una guardia outer-aware, coerente con `plain 11.5/FIX` rifiutato).

La rete è stata **corretta** per riflettere la causa misurata (header + commenti dei test in
`corner_self_intersection_net_test.cljs`): non più "build per-segmento, cura = tappa-2" ma
"sotto-miter della pelle esterna, cura = miter sulla sporgenza esterna".

**Nota sul branch (deviazione di scopo):** `miter-join-corner-construction` ha ormai deviato
dal nome. Storia: (1) accertamento miter-join → il pieno è già sano, shell è il rotto; (2)
accertamento shell → mis-attribuito all'assemblaggio; (3) **questa sessione** → l'assemblaggio
è falsificato, la causa è il proxy della pelle esterna. Il miter-join resta **archiviato**
(estende la realizzabilità, non è protezione). Il branch porta ora: la rete tri-tri (incl.
shell) e tre report di accertamento. Il **fix** di shell è rinviato al lavoro sul proxy
shape-radius, dove ha la sua bussola pronta.
