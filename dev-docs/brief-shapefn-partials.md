# Brief: forma parziale dei combinatori shape-fn

Seguito di `brief-loft-plus` (committato). Motivazione: dentro `transform->`, il passo loft oggi richiede una lambda cruda, illeggibile. La forma parziale rende leggibile il caso d'uso senza toccare alcun dispatch:

```clojure
;; oggi
(loft+ (fn [s t] (scale-shape s (+ 1 (* t 0.3)))) (f 30))
;; obiettivo
(loft+ (tapered :to 1.3) (f 30))
```

## Contesto

Un combinatore shape-fn chiamato **senza shape** restituisce la trasformazione nuda, cioè una normale `(fn [shape t] -> shape)`: esattamente il contratto che il modo legacy di `loft` (e il ramo `:else` di `loft+-impl`) accetta già. Per questo il dispatch di `loft` / `loft+` / `transform->` **non si tocca**: il lavoro sta tutto in `turtle/shape_fn.cljs`, più un binding in `operations.cljs` e un messaggio d'errore in `impl.cljs`.

Alternativa scartata (per il registro): "rebase" di uno shape-fn completo sulla shape entrante di `transform->`. Richiederebbe `:transform` nei metadata e produrrebbe un argomento morto nel sorgente (`(tapered (circle 20) :to 0.5)` con `(circle 20)` scritto ma scartato): magia non raccontabile, respinta.

Struttura attuale di un combinatore (es. `tapered`, `shape_fn.cljs:100`):

```clojure
(defn tapered
  [shape-or-fn & {:keys [to from] :or {to 0 from 1}}]
  (shape-fn shape-or-fn
            (fn [s t] (xform/scale s (+ from (* t (- to from)))))))
```

Forma bersaglio (schema, non prescrittivo alla lettera):

```clojure
(defn tapered
  [shape-or-fn & args]
  (let [partial? (keyword? shape-or-fn)
        {:keys [to from] :or {to 0 from 1}}
        (if partial? (apply hash-map shape-or-fn args) (apply hash-map args))
        xf (fn [s t] (xform/scale s (+ from (* t (- to from)))))]
    (if partial? xf (shape-fn shape-or-fn xf))))
```

Vincoli dello schema: la lambda `xf` è **una sola** per entrambi i rami (unica fonte di verità della trasformazione); la forma piena resta invariata nel comportamento (shape-fn identici a prima); il ramo parziale restituisce una fn **senza** metadata `:shape-fn` (deve risultare `(shape-fn? …) => false` e cadere nel ramo legacy).

## Lavoro richiesto

Piccoli passi, ogni commit verde.

**1. `turtle/shape_fn.cljs` — forma parziale per i combinatori profilo-safe.**
Combinatori interessati: `tapered`, `twisted`, `fluted`, `rugged`, `noisy`, `displaced`, `capped`.

- Per quelli a kwargs (`tapered`, `twisted`, `fluted`, `rugged`, `noisy`, `capped`): rilevamento `(keyword? shape-or-fn)`. Il destructuring attuale `& {:keys …}` non sopravvive alla keyword in prima posizione (le coppie si sfasano): passare a `& args` con smontaggio esplicito. Il parsing si ripete su sei combinatori: estrarre un piccolo helper condiviso (parse degli args con eventuale primo elemento keyword incluso), niente di più.
- `displaced` ha firma posizionale `[shape-or-fn displace-fn]`: la forma parziale è la **1-aria** `(displaced displace-fn)`.
- `capped` dipende da `sfn/*path-length*`: verificarne il comportamento nella forma parziale (vedi punto 2).
- Verificare le firme reali di `noisy` e `rugged` prima di applicare lo schema (potrebbero avere argomenti posizionali oltre alle kwargs).

**2. `editor/operations.cljs` — binding di `*path-length*` nel path legacy.**
Oggi solo `pure-loft-shape-fn` binda `sfn/*path-length*`; `pure-loft-path*` no, quindi una trasformazione nuda che ne dipende (`capped` parziale, o una lambda utente) vede il valore di default. Bindare `*path-length*` anche in `pure-loft-path*`, calcolato come già fa `pure-loft-shape-fn`. Nota deliberata: è un cambiamento semantico per le lambda legacy che leggessero `*path-length*` (prima vedevano il default, ora la lunghezza reale). È un upgrade, non una regressione: prima quel valore era inutile in modo legacy. Documentarlo nel commit.

**3. `editor/impl.cljs` — messaggio della guardia.**
La guardia di `loft+-impl` che rifiuta uno shape-fn come secondo argomento resta; il messaggio diventa didattico, suggerendo la forma parziale: es. "loft+: got a shape-fn as transform argument. Inside transform-> the profile comes from the previous step; use the partial form, e.g. (tapered :to 0.5), or a (fn [shape t] …)".

**4. Binding SCI.**
I combinatori sono già bindati; la forma parziale viaggia sugli stessi binding. Verificare che non serva nulla e che la fn restituita sia invocabile da codice interpretato (è il flusso legacy esistente, ma va confermato nel test headless).

**5. Documentazione.**
`docs/Spec.md`: la forma parziale nella sezione loft (modo legacy) e un esempio nella sezione Chaining con `loft+`. Schede Reference dei combinatori toccati: aggiungere la forma parziale a signature ed esempi. Scheda `loft-plus`: sostituire o affiancare l'esempio con lambda cruda con la forma parziale.

## Verifica

- **Regressione forma piena**: test shape-fn esistenti verdi; mesh di loft con shape-fn rappresentativi byte-identiche prima/dopo.
- **Natura del parziale**: `(shape-fn? (tapered :to 0.5))` è false; il valore è invocabile come `(xf shape t)`.
- **Equivalenza forte** (il test centrale): `(loft (circle 20) (tapered :to 0.5) (f 30))` produce mesh identica a `(loft (tapered (circle 20) :to 0.5) (f 30))`. Ripetere per `twisted` e per `capped` (dove l'equivalenza dipende proprio dal binding del punto 2).
- **Caso d'uso motivante**: la pipeline `transform->` con `(loft+ (tapered :to 1.3) (f 30))` fra due `revolve+` → manifold, cuciture pulite.
- **`displaced` parziale** 1-ario dentro un loft legacy.
- **Guardia**: il nuovo messaggio compare quando si passa uno shape-fn completo a `loft+` come trasformazione.
- **SCI headless**: forma parziale invocata da codice interpretato, non solo dal compilato.

## Fuori scope

- Composizione di parziali (combinatori che accettano una transform-fn come primo argomento): si aggiunge quando emergono casi concreti.
- Forma parziale di `morphed` (duplica il two-shape mode di loft).
- `shell`, `embroid`, `woven-shell`, `heightmap`, `profile`: fuori dalla famiglia profilo-safe.
- Il meccanismo di rebase (`:transform` nei metadata): respinto, non rimandato.
- Qualsiasi modifica al dispatch di `loft` / `loft+` / `transform->`.
