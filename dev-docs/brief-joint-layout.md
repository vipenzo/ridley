# Brief: `layout-anchors` — numero e posizione delle giunzioni su una faccia di taglio

## Contesto

Decisione del 2026-09-21 (Vincenzo), a valle di `dev-docs/brief-split-anchors.md`
(FATTO: ogni taglio di `mesh-split` lascia su entrambi i pezzi un anchor col nome del
mark, posizione sul piano, heading uscente, up del taglio). Il passo successivo verso
le giunzioni per rimontare i pezzi stampati: quando la faccia di taglio è ampia, un
perno solo non basta e distanziare a mano più `on-anchors` è tedioso. Serve un
automatismo che **misuri** la faccia e **proponga** numero e posizione delle giunzioni.

Principio, lo stesso del `:phases` della gabbia: il software misura e suggerisce, non
costruisce. La funzione ritorna anchor; i perni li scrive Vincenzo con i mezzi ordinari
(`on-anchors`, `attach`, `mesh-difference`). Il contratto per una giunzione built-in,
se mai, verrà dopo gli esempi a mano.

Idea di Vincenzo, confermata sul sorgente: per la posizione si usa il Voronoi, o meglio
la sua iterazione (Lloyd): dato N, i semi si assestano nei baricentri delle proprie
celle e si distribuiscono da soli in una L, in una ciambella, in fila su una faccia
stretta. `src/ridley/voronoi/core.cljs` ha già `generate-seeds`, `compute-voronoi-cells`
(d3-delaunay) e `lloyd-relax`, oggi privati e cuciti su `voronoi-shell`.

Fatti verificati sul sorgente prima di questo brief:

- Il contorno della faccia di taglio si legge con `slice-mesh` un pelo dentro il pezzo:
  `(turtle piece :at :cut (f -0.01) (slice-mesh piece))` — sul piano esatto restituisce
  nulla (piano coincidente con la faccia). Ritorna un vettore di shape 2D nel frame
  dell'anchor (X = right, Y = up), una per isola, con `:preserve-position? true`.
- `shape-offset` accetta un delta (negativo = ritiro). Da verificare che su una forma con
  più isole o con un collo stretto restituisca più shape, e non solo la prima.
- `area` misura una FACCIA DI MESH (`(area mesh face-id)`), non una shape. L'area di
  una shape esiste solo come `signed-area-2d`, privata e duplicata in `clipper/core` e
  `voronoi/core`. Va esposta una volta sola.
- `lloyd-relax` ritaglia ogni cella sulla forma con `clipper/shape-intersection`, che
  restituisce UNA shape: su una forma concava una cella può risultare spezzata in due
  pezzi scollegati e il seme finirebbe nel baricentro del pezzo sbagliato, o fuori.
- `clipper/point-in-polygon?` esiste ed è pubblica.

## Lavoro richiesto

Due stadi con una responsabilità ciascuno, più il mattone 2D che serve a entrambi.

### Parte 0: i mattoni 2D mancanti

- **`shape-area`** pubblica: area di una shape (positiva, fori sottratti). Le due
  `signed-area-2d` private convergono su una sola implementazione.
- **`spread-points`**: `(spread-points shape n & {:keys [seed iterations]})` → vettore
  di N punti `[x y]` dentro la shape, distribuiti con Lloyd. Deterministica a parità di
  `seed` (default 0): rieseguendo lo script i perni non si spostano. È `lloyd-relax`
  resa pubblica e corretta:
  - le celle si ritagliano su TUTTE le isole dell'intersezione, e a ogni seme si
    assegna il baricentro del pezzo che lo contiene (`point-in-polygon?`); se nessun
    pezzo lo contiene, resta dov'è;
  - con N = 1 Lloyd degenera nel baricentro della shape, che su una C cade fuori: in
    quel caso il punto è il punto interno più lontano dal bordo (campionare l'interno,
    massimizzare la distanza dal contorno). Stesso fallback per ogni seme che a fine
    iterazioni non sta dentro;
  - `voronoi-shell` la riusa senza cambiare comportamento (stesso seed → stessa shell).

### Parte 1: la zona ammessa

`(joint-zone piece anchor & {:keys [inset]})` → vettore di shape 2D nel frame
dell'anchor: il contorno della faccia di taglio ritirato di `inset`.

- `piece` è una mesh uscita da `mesh-split` (`(:behind halves)`, `:piece-2` di uno
  `split-tree`, o il suo nome registrato); `anchor` è il nome dell'anchor di taglio su
  quel pezzo (`:cut`, o il mark: `:cut-1`). Insieme scelgono «di questo pezzo, questa
  faccia»: un pezzo di mezzo ne ha due. Sono gli stessi due argomenti di
  `(turtle piece :at anchor …)`, che è ciò che la funzione fa dentro.
- `inset` = distanza minima del centro di una giunzione dal contorno. Raggio del perno
  e parete minima contano solo come somma (decisione di Vincenzo: raggio 5 e parete 2
  fanno lo stesso effetto di raggio 0 e parete 7), quindi un parametro solo. Nessun
  default: dipende da materiale e stampante e va dichiarato (politica di `:phases`).
- Un'isola che sparisce nel ritiro non è un errore: quella parte della faccia è troppo
  stretta per quell'inset. Il risultato può essere vuoto.
- Il contorno si legge con il rientro di un pelo (Parte 0 del brief precedente). Se
  `slice-mesh` acquisirà un fallback automatico per il piano coincidente, `joint-zone`
  lo usa e il rientro sparisce da qui.

### Parte 2: la proposta — `layout-anchors`

Non è specifica dei giunti (decisione di Vincenzo): divide una faccia in punti ben
distribuiti secondo certi criteri e restituisce **anchor**, cioè pose 3D nel mondo
sullo stesso piano dell'anchor dato, con il suo heading e il suo up.

`(layout-anchors piece anchor & {:keys [inset spacing n seed prefix]})` → mappa
`{:pin-1 pose :pin-2 pose …}` (prefisso da `:prefix`, default `"pin"`), con metadati
`:layout {:zones [shape …] :rejected [{:area …} …]}` sulla mappa.

- **Numero**: per ogni isola della zona ammessa, `n_i = max(1, round(area_i / spacing²))`
  con `spacing` = distanza tipica tra giunzioni, dichiarata. `n` esplicito sovrascrive il
  totale (ripartito tra le isole in proporzione all'area, minimo 1 per isola).
- **Posizione**: `spread-points` isola per isola, poi ogni `[x y]` è riportato nel mondo
  con il frame dell'anchor (`position + x·right + y·up`).
- **`:rejected`**: le isole della faccia sparite nel ritiro, con la loro area, così il
  messaggio è «questo rebbio è troppo stretto per un inset di 5», non un silenzio.
- **Pose nel mondo, quindi valide per entrambi i pezzi**: la stessa posa serve al pezzo
  e al suo gemello, e il problema dello specchio in x sparisce. Un cilindro centrato
  sulla posa sta metà in uno e metà nell'altro; si sottrae da tutti e due.
- La funzione **non costruisce niente**. L'uso previsto:

  ```clojure
  (def L (layout-anchors (:behind halves) :cut :inset 5 :spacing 15))
  (register pins (on-anchors L "pin" :align (cyl 3 10)))
  (register B (mesh-difference (:behind halves) pins))
  (register A (mesh-difference (:ahead halves) pins))
  ```

  Perché questo funzioni, **`on-anchors` accetta come bersaglio anche una mappa nuda di
  anchor** (`{nome pose …}`), oltre a mesh, nome registrato e path: estensione di
  `on-anchors-resolve-target`, con la guardia che una mappa con `:vertices` o `:type
  :path` resta quello che era. Vale anche per `(anchors L)` e per `move-to :at` dove
  ha senso; non è richiesto oltre `on-anchors`.

### Parte 3: dove passa

- `spread-points` e `shape-area` in `voronoi/core` e `clipper/core` (o `turtle/shape`),
  esposte in `bindings.cljs` con `^:export`, mirror in `sci_harness.cljs`.
- `joint-zone`/`layout-anchors` in `editor/implicit.cljs` accanto a `implicit-slice-mesh`,
  perché leggono la tartaruga e l'anchor; `on-anchors-resolve-target` estesa lì.
- Nessuna modifica a `mesh-split`, agli anchor di taglio, a `edit-mesh-split`.

## Verifica

Allo schermo, non solo a REPL, con `register` (senza non si vede niente):

1. Rettangolo: `(box 20 60 40)` tagliato a metà; `layout-anchors` con inset 5 e
   spacing 15 propone 2–3 anchor in fila lungo il lato lungo, tutti a ≥5 dal bordo.
   `(on-anchors L "pin" :align (cyl 3 10))` mette i cilindri a cavallo del piano;
   sottratti da entrambi i pezzi, i fori coincidono.
2. L: un pezzo a L tagliato in modo che la faccia sia a L; i punti finiscono in
   entrambi i bracci, nessuno nell'angolo interno fuori dalla forma.
3. Ciambella: un tubo tagliato trasversalmente; la faccia è un anello; i punti si
   distribuiscono lungo l'anello, nessuno nel foro.
4. C con un solo perno: `:n 1` su una faccia a C; il punto sta dentro la C, non nel
   baricentro vuoto.
5. Troppo stretta: faccia 6 mm di larghezza con inset 5; mappa vuota, `:rejected`
   con l'isola e la sua area; nessun errore.
6. Determinismo: due chiamate uguali danno le stesse pose; cambiare `:seed` le sposta.
7. Non regressione: `voronoi-shell` con seed fisso dà la stessa shell di prima del
   brief (confronto dei punti dei fori).
8. Il primo uso reale: uno dei pezzi tagliati da Vincenzo, perni costruiti con
   `on-anchors` sulla mappa proposta, sottratti da entrambi i pezzi. Se la scrittura
   dell'uso (Parte 2) è scomoda, dirlo.

## Fuori scopo

Costruzione automatica dei perni, forma della giunzione (cilindro, tenone, battuta,
perni inclinati rispetto al filamento), anteprima nell'editor, contratto joint-fn.
Tutto dopo, a valle degli esempi scritti a mano.
