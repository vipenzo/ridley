# HANDOVER — zero click: la gabbia si legge da sola

## In una riga

Vincenzo (2026-08-27): «se non riusciamo ad avere la registrazione automatica
delle foto sarà tutto inutile». La fetta è partita lo stesso giorno: **2 foto su
8 si registrano da sole** (camera a 1.0 e 3.5mm dalla mano, zero falsi), e le sei
rifiutate muoiono TUTTE nello stesso punto — lo stadio delle ellissi. Il lavoro
è alzare quel 2/8, e il banco per misurarlo è in piedi.

Entry point precedente, con tutta la storia della gabbia (detector, riletture,
anello incollato a 90°, chiave di montaggio, portapezzi, la settimana
dell'avvelenamento): `dev-docs/HANDOVER-cage-auto-detect.md`. Questo file è lo
stato dei lavori per ripartire.

## Lo stato, tutto insieme

- **Rilevatore** (`blob_detect.cljs`, `cage-opts`): fatto e stabile. Riferimento
  chiaro = massimo locale, risoluzione piena, `enclosed-frac` (16 raggi sui
  pixel veri). Su foto vera: ~30 candidati, 13/13 mark noti. NOTA misurata sul
  banco zero-click: trova 16–31 candidati dove la mano ne ha cliccati 19–30 —
  il recall è una delle tre leve (sotto).
- **Lettura seminata** (`match_cage.cljs` / `read-crown`): fatto. 4 click su UN
  anello + zero-indice → le 48 riletture arbitrate dal resto della gabbia, lo
  zero non si mette ai voti, `phase-probe` per gli anelli incollati a passi
  interi. In `a` quando ci sono ≥4 click.
- **Lettura ZERO CLICK** (`match_cage.cljs` / `auto-read`): prima luce, commit
  `1626c0a`. In `a` quando i click sono 0; il seeded è il ripiego e il
  messaggio lo dice. Catena: candidati → ipotesi ellisse
  (`ellipse/fit-inliers-ranked`, min 8 inlier, top 6) → rango per REGOLARITÀ
  ANGOLARE → per ipotesi × faccia, `mp/assign-marks` (corona + zero verificati
  sui pixel) sotto budget `:max-identify` (18) → posa del seme → `assign` su
  tutta la gabbia → solve pieno → guardie (rms ≤ `accept-rms-px`,
  `sees-its-own-picks?`, `phase-probe`) → **migliore** per
  `[explained off-ring rms]`.
- **La gabbia fisica**: quella NUOVA (chiave di montaggio + portapezzi, stampata
  2026-08-25) si modella `(registration-cage :d 176)` liscia. Quella VECCHIA ha
  l'anello grande incollato a 90°: `(registration-cage :d 176 :phases {:x 90})`,
  sempre. **VERDETTO 2026-08-27 sera (misurato sui pixel, battiscopa1)**: la
  gabbia NUOVA di Vincenzo montata sta con l'anello Y a **180° dal modello** →
  per LEI serve `:phases {:y 180}`. Prova: sotto la posa registrata di grab-01
  (21 mark, 13.4px, ancorata dall'indice di Z che riproietta a 9px dal suo
  pick → non è il gemello), lo zero-yp del modello proietta su banda LISCIA a
  [1223 806], mentre lo zero-yp GIRATO di 180° proietta a [1028 516] ≈ il
  doppio pallino che Vincenzo aveva cliccato ([1021 479], visibile nel crop —
  coppia indice+mark inconfondibile). Il suo «Y è girato di 180°» era
  giusto DUE volte; il rifiuto del suggerimento `:phases` («su altre foto
  funzionava») non reggeva: quelle registrazioni non avevano mai verificato
  l'indice di Y coi nomi accesi. Con `{:y 180}` dichiarato, Vincenzo confronta
  proxy e gabbia vera: anche **X è a 180°** (Z è giusto — il suo indice
  riproiettava a 9px). Stato finale della gabbia montata:
  **`:phases {:y 180 :x 180}`**. PERCHÉ — CONFERMATO DAL TEST FISICO
  (Vincenzo, 28/8): la chiave NON è a una via — l'anello X si monta anche
  RIBALTATO (facce m/p scambiate). Le fasi sono quindi un fatto
  PER-MONTAGGIO: la gabbia si apre a ogni cambio pezzo (il portapezzi sta
  dentro), ogni apertura rilancia la moneta di ogni anello, e i `:phases`
  dichiarati muoiono con lo smontaggio (la sessione-verità del 25/8 senza
  fasi era la STESSA gabbia in un altro giro). Due rami di cura, in ordine
  di resa: (1) SOFTWARE, preferito perché copre anche le stampe esistenti —
  scoprire le fasi DALLA FOTO: gli zero-indice cliccati o rilevati arbitrano
  la fase di ogni anello, stesso meccanismo del veto già in lista; a regime
  `:phases` diventa inutile. (2) STAMPA — una chiave che vieti ANCHE il
  ribaltamento: il vincolo va rotto fuori dal piano dell'anello, non solo in
  azimut (da progettare su `key-pin-azim`/`joint-tabs`).
  Il phase-probe resta cieco a 6 passi (i
  dischetti ricadono identici, solo lo zero si sposta) e il solve scartava
  come outlier proprio i click sullo zero. **Fetta da fare**: gli
  zero-indice cliccati A MANO su anelli diversi dal seme diventano un VETO
  dentro read-crown (una lettura la cui posa riproietta quel doppio pallino
  dall'altra parte dell'anello è contraddetta da un fatto, non da un punteggio
  — confronto min sulle due facce zero-yp/zero-ym, soglia larga: il gemello
  sbaglia di centinaia di px), e in on-solve-pnp! uno zero a mano non è
  scartabile come outlier senza prima provare la rilettura a k passi del suo
  anello. Il workaround «rendi dominante l'anello conteso» NON è bastato: la
  lettura seminata dall'anello Y pieno (13 click, zero incluso) ha DICHIARATO
  il pareggio («1 riletture spiegano la gabbia altrettanto bene») e scelto il
  gemello sbagliato — l'arbitro vero è lo zero cliccato dell'ALTRO anello.
  In più la notte ha scoperto DUE bug fratelli:
  (a) le proposte di corr possono DOPPIO-PRENOTARE un dischetto già occupato
  da un click a mano sotto il nome dell'altra faccia (misurato: 5 dischetti di
  Z con zm* e zp* insieme, stesso pixel → fit 194.9px, nessuna rilettura può
  salvarlo. `kept` filtra per INDICE, serve anche il filtro per DISTANZA
  PIXEL dai click a mano). MECCANISMO INCHIODATO il 28/8 (foto 4, log di
  Vincenzo): NON servono proposte stantie — è corr stessa che, accettati i
  click sulla faccia p di un anello, propone l'INTERA faccia m sopra di loro
  (le due facce proiettano a ~2px attraverso la plastica): `zp01` [1159 931]
  e `zm01` [1159 931], pixel identico. Con 2 doppioni il solve li scarta e
  registra; con 10 muore camera-dietro. Il fix: nessuna proposta a meno di
  ~snap-radius px da un pick esistente, qualunque nome porti;
  (b) NON ESISTE un gesto per cancellare un pick — una foto avvelenata non si
  ripara a mano. Serve la gomma (e/o «pulisci i pick di questa foto»).
  Fixture reale per il banco (battiscopa1, grab della sera, 1920×1440 — NB
  la lente vera di questa camera è ≈44mm-equiv, MISURATA 2026-08-28 sul set
  pulito a 32 pick del proxy con le fasi: min 3.97px a 44, 22.5px a 26; la
  rifinitura congiunta si assesta a 47. Il «~26-30» creduto il 27/8 era
  misurato su pick con X ancora etichettato girato — mai misurare la lente
  su etichette non verificate — la lista pick del rifiuto finale, Y a nomi giusti per Vincenzo,
  Z/X contaminati dal gemello + 5 doppioni):
  [[:xm00 [1046 24]] [:xm01 [1123 114]] [:zm11 [802 737]] [:zp00 [1451 511]]
   [:zp01 [1326 360]] [:yp00 [1082 453]] [:zp02 [1134 287]] [:yp01 [887 511]]
   [:yp02 [731 598]] [:zp04 [805 423]] [:xm07 [1112 1207]] [:yp03 [653 702]]
   [:yp04 [689 798]] [:zp06 [802 737]] [:yp05 [865 856]] [:yp06 [1142 840]]
   [:zp08 [1079 931]] [:yp07 [1415 752]] [:yp08 [1572 630]] [:zp10 [1396 837]]
   [:yp09 [1577 520]] [:yp10 [1466 452]] [:yp11 [1285 432]]
   [:zero-yp [1021 479]] [:zero-zp [1381 480]] [:zm01 [805 422]]
   [:zm03 [1134 286]] [:zm04 [1326 360]] [:zm05 [1451 511]]] La chiave (`:key-pin`/`:key-notch` in `joint-tabs`) vieta i
  quarti di giro ma NON il ribaltamento (test fisico 28/8, vedi sopra: le
  fasi sono per-montaggio); gli slot del portapezzi (`stick-slots`, 2 per
  anello a 60°/240°) portano gli stick ellittici collaudati da Vincenzo
  (`acquire-cage/stick`, `punta-tricuspide`).
  **CODA DI RISTAMPA** (per quando si rimette mano alla gabbia):
  1. chiave anti-ribaltamento — il vincolo va rotto fuori dal piano
     dell'anello;
  2. fori/canali degli stick PIÙ ELLITTICI (Vincenzo 28/8: negli anelli Y e
     Z gli stick fanno meno attrito che in X, causa ignota — sospetto la
     curvatura di banda che cambia col raggio dell'anello; da guardare in
     `stick-slots` prima di ritoccare i numeri).
- **Sessione battiscopa**: 8/8 registrate a mano, focale rifinita 48.9mm — è la
  VERITÀ del banco zero-click.
- **PRIMA SESSIONE LIVE END-TO-END (battiscopa1, chiusa 2026-08-28)**: 5 frame
  grabbati (1920×1440, Continuity iPhone), tutti registrati col giro
  «un anello + `a`», rifinitura congiunta su 5 → focale 45.33mm (coerente col
  44 misurato), riproiezione 7.57px, per-foto 4.8–12.2px. Il giro che REGGE:
  Azzera se la foto è sporca → 4–8 click ALT su UN solo anello (zero se
  visibile) → `a` → `n` → mai un secondo anello a mano finché il fix (a) non
  è dentro. Se un anello-seme non legge il resto della gabbia, provarne un
  ALTRO (sulla stessa foto X falliva, Z affogava nei doppioni, Y registrava
  a 7.1px — l'istinto di Vincenzo su quale anello usare ha battuto il
  consiglio calcolato TRE volte).
- **Presa dal vivo su gabbia** (2026-08-27, non committata): il Grab ora TIENE
  il frame come foto libera (θ nil, `keep-live-frame-unregistered!`) invece di
  rimbalzarlo — prima ogni presa moriva sulla via automatica del piatto e il
  consiglio «'p' poi 'a'» era inapplicabile a una foto mai entrata in pellicola
  (una sessione intera di grab scartati). La registrazione resta a mano
  ('p'+'a'); cablare `auto-read` nel grab è rinviato finché sta a 2/8 con
  25–40s per rifiuto. NB: un frame grabbato non ha EXIF — la sessione parte
  alla focale di default finché la rifinitura non la misura.

## Le quattro regole di auto-read, tutte misurate prima di essere scritte

1. **Il seme della macchina NON passa dal voto a 48.** Il voto esiste per
   etichette non fidate (le mani); il seme automatico ha lo zero-indice puntato
   e la faccia giudicata sui pixel — prove migliori del conteggio dei candidati.
   Il voto gli rompeva i nomi (rot 5 → 547mm) o lo uccideva coi pareggi del
   gemello attraverso-la-plastica.
2. **Mai il primo che passa: il migliore.** Gli stessi 11 dischetti si
   identificano come anello X E come anello Y (stesso cerchio, raggio diverso —
   la posa assorbe la scala nella distanza), entrambi a rms pulito, entrambi
   oltre la guardia. Li separa solo quanto del RESTO della gabbia spiegano
   (13 contro 12): first-wins ha spedito il 12, camera a 697mm.
3. **Niente filtro competitivo per taglia** (la regola del piatto, sbagliata
   qui): una conica-spazzatura che infila tre corone intrecciate raccoglie PIÙ
   inlier di qualunque corona vera (13 e 16 contro 12, misurato sul sintetico
   — e il filtro `≥ biggest−3` cancellava ogni anello vero in piena vista).
   Al suo posto: regolarità angolare + budget.
4. **Il budget di identità** (`:max-identify`): C(12,k) è il costo intero —
   identificare k=7 punti fra 12 mark enumera 11088 sottoinsiemi. Senza budget i
   rifiuti costavano 40–65s; con, 25–40. Ancora troppi: è UI bloccante.

## La frontiera — perché 2/8 e non 8/8

Le sei rifiutate muoiono tutte allo stadio ellissi: l'anello vero non emerge fra
le ipotesi RANSAC (o non raccoglie 8 inlier puliti, o le coniche miste lo
seppelliscono). Tre leve, in ordine di resa attesa:

1. **Concentricità**: i tre anelli condividono il centro, e il RANSAC ancora non
   lo sa — ipotizza coniche indipendenti. Un RANSAC che ipotizza la FAMIGLIA
   concentrica (o anche solo: trovata un'ellisse, cerca le sorelle intorno allo
   stesso centro) cambia il gioco.
2. **Identità condivisa fra famiglie di anello**: X/Y/Z hanno la stessa
   geometria a meno di scala — oggi la stessa ricerca C(12,k) si paga 6 volte
   (una per faccia). Una ricerca sola, poi il raggio decide la famiglia.
3. **Recall del rilevatore su queste foto**: 16–31 candidati contro i 19–30
   pick della mano (foto 8: 16 contro 19). Ogni dischetto perso è un inlier in
   meno per l'ellisse. `cage-opts` fu tarato sulle foto della gabbia vecchia
   senza pezzo dentro.

E il tempo: 25–40s di UI bloccata per un rifiuto non è spedibile oltre il
prototipo — o si accorcia (le leve 1–2 aiutano anche qui), o si sposta su un
worker.

## Il banco di prova, e usalo

- **`node out/cage-auto.js`** — zero-click contro la sessione battiscopa
  (`test-assets/cage-battiscopa`, jpeg non tracciate, `acquire-state.json` =
  verità: pose camera a mano). Stampa per foto: candidati, seme, spiega, rms, e
  **a quanti mm atterra la camera dalla mano**. Su un rifiuto stampa la traccia
  (`:trace`) — stadio ipotesi e ogni tentativo di identità.
- **`CAGE_AUTO_SYNTH=1 node out/cage-auto.js`** — la scena sintetica (39
  candidati perfetti, 3 anelli): deve dare 39/39 a 0.00mm. Se la rompi, hai
  rotto la catena, non le tarature.
- **`node out/cage-study.js <dir>`** — il rilevatore da solo (candidati +
  recall per anello su IMG_9014).
- **`node out/cage-fit.js`** — le premesse della fetta 2 (riletture, fasi,
  facce) su `test-assets/cage-presa`.
- Build: `:cage-auto`, `:cage-study`, `:cage-fit` in shadow-cljs.edn. Compila
  SEMPRE via nREPL (`(shadow.cljs.devtools.api/compile :cage-auto)`), mai un
  secondo processo CLI col watcher attivo.
- Suite: `compile :test` + `node out/test.js` → 980 test, 0 fallimenti al
  momento del commit `1626c0a`. Il conteggio dei warning è un segnale: 16 su
  :app, 127 su :test — se cambia, qualcosa è regredito.

## Trappole, tutte pagate questa settimana

- **`rotate` su mesh gira attorno alla creation-pose, che `mesh-translate`
  porta con sé**: orientare PRIMA all'origine, traslare dopo (gli slot
  finivano tutti ad azimut zero).
- **In CLJS `(nth coll nil default)` LANCIA** — un indice nil è rifiutato anche
  col default. `get` su un vettore.
- **`:pnp?` vive solo in memoria**: dopo una ricarica una foto registrata si
  riconosce con `registered-result?` (guarda l'rms persistito), non col flag.
- **Un soccorso che non compra un fit sotto soglia non rinomina i pick** — a
  103px stava tirando a indovinare e persisteva l'indovinello.
- **La rifinitura congiunta**: una foto sopra 3× la mediana non vota; una foto
  MAI registrata non vota qualunque click abbia; un risultato peggiore non si
  adotta. (La focale avvelenata a 61mm è costata tre giorni: ogni pezzo di
  questa frase è un buco trovato da un log di Vincenzo.)
- **Verificare la UI dal vivo, non col compile**: `scripts/dev-browser.sh
  start` + `eval` via CDP; i namespace stanno su `window.ridley.*`, i valori
  CLJS si costruiscono da `window.cljs.core`. Una chiamata a vuoto ha trovato
  il bug di `nth` che nessun test con sessione aperta poteva vedere.
- **Un solo Ridley alla volta** (porta 12321), **provare sulle COPIE** in
  test-assets, mai sulla sessione viva.
- **La libreria builtin sta su filesystem condiviso** (`~/.ridley/libraries/`):
  un'app desktop vecchia può eseguire una libreria nuova che chiama binding che
  il suo bundle non ha. Non è versionata contro l'app.
- **I pick di `acquire-state.json` non sono verità**: su IMG_9014 quattro su
  quattordici erano sbagliati (tre `proposed?` su fondo/banda, uno col nome del
  vicino). La verità si legge sulla foto a 4×, e vive nelle fixture dei test.

## Lavorare con Vincenzo, su questo fronte

- I suoi log incollati sono lo strumento di misura principale: ogni bug della
  settimana è stato trovato da un suo log, non da un test.
- La sua lettura del PEZZO è un arbitro indipendente (le facce: «X e Z sono m,
  Y è p» ha deciso il segno del quarto di giro quando i candidati non
  potevano).
- Ha collaudato lui l'ellisse-camma del portapezzi e proposto lui la chiave di
  montaggio («una tacca e una spina») — le idee di fabbricazione buone di
  questo progetto sono le sue; il lavoro è dargli i numeri per giudicarle.
- Le istruzioni operative finali vanno in «Prossimi passi per te» (regole in
  CLAUDE.md: bottoni esatti, un'azione per passo, niente nomi interni).

## File

- `src/ridley/photogrammetry/match_cage.cljs` — `read-crown` (seeded),
  `auto-read` (zero click), `ring-faces`, `phase-probe`
- `src/ridley/photogrammetry/blob_detect.cljs` — rilevatore, `cage-opts`,
  `enclosed-frac`
- `src/ridley/photogrammetry/match_plate.cljs` — `crown-ring-hypotheses`,
  `assign-marks` (l'identità che auto-read riusa)
- `src/ridley/photogrammetry/ellipse.cljs` — `fit-inliers-ranked` (lo stadio
  dove muoiono le sei rifiutate)
- `src/ridley/photogrammetry/cage.cljs` — geometria, `stick-slots`, chiave,
  `:phases`
- `src/ridley/editor/edit_acquire.cljs` — `cage-read-and-place!` (il tasto
  `a`, tre rami: 0 click → auto, ≥4 → seeded, 1–3 → messaggio),
  `on-refine-session!` (le guardie della rifinitura)
- `test/ridley/photogrammetry/cage_auto_study.cljs` — il banco zero-click
- `test/ridley/photogrammetry/match_cage_test.cljs` — sintetici + foto vera
- `public/builtin-libraries/acquire-cage.clj` — stampa: anelli, chiave, slot,
  stick, punta-tricuspide, culla
- Commit della settimana: `834c92b` detector → `fee7154`/`ab79a8b` read-crown →
  `c2dbee1` tasto a → `06eee7e` zero-indice/90° → `1e300b6` chiave → `c5d7692`
  portapezzi → `c341015`…`fdbfe01` guardie rifinitura → `1626c0a` zero click.
