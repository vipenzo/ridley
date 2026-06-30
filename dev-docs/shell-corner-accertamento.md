# Accertamento — causa dell'auto-intersezione di shell ai corner + rete tri-tri per shell

> ⚠️ **CORREZIONE (2026-06-29) — la conclusione sulla CAUSA in questo documento è stata
> FALSIFICATA.** Vedi [dev-docs/shell-corner-fix-attempt.md](shell-corner-fix-attempt.md).
> Le MISURE qui (tri-tri 126/16/108; coppie outer-skin vs outer-skin; pelle interna
> innocente; controllo plain@outer = 0) **restano valide**. Ma l'attribuzione al *build
> dual-ring per-segmento* (Domanda 1/2/3) e la cura *"estendi la tappa-2"* sono **sbagliate**:
> il build continuo è stato applicato a shell e **non cambia nulla** (continuo e per-segmento
> producono geometria identica → tri-tri identico). La causa reale è il **sotto-miter della
> pelle esterna** (il miter è dimensionato sul raggio BASE, non su `base+spessore/2`) — la
> famiglia del **proxy shape-radius**. Il controllo plain@outer = 0 dimostra la BASE del
> miter (base vs esterno), non l'assemblaggio. Leggere il resto come *misure corrette,
> attribuzione superata.*

Branch: `miter-join-corner-construction`. Natura: **accertamento, nessuna modifica al
codice di produzione.** Le misure sono state fatte con scratch (tri-tri pierce test) poi
rimossi; la **rete tri-tri per shell** è il deliverable che resta in suite
(`test/ridley/turtle/corner_self_intersection_net_test.cljs`). Segue
[dev-docs/miter-join-accertamento.md](miter-join-accertamento.md).

> **Esito sintetico.** La causa è stata **misurata, non inferita** — e NON è quella che il
> report del miter-join aveva ipotizzato per analogia col pieno. Le coppie tri-tri di shell
> sono **OUTER-skin vs OUTER-skin, addensate al corner; la pelle INTERNA non è coinvolta**
> (zero inner-inner, zero inner-outer). Non è una piega del ring interno. Il controllo
> decisivo: un **loft pieno al raggio ESTERNO** del guscio (`base + spessore/2`) costruisce
> **pulito (0)** ovunque costruisca — quindi la geometria esterna **ci sta** nella piega —
> **eppure il guscio allo stesso raggio esterno si auto-interseca.** La differenza è il
> **build dual-ring per-segmento** (il `continuous?` gate, [loft.cljs:969](../src/ridley/turtle/loft.cljs#L969), esclude il dual-ring → shell flusha sezione + un sotto-mesh di
> corner separato, che si sovrappongono sulla pelle esterna) contro il **build continuo a
> sequenza unica** del pieno (tappa-2). Quindi la raccomandazione del report — *estendi la
> tappa-2 al dual-ring* — è **sostenuta dalla misura**, con un **distinguo importante**: il
> fix tocca **solo il build**, non la guardia, ma **non estende** la realizzabilità — un
> guscio con pelle esterna troppo larga per un corner stretto continuerà a piegare (è il
> gap del proxy shape-radius, voce separata fuori scope).

---

## Domanda 1 — dove stanno le coppie tri-tri: tra segmenti o tra ring?

**Misura (localizzazione per provenienza, profilo `circle 10 24`, np=24, layout
`[outer0..N inner0..N]` per ring):**

| caso | tri-tri | breakdown per skin | dist mediana dal vertice del corner |
|---|---|---|---|
| shell t=3 / FIX `(f10 th89 f20)` | 108 | **(outer,outer) 89 · (cap,outer) 19** · inner: **0** | ~15 (regione del corner) |
| shell t=3 / LONG `(f40 th89 f40)` | 16 | **(outer,outer) 16** · inner: **0** | ~16 |
| shell t=3 / dritto `(f30)` | 0 | — | — |

**Risposta:** le coppie sono **OUTER-skin vs OUTER-skin** (più poche cap-vs-outer ai bordi
dove il taglio incontra la cappa anulare di start), **addensate al corner**. La **pelle
interna non compare mai** (nessuna inner-inner, nessuna inner-outer). **NON è una piega del
ring interno.** È il meccanismo (a) del brief — *tra segmenti / build per-segmento* — sulla
pelle esterna; **non** il meccanismo (b) dual-ring/ring-interno.

**Il meccanismo che le genera — controllo decisivo (shell vs loft pieno allo stesso raggio
esterno):**

| rail | spessore | outer R | **shell** | **loft pieno @ outer R** | loft pieno @ R=10 |
|---|---|---|---|---|---|
| FIX  | 0.2 | 10.1 | **126** | **0** | 0 |
| FIX  | 1   | 10.5 | 100 | RIFIUTATO (guardia) | 0 |
| FIX  | 3   | 11.5 | 108 | RIFIUTATO (guardia) | 0 |
| FIX  | 6   | 13   | 118 | RIFIUTATO (guardia) | 0 |
| LONG | 0.2 | 10.1 | **4**  | **0** | 0 |
| LONG | 1   | 10.5 | 10 | 0 | 0 |
| LONG | 3   | 11.5 | **16** | **0** | 0 |
| LONG | 6   | 13   | 26 | 0 | 0 |

Le righe **smoking-gun**: su **LONG** (corner generoso) il loft pieno al raggio esterno è
**0 a ogni raggio** (10.1→13) — la geometria esterna **ci sta comodamente** — eppure il
guscio piega a **ogni** spessore (4→26, crescente con lo spessore). Idem su **FIX t=0.2**:
outer 10.1 ci sta (pieno=0), eppure shell=126. **La piega non è di larghezza** (provato: il
pieno allo stesso outer è pulito dove costruisce) **né del ring interno** (mai coinvolto):
è il **build dual-ring per-segmento** che, assemblando sezione + sotto-mesh di corner
separati, fa compenetrare la pelle esterna al corner. Il pieno evita la compenetrazione
perché costruisce **una sequenza continua** (tappa-2).

**Firma topologica:** `mesh-diagnose` resta **cieco** su tutti questi (nm=0, watertight=true
con tri-tri>0) — coerente col fatto che è auto-intersezione **geometrica**, non topologica.
Conferma il sospetto del brief: firma diversa dal pieno pre-tappa-2 (che era non-manifold)
→ **build rotto in modo diverso**, anche se la cura (build continuo) è la stessa famiglia.

**Questo dimensiona il fix:** è *tra-segmenti / build*, quindi **"estendi la tappa-2"** è la
direzione giusta; **non** serve gestione del ring interno (innocente).

---

## Domanda 2 — il build continuo si trasferisce al dual-ring?

**Sì, con plumbing minimo.** Il builder dual-ring **già** sa fare ciò che serve:

- `build-shell-sweep-mesh` ([extrusion.cljs:1973](../src/ridley/turtle/extrusion.cljs#L1973))
  spazza una **sequenza di anelli arbitraria** `[{:outer :inner :values} …]` (cella per cella
  tra anelli consecutivi, righe 1990-2022) ed è **cap-aware**: `caps?` ∈ `true/:start/:end/
  false` (righe 2026-2050). Identico per `build-shell-isocontour-mesh`
  ([:2090](../src/ridley/turtle/extrusion.cljs#L2090)) e `build-embroid-mesh`
  ([:2195](../src/ridley/turtle/extrusion.cljs#L2195)).

Quindi il build continuo = **splicare il bridge di corner nella UNICA sequenza dual-ring e
chiamare il builder UNA volta**, cappando solo i due veri estremi — esattamente ciò che fa
il pieno. La macchina dello splice **esiste già** ed è agnostica al tipo di elemento (mappe
`{:outer :inner :values}` o vettori di punti): [loft.cljs:971-983](../src/ridley/turtle/loft.cljs#L971).

**Dove diverge oggi (il punto unico da cambiare):** il flag

```clojure
continuous? (and (not dual-ring?) (not has-holes?))   ; loft.cljs:969
```

**esclude** il dual-ring, instradandolo al **flush per-segmento** ([loft.cljs:984-1002](../src/ridley/turtle/loft.cljs#L984)) che produce i sotto-mesh sovrapposti. Estendere la
tappa-2 = **far entrare il dual-ring nel ramo `continuous?`** e lasciare che `do-build`
cappi solo i veri estremi (la flush finale, [loft.cljs:799-806](../src/ridley/turtle/loft.cljs#L799)).

**Compatibilità coi joint-mode:** per `:flat` (default) `bridge-rings = []` → splice diretto.
Per `:round`/`:tapered`, `do-round-corners`/`do-tapered-corners` sono **disattivati**
(`(fn [& _] nil)`) per il dual-ring ([loft.cljs:678-685](../src/ridley/turtle/loft.cljs#L678)),
ma `midpoint-ring` **è** dual-ring-aware ([loft.cljs:686-694](../src/ridley/turtle/loft.cljs#L686))
e fornisce il `fallback-mid`. Quindi il continuo regge per tutti i joint-mode.

**Dimensione stimata: piccola** — il gate a `loft.cljs:969` + verifica delle cappe + che lo
splice di mappe regga (banale). I builder **non si toccano**. NON è una riscrittura del
builder seam-aware; è far percorrere al dual-ring il ramo continuo già scritto per il pieno.

> Onestà sull'inferenza: la misura **prova** che la causa è il build per-segmento (il pieno
> continuo allo stesso outer è pulito). Che il continuo dual-ring porti tri-tri a **0** è
> **fortemente indicato** (le due pelli, costruite come sequenze continue, sono singolarmente
> pulite ai loro raggi) ma **non costruito qui** (sarebbe modifica di produzione). La rete
> EXPECTED-RED è la verifica: dirà se il build continuo basta davvero.

---

## Domanda 3 — il fix tocca la guardia condivisa o solo il build?

**Solo il build.** Il fix cambia **come** shell assembla la mesh al corner (continuo invece
di per-segmento). **Non cambia cosa shell rifiuta**: la guardia di realizzabilità
(`validate-corner-realizability!`, condivisa, [loft.cljs:590](../src/ridley/turtle/loft.cljs#L590))
è **intatta** → l'invariante **extrude≡loft regge**, nessuna propagazione alla zona
condivisa. **Isolabile a shell** (il ramo dual-ring di `loft-from-path`).

**Distinguo cruciale — il fix NON estende la realizzabilità.** Alcuni gusci che la guardia
**ammette oggi** continueranno a piegare anche col build continuo: quando la **pelle esterna
è troppo larga** per il corner. Esempio misurato: shell t=3 su FIX ha outer 11.5; un loft
pieno di 11.5 su FIX è **RIFIUTATO** (non ci sta). La guardia ammette il guscio perché
dimensiona il miter sul **raggio BASE** (10), non sulla pelle esterna (11.5). Questo è il
**gap del proxy shape-radius** — voce **separata, fuori scope** (il brief esclude "il
raffinamento del proxy shape-radius").

Conseguenza per il fix e per la rete:
- dove la pelle esterna **ci sta** (LONG a ogni spessore; FIX con parete sottile): il build
  continuo **dovrebbe** portare a 0 → questi sono i casi-bussola.
- dove la pelle esterna **non ci sta** (t=3 su FIX): la guardia la ammette per il proxy, e il
  build continuo da solo **non** la salva (la geometria esterna non fa la curva). Resta al
  proxy. **Escluso dalla bussola** apposta.

Quindi: **il fix è isolabile al build di shell e non trascina la zona condivisa**; ma "shell
sano ai corner" al 100% richiede ANCHE il proxy (separato). Due cure distinte per due
meccanismi, tenuti distinti come (a)/(b) del corner-assembly.

---

## Deliverable — rete tri-tri per shell (in suite)

In `test/ridley/turtle/corner_self_intersection_net_test.cljs`:

- **`shell-straight-is-clean`** — GREEN: shell / dritto = 0 pairs (bussola verde).
- **`shell-corner-outer-geometry-fits-control`** — GREEN, invariante permanente: loft pieno
  al raggio esterno (10.1/FIX, 11.5/LONG) = 0 → la geometria esterna ci sta; isola il build.
- **`shell-corner-tri-tri-zero-EXPECTED-RED`** — **ROSSO-ATTESO** (la bussola del fix): asserisce
  il TARGET tri-tri=0 su due fixture a **puro difetto di build** (pelle esterna che ci sta):
  - shell `r=10 t=0.2 / FIX` (~126 pairs oggi) — corner stretto, parete sottile;
  - shell `r=10 t=3 / LONG` (~16 pairs oggi) — corner generoso.
  ROSSI oggi; **diventano VERDI quando il build continuo (tappa-2) raggiunge il dual-ring.**
- **`shell-corner-blind-to-mesh-diagnose-diagnostic`** — DIAGNOSTICO (stampa): per ciascun
  caso stampa `tri-tri / nm / watertight`, confermando la **cecità di mesh-diagnose**
  (tri-tri>0 con nm=0, watertight) — la rete tri-tri è l'unico testimone. Include la riga
  `build+proxy : shell t=3 / FIX` per documentare il caso misto (tenuto FUORI dalla bussola).
- **`shell-localization-diagnostic`** — DIAGNOSTICO: ripartizione delle coppie per skin
  (outer/inner/cap), conferma in suite che la pelle interna è innocente.

Le fixture rosso-attese sono scelte sul **puro difetto di build** (outer che ci sta), così
che la bussola diventi verde col **solo** fix del build; il caso misto col proxy è
deliberatamente diagnostico, non asserito, per non legare la bussola a una voce fuori scope.

---

## Verifica / chiusura

1. **Dove stanno le coppie** (Dom.1): **OUTER vs OUTER, al corner; inner innocente**
   (FIX 89 outer-outer +19 cap-outer; LONG 16 outer-outer; inner 0). Meccanismo: **build
   dual-ring per-segmento** (provato col controllo pieno@outer = 0 dove shell piega). È
   *tra-segmenti/build*, **non** ring-interno.
2. **Build continuo si trasferisce?** (Dom.2): **sì, plumbing minimo** — i builder
   ([extrusion.cljs:1973/2090/2195](../src/ridley/turtle/extrusion.cljs#L1973)) già spazzano
   sequenze arbitrarie e cap-aware; lo splice esiste ([loft.cljs:971-983](../src/ridley/turtle/loft.cljs#L971));
   il solo punto di divergenza è il gate [loft.cljs:969](../src/ridley/turtle/loft.cljs#L969).
   Dimensione **piccola**.
3. **Tocca la guardia o solo il build?** (Dom.3): **solo il build → isolabile a shell**,
   invariante extrude≡loft intatto. **Ma non estende la realizzabilità**: il gap del proxy
   (pelle esterna larga su corner stretto) resta, voce separata fuori scope.
4. **Rete tri-tri per shell** (deliverable): in suite — verde (dritto + controllo
   pieno@outer), **rosso-atteso** (shell t=0.2/FIX ~126, shell t=3/LONG ~16), diagnostici
   per cecità di mesh-diagnose (nm=0 con tri-tri>0) e localizzazione per skin.
5. **Raccomandazione sulla forma del fix**: la misura **sostiene "estendi la tappa-2"** —
   far entrare il dual-ring nel ramo `continuous?` ([loft.cljs:969](../src/ridley/turtle/loft.cljs#L969)),
   build unico, cappe solo ai veri estremi; builder invariati; dimensione piccola. **NON**
   serve gestione del ring interno (innocente). **Caveat**: cura il difetto di build (i casi
   rosso-attesi della rete); **non** copre il gap del proxy shape-radius (pelle esterna
   troppo larga su corner stretto), che è un secondo meccanismo **separato e fuori scope** —
   da affrontare a parte, come (a)/(b) del corner-assembly. Nessun tocco alla guardia
   condivisa.
