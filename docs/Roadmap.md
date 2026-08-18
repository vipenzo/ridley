# Roadmap

## Cos'è questo documento

Questo documento descrive le direzioni di lavoro previste per Ridley nei prossimi mesi e anni. Non è una lista di task con scadenze, e non è una promessa di funzionalità: è una mappa.

La mappa è organizzata per orizzonti, dal più vicino al più lontano. La Parte I raccoglie il lavoro di breve termine, la Parte II quello di medio termine, la Parte III le visioni di lungo periodo. La Parte IV vive su un asse separato: raccoglie sperimentazioni tecniche di natura aperta, dove non c'è ancora consenso su se valga la pena pagare il costo.

Dentro ciascun orizzonte, le voci sono raggruppate per natura: manutenzione, evoluzione del DSL, evoluzione dell'AI, librerie a corredo, e così via. La distinzione fra le nature è importante: alcune voci sono pagamento di debito già preso (manutenzione), altre sono direzioni di esplorazione il cui esito non è scontato.

Il riferimento di base per il debito tecnico già diagnosticato è il capitolo 15 di `Architecture.md`. Molte voci della Parte I rimandano puntualmente a sezioni di quel capitolo, e questo documento non le riprende per intero ma le inquadra nell'orizzonte.

---

## Current Sprint — bussola per ogni nuova sessione

*Questa sezione è la prima cosa da leggere per orientarsi: dice dove stiamo
andando e quali documenti governano il lavoro in corso. È a maglia larga per
scelta: il dettaglio vive nei brief. Si aggiorna a ogni cambio di fase
(chiusura di una Parte di un brief, apertura di un fronte), come da
istruzioni in CLAUDE.md.*

**Fase CHIUSA (2026-08-11): acquire guidata dalle osservazioni, gradino 3 —
BORDI DICHIARATI, e la svolta "il gesto si guida dal codice".** 42 commit,
integrati in main. Documento di governo:
`dev-docs/brief-observation-driven-acquire.md`; entry point
`dev-docs/HANDOVER-edge-declared.md`.

**Fronte APERTO (2026-08-17): LA GABBIA DI REGISTRAZIONE — l'ultimo
esperimento prima dell'archivio.** Nasce da un caso reale fallito: un pezzo di
elettrodomestico abbandonato dall'acquire e rifatto senza problemi con
`edit-image-board`, con la diagnosi giusta — *«le tante foto obbediscono a
criteri boilerplate: servono a far star su il meccanismo, non a disegnare
l'oggetto»*. Il riferimento smette di stare SOTTO l'oggetto e gli va INTORNO:
tre anelli ortogonali, il pezzo ancorato al centro, ogni foto ben mirata e
auto-registrata, niente giradischi/θ/NOTE.md/fusione.

*Fetta 0 COSTRUITA (2026-08-17), gate NON ancora fatto*: `registration-cage` +
libreria `acquire-cage` (stampabile a due colori) + le guardie in `edit-acquire`.
Ordine deliberato: **il detector automatico non si costruisce prima del gate** —
tre delle quattro domande del gate si rispondono cliccando i mark a mano, e se la
gabbia non batte l'image-board si archivia senza aver scritto il multi-ellisse.
Documento di governo `dev-docs/brief-registration-ring.md`.

**Fronte APERTO (2026-08-11): "SCATTA E REGISTRA"** — il telefono usato come
webcam (Continuity Camera), un tasto, e il fotogramma entra nella sessione già
registrato. Entry point `dev-docs/HANDOVER-grab-and-register.md`; complemento
`dev-docs/brief-live-sources.md`.

**Prima fetta COSTRUITA e GATE UMANO PASSATO (2026-08-11)**, su Logitech C922 a
1920×1080, in Chrome su `localhost:9000` (in Tauri i permessi camera non ci sono
ancora). Esito: `grab-01.jpg registrata ✓ rms 2.9px, corona 12/12, focale
MISURATA dal piatto 31.1mm`, e il fit congiunto su 5 viste chiude a **28.4122mm /
1.27px** — cioè lo stesso numero che un fotogramma singolo aveva misurato da solo
(28.43), e coerente con i 78° di diagonale dichiarati dalla webcam. Una sessione
tutta-webcam converge come quelle a foto.

Cosa ha cambiato la fetta rispetto a come era stata proposta:

- **la focale era il fronte, non un dettaglio.** Il primo fotogramma di una
  sessione non ha né EXIF né una seconda vista contro cui fittare — e una focale
  sbagliata non viene respinta, viene assorbita nella distanza: misurato, 20mm
  invece di 28 dà rms 7.95px (sotto la soglia di 12) con la camera a 167mm
  invece di 229. Risposta: `photogrammetry/plate-focal`, che ricava la focale dal
  piatto in forma chiusa (vincoli di Zhang su un bersaglio piano). Esatta sulle
  proiezioni esatte, ~1.5% con 0.5px di rumore, e **rifiuta per nome** un piatto
  ripreso in faccia invece di inventare un numero;
- **un difetto già presente**: la finestra di `blob/snap-to-blob` era fissa a
  40px, tarata su foto da 4032px. Su un fotogramma da 1920px lo stesso dischetto
  è ~8px e lo snap rifiutava in silenzio (6 mark su 12, 20px di scarto). Ora la
  finestra vale 3 raggi del dischetto VISTO (`match-plate/snap-window-radius`):
  12 su 12, 0.12px. Il percorso delle foto da telefono è invariato per
  costruzione;
- una cartella **vuota** ora apre (prima si rifiutava, negando l'accesso al
  bottone che avrebbe creato le foto di cui si lamentava), e uno scatto è
  misurato PRIMA di essere scritto: se non si registra non lascia niente;
- **il gate stesso ha trovato due difetti**, entrambi corretti con regressione sui
  blob VERI del fotogramma che aveva fallito. *(a)* «non si vede» non è «non c'è»:
  un oggetto sul piatto copre il riferimento, e il messaggio unico «crown not
  recognised» mandava a inquadrare meglio invece che a **girare il piatto** —
  `fit-crown-explained` ora riporta il motivo. *(b)* la selezione della corona era
  una scommessa: lo stadio 1 decideva da solo su un giudizio debole (conteggio
  degli inlier), le ipotesi alternative erano quasi-copie della stessa, e sotto a
  tutto un campionamento RANSAC che con 11 dischetti su 24 candidati aveva l'1.1%
  di estrazioni utili su 250 tentativi. Ora: prime K ipotesi **diverse**, arbitrate
  dallo stadio 2, con 1200 estrazioni. E la correzione ha reso il RIFIUTO
  lentissimo (8s per passaggio, 49s per la scala delle focali: «non esce più»),
  ripreso alla presa di controllo successiva — il costo non era la ricerca
  dell'ellisse (14ms) ma l'identificazione di un anello PARZIALE, che enumera
  `C(12,k)` sottoinsiemi: 24 candidati con k=12, 11088 con k=7. Ora solo gli anelli
  vicini per taglia al migliore meritano un solve, e la scala si ferma su un
  rifiuto definitivo: **509ms**;
- **e il collaudo ha continuato a pagare**. *(c)* Un granello di sporco (raggio
  4px contro i 13 di un dischetto) ha fatto da zero-indice a un fotogramma il cui
  riferimento era coperto, **eleggendo la rotazione** — e il residuo di 1.9px non
  lo smentiva, perché una corona simmetrica riproietta identica ruotata di 30°. Il
  giudizio di presenza ora pesa la TAGLIA. *(d)* Il fit congiunto era marcato
  `:manual` e veniva scavalcato dal primo scatto successivo (28.41mm su 5 viste →
  27.25mm da uno solo): ora è `:refined`. *(e)* La pulizia degli outlier di
  `solve-pnp` non partiva mai sui fotogrammi live — usciva appena sotto la soglia
  di accettazione, e la soglia di "grossolano" era 30px assoluti (0.75% di 4032px,
  enorme su 1920). Ogni foto teneva il suo punto peggiore: 1-5px su dieci
  dischetti e uno a 26-34px, tre quarti dell'errore. Ora la soglia scala con
  l'immagine e le due spie (rms per il danno sparso, `gross-outlier?` per quello
  isolato) valgono in `or`.

**Il piatto si può misurare — e la prima volta che l'abbiamo misurato non era
storto (2026-08-13/14).** Vincenzo: «stampare un piatto perfetto è
difficilissimo, per me e per chiunque provasse a utilizzare questa feature» —
quindi la difficoltà si sposta dallo *stampare* al **misurare**, come per tutto il
resto del canale. Costruito; e poi la verifica ha ribaltato la diagnosi che lo
aveva motivato.

**Come è andata, perché è la lezione.** Il residuo sparso fra 0.36 e 3.42px su 12
viste sembrava spiegato: triangolando i mark si vedeva il ⌀300 imbarcato (mark 0
a −1.63mm fuori piano). La calibrazione, costruita, confermava: 1.97mm sul mark
peggiore, residuo da 2.18 a 1.78px. Due conti indipendenti d'accordo — e non
provavano niente, **perché usavano gli stessi click**. La verifica vera è tenere
fuori una foto alla volta e chiedere a chi non ha votato: così il piatto
"misurato" peggiorava **otto foto su dodici**. Togliendo il solo mark 0, quattro
foto crollano da 3.4/2.3/3.0/2.4px a 1.5/0.4/0.3/0.3 **sul piatto del modello**.
Non era un piatto imbarcato: era un click sbagliato su un mark in quattro
fotogrammi, sotto la soglia di `gross-outlier?`, e la calibrazione stava piegando
il piatto attorno a quello.

Quindi `C` adesso **si rifiuta di adottare** ciò che non supera la prova delle
foto tenute fuori, e sulla sessione che l'ha motivato si rifiuta.

**E poi si è trovata la causa vera, che valeva molto più della calibrazione.**
Guardando il residuo PER MARK invece che per foto: ogni foto cattiva aveva
esattamente UN pick sballato fra 6.1 e 9.4px, con tutti gli altri undici sotto il
pixel. Non geometria: click sbagliati. `gross-outlier?` non li prendeva perché la
sua metà assoluta valeva 0.75% della larghezza — 14.4px su 1920 — tarata sui
CLICK A MANO, dove qualche pixel è la mano. Il rilevamento automatico dei
dischetti centra a mezzo pixel, quindi lì un punto a 9px è venti volte gli altri
ed è ovviamente sbagliato, e restava. Portata a 0.15% (2.9px su 1920, 6px su
4032; sui click a mano comanda ancora la metà RELATIVA, quindi quel percorso non
cambia), la stessa sessione passa da **2.1px a 0.6px**:

    foto 1  2.24 → 1.05    foto 3  3.03 → 0.57    foto 4  3.42 → 1.14
    foto 5  2.31 → 0.43    foto 11 2.74 → 0.31    foto 12 2.40 → 0.31

E ricalibrando DOPO la pulizia, il piatto risulta piano entro **0.24mm**, che la
verifica a foto tenute fuori non conferma neanche. Confermato dalla mano: Vincenzo
ha fatto girare il piatto guardando un punto fisso — si alza in un punto solo del
cerchio, meno di un millimetro. Il piatto è a posto; erano sei click.

Confutate lungo la strada, con misure: la distorsione della lente (scansione di
k1: minimo a −0.02 per uno 0.1% di guadagno) e il decentramento dei mark
nell'immagine (le foto 9-12 hanno raggio medio identico, 248px, e residui da 0.36
a 2.74).

- `photogrammetry/plate-calib`: alterna triangolazione dei mark (pose ferme) e
  PnP delle pose (mark fermi). Il **gauge** è il punto delicato: mark e pose
  possono scivolare insieme senza cambiare un pixel (piatto ×s, camere ×s), quindi
  a ogni giro i mark misurati vengono rimessi sul frame del modello — livellati,
  centrati, ruotati e **scalati** al raggio nominale. Ne esce la FORMA, che le foto
  sanno misurare; non la TAGLIA, che non sanno: quella resta `:d` e il calibro. Un
  test lo pretende esplicitamente (un piatto del 5% più grande deve tornare della
  taglia del MODELLO);
- `plate-calib/cross-validate`: tiene fuori una vista alla volta, calibra sulle
  altre, e chiede alla foto esclusa — con la posa risolta da capo su entrambi i
  piatti, così a confronto c'è il PIATTO e non la registrazione — se il piatto
  misurato le vada meglio del modello. È l'unico numero che distingua un piatto
  imbarcato da un calcolo che si è mangiato il rumore;
- il modo di default è `:out-of-plane`: un mark può spostarsi solo in
  perpendicolare. La storia fisica (una stampante posa l'inchiostro a un decimo di
  percento — 0.13mm su 133 di raggio — mentre la superficie si imbarca di
  millimetri) l'avevo scritta come default PRIMA di misurarla, ed è stata smentita
  sui dati veri (non salvava niente). Decisa poi per esperimento: su un piatto
  sinteticamente imbarcato con 1.5px di rumore, tenute fuori, il vincolato dà
  1.91px contro 2.06 del libero;
- `C` riporta lo scostamento **diviso in tre** (fuori-piano, radiale,
  tangenziale — tre difetti diversi) e **rifiuta** sia oltre il 3% del raggio sia
  quando la prova delle foto tenute fuori non conferma;
- **archiviato per PIATTO, non per sessione** (`~/.ridley/plates/`, per diametro e
  numero di mark): un piatto si calibra una volta e vale per tutte le sessioni
  future, che lo annunciano all'apertura. È ciò che rende la cosa utile a chi non
  sia noi;
- **difetto trovato per strada, vecchio e silenzioso**: `camera/rot-mat->rodrigues`
  sbagliava ogni rotazione di **mezzo giro**. Ricavava i segni dell'asse da
  `(m01 − m10)` e `(m02 − m20)`, che per una rotazione di π sono identicamente
  ZERO perché quella matrice è simmetrica: i due test non potevano scattare. Chi ne
  soffriva è `look-at-pose`, che costruisce `:t` dalla matrice e `:rvec` da qui —
  cioè una camera che dichiarava un centro e fotografava da un altro. Una vista di
  giradischi da +Y è esattamente un mezzo giro. Trovato perché la calibrazione di
  un piatto PERFETTO tornava spostata di 7mm; era la vista 2 della scena
  sintetica.

Si dichiara un bordo dipingendolo su due foto e ne esce un segmento 3D misurato;
da più bordi, il piano. Poi, dopo sei giri d'uso vero, Vincenzo ha chiesto di
togliere stati alla UI e usare il codice — e ne è uscito un canale più piccolo:

- `(edit-edge-mark)` / `(edit-curve-mark)` scritte fra gli `:edges` ARMANO il
  gesto; la conferma sostituisce la forma con `(edge-mark {…})` sotto il nome
  che le ha dato l'utente. Niente bottone, niente modo da accendere;
- `(plane-from-edges :a :b …)` fra i `:marks` è il piano come FORMULA: si rifà a
  ogni Run dalle prove che nomina, quindi correggere uno spigolo muove il piano;
- demoliti banco, selezione, tasti numerici e bottone Spigolo (−347 righe);
- chiavi di visibilità `:show` / `:label` su ogni mark, perché il viewport si
  pilota dal sorgente e non da un pannello di caselle;
- feedback dove sta la mano: inchiostro vivo durante la pennellata, riga di
  risposta ✓/✗ nel pannello, e **leave-one-out** sul piano (di quanto ruota
  togliendo un bordo) — che ha scoperto sui dati veri un disaccordo di 9° che la
  planarità in millimetri non vedeva.

**Come si è chiusa**: gate umani passati su tutto (il gesto, il piano come
formula, la demolizione, e infine il piano di una faccia CURVA ricavato da
segmenti fra punti riconoscibili — il dischetto resta incollato in tutte le
foto). Le curve sono state TOLTE per decisione di Vincenzo: misurarle chiede di
appaiare punti fra le foto, che è ciò che questo canale evita, e due spigoli
dritti danno lo stesso piano senza appaiare niente. Manuale: nove schede nuove
(`acquire`, `edge-mark`, `curve-mark`, `plane-from-edges`, `edit-edge-mark`,
`registration-plate`, …). **Dichiarato e non costruito**: il `path-2d` dai bordi
di una faccia — Vincenzo stesso dubita che paghi. **Non attaccato**: i punti
2/4/5 della scala del brief, cioè i bordi come VINCOLI per le pose delle camere
(oggi si misurano DA pose fisse e non le migliorano) — resta il pezzo grosso.
Scoperto lì e poi chiuso: la scheda di manuale di `edit-acquire` (scritta
2026-08-13, con la sezione sulla calibrazione del piatto).

**Fronte (dal 2026-07): canale di acquisizione parametrica — SOSTANZIALMENTE
CONSEGNATO.** Da foto su giradischi a geometria Ridley nativa, senza scanner.
Registrazione camere via PnP su proxy, ricalco su viste in posa, emissione/
round-trip (P4a), palcoscenico non-modale (P4b) e piatto di registrazione a
marker (fette A/B/anello/C): tutto fatto, collaudato dal vivo e committato sul
branch `edit-acquire-registration-stability`. **Gate funzionale del v1 passato**
(giro completo foto → registrazione → ricalco della faccia → estrusione, da
utente).

- **Documento di governo**: `dev-docs/brief-param-acq-v1.md` (Parti P0-P5).
  Stato: **P0–P4a ✅. P4b ✅** — palcoscenico eval-driven come STATO DEL VIEWPORT
  guidato dalla `(acquire …)` valutata nel sorgente normale: frustum cliccabili
  → camera in posa con foto + proxy allineato, raddrizzamento, **toolbar**
  (Prev / Photo-lock / Next in ordine θ, riproiezione live navigando), **`:faces`**
  (la acquire posa la turtle su una faccia → il ricalco è l'`edit-path-2d`
  NORMALE dell'utente sopra la posa, non un modale annidato). Handover
  `dev-docs/HANDOVER-p4b-stage-toolbar.md` / `HANDOVER-p4b-frustum-phaseB.md`.
- **Piatto di registrazione a marker** (promosso dopo il gate di *precisione*:
  i raccordi ~1.7 mm del lettore SD ≈ 33 px, "l'angolo" non esiste come punto →
  il piatto dà landmark netti sub-pixel). `examples/param-acq-plate.clj` +
  binding built-in **`(registration-plate)`** (⌀130 + corona 12 dischetti +
  zero-indice, unica fonte geometria+mark). Registrazione: **A** (4 click →
  propose + blob-snap), **B** identità-free (`b`+`r`, ricerca esaustiva +
  zero-indice), **anello `f`** (registra alcune, propaga le altre), **C ZERO-click**
  (`a`/"Auto": detector globale dei dischetti + **fit dell'ellisse della corona**
  che ne seleziona i 12 + PnP; ~170× più veloce della vecchia ricerca a quartetti;
  fail-safe → le radenti restano per `f`/`p`). Live-gated: piatto di carta **8/10
  in ~4 s**. Handover `HANDOVER-edit-acquire-fetta-{B,C}-live-gate.md`.
- **Residui piccoli (non bloccanti)**: togliere il modo modale `:retrace` (tasto
  `d`), ora che il ricalco si fa con `edit-path-2d` normale sopra la posa (pulizia
  architetturale); ri-collaudare param-plate-one con focale 48 (dato di sessione
  sbagliato — 22 mm — non codice); rifiniture di precisione P2 (priori θ nel fit).
- **Domanda UX di FASE B — DECISA 2026-08-02** (Vincenzo: "non ho preferenze,
  dipende dai momenti, credo servano entrambe"): frustum cliccabili **e**
  pellicola di miniature convivono sul palcoscenico, nessuna delle due è "la"
  navigazione. Default scelto da Code, da costruire: frustum accesi in orbita
  libera (dicono DOVE stanno le camere), pellicola disponibile sempre come
  strisciata rapida (dice COSA si vede) — oggi la pellicola esiste solo dentro la
  sessione modale di registrazione, sul palcoscenico va portata. Vincolo di
  progetto che ne discende: la pellicola dello stage nasce già consapevole di
  PIÙ sessioni (chip per sessione), perché è lo stesso pezzo che serve alla
  fusione — vedi coda post-v1.
- **Mark-piano — GATE PASSATO** (2026-07-31; brief
  `dev-docs/brief-plane-marks.md`, gradino 1). Col proxy piatto il ricalco non
  aveva piani di lavoro sull'oggetto: ora se ne crea uno a mano dal
  palcoscenico. Bottone **"Piano"**: clicca lo stesso punto su ≥2 foto
  registrate (triangolazione ai minimi quadrati), ripeti per ≥3 punti, il piano
  fittato diventa un **mark ordinario** `{:position :heading(=normale) :up}` che
  finisce nello slot `:marks` della `(acquire …)` già emessa — quindi
  `(turtle (:piano-1 (:marks A)) (edit-path-2d …))` funziona senza DSL nuovo.
  Decisione architetturale del brief risolta in **(A) palcoscenico**: il
  write-back nel sorgente è piccolo (`ridley.editor.source-edit`, puro e
  testato) e in cambio rinomina/cancella del mark diventano gratis (si edita il
  sorgente). Il caso 1-click "piano parallelo al piatto" è supportato. Nuovo
  `ridley.photogrammetry.triangulate`; `pnp/plane-frame` resa pubblica.
  Dopo il gate: **HUD sul viewport** con i tre passi della procedura e ogni
  azione come bottone abilitato/disabilitato (lo stato si legge invece di
  ricordarlo), e **qualità di registrazione per foto** — già salvata in
  `acquire-state.json` e mai letta — esposta col ⚠ sul bottone Foto, che vale
  per tutto ciò che si riproietta, non solo per il piano.
  Rieditabile: **`(plane-mark …)` ⇄ `(edit-plane-mark …)`** — un mark porta i
  punti da cui è nato (`:from`) e si riapre con la grammatica della famiglia
  (anteponi `edit-` alla testa e Run). E il confirm di edit-acquire non cancella
  più i mark del palcoscenico: fonde `:shapes`/`:marks` **per chiave** invece di
  rigenerarli in blocco, così ognuno dei due writer rigenera solo ciò che
  possiede e lascia il resto byte-identico.
  Handover `dev-docs/HANDOVER-plane-marks.md` (in testa lo stato alla
  sospensione). Gradini 2 (click assistito) e 3 (omografia) fuori perimetro.
- **Gemello planare — CHIUSO 2026-08-02, ma non dov'era stato ipotizzato.**
  L'omografia con piano noto e `h33` pinnato ha UN solo ramo: l'ambiguità sta
  nelle ETICHETTE. La corona (e lo zero-indice, che sta sull'asse di m00) è
  simmetrica per riflessione, quindi `i → (n−i)` dà un etichettamento che gli
  stessi click adattano con residuo IDENTICO fino all'ultima cifra e camera dal
  lato opposto. Misurato sui pick veri: 10.74 px in entrambi i casi, z = −55.3
  vs +58.3. Ora `solve-and-apply!` riflette e rietichetta i pick; `r` non
  oscilla più. Trovati verificando: il rifiuto applicava lo stesso (`when-let`
  accettava `::refused`, e `solver-pose->camera` di `nil` restituisce una posa
  plausibile invece di fallire) e la spiegazione veniva sovrascritta dalla riga
  di diagnosi. `dev-docs/HANDOVER-plane-marks.md`, sezione RIPRESA.
- **Pagine di manuale vuote — CHIUSO 2026-08-02**. Non era il sito (che è
  sano: spazzati tutti i 294 URL delle schede e tutte le guide) ma **l'app
  desktop**: `desktop-build.yml` non eseguiva `npm run sync-manual` e
  `public/manual/` è gitignorato, quindi il DMG incorporava indice e nessun
  corpo. Silenzioso perché Tauri risponde `index.html` con HTTP 200 a un file
  mancante. Corretti: il workflow, `beforeBuildCommand` in `tauri.conf.json`,
  una guardia che rifiuta la shell HTML con un messaggio esplicito, e — bug
  indipendente trovato nella spazzata — il percent-encoding del nome scheda
  (`sdf-node?` dava 404 anche online). Rilasciato in **v3.5.1** (`main`
  fast-forward al branch: la release porta tutto il lavoro di luglio, ma il
  changelog dichiara solo il fix; il numero minore resta libero per la
  presentazione dell'acquisizione parametrica). Verificato sul DMG della
  release: 40 chiavi `/manual/guides` + 296 `/manual/reference`, dov'era zero.
  `dev-docs/HANDOVER-manual-pages-blank.md`.
- **Uso vero, 2026-08-03** (Vincenzo ha ricalcato un oggetto complesso): «si
  riesce senza troppe difficoltà», con tre osservazioni. (a) **Misura sulla
  foto** (due punti su un piano dichiarato → mm): non esiste — c'è il righello
  shift+click, ma raycasta la geometria in scena, e la foto è uno sfondo
  agganciato alla camera. Rimandata da Vincenzo, «nice to have»: la misura si
  ottiene indirettamente popolando la scena e confrontando. La matematica
  sarebbe pronta (`pixel-ray` + `ray-plane-point`). (b) **Ergonomia**: «i passi
  sono tanti e legati uno all'altro, mi sembra difficile, anche da
  raccontare» → i passaggi di consegne fra i tre modi (sessione modale,
  palcoscenico, sorgente) sono tutti battuti a mano e nessuna vista dice a che
  punto sei. Direzione proposta: pannello di stato dell'acquisizione + i gesti
  scrivono nel sorgente la riga successiva (il write-back esiste già). Da
  scrivere come brief prima di costruire. (c) **Turtle su un mark** — FATTO,
  `ff6f955`: `(turtle A :at :piano-1 …)`.
- **RILASCIATA in v3.6.0** (2026-08-06): la versione che presenta la fusione di
  sessioni. `main` portato avanti dal branch `edit-acquire-registration-stability`
  (27 commit). NON pubblicato in questa release: il **capitolo 19** del manuale
  esiste nel repo ma resta fuori da `structure.cljs` per scelta di Vincenzo —
  quindi l'acquisizione parametrica è nel prodotto e non ancora nella guida.
- **Fusione di sessioni — GATE PASSATO 2026-08-06.** Tre sessioni del collare
  (19 foto), tutte coincidenti col modello costruito sul primo set a meno di 2-3
  mm; anello A→B→C chiuso a 0.13 mm. Il palcoscenico tiene tutte le sessioni
  fuse e `[`/`]` percorre l'intera pellicola. Le guardie nate dal collaudo, tutte
  da errori veri sopravvissuti alle precedenti: un piano non ha lato (confronto
  fra rette); tre piani ammettono quattro sistemazioni (rifiuto + richiesta di un
  aggancio asimmetrico `:point? true`); un punto non ha normale; leave-one-out
  che NOMINA l'aggancio sbagliato invece di spalmare lo scarto; chiusura
  dell'anello con tre o più sessioni. **Residuo dichiarato**: i 2-3 mm non sono
  la fusione ma la posa per foto (PnP a 4-5 px, ~1 mm di profondità; due radenti
  in A a 9-11 px). Prossimo passo indicato: **bundle a focale condivisa per
  sessione** — automatizzabile, nessun lavoro in più per l'utente.
- **Direzione v2: acquire guidata dalle osservazioni** (Vincenzo, 2026-08-06;
  brief `dev-docs/brief-observation-driven-acquire.md` — VISIONE, non task).
  Un solo solutore che mangia OSSERVAZIONI dichiarate ("questo è il dischetto
  3", "questo è lo stesso edge fisico", dritto o curvo, cliccato su più foto)
  e stima insieme pose, focali, punti, edge e trasformazioni tra gruppi di
  rigidità; il programma segnala per nome le foto sotto-vincolate. Tre punti
  forti: gli edge non richiedono identità di punto (un punto qualsiasi lungo
  l'edge è un'osservazione valida); un edge è CONTRASTO, non texture (snap al
  gradiente funziona sulla plastica nera dove il matching fotometrico è
  cieco); un edge triangolato È già ricalco (i click diventano prodotto).
  Primo gradino = il bundle a focale condivisa già indicato qui sopra — è la
  fondazione, non lavoro da buttare. È la risposta alla radice della fatica
  segnalata nell'uso vero (punto b del 2026-08-03): il brief di ergonomia da
  scrivere e questa visione vanno progettati INSIEME.
- **Spigoli dichiarati — COSTRUITO 2026-08-06, click singolo GATED** (gradino 3
  del brief sopra, anticipato per scelta di Vincenzo: «è lì che sta la fatica»).
  Bottone **Spigolo** sul palcoscenico, gemello di Piano: su ogni foto **UN
  CLICK** sullo spigolo — non servono gli stessi punti delle altre foto — e ne
  esce un segmento 3D misurato
  che il palcoscenico disegna nel mondo (quindi `[`/`]` è già la verifica) e
  scrive nel sorgente come `:edges {:spigolo-1 (edge-mark {…})}`. È una POSA che
  corre LUNGO lo spigolo, così `(turtle A :at :spigolo-1 (extrude (circle 2)
  (f len)))` posa un raccordo senza DSL nuovo; tenuta separata da `:marks`
  perché l'heading di un mark è una normale e `acquire-union` leggerebbe uno
  spigolo come un piano storto. `ridley.photogrammetry.edge` è puro: piani
  d'interpretazione + intersezione in forma chiusa + LM sui 4 DOF, residuo in
  px, `:exact?` con due sole foto, e con ≥4 foto NOMINA quella disegnata male.
  **Numero non ovvio che ne è uscito**: la parallasse di uno spigolo è il giro
  che le camere fanno INTORNO a lui, ripiegato in [0°,90°] — muoversi lungo lo
  spigolo non serve, e mezzo giro è cieco quanto stare fermi (a 180° camere e
  spigolo tornano complanari). **Un click basta** (chiesto da Vincenzo lo stesso
  giorno): la direzione non può venire dalla geometria già nota — un click sulla
  seconda foto vincola la retta di 1 grado di libertà su 4, il secondo
  servirebbe comunque — ma viene dall'IMMAGINE, col tensore di struttura attorno
  al click, che dà anche quanto quel punto è un bordo dritto invece che un
  angolo; poi il programma cammina lungo il bordo e trova pure l'estensione (972
  px contro i 120 di un tratto tracciato a mano). Il pezzo decisivo: i bordi veri
  finiscono in curve, quindi si tiene il tratto dritto ATTORNO AL CLICK — senza,
  sul collare nero un click alla cieca riusciva 1 volta su 1442; con, 33. Quattro
  rifiuti nominati (niente contrasto / più di una direzione / troppo corto /
  curvo) e in tutti restano i due click come via di scampo. Verificato dal vivo
  sulla sessione vera: retta nota recuperata a 1e-14 mm da 3 pose reali,
  round-trip nel sorgente, snap agganciato al contrasto vero. **Gate di Vincenzo
  sul click singolo, 2026-08-06**: «una volta su due devo dare due click, va
  abbastanza bene» — e un rifiuto costa UN click in più, non due, perché quel
  click diventa il primo capo del tratto a mano (~1.5 click per foto). Resta: il
  magazzino delle osservazioni (i tratti oggi non sopravvivono alla chiusura) e i
  punti nel pool. Entry point `dev-docs/HANDOVER-edge-declared.md`.
- **Il banco vive nel sorgente — 2026-08-07.** «Sembra non ci sia modo, se sbagli,
  di annullare un piano e rifarlo. Non sarebbe meglio accumulare le cose (piani,
  segmenti) nel sorgente, così li posso cancellare come testo invece che nella
  UI?». Sì: era il resto del canale a essere coerente e questa gestura no — i
  piani già finivano nel sorgente, erano i BORDI a restare in un magazzino
  invisibile che per giunta spariva chiudendo il gesto. Ora `n` scrive il bordo
  nel blocco `:edges` dell'acquire e il banco è la LETTURA di quel blocco; i
  piani si leggono da `:marks`; nessuna copia in memoria di nessuno dei due,
  perché una copia è proprio ciò che una riga cancellata non raggiunge. Il
  guadagno non è solo l'annullamento: cancellare, rinominare, riordinare, tenere
  fra sessioni, diffare — il testo fa già tutto, e ogni verbo che avrei dovuto
  costruire nella UI è un verbo che non esiste. Nuova forma di riposo
  `(curve-mark {:points …})`, senza posa perché una curva non ne ha (è evidenza,
  non ancoraggio: `named-poses` la salta), trasportata da `acquire-union`.
  Verificato: cancellare `:curva-1` toglie il bordo dal banco, cancellare
  `:piano-1` annulla il piano — elenco, disegno ed etichette.
- **La pennellata, terzo giro: tre quantità — 2026-08-07.** «Ancora non si riesce.
  Qui siamo un pelo zoomati. Senza zoom viene verde, ma credo prenda altri bordi
  che vengono inclusi». Due frasi, una causa: il tubo laterale era misurato dalla
  pennellata DIPINTA, quindi doveva assorbire l'errore della mano — e la punta,
  essendo in pixel-schermo, zoomando diventa stretta in pixel-foto. Zoomato: tubo
  da 7 px, il cammino esce dopo 40 px su 564 dipinti e sotto soglia dice «non c'è
  contrasto»; senza zoom: tubo da ~58 px, largo abbastanza da far saltare il
  cammino su un bordo parallelo. Ora dalla gestura escono TRE quantità con tre
  mestieri: la LARGHEZZA aggancia di lato i punti dipinti sul contrasto (quanto
  la mano può sbagliare), la LUNGHEZZA ferma il cammino fra i capi, e il TUBO
  (14 px, assoluto) tiene il cammino vicino al BORDO AGGANCIATO — non alla
  pennellata, quindi non deve più assorbire niente e può essere stretto e
  indipendente dallo zoom. Cinque regimi misurati, tutti ✓: zoomato con mano
  peggiore 108 px a 1.19 di scarto; senza zoom con raggio 156 px, 66 px a 0.88.
- **La pennellata: larghezza e lunghezza sono due cose — 2026-08-07.** «Non ce la
  fa ancora con questo, ho provato sia con size grandi che piccoli». Misurato sul
  suo bordo: con una fascia da 10-30 px viene DRITTO a 1.19 px di scarto, con una
  da 60 px scappa di nuovo — e la punta di default, allo zoom normale, ne
  produceva già ~56. La pennellata funzionava solo stretta, e stretta di default
  non era. Il difetto era di disegno: la LARGHEZZA faceva due lavori in conflitto
  (tollerare la mano, che la vuole generosa; fermare il cammino, che la vuole
  stretta), mentre quello che si dichiara dipingendo è «fin qui», cioè una
  LUNGHEZZA. Ora sono separate: lungo il tratto si sta fra i suoi capi
  (proiezione sulla direzione principale, +12 px di margine), di lato entro il
  raggio — e la larghezza guadagna il secondo mestiere che prima non poteva
  avere, agganciare ogni seme al contrasto più forte dentro la fascia. Con le due
  cose separate ogni raggio da 10 a 120 px dà lo stesso risultato, e il gesto vero
  regge una mano fuori di 22 px.
- **Il bordo sfocato: era la BANDA — 2026-08-07.** Vincenzo manda la foto di un
  bordo in silhouette ovvio all'occhio che il cammino non prende: «un mix di
  sfocatura e rotondita' dello spigolo … forse si puo' abbassare qualche
  soglia?». Non era una soglia di contrasto: censimento su 186 punti con un salto
  vero (>60 livelli su 24 px), quelli rifiutati avevano **coerenza 0.84** — la
  direzione si vedeva benissimo. Era la banda di ricerca perpendicolare: un bordo
  sfocato spalma la transizione su 10-15 px, quindi una scansione da ±10 ci sta
  tutta dentro e il massimo cade all'ESTREMO, dove viene giustamente rifiutato
  (un picco al bordo vuol dire che il bordo vero e' fuori). Allargata a ±16: i
  rifiuti passano da **17 a ZERO**, al prezzo di un falso in piu' su 454 zone
  piatte. Provata e SCARTATA, con le misure agli atti perche' non si ri-provi:
  rendere il test del picco relativo invece che assoluto (17 → 21 → 26 a ogni
  variante) — una gobba larga in una banda che riempie non ha fondo da cui
  distinguersi, quindi la prominenza misurava la gobba contro se stessa.
- **Vedere il banco — 2026-08-07.** «Comincia a essere usabile. La difficolta'
  piu' grande ora e' interagire col banco: i segmenti listati li', come faccio a
  vedere dove sono nelle foto?». Tre domande sue, un problema solo: il banco era
  un elenco senza corrispondenza, e una lista di cose numerate i cui numeri non
  compaiono sulla cosa e' un indovinello. Ora il numero e' scritto NELLA FOTO
  accanto al bordo (billboard, le stesse etichette di edit-path); un bordo speso
  in un piano non sparisce ma si smorza e dice a chi appartiene, nell'elenco e
  nella foto (uno spigolo puo' servire a due facce); e i piani fatti sono un
  elenco a se', ognuno coi numeri dei bordi di cui e' fatto. I numeri si spengono col tasto `l`,
  perche' rispondono a una domanda che ci si fa TRA una pennellata e l'altra e
  durante una stanno in mezzo — sono disegnati sopra tutto e coprono la foto dove
  si dipinge. Trappola per chi tocca: `set-labels!` e' globale, va chiamata solo a
  gesto attivo o cancella le etichette di un edit-path-2d aperto. **Direzione dichiarata e non costruita**:
  raccogliere i bordi di una faccia in un `path-2d` editabile — sono semilavorati
  di un contorno, e proiettati sul piano che definiscono lo sono gia'; manca
  l'ordinamento (sequenza e cuciture agli angoli), non la geometria.
- **Accontentarsi del pezzo in comune — 2026-08-07.** Vincenzo crea un piano, ma
  solo coi tratti DRITTI: «con le curve e' difficile ripetere lo stesso segmento
  in foto diverse, la parte comune sara' solo un pezzo, dovrebbe accontentarsi».
  La misura gli da' ragione e dice perche': un accoppiamento vero passa a 0.23 mm
  e uno falso a 32 — fattore sessanta, quindi la distanza e' gia' un giudice
  quasi perfetto e i tenuti sono praticamente tutti veri; ma sono POCHI (3 su 40
  raggi, il 7% in comune). Scartata per misura l'ipotesi che fosse granularita'
  di campionamento (infittire da 40 a 567 punti non cambia niente). Il pericolo
  non e' la contaminazione ma la scarsita', quindi la frazione d'ordine scende da
  0.75 a 0.6 e — la parte che conta — **una curva sul banco non deve pinzare un
  piano da sola**: bastano 3 punti in comune per tenerla, e se ce ne sia
  abbastanza lo decide dopo la larghezza sui punti di tutti i bordi insieme. E'
  per questo che il banco esiste. Misurato con una mano volutamente imprecisa:
  pezzi da 3 e 18 punti tenuti, uno da 13 con ordine 33% rifiutato, piano dai due
  a 1.1° dalla verticale e 0.87 mm di quota.
- **Il PENNARELLO — COSTRUITO 2026-08-07, gate umano DA FARE.** Segnalato da
  Vincenzo con una foto: «la cattura della linea ha preso troppo: insegue tratti
  non complanari. Non è che può essere utile una sorta di pennarello a punta
  spessa, con cui l'utente dice: la linea cercala in questa zona?». Coglie il
  difetto alla radice: il cammino si ferma quando muore il CONTRASTO, ma un bordo
  vero non muore a un angolo — si trasforma in un altro bordo, e il cammino lo
  segue girando su una faccia di un altro piano. Quale bordo si intenda è
  conoscenza che il programma non ha e l'utente sì. Ora **trascinare in posa
  dipinge una fascia** e il cammino ci resta dentro; la pennellata resta
  disegnata (così anche un rilevamento fallito mostra che il gesto è stato
  sentito), la punta è spessa in pixel-SCHERMO e convertita con la scala che il
  tratto stesso misura (stesso spessore a ogni zoom, e zoomare è il modo di
  averla più fine), il seme si cerca lungo la pennellata dal centro in fuori, e
  la soglia di lunghezza si abbassa da 60 a 25 px perché dipingere corto è un
  atto deliberato. **Misurato**: stesso bordo del collare, click libero →
  cammina 692 px, ne tiene 163 dritti, rifiutato; con la pennellata → confinato a
  254 px, accettato, 1.19 px di scarto. I casi di cammino che scavalca, sul solo
  collare, sono 48.
- **Il BANCO dei bordi — COSTRUITO 2026-08-07, gate umano DA FARE.** Il primo
  giro della curva→piano è stato bocciato all'uso da Vincenzo («troppo
  complicato … al primo click dice che la curva c'è, ma non si vede nessuna
  linea, e alla fine non sono mai riuscito ad andare oltre»), e la sua proposta
  è migliore: «se accumulassimo semplicemente segmenti che restano visualizzati e
  l'utente può selezionare per dire *questi stanno sullo stesso piano*?». Due
  cose distinte, entrambe fatte. (a) Un **difetto vero**: la dichiarazione non
  veniva disegnata — i punti comparivano solo dopo che la seconda foto rendeva
  possibile il fit, quindi per due foto intere non c'era modo di distinguere un
  click buono da uno cattivo. Ora si vede subito, alla profondità dell'oggetto
  sul proprio raggio. (b) Il **banco**: ogni bordo misurato, dritto o curvo,
  resta disegnato e numerato; `n` lo tiene, il numero lo seleziona, `p` scrive il
  mark-piano dai selezionati, Invio scrive uno spigolo, `c` un cerchio. **Perché
  è meglio e non solo più comodo**: una RETTA si misura intersecando i piani
  delle sue righe-immagine, senza accoppiare nessun punto — niente fantasmi,
  niente ordine, niente accordo al 62% — ed è proprio quella che il rilevatore
  trova meglio; due rette non parallele su una faccia ne fissano il piano
  esattamente. La curva smette di essere l'unica strada al piano. Ogni bordo è
  indipendente e può venire da coppie di foto diverse. **Misurato dal vivo** sul
  piatto (due archi dello stesso bordo, provatamente complanari, da foto
  diverse): piano a **0.64° dalla verticale e 0.47 mm di quota**, contro i 6.9°
  della singola curva.
- **Da una curva, il PIANO — COSTRUITO 2026-08-06** (primo giro, superato dal
  banco qui sopra ma la matematica è la stessa).
  Correzione di rotta di Vincenzo dopo aver provato i cerchi sui suoi pezzi: «la
  curva da identificare non è mai un cerchio, al massimo un segmento … potremmo
  usare le linee curve per identificare PIANI. Quelle su cui sto cliccando sono
  tutte curve adagiate su un piano». Ha ragione e la geometria concorda: un piano
  ha 3 gradi di libertà contro i 6 di un cerchio, non chiede alla curva di essere
  niente in particolare, ed esce come un **normale mark-piano** scritto in
  `:marks` dallo STESSO write-back del gesto a tre punti — quindi turtle,
  edit-path-2d e gli agganci di acquire-union funzionano intatti. Sostituisce il
  gesto che costa di più: tre punti × due foto = sei click che chiedono ogni
  volta di RITROVARE lo stesso punto fisico (il posto da cui venivano gli
  "specchiati" del gate di fusione). Ora due click e nessuna corrispondenza
  richiesta; `n` aggiunge una seconda curva sulla stessa faccia, che è l'unica
  cura quando una curva poco pronunciata dà punti quasi in fila — e una fila sta
  su infiniti piani (misurato: larghezza 3.05 → 56.5 mm). **Il difetto insidioso
  e i due test sbagliati**: metà degli incroci fra i raggi sono fantasmi, e il
  caso peggiore è un piano ordinato, pulito e sbagliato di 88°. L'elevazione
  delle camere non basta (vera sul piatto, inutile in generale) e "le due foto
  ricostruiscono la stessa curva" è **circolare** — i fantasmi SONO gli incroci,
  quindi ogni piano che ci passa le fa coincidere (93% di accordo per un piano
  sbagliato di 88°). Funziona l'ORDINE: il cammino dà i punti ordinati, e una
  corrispondenza vera lo conserva mentre gli incroci casuali no — 100% contro
  51%, dove il 51% è la sottosequenza monotona attesa di una permutazione a caso.
  Misurato dal vivo: piano a 0.59 mm da quello vero del piatto, e rifiutato lo
  stesso perché largo 2 mm su 4 richiesti.
- **Cerchi e archi — COSTRUITO 2026-08-06** (fetta precedente, ora subordinata al
  piano: da una curva si scrive il piano con Invio, il cerchio con 'c' quando la
  curva è davvero un cerchio ben definito). Un bordo curvo non è più un rifiuto: il
  cammino l'ha già seguito, e quei punti sono ciò che il fit del cerchio mangia,
  quindi il gesto passa da solo in modo CERCHIO ed emette `(circle-mark {…})`
  nello stesso blocco `:edges` — posa col centro e l'asse più `:radius`, così
  `(turtle A :at :cerchio-1 (extrude (circle r) (f d)))` alesa dove le foto hanno
  trovato un cerchio. La matematica è un'altra: una riga spanna un piano e due
  piani si incontrano in una retta, ma una curva spanna un CONO e due coni si
  incontrano in una quartica — quindi si recuperano prima i punti 3D (i raggi
  delle due foto che si sfiorano; le pose sono note, l'accoppiamento lo fa la
  geometria), poi piano, cerchio nel piano e LM in pixel. **Tre difetti veri
  trovati sui dati veri**, tutti invisibili ai sintetici: la soglia di
  accoppiamento era il triplo del necessario (giusto 0.15 mm contro sbagliato
  3.3 — con 1.5 il RANSAC preferiva un cerchio di raggio 1838 mm a un bordo da
  65); un modello illimitato si adatta a tutto (ora un candidato non supera 3×
  l'estensione della nuvola); e mancava la guardia sul RESIDUO, per cui una
  figura con 157 px di riproiezione passava ogni altro test — ora niente si
  scrive sopra gli 8 px, rette comprese. Misurato sul piatto vero: **⌀128.4 su
  ⌀130 nominale, asse a 2.4° dalla verticale, 5.2 px** — e rifiutato lo stesso
  perché se ne era visto solo 73° di giro contro i 120 richiesti.
- **Fusione di sessioni — FETTA A COSTRUITA 2026-08-03** (`brief-session-fusion.md`).
  `(acquire-union a b …)`: due sessioni dello stesso oggetto in un frame solo,
  agganciate sui mark OMONIMI (dichiarati dall'utente, nessun matching
  fotometrico). Puro e ricalcolato a ogni eval — nessun numero cotto nel
  sorgente. `ridley.photogrammetry.fuse`: seme in forma chiusa (terne
  ortonormali, niente SVD) + LM sui 6 DOF, residuo per mark in mm, e rifiuto
  onesto (un mark solo, due punti nudi senza normali, collineari, base < 5 mm)
  invece di una posa plausibile. Resta: il **palcoscenico multi-sessione** —
  oggi le foto di B non sono navigabili, lo stage mostra la prima sessione con
  tutti i mark (la caduta dei mark trasportati sull'oggetto nelle foto di A è
  già la verifica visiva). E il **gate con foto vere**, che Vincenzo deve
  ancora scattare: seconda sessione con l'oggetto girato + mark omonimi.
- **Gizmo di edit-attach sull'oggetto vero — FATTO 2026-08-04** (segnalato da
  Vincenzo dall'uso). Se il valore di `(edit-attach …)` era poi spostato dal
  resto del sorgente — `(attach (mesh-union (box 20) (edit-attach (cyl 10 5)))
  (u 30))` — il gizmo restava sulla pose interna, non trasformata: si editava
  da una parte e l'oggetto si muoveva dall'altra. Ora la trasformazione esterna
  si **misura** invece di assumerla identità: `request!` timbra un'ancora sonda
  sul valore restituito, gli `:anchors` viaggiano rigidamente attraverso
  trasformazioni e boolean, `enter!` la rilegge a scena finita. Gizmo, turtle e
  anteprima wireframe stanno sull'oggetto che si vede; i comandi emessi non
  cambiano (frame rigido ⇒ covarianti). Vale anche per l'alias `pilot` e per la
  modalità origin, che prima confrontava un pivot non trasformato con click
  raycastati in world. Dettaglio in `Architecture.md` §11.2.3.
- **Cucitura dopo un arco in `transform->` — FATTO 2026-08-04** (segnalato da
  Vincenzo dall'uso, secondo difetto della stessa catena del quarto di giro).
  `(transform-> anello (extrude+ (f 30)) (loft+ sf (arc-v 80 90)) (extrude+ (f 30)))`
  lasciava una fessura fra il loft e l'estrusione successiva. `arc-h`/`arc-v` si
  tessellano in mezzo-passo · corde · mezzo-passo: `loft` stampava l'ultimo
  anello **prima** del mezzo-passo finale — quadrato all'ultima corda — ma
  dichiarava come posa di fine la tangente analitica. Il passo successivo
  partiva quindi su un piano inclinato di mezzo passo attorno al centro della
  sezione: i due solidi si incontravano a cuneo, aperto da un lato (0.43 mm con
  profilo r=35 su arco R=80). `extrude` non l'ha mai avuto (`trail-cap-rot`);
  ora `loft` fa lo stesso — è anche il gemello simmetrico di `split-leading-cap`
  all'altro capo. Vale per entrambi i rami di costruzione (profilo pieno e
  profilo col buco). Test `loft_plus_test.cljs` §9 `arc-trailing-cap-seam`; il
  §8 può stringere la tolleranza da 0.2 mm a 1e-6.
- **Coda post-v1** (dichiarata, non iniziata): proxy oltre box/piatto
  (rounded-prism / fillet); sorgenti foto live (webcam / companion app).
  Il multi-acquire dello stage (la "decisione D" di P4b) è ora la seconda
  fetta della fusione, sopra.
- **Design/storia**: `dev-docs/acquisizione-parametrica-design.md`; handover in
  `dev-docs/HANDOVER-edit-acquire-*.md` — leggere solo per il *perché* di una scelta.

**Fronti chiusi di recente** (2026-07): famiglia mesh-split/mesh-board —
spec ad albero, `split-tree`, viste di confronto, heal-slivers (brief
relativi in `dev-docs/`, capitolo 18 delle guide). **Fronti in pausa
esplicita**: canale scanner denso (`dev-docs/brief-scanner-channel.md`,
riprioritizzato dietro l'acquisizione parametrica — resta valido l'import
OBJ, fatto).

---

## Parte I — Breve termine

Le voci di questa parte sono lavoro a settimane o mesi, con dipendenze risolte e design già preso. Sono pagamenti di debito conosciuto e completamenti puntuali.

### 1.1 Bonifica degli esempi

Priorità prima di tutto. La cartella `examples/` contiene oggi diversi script che non funzionano: alcuni riferiscono primitive SDF la cui API è cambiata, altri sono in stati intermedi di port. Lo stato di fatto è sgradevole nel modo più diretto: gli esempi sono spesso il primo contatto di un visitatore col progetto, e un esempio rotto è una pessima introduzione.

Il lavoro è di tre tipi: aggiornare gli esempi esistenti all'API corrente, decidere quali esempi non hanno più senso e cancellarli, scriverne di nuovi dove la copertura è scarsa (in particolare per le SDF, dove l'esemplificazione è oggi sotto-rappresentata rispetto alla maturità del sottosistema). La rimozione del codice morto è preferibile alla sua manutenzione: meno esempi, ma tutti funzionanti e ben scelti.

Una conseguenza laterale di questo lavoro è il completamento dell'abbozzo Gears (1.5), che diventa il primo elemento della libreria parts a corredo.

### 1.2 Revisione della manualistica

La documentazione di Ridley vive su tre livelli, e tutti e tre richiedono attenzione.

**`Spec.md`** è il riferimento DSL completo, in inglese, organizzato per categoria di operazione. Pubblico: chi vuole sapere esattamente cosa fa una funzione e quali parametri accetta. Stato attuale: disallineato dall'implementazione corrente. Mancano funzioni introdotte negli ultimi mesi (scope, scena, animazioni procedurali, anim-proc!, parts del nuovo sistema di registry, shape-fn aggiunte come `shell` e voronoi-shell). Alcune voci sono obsolete. La struttura di alto livello è cresciuta organicamente e ha bisogno di un riordino editoriale prima del riallineamento puntuale del contenuto.

Il piano è in due passaggi: prima la riorganizzazione manuale dello scheletro (decisione editoriale di come raggruppare le voci), poi il riempimento del contenuto delegabile a Code, file per file, con un brief che fornisca lo scheletro e chieda di riempirlo guardando il sorgente. Il `File Structure` finale del documento, oggi sbagliato, va riscritto da zero.

**Manuale online** (bilingue EN/IT) è stato ristrutturato nella versione v1 (giugno 2026): la navigazione vive in `src/ridley/manual/structure.cljs`, la prosa in Markdown sotto `docs/manual/`, e una Reference a schede è ora sfogliabile per categoria e **cercabile** dentro il manuale (architettura descritta in §11.6 di `Architecture.md`; piano in `docs/manual-redesign-plan.md`). I due debiti storici sono chiusi: la search interna esiste (sopra l'indice `reference_index.cljs` generato al build) e la struttura è stata riorganizzata. Il monolite legacy `content.cljs` resta dietro il flag di cutover `config/new-manual?` e va rimosso a cutover consolidato. Lavoro residuo: traduzioni complete (oggi guide IT + schede EN, coperte dal fallback), schede `internals/`, guide tematiche e cap. 18.

Una volta che la search del manuale esiste — ed esiste — è naturale estenderla per presentare anche i risultati provenienti da `Spec.md`: il documento resta separato, ma le sue pagine diventano visualizzabili online come approfondimento. Il manuale e la spec rimangono distinti per pubblico e funzione, ma comunicano nel punto in cui l'utente cerca informazione.

**User guide e supporti narrativi** sono il terzo livello e oggi non esistono. Ne parla la Parte II, sezione 2.5: è un progetto editoriale che richiede pianificazione, non manutenzione, e quindi vive in un orizzonte diverso.

### 1.3 Pulizia ed estrazione del codice

Le voci di consolidamento del cap. 15.2 di `Architecture.md`, ordinate per costo crescente.

Costo basso: rimozione di `api.cljs` e del build target `:core` ormai inerte (15.5.1, meno di un'ora). ~~Rinomina di `test_mode` → `tweak_mode`~~ **fatto (2026-06-08)**, insieme all'estrazione 15.2.1.

Costo medio: ~~estrazione del pattern modal evaluator dalle implementazioni concrete di `tweak_mode` e `pilot_mode`~~ **fatto (2026-06-08)**: `editor/modal_evaluator.cljs` raccoglie meccaniche condivise + il driver a due fasi come registro generico di kind; tweak (caso degenere sincrono) e pilot vi sono migrati, e `edit-bezier` è il primo evaluator costruito sopra il layer invece di clonare pilot (§2.2). Multimethod per il dispatch dei provider AI al posto del `case` corrente, con i provider come tabella di contenuti registrati invece che casi cablati (15.2.2, una giornata). Unificazione del flusso storia AI fra le due superfici palette e voce, oggi in due percorsi separati (15.2.3, due-tre giorni). Decisione di principio sull'uso del macro prompt di sistema AI (15.2.4): se mantenerlo, riprogettarlo, o rimuoverlo.

L'effetto cumulativo di questa pulizia è una superficie di codice più piccola e più leggibile, e la rimozione di trappole conosciute. Non aggiunge feature, ma rende il sistema più adatto a riceverne di nuove.

### 1.4 Robustezza puntuale

Le voci del cap. 15.3 di `Architecture.md` che sono fattibili a costo limitato.

Tweak offset stale e graceful degradation del mutex interattivo (15.3.1, una giornata): un'edit accidentale durante una sessione tweak poteva lasciare lo stato incoerente. **Mitigato (2026-06-10):** durante ogni sessione modale (tweak / edit-bezier / pilot) l'editor è reso **read-only** (`EditorView.editable`, che blocca solo l'input utente — la riscrittura del sorgente al confirm resta programmatica), e il cambio workspace chiude la sessione (`force-close-active!`) prima di sostituire il buffer. Resta da fare la parte "graceful sulla collisione di mode" vera e propria se si vorrà permettere edit concorrenti. Storage librerie con segnalazione errori esplicita all'utente (15.3.2, mezza giornata): oggi un fallimento di scrittura è silente. Protocollo backend storage librerie (15.3.3, una giornata): unificare i due backend (filesystem desktop, localStorage web) dietro un protocollo comune. Edit mode pannello librerie (15.3.4): rinominazione e cancellazione delle librerie utente sono oggi possibili solo da console; serve una UI minima.

Spy/recorder per gli stub muti del sci-harness (15.3.5, alcune ore): il test harness oggi traccia silenziosamente le call a stub di Three.js, di registry, eccetera; un piccolo recorder che li espone migliorerebbe la diagnosticabilità dei test che falliscono per side-effect non previsti.

Cancellazione cooperativa dell'eval (due-tre giorni): oggi una valutazione SCI lunga — tipicamente molte chiamate CSG su geometria grande o degenerata — blocca il main thread senza modo di interromperla, e l'utente deve chiudere il tab. Il primo passo è cooperativo, a basso costo architetturale: un flag globale di cancel impostato da un bottone "Stop" nell'editor, e controlli ai punti naturali di yield (`mesh-union-impl`, `mesh-difference-impl`, `mesh-intersection-impl`, e i reduce dei macro di union variadici) che lanciano un'eccezione di abort se il flag è attivo. La granularità è per-CSG-call, quindi non interrompe una singola `manifold/union` lunga ma sblocca lo scenario tipico di "ho scritto qualcosa di troppo grande/sbagliato e si è incastrato". L'interruzione *durante* una singola WASM call resta dipendente dallo spostamento del CSG in worker (4.2).

Guard sul ricampionamento per i mark morph-aware (mezza giornata): i mark di un profilo `path-to-shape` sono memorizzati come riferimento all'indice del punto (`:mark-refs`, [shape.cljs](../src/ridley/turtle/shape.cljs) `compute-mark-refs`), così cavalcano gratis le shape-fn che **preservano numero e ordine dei punti** (`tapered`, `twisted`, displacement) — è ciò che rende `(slice-mesh m :on t)` morph-aware. Ma una shape-fn che **ricampiona** il profilo (es. `fluted`, che aggiunge punti) sposterebbe gli indici → mark sul vertice sbagliato, oggi senza protezione. Da fare: in `mark-refs->section-anchors` ([extrusion.cljs](../src/ridley/turtle/extrusion.cljs)) rilevare il cambio di conteggio punti rispetto a quando il ref è stato creato e ricadere in modo trasparente sulla risoluzione base via `:source-path`; in prospettiva, rendere mark-aware anche le shape-fn di ricampionamento (propagare i ref attraverso la nuova indicizzazione).

**Coercizione automatica SDF→mesh nelle operazioni mesh-only — FATTO.** Censimento completo in `dev-docs/brief-sdf-mesh-coercion.md`: `warp`, `solidify`, `mesh-hull` e `concat-meshes` restituivano un risultato silenziosamente sbagliato quando ricevevano un nodo SDF (operando scartato o mesh originale invariata, senza errore); `transform`, `export` diretto di un nodo SDF ed `sdf->mesh` a un argomento fallivano in modo criptico o rischiavano di uccidere il server Rust con un budget di voxel non applicato. Tutti e sette ora coercizzano via `sdf/ensure-mesh` (stesso budget di `ensure-mesh`, mai il default a 15 vpu di `materialize`). Test puri per il routing di solidify/hull/concat-meshes in `sdf_coercion_test.cljs` (non possono verificare un materializzato corretto senza server, ma verificano che l'operando SDF non sparisca più silenziosamente). Verifica del round-trip reale: manuale, desktop, vedi il brief.

### 1.5 Completamenti DSL minori

Lavori di pochi giorni ciascuno, ben circoscritti.

Non-uniform scale con vector: oggi la firma è `(scale fx fy fz)` con tre numeri; estendere il caso `(vector? ...)` in `unified-scale` per accettare `(scale [sx sy sz])` (mezza giornata).

Pretty-print dei risultati REPL: oggi la output usa `pr-str` puro; passare a `cljs.pprint/pprint` con troncamento per output grandi rende il REPL leggibile (mezza giornata).

Auto-complete custom dei binding DSL — **fatto**: l'editor ha un completion source sui simboli Ridley, alimentato dall'indice `reference_index.cljs` (generato al build) più `clojure_core_index.cljs`, con signature e descrizione per voce.

Inline documentation hover — **fatto**: tooltip in hover sul nome di funzione (estensione `hoverTooltip` di CodeMirror) con signature e descrizione dall'indice Reference, più un bottone "apri nel manuale" che porta alla scheda completa (T-009). Lo stesso tooltip compare ora anche nei blocchi di codice degli esempi dentro il manuale.

Error highlighting persistente: oggi gli errori SCI producono un flash arancio sulla riga; l'integrazione con `@codemirror/lint` darebbe marker persistenti su gutter e squiggle sotto il token problematico (una giornata).

Wireframe della mesh sotto il cursore: evidenziare in wireframe nel viewport la mesh prodotta dall'espressione su cui si trova il cursore. Aiuta a disambiguare quale espressione di uno script lungo ha prodotto quale oggetto. Estende il source tracking esistente con una mappa line→mesh-name; un layer di rendering wireframe attivato da selezione in CodeMirror (due giorni).

Save/open file dialog simmetrici: ✅ fatto — l'open dialog nativo (`/pick-open-path`) ora è speculare al save lato Tauri. Aprire un file lo carica in un nuovo *workspace* legato al path; Save scrive sul path legato, Save As ri-lega.

Workspace (documenti multipli): ✅ fatto — l'editor gestisce più workspace switchabili (`ridley.workspace.store` + pannello "Workspaces"), distinti dalle librerie. Aprire un esempio del manuale non sovrascrive più il lavoro corrente. Predispone il versioning desktop (un workspace salvato è un file di testo a path stabile → in futuro "cartella = repo git").

Recent files: lista dei file aperti di recente con persistenza e voce di menu (una giornata).

`path-to-shape` come vera proiezione XY: oggi `path-to-shape` ([shape.cljs:359](../src/ridley/turtle/shape.cljs#L359)) ritraccia il path considerando solo `:f`, `:th` e `:set-heading` (con Z scartata), ignorando silenziosamente `:b`, `:tv` e `:tr`. La docstring e Spec.md la descrivono come "XY projection", ma una proiezione reale eseguirebbe il path completo in 3D e poi scarterebbe la Z dei waypoint. Da fare: eseguire il trace con turtle 3D e proiettare a posteriori (mezza giornata).

~~`loft+` (chaining variant di loft)~~ **FATTO.** `loft+` restituisce `{:mesh :start-face :end-face}` con dispatch identico a `loft` (shape-fn / two-shape / transform-fn), integrato in `transform->`. Nota implementativa che ha smentito l'abbozzo qui sopra: l'`:end-face` **non** rivaluta la shape-fn a `t=1`. In `loft-from-path`, su path con corner e segmenti corti, il clamp `min-step` del ring loop diverge dal denominatore `total-effective-dist`, quindi l'ultimo ring può stare a `t<1`; una rivalutazione a `t=1` nominale produrrebbe una sezione diversa dall'ultima faccia reale → crack alla cucitura del segmento successivo. Cura: threadare fuori dal loop la 2D shape effettivamente stampata sull'ultimo ring (`:loft-end-shape`), consumata da `pure-loft-path*`. Guard: shell/embroid come profilo rifiutati (nessuna sezione di fine unica). Test: `loft_plus_test.cljs` (regressione byte-identica, seam, two-shape, pipeline, guard).

**Forma parziale dei combinatori shape-fn** (seguito, FATTO): dentro `transform->` il passo loft richiedeva una lambda cruda illeggibile. Ora un combinatore profilo-safe chiamato **senza shape** restituisce la trasformazione nuda `(fn [shape t] -> shape)` — nessun metadato `:shape-fn`, cade nel ramo legacy: `(loft+ (tapered :to 1.3) (f 30))`. Toccati `tapered` `twisted` `fluted` `rugged` `noisy` `capped` (kwargs, con helper `combinator-kwargs`) e `displaced` (parziale 1-aria). Nessun cambiamento di dispatch. `pure-loft-path*` ora binda `*path-length*` anche nel path legacy (upgrade semantico: una trasformazione nuda che lo legge — `capped` parziale — vede la lunghezza reale). Guardia di `loft+` ridocumentata (suggerisce la forma parziale). Test: `shapefn_partials_test.cljs` (natura del parziale, equivalenza forte full==partial incl. `capped`, `displaced` 1-aria, guardia, invocazione SCI interpretata).

Sorte di `attach-path` dopo i mark di profilo (mezza giornata + decisione di design): con la propagazione automatica dei mark profilo→mesh ([shape.cljs](../src/ridley/turtle/shape.cljs), che ora deposita `:section-anchors`/`:rail-path`/`:profile-shape` sul mesh), `attach-path` ([implicit.cljs:311](../src/ridley/editor/implicit.cljs#L311)) è diventato ridondante per il caso "recupera il rail su cui hai estruso" — quel rail è già `:rail-path`, raggiungibile via `(move-to … :on …)`. Resta però necessario per i mesh **senza path generatore** (import STL, risultati di boolean, primitive) e per due usi vivi: `lay-flat` con una print-face e lo scheletro di assemblaggio ([macros.cljs:813](../src/ridley/editor/macros.cljs#L813)). Da decidere: (a) **restringere** e ridocumentare `attach-path` a quel ruolo, deprecando l'uso "recupera il rail" e indirizzando a `:on`; oppure (b) **eliminarlo**, previa alternativa per `lay-flat`/scheletro. Ortogonale al resto del lavoro sui mark.

### 1.6 Recupero della copertura test

Il cap. 15.2.7 di `Architecture.md` elenca le aree del sistema con copertura test scoperta. Sono aree distribuibili nel tempo e su sessioni separate, perché ognuna è autocontenuta: voce, AI, animazione, viewport, librerie, modi interattivi. Il lavoro è di scrivere fixture e harness specifici per ogni area, non di portare il numero di test in alto in modo grossolano.

Il recupero degli E2E del sidecar (15.2.6) è una voce a sé. Quando il sidecar JVM è stato cancellato il 23 aprile 2026, una serie di test E2E che giravano su quello hanno perso il loro target. Il loro recupero richiede port a SCI+CLJS e estensione del test harness per coprire le superfici E2E rilevanti. È una settimana o più di lavoro, e va pianificato come progetto a sé.

---

## Parte II — Medio termine

Le voci di questa parte sono linee di lavoro più sostanziose, dove il design è chiaro nelle linee generali ma richiede ancora scelte. Sono direzioni, non task.

### 2.1 Evoluzione del rapporto con l'AI

L'integrazione AI è funzionale ma il guadagno effettivo dell'utente è modesto rispetto all'investimento. La diagnosi e le vie di pagamento sono tracciate in 15.4.5 di `Architecture.md`. Qui interessa l'orientamento di medio termine.

La via di sperimentazione naturale è il **RAG su `examples/`** invece che (o in aggiunta a) `Spec.md`. Il RAG keyword-based attuale recupera chunk pertinenti ma non basta a colmare il gap del modello che non ha visto Ridley nel training set. Imparare per esempi piuttosto che per descrizione è una via che non è stata ancora tentata, e la cartella `examples/` — una volta bonificata (1.1) — è il corpus naturale.

La via realistica nel medio termine è la **ridefinizione del ruolo dell'AI** nel progetto. Non assistente alla generazione di codice complesso, dove la qualità è insufficiente, ma scaffolder per costrutti semplici, traduttore di intenti high-level in skeleton di partenza che l'utente raffina, oppure interprete di richieste nel mode `:ai` della voce dove la qualità accettabile è più bassa perché il contesto è esplorativo. Questa ridefinizione è in parte una scelta editoriale (cosa promettiamo all'utente) e in parte di prodotto (come strutturiamo le superfici AI per riflettere i loro punti di forza reali).

La via massima — fine-tuning di un modello small su corpus crescente di codice Ridley — vive in Parte III come visione lunga.

### 2.2 Allargamento delle superfici di edit non testuale

Il paradigma testuale di Ridley fonde due affermazioni: il codice come fonte di verità del modello, e scrivere codice come esperienza utente. La prima è invariante. La seconda è già parzialmente smontata da `tweak`, `pilot`, viewport picking, AI palette: nuove superfici di edit non testuale, accessibili a utenti meno esperti di programmazione, sono un obiettivo dichiarato del progetto a patto che producano codice come output.

Il cap. 15.4.6 di `Architecture.md` raccoglie cinque direzioni concrete in ordine di maturazione crescente. Le prime quattro vivono nel medio termine.

**Estensione del pattern modal evaluator** (la prima e più immediata): oggi `tweak` e `pilot` sono i due esemplari, ma il pattern è generalizzabile. Sessioni di painting, dove l'utente "dipinge" su una superficie e Ridley produce le primitive corrispondenti. Sessioni di tracing, dove l'utente disegna un path nel viewport e Ridley produce il `path` o `bezier-as` corrispondente. Altre superfici interattive specifiche per dominio. Il prerequisito è l'estrazione del pattern modal evaluator (1.3, voce 15.2.1).
Primo esemplare concreto: edit-bezier — **implementato (2026-06-08)** in `editor/edit_bezier.cljs` sopra il layer `modal_evaluator`, con il flag `:local` aggiunto a `bezier-to`. La prima sessione di tracing da costruire non è un editor di path generico ma l'authoring interattivo di una singola curva di Bezier cubica, nata da un bisogno reale: tracciare un path che segue il profilo di un oggetto senza calcolare a mano i punti di controllo (oggi l'unica via è risolvere l'equazione della cubica per ricavare i valori da passare a bezier-to o bezier-as). La forma d'uso è (bezier-to (edit-bezier)): il marker (edit-bezier) apre una sessione modal, e alla conferma si riscrive nei tre vettori letterali che bezier-to già accetta — punto di arrivo e due punti di controllo — lasciando una chiamata ordinaria (bezier-to [..] [..] [..] :local). Il primo punto della cubica non compare fra gli editabili: è la posa della tartaruga al call site, catturata durante l'eval con lo stesso ingresso in due fasi di pilot (request! / enter!), e ricalcolata a ogni eval invece di essere scritta a sorgente. Solo i tre punti mobili finiscono nel codice, espressi nel frame locale della tartaruga. bezier-to oggi interpreta i suoi vettori in coordinate di mondo; edit-bezier introduce un flag opzionale :local (additivo, default invariato) che li fa leggere nel frame locale [right up heading] della posa di P0 — così i numeri restano piccoli e leggibili, la chiamata è pose-independent, e riaprirla è un round-trip identità garantito dall'ortonormalità della base.
L'interazione è da tastiera e nativamente 3D. L'idea era partita come "mini editor SVG", inerentemente 2D e planare; camminando si è trasformata in un modal evaluator 3D guidato da tastiera, cioè in qualcosa di completamente nativo a Ridley. Tab cicla fra i tre punti mobili, le frecce spostano il punto selezionato con step regolabile alla maniera di pilot, e il terzo asse si raggiunge con la coppia di tasti che pilot usa già per muovere la tartaruga in profondità — quindi rimuovere la restrizione al piano non aggiunge complessità di interaction design, il vocabolario esiste già. Durante la sessione il viewport mostra geometria effimera (i quattro punti, il poligono di controllo, la curva di preview ricalcolata a ogni nudge) guidata dalla sessione stessa e non da una rivalutazione dell'espressione, esattamente come pilot mostra il proprio wireframe preview. Questa geometria effimera è la parte di lavoro specifica di edit-bezier: lo scheletro (stato, mutex, pannello, keyhandler, riscrittura) viene tutto dal layer modal_evaluator estratto in 1.3, ed è il motivo per cui questa voce dipende da quella estrazione. Costruire edit-bezier clonando pilot invece che sopra il layer estratto sarebbe esattamente il terzo clone che 15.2.1 mette in guardia di non scrivere.
**Forma anchor / tension** — **aggiunta (2026-06-09)**: accanto alla forma libera a tre punti, `edit-bezier` ha ora `(edit-bezier path :at :mark [:symmetric])`, dove gli estremi e le direzioni tangenti sono fissati dai mark del path (start = posa corrente, end = il mark nominato) e gli unici gradi di libertà editabili sono le distanze dei punti di controllo (le tension). È il modo visuale di autorare una `bezier-to-anchor` senza indovinare le tension: le frecce alzano/abbassano la tension e la geometria estrusa reale si rimodella in diretta (nessun poligono di controllo effimero). `:symmetric` lega le due tension in un unico valore (la scelta naturale per gli spigoli simmetrici). Alla conferma il marker si riscrive in `(bezier-to-anchor path :at :mark :tension t [:tension-end t2])`, con `path` lasciato come espressione originale. Prerequisiti aggiunti contestualmente a `bezier-to-anchor`: l'opzione `:tension-end` (manici asimmetrici, direzioni comunque bloccate sugli heading) e la forma path-first `(bezier-to-anchor path :at :mark …)` come zucchero per `with-path`.

**`edit-path`** — seconda sessione di tracing, **specificata (2026-06-09)** in `dev-docs/brief-edit-path.md`, non ancora implementata. Edita un path a segmenti `(def ps (edit-path (f 30) (th 45) …))` che si riscrive in `(path …)` alla conferma; ←/→ selezionano gli elementi, ↑/↓ ne cambiano lunghezza/angolo (shift+↑/↓ il secondo parametro degli arc), Tab predispone il tipo-da-inserire, Ins/Shift+Ins/Canc editano la struttura. Preview = solo il control-polygon; tutto ciò che usa il path a valle (tipicamente `bezier-as :control`) si rimodella in diretta via live-reeval. Pensato in coppia con la modalità control-polygon di `bezier-as` (aggiunta lo stesso giorno) e col righello (`ruler`/`mid`/`seg-mid`) per i vincoli.

**Default bezier-smooth + bulk smooth/lines** — **aggiunto (2026-06-21)** a `edit-path` / `edit-path-2d` (2D e 3D): i nodi nuovi nascono come bezier *smooth* con manici collineari alla corda (sembrano dritti e si bakeano come linea finché non li si modella, ma sono curvabili senza premere `c`); un bezier lasciato collineare ribake come linea pulita (`f`/`th`, `set-heading`+`f`). Tangent-continuity (G1) ora vale anche in 3D (nuova `reconstrain-handles-3d`, re-snap su move/drag/nudge). Il nodo iniziale di un path aperto è cuspide implicita (nessun segmento entrante). `x` (toggle smooth↔cuspide) abilitato anche in 3D; `Shift+A` rende tutti i segmenti bezier smooth senza cuspidi, `Shift+X` riporta tutto a linee.

Il drag dei punti di controllo col mouse è la raffinatezza che renderebbe l'editing davvero comodo, ed è deliberatamente rinviato. Richiede di scegliere un piano su cui proiettare il movimento (o la proiezione sul piano della vista, dinamico e raggiungibile orbitando), maniglie selezionabili via picking, e una conversione schermo-mondo. È un layer di manipolazione diretta del viewport che — vale la pena notarlo — gioverebbe anche a tweak e pilot, e proprio per questo va trattato come una fattorizzazione trasversale a sé, da innescare quando avrà i suoi casi concreti, non da gonfiare dentro edit-bezier. Per ora edit-bezier è keyboard-first; il drag è fase due.
**Palette di blocchi pre-confezionati**: galleria di blocchi geometrici inseribili nel sorgente con drag-and-drop o picking dal menu, ognuno coi parametri esposti come UI ma persistiti come codice. La forma esatta è da decidere — palette flottante, sidebar, picker contestuale — ma il principio è chiaro.

**Wizard parametrici**: per oggetti comuni (cerniere, viti, ingranaggi, contenitori parametrici), una UI di wizard che chiede all'utente i parametri rilevanti e produce codice. È più editoriale che tecnica: scegliere quali oggetti meritano un wizard, scrivere le librerie corrispondenti, costruire la UI di interrogazione.

**Estensione del sistema di assemblaggio**: oggi i `mark` permettono di nominare punti significativi su una mesh per riferirli da un'altra. La sua estensione naturale è l'**assemblaggio per facce combacianti**: l'utente seleziona dal viewport due facce di mesh diverse, Ridley produce il codice di trasformazione che le fa combaciare. È il superamento del "calcola le coordinate giuste a mano" verso il "dichiara la relazione, lascia che Ridley calcoli le coordinate". Richiede un piccolo lavoro di design DSL per la sintassi della relazione e l'integrazione col viewport picking esistente.

### 2.3 Animation export

L'export GIF è la prima voce in casa, e ha portato con sé l'infrastruttura su cui si appoggiano le altre. Il loop di cattura off-realtime sta in `ridley.export.animation/capture-frames!`: sospende il render loop di Three.js, itera `t ∈ [0,1]` chiamando `seek-and-apply!`, forza un render sincrono, e passa il canvas a un `on-frame` callback. È encoder-agnostico per costruzione. L'encoder GIF (gif.js, lazy-loaded da `public/vendor/`) consuma quel callback e scrive il file via geo_server in `~/Documents/Ridley/exports/`. Solo desktop, gating su `env/desktop?`, bundle web invariato.

Le voci ancora aperte poggiano tutte su `capture-frames!`.

**PNG frame-by-frame export**: con il loop pronto è un giorno di lavoro. On-frame raccoglie `canvas.toBlob('image/png')`, JSZip (già nel bundle) impacchetta, write-file scarica.

**MP4 export via ffmpeg**: l'output PNG sequence passa per ffmpeg per produrre un video. La scelta di design è ffmpeg system-installed contro ffmpeg sidecar bundled nel binario Tauri. Bundled è più affidabile per l'utente finale ma aumenta la dimensione del bundle; system-installed è più leggero ma richiede installazione utente. Tre-cinque giorni più la decisione.

**Encoder GIF Rust nativo (gifski)**: oggi gif.js (NeuQuant in JS, output 1-3 MB sui pilot) è sufficiente per la documentazione tecnica. Migrazione a `gifski` via crate Rust darebbe palette percettiva (output 30-50% più piccolo a parità di qualità) ed encoding parallelo veloce, al costo di ~5-15 MB sul binario Tauri. Da rivalutare quando avremo abbastanza GIF nella documentazione per giudicare se la qualità di gif.js basta.

La generalizzazione di `capture-frames!` da uso visivo a uso analitico vive in §2.7.

### 2.4 Geometria avanzata

Tre completamenti sostanziosi del DSL geometrico, indipendenti fra loro.

**Adaptive loft step density per shape-fn transitions**: oggi i loft step sono uniformi lungo il path. Il `capped` shape-fn produce faceting grezzo perché il profilo cambia rapidamente in zone strette ma il sample rate non lo sa. Il blocco di design è la metrica: definire un "cambio shape" robusto per shape-fn arbitrarie — una shape-fn ritorna una shape, e misurare la distanza fra due shape è in generale più sottile che misurare la distanza fra due punti di un path. Tre-cinque giorni di lavoro più l'esplorazione della metrica.

**Face cutting**: disegnare una shape 2D su una faccia esistente di una mesh e usarla come operazione di taglio (passante o cieco). Oggi `attach-face` permette estrusione e inset di una faccia, ma non "qui voglio scavare un buco con questo profilo". Una settimana, con tre blocchi di design: orientamento UV della shape sulla faccia, gestione della profondità (passante/cieco), boolean Manifold finale.

**Attach structure-preserving**: oggi `attach` accetta vettori di mesh ma li flatten ricorsivamente. Una variante structure-preserving permetterebbe di trattare gruppi nidificati come corpi rigidi, con la stessa nesting structure in input e output. Uno-due giorni di lavoro.

**Shell openings: marching squares al posto di marching triangles**: il taglio isocontour degli shell `:voronoi`/`:lattice` con `:softness > 0` (`build-shell-isocontour-mesh`) usa il marching *triangles* — ogni quad della griglia è spaccato da una diagonale fissa, e il bordo del rim zigzaga lungo quella diagonale, lasciando una seghettatura regolare a denti orientati uniformemente sui bordi delle strisce. Visibile solo molto zoomati e probabilmente trascurabile rispetto alle imperfezioni di una stampa reale (da verificare stampando), ma eliminabile passando al marching *squares*: bordo calcolato come un solo segmento per quad invece che per triangolo → curva liscia, denti dimezzati e senza bias direzionale. Costo: gestione dei 16 casi (incluso il caso sella) e rigenerazione di skin+rim+cap per quad; tocca la stessa funzione critica, quindi va fatto con verifica manifold + zero facce degeneri prima di considerarlo a posto. Mezza-una settimana.

Limite noto correlato: il taglio isocontour di `:lattice` con `:invert? true` non si chiude manifold (il plateau dei confini di banda `longit=0` non sigilla sotto inversione), quindi quel caso ricade sul taglio binario duro. `:voronoi` invertito invece funziona. Da sistemare insieme alla riscrittura marching squares, che ridiscute comunque la chiusura di rim e cap.

**Estendere `embroid` a `shell` (campo di pattern condiviso)**: `embroid` (traforo di una parete sottile già esistente) e `shell` (svuotamento di un solido in parete sottile) condividono la stessa astrazione di fondo — un campo `(fn [...] -> 0..1)` su una superficie parametrizzata, tagliato con l'isocontour.

*Parte user-facing FATTA (2026-06-22):* `shell` ha ora `:style :pattern`, che piastrella un motif 2D arbitrario **per ascissa lungo il perimetro**, in *unità-cella* (`:cells` attorno alla circonferenza, `:rows` lungo lo sweep → wrap pulito al seam per ogni `:cells` intero); opzioni `:pattern`/`:cells`/`:rows`/`:grid`/`:inset`/`:margin`, motif = foro di default, `:invert?` lo rende pieno. Risolto il blocco della parametrizzazione angolare non-isometrica passando la frazione d'arco (`perimeter-fractions`) invece dell'angolo `atan2`. Contestualmente sistemato un **bug non-manifold preesistente** di `embroid`/`shell` su **rail curvi**: `build-embroid-mesh` ignorava `caps?` ed emetteva i border-rim delle end-row a ogni cucitura interna dei sub-mesh `:combined` → facce spurie; ora rispetta `caps?` come shell, e il loft passa `:start`/`:end` al primo/ultimo sub-mesh (ha chiuso anche gli estremi aperti di shell su curva). Tutti i casi curvi ora watertight.

*Cosa resta:* (a) promuovere il campo a meccanismo condiviso riusabile (`tile-field` standalone) invece dei due `panel-field` / `style->thickness-fn` separati; (b) unificare o no i due builder isocontour (`build-shell-isocontour-mesh` chiuso/wrapping vs `build-embroid-mesh` aperto/non-wrapping); (c) **qualità su rail curvo**: `arc-h`/`arc-v` sono registrati come corner duri, quindi il loft dual-ring li spezza in segmenti con bridge pieni → le aperture scalettano e mostrano seam radiali (la mesh resta manifold). Cura: taggare i `th`/`tv` di arc-h/arc-v come `:smooth` (come i bezier) così il rail non viene spezzato — ma è codice core ad ampio blast-radius (extrude, profili-2D, portaforbici), da fare con validazione esaustiva. Per ora documentato il workaround (rail bezier). Due-cinque giorni, dopo il marching squares.

**Famiglia "acquisizione STL" (decomposizione di mesh importate) — in gran parte FATTA (2026-07).** Brief-driven, per rendere lavorabili gli STL importati (che non hanno path generatore) tagliandoli in pezzi convessi/stampabili. Consegnato e live-verificato: `mesh-split` + `convex?`/`finished?` (semaforo per-componente); `mesh-components` + sessione **ad albero** di `edit-mesh-split` (ogni taglio genera due pezzi dell'albero, separazione topologica senza piano, undo cronologico cross-branch, keep-alive del Manifold); **simmetria** (`mesh-mirror`/`mirror?`/`symmetry-planes` con PCA pesata per area, gesto `y` proponi-piano, badge specchio, `d` decomponi-a-specchio) — incluso un fix all'eigensolver Jacobi 3×3 e il display del livello ("quasi simmetrico ~X%"); **UI di sessione** (addendum-3: bottoni con stato+ragione, nessun no-op silenzioso, due stati di scena, etichette billboard); **generatore di candidati di taglio** (`section-area` + `cut-candidates` translation/rotation, gesto `[`/`]` salta-al-prossimo-evento + striscia di profilo nel pannello); **V2 riflessa** (`cut-candidates {:mode :reflex}` — candidati dagli spigoli concavi, clustering per piano refinement-invariante B4, salienza = Σ lunghezza × eccesso d'angolo, gesto `c` proponi-e-cicla per salienza come `y`, disabilitato sui pezzi convessi); **viste di contesto e di processo** (fase 3 mesh-board, primo brief: `mtree/tree-view`/`leaf-counts` + widget ad albero cliccabile nel pannello — click su foglia aperta seleziona, "N aperte · M finite" in testa, ordine coerente col ciclo `n`/`p`, enum di stato predisposto per `:nativo` — e `ridley.viewport.inset`, un secondo mini-viewport picture-in-picture non interattivo con camera sincronizzata per orientazione alla principale che mostra la mesh originale ghosted col pezzo corrente acceso al suo posto); **la direttiva `mesh-board`** (brief-mesh-board.md, 2026-07-14) — il body emesso da `mtree/emit` è ora una **mappa** nome→mesh (Parte 0; i vettori delle emissioni precedenti restano validi e mostrabili, senza `:only`); `attach` accetta ora anche una **mappa o vettore di mesh** come gruppo rigido, con errore leggibile (mai più no-op muto) su un argomento non supportato (Parte 1); nuovo layer scaffold ghost-wireframe (registry + viewport + toggle "Boards" in toolbar, trittico copiato da `stamp`, Parte 2); la direttiva stessa — `(mesh-board t)` mostra le foglie in place, `{:only […]}` filtra per nome, `(mesh-board riferimento candidato {:mode :overlay|:intersection|:diff :label …})` confronta e stampa la fedeltà (macchinario di `mirror?` generalizzato a due mesh), sempre pass-through (Parte 3); cittadinanza per inerzia referenziale — export strutturalmente escluso (mai in `current-meshes`), nessun type-wrapper anti-CSG (Parte 4). Live-verificato via REPL browser: scaffold ghost-wireframe nel mondo, toggle, `attach` su albero reale, confronto con fedeltà stampata; un gap trovato e risolto durante la verifica — un `LineSegments` puro non è raycast-hittable da `raycast-world-point` (solo `THREE.Mesh` lo è), quindi ogni scaffold porta ora anche una mesh solida invisibile (opacity 0) gemella, solo per la misura shift+click, esclusa dal pick strutturale come il wireframe. **Viste di confronto multiple** (brief-mesh-board-views.md, 2026-07-16) — il modo confronto `:mode` (`:overlay`/`:intersection`/`:diff`, un secondo scaffold in-place) è sostituito da `:views` (`:intersection`/`:missing`/`:excess` — due diff **direzionali**, non la differenza simmetrica — default tutte e tre) + `:ghost` opzionale (l'overlay in-place grigio/azzurro precedente, ora opt-in anziché sempre attivo); ogni vista apre una finestrella **inset picture-in-picture** (resa solida, non wireframe, orientamento sincronizzato alla vista principale, etichetta con nome e volume) invece di vivere alla posizione dei pezzi; `ridley.viewport.inset` generalizzato da singleton a **N istanze keyed** (mount!/unmount!/set-content! prendono una chiave), un solo frame-callback condiviso, colonna di finestrelle ancorata all'angolo del viewport; `edit-mesh-split` riusa lo stesso manager (chiave `:context`, comportamento visivo invariato); fedeltà ora stampata nel pannello dell'app (`state/capture-println`, non più `println` su devtools, che finiva solo in console); più `mesh-board` di confronto coesistenti restano gruppi di finestre distinti per `:label`. **Iterazione 2** (stesso brief, stesso giorno, feedback dal primo uso) — quattro correzioni: (4.1) un risultato vuoto (pezzi non ancora sovrapposti) non lasciava più contenuto stantio nella finestrella — `update-compare-insets!` non salta mai più l'aggiornamento, un risultato vuoto è un'etichetta esplicita (`intersection: vuoto`), mai silenzio; (4.2) ogni finestrella mostra ora anche il wireframe ghost del riferimento (ancoraggio spaziale) e la camera inquadra il bbox del **riferimento**, stabile mentre il candidato cambia; (4.3) le finestrelle si trascinano dalla barra del titolo (passano a `position: fixed`, escono dal flusso della colonna) — posizione è view state, non sopravvive al reload; (4.4) rotella sopra la finestrella zooma (moltiplicatore di distanza per-istanza, clampato 0.2×-5×), con `preventDefault`/`stopPropagation` così il viewport principale sottostante non reagisce mai. 654 test verdi; tutte e quattro le correzioni live-verificate via browser (Playwright) su un caso sintetico; live-verify sul mount STL reale ancora da fare. *Resta*: la riscrittura assistita (sostituzione di una foglia nel sorgente) e la caduta dell'impalcatura restano orizzonti dichiarati della famiglia, dietro il localizzatore preciso già in tracker.

### 2.5 Apertura del progetto verso l'esterno

Il livello narrativo della documentazione, oggi assente. Chi arriva su `vipenzo.github.io/ridley` o sul subreddit non trova un percorso "guarda cosa puoi fare in 5 minuti", non trova un'introduzione al perché Ridley esiste, non trova esempi commentati di cosa il sistema sa fare bene.

Il lavoro è editoriale, non tecnico. Le forme possibili sono diverse e non mutuamente esclusive:

**User guide testuale breve**: una pagina o un documento che porta un nuovo arrivato dal "che cos'è questa cosa" al primo modello stampabile in 5-10 minuti. È blog post lungo, non manuale. Tono narrativo, screenshot, codice commentato passo passo. Il pubblico è chi è curioso ma non ancora investito.

**Serie YouTube tematica**: video brevi (5-10 minuti) ognuno su un aspetto del sistema. Il primo, "What is Ridley?", è un investimento ad alta leva: pochi giorni di registrazione e montaggio per qualcosa che vive su YouTube e si lega facilmente nelle altre vetrine del progetto. Video successivi: "Hello turtle", "Modeling a bracket in 5 minutes", "From sketch to STL", e via dicendo.

**Articoli di approfondimento**: una serie di blog post tecnici sull'architettura, sulle decisioni di design, sull'esperienza dello sviluppo AI-assistito. Sono già parzialmente esistenti come materiale, vanno strutturati come serie. Il subreddit r/RidleyCAD, oggi usato quasi solo per gli announce di versione, è il posto naturale per ospitarli e dare al subreddit stesso una funzione che vada oltre il changelog. Pubblicare regolarmente lì un articolo a settimana o ogni due settimane attiverebbe il subreddit come spazio di lettura, non solo di notifica.

La scelta della forma o della combinazione di forme è essa stessa parte del lavoro. Una preferenza iniziale: cominciare dal primo video YouTube come pezzo singolo ad alto rendimento, dalla user guide testuale come ancoraggio del sito GitHub Pages, e dall'attivazione editoriale di r/RidleyCAD come canale continuativo.

### 2.6 Librerie a corredo

Standard parts come libreria parts vere e proprie, non come funzionalità del core. Sono progetti di contenuto: si scrivono come `.clj` idiomatici, si pubblicano come parte degli esempi o di una collezione `library/parts/` separata.

**Gears**: completamento di quanto abbozzato nei primi esempi con ingranaggi. Coppie di ingranaggi spur con parametri standard (modulo, denti, larghezza), eventuali estensioni a ingranaggi elicoidali e conici come incremento.

**Threads**: viti, dadi, accoppiamenti filettati. Helix sweep su profilo trapezoidale per filetti realistici. Set base secondo standard ISO (M3, M4, M5, M6, M8, M10) più dadi e rondelle corrispondenti. È lavoro di una-due settimane per il set base; il blocco non banale è il filetto realistico, dove la mesh prodotta deve essere stampabile e stabile in boolean.

Ulteriori librerie a corredo (cuscinetti, profilati, raccordi) possono seguire come incrementi quando emergono casi d'uso.

### 2.7 Animazioni come strumento di analisi

`capture-frames!` (§2.3) è oggi il loop di cattura visiva: per ogni frame, render del canvas e cattura. La sua generalizzazione naturale è sostituire "cattura il canvas" con "esegui una funzione utente arbitraria" — misura geometrica, predicato, calcolo. L'output non è più un GIF: è una traccia temporale di una proprietà del modello.

Per la cerniera di `01-hinge-c-profile`, a ogni `t` calcoli `(mesh-volume (mesh-intersection inner-hinge outer-hinge))`: se è zero per ogni `t` la cerniera è meccanicamente valida, se è non-zero per qualche `t` hai un'interferenza e sai *quando* (per quale angolo) e *quanto* (volume). È un salto di categoria: oggi le animazioni di Ridley servono a *vedere* il movimento, con questa estensione servirebbero a *verificarlo* — sostituendo molte iterazioni di "stampo, monto, riprogetto" con due righe di check nel sorgente. Diventa pertinente appena scriveremo esempi meccanici più complessi (manovellismi, giunti cardanici, meccanismi a quattro barre).

L'API è stratificata, una sola implementazione. La primitiva **`anim-fold`** ha semantica reduce con supporto a `reduced` per stop precoce: accumula su tutti i frame, ferma appena l'utente decide. Sopra di essa, due helper. **`anim-sample`** è `anim-fold` con `:init [] :step conj`: ritorna un vettore di N misure, adatto al caso comune di produrre un trace per plotting o ispezione. **`anim-check`** è `anim-fold` con un predicato ed early-stop alla prima falsità, adatto alle verifiche pre-confezionate.

Lo spec va scritto prima di implementare. Aperti: la firma esatta degli helper; una libreria di check pre-confezionati (`point-in-mesh` leggero contro `mesh-intersection` costoso, perché 90 booleane Manifold per check non si possono permettere sempre); eventuale visualizzazione del trace sovrapposta all'animazione, voce secondaria.

---

## Parte III — Lungo termine

Le voci di questa parte sono visioni del progetto, non piani. Hanno orizzonte di anni o di "quando sarà il momento", e il loro design è ancora in larga parte aperto.

### 3.1 WebXR di seconda generazione

Il sottosistema WebXR (sezione 11.8 di `Architecture.md`) è oggi una superficie di rendering: la scena è visualizzata in immersione, ma manca la simmetria del lato editing. Il sottosistema voce, pensato come canale di edit alternativo alla tastiera fisica, è in pausa di sviluppo (15.4.1) in attesa di sblocco da parte della 15.4.2: la visualizzazione del sorgente in XR.

Il problema di 15.4.2 è di design, non di implementazione. Una semplice trasposizione di CodeMirror in 3D non è una soluzione: un editor di testo galleggiante in uno spazio virtuale non è ergonomico né per leggere né per editare. La domanda è cosa significhi "vedere il codice" in un contesto immersivo, dove le metafore del piano 2D non valgono e quelle dello spazio reale non sono ancora codificate da convenzioni stabili.

Quando questa domanda trova risposta, il bundle si sblocca e diventa fattibile in tempi misurabili: rendering della rappresentazione scelta, riapertura della voce con aggiornamento dei matching i18n e dell'help db al DSL corrente, integrazione fra voce ed edit del codice nel visore.

La quinta direzione di 15.4.6 — composizione visuale 3D del codice come blocchetti funzionali assemblabili in immersione — è una variante diversa dello stesso problema, presa dall'angolo "come faccio a comporre il codice in 3D" invece di "come faccio a vedere il codice in 3D". È visione lunga, con elemento esogeno (richiede progressi di paradigma in interaction design XR che oggi non esistono come stato dell'arte).

### 3.2 Versioning e storia del modello via git

Il modello in Ridley è codice. Il codice si versiona con git. La conseguenza naturale è che il "salvataggio del progetto" e la "storia del modello" coincidono con commit e log di un repository git, e l'undo "vero" è un checkout su un commit precedente.

L'integrazione git nativa nella versione desktop sfrutterebbe questa coincidenza. Una vista temporale dei commit del file corrente, ognuno cliccabile per visualizzare lo stato del modello a quel punto. Commit automatici a ogni eval di successo, o manuali con messaggio. Branch per esplorare varianti del modello senza perdere la linea principale.

Questa direzione sostituisce e supera l'idea di un undo/redo globale del modello: invece di un meccanismo dedicato di history a runtime, si appoggia su un sistema (git) che già conosce versioning, branch, merge, diff. La WebView ha accesso al filesystem desktop tramite il sidecar Rust, quindi le primitive git sono disponibili lato server con un'API HTTP nuova.

Il design da prendere include: granularità del commit (per eval, per save, manuale), interfaccia utente (pannello laterale, vista timeline, integrazione nell'editor), comportamento sui branch e sui conflitti. È visione di lungo termine, non perché il lavoro tecnico sia enorme, ma perché richiede una visione editoriale matura del rapporto fra utente e storia del proprio modello.

### 3.3 Fine-tuning di un modello small su corpus Ridley

La via massima del cap. 15.4.5: fine-tuning di un modello small (dimensioni nell'ordine dei 7-13B parametri) su un corpus crescente di codice Ridley scritto a mano. È il pagamento più sostanzioso della diagnosi sull'AI: invece di compensare la sotto-rappresentazione del corpus nel training set dei modelli generici, addestrare un modello che ha visto codice Ridley.

I prerequisiti sono tre. Un volume di codice esemplare significativamente più ampio di quello attuale (la cartella `examples/` ha 23 file `.clj` oggi; per fine-tuning utile servono ordini di grandezza in più). Infrastruttura di addestramento che il progetto non ha. Una visione del prodotto — il modello fine-tuned diventa un asset distribuibile, e questo cambia il modello di distribuzione di Ridley.

È visione di lungo periodo, e ha un elemento esogeno: dipende dall'evoluzione dei modelli small open weight, dal costo dell'addestramento, e dal volume di codice Ridley pubblico nel tempo. Resta in roadmap come direzione consapevolmente nominata, non come piano operativo.

---

## Parte IV — Sperimentazioni tecniche

Le voci di questa parte sono linee aperte sul fronte performance e architettura, dove non c'è ancora consenso su se valga la pena pagare il costo. Vivono su un asse separato dalle Parti I-III: non hanno orizzonte temporale stabilito, e la decisione di intraprenderle dipende da rivalutazioni quando i vincoli cambiano.

### 4.1 Trasporto binario mesh Rust↔SCI

Il trasporto attuale fra geo-server Rust e CLJS passa per JSON sincrono via XHR. Il cap. 9.3 di `Architecture.md` documenta il razionale: SCI è sincrono, le mesh devono ritornare prima che `register` continui, e fra le opzioni sincrone JSON è risultato più veloce delle alternative binarie a causa dello scan UTF-8 sui body `application/octet-stream` in WKWebView e Chrome.

La rivalutazione del trasporto torna in considerazione se uno dei vincoli cambia. Il candidato principale è la sostituzione delle dipendenze CDN con bundle locale, che farebbe cadere il problema COEP e renderebbe percorribile la via SharedArrayBuffer + Web Worker per pseudo-sync su un canale async. Quel cambio richiede 3-5 giorni di refactor con regressioni rischiose, e il `transport-audit.md` raccoglie il quadro completo delle alternative valutate.

Per ora il rapporto fra costo e beneficio non si paga e l'attuale trasporto regge. La voce resta in pista come esperimento da fare se le condizioni cambiano.

### 4.2 Web Worker per SCI evaluation

Spostare la valutazione SCI fuori dal main thread. Lavoro nell'ordine di una-due settimane. Tre blocchi pesanti: gli atom condivisi (turtle, registry, stato persistente del runtime DSL) richiedono un message passing strutturato; Three.js è main-thread-only, quindi le mesh prodotte da SCI andrebbero serializzate worker→main; Manifold (via geo-server HTTP) e SDF sono già off-thread, riducendo il guadagno netto della parallelizzazione.

Il valore della voce è discutibile: dato che le operazioni geometriche pesanti sono già off-thread, lo SCI sul main thread blocca solo per il costo della sua valutazione pura, che per script tipici è nell'ordine di decine di millisecondi. Lo sblocco interessante non è la performance ma la responsività della UI durante eval lunghe. Va tenuta in pista come esperimento da fare in coppia con 4.1, perché entrambe puntano allo stesso obiettivo per vie diverse.

### 4.3 Incremental geometry updates

Oggi una modifica al sorgente innesca un full clear della scena Three.js seguito da un create-three-mesh per ogni mesh registrata. Per scene piccole il costo è invisibile; per scene grandi (decine di mesh, qualcuna con vertex count alto) il delay diventa percepibile.

Il piano di lavoro è un diff per identity/hash mesh fra rebuild successivi: solo le mesh effettivamente cambiate vengono ricreate, le altre restano in scena. L'animation system fa già qualcosa di simile in `apply-mesh-pose!`, ma per rigid transforms via `position`/`quaternion` su Object3D senza ricostruire geometry. Per il caso editor il diff è più sottile perché le mesh possono cambiare in topologia, non solo in posa. Una settimana di lavoro per un primo cut.

### 4.4 Level-of-detail per viewport

Riduzione del dettaglio delle mesh distanti dalla camera. Il modulo `mesh-simplify` esiste, ma non è chiamato dal viewport, e Three.js LOD non è usato. Il blocco è la cache: la decimazione real-time è troppo lenta, quindi serve una cache di mesh decimati pre-calcolati a soglie multiple. Tre-cinque giorni di lavoro più il design della cache.

Come 4.3, è una voce di performance la cui priorità dipende dalla dimensione tipica delle scene utente. Oggi non è urgente. Lo diventerà se il progetto si sposterà verso scene grandi (assemblaggi complessi, moduli architettonici parametrici, decorazioni a lattice generative).

---

## Come si paga la roadmap

Le voci della Parte I sono pagamenti di debito già preso e completamenti di lavori in corso: vanno fatte, e il quando dipende solo dalla cadenza di lavoro. La bonifica degli esempi e la revisione della manualistica sono priorità prima di tutto perché toccano la prima superficie di contatto col progetto, e uno stato di fatto disallineato qui costa più degli altri tipi di disallineamento.

Le voci della Parte II sono direzioni di esplorazione, non promesse. La scelta di quale perseguire e in che ordine dipende da segnali — feedback degli utenti, nuove dipendenze esterne mature, momenti di interesse pubblico per certe aree del progetto. La sequenza fra 2.1 (AI), 2.2 (superfici di edit), 2.3 (animation export), 2.4 (geometria), 2.5 (apertura esterna), 2.6 (librerie) non è prescrittiva.

Le voci della Parte III sono visioni: vivono come direzioni nominate, e prendono concretezza solo quando una decisione editoriale e una serie di prerequisiti convergono. La 3.1 dipende da una svolta di design XR. La 3.2 dipende da una visione editoriale matura. La 3.3 dipende da maturazione di tecnologia esterna e crescita del corpus pubblico Ridley.

Le voci della Parte IV vivono come opzioni: si attivano se rivalutazione dei vincoli cambia il rapporto costo/beneficio, e fino a quel momento restano nominate.

La roadmap è uno strumento di consapevolezza, non di pianificazione vincolante. Il principio guida è quello del cap. 16 di `Architecture.md`: turtle-centricity come bussola, codice come fonte di verità, astrazioni a soglia. Ogni voce di questo documento, quando arriva il momento di essere pagata, si misura contro questo principio.

## RAW
### mesh→SDF (sampling-based)

Convertire una mesh arbitraria in SDF tramite signed distance sampling.
Apre offset arbitrari su mesh importate o risultanti da booleane, blend
mesh↔SDF, smoothing/morphing via operatori SDF. Punto di aggancio
naturale: API libfive che accetta distance functions arbitrarie →
`mesh-distance-fn` che restituisce un SDF chiamabile.

Tre blocchi: (1) BVH lookup per query veloce (Manifold ha primitive utili,
o impl. propria); (2) signed test (raycast parity, oppure normali +
inside/outside); (3) wrapping libfive come SDF node che il resto del
sistema usa come gli altri.

Versione iniziale: approssimazione BVH (veloce, non distance-field puro).
Versione esatta come passo successivo. Costo scala con complessità mesh
— per mesh leggere fattibile, per mesh dense pesante. Stima impegno:
1-2 settimane per prototipo funzionante.

### Lattice infill

Fill a solid volume with procedural internal structure (TPMS, strut lattice, etc.). Pipeline: mesh → bounding box → voxel grid → implicit function evaluation → marching cubes → lattice mesh. Enables lightweight structural parts like those produced by DMLS/SLA 3D printing.

**Key components:**
- Implicit density functions: Gyroid (`sin(x)*cos(y) + sin(y)*cos(z) + sin(z)*cos(x)`), Schwarz-P, Diamond, strut-based
- Point-in-mesh test (ray casting or signed distance field)
- Marching cubes (or dual contouring) for isosurface extraction
- DSL: `(lattice mesh :type :gyroid :scale 5 :thickness 0.3)` or `(lattice mesh :fn (fn [x y z] ...))`

### Path↔SDF binding

Modo per "legare" i path agli SDF, analogo concettuale di `bezier-as` ma sul versante distance field: un path produce o partecipa a un SDF, e i marker del path diventano punti di aggancio nello spazio SDF. Sblocca l'uso di operatori SDF (smooth-union, offset, blending continuo) su geometrie generate da path, oggi confinate al ramo mesh.

Caso d'uso motivante — il portachiavi KHP, oggi scritto come:

```clojure
(register D
  (mesh-union
    (attach (extrude (rect trace-w trace-w) (play-path KHP)))
    (on-anchors KHP
      "key-" :align (attach (socket KP) (th 90) (tv 180) (f -2))
      #{:start :end} (attach (washer) (tv -90)))))
```

vorremmo poterlo riscrivere in versione SDF, in particolare per ottenere raccordi smooth fra arco e socket invece di intersezioni booleane secche. Tre direzioni con DSL diverso da valutare.

**A. Sweep — `sdf-extrude` (calco diretto di `extrude`).** Un profilo SDF 2D viene sweeppato lungo il path, l'output è un SDF tubo.

```clojure
(register D
  (sdf-mesh
    (sdf-smooth-union 1.5
      (sdf-extrude (sdf-rect2d trace-w trace-w) KHP)
      (on-anchors KHP
        "key-" :align (sdf-attach (sdf-socket KP) (th 90) (tv 180) (f -2))
        #{:start :end} (sdf-attach (sdf-washer) (tv -90))))))
```

Pro: salto dal codice mesh minimo, vocabolario familiare. Contro: serve `on-anchors` polimorfico (o gemello SDF) e un'aritmetica di pose SDF — raddoppio di superficie.

**B. Bend — `sdf-bend-along` (lo spazio si piega col path).** Un SDF rettilineo (es. un box lungo `path-length`) viene riparametrizzato in arc-length lungo il path. Il path non genera niente, deforma.

```clojure
(register D
  (sdf-bend-along KHP
    (sdf-smooth-union 1.5
      (sdf-box (path-length KHP) trace-w trace-w)
      (sdf-along-axis (key-positions KHP "key-")
        (sdf-socket KP)))))
```

I marker diventano coordinate scalari `s` lungo l'asse X di un mondo "dritto", poi tutto si piega insieme. Pro: matematicamente pulito, raccordi perfetti per costruzione, una sola operazione. Contro: DSL meno familiare (pensi in spazio dritto e ti fidi del bend), path con torsione forte distorce le sezioni, marker perdono il frame e restano solo scalari.

**C. Anchors come SDF — `sdf-on-path`.** Niente sweep esplicito: il path produce solo posizioni con frame, e ogni segmento fra marker consecutivi è una capsula SDF, ogni marker è una primitiva SDF, il tutto unito smooth.

```clojure
(register D
  (sdf-mesh
    (sdf-on-path KHP :smooth 1.5
      :segment    (sdf-capsule-section trace-w)
      :marker "key-" (sdf-socket KP)
      :marker #{:start :end} (sdf-washer))))
```

Pro: entry point unico, marker e tubo trattati uniformemente, blending smooth come default. Contro: il "tubo" è approssimazione a capsule (più capsule = SDF più costoso), meno controllo su sezioni non circolari, costringe a pensare il path come "perline su filo" invece che come traiettoria continua.

**Lettura.** A è il path di minor sorpresa per chi viene da Ridley mesh, ma raddoppia il vocabolario (un `sdf-` per ogni primitiva di pose). B è il più "SDF-puro" e regalerebbe gratis modulazione del raggio lungo `s`, twist, taper — cose impossibili pulitamente con la mesh — al costo di un cambio di mentalità. C è il più compatto come DSL e probabilmente il più semplice da implementare bene, ma la semantica "perline su filo" è diversa da `extrude + on-anchors` e i raccordi tubo↔socket diventano parte dell'astrazione, non opzionali.

Costo computazionale comune a tutte e tre: una `sdf-extrude` / `sdf-bend-along` / `sdf-on-path` è O(N) per sample point sui N segmenti del path, quindi un path lungo chiede BVH o grid spaziale per restare interattivo. Per path corti (≲50 segmenti) trascurabile.

Da decidere prima di prototipare: quale delle tre direzioni vale la pena pagare, e se vale la pena perseguirne più di una in parallelo (A e C non sono mutuamente esclusive — A è "tubo SDF puro", C è "tubo + anchor SDF in un'unica operazione").

### Attach on mesh collections (structure-preserving)

Extend `attach` to work on nested vectors of meshes, transforming all meshes while preserving the nesting structure. This enables treating function-composed groups as rigid bodies:

```clojure
(defn branch [l] ...)              ;; returns a mesh
(defn ring [l n] ...)              ;; returns [mesh mesh ...]
(defn tree [l h rings branches]    ;; returns [[mesh ...] [mesh ...] ...]
  (for [i (range rings)]
    (do (f (/ h rings)) (ring l branches))))

(def t (tree 50 100 5 5))
(register T (attach t (f 20) (th 45)))
;; t2 has same nested structure, all vertices transformed
```

Key principles:
- **Preserve structure**: `attach` walks recursively, transforms meshes in-place, returns same nesting shape. No flattening.
- **Pivot = current turtle position**: the turtle's position at call time is the transformation pivot.
- **Composability**: since structure is preserved, the result can be passed to another `attach` or used in further function composition.
- **Revisit `register` flatten**: the current flatten in `register` is a workaround. Ideally the registry and viewport should walk nested structures for rendering without flattening, so that `(hide :T 2)` can address sub-groups by index path.
