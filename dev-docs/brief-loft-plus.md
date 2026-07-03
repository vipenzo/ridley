# Brief: `loft+` (chaining variant di loft)

Voce Roadmap 1.5. Prerequisito: fix extrude-holed committato (il caso holed-con-corner di loft+ cavalca il path loft-holed).

## Contesto

`extrude+` e `revolve+` restituiscono `{:mesh :end-face :start-face}` per il chaining via `transform->`. Manca la variante per `loft`. Il caso d'uso: un tratto rastremato seguito da un gomito, senza dover calcolare a mano la shape finale del loft e riposizionare la tartaruga.

La catena esistente, che va rispecchiata:

- Chaining: `extrude+-impl` (`editor/impl.cljs:146`), `revolve+-impl` (`impl.cljs:212`), `transform->step` / `transform->impl` (`impl.cljs:~335-380`). Macro in `editor/macros.cljs:858-890` (il parser di `transform->` riconosce `extrude+` / `revolve+` e gestisce `:mark` / `:mark-cap`).
- Loft: macro `loft` (`macros.cljs:768`, con detection `mvmt?` per distinguere dispatch-arg da movimenti) → `loft-impl` (`impl.cljs:50`, dispatch shape-fn / two-shapes / legacy transform-fn) → `ops/pure-loft-shape-fn` / `pure-loft-two-shapes` / `pure-loft-path` (`editor/operations.cljs:148-228`) → `turtle/loft-from-path` (`turtle/loft.cljs:560`).
- `pure-loft-path` costruisce un initial-state fresco dalla tartaruga corrente, chiama `loft-from-path`, poi `combine-meshes` + `merge-vertices 1e-4` → **una singola mesh** anche nei casi multi-sub-mesh. Il final-state di `loft-from-path` (posizione/heading a fine path) viene oggi **scartato**: `loft+` è il primo consumatore che ne ha bisogno.

### Vincolo centrale: end-face = ultima sezione emessa, MAI rivalutata

La formulazione della Roadmap ("valutare la shape-fn a t=1") nasconde una trappola. In `loft-from-path` l'ultimo ring è stampato a `clamped-t = (min 1 (/ taper-at total-effective-dist))`, dove `taper-at` accumula gli `effective-seg-dist` del ring loop. Ma i due calcoli usano clamp **diversi**:

- `total-effective-dist` (denominatore): per segmento, `(max 0.001 (- seg-dist prev-rn r-p))`
- ring loop (numeratore): per segmento, `(max min-step (- remaining-to-corner r-p))` con `min-step = remaining/seg-steps`

Su path con corner e segmenti corti i due clamp divergono → il t dell'ultimo ring può essere **< 1**. Inoltre ai corner il profilo viene "tenuto" al t del corner. Rivalutare la shape-fn a t nominale = 1 produrrebbe una sezione diversa dall'ultima faccia reale della mesh → crack alla cucitura del segmento successivo del `transform->`.

Cura: **threadare fuori dal loop la 2D shape effettivamente stampata sull'ultimo ring** (ogni chiamata a `do-stamp` riceve una `transformed-shape`: tracciarla accanto ad `acc-rings`, inclusi i `next-start-ring` del branch corner). Il valore threadato è l'end-shape; nessuna rivalutazione.

Nota simmetrica: la **start-shape** può invece essere `(transform-fn shape 0)` in sicurezza, perché il primo ring è a `clamped-t = 0` esatto per costruzione (`i = 0`) e le transform-fn sono pure.

## Lavoro richiesto

Piccoli passi, ogni commit verde.

**1. `turtle/loft.cljs` — esporre l'end-shape.**
Nel loop di `loft-from-path`, threadare la 2D shape dell'ultimo ring stampato (tutti i branch: segmento liscio, corner continuous, corner per-segment). Nel flush finale, `assoc` sul final-state una chiave effimera, es. `:loft-end-shape`, consumata dal chiamante. Vincolo di regressione: per il plain loft il comportamento osservabile non cambia — mesh byte-identiche, la chiave in più sullo state è invisibile agli usi esistenti.

**2. `editor/operations.cljs` — variante ricca del pure-loft.**
`pure-loft-path*` (nome indicativo) che restituisce `{:mesh m :start-shape s0 :end-shape s1 :end-state {:position p :heading h}}`; `pure-loft-path` diventa `(:mesh (pure-loft-path* …))` così i due condividono lo stesso code path (niente duplicazione). Stesso trattamento per gli adapter: `pure-loft-shape-fn` (attenzione a preservare il binding `sfn/*path-length*`) e `pure-loft-two-shapes` (l'end-shape threadata copre automaticamente il caso resampled/aligned: è la lerp effettiva dell'ultimo ring). Input vettoriale di shapes: parity con `extrude+` (vettore di risultati).

**3. `editor/impl.cljs` — `loft+-impl`.**
Speculare a `extrude+-impl`, con dispatch identico a `loft-impl` (2-arg shape-fn, 3-arg cond). Ritorna:

```clojure
{:mesh <mesh con :creation-pose>
 :start-face {:shape <start-shape> :pose <pose iniziale>}
 :end-face   {:shape <end-shape threadata> :pose {:pos <end-state position>
                                                  :heading <end-state heading>
                                                  :up <derive-end-up come extrude+>}}}
```

Opzioni `:mark` / `:mark-cap` come `extrude+`. Due guard con errore chiaro:
- se il profilo è shell/embroid (probe con `:shell-mode` / `:embroid-mode`): rifiutare, semantica di chaining indefinita;
- nel 3-arg, se il *secondo* argomento è una shape-fn (caso che dentro `transform->` nasce da un uso ingenuo): rifiutare spiegando di usare una transform-fn `(fn [s t] …)` o una target shape.

**4. `editor/macros.cljs` — macro e transform->.**
Macro `loft+` speculare a `loft` (stessa `mvmt?` detection, stesso `add-source` con `:op :loft+`). Nel parser di `transform->`, aggiungere il case `loft+`: la shape entrante viene iniettata come first-arg, quindi il form step è `(loft+ <transform-fn-o-target-shape> movements…)` con `:args [dispatch-arg (path …)]` e gestione `:mark` / `:mark-cap` come gli altri. In `transform->step`, aggiungere il dispatch `:loft+`.

**5. Documentazione.**
Aggiornare la sezione Chaining di `docs/Spec.md` (~riga 1370) con `loft+`, incluso un esempio dentro `transform->`. Se esiste una scheda Reference per la famiglia chaining (grep su extrude+ sotto `docs/manual/`), aggiungere `loft+` lì; altrimenti segnalarlo senza crearla.

## Verifica

- **Regressione plain loft**: test esistenti verdi; mesh di un set di loft rappresentativi (liscio, corner, bezier, holed senza corner) byte-identiche prima/dopo il punto 1.
- **Seam test**: `(loft+ (tapered (circle 20) :to 0.5) (f 30))` seguito da `extrude+` dall'`:end-face` → i vertici della sezione finale del loft coincidono col primo ring dell'extrude entro 1e-6; l'union è manifold.
- **Trap test del clamp** (falsificazione del vincolo centrale): path con corner e segmenti corti che attivano il clamp `min-step`; asserire che `(:shape (:end-face …))` è **uguale alla shape threadata dell'ultimo ring**, e che nel caso costruito differisce da `(transform-fn shape 1)` valutata a t nominale. Se non si riesce a costruire un caso in cui i due divergono, documentare il tentativo: la falsificazione è un risultato valido, ma il meccanismo threadato resta comunque (è corretto per costruzione).
- **Pipeline del caso d'uso Roadmap**: `(transform-> (circle 20) (loft+ (fn [s t] (scale-shape s (- 1 (* 0.4 t)))) (f 30)) (revolve+ 45 :pivot :left) (extrude+ (f 20)))` → manifold, watertight, visivamente continuo.
- **Two-shape mode**: `(loft+ (rect 20 20) (circle 10) (f 40))` → end-face con point count del resampling; chaining pulito col segmento successivo.
- **`:mark` / `:mark-cap`**: anchor registrato correttamente su `:end-cap`.
- **Guard**: shell come profilo e shape-fn come secondo argomento producono i due errori con messaggio esplicativo.
- **Holed con corner**: test da aggiungere solo a fix extrude-holed committato; holed senza corner in verifica ordinaria.

## Fuori scope

- `loft-n+` (variante con step count custom): aggiungibile dopo sulla stessa infrastruttura.
- Supporto shell/embroid nel chaining (solo guard con errore).
- Qualsiasi modifica al comportamento di `loft` / `loft-impl` / joint-mode.
- Adaptive loft step density (Roadmap 2.4).
- Il fix extrude-holed stesso (brief separato, prerequisito).
