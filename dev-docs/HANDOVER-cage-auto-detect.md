# HANDOVER — riconoscimento automatico della gabbia

## In una riga

Dare alla GABBIA quello che il piatto ha già: premi un tasto, il programma trova
i dischetti nella foto, capisce quale è quale, e registra. Vincenzo ha dato il
via il 2026-08-23, dopo che il gesto manuale si è dimostrato chiuso.

## Perché, e non è un lusso — è misurato

Tutto quanto segue viene dai dati veri di Vincenzo, non da ragionamenti. Foto
`~/Pictures/RidleyScan/Presa/IMG_9014.jpeg` (gabbia ⌀176, 4032×3024 raster con
EXIF Orientation 6, quindi **3024×4032 in coordinate di visualizzazione**, che
è il sistema in cui stanno i pick).

1. **I click dell'utente sono impeccabili.** Dodici su dodici centrati sui
   dischetti, ritrovati dal rilevatore entro 0.7–1.9px. Il problema non è mai
   stata la mano.
2. **Una corona da sola non può identificarsi.** Dodici mark equidistanti sono
   invarianti per rotazione e dall'altra faccia si leggono specchiati: **tutte
   e 48 le riletture della corona X danno lo stesso rms, 32.5px**.
3. **La posa da un anello solo non colloca gli altri.** Nove punti di una
   corona (indice compreso) chiudono a 5.15px e mettono i mark degli altri due
   anelli a **130–260px** dai dischetti veri: una corona è complanare, la posa
   è precisa nel suo piano e vaga fuori.
4. **Un solo punto di un secondo anello fa esplodere il fit**: 5.15px →
   **1008px**. Dodici coplanari più uno: il punto fuori piano porta tutta la
   profondità e tutto il proprio errore. È la famiglia del guasto già
   documentata in `HANDOVER-registration-cage.md` (foto 5, 9736px).
5. **L'utente non può rompere il circolo a occhio.** Deve appaiare i nomi ai
   dischetti sopra un disegno fuori di 150px, e **tre click su sei** finiscono
   su un dischetto dell'anello piccolo creduto del medio — vicino agli incroci
   fra anelli non c'è modo di distinguerli.

Quindi: l'identificazione va tolta dalle mani dell'utente. Non è una comodità,
è l'unica strada.

## Cosa c'è già, e cosa fa

### Il rilevatore — `src/ridley/photogrammetry/blob_detect.cljs`

`detect-blobs` cerca componenti scure sotto la media locale, su immagine
ridotta. Nato per il PIATTO e tarato per quello.

Aggiunto il 2026-08-23 (`c14733a`), e sono i due mattoni da cui ripartire:

- **`cage-opts`** — tarature per la gabbia: finestra stretta (`:win 6`),
  `:max-aspect 6.0` (i mark si vedono a ogni obliquità e si schiacciano in
  ellissi lunghe), `:surround-bright 0.5`.
- **`bright-around`** — un dischetto è scuro ED è circondato di bianco; una
  macchia del fondo no. Campiona un anello a 1.8/2.4/3.0 raggi, 32 direzioni.

Misurato su `IMG_9014` (fondo NERO): il modo piatto trova **tutti** i dischetti
dell'anello grande e **nessuno** dei due interni — la finestra della media
locale è scelta «≫ un dischetto, ≪ la distanza fra due», che sugli anelli
interni (bande sottili su fondo scuro) si riempie di fondo e manda la media a
nero. Stringendola: 214 candidati per 10 buoni; con `bright-around`: 89
candidati, un solo dischetto vero perso.

Misurato su `IMG_9019` (fondo CHIARO, carta bianca): il guasto della media
locale sparisce, e **resta scoperto quello sotto** — a risoluzione ridotta il
**bordo** della plastica somiglia a un dischetto quanto un dischetto. Modo
piatto: 26 rilevazioni quasi tutte vere ma poche. Modo gabbia: 104, con una
fila di falsi lungo il bordo dell'anello grande e sull'ombra.

### Il percorso automatico del PIATTO, da imitare

`edit_acquire.cljs` tasto **`a`** → `on-auto-register!` (riga ~3256), che oggi
rifiuta esplicitamente la gabbia (`no-auto-on-cage-msg`). Sotto:
`blob-detect/detect-blobs` → `match-plate/fit-crown` (riga 463, RANSAC su
quartetti sparsi) → `match-plate/assign-marks` (riga 223, identità e indice
dello zero) → `pnp/solve-pnp` per foto, con fail-safe rms≤12 e corona≥8.

### Il resto della catena, già solido

- `cage/registration-cage`, `mark-id`/`mark-parts`/`index-id`/`index-parts`,
  `crown-misreadings`/`relabel` (l'indice cambia faccia ma non gira),
  `anchor-axis`, `:phases` per un anello incollato storto.
- `bridge/pnp-target-points` — **78** bersagli su una gabbia: 72 mark di corona
  più i sei zero-indici (`:index? true`), che dal 2026-08-23 sono cliccabili.
- `bridge/camera-sees-marks?` — la GUARDIA FISICA: quei dischetti erano
  fotografati, quindi la camera stava davanti a ciascuno. È l'unico arbitro
  della faccia, perché il residuo è cieco ai 3mm fra le due.
- `pnp/solve-pnp` con `:auto` → dlt / planar / planar-seeded.

## Il piano

### Fetta 1 — il rilevatore a risoluzione piena, con criterio di FORMA

Il problema residuo è netto: **un bordo non è un dischetto, ma a risoluzione
ridotta gli somiglia.** La soglia locale non sa distinguerli; la forma sì.

Correlazione con un modello di disco (un kernel a cappello: positivo al centro,
negativo su una corona attorno) alla risoluzione piena o quasi. Un bordo dà
risposta bassa perché la corona negativa cade metà sul chiaro e metà sullo
scuro; un dischetto dà risposta alta, e **anche schiacciato in ellisse** finché
il kernel è abbastanza piccolo. I massimi locali della risposta sono i
candidati.

Bersaglio da battere, sulla stessa foto: modo gabbia oggi = 89 candidati per 10
dischetti veri noti. Serve arrivare a precisione ≫ e recall ≥ su TUTTI E TRE
gli anelli.

### Fetta 2 — abbinamento posa + identità

Sopra i candidati. Due strade, la prima più semplice e probabilmente
sufficiente:

- **Con seme**: l'utente clicca 4+ mark di UN anello (cosa che sa fare bene, e
  chiude a 5px) più il suo zero-indice se lo vede. Da lì si campiona un
  intorno della posa — la direzione debole è fuori dal piano della corona — e
  si tiene la posa che spiega più candidati. Poi assegnazione, PnP, guardia.
- **Senza seme**: RANSAC sulle ellissi. I tre anelli proiettano tre ellissi;
  cinque punti definiscono una conica. Trovata un'ellisse e i suoi inlier, il
  raggio noto del cerchio dà la posa (due soluzioni), e la seconda ellisse le
  disambigua. Più lavoro, ma nessun click.

### Fetta 3 — cablaggio

Tasto `a` per la gabbia (oggi rifiuta), messaggio, e i risultati nel pannello
come per il piatto.

## Il banco di prova, e usalo

### Dati veri

- **`~/Pictures/RidleyScan/Presa`** — cinque foto della sessione, fondo NERO,
  più `acquire-state.json` con i pick veri. La foto 0 è `IMG_9014.jpeg`.
- **`~/Pictures/RidleyScan/GabbieVuote`** — gabbia sola: `IMG_9019` su carta
  bianca (la più pulita), `IMG_9021`/`IMG_9022` contro la scrivania (fondo
  disordinato, molti falsi), `IMG_9020` come 9021 ma con ritaglio digitale a
  121mm equivalenti.

### Verità note su `IMG_9014` (coordinate di visualizzazione 3024×4032)

Dischetti veri, verificati disegnandoli sulla foto — otto dell'anello grande
più il suo zero-indice:

```
xm00 (494.0,1005.8)  xm01 (992.3,578.5)   xm02 (1631.4,422.0)
xm03 (2261.8,573.9)  xm04 (2728.4,1022.3) xm06 (2648.0,2362.9)
xm07 (1949.7,2776.5) xm09 (304.6,2235.1)  zero-xm (693.9,890.8)
```

Con questi nove la posa chiude a **5.15px**, e `zero-xm` riproietta a **3px**:
è il seme di riferimento. Altri quattro dischetti veri (anelli interni, identità
NON affidabile): (1390.0,2523.2) (1021.4,1711.0) (661.1,1491.7) (931.3,2474.6)
(1898.9,2047.2).

### Le facce, dette da Vincenzo guardando il pezzo

- foto 1 (`IMG_9014`): **X e Z sono `m`, Y è `p`**
- foto 2 (`IMG_9015`): **X è `p`, Y e Z sono `m`**

Sono l'unico dato indipendente sulla faccia. Un risultato che le contraddice è
sbagliato, per quanto basso sia l'rms.

### Focale e intrinseche

EXIF dà 48mm equivalenti su tutte le foto della sessione. Le intrinseche si
costruiscono `(cam/intrinsics-from-fov (cam/equiv-focal->hfov-deg 48 (/ 3024 4032)) 3024 4032)`
— e **l'orientamento conta**: usare 4032×3024 al posto di 3024×4032 è costato
un giro il 19 agosto.

## Trappole, tutte pagate

- **Guarda la foto PRIMA di ragionare.** Il 23 agosto ho sbagliato tre diagnosi
  di fila (gemella planare, anello incollato girato, vicinanza affidabile dopo
  il primo fit) e ogni volta la correzione è arrivata dal disegnare i punti
  sulla foto o dal leggere i pick veri. `magick` c'è: disegnare i candidati
  sull'immagine e guardarli costa un minuto e vale più di un'ora di ipotesi.
- **Non generalizzare da due numeri fortunati.** Avevo dichiarato «dopo la
  registrazione dell'anello X la vicinanza è affidabile» avendo visto 27 e
  48px; sul set successivo erano 130–260px.
- **Il residuo non sa nulla della faccia.** Misurato: 0.00px con le etichette
  giuste e 0.00px con quelle della faccia opposta, perché i 3mm se li mangia la
  camera spostandosi di 3mm. Solo `camera-sees-marks?` decide.
- **La scala è inosservabile**: `:d` 176 o 230 danno lo stesso rms. Non
  diagnosticare mai con uno sweep del diametro.
- **Un solo Ridley alla volta.** Il geo-server sta dentro il processo Tauri e
  serve ANCHE la scheda del browser; se la porta 12321 è occupata il secondo
  non parte e ogni cartella legge vuota. `lsof -nP -iTCP:12321 -sTCP:LISTEN`.
- **Mai `shadow-cljs release app` col watcher attivo**: scrivono lo stesso
  `public/js/main.js`. Ricompilare passando dall'nREPL già connesso
  (`(shadow.cljs.devtools.api/compile :app)`), mai un secondo processo CLI.
- **Provare sulle COPIE**, non sulla sessione di Vincenzo: il 22 agosto ho
  distrutto quattro punti di ricalco suoi testando dal vivo.
- **`js->clj` con `:keywordize-keys` non keywordizza i vettori.** Un path
  costruito così scrive su una chiave-stringa e il codice legge la keyword: si
  perde mezz'ora a credere che uno `swap!` non abbia effetto.

## Cosa NON fare

- Non ritoccare le tarature del PIATTO: `default-opts` è tarato su quello e
  funziona (recall 12/12 sul gate di luglio). La gabbia ha il suo `cage-opts`.
- Non far scegliere all'utente la faccia come strada principale: l'aveva
  proposto lui, ma con l'automatico non serve più. Semmai come rete.
- Non promettere che il tele aiuti: l'EXIF dice che è un ritaglio digitale
  della stessa ottica, e allontanarsi appiattisce la prospettiva, che è proprio
  l'informazione di profondità che manca. Consiglio alle foto: **48mm nativo,
  vicino, gabbia inclinata, fondo liscio e chiaro, ombra staccata dal fondo.**

## File

- `src/ridley/photogrammetry/blob_detect.cljs` — rilevatore, `cage-opts`, `bright-around`
- `src/ridley/photogrammetry/match_plate.cljs` — `fit-crown`, `assign-marks` (il modello da imitare)
- `src/ridley/photogrammetry/cage.cljs` — geometria, id, riletture, `:phases`
- `src/ridley/photogrammetry/bridge.cljs` — `pnp-target-points` (78 bersagli), `camera-sees-marks?`
- `src/ridley/photogrammetry/pnp.cljs` — `solve-pnp`, `accept-rms-px`
- `src/ridley/editor/edit_acquire.cljs` — `on-auto-register!` (~3256), tasto `a` (~5549),
  `cage-relabel-rescue` / `best-misreading` (~1948)
- `test/ridley/photogrammetry/cage_test.cljs` — 959 test verdi, fixture su dati veri
- `dev-docs/HANDOVER-registration-cage.md` — la gabbia nel suo complesso
