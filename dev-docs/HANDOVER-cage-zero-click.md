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

**STATO AL 30/8, per chi riparte da qui**: l'IDENTIFICAZIONE
CONTAMINATION-PROOF (leva 1) è COSTRUITA, TESTATA (988/0) e MISURATA AL BANCO —
e il banco ha spostato la frontiera. Il meccanismo: `ellipse/comb-teeth` (il
pettine come PRIMA MOSSA dell'identità — gap-snapping ciclico in anomalia
eccentrica; tolleranza scalata con la campata del gap perché la deriva
prospettica si ACCUMULA coi passi attraversati, misurato 2.70 per un gap da 3;
riparazione guidata da Σgap=12) + `assign-marks :teeth` (24 candidati — 12
rotazioni × 2 versi — al posto di C(12,k)·k·2: i buchi dell'anello parziale si
leggono dai denti invece di enumerarli, e l'intruso non arriva MAI
all'omografia perché non ha dente) + doppio tentativo per (ipotesi × faccia)
in `auto-read` (denti gratis, enumerazione baseline sotto lo STESSO budget —
la baseline non può peggiorare per costruzione). MA il cablaggio di produzione
resta col gate CHIUSO (`:teeth?` off, stesso criterio del 29/8: zero falsi
batte qualunque tasso): coi denti accesi foto 6 e 7 si REGISTRANO DAL GEMELLO
attraverso-la-plastica (548/764mm dalla verità) — la corona ora si identifica
A PARI MERITO su tutte e sei le facce (misurato: corona 11 su ognuna) e decide
SOLO lo zero-indice al giudice dei pixel, che su quei frame passa esattamente
sulla faccia del gemello (lo zero del lato vero non si vede). La frontiera,
foto per foto (sonde al REPL, 30/8): **6/7 = arbitro del gemello per semi
macchina (leva 2 — PROSSIMA FETTA)**; **3 = selezione** (il vero xp da 9
dischetti non si assembla MAI in un'ipotesi — nemmeno col concentrico,
misurato identico foto per foto e cablato dietro `:concentric?`); **2 =
recall del detector (leva 3)**. Il pettine intanto RIFIUTA onestamente 4
ipotesi-spazzatura su 5 (foto 6) — l'enumerazione le pagava a budget. Banco:
`CAGE_AUTO_TEETH=1` / `CAGE_AUTO_CONC=1`. Stato 29/8 (veto zero, gomma,
k passi, registration-verdict) qui sotto, tutto ancora valido.

**STATO AL 29/8**: le fette del veto degli zero, della
rilettura a k passi (solo DIAGNOSI: mai supplire a `:phases` — direttiva),
dell'anti-doppioni e della gomma sono DENTRO e col gate live passato (veto: 42
riletture contraddette sul log di Vincenzo; gomma collaudata). Il triangolino
del palcoscenico ora giudica la gabbia col suo metro (`registration-verdict`).
La campagna sullo zero-click ha MISURATO la frontiera (sezione «La frontiera»,
aggiornata): il cablaggio di produzione resta 2/8 con zero falsi PER SCELTA.
Commits della giornata: 2228e4a → 2f61f5b → a7cc17c → cab7375 → 332981c.

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
  fasi era la STESSA gabbia in un altro giro). Due rami di cura — e la
  DIRETTIVA di Vincenzo (28/8) su come dividerli: «non mi sembra una cosa
  furba supplire alla mancanza di :phases — ci accolliamo lavoro e incertezza
  in più per niente: la gabbia deve essere giusta. Se mai serve qualcosa che
  riconosca che è montata sbagliata e suggerisca di aggiungere il :phases
  opportuno». Quindi: (1) SOFTWARE = solo DIAGNOSI — riconoscere dalla foto
  il montaggio girato e suggerire il `:phases` esatto, mai registrare come se
  la fase fosse dichiarata (una sessione che crede a due geometrie insieme è
  proprio l'incertezza in più). (2) STAMPA = la CURA — una chiave che vieti
  ANCHE il ribaltamento: il vincolo va rotto fuori dal piano dell'anello, non
  solo in azimut (da progettare su `key-pin-azim`/`joint-tabs`; è in coda di
  ristampa).
  Il phase-probe resta cieco a 6 passi (i
  dischetti ricadono identici, solo lo zero si sposta) e il solve scartava
  come outlier proprio i click sullo zero.

  **FETTA COSTRUITA (2026-08-28, questa sessione) — le fasi scoperte dalla
  foto, in due metà:**
  1. **Il veto degli zero in read-crown** (`:zero-picks` in opts, da
     `cage-read-and-place!`: gli zero-indice cliccati A MANO su anelli diversi
     dal seme). Una lettura la cui posa riproietta quel doppio pallino lontano
     dal click è CONTRADDETTA da un fatto, non battuta ai punti — confronto
     min sulle due facce zero-…p/zero-…m, soglia `:zero-veto-px` 100 (il
     gemello sbaglia di centinaia di px, la lettura vera porta solo lo slop
     del modello). REGOLA DI ONESTÀ: uno zero che contraddice TUTTE le 48
     riletture è prova sul SUO ANELLO (montato a passi interi), non sulle
     letture — viene accantonato (`:moot`), mai trasformato in rifiuto, e il
     messaggio dice che il solve lo misurerà. Ritorna
     `:zero-veto {:killed n :moot [...]}`; il pareggio sintetizzato (candidati
     senza zeri, anello Y pieno → gemello a 6 passi in parità) muore col veto:
     test `the-other-rings-zero-arbitrates-the-tie`.
  2. **La rilettura a k passi nel solve** (`match-cage/rescue-hand-zeros`,
     pura, cablata in `solve-and-apply!` via `cage-zero-phase-rescue!`): uno
     zero a mano che il solve vuole scartare come outlier viene prima provato
     a ogni giro di passo intero del suo anello (proiezione sotto la posa
     degli ALTRI pick, soglia 26px). Se un k spiega il click, la foto NON
     viene registrata come se la fase fosse dichiarata — direttiva di
     Vincenzo 28/8, che ha ribaltato la prima stesura (adottava il re-solve
     con lo zero girato): il solve che scarta lo zero RESTA quello valido, lo
     zero resta un outlier onesto del modello in uso, e la scoperta —
     «l'anello R risulta MONTATO girato di k passi» — va in `:note` (status
     line), in `auto-log!`, e in `:zero-phases` (il phase-report ignora lo
     zero stantio per non avvelenare la misura sub-passo). Il re-solve di
     prova gira comunque, come PROVA: il messaggio dice a quanti px
     chiuderebbe il fit con la fase dichiarata (mai un suggerimento a
     indovinare) e dà la dichiarazione TOTALE (`:phases` già dichiarate +
     scoperta) pronta da copiare. Test:
     `a-mounted-ring-is-measured-from-its-clicked-zero` (anello Z montato a
     90°: senza soccorso lo zero vero è outlier; la misura dà k=3, 90°, rms
     sotto 1px con lo zero dentro; su un solve pulito la sonda resta muta —
     il test collauda la funzione pura, che misura; il chiamante spedisce
     solo la diagnosi).
  NIENTE AUTO-APPLY, per scelta e non per rinvio (stessa direttiva): la
  gabbia deve essere giusta — la cura vera è la chiave anti-ribaltamento in
  coda di ristampa; il software riconosce e suggerisce, non supplisce.
  **GATE LIVE DEL VETO: PASSATO (Vincenzo, 28/8, log)** — 8 click ALT su Y +
  lo zero di Z, `a` → «i nomi che avevi dato erano giusti · 20 dischetti
  piazzati · lo zero cliccato sull'altro anello ha fatto da arbitro: 42
  riletture contraddette», solve a 8.6px. Nello stesso giro la GOMMA ha tolto
  le due proposte outlier (zp03, zp07) — passata anche lei. NON ancora
  esercitati dal vivo: il soccorso a k passi (serve una gabbia RIMONTATA
  girata — scatterà al prossimo cambio pezzo senza `:phases` dichiarate) e il
  ramo `:moot`. NOTA scoperta dal log: `propose-and-snap!` (l'aggancio blob
  di fetta A) gira ANCHE sulla gabbia — `plate-proxy?` guarda gli `:anchors`,
  che una gabbia ha — e nel gate ha agganciato 1 marker in più; la sua
  guardia interna `claimed` (mezzo passo dal vicino predetto) è la terza
  gamba della difesa anti-doppioni, e il culling per-anchor le mostra una
  faccia sola per anello. LA STESSA TRAPPOLA («ha anchors» ≠ «è un piatto»)
  viveva nel PALCOSCENICO e l'ha trovata Vincenzo il 29/8 (`edit-edge-mark`
  su sessione gabbia: «le foto dalla 2 in avanti sono flaggate col
  triangolino — sembrano corrette», ed erano corrette): il test
  camera-dietro-la-faccia bollava «mal registrata» mezza gabbia sana (foto 3
  del banco: rms 7.7px, ⚠ per stare 6mm oltre il piano Z del modello) e la
  soglia era gli 8px del piatto invece dei 12 della gabbia. CHIUSA lo stesso
  giorno: `bridge/registration-verdict` (pura, testata coi numeri del banco)
  giudica per specie — la gabbia solo dalla SUA asticella, mai
  :flipped/:grazing — e nel palcoscenico ogni assunzione solo-piatto passa
  da `plate-stage?` (piatto E non gabbia), scorciatoia del piano parallelo
  compresa. Direttiva collegata: la gabbia è la via principale.

  Il workaround «rendi dominante l'anello conteso» NON è bastato: la
  lettura seminata dall'anello Y pieno (13 click, zero incluso) ha DICHIARATO
  il pareggio («1 riletture spiegano la gabbia altrettanto bene») e scelto il
  gemello sbagliato — l'arbitro vero è lo zero cliccato dell'ALTRO anello.
  In più la notte ha scoperto DUE bug fratelli, ORA CHIUSI (stessa sessione):
  (a) le proposte di corr potevano DOPPIO-PRENOTARE un dischetto già occupato
  da un click a mano sotto il nome dell'altra faccia (misurato: 5 dischetti di
  Z con zm* e zp* insieme, stesso pixel → fit 194.9px, nessuna rilettura può
  salvarlo. `kept` filtrava per INDICE). MECCANISMO INCHIODATO il 28/8 (foto
  4, log di Vincenzo): NON servono proposte stantie — è corr stessa che,
  accettati i click sulla faccia p di un anello, propone l'INTERA faccia m
  sopra di loro (le due facce proiettano a ~2px attraverso la plastica):
  `zp01` [1159 931] e `zm01` [1159 931], pixel identico. Con 2 doppioni il
  solve li scarta e registra; con 10 muore camera-dietro. FIX: nessuna
  proposta a meno di `propose-clear-px` (30px: sopra l'errore di un click ALT
  ~25px, sotto i 200-500px fra mark) da un pick esistente, qualunque nome
  porti — e il ramo zero-click di 'a' ora AZZERA le proposte stantie prima di
  scrivere le sue (stessa malattia, altra porta). Il messaggio di 'a' conta le
  proposte scartate («cadevano su dischetti già tuoi»).
  (b) NON ESISTEVA un gesto per cancellare un pick — una foto avvelenata non
  si riparava a mano. FIX: LA GOMMA — clic DESTRO su un pallino (o Backspace
  col cursore vicino, fuori dal batch dove Backspace resta «annulla ultimo»)
  toglie QUEL pick, click a mano o proposta, pulisce i suoi flag di fit,
  salva, e dice come rimetterlo; `eraser-radius-px` 40. Un cenno nel pannello
  la rende trovabile. «Azzera» (già esistente) resta la pulizia totale.
  GATE LIVE di (a)+(b): da fare sulla foto 4 avvelenata o su un grab nuovo.
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
  44 misurato), riproiezione 7.57px, per-foto 4.8–12.2px. Il giro che REGGEVA
  ALLORA (coi bug (a)/(b) ancora aperti): Azzera se la foto è sporca → 4–8
  click ALT su UN solo anello (zero se visibile) → `a` → `n` → mai un secondo
  anello a mano. DA QUESTA SESSIONE il fix (a) è dentro e il secondo anello
  non solo è permesso: cliccare lo ZERO di un secondo anello è ciò che ARMA
  il veto dei gemelli (e la gomma ripara i pick sbagliati senza Azzera). Se
  un anello-seme non legge il resto della gabbia, provarne un ALTRO (sulla
  stessa foto X falliva, Z affogava nei doppioni, Y registrava a 7.1px —
  l'istinto di Vincenzo su quale anello usare ha battuto il consiglio
  calcolato TRE volte).
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

## La frontiera — perché 2/8 e non 8/8 (AGGIORNATA 29/8: ora è MISURATA)

La campagna del 29/8 ha attaccato le leve 1 e 2 e ha lasciato il cablaggio di
produzione ALLA BASELINE (2/8, zero falsi) **per scelta**: ogni arricchimento
provato o non spostava il tasso o comprava un falso positivo. Ma la frontiera
non è più una congettura — è un audit, foto per foto, con due strumenti nuovi
nel banco (`CAGE_AUTO_RECALL=1`): la riga di **recall** (per anello visibile,
quanti candidati cadono sui suoi mark sotto la posa-verità) e il
**coverage-report** (per anello trovabile: quale ipotesi lo copre, e se è mai
stata TENTATA sotto il budget). Cosa dicono:

- **Gli anelli veri CI SONO**: su 5 delle 6 rifiutate un anello ha 8–9
  dischetti rilevati (foto 3: xp 9; foto 4: xp 8 + ym 8; foto 5: ym 9;
  foto 6: yp 8; foto 7: ym 9). Solo la foto 2 è affamata davvero (max 3) —
  quella è territorio della leva 3 (recall del rilevatore).
- **Il budget bruciava senza mai provarli**: 6 facce per ipotesi × budget 18 =
  solo le prime 3 ipotesi (per regolarità) venivano tentate. L'anello vero,
  quinto in lista, non veniva MAI provato.
- **La famiglia concentrica** (`ellipse/fit-concentric-ranked` +
  `sisters-about`: fit a centro bloccato, 3 incognite, terne esaustive; semi =
  centri delle ipotesi dello stadio 1 — spazzatura inclusa, che è centrata
  sulla gabbia anche lei — più il baricentro) fa emergere gli anelli veri…
  **dentro soprainsiemi CONTAMINATI** (+2…+6 punti in banda Sampson ai passi
  sbagliati): foto 7, i 9 ym completi in un'ipotesi da 11; foto 6, gli 8 yp in
  una da 14, la cui corda gonfiata falsava pure il suggeritore d'asse.
  ATTENZIONE al merge: le sorelle possono solo AGGIUNGERE anelli, mai
  spodestare le ipotesi libere dello stadio 1 — la prima stesura giudicava per
  taglia e il soprainsieme sporco CANCELLAVA l'insieme pulito (sintetico da
  39/39 a RIFIUTATO; misurato, corretto).
- **Il pettine** (`ellipse/comb-select`): la corona è equispaziata in anomalia
  eccentrica (invariante affine), i contaminanti no — due passate (il fit sul
  miscuglio classifica male; rifit sui denti, riselezione dall'insieme pieno).
  Costruito e testato; da solo non basta perché l'identificazione a valle
  (`assign-marks`) muore comunque sui set quasi-puliti di queste foto.
- **IL GEMELLO-RIFLESSIONE, il pericolo vero**: con lo stream di ipotesi
  arricchito, la foto 7 si è REGISTRATA dalla riflessione dell'intera gabbia —
  camera a 764mm dalla verità, «spiega 19» — perché quando l'identificazione
  vera fallisce il gemello vince INCONTRASTATO, e il conteggio di corr SATURA
  quando le predizioni si infittiscono (posa più lontana → gabbia più piccola
  → più coppie mutual-nearest entro 26px). `explained` ora conta candidati
  DISTINTI (un dischetto conferma UN mark) — giusto in sé, ma non basta come
  arbitro.
- **La guardia degli zeri è SMENTITA dai pixel**: «sotto la posa vera gli
  zero degli altri anelli cadono sui loro doppi pallini» vale per l'occhio e
  per i click, NON per `disc-at?` sui frame veri — la guardia uccideva anche
  foto 1 e 8 (0/8). Rimossa. L'arbitro automatico del gemello resta da
  inventare.

**Le prossime leve, riviste dall'audit** (in ordine — AGGIORNATE 30/8 dopo la
leva 1):
1. ~~**Identificazione contamination-proof**~~ — **FATTA (30/8)**: il pettine
   come fase iniziale dell'identità (`comb-teeth` → `assign-marks :teeth`).
   Cosa ha INSEGNATO il banco: su foto 6 gli 8 yp veri stanno nell'ipotesi da
   14, il pettine ne tiene 10 e l'identità li legge — corona 11 su TUTTE E SEI
   le facce (la simmetria del gemello, ora misurata anche qui) — e a decidere
   resta solo lo zero al giudice dei pixel, che elegge il GEMELLO (ym, 548mm;
   foto 7 idem, 764mm). Su foto 3 invece il vero xp (9 dischetti) non entra
   MAI intero in un'ipotesi — le migliori ne coprono 5/9 — quindi lì la
   malattia è la SELEZIONE, non l'identità. Il gate `:teeth?` resta CHIUSO in
   produzione finché non c'è la leva 2: due registrazioni a mezzo metro non
   sono un tasso, sono falsi.
2. **Arbitro del gemello per semi macchina — ORA È QUI CHE SI MUORE**
   (foto 6/7): il veto degli zero funziona coi click; per l'auto serve un
   testimone che regga sui pixel veri (lo zero RILEVATO dal detector — oggi il
   detector li manca spesso; o la chiralità dell'indice fuori-asse, che è il
   suo scopo di progetto). NB misurato: `explained` non arbitra (15-19 per il
   gemello contro 13 delle vere — satura con le predizioni fitte), rms non
   arbitra (5.5-6.1 contro 1.6-6.1), la guardia fisica passa su entrambi.
3. **Selezione dell'anello povero** (foto 3, e 4/5 da riverificare): il
   concentrico di oggi non lo fa emergere (misurato 30/8: stream identico al
   libero su tutte le 8). Serve un'idea nuova — o un pettine che PESCHI
   (denti noti → cerca i dischetti mancanti sulle posizioni previste), o la
   sorella con centro più libero.
4. **Recall del rilevatore** (foto 2, e +1 inlier ovunque): `cage-opts` fu
   tarato sulla gabbia vecchia senza pezzo dentro.

E il tempo: 25–40s di UI bloccata per un rifiuto non è spedibile oltre il
prototipo — o si accorcia, o si sposta su un worker. Il materiale della
campagna (concentrico, pettine, audit) sta in `ellipse.cljs` + banco, testato
(`concentric-family-recovers-the-sparse-ring`,
`sisters-about-is-exhaustive-where-sampling-is-lucky`), pronto per essere
ricablato quando la leva 1 (identificazione) è dentro.

## Il banco di prova, e usalo

- **`node out/cage-auto.js`** — zero-click contro la sessione battiscopa
  (`test-assets/cage-battiscopa`, jpeg non tracciate, `acquire-state.json` =
  verità: pose camera a mano). Stampa per foto: candidati, seme, spiega, rms, e
  **a quanti mm atterra la camera dalla mano**. Su un rifiuto stampa la traccia
  (`:trace`) — stadio ipotesi e ogni tentativo di identità.
  `CAGE_AUTO_TEETH=1` accende l'identità coi denti (misurato 30/8: 2 vere + 2
  GEMELLI a 548/764 — il motivo del gate); `CAGE_AUTO_CONC=1` lo stream
  concentrico (misurato 30/8: identico foto per foto).
- **`CAGE_AUTO_SYNTH=1 node out/cage-auto.js`** — la scena sintetica (39
  candidati perfetti, 3 anelli): deve dare 39/39 a 0.00mm. Se la rompi, hai
  rotto la catena, non le tarature.
- **`node out/cage-study.js <dir>`** — il rilevatore da solo (candidati +
  recall per anello su IMG_9014).
- **`node out/cage-fit.js`** — le premesse della fetta 2 (riletture, fasi,
  facce) su `test-assets/cage-presa`.
- **Sondare una foto singola SENZA cicli di compile** (è così che il 30/8 si è
  inchiodato il meccanismo del gemello): `(shadow.cljs.devtools.api/node-repl)`
  via nREPL 7888 → `js/require` di fs+sharp funziona lì dentro → decodifica
  asincrona in un atom (`.then` + reset!, si legge all'eval successiva) →
  ricostruire la catena di auto-read a mano (detect-blobs → fit-inliers-ranked
  → comb-teeth → assign-marks per faccia → assign+solve-pnp) stampando per
  ogni (ipotesi × faccia): denti, corona, zero, rms, distanza della camera
  dalla verità (`bridge/editor->solver-pose` su `acquire-state.json`).
  TRAPPOLA PAGATA: nel node-repl `dir` è un'utility del REPL — un `(def dir
  …)` fallisce in silenzio e l'eval si tronca a metà: usare un altro nome.
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

- `src/ridley/photogrammetry/match_cage.cljs` — `read-crown` (seeded, ora con
  `:zero-picks`/`:zero-veto`), `rescue-hand-zeros` (rilettura a k passi),
  `auto-read` (zero click; `:teeth?` = identità coi denti, GATE chiuso in
  produzione; `:concentric?` = stream concentrico), `ring-faces`, `phase-probe`
- `src/ridley/photogrammetry/blob_detect.cljs` — rilevatore, `cage-opts`,
  `enclosed-frac`
- `src/ridley/photogrammetry/match_plate.cljs` — `crown-ring-hypotheses`,
  `assign-marks` (l'identità che auto-read riusa; opz. `:teeth` = via
  comb-locked, 24 candidati)
- `src/ridley/photogrammetry/ellipse.cljs` — `fit-inliers-ranked` (lo stadio
  dove muoiono le rifiutate), `comb-teeth`+`gap-classify` (leva 1: denti per
  l'identità, intrusi in panchina), `fit-concentric-ranked`, `comb-select`
  (superato da comb-teeth, nessun chiamante)
- `src/ridley/photogrammetry/cage.cljs` — geometria, `stick-slots`, chiave,
  `:phases`
- `src/ridley/editor/edit_acquire.cljs` — `cage-read-and-place!` (il tasto
  `a`, tre rami: 0 click → auto, ≥4 → seeded, 1–3 → messaggio; costruisce
  `:zero-picks`, filtra le proposte con `propose-clear-px`),
  `cage-zero-phase-rescue!` (cabla `rescue-hand-zeros` in `solve-and-apply!`),
  `erase-pick-at!`/`pnp-on-contextmenu` (la gomma),
  `on-refine-session!` (le guardie della rifinitura)
- `test/ridley/photogrammetry/cage_auto_study.cljs` — il banco zero-click
- `test/ridley/photogrammetry/match_cage_test.cljs` — sintetici + foto vera
- `public/builtin-libraries/acquire-cage.clj` — stampa: anelli, chiave, slot,
  stick, punta-tricuspide, culla
- Commit della settimana: `834c92b` detector → `fee7154`/`ab79a8b` read-crown →
  `c2dbee1` tasto a → `06eee7e` zero-indice/90° → `1e300b6` chiave → `c5d7692`
  portapezzi → `c341015`…`fdbfe01` guardie rifinitura → `1626c0a` zero click.
