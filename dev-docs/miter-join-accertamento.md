# Accertamento miter-join — mappa dei cammini divergenti del corner

Branch: `miter-join-corner-construction` (da `8bcf163`, suite verde).
Natura: **accertamento, nessuna modifica al codice di produzione.** La misura di Domanda 3
è stata fatta con un test-scratch (tri-tri pierce test riusato da
`corner-self-intersection-net-test`), eseguito e poi **rimosso**; i numeri sono qui sotto.

> **Esito sintetico (da leggere prima di tutto).** Il corner è **troppo frammentato** per un
> miter-join unico, e — sorpresa che riorienta il brief — il difetto che il miter-join
> vorrebbe curare (la *piega da larghezza* del loft pieno) **sul cammino vivo è già
> interamente coperto dalla guardia di realizzabilità**: il loft pieno è pulito (tri-tri=0)
> a ogni corner realizzabile, e quelli troppo larghi sono **rifiutati**. Nel frattempo
> **shell ha un difetto di corner suo, indipendente dalla larghezza e dallo spessore**, che
> si auto-interseca a *ogni* corner (anche quelli che il loft pieno costruisce pulito) e che
> **un taglio al bisettore non toccherebbe**. Embroid è pulito. Quindi il valore di un
> miter-join sul pieno si riduce a *estendere la realizzabilità oltre la guardia* — e la
> guardia è **condivisa** da extrude+loft+shell+embroid+holed, perciò nemmeno il "join sul
> solo pieno" è davvero locale. La mappa onesta della frammentazione è l'esito di valore;
> la sequenza di costruzione unificata **non è sostenuta dalla struttura**.

---

## Domanda 0 — mappa dei cammini divergenti del corner

### Entry points (macro → operation → engine → builder)

| operazione | macro | operation fn | engine | builder al corner |
|---|---|---|---|---|
| **loft (pieno/taper/twist/two-shape)** | `loft` `macros.cljs:768` | `pure-loft-path` / `pure-loft-shape-fn` / `pure-loft-two-shapes` `operations.cljs:148/216/186` | **`loft-from-path` `loft.cljs:560`** | `build-sweep-mesh` (continuo) |
| **loft shell** | idem (shape-fn `shell`) | idem | `loft-from-path` | `build-shell-sweep-mesh` `extrusion.cljs:1973` / `build-shell-isocontour-mesh` `:2090` |
| **loft embroid** | idem (shape-fn `embroid`) | idem | `loft-from-path` | `build-embroid-mesh` `extrusion.cljs:2195` |
| **loft holed** | idem (profilo con `:holes`) | idem | `loft-from-path` | `build-sweep-mesh-with-holes` `:1065` |
| **extrude** | `extrude` `macros.cljs:737` | `pure-extrude-path` `operations.cljs:64` | **`extrude-from-path` `extrusion.cljs:1545`** | continuo, inline |
| **extrude holed** | idem | idem | `extrude-with-holes-from-path` `extrusion.cljs:1410` | ring-data accumulation |
| **revolve** | `revolve` `macros.cljs:842` | `pure-revolve` `operations.cljs:261` | `revolve-shape` `core.cljs:1944` | **nessun corner** (sweep rotazionale) → **FUORI** |
| **loft legacy** | (nessuna) | `implicit-finalize-loft` `implicit.cljs:712` | `finalize-loft` `loft.cljs:319` | **codice morto** |

### Il punto di biforcazione

`loft-from-path` è **un solo loop**, con una **parte condivisa** seguita da **helper polimorfici**.

**Condiviso a monte di tutti i cammini** (`loft-from-path`):
- `validate-rail-start-frame!` `loft.cljs:585`
- **`validate-corner-realizability!` `loft.cljs:590`** ← la **guardia**, alimentata da
  `analyze-open-path` + `shape-radius` (la STESSA grandezza di extrude)
- `analyze-loft-path` `:603`
- `compute-corner-data` `:714-753` → **shortening `r-p = r-n = calc-shorten-for-angle(θ,R) = R·tan(θ/2)`** `:738-744`
- posizionamento sezioni: `corner-base` `:860`, `next-start-pos` `:919`, `end-ring`/`next-start-ring`

**Biforcazione** (`loft.cljs:641-705`, gli helper scelti da `embroid-mode? / shell-mode? / has-holes? / :else`):
- `do-stamp` `:641` — come si campiona un anello: `{:outer :inner :values}` (shell/embroid) vs `stamp-shape` (pieno) vs `…-with-holes`
- `do-build` `:663` — come gli anelli diventano triangoli (i 4 builder sopra)
- `do-round-corners` / `do-tapered-corners` `:678-685` — **disattivati `(fn [& _] nil)` per dual-ring**
- **`continuous?` `:969`** = `(and (not dual-ring?) (not has-holes?))`
  - **pieno → splice continuo** `:971-983` (un'unica `build-sweep-mesh`, tappa 2)
  - **dual-ring / holed → flush per-segmento** `:984-1002` (section sub-mesh + **corner sub-mesh separato**, poi saldatura globale)

### Struttura — cosa è condiviso, dove si dirama

```
loft-from-path (vivo)
├─ CONDIVISO: rail-frame guard · realizability guard · shortening R·tan(θ/2) · corner-base · next-start-pos
└─ BIFORCA in do-stamp / do-build / continuous?:
   ├─ PIENO     → splice continuo  → build-sweep-mesh                 (tappa 2)
   ├─ SHELL     → flush per-seg     → build-shell-sweep / -isocontour  (corner sub-mesh)
   ├─ EMBROID   → flush per-seg     → build-embroid-mesh               (corner sub-mesh)
   └─ HOLED     → flush per-seg     → build-sweep-mesh-with-holes      (corner sub-mesh)

extrude-from-path (engine separato, stessa guardia/shortening/generate-*-corner-rings) → continuo
extrude-with-holes-from-path → separato
finalize-loft (P / calculate-loft-corner-shortening) → MORTO
revolve → nessun corner → fuori
```

**Conta dei cammini di corner distinti: almeno 5 vivi** (pieno-loft, shell-sweep, shell-iso,
embroid, holed-loft) **+ 2 extrude** (continuo, holed) **+ 1 morto**. Lo *shortening* è
condiviso; il *build del corner* diverge per ciascuno.

### Dov'è P (la premessa del brief)

`calculate-loft-corner-shortening` → `:intersection-point` (`loft.cljs:191-208`) **vive solo in
`finalize-loft`, codice morto.** Il cammino **vivo** (`loft-from-path`) **non calcola P**: usa
lo shortening **simmetrico** `R·tan(θ/2)` (`loft.cljs:742`). Idem extrude. **Quindi la premessa
"P già calcolato" è vera solo nel codice morto**; un miter-join sul cammino vivo dovrebbe
calcolare il piano bisettore / P daccapo (banale: ha `corner-base`, `old-heading`,
`new-heading` → bisettore = `normalize(new-heading − old-heading)` per `corner-base`).

---

## Domanda 1 — il corner del loft pieno oggi, passo per passo

Tutto in `loft-from-path` (`loft.cljs:560`), ramo `continuous?`.

1. **Radius di taratura**: `initial-radius = (shape-radius shape)` a t=0 (`:605`). **Costante**, non per-corner.
2. **Shortening** (`compute-corner-data` `:714-753`): per ogni segmento con corner,
   `turn-angle` dal dot dei heading (`:733`); `shorten = calc-shorten-for-angle(deg, R) = R·tan(θ/2)`;
   **`{:r-p shorten :r-n shorten}`** (`:742`) — **simmetrico**. (`R_p`/`R_n`/`P` di
   `calculate-loft-corner-shortening` **non sono usati qui**.)
3. **Distanza effettiva** (`total-effective-dist` `:757-773`): sottrae `prev-rn` e `r-p`.
4. **Per segmento** (`:834`): `effective-seg-dist = remaining-to-corner − r-p` (`:855-857`);
   `corner-base = position + heading·remaining-to-corner` (`:860`); anelli campionati `:866-884`.
5. **Al corner** (`:909`): `next-start-pos = corner-base + new-heading·r-n` (`:919`);
   `end-ring` = ultimo anello accumulato; `next-start-ring` stampato a `next-start-pos` (`:928`);
   `bridge-rings` da `joint-mode` (`:940-966`) — **`:flat` → `[]`** (collegamento diretto).
6. **Build (continuo)**: gli anelli `(end-ring … bridge … next-start-ring)` sono **splicati nella
   stessa sequenza** e si continua ad accumulare (`:974-983`); a fine loop **una sola**
   `build-sweep-mesh` con **cap solo ai due veri estremi** (`:799-806`).

**`P / R_p / R_n` evidenziati:** sul vivo, `R_p = R_n = R·tan(θ/2)` (`loft.cljs:742`), **niente P**.
Il corner del pieno è quindi un **miter piatto**: end-ring accorciato + next-start-ring (offset
`r-n`) connessi diretti, in **un'unica mesh continua** — identico in spirito a extrude.

---

## Domanda 2 — cosa rimpiazza il miter-join, e su quale cammino

**Linea di sostituzione sul pieno:** restano lo shortening (`r-p`/`r-n`), `corner-base`,
`next-start-pos`. Verrebbe sostituito il **bridge** (oggi: connessione piatta degli anelli
splicati, `:971-983`) con **taglio al bisettore + ricucitura**.

**Monte o valle?** Doppio:
- Il **build del corner** (lo splice, ramo `continuous?`) è **a valle** della biforcazione →
  un taglio sugli anelli/mesh del pieno è **gatabile al solo pieno** (shell/embroid/holed
  restano intoccati).
- **Ma** la **guardia** (`validate-corner-realizability!` `loft.cljs:590`, e gemella in
  extrude `:1571`) è **a monte e condivisa**. Se il join deve **costruire ciò che oggi è
  rifiutato**, la guardia va rilassata — e rilassarla tocca **tutti** i cammini, oltre a
  rompere l'invariante "extrude e loft rifiutano identicamente" che la rete asserisce.

**Costruzione unica vs condizionale:** un corner **dolce** non ha materiale da tagliare; il
loft pieno **già lo costruisce pulito** (misurato tri-tri=0). La piega esiste **solo oltre
soglia**, e quei casi sono **oggi rifiutati**. Quindi il join ha valore **solo oltre la
soglia di auto-intersezione** → **conditional** (join quando `effective-dist < 0`, lasciando i
corner dolci sullo splice continuo attuale). Razionale: non rischiare di degradare i corner
dolci **misurati puliti**. Un "always-join, no-op sui dolci" introdurrebbe un taglio/weld
dove oggi non serve, sul codice che funziona.

**Significato per shell/embroid:** poiché il build-change è a valle (gatabile al pieno),
**shell/embroid non sarebbero toccati** da un join sul solo pieno → restano rifiutati per
larghezza grossa **e** mantengono il loro difetto di corner (Domanda 3). Un taglio al
bisettore su un **muro forato** (embroid) o un **guscio cavo** (shell) è geometria diversa
(due pelli, vuoto interno) e non è il rimedio al loro difetto.

---

## Domanda 3 — shell ed embroid soffrono la stessa auto-intersezione? **MISURATO**

Fixture: profilo/parete su rail con corner; tri-tri = coppie di triangoli non-adiacenti che
si trafiggono (Möller–Trumbore strict-interior, lo strumento della rete). Rail:
`FIX = (f 10)(th 89)(f 20)` (gambe corte), `LONG = (f 40)(th 89)(f 40)` (gambe generose),
`STR = (f 30)` (dritto, controllo).

| caso | tri-tri |
|---|---|
| **CONTROLLI dritti** | |
| plain loft circ10 / STR | **0** |
| shell circ10 / STR | **0** |
| embroid w20 / STR | **0** |
| **corner** | |
| plain loft circ10 / FIX | **0** |
| **shell circ10 / FIX** | **108** |
| embroid w20 / FIX | **0** |
| shell circ10 / **LONG** (corner generoso) | **16** |
| embroid w20 / LONG | **0** |
| **over-width (guardia)** | |
| plain loft circ21 / FIX | **RIFIUTATO** ("too sharp for how wide") |
| shell circ21 / FIX | **RIFIUTATO** |
| embroid w40 / FIX | **RIFIUTATO** |
| shell circ21 / LONG (realizzabile per la centerline) | **18** |
| **sensibilità allo spessore** (shell circ10 / FIX) | |
| th=0.2 / 1 / 3 / 6 | **126 / 100 / 108 / 118** |

**Letture decisive:**
1. **Il loft pieno NON si auto-interseca** su nessun corner realizzabile (0 ovunque); gli
   over-width sono **rifiutati**. Sul cammino vivo, **la piega-da-larghezza del pieno è già
   interamente coperta dalla guardia**. (La "malattia" del brief è curata, sul pieno.)
2. **Shell si auto-interseca a OGNI corner** (FIX 108, LONG 16) ma è **pulito sul dritto (0)**
   → è un difetto **del corner**, non del build in sé.
3. **Indipendente dallo spessore** (0.2→126 ≈ 6→118): **non** è l'offset della parete (non è
   il proxy `shape-radius` che ignora ±t/2). È **intrinseco al build dual-ring per-segmento**
   del corner — la pelle interna del corner-bridge ripiega su sé stessa qualunque sia lo
   spessore. **Meccanismo diverso** dalla piega-da-larghezza del pieno.
4. **Indipendente dalla larghezza/generosità**: shell circ10 si interseca anche su LONG (16),
   dove il pieno è 0. Un join "per larghezza" sul pieno **non curerebbe** questo.
5. **Embroid è pulito ovunque** testato (build a marching-triangle del pannello aperto regge
   il corner). Non esibisce il difetto.
6. **La guardia scatta identica** per shell ed embroid (over-width rifiutati) — conferma che è
   **a monte della biforcazione dual-ring**.

**Regime di protezione — la decisione:** il brief ipotizzava "pieno riparato vs shell/embroid
rifiutati-non-riparati". La realtà è **peggiore e diversa**: shell **non** è "rifiutato e
basta", è **attivamente rotto** (auto-intersecante) **oggi**, su corner che la guardia
**passa** (LONG 16, FIX 108). È un **bug preesistente**, indipendente dal miter-join, che il
miter-join (taglio al bisettore per larghezza) **non tocca**. Embroid è a posto. Quindi:
- costruire il join **solo sul pieno** porterebbe il pieno a "esteso/riparato" mentre **shell
  resta rotto a ogni corner** — un'incoerenza **maggiore** di quella prevista, e non sanabile
  dal join stesso.
- shell richiede un **fix proprio** — **non un miter-join**, ma **estendere la tappa-2 (build
  continuo) al dual-ring**, dato che la causa-radice è proprio l'aver lasciato shell/embroid/
  holed sul vecchio flush per-segmento (`continuous?` gatato al pieno, `loft.cljs:969`).

---

## Domanda 4 — dove vive la ricucitura e che rischio porta

Sul pieno, ora **continuo** (`:805`, `:971-983`), il taglio+ricucitura può stare:
- **(a) prima del build, sugli anelli** — preserva la proprietà "una mesh, manifold per
  costruzione". **Rischio/difficoltà**: il taglio al bisettore di un tubo sweepato **non è un
  anello** (è un piano che taglia trasversalmente più anelli); esprimerlo in topologia ad
  anelli è il pezzo difficile.
- **(b) dopo il build, mesh-surgery** — taglia la mesh col piano bisettore, **ri-salda** le due
  gambe. È quanto fatto nell'esperimento "due tubi" (cut + union manifold): **coerente con
  l'esperimento**, più diretto, ma reintroduce un **boolean/weld al corner** — esattamente la
  zona di T-junction / non-manifold che le **tappe 1-2** hanno bonificato. **Rischio: nm>0**
  di ritorno (la rete `loft-corner-assembly-net` è lì apposta).

**`:centered? false` (secondo meccanismo).** Osservato, non affrontato: il test
`centered-false-corner-self-intersects` mostra che vive nel **percorso di stamp**
(`compute-stamp-transform` / `stamp-shape`), **a monte** e **fuori** dalla zona di assemblaggio
del corner dove starebbe la ricucitura. **Il join non lo tocca** (né lo risolve né lo
complica). Resta indagine separata.

---

## Domanda 5 — confine con la guardia di realizzabilità

Oggi la guardia rifiuta `effective-dist < 0` ⇔ `R·tan(θ/2) > segmento` (`extrusion.cljs:362`,
soglia `corner-realizability-tol = -1e-4`). Se il join costruisce questi casi, il **nuovo
confine** è dove **il taglio rimuove l'intera gamba** — il piano bisettore oltrepassa l'anello
estremo del segmento, cioè quando l'accorciamento eguaglia/supera la **lunghezza intera** del
segmento da entrambi i lati (oltre, nemmeno il join lascia materiale). Soglia ≈
`R·tan(θ/2) ≳ dist` su entrambe le gambe (da derivare con precisione).

**Test da convertire (RED→GREEN) — ma solo sul cammino dove il join è costruito:**
- `corner-self-intersection-net-test/guard-refuses-overmitre` `:204` — R=21@89, R=50@89, R=21@120
- `extrusion-test/extrude-very-short-segments` `:234`
- `loft-nm-isolation-test/extrude-sharp-corner-is-refused` `:39`
- `loft-corner-assembly-net-test/mech-7-variant-sharp-corner-is-refused` `:136`,
  `visible-manifestation-sharp-corner-now-refused` `:263`

**Il vincolo che rompe la gatabilità:** questi test asseriscono il rifiuto **per extrude E
loft insieme** (la guardia è condivisa, e la rete impone "rifiutano identicamente"). Se il
join è costruito **solo sul loft pieno continuo**, le righe **extrude** devono **restare
rifiutate** → o **(a)** il join si costruisce **per entrambi** gli engine (extrude-from-path +
loft-from-path), oppure **(b)** si accetta la divergenza loft-costruisce / extrude-rifiuta,
**rompendo l'invariante** che la rete protegge. **Quindi l'estensione di realizzabilità NON è
gatabile pulita**: la soglia **non** vale "per tutti i cammini" automaticamente, e separarla
per cammino rompe un invariante esistente.

---

## Verifica / chiusura

1. **Mappa & biforcazione** (Dom.0): cammino vivo = `loft-from-path`; condiviso fino a
   guardia+shortening+posizionamento; biforca in `do-stamp`/`do-build`/`continuous?`
   (`loft.cljs:641-705,969`). ≥5 cammini di corner vivi + 2 extrude + 1 morto. **P vive nel
   codice morto** (`finalize-loft`); il vivo usa `R·tan(θ/2)` simmetrico.
2. **Corner pieno passo-passo** (Dom.1): shortening simmetrico `R_p=R_n=R·tan(θ/2)`
   (`loft.cljs:742`), splice continuo, cap solo ai veri estremi. **Niente P.**
3. **Linea di sostituzione** (Dom.2): build-change **a valle** (gatabile al pieno), ma
   **relax-guardia a monte e condiviso**. Conditional (join oltre soglia), non always.
4. **Shell/embroid** (Dom.3, **misurato**): pieno **già coperto dalla guardia** (0 / rifiutato);
   **shell rotto a OGNI corner** (108/16), **width- e thickness-independent** → difetto diverso,
   **non curato da un join per larghezza**; **embroid pulito**. Shell non è "rifiutato non
   riparato" ma **attivamente auto-intersecante oggi** → incoerenza maggiore; serve fix proprio
   (estendere tappa-2 al dual-ring, **non** un miter-join).
5. **Unica vs condizionale** (Dom.2): **condizionale**, per non degradare i dolci puliti.
6. **Ricucitura** (Dom.4): (a) sugli anelli (manifold-safe ma taglio difficile da esprimere) vs
   (b) mesh-surgery (come l'esperimento, ma re-weld → rischio nm>0 nella zona tappe 1-2).
   `:centered? false` è fuori zona, intoccato.
7. **Confine** (Dom.5): nuova soglia ≈ "il taglio svuota la gamba"; lista test da convertire
   sopra; **non vale per tutti i cammini** e separarla rompe l'invariante extrude≡loft.

### Verdetto sulla frammentazione (cautela esplicita del brief)

**Mi fermo alla mappa.** La struttura **non sostiene un miter-join unico**:
- il difetto bersaglio (piega-da-larghezza del pieno) **sul cammino vivo è già curato** dalla
  guardia → il join sul pieno servirebbe **solo** a estendere la realizzabilità, e ciò tocca
  una **guardia condivisa** + l'invariante extrude≡loft (non locale);
- il cammino **davvero rotto** (shell) ha un difetto **diverso** (build dual-ring per-segmento,
  width/thickness-independent) che **il taglio al bisettore non risolve** — va finito il
  **build continuo (tappa 2) per il dual-ring**, lavoro distinto;
- embroid non ne ha bisogno.

La decisione torna allo sviluppatore: **non "come costruisco il join", ma "su quanti cammini,
in che ordine, e se ne vale la pena"**. Raccomandazione (offerta, non forzata):
1. **Priorità più alta del miter-join**: sanare il corner di **shell** estendendo la tappa-2
   (splice continuo) al dual-ring — è la causa-radice misurata, ed è un bug attivo oggi.
2. Il **miter-join** (taglio al bisettore) ha senso **solo** se "estendere la realizzabilità
   oltre la guardia" è un obiettivo dichiarato, e va fatto **insieme** su guardia condivisa +
   extrude + loft pieno, non come patch locale.

Una sequenza di costruzione unificata (piano di taglio → taglio esatto → ricucitura, criteri
bisettore/P · tri-tri=0 · nm=0) **non viene proposta**, perché la mappa **non la sostiene**:
sarebbe forzare un'unificazione che la struttura del codice non ha.
