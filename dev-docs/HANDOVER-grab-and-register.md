# Handover: "scatta e registra" — la camera in diretta come sorgente di viste

> **Decisione docs (Vincenzo + Claude-docs, 2026-08-11)**: la revisione del
> manuale (cap. 19 delle guide, oggi fuori da `structure.cljs`) si fa DOPO
> questa integrazione: col "scatta e registra" ripartire da zero su un
> OGGETTO NUOVO costa poco, quel collaudo diventa l'esempio conduttore del
> capitolo, e le impressioni finali si registrano direttamente lì. Durante
> quel collaudo, annotare gli inciampi a caldo (cosa ti aspettavi, cosa hai
> visto, cosa ti ha sbloccato): sono la spina dorsale del capitolo, che è
> impostato attorno agli errori tipici e non alla sequenza dei gesti.

Aperto 2026-08-11, alla chiusura della fase dei **bordi dichiarati**.

> **STATO 2026-08-11 (stessa giornata): la prima fetta è COSTRUITA, i test sono
> verdi, il GATE CON LE MANI NON è stato fatto.** Vedi §"Cosa è stato costruito"
> in fondo — inclusi due difetti trovati costruendo, uno dei quali era già lì e
> avrebbe colpito qualunque fotogramma piccolo. Decisione presa con Vincenzo:
> il collaudo è una **sessione tutta a webcam su un oggetto nuovo**, non scatti
> aggiunti a una sessione di foto esistente (due obiettivi nella stessa sessione
> chiederebbero la focale per-foto, che non è costruita).

Brief del canale: `dev-docs/brief-observation-driven-acquire.md`.
Fase appena chiusa: `dev-docs/HANDOVER-edge-declared.md`.
Complemento (pagina LAN per gli scatti fermi, ARKit): `dev-docs/brief-live-sources.md`.

## Cosa ha chiesto Vincenzo

> «Una cosa interessante poteva essere la connessione di una webcam/cellulare in
> diretta per costruire la scena da zero senza passare da fotografie. Pensavo al
> cellulare usato come webcam.»

E, sul come: **"scatta e registra"** prima della diretta vera. Inquadri, premi
un tasto, il fotogramma entra nella sessione GIÀ REGISTRATO. È lì che sta quasi
tutto il guadagno — sparisce il giro scarica-copia-rinomina — e si innesta su
ciò che esiste senza toccare né i gesti né il solutore.

La diretta vera (wireframe sovrapposto al video, click sull'immagine viva) è
un'altra cosa, molto più grossa: serve la posa a ritmo interattivo, il video
come sfondo, e la gestione del mosso. Non è il primo passo.

## Il pezzo difficile c'è già

Il piatto di registrazione **è** una marker board. Da un fotogramma in cui si
vede, la posa della camera si ricava con la strada di adesso, che è tutta
costruita e collaudata:

| pezzo | dove |
|---|---|
| rilevamento dischetti (globale, sub-pixel) | `src/ridley/photogrammetry/blob_detect.cljs` |
| identità della corona + zero-indice | `src/ridley/photogrammetry/match_plate.cljs` |
| PnP planare (omografia + LM) | `src/ridley/photogrammetry/pnp.cljs` |
| focale UNICA per sessione, FITTATA dai dati | bundle di gradino 1 |
| stato della sessione | `session.json` + `acquire-state.json` nella cartella |
| scrittura file (desktop) | `stl/desktop-write-file` → rotta Rust `/write-file` |

**La focale è il punto che rende la cosa possibile.** Una foto di telefono porta
l'EXIF; un fotogramma di webcam no. Ma il gradino 1 ha già portato la focale a
essere *fittata da tutte le osservazioni della sessione* invece che letta: una
vista senza EXIF non è più un caso speciale, è il caso normale.

## Vincoli accertati (non ipotesi)

- **Il telefono come webcam: Continuity Camera.** Su macOS recente un iPhone si
  presenta al sistema come una camera qualunque, quindi `enumerateDevices` /
  `getUserMedia` lo vedono senza app né rete. È di gran lunga la strada più
  corta, e va provata PER PRIMA.
- **Il telefono come client web NON è la strada corta.** Se il telefono aprisse
  una pagina servita dal Mac via `http://<ip>:9000`, la camera sarebbe bloccata:
  `getUserMedia` vuole un contesto sicuro, e `localhost` lo è mentre un IP in
  chiaro no. Servirebbe HTTPS con certificato — attrito vero, da evitare finché
  Continuity basta.
- **"Scatta e registra" è una funzione DESKTOP.** Il fotogramma va scritto nella
  cartella della sessione, e il browser non ha filesystem: la scrittura passa da
  `desktop-write-file` (rotta Rust `/write-file`), che esiste. Nel browser si
  potrà al massimo tenere il fotogramma in memoria per la sessione corrente.
- **Nell'app Tauri i permessi camera non ci sono ancora.** In
  `desktop/src-tauri/` non compare nulla di camera: servono la usage description
  (`NSCameraUsageDescription`) e, se l'app è sandboxata, l'entitlement
  `com.apple.security.device.camera`. Da mettere in conto PRIMA di promettere
  che funziona nel DMG: in Chrome su `localhost:9000` funzionerà comunque.
- **La precisione è da MISURARE, non da assumere.** Un fotogramma di webcam ha
  meno pixel di una foto e può essere mosso, e la precisione della misura scala
  coi pixel. L'uso onesto è *inquadrare* e *aggiungere una vista dove serve*;
  se poi regga anche come misura lo dice il residuo di registrazione (px), che
  la sessione già calcola e mostra per ogni foto.

## Prima fetta proposta

1. Un bottone **Grab** sul palcoscenico, abilitato solo quando c'è una camera
   disponibile e una sessione aperta.
2. Anteprima del video in un angolo, così si inquadra guardando l'oggetto e non
   il Mac.
3. Alla pressione: fotogramma → JPEG → `desktop-write-file` nella cartella della
   sessione, con un nome che segue la convenzione delle altre foto.
4. Registrazione immediata con la strada zero-click (`blob-detect` +
   `match-plate` + PnP planare) e **verdetto ad alta voce**: registrata con
   residuo N px, oppure rifiutata con il motivo. Un fotogramma che non si
   registra non deve entrare nella sessione.
5. `session.json` aggiornato, e la nuova vista compare fra i frustum come le
   altre — da lì in poi non è più un caso speciale per nessuno.

Il criterio di riuscita, in una riga: **scattare una vista nuova e misurarci
sopra uno spigolo senza toccare il Finder.**

## Domande aperte da decidere insieme

- L'angolo θ del giradischi: le foto lo portano da `NOTE.md`. Un fotogramma
  scattato a mano non ha un θ — va lasciato `nil` («foto libera», che il canale
  già gestisce) oppure chiesto?
- Quante viste servono davvero, se aggiungerle costa un tasto? Forse conviene
  scattare POCO e mirato — una vista in più *dove serve allo spigolo che stai
  misurando* — invece del giro completo del giradischi.
- Vale la pena, appena una vista è registrata, dire di quanto MIGLIORA la misura
  in mano (il giro attorno allo spigolo passa da X° a Y°)? È il numero che
  trasformerebbe "scatta" in "scatta QUI".

---

# Cosa è stato costruito (2026-08-11, NON committato, gate umano da fare)

## La correzione di rotta: la focale non era un dettaglio, era il fronte

L'handover dava per scontato che la focale fittata per sessione rendesse una
vista senza EXIF «il caso normale». È vero **dentro un obiettivo solo**:
`session-intrinsics` applica UNA `:focal-mm` a tutte le foto e
`bundle/refine-session` ne fitta UNA. Ma una webcam non ha la focale delle foto
ferme, e soprattutto **il primo fotogramma di una sessione non ha nulla da cui
partire**: nessun EXIF, e nessuna seconda vista contro cui fittare.

Senza una focale non c'è registrazione — e il modo in cui fallisce è il peggiore
possibile. Misurato (`plate-focal-test`, ultimo test):

    focale assunta 20mm → rms 7.95px, camera a 167.2mm (vera 229.5mm)
    focale assunta 28mm → rms 0.00px, camera a 229.5mm  ← il vero
    focale assunta 48mm → rms 9.36px, camera a 388.5mm (vera 229.5mm)

Entrambi i residui sbagliati stanno **sotto** la soglia di accettazione (12px).
Una focale sbagliata non viene respinta: viene assorbita nella distanza, e
consegna una registrazione accettata e sbagliata di 60-160 mm. È esattamente
l'errore piccolo, plausibile e invisibile che questo canale combatte.

## La risposta: il piatto dice la sua focale, in forma chiusa

`src/ridley/photogrammetry/plate_focal.cljs` (nuovo, puro, 5 test). Il piatto è
PIATTO, quindi si immagina attraverso un'omografia, e r1⊥r2 con ‖r1‖=‖r2‖ — i
vincoli di Zhang — sopravvivono dentro di essa. Col punto principale al centro e
pixel quadrati (ciò che il canale già assume ovunque) l'unica incognita è f, e
ciascun vincolo la dà per intero. **Due stime indipendenti da UN fotogramma**:
devono accordarsi, e quando non lo fanno il fotogramma sta dicendo che non può
rispondere.

Misurato:

- su proiezioni esatte: **0.00% di errore**, a qualunque obliquità e focale;
- con rumore sui click: 0.73% a 0.25px, 1.5% a 0.5px, 3.1% a 1px;
- **piatto ripreso in faccia → rifiuto per nome** (`:fronto-parallel`), mai un
  numero. A quell'angolo ogni focale spiega l'immagine e la distanza assorbe la
  differenza: non è rumore da mediare, è un punto cieco con un rimedio (inclina);
- quanto basta inclinare, misurato a 0.5px di rumore: 5°→21% di errore,
  10°→4.5%, 15°→1.8%, 20°→1.6%, 30°→0.8%. **Da 15-20° in su la mano ce la fa.**

Un caso che sembrava un guasto e non lo è: se la camera orbita restando a
livello, un asse del piatto resta parallelo all'immagine, quel vincolo divide per
zero e **l'altro è perfettamente sano**. Rifiutare lì avrebbe rifiutato un buon
fotogramma per la forma del braccio di chi lo scatta: un vincolo solo si USA, e
si dichiara (`:single-constraint?`).

## Perché l'identità può girare PRIMA di sapere la focale

`assign-marks` giudica un candidato riproiettando corona e zero-indice e
chiedendo se cadono su dischetti veri — e quei punti sono tutti sul piatto, cioè
complanari. La riproiezione di punti complanari passa per l'omografia, che la
focale non cambia. Verificato end-to-end su una scena sintetica (vero 28mm):
**i seed da 20 a 50mm identificano la corona correttamente e ne ricavano
28.00mm (0.01%)**. La scala di partenza `[28 35 22 45 18 60]` ha margine
abbondante e in pratica si ferma al primo gradino.

## Il difetto che era già lì: la finestra di snap era tarata sulle foto

Costruendo, il test end-to-end ha trovato un difetto **di produzione**, non del
test. `blob/snap-to-blob` divide la sua finestra al punto medio della propria
luminanza e poi pretende che la parte scura stia fra il 3% e il 75%. La finestra
era **fissa a 40px**, tarata su una foto da 4032px dove un dischetto misura ~23px
(1.7 raggi). In un fotogramma **da 1920px lo stesso dischetto è ~8px**: 40px sono
5 raggi, la parte scura è il 3.0% — sul filo del pavimento — e lo snap **rifiuta
in silenzio su un fotogramma perfettamente buono**.

Misurato prima e dopo, stessa scena:

| | mark agganciati | peggior scarto dal vero | focale ricavata |
|---|---|---|---|
| finestra fissa 40px | 6/12 | 20.54 px | 25.16mm (err 10.15%) |
| finestra 21px (3 raggi) | **12/12** | **0.12 px** | **28.00mm (err 0.01%)** |

Correzione: `match-plate/snap-window-radius` — la finestra vale
`snap-window-discs` (3) raggi del dischetto VISTO, letti dalla corona stessa
(`crown-image-scale`, mediana dei rapporti fra mark adiacenti), limitata a
[8, 40]. **Il percorso delle foto da telefono è invariato per costruzione**
(3×23=69 → si ferma a 40, il valore di prima). Usata sia dal vivo sia da `a`
(Auto), perché è lo stesso difetto.

## Il resto della fetta

- **`src/ridley/editor/camera_capture.cljs`** (nuovo): apertura camera, elenco
  dispositivi, anteprima, fotogramma → JPEG. Niente di fotogrammetrico dentro.
  Le etichette dei dispositivi sono vuote finché il permesso non è dato: quindi
  prima si apre uno stream, POI si elenca.
- **`acquire_backdrop/sampler-of`**: il campionatore di pixel estratto da
  `load-luminance-sampler`, così **un fotogramma mai finito su disco vale quanto
  una foto che c'è**. È ciò che permette di misurare PRIMA di scrivere.
- **`edit_acquire`**: tasto `g` / bottone Grab, il box "Camera/Grab" nel
  pannello (solo col piatto), l'anteprima in basso a destra nel viewport,
  `session.json` riscritto conservando le chiavi altrui, la camera spenta in
  uscita (una spia accesa senza motivo è un difetto).
- **Sessione VUOTA che si apre.** Prima, una cartella senza `session.json` e
  senza `NOTE.md` veniva rifiutata — e con "scatta e registra" quel rifiuto è
  assurdo: nega l'accesso al bottone che creerebbe le foto di cui si lamenta.
  Ora una cartella vuota apre con un film vuoto e si riempie scattando.
- **θ = `nil` (foto libera)** per uno scatto a mano, la risposta alla prima
  domanda aperta: non è sul giradischi, e il canale già sa cosa farne
  (`free-photo?` la tiene fuori dal modello ad anello e dalle sue predizioni).
- **La focale si adotta UNA volta**, dal primo scatto, con provenienza `:live`
  (che sopravvive alla riapertura). Gli scatti dopo la riusano e si limitano a
  RIFERIRE la propria: un obiettivo che è davvero cambiato dev'essere visibile,
  non mediato di nascosto. `R` resta il fit congiunto quando ci sono due viste.

## Verificato DAL VIVO nel browser vero (non solo nei test node)

Prima trappola evitata: la pagina aperta su `localhost:9000` era **vecchia** —
non aveva né `plate_focal` né `camera_capture`, e l'hot-reload non tira dentro
namespace NUOVI. Un collaudo su quella pagina avrebbe misurato il codice di ieri.
Dopo il reload, sul percorso di produzione:

- `getUserMedia` disponibile e contesto sicuro su `localhost:9000` — **true**;
- focale da proiezioni esatte nel browser: **31.000000000000075** su un vero di 31;
- **cartella VUOTA aperta**: `session.json` creata (`"photos": []`), sessione a 0
  foto, e il suggerimento del pannello è quello giusto («Sessione vuota: apri la
  Camera…»). Il box Camera/Grab compare col Grab **disabilitato** finché la camera
  è chiusa;
- **fotogramma sintetico 1920×1080 fatto passare per l'intero percorso reale**
  (rilevamento → identità → focale → ri-identificazione → PnP): `ok`, corona
  12/12, **rms 0.073px**, **focale misurata 31.06mm** con la sessione ancora sul
  default di 48mm, cioè scala di partenza + misura, da zero;
- **accettazione su disco**: `grab-01.jpg` scritto (18.5 KB), `session.json` con
  `["grab-01.jpg", null]` (θ nullo = foto libera), `acquire-state.json` con
  `focal {mm 31.06, source "live"}` e la foto 0 a rms 0.073px, badge di pellicola
  «1 · 0.1px»;
- **secondo scatto**: riusa la focale di sessione ESATTAMENTE (`:uguali true`) e
  riferisce a parte la propria — «adotta una volta, riferisci sempre»;
- **rifiuto**: un fotogramma senza piatto → «Crown not recognised (0 blobs
  detected)» e **niente sul disco** (una sola foto in `session.json`, nessun file
  in più).

Resta non verificabile senza le mani: una camera vera, un piatto vero, la luce
vera, e il mosso.

Suite: **892 test / 3557 asserzioni, 0 failures, 0 errors** (erano 886/3502): +6
test, tutti in `plate_focal_test`. Zero warning nuovi nel build `:app` (i 18 che
restano sono `infer-warning` preesistenti in codemirror/gizmo/viewport/clipper).

## PRIMO GATE (2026-08-11, Logitech C922 @1920×1080): PASSATO

> **ESITO: `grab-01.jpg registrata ✓ rms 2.9px, 10 dischetti, corona 12/12 ·
> focale MISURATA dal piatto: 31.1mm-equiv`.** Il canale funziona dal vivo,
> end-to-end, su una webcam vera. La causa del blocco iniziale era lo
> zero-indice NON VISIBILE — ma **presente**: era occluso dalla cartuccia di
> stampante posata sul piatto. Vincenzo ha girato il piatto e la `g` l'ha presa.
>
> **Errore diagnostico da non ripetere**: avevo riproiettato tutte e 12 le
> posizioni possibili dello zero e concluso che «cadono tutte su piatto bianco
> pulito, quindi non è stampato». Due di quelle dodici cadevano visibilmente
> SULLA CARTUCCIA nella mia stessa immagine annotata, e non le ho lette. La
> lezione non è «guarda meglio»: è che una posizione riproiettata su una
> superficie OCCLUSA non è evidenza di assenza, ed è il programma che deve
> distinguere «non c'è» da «non si vede» — vedi il lavoro proposto sotto.

### La diagnosi (valida, tranne la conclusione)

Vincenzo ha scattato e ha ottenuto due volte «Crown not recognised (40 blobs
detected)». Diagnosticato sul suo fotogramma vero, tirato giù dalla sua sessione
viva col REPL e guardato a occhio:

- **gli 11 dischetti visibili della corona SONO tutti rilevati** (aree 197-503;
  il dodicesimo è coperto dall'oggetto). Il rilevatore non è il problema;
- dando a `assign-marks` SOLO quegli 11 punti veri: `corona 11/12 · zero-hit
  false`. Il blocco è lo **zero-indice**;
- riproiettate tutte e **12** le posizioni possibili dello zero (la rotazione è
  ambigua proprio perché lo zero non si vede). *Qui ho sbagliato a leggere: due
  di quelle posizioni cadevano sulla cartuccia, e lo zero era sotto una di
  quelle. Girando il piatto è ricomparso.*;
- **prova del nove**: aggiunto uno zero FINTO alla lista dei blob veri, il
  percorso di produzione passa sul fotogramma vero — corona 11/12, zero true,
  finestra di snap **26px** (dimensionata sul dischetto visto: la correzione di
  oggi che lavora sui dati veri), **11/12 mark agganciati ai pixel veri**,
  **focale 28.43mm**, **rms 1.28px** contro una soglia di 12.

La focale misurata è una verifica fisica indipendente: la C922 dichiara 78° di
diagonale, cioè ~26.7mm equivalenti. 28.43mm misurati dal piatto, a occhio chiuso.

Geometria del piatto, per il record: **corona a 58mm, zero-indice a 52mm** (cioè
6mm più interno), dischetti da **2.5mm** di diametro, 12 mark.

### I due lavori che il gate ha reso obbligatori — FATTI (2026-08-11)

Suite **894 test / 3566 asserzioni, 0 failures** (erano 892/3557). Zero warning
nuovi. Il test di regressione gira sui **37 blob VERI** di quel fotogramma,
tenuti come dato letterale in `match_plate_test` (`c922-blobs`): un'imitazione
sintetica del disordine non è lo stesso testimone.

    37 blob (11 corona veri + sporco + fori) → zero-not-visible · corona 11
    con lo zero scoperto → corona 11 · 11/11 dischetti veri centrati

**1. «Non si vede» adesso si chiama per nome.** `match-plate/fit-crown-explained`
riporta il motivo — `:crown-not-found`, `:not-identified`, `:too-few-crown`,
`:zero-not-visible`, `:too-few-blobs`, `:no-zero-index` — e la presa dal vivo lo
passa all'utente per esteso: «the crown is recognised (12/12 marks) but the
zero-index is not visible — something on the plate is covering it. **Turn the
plate.**» `fit-crown` resta com'era (un sottile involucro che restituisce nil sul
rifiuto), quindi nessun chiamante esistente cambia comportamento.

Due trappole trovate scrivendo, non dopo: (a) **l'ordine dei controlli** — la
soglia della corona va verificata PRIMA dello zero, altrimenti un anello-esca che
non ha riconosciuto niente verrebbe annunciato come «corona riconosciuta (0/12) ma
riferimento coperto», mandando l'utente a girare un piatto che il programma non ha
mai trovato; (b) fra più ipotesi fallite si riporta **la più azionabile**, non
l'ultima: «gira il piatto» vale più di «non trovo l'anello» anche se viene
dall'ellisse meno supportata.

**2. La selezione della corona non è più una scommessa.** Tre difetti in fila,
ognuno scoperto perché il precedente non bastava:

- *lo stadio 1 decideva da solo.* Ora `ellipse/fit-inliers-ranked` restituisce le
  prime K ipotesi e **lo stadio 2 arbitra** (equidistanti? zero-indice al suo
  posto?), fermandosi alla prima che passa — quindi un fotogramma pulito costa
  esattamente quanto prima;
- *le K ipotesi erano distinte ma non DIVERSE.* Un'ellisse forte viene riscoperta
  da molti campioni, e ogni quasi-copia è un insieme distinto: `top-k` 5 comprava
  cinque variazioni della stessa risposta sbagliata. Ora due ipotesi che
  condividono più di `max-overlap` (0.5) dei punti contano come lo stesso anello;
- *e sotto a tutto c'era dell'aritmetica.* Un campione RANSAC serve solo se tutti
  e CINQUE i punti sono della corona: con 11 corona su 24 candidati fa l'1.1%, e
  su 250 estrazioni le estrazioni utili attese sono ~2.7 — spesso zero, e allora
  la corona non è mai nemmeno un'ipotesi. `:ellipse-iters` da 250 a **1200**
  (probabilità di mancarla sotto 1 su 10⁵). Ogni estrazione è una soluzione
  lineare a 5 punti: costa millisecondi, il numero vecchio non comprava velocità
  che qualcuno potesse sentire.

### La correzione ha reso il RIFIUTO lentissimo, e il gate l'ha ripreso subito

Vincenzo, alla presa di controllo (cartuccia messa apposta a coprire il
riferimento): «Ora dice "Grabbed registering …" e non esce più». Il verdetto
arrivava — giusto — ma dopo secondi, e intanto la riga di stato non diceva nulla.

Misurato invece che immaginato, e la misura ha smentito il mio primo sospetto:

    un passaggio 8086ms · ricerca anelli 14ms · sei focali 48914ms

**La ricerca dell'ellisse costa 14ms**: irrilevante. Gli 8 secondi stavano tutti
nello stadio 2, e l'aritmetica dice perché: identificare un anello PARZIALE di k
punti fra m=12 mark enumera `C(12,k)` sottoinsiemi — 24 candidati con k=12, 264
con k=11, ma **11088 con k=7**, e ciascuno punteggia un'omografia contro tutti i
blob. Su quel fotogramma gli anelli erano `[11 11 8 8 8]`: i due grandi costavano
spiccioli, i tre piccoli tutto il resto.

Tre rimedi, in ordine di resa:

1. **Solo gli anelli VICINI PER TAGLIA al migliore meritano lo stadio 2**
   (`ring-slack` 2). Non è solo il rimedio economico, è quello giusto: la corona è
   l'anello più grande della foto, o a un mark o due da esso quando l'oggetto ne
   copre qualcuno; un anello tre punti più piccolo è spazzatura o un sottoinsieme
   di uno già tenuto. **8086ms → 509ms.**
2. **La scala delle focali si ferma su un rifiuto DEFINITIVO.** La scala esiste
   per far riconoscere la corona; una volta riconosciuta, se lo zero sia coperto è
   un fatto della fotografia e non della focale — chiedere ad altre cinque focali
   è pagare per sentirsi dire la stessa cosa. Il caso di Vincenzo paga UN
   passaggio, non sei.
3. **La ricerca degli anelli è issata fuori dalla scala** (legge solo pixel,
   quindi è la stessa a ogni focale). Vale 14ms — l'ho tenuta perché è
   strutturalmente giusta, non perché si senta.

Caso peggiore residuo (nessuna focale identifica, e due anelli grandi da
provare): **2986ms**. Le soglie nel test sono larghe apposta: sorvegliano la
FORMA del costo, non la velocità di questa macchina.

**Compromesso accettato e dichiarato**: se l'oggetto copre 3 o più mark della
corona E c'è in scena un anello di spazzatura più grande, `ring-slack` scarta la
corona vera. Il rimedio per l'utente è lo stesso di sempre — gira il piatto — ma è
il limite da ricordare se un giorno un fotogramma buono viene rifiutato senza
motivo apparente. Il rimedio strutturale, se servirà, è rendere economico
l'anello parziale usando la sua struttura ANGOLARE (m rotazioni × 2 versi invece
di C(m,k) sottoinsiemi), non alzare la tolleranza.

### IL DIFETTO PIÙ GRAVE DELLA GIORNATA: un granello al posto dello zero-indice

Vincenzo, dopo le correzioni: «La prima l'ha presa anche se il riferimento era
coperto (almeno secondo me). Comunque va.» — `grab-06 registrata ✓ rms 1.9px,
corona 11/12`.

Aveva ragione lui. Indagato sul file vero:

- la corona riproietta esatta sotto la posa risolta (mark 0 a [871 856], il
  dischetto vero è lì) — **ma questo non prova niente sulla rotazione**: la corona
  è simmetrica a 12, quindi un'identità ruotata di 30° riproietta altrettanto
  bene e darebbe lo stesso 1.9px di residuo. **Solo lo zero-indice decide il
  verso**;
- lo zero riproiettava su piatto vuoto. Il blob che aveva soddisfatto il test era
  a 9px, con **area 41 e raggio 4px** — contro **area 517 e raggio 13px** di un
  dischetto vero. Un granello di sporco, un decimo di un dischetto;
- delle 12 posizioni candidate dello zero, solo quella aveva qualcosa vicino: le
  altre a 29-139px. Cioè il granello ha **eletto la rotazione**.

Causa: il giudizio di presenza chiedeva «c'è un blob qui?», mai «c'è un
DISCHETTO qui». Con lo zero coperto dalla cartuccia, un granello a 9px ha preso il
bonus dello zero (100 punti, più di tutta la corona) e ha vinto la ricerca. Il
risultato è **il fallimento peggiore che questo impianto possa avere**: una posa a
rotazione ignota consegnata come misura buona, con un residuo perfetto a
certificarla.

Rimedio: **`match-plate/blob-judge` pesa la TAGLIA**. Il raggio `r` che il
giudizio riceve è già `ring-scale` volte il raggio a cui un mark dovrebbe
immaginarsi sotto la posa in prova — quindi il raggio atteso è `r / ring-scale`, e
un blob molto sotto è sporco, non un mark. Soglia `min-mark-radius-fraction` 0.45,
misurata su questi numeri: respinge il granello (4px dove ne servono ~11 → soglia
5.0) con margine, e accetta un dischetto letto piccolo dalla sfocatura o
dall'angolo radente. Usato sia dal vivo sia da `a` (Auto).

Test di regressione col RAPPORTO vero (4/13), non coi pixel: corona + granello →
`:zero-not-visible`; corona + zero vero → registrata, corona 12/12. **Conseguenza
per la sessione di Vincenzo: `grab-06` va buttata** — la sua rotazione non è
verificabile a posteriori.

### La focale rifinita veniva scavalcata dal primo scatto successivo

Sempre dal collaudo: dopo `R` (28.4122mm fittati su 5 viste), il grab successivo
ha detto «focale MISURATA dal piatto: 27.3mm» e l'ha **adottata**. Causa:
`on-refine-session!` marcava il fit congiunto come `:manual`, e `live-focal`
accettava solo `:live` — quindi la sessione risultava «senza obiettivo proprio» e
un singolo fotogramma si sostituiva a una misura su cinque viste.

Corretto: il fit congiunto è `:refined`, e `own-lens-sources` (`:live`,
`:refined`, `:manual`) raccoglie in un posto solo la domanda «la sessione ha già
l'obiettivo con cui sta scattando?». Restano fuori `:default` (una supposizione) e
`:exif` (un obiettivo vero, ma quello della fotocamera FERMA del telefono, che non
è la camera puntata sul piatto).

### La pulizia degli outlier si fermava troppo presto (2026-08-11, terza tornata)

Con le correzioni precedenti in mano, la sessione nuova (`RDCam2`) ha passato
tutte e tre le prove — rifiuto col riferimento coperto, adozione solo al primo
scatto, poi «questa presa dice X, la sessione usa Y» — ma con **residui pessimi**:
5.0, 10.7, 11.6, 11.9 px contro una soglia di 12, e soprattutto un `R` che **non
migliorava niente**: 10.1578 → 10.1204 px. Un fit congiunto che non sposta nulla
dice che il guasto sta nelle MISURE, non nelle pose.

I residui per singolo mark lo hanno detto subito:

    foto 0: 0.9 0.9 0.9 1.3 2.1 2.2 2.6 3.7 4.5 7.3 13.1
    foto 1: 2.2 2.9 3.6 4.1 5.2 5.3 5.6 12.6 12.6 26.5
    foto 2: 1.3 1.4 2.9 3.0 3.5 4.7 7.7 9.2 14.3 30.8
    foto 3: 1.3 3.8 3.9 5.0 6.0 6.4 7.0 7.4 8.6 10.3 33.8

Quasi tutti i dischetti a 1-5px, e **uno solo per foto a 26-34px** che da solo
faceva tre quarti dell'errore. `solve-pnp` ha già una pulizia greedy degli
outlier: non partiva mai. Due costanti, di nuovo del regime a 4032px:

1. **Il ciclo si fermava appena `rms ≤ accept-rms-px` (12px).** Quella soglia
   risponde a «questa registrazione è utilizzabile?»; il ciclo doveva rispondere a
   «uno di questi punti è sbagliato?». Sono domande diverse, e appaiarle in un
   `or` di uscita significa che un fotogramma a 11.6px si tiene il suo punto a
   30px. Ora si esce solo quando il fit è accettabile **E** non c'è un outlier
   grossolano — perché le due malattie hanno due spie diverse: un'identità
   sbagliata SPALMA il danno su tutti i residui (la vede l'rms), un aggancio
   sbagliato svetta da solo (lo vede `gross-outlier?`). Servono entrambe.
2. **`outlier-floor-px` era 30px assoluti**, cioè lo 0.75% di una foto da 4032px e
   una tolleranza enorme su un fotogramma da 1920. Ora è `outlier-floor-frac`
   (0.0075) della LARGHEZZA dell'immagine, letta dalle intrinsics (il punto
   principale è il centro, quindi la larghezza è 2·cx). A 4032px riproduce i 30px
   di prima: **il percorso delle foto da telefono non cambia**.

Test di regressione col profilo vero (piatto a 12 mark, fotogramma 1920, dieci
punti a rumore normale e uno spostato di 30px): **senza pulizia rms 7.54px — già
sotto la soglia, ed è per questo che passava — con pulizia 0.46px**, scartato il
punto giusto. Più la guardia che i dati puliti non perdano niente, e che a 4032px
la soglia resti ~30px.

### Il fit congiunto rimangiava gli outlier che il solve per-foto aveva scartato

Trovato solo perché il rapporto era diventato PER FOTO. Vincenzo: «Mi sembrano più
alte» — e lo erano:

    foto 6: 8.0398 → 8.0774 px      (ma la sua registrazione diceva 1.7 px)
    foto 5: 15.0542 → 13.2594 px    (la sua registrazione diceva 5.0 px)

`solve-pnp` riporta l'rms sui punti SOPRAVVISSUTI alla pulizia, ma i punti
scartati restano in `:pnp-picks` — di proposito, perché servono a farli
ri-cliccare. `on-refine-session!` li ripescava tutti. Quindi il fit congiunto
ri-adattava OGNI posa contro punti già giudicati sbagliati: non solo il numero
sembrava peggiore, **lo era** — le pose venivano trascinate per guadagnarselo.

Corretto: le viste passate al bundle escludono `:pnp-outliers`. Un punto che il
solve per-foto ha respinto non vota nel fit congiunto.

Da qui anche la lezione sul rapporto: **un numero solo su N foto non è
giudicabile da nessuno**, e infatti nessuno dei due lo stava giudicando. Ora `R`
stampa una riga per foto (prima → dopo), segna quella che tira su la media, e
aggiorna i numeri sulle miniature, che restavano quelli di prima della
rifinitura.

### Buttare uno scatto venuto male (chiesto da Vincenzo)

> «La possibilità di eliminare gli scatti venuti male volevo chiedertela io.»

Costruito. Bottone a DUE click nel riquadro della camera («Delete view 3» →
«Sure? delete view 3», che si disarma da solo dopo 4s) e nessuna scorciatoia da
tastiera: cancella un file, quindi non dev'essere raggiungibile per riflesso.

È una mancanza che la fetta aveva creato: **una vista che costa un tasto produce
viste da buttare**. Quando una foto costava un giro col telefono ci si teneva
quelle che c'erano; adesso se ne fa un'altra, e la cartella si riempie di
tentativi che restano nel fit congiunto per sempre.

Il pezzo delicato è la RINUMERAZIONE: nella sessione tutto è indicizzato per
numero di foto (`:camera-poses`, `:acquire-results`, `:pnp-picks`,
`:pnp-residuals`, `:pnp-outliers`, `:pnp-occluded`, `:pnp-batch`,
`:marker-picks`). Sono elencati una volta sola in `photo-indexed-keys` e spostati
insieme — una mappa dimenticata continuerebbe a puntare alla foto sbagliata, e si
scoprirebbe tre passi dopo come geometria che non torna. Il JPEG viene rimosso
insieme (`stl/desktop-delete-file`, rotta Rust `/delete-file` che già c'era ma
non era esposta al browser): lasciarlo lì metterebbe cartella e `session.json` in
disaccordo su cosa è stato scattato.

### La camera nell'app desktop (2026-08-11)

Vincenzo, a fetta committata: «mi sembra urgente». Ha ragione — finché manca,
"scatta e registra" esiste solo in Chrome.

Accertato prima di scrivere una riga, perché due delle tre cose che si sarebbero
potute fare erano inutili:

- **wry concede già il permesso al webview.** `WKWebView` nega `getUserMedia` se
  l'app non risponde a
  `webView:requestMediaCapturePermissionForOrigin:…:decisionHandler:`, ed è la
  ragione per cui molte app in webview non vedono la camera. wry 0.54.4 lo
  implementa e risponde `WKPermissionDecision::Grant` senza condizioni
  (`wkwebview/class/wry_web_view_ui_delegate.rs`). **Niente da fare lato Rust.**
- **L'entitlement `com.apple.security.device.camera` NON serve**, perché il DMG
  non è firmato: `.github/workflows/desktop-build.yml` non ha identità né
  notarizzazione, quindi l'hardened runtime non si applica e non c'è nessun
  entitlement da soddisfare. Metterlo adesso farebbe invocare `codesign` con un
  file di entitlement e nessuna identità — fallisce invece di preparare qualcosa.
  Va aggiunto SE E QUANDO si firmerà.
- **Serve solo `NSCameraUsageDescription`**, in `desktop/src-tauri/Info.plist`
  (tauri-bundler cerca un file con quel nome accanto a `tauri.conf.json` — è
  scritto nello schema del CLI stesso). Senza, macOS non NEGA l'accesso: termina
  il processo, quindi la mancanza si presenta come un crash e non come un
  permesso rifiutato — che è il modo peggiore in cui poteva mancare.

Deliberatamente NON messo: `NSMicrophoneUsageDescription`. La cattura chiede
`audio: false`, e dichiarare un permesso mai esercitato farebbe offrire
all'utente una scelta su qualcosa che l'app non fa.

**Limite da sapere**: `cargo tauri dev` esegue il binario NON impacchettato, che
non ha Info.plist — e il CLI cerca solo `Info.plist` e `Info.ios.plist`, non una
variante di sviluppo. La camera va quindi provata nell'app COSTRUITA (o in
Chrome), non in `tauri dev`.

### Le due domande originali (per il record)

1. **«Non si vede» non è «non c'è», e il programma deve dirlo.** Oggi
   `fit-crown` restituisce `nil` sia quando la corona non si trova, sia quando si
   trova benissimo ma lo zero-indice è coperto — e il messaggio è lo stesso,
   «Crown not recognised». I due casi hanno rimedi opposti: il primo è
   «inquadra meglio», il secondo è «**gira il piatto**», che è un gesto di due
   secondi. Un oggetto abbastanza grande da stare sul piatto è abbastanza grande
   da coprire lo zero: non è un caso raro, è il caso NORMALE. `fit-crown` deve
   riportare il motivo, e la presa dal vivo deve dire «corona 11/12 riconosciuta,
   ma il riferimento è coperto: gira il piatto».
2. **La RANSAC dell'ellisse è fragile su una scena in disordine.** Sui 37 blob
   veri di quel fotogramma ha agganciato una **conica sbagliata** — 12 inlier
   fatti di sporco sul tappetino e di fori esagonali di un attrezzo giallo sullo
   sfondo, contro gli 11 dischetti veri. Ha vinto per un punto solo. Segnale
   forte e inutilizzato: **i dischetti della corona sono molto più GRANDI del
   rumore** (raggio 8-13px contro 3-7px). Va usato — o vanno provate le prime K
   coniche lasciando che sia lo stadio 2 (che ha lo zero) ad arbitrare, invece di
   far prendere allo stadio 1 una decisione irreversibile.

### La focale su una sessione tutta-webcam CONVERGE (verificato)

Due fotogrammi singoli della stessa webcam avevano dato **28.43mm** e **31.1mm**:
~9% di scarto. Non è un errore del metodo (su proiezioni esatte è esatto), è quel
che vale UNA vista con l'inclinazione che ha. Il fit congiunto su 5 viste:

    === rifinitura congiunta: 5 foto ===
    focale 31.0723 → 28.4122 mm
    riproiezione 2.954 → 1.2748 px

**28.4122 mm**, cioè lo stesso numero che il fotogramma singolo meglio condizionato
aveva misurato da solo (28.43), per una strada indipendente — e la riproiezione più
che dimezzata. Terza conferma, fisica: la C922 dichiara 78° di diagonale, ~26.7mm
equivalenti. La sessione tutta-webcam converge come quelle a foto: `R` fa il suo
lavoro e la focale per-fotogramma è solo un seme.

## Dove sta, e cosa NON è

Il bottone sta in **`edit-acquire`** (la sessione modale), non sul palcoscenico
autonomo come diceva la fetta proposta: il palcoscenico LEGGE le pose da
`acquire-state.json` e non sa registrare — la macchina (rilevamento, identità,
PnP, persistenza, film) vive tutta nella sessione modale. La vista nuova compare
fra i frustum del palcoscenico al Run successivo, come tutte le altre.

**NON fatto, e da fare in quest'ordine:**

1. ~~Il gate con le mani~~ — **PASSATO 2026-08-11** (vedi sopra). Resta da
   riportare al banco la correzione della selezione della corona: è verificata
   sui test e sui blob veri di quel fotogramma, **non ancora con le mani** su
   una presa nuova.
2. ~~I permessi camera in Tauri~~ — **FATTI** (vedi §"La camera nell'app desktop").
   Vecchio testo: (`NSCameraUsageDescription`, e l'entitlement
   se l'app è sandboxata): in `desktop/src-tauri/` non c'è ancora niente di
   camera, quindi il gate va fatto **in Chrome su `localhost:9000`**. Nel DMG
   non funzionerà finché non si mettono.
3. **La precisione vera**, che si legge dal residuo per foto: un fotogramma
   webcam ha meno pixel di una foto e può essere mosso. L'uso onesto è
   *inquadrare* e *aggiungere una vista dove serve*.
4. Le altre due domande aperte (quante viste servono; dire di quanto MIGLIORA
   la misura in mano) restano aperte: sono da decidere DOPO aver scattato.
