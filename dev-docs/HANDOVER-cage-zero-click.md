# HANDOVER — zero click: la gabbia si legge da sola

## In una riga

Vincenzo (2026-08-27): «se non riusciamo ad avere la registrazione automatica
delle foto sarà tutto inutile». La leva 1 (identità coi denti) e la leva 2
(arbitro del gemello) sono DENTRO e misurate; al banco 2/8 vere con zero falsi
e una registrazione a 0.2mm sulla sessione dichiarata. Ma la settimana dal vivo
ha spostato il collo di bottiglia dall'algoritmo all'**INTERFACCIA DEL
CLICK**: era il pannello a imporre facce sbagliate, ed è lì che nascevano tre
serate di rifiuti.

Entry point precedente, con tutta la storia della gabbia (detector, riletture,
anello incollato a 90°, chiave di montaggio, portapezzi, la settimana
dell'avvelenamento): `dev-docs/HANDOVER-cage-auto-detect.md`. Questo file è lo
stato dei lavori per ripartire.

---

## ATTERRAGGIO — 30/8 sera, leggi PRIMA questo

**Dove siamo**: il meccanismo automatico regge (leve 1 e 2 costruite, testate
993/0, misurate al banco). Il lavoro vivo è il flusso a mano di Vincenzo sulla
sessione `~/Pictures/RidleyScan/battiscopa3` (5 grab, 1920×1440, lente 44
manuale, gabbia `(registration-cage :d 176 :phases {:y 180 :x 180})`).

**I tre fatti di dominio, verificati, che governano tutto** (se ne dimentichi
uno, riscrivi codice sbagliato — è successo):
1. **La gabbia è INCOLLATA** (attack). Il montaggio non è per-assemblaggio:
   è una costante fisica come `:d`, cambiabile solo ristampando. Il pezzo si
   riposiziona con gli stick, gli anelli mai. Perciò su gabbia DICHIARATA un
   indice letto specchiato/girato accusa la REGISTRAZIONE, mai la gabbia.
2. **Le fasi `{:y 180 :x 180}` sono vere e verificate tre volte** (palcoscenico
   settimane fa, banco senza fasi che rilegge k6/k6, app col modello fasato che
   legge k0). Dichiarate → tutti gli anelli nominali.
3. **La lente è 44mm** (iPhone Continuity), MAI l'EXIF (i grab non ce l'hanno).
   Il default 48 ha avvelenato tre serate di seguito: obs del montaggio nate
   sotto la lente sbagliata leggono la famiglia specchiata e fabbricano falsi
   GEMELLO. Ora c'è il gate (`cage-obs-focal-ok?`) + la focale nell'impronta.

**Cosa fare per primo**: i toggle delle facce hanno PASSATO il primo giro vivo
(grab-01, 30/8 notte) e hanno subito trovato altro — vedi «Ottavo giro live» e
«Nono giro live» sotto. Su grab-04 la dichiarazione non arrivava ai click già
fatti (chiuso), e le facce vere di quella foto sono **Xp Ym Zm**, non le Xp Yp
Zp dichiarate. Su grab-06 (foto 5) è la faccia X: **Xp Ym Zp**, non Xm. Su
grab-05 (foto 4) NON è decisa: il banco diceva Zm, Vincenzo legge Zp sulla
stampa, e la sonda della chiralità gli dà ragione sul METODO — vedi
«Dodicesimo giro». Lì il set contiene un click a 508px e non basta a decidere:
va ricliccato l'anello X sui dischetti che il detector vede davvero. Le facce che Vincenzo dichiara sono risultate le uniche fisicamente
possibili e i suoi click cadono a 0–1px dai dischetti rilevati: il fit non
scendeva per colpa delle PROPOSTE automatiche, e il verdetto accusava lui.
Chiuso: verdetto col test del crollo, niente «riclicca» sulle proposte, niente
✓ e niente pallino armato sopra l'asticella, e il ripiego sui soli click a mano
(`retry-on-hand-picks!`, 13.8 → 9.2px misurati). **Gate live pendente**: rifare
il giro su grab-01 — Azzera → facce (Xm Ym Zp) → click → `r` — atteso ~9.2px con
xm05/xm08 accusati a ragione (lì il crollo c'è). Poi le altre foto, poi `R`.

**Difetti aperti, nominati** (in ordine di rendimento):
- **Memoria per-camera della focale** (`~/.ridley/`): la 44 è stata misurata
  due volte e ogni sessione nuova riparte da 48. È la trappola che ha morso
  più di ogni altra cosa in questa settimana.
- **La gabbia orientabile col gizmo** (idea di Vincenzo, la fetta grossa):
  quando il doppio pallino non si vede i nomi non hanno senso PER
  COSTRUZIONE; lui risolve prendendo in mano la gabbia e appaiandola alla
  foto. Virtualizzarlo dà facce giuste, nomi giusti E un seme umano per `a`
  (altro colpo al cold start). Impianto da riusare: gizmo del proxy foto 0 +
  palcoscenico P4b.
- **`max-outliers` a 2 è tarato su set piccoli** (nuovo, 31/8): su 13 pick il
  pulitore si ferma a 14.0px quando la verità sta a 6.8 quattro scarti più in
  là, e tutto a valle (`rename-worthy?`, il verdetto, l'adozione) giudica il
  numero sbagliato. Misurato su grab-04. Non toccato: alzare la valvola le fa
  mangiare punti buoni, e le prove sono di una foto sola.
- **Accettazione senza asticella**: «registrata sui restanti (72.1px)» — il
  retry senza outlier accetta QUALUNQUE rms. Sopra ~2× l'asticella deve
  rifiutare, non persistere una posa selvaggia. (Il 30/8 notte è stata tolta
  almeno la BUGIA: sopra l'asticella niente ✓, niente colpevoli nominati e
  niente pallino armato — ma la posa selvaggia si persiste ancora.)
- **Rinomina prima del solve**: `relabel-picks!` rinomina PRIMA, e un solve poi
  rifiutato lascia i nomi del gemello sui pick (viola «un soccorso che non
  compra un fit sotto soglia non rinomina»; il rollback attraversa il confine
  asincrono di `on-solve-pnp!`).
- **COLD START** (sotto): chiuso per gabbie dichiarate, aperto per le altre.

**Strumenti nuovi di questa settimana, usali**: `CAGE_AUTO_SEED=<n>` (la sonda
del seme: click di una foto → distanza dal candidato rilevato, solve sui soli
click, migliore rietichettatura per anello — ha chiuso due casi che a occhio
erano indecidibili), `CAGE_AUTO_FIT=<n>` (il fit dell'app smontato: click a mano
contro click+proposte, e la TRACCIA onesta del pulitore — è la sonda che dice se
uno scarto è un colpevole o un capro espiatorio), `CAGE_AUTO_FACE=<n>` (le otto
dichiarazioni di faccia sui soli click, filtrate dal test fisico: l'rms da solo
sceglie SEMPRE la faccia girata via), `CAGE_AUTO_PICKS_FILE=<json>` (per set di
click che non sono mai arrivati allo stato: il rifiuto camera-dietro non li
salva), `CAGE_AUTO_PHASES`, `CAGE_AUTO_NOCTX`, `CAGE_AUTO_ZERO`,
`CAGE_AUTO_CTXONLY`.

**Come lavorare con Vincenzo su questo fronte** (confermato tre volte questa
settimana): i suoi log incollati sono lo strumento di misura principale, e il
suo DUBBIO va preso sul serio — «sei sicuro della diagnosi? io le vedo poco
mosse» ha ribaltato una mia sentenza sbagliata, e «avevo messo p perché mi
presentava solo quelli» ha trovato la causa a monte di tre serate. Prima di
sentenziare su una foto: SONDA, non congettura.

---

**La leva 2, com'è fatta (costruita 29–30/8)**: l'arbitro del gemello
per semi macchina — è COSTRUITA, TESTATA (991/0) e MISURATA, e strada facendo
ha CONDANNATO UNA POSA A MANO della sessione-verità. Il meccanismo, nato da
tre giri di banco che hanno ucciso tre specie di impostori una per volta:

- **`index-witness`** (match_cage): sotto una posa, l'indice di ogni faccia
  visibile può stare solo in 24 ALLOGGI (12 per senso, ±⅓ di passo dai mark);
  un candidato del DETECTOR non spiegato da corone che cade in un alloggio è
  un'osservazione `{:axis :sense :k :d}`. Senso E slot k sono ASSOLUTI DI
  POSA (gli alloggi si calcolano dagli azimut del modello, nessun gauge di
  lettura) — ogni posa vera di una sessione legge lo stesso (senso,k) su un
  anello, perché QUELLA COPPIA È IL MONTAGGIO. Scoperta collaterale che
  rifonda la leva: gli indici sul banco SONO rilevati (Y a 4–11px su 5 foto)
  ma in alloggio SPECCHIATO — **l'anello Y della gabbia battiscopa è montato
  RIBALTATO** (il ribaltamento che la chiave non vieta, test fisico 28/8), e
  il "gemello" di foto 6/7 era la lettura che spiegava l'indice VERO meglio
  del modello nominale. Con montaggio libero per-assemblaggio la SINGOLA foto
  non può distinguere gemello da vero: l'arbitro è la SESSIONE (un montaggio
  solo per sessione).
- **`vote-mounting`**: il montaggio di sessione per VOTO DI MAGGIORANZA
  sulle coppie (senso,k) — mai per nitidezza (la posa avvelenata di foto 1
  era più nitida di 0.05px e da sola ribaltava tutto), mai per senso solo
  (la gabbia girata di 180° attorno a un ALTRO asse conserva il senso e
  sposta k di 6: foto 7 a 643mm, spiega 18, morta solo sul k). Un anello
  CONTESO è di suo una diagnosi: c'è un gemello TRA le registrazioni della
  sessione — ed è così che si è scoperto che **la posa A MANO di foto 1
  è l'impostore a 180°** (centro camera = quello vero con x,y negati;
  5 foto concordi contro 1; nessuno aveva mai verificato gli indici).
- **In `auto-read`** (opts `:mounting`, `:blobs`): VETO (osservazione che
  contraddice il voto → lettura scartata, qualunque punteggio), CONFERMA
  RICHIESTA (anello-seme a montaggio noto con ≥2 voti: la sola luminanza
  non basta più — è ciò su cui il gemello cavalcava), AVALLO nel rango (una
  lettura col disco-indice rilevato sull'alloggio di sessione batte
  qualunque `explained` — che satura, misurato 19 per il gemello), **sweep
  dei 12 GAUGE** del seme quando il montaggio è noto (senza zero il seme
  elegge una rotazione arbitraria = camera orbitata di k passi: foto 7 a
  325mm con indice a 1px), e **pavimento `:min-off-ring` 2** (le due pose
  lontane spiegavano ZERO fuori-anello; l'indice è complanare alla corona e
  non vincola la profondità — il principio fondativo del namespace applicato
  anche alla macchina).

**Il banco, dopo (leave-one-out sulle pose a mano = il contesto che una
sessione mista ha davvero)**: foto 7 — quella dove la leva 2 "moriva" —
registra VERA a **1.1mm** (spiega 18, fuori-anello 8, indice y=rev(k11)@4px);
foto 8 vera a 1.0mm; foto 1 elegge la lettura concorde con la maggioranza su
DUE indici a 1px — "823mm dalla tua" perché la SUA verità è il gemello (il
banco ora lo annota: «LA VERITÀ QUI È IL SOSPETTO»); foto 6 rifiuto onesto
(il suo frame non ha NESSUN indice rilevato — territorio leva 4); 2–5
rifiutate come prima (selezione, leva 3). **Zero falsi, con e senza denti.**
E il vecchio «2/8 zero falsi» della baseline va riletto: era 1 vera + 1
GEMELLO mai scoperto (foto 1 combaciava con la sua verità avvelenata).
Sintetico: 39/39 a 0.00mm intatto; suite 991 test / 0 fail; warning 16/:app
invariati.

**Cablaggio di produzione (edit_acquire, FATTO ma gate live da fare)**: le
osservazioni-indice si accumulano per foto (`:cage-mounting-obs` —
PERSISTITE in acquire-state.json dal 29/8 sera: erano in memoria «come
:pnp?» e una ricarica ha azzerato il voto proprio mentre serviva, il gemello
flip-face è rientrato al primo 'a' seminato; la rifinitura le azzera comunque,
intrinseche stantie) da OGNI lettura accettata ('a' seminato e zero-click);
il ramo zero-click passa `:mounting` (voto leave-one-out) + `:blobs`, e
**`:teeth?` si accende da solo quando la sessione ha montaggio** (denti solo
dove l'arbitro ha giurisdizione — contextless coi denti = i gemelli del
30/8). Il ramo SEMINATO riceve `:mounting` e `:declared-faces`. Messaggi,
solo quando c'è da dirlo e mai più grandi delle prove (ogni riga è stata
corretta da un log di Vincenzo): un `:rev` da UNA foto dice «in questa foto
si legge specchiato, da solo non fa verdetto», col voto concorde dice
«INCOLLATO ribaltato → ristampa» (mai «rimontalo», è impossibile); una
contraddizione con la DICHIARAZIONE accusa la registrazione; uno zero moot su
gabbia dichiarata accusa il click; un anello conteso riceve SOLO l'avviso di
contesa; un doppio pallino cliccato ma non rilevato viene dichiarato tale
(non conta nel voto).

**GATE LIVE: PASSATO (Vincenzo, 29/8, log)** — su una sessione viva (foto
senza EXIF, focale al default 48): due seeded a 6.2/9.4px, poi il **primo
ZERO-CLICK dal vivo riuscito** (anello y + zero-indice, 21 dischetti, solve
9.0px, 10 marker agganciati) e due rifiuti onesti coi messaggi giusti. Le
diagnosi nuove sono SCATTATE al primo giro: l'anello Z conteso fra la foto A
(obs di posa, senza click su Z) e la foto B (ancorata dal SUO ⊙zp cliccato,
42 riletture contraddette dal veto). Il log ha trovato un difetto di
messaggistica, corretto subito: RIBALTATO e GEMELLO uscivano INSIEME sullo
stesso anello — ma se la lettura è contesa, affermare il ribaltamento è
prematuro; ora un anello conteso riceve solo l'avviso di contesa, con la
mossa di spareggio nel messaggio (click sul doppio pallino in una TERZA
foto). Il conteso su Z resta DA RISOLVERE lì: 1-1 nel voto (parità = nessuna
giurisdizione), spareggio alla prossima testimonianza; e la focale della
sessione va misurata con la rifinitura appena ci sono 3+ foto (48 di default
contro la 44 vera del Continuity — trappola nota del grab).

**Secondo giro live (29/8, log)**: la rifinitura su 3 foto misura la lente
**48 → 44.07mm** — il valore noto del Continuity: trappola del default
chiusa, riproiezione 8.26 → 7.58 — e subito dopo il **secondo zero-click
riuscito** (anello x, 24 dischetti, 7.5px). Lo spareggio su Z è rimasto
ambiguo: il ⊙zm cliccato sulla terza foto non ha prodotto messaggi, che può
voler dire «concorda» o «indice non rilevato → nessun voto» — corretto: ora
un doppio pallino cliccato che non risulta fra i dischetti rilevati viene
dichiarato («qui ha arbitrato le riletture, ma nel voto di sessione non
conta»). Spareggio di Z ancora aperto: si chiude ri-premendo `a` sulla foto
del ⊙zp di ieri col voto ormai popolato.

**Terzo giro live (29/8 pomeriggio, sessione battiscopa2 di Vincenzo —
`~/Pictures/RidleyScan/battiscopa2`, 5 grab a 44mm rifiniti)**, quattro
frutti:
1. **Il testimone legge il montaggio FISICO**: sotto le pose a mano, unanime
   su tutte le foto, `x=fwd(k6) y=fwd(k6) z=fwd(k0)` — cioè X e Y girati di
   180°, che è ESATTAMENTE il `:phases {:y 180 :x 180}` misurato settimane
   prima sul palcoscenico per questa stessa gabbia fisica. Conferma
   indipendente dello strumento. POSTILLA (sera): quei k6 erano letti dal
   BANCO col proxy hardcodato SENZA fasi — la sessione di Vincenzo dichiara
   già `:phases {:y 180 :x 180}`, e sotto il suo modello le stesse
   osservazioni leggono k0 ovunque (nominale = modello giusto; il
   suggerimento GIRATI da lui non apparirà mai, correttamente). Il banco ora
   usa la gabbia della sessione (`CAGE_AUTO_PHASES` o l'impronta persistita
   col voto) — e col modello giusto grab-01 si registra da sola a 0.2mm
   (spiega 31, fuori-anello 19). Su una gabbia ben dichiarata tutti gli
   anelli sono nominali → l'indice del seme non discrimina mai (per
   costruzione): l'avallo vive sugli anelli NON-seme, la difesa sui frame
   affamati resta secondo-anello + camera-dietro.
2. **Il GEMELLO su Z del primo giro era un artefatto della focale** (obs
   raccolte al default 48; a 44 misurati la contesa sparisce). FIX: la
   rifinitura ora AZZERA `:cage-mounting-obs` — osservazioni misurate sotto
   intrinseche vecchie sono stantie e peggio che vuote; si ricostruiscono
   ripremendo `a`.
3. **Il suggerimento `:phases` per anelli GIRATI** (direttiva 28/8,
   riconoscere-e-suggerire): un anello che il voto legge fwd(k≠0) con ≥2
   voti non contesi frutta il messaggio con la dichiarazione pronta da
   copiare. Autolimitante: dichiarata la fase, le obs leggono k0 e la riga
   tace.
4. **La quarta specie di gemello, uccisa**: su un anello montato NOMINALE
   l'indice attraverso la plastica è lo stesso pixel per la lettura vera e
   la gemella — il gemello zp di grab-01 spediva a 447mm AVALLATO dal suo
   stesso indice Z a 1px. Regola del DISCRIMINANTE (in auto-read):
   un'osservazione avalla/conferma solo se su un anello DIVERSO dal seme,
   oppure sul seme quando il montaggio votato non è (fwd,0) — la stessa
   lezione del flusso a mano, «l'arbitro vero è l'indice dell'altro
   anello». Su un anello-seme nominale noto, niente relax e niente
   richiesta: vale la vecchia regola dello zero ai pixel (il gemello lì si
   separa solo con evidenza fuori-anello). Banco: 447 morto, battiscopa1
   invariato (7→1.1mm, 8→1.0mm, 1 annotata), sintetico 39/39, suite 991/0.

**QUESTIONE APERTA (banco ≠ app, battiscopa2 foto 4)**: l'app la registra
zero-click VERA (x, 24 dischetti, 7.5px, due giri consecutivi) mentre il
banco su di lei muore allo stadio ipotesi (34 candidati, ipotesi max 9,
corona ≤2) — un divario di INPUT, non di logica (prcedente a ogni fix di
oggi). Sospetto: il campionatore di luminanza del banco (sharp,
0.299/0.587/0.114) non è identico a quello dell'app (backdrop loader) e il
detector ne risente. Da chiarire prima di fidarsi dei RIFIUTI del banco
come pavimento del tasso vero — i suoi successi/gemelli restano
attendibili (le pose combaciano con la mano al mm).

**Foto 5 di battiscopa2, rifiuto spiegato** (domanda di Vincenzo, misurata):
18 candidati contro 24-42 — l'anello X esce dall'inquadratura in alto, un
anello è quasi di taglio (di profilo i dischetti non esistono per il
detector), la zona alta è sfocata. Resta ~1 anello e mezzo utilizzabile:
sotto il pavimento di 8 per l'identificazione. Rimedio: seminare a mano
l'anello centrale nitido, o ri-grabbare con la gabbia intera in campo.

**Quarto giro live (29/8 sera, foto 5 di battiscopa2)**: la lettura SEMINATA
sul frame affamato (18 dischetti) ha eletto il gemello flip-face di Y — che
la sessione sapeva montato fwd(k6) da TRE foto — e ha RINOMINATO i click
giusti coi nomi del gemello prima che il rifiuto camera-dietro fermasse il
solve. FIX: il montaggio votato ora arbitra ANCHE `read-crown` (opts
`:mounting`: le riletture il cui indice del seme contraddice la coppia
votata muoiono come fatti — solo l'anello del seme, le pose a un anello
portano 90-160px sugli altri; stessa regola di onestà degli zeri: se
ucciderebbe tutto, non uccide niente e lo dice). Test:
`the-session-mounting-vetoes-the-hand-twin` (23 riletture uccise, nomi
giusti intatti — il veto ammazza anche la famiglia delle rotazioni a passi
interi). DIFETTO RESIDUO NOMINATO, da fetta: `relabel-picks!` rinomina
PRIMA del solve, e un solve poi rifiutato lascia i nomi del gemello sui
pick (viola la regola pagata «un soccorso che non compra un fit sotto
soglia non rinomina» — il rollback attraversa il confine asincrono di
on-solve-pnp!). E il veto è a testimoni: su un frame dove l'indice del seme
non è rilevato non protegge — lì la difesa resta camera-dietro + il
consiglio del secondo anello.

**Quinto giro live (29/8 notte, sessione NUOVA battiscopa3 — il pezzo si era
spostato nella gabbia)**: TERZA morsicatura della trappola della focale — la
sessione nuova riparte dal default 48 (stessa camera da 44 misurata due
volte!) e tutto degrada a cascata: fit 14px, sette proposte spazzate a mano
con 'o', snap che aggancia il vicino a 72px, «Z SPECCHIATO» d'artefatto e il
flip-twin su foto 2 (rifiutato camera-dietro, coi soliti nomi rinominati).
FIX: `cage-obs-focal-ok?` — a lente non misurata (`:focal-source :default`)
niente accumulo di osservazioni, niente diagnosi di montaggio e niente
arbitrato (le obs sono geometria di slot: sotto la lente sbagliata mentono,
misurato tre volte). FETTA IN CODA, ad alto rendimento: **memoria per-camera
della focale** — la sessione conosce l'etichetta della camera («Fotocamera di
Vincenzo Piombo's iPhone», C922…) e la 44.07 era già stata misurata in
battiscopa2; un archivio {camera → focale misurata} in ~/.ridley/ semina le
sessioni nuove e questa trappola muore per sempre.

**CORREZIONE DEL MODELLO DEL DOMINIO (30/8, da Vincenzo)**: «gli anelli sono
incollati con l'attack — l'unico modo di cambiare la posizione reciproca dei
ring è ristampare la gabbia». Quindi il MONTAGGIO NON È PER-ASSEMBLAGGIO —
la premessa del 28/8 («la gabbia si apre a ogni cambio pezzo») era FALSA: il
pezzo si riposiziona con gli stick, gli anelli non si toccano mai. Il
montaggio è una COSTANTE della gabbia fisica, come `:d`. Conseguenze
cablate:
1. **La dichiarazione arma l'arbitro dalla prima foto**
   (`declared-cage-mounting`): un proxy con `:phases` esplicite asserisce
   indici nominali su ogni anello (voto 2, `:declared?`), battibile solo da
   3+ foto concordi non contestate — la via d'uscita onesta per una gabbia
   incollata storta. Il COLD START è CHIUSO per le gabbie dichiarate.
2. Su gabbia dichiarata, un'osservazione `:rev`/`k≠0` è FISICAMENTE
   impossibile → accusa la registrazione, mai la gabbia: messaggio dedicato
   («contraddice la DICHIARAZIONE — è QUESTA registrazione a essere
   sospetta»), e addio per sempre a «rimontalo dritto» (impossibile). Un
   `:rev` confermato da più foto = anello INCOLLATO ribaltato → ristampa
   (chiave anti-ribaltamento), i flip non si dichiarano.
3. Foto 2 di battiscopa3 (registrata a 11.9px con 5 rinomine e X/Z letti
   specchiati) va considerata SOSPETTA-GEMELLO ad alta probabilità: gabbia
   incollata + fasi giuste ⇒ quei `:rev` non possono essere veri. Le
   prossime registrazioni della sessione la giudicheranno (e ora la
   dichiarazione veta i suoi simili in auto dalla prima foto).
NB storico: il test fisico del 28/8 («l'anello X si monta anche RIBALTATO»)
riguardava la libertà AL MONTAGGIO, prima dell'incollaggio — vale per la
stampa della prossima gabbia, non per l'uso di questa.

**FETTA PROPOSTA DA VINCENZO (30/8), in coda con priorità**: la GABBIA
ORIENTABILE A OCCHIO. Il suo flusso reale quando il doppio pallino non si
vede: «per riconoscere alcuni mark devo guardare la gabbia fisica,
posizionarla come in foto, e da lì capisco i nomi» — cioè risolve a mano la
simmetria che la foto da sola non scioglie (senza indice i nomi non hanno
senso, PER COSTRUZIONE). La versione software: su una foto non registrata,
un gizmo per orientare la gabbia virtuale finché non "combacia" a occhio
con la foto — da lì (1) il pannello offre le facce giuste, (2) i nomi dei
mark si contano giusti, (3) la posa a occhio può fare da SEME per 'a'
(altro colpo al cold start: un seme umano grossolano vale più di quattro
click). Impianto esistente da riusare: il gizmo del proxy della foto 0 e il
palcoscenico P4b. Nota di collaudo dello stesso giro: registrazione a
52.8px passata come «registrata sui restanti» — un'accettazione sopra
l'asticella andrebbe almeno bollata in rosso nel messaggio; e il moot dello
zero ora è declaration-aware («su gabbia incollata = click sbagliato», mai
«montato girato»).

**Sesto giro live (30/8 sera, foto 3 di battiscopa3) — chiuso dalla SONDA
DEL SEME** (`CAGE_AUTO_SEED=<n>` sul banco, costruita per l'occasione: click
a mano dallo stato → distanza di ogni click dal candidato rilevato più
vicino, solve sui soli click, meglio-rietichettatura greedy per anello).
Verdetto su foto 3 (registrata dall'app a 52.8 poi 72.1px): (1) la foto è
MOSSA — gabbia tenuta IN MANO durante il grab, visibile nell'immagine; (2)
il detector è quasi cieco su tutto il lato Z (click a 42–122px dal primo
candidato — l'arbitrato delle 48 riletture giudica sui candidati, e lì non
ce n'erano); (3) i click stessi non sono salvabili da NESSUNA
rietichettatura (greedy per anello: meglio 48–81px con scarti) — senza
doppio pallino visibile e su foto mossa i nomi erano tirati a indovinare,
come Vincenzo stesso descrive. Tre colpi indipendenti: la foto non
contiene l'informazione, si scarta o si rifà. REGOLA D'ACQUISIZIONE che ne
esce: la GABBIA FERMA (appoggiata, mai in mano), fuoco assestato, poi il
grab. DIFETTO NOMINATO da fetta: «registrata sui restanti (72.1px)» — il
retry senza outlier viene accettato a QUALUNQUE rms; sopra ~2× l'asticella
dovrebbe rifiutare con un messaggio onesto, non persistere una posa
selvaggia.

**Settimo giro live (30/8 sera, grab-04) — il dubbio di Vincenzo aveva
ragione e ha trovato il difetto vero**: «le vedo un po' mosse ma pochissimo:
i mark sono molto ben distinguibili — sei sicuro della diagnosi?». La sonda
(estesa: `CAGE_AUTO_PICKS_FILE` per set di click mai arrivati allo stato —
il rifiuto camera-dietro NON salva) ha dato il verdetto: i suoi 15 click
freschi chiudono a **10.9px coi SUOI nomi** (foto buona, click buoni), la
soluzione mette la camera dietro TUTTI i 7 click Z (la faccia visibile era
zm, lui aveva scritto zp — pixel perfetti, etichetta di faccia sbagliata,
la rinomina attraverso-la-plastica la cura), e il soccorso rinomina-per-
anello la stava comprando… a 13.0px contro l'asticella di 12, **respinto per
un pixel da 3 proposte stantie** (sui soli click: 10.9). FIX: al rifiuto
camera-dietro l'intera catena di soccorso riprova sui SOLI CLICK A MANO —
le proposte sono congetture della vecchia posa e non votano contro la cura;
se la via a mano compra un fit accettabile, le proposte bloccanti si
buttano (`drop-proposals!`) e il messaggio lo dice. La diagnosi «foto
mossa» resta vera SOLO per grab-03 (lì misurata: detector cieco sul lato Z,
nessuna rietichettatura sotto 48px); per grab-04 era sbagliata — il banco
prima di sentenziare, sempre.

**LA CAUSA A MONTE DI TUTTO (30/8 notte, Vincenzo): «avevo messo p perché mi
presentava solo quelli»** — i nomi "sbagliati" di tre serate non erano suoi,
GLIELI IMPONEVA IL PANNELLO. Su una foto non registrata il culling per-mark
interroga la POSA, cioè proprio l'incognita, e offre una faccia sola; se è
quella sbagliata l'utente non ha modo di dire «io vedo zm» e ogni click nasce
già avvelenato (`show-all-marks` non basta: offre ENTRAMBE le facce, e
sbagliare resta facilissimo). Sua la cura, ed è quella giusta: **tre toggle
per-anello (Xp/Xm, Yp/Ym, Zp/Zm)** con cui dichiara, per QUESTA foto, quale
faccia vede — la stessa decisione che prende prendendo in mano la gabbia e
appaiandola alla foto. Cablato: `:cage-face-choice` per-foto (persistito,
rinumerato da Delete view), l'anello dichiarato offre SOLO quella faccia
qualunque cosa creda la posa, e la dichiarazione arriva fino alla lettura
(`read-crown` opts `:declared-faces`: le riletture che ribaltano una faccia
dichiarata sono scartate PRIMA del punteggio — through-plastic pareggiano e
nient'altro può rifiutarle; se la dichiarazione uccidesse tutte le riletture
non uccide niente, è lei a essere in dubbio). Test
`a-declared-face-is-not-re-read` (la rotazione sbagliata si corregge lo
stesso: 3 passi, faccia intatta). Suite 993/0.
NB di collaudo: il pannello mostra i toggle solo su proxy GABBIA; la regola
del passetto (dal dischetto grande verso il pallino piccolo: antiorario = p,
orario = m) sta nel tooltip di ogni bottone.

**Ottavo giro live (30/8 notte, grab-01 di battiscopa3, primo giro COI TOGGLE
delle facce) — «mi chiede di correggere punti che credo siano giusti»: aveva
ragione, e il difetto stava nel VERDETTO, non nei suoi click.** Il log: quattro
solve di fila fra 12.8 e 14.6px, ognuna che accusa una COPPIA DIVERSA
(#xm10,#ym10 → #xm10,#xm11 → #xm03,#xm08 → #xm08,#xm11). Sonde nuove al banco
(`CAGE_AUTO_FIT=<n>`, la traccia onesta del pulitore; `CAGE_AUTO_FACE=<n>`, le
otto dichiarazioni di faccia filtrate dal test fisico), quattro misure:
1. **Le facce che ha dichiarato sono le uniche possibili.** Delle 8
   combinazioni, `xm ym zp` — la sua — è l'unica che non mette la camera dietro
   dischetti fotografati. La migliore per rms (`xp ym zm`, 4.9px contro 9.2)
   è IMPOSSIBILE: la trappola attraverso-la-plastica un'altra volta, il
   residuo non vede la faccia e sceglie sempre quella girata via.
2. **I suoi click sono ottimi**: i sette dell'anello X cadono a **0–1px** dal
   dischetto RILEVATO.
3. **Sui suoi soli 11 click il fit è 9.2px** — sotto l'asticella. Con in mezzo
   le **17 proposte automatiche**: 13.8px, sopra. Le proposte costavano 4.6px.
4. **La traccia su tutti e 28**: 15.5 → 14.6 → 13.8 → 12.6 → 11.7. Nove decimi
   di pixel per scarto, **nessun crollo**; i residui sono un continuo da 28.8 a
   0.8px. Non c'è un outlier: c'è un modello sbagliato (le proposte).

Quattro difetti, tutti chiusi in questa fetta:
- **L'accusa senza il test del crollo** (la causa della sua domanda).
  `pnp-diagnosis` nominava gli scartati come colpevoli ogni volta che il
  pulitore ne aveva scartato uno — senza mai chiedersi se il residuo fosse
  CROLLATO. Il criterio sta nella docstring di `accept-rms-px` dal giorno in cui
  è stata scritta («drop the worst and refit — if the rms collapses, that corner
  was the culprit and the rest were innocent») e non era mai stato applicato.
  Sopra l'asticella il pulitore ha solo tolto i due peggiori di un fit brutto
  dappertutto, e QUALI due è arbitrario: ecco le quattro coppie diverse. Ora
  sopra l'asticella il messaggio dice che non è un punto solo e manda a
  controllare faccia e focale.
- **«Riclicca più preciso» su un punto mai cliccato**: `#xm11` (e prima
  `#xm03`, per cui ha speso una 'o') erano PROPOSTE automatiche. Una proposta
  non si riclicca — si toglie; il messaggio ora le separa dai suoi click.
- **Il ✓ verde e il pallino rosso armato sopra l'asticella**: il pannello
  scriveva «✓ registrata (rms 13.8px) — i punti … non si allineano» e
  `solve-and-apply!` ARMAVA uno degli innocenti per il riclick immediato — cioè
  gli metteva in mano proprio il gesto inutile. Ordine del `cond` invertito,
  armamento condizionato al crollo.
- **Le proposte battevano i click** (`retry-on-hand-picks!`, nuovo): se il fit
  assestato sta sopra l'asticella e nel set ci sono proposte, si risolve di
  nuovo sui SOLI click a mano; se quello è pulito E fisicamente possibile, le
  proposte si buttano e il messaggio lo dice. Misurato su grab-01: 13.8 → 9.2px.
  La catena camera-dietro aveva già imparato questa lezione (grab-04, 30/8) ma
  solo per il proprio rifiuto; il caso comune — sopra l'asticella e basta — non
  aveva la stessa rete. Nulla viene buttato se il fit a mano non compra
  davvero: meno punti sono più facili da fittare, quindi «rms migliore» da solo
  non è prova.

Test: `a-fit-that-does-not-collapse-has-no-single-culprit` (pnp-test) — asserisce
il discriminante su cui il verdetto ora poggia: un mark scambiato CROLLA sotto
l'asticella quando lo togli, una lente sbagliata del 10% no (due scarti lasciano
in piedi più del 75% dell'errore).

RESIDUO ONESTO su grab-01, e ora l'app lo dirà bene: tolte le proposte, il
pulitore scarta xm05 e xm08 con un crollo VERO (17.8 → 12.5 → 9.2px). Quei due
valgono un riclick — sono l'unica cosa che gli era stata chiesta a ragione.

**Nono giro live (31/8, foto 3 = grab-04 di battiscopa3): «non ne vuole
sapere».** Tre difetti indipendenti, tutti misurati, tutti chiusi.

**1. La dichiarazione della faccia non arrivava ai click GIÀ FATTI.** Nel log:
`click (⊙ym)` e SUBITO DOPO `facce dichiarate da te: Xp Yp Zp`. Il toggle
cambiava solo ciò che il pannello OFFRE; il pick `⊙ym` restava lì. Risultato:
un set che nomina `yp00…yp09` e `zero-ym`, cioè le DUE facce dello stesso
anello. Le due facce guardano da parti opposte: nessuna posa può averle
fotografate entrambe, e nessuna rilettura dell'ANELLO può curarlo, perché il
flip si porta dietro la contraddizione (crown e zero si scambiano insieme).
Misurato sul suo stato: delle 8 combinazioni di facce, **ZERO possibili**; col
solo `⊙ym` rinominato, una lo diventa. L'app se n'era pure accorta a metà
(«quel dischetto era già assegnato a #⊙ym: ora è #⊙yp, e quello resta da
ripiazzare») e ha lasciato l'orfano nel mucchio. FIX
`reface-picks-to-declaration!`: dichiarare una faccia RIPORTA i click di
quell'anello sulla faccia dichiarata — stesso dischetto attraverso la plastica,
cambia solo il nome, il pixel non si muove — e chi trova il nome nuovo già
occupato viene tolto, dicendolo. Test
`a-ring-clicked-on-both-faces-has-no-possible-pose` (set onesto 0.00px camera
davanti a tutto; un solo nome spostato → 0/48 riletture possibili).

**2. Nessuno controllava che un anello avesse i click su UNA faccia sola** —
cosa che si vede prima di ogni solve, in una passata sui pick. Senza il
controllo il solutore scopriva l'impossibile e usciva col rifiuto
camera-dietro, che accusa «un click su un dischetto di un anello diverso»:
diagnosi sbagliata, e Vincenzo a cercare fra click tutti giusti. FIX
`two-faced-rings` + rifiuto PRIMA del solve, che nomina i pick colpevoli e la
mossa (dichiara la faccia di quell'anello, oppure gomma).

**3. Il soccorso aveva TROVATO la lettura giusta e l'ha buttata per 2 pixel.**
`cage-relabel-rescue` restituiva `nil` quando il candidato superava
`(max baseline-rms 12)`; su grab-04 la baseline era 11.2 e l'unica lettura
fisicamente possibile dei 13 click chiude a **14.0px** → scartata in silenzio,
e il messaggio diceva «e non basta». FIX: il candidato si RESTITUISCE con
`:adoptable?`, i due call site chiedono quel flag (adozione invariata, nessuna
soglia toccata) e il rifiuto ora dice qual è la lettura possibile, a quanti px,
e su quali facce va dichiarata (`rescue-face-phrase`).

**Cosa dicono le misure su grab-04** (sonde: `CAGE_AUTO_FACE`/`CAGE_AUTO_FIT`
ora leggono anche `CAGE_AUTO_PICKS_FILE`, e la rietichettatura per anello della
sonda del seme è **possible-first** come la produzione — senza il filtro fisico
la sonda concordava con la risposta sbagliata dell'app):
- i suoi click sull'anello Y sono ottimi (0–3px dal dischetto rilevato); i tre
  doppi pallini stanno invece a 148–168px da qualunque candidato — su questa
  foto il detector ne trova 18 in tutto e i marcatori di zero non li vede;
- l'unica lettura possibile dei 13 click è **Xp Ym Zm** (14.0px). Lui aveva
  dichiarato **Xp Yp Zp**: Y e Z sono sull'altra faccia;
- la cura richiede di girare Y e Z INSIEME. Ad anello singolo con gli altri
  fermi: 0/48 possibili per X e per Z, e per Y solo scarti da ≥167px. Col solo
  Z già girato, l'anello Y ha 8/48 possibili e la migliore è il puro cambio di
  faccia a 14.0px, la seconda a 66.3 — cioè, applicato il filtro fisico, la
  risposta è netta;
- sotto `Xp Ym Zm` la traccia è 17.2 → 15.5 → **14.0** → 11.8 → **6.8**px: il
  crollo vero arriva al QUARTO scarto, ma `max-outliers` è 2 e il pulitore si
  ferma a 14.0. **DIFETTO ANCORA APERTO, nominato**: la valvola a 2 è tarata su
  set piccoli e su 13 pick lascia la verità fuori portata. Non l'ho toccata —
  alzarla lascia il pulitore mangiare punti buoni e ho le prove di una foto
  sola. Da decidere con più foto in mano.

**Decimo giro live (31/8, foto 5 = grab-06 di battiscopa3): «non riesco a farla
passare».** La foto è diversa dalle altre due: qui i click sono PERFETTI — tutti
e otto a **0–1px** dal dischetto rilevato, e il detector su questa foto ne trova
33 — eppure il fit non scende sotto 30px e ogni tentativo esce col rifiuto
camera-dietro.

**Cosa dicono le sonde** (banco, sui pick del log):
- **La faccia X dichiarata è sbagliata.** Delle 8 combinazioni, l'unica
  fisicamente possibile è **`xp ym zp`**; lui aveva dichiarato **Xm** Ym Zp.
  Tutto quello che è successo dopo — compresa la rinomina automatica «la corona
  era sfasata di 2 mark, letta al contrario» — è stato calcolato dentro la
  famiglia sbagliata.
- **Anche con le facce giuste il set non regge**: 130.6 → 56.5 (tolto
  `⊙ym`) → 30.9px (tolto `xp05`), e lì il pulitore si ferma perché restano 6
  punti, il minimo. Residui: `⊙ym` **249px**, `xp05` 100px, `xp07` 52px, il
  resto 12–31px. Il messaggio dell'app («lo zero dell'anello Y non torna con
  NESSUNA rilettura … quel click è su un dischetto sbagliato») era GIUSTO ed è
  ora corroborato al banco.
- **Il soccorso non ha nemmeno girato.** Sul secondo set (7 pick) l'anello più
  fornito ne aveva 3, e `cage-relabel-rescue` esce subito su `(>= (count
  anchor) 4)` — mentre il messaggio diceva «Ho provato a rinominarli anello per
  anello … e non basta». Non aveva provato niente.

**Due difetti chiusi, tutti e due di ONESTÀ DEL MESSAGGIO:**
1. **La riga «unica lettura possibile» che avevo aggiunto il giorno prima
   sparava a qualunque rms**: qui ha detto «chiude a 30.8px … se è quello che
   VEDI, dichiaralo», mandandolo a rifare i bottoni delle facce sulla forza di
   un fit senza valore. Ora esce solo entro ~2× l'asticella — la stessa regola
   che vale in tutto il resto del namespace: sopra quella soglia un numero non
   è una prova.
2. **«Ho provato … e non basta» era una bugia quando la ricerca non era
   partita.** Ora, se l'anello più fornito ha meno di 4 click, il rifiuto lo
   dice e dà la mossa vera: clicca altri mark sullo STESSO anello.

**MISURA DA TENERE** (non cablata, prove di un set solo): sul set B una posa
seminata da TUTTI i pick con UN anello rietichettato trova `xp ym zp` a
**12.2px** (secondo classificato 29.3 — margine largo) dove la produzione
stava a 120.3px e rifiutava. La produzione semina solo dall'anello di
ancoraggio (2 letture, rot 0) più le pose che il chiamante ha già in mano;
«tutti i pick con un anello riletto» è un seme che non prova mai. Controprova
sulle foto già caratterizzate: grab-01 vince con la lettura IDENTICA (11.1px,
i suoi nomi, nessuna rinomina) e grab-04 con `xp ym zm` a 14.0px — cioè
allargare la ricerca non sposta le risposte che sappiamo giuste. Sonda:
`CAGE_AUTO_JOINT=<n>` (`CAGE_AUTO_SEEDALL=1` per il seme da tutti i pick).

**REGOLA D'USO che ne esce, per Vincenzo**: la faccia si dichiara PRIMA di
cliccare, e se il fit non scende è la prima cosa da rimettere in discussione —
su tre foto di fila (grab-04, grab-06) la faccia dichiarata era sbagliata su
uno o due anelli, e ogni cosa a valle nasceva avvelenata.

**Undicesimo giro live (31/8, foto 4 = grab-05 di battiscopa3): «sulla focale
dava problemi la 4… ho provato a rifarla ma non va».** Il messaggio nuovo del
rifiuto AVEVA la risposta giusta e lui non l'ha potuta usare — ed è quello il
difetto di questo giro.

**La focale non c'entra** (era la sua ipotesi, misurata e scartata): sweep da 36
a 52mm sui suoi pick, il minimo è piatto — 38mm→9.2px, 44mm→11.4px, 48mm→13.8px.
Sei millimetri comprano due pixel: la 44 resta la lente verificata due volte, e
non è lei a tenere alta questa foto. Quello che aveva mandato in vacca la
rifinitura (10.993 → 47.1px, «la peggiore è la foto 4») era la foto 4 registrata
con la faccia Z SBAGLIATA nel giro precedente: R stava misurando la lente su una
posa avvelenata.

**La foto 4 in chiaro**: l'unica lettura fisicamente possibile è **Xm Ym Zm** —
lui aveva dichiarato **Zp**. Con Z sulla faccia m la traccia è 240.9 →
**16.7** (tolto `xm09`, residuo 503px) → **11.4px** (tolto `xm04`, 24.7px):
SOTTO l'asticella. Cioè la foto passa, e serviva un bottone solo. `xm09` sta a
1px da un dischetto rilevato ma col nome sbagliato; `xm04` sta a 194px da
qualunque candidato (lì il detector non vede niente).

**IL DIFETTO: una risposta giusta che nessuno può eseguire.** Il messaggio
diceva «legge X sulla faccia m, Z sulla faccia m» — vero — ma lui aveva
dichiarato `Xm Ym Zp` e per ricavarne la mossa doveva confrontare tre lettere
con le proprie a memoria. FIX: `rescue-face-phrase` ora emette il **DELTA
rispetto alla dichiarazione corrente**, e il messaggio apre con il gesto:
«LA MOSSA: cambia il bottone Zm (Xm va bene), poi ripremi 'r'». Se la lettura
coincide con le facce già dichiarate, non parla di facce per niente e dice che
il problema è nei nomi dei singoli click.

**PATTERN ORMAI DA TRE FOTO, e la scelta di NON toccarlo**: la lettura giusta
cade a un soffio dall'asticella (grab-04 14.0, grab-06 12.2, grab-05 12.7 nel
soccorso mentre il solve piano ne fa 11.4) e viene rifiutata. Rilassare
l'adozione violerebbe la regola pagata «un soccorso che non compra un fit sotto
soglia non rinomina» — rinominare i click sulla forza di un fit sopra soglia
persiste una congettura. La via d'uscita giusta è quella cablata qui: NON
rinominare da soli, ma dire ALL'UTENTE quale bottone premere; dichiarata la
faccia da lui, la rinomina non serve più e il solve normale ci arriva da sé
(11.4px su grab-05).

**Dodicesimo giro (31/8, foto 4): «credo che tu stia sbagliando: il ring Z è p,
i mark girano in senso antiorario» — e la sentenza del banco NON era autorizzata.**
Quarto ribaltamento del suo dubbio; la regola resta quella («SONDA, non
congetturare» — e sonda anche il TUO strumento, non solo la foto).

**Prima cosa verificata: la sua regola di lettura è GIUSTA.** Sonda nuova
`CAGE_AUTO_CHIR=1`: costruisce la gabbia, mette una camera davanti a ciascuna
delle sei facce e misura IN PIXEL (v verso il basso, come l'occhio sulla foto)
il verso dal dischetto grande al pallino piccolo. Risultato netto e uguale sui
tre anelli: **p = ANTIORARIO, m = ORARIO**, esattamente il tooltip. Il sospetto
che il modello fosse invertito (una regola scritta in coordinate matematiche
legge al contrario in un'immagine) è MISURATO E SCARTATO: il suo metodo non è
invertito.

**Seconda cosa: la sentenza del banco era vera per la geometria e senza
titolo.** Sotto i suoi nomi (Xm … Zp) i dischetti Z guardano via dalla camera
di **43°** — non un caso limite, il test non è rumore lì. MA quella posa esce da
un set che contiene `xm09` con un residuo di **508px** e due click (`xm04`,
`xm06`) a 113–194px da qualunque dischetto che il detector veda su questa foto.
Traccia coi suoi nomi: 240.6 → 14.4 (tolto xm09) → **9.3px** (tolto xm04) — cioè
la SUA lettura, ripulita, fitta meglio (9.3) di quella che il banco proponeva
(11.4 con Z su m). Una posa tenuta su buttando un punto a 508px non ha titolo
per giudicare una faccia, che è una decisione di SEGNO.
Controprova onesta: con Z=p fissato, nessuna rinumerazione dell'anello X scende
sotto 53px. Quindi **questa foto, con questi click, non decide**: o la lettura
di Z è sbagliata, o parecchi click X lo sono, e il set non contiene
l'informazione per distinguerlo.

**FIX (`gross-pick-px` = 5× l'asticella):** il rifiuto non afferma più una
faccia quando la lettura che la sostiene ci arriva solo BUTTANDO un punto
grossolano. Al suo posto nomina quel punto e la sua distanza («la lettura
migliore ci arriva solo buttando #xm09, che le cade a 508px — una posa tenuta su
da uno scarto così non ha titolo per giudicare le facce»). È lo stesso principio
del test del CROLLO (30/8) applicato un piano più su: **una diagnosi non può
essere più forte delle prove che la reggono.**

**Lezione di metodo, la terza in una settimana**: due giorni fa il difetto era
accusare punti innocenti perché nessuno chiedeva se il residuo crollasse; ieri
era una risposta giusta che nessuno poteva eseguire; oggi è una risposta
AFFERMATA TROPPO FORTE. Ogni volta il codice sapeva abbastanza per dire il vero
e diceva di più.

**Tredicesimo giro (31/8, foto 4, set PULITO da 15 click): la disputa su Z
resta APERTA, e stavolta una prova indipendente sta dalla parte di Vincenzo.**

Il set nuovo è il migliore che questa foto abbia avuto: 8 click su 15 cadono a
**1px** da un dischetto rilevato (xm00, xm05, xm10, ym00–ym03, ⊙ym). Nessuno
scarto grossolano: il gate `gross-pick-px` lascia passare, e il messaggio torna
ad affermare Zm — a 14.7px.

**La sonda della predizione (`CAGE_AUTO_PREDICT`), costruita per rompere lo
stallo senza usare i pick contesi**: si fitta la posa sui SOLI 8 click fidati
(anelli X e Y, nessun pick di Z), rms 9.8px, e si chiede al modello dove cade
l'anello Z:

| mark | predetto | click di Vincenzo | distanza |
|---|---|---|---|
| zero-**zp** | [797 983] | [790 977] | **9px** |
| zero-zm | [800 996] | [790 977] | 22px |
| **zp00** | [858 992] | [845 986] | **14px** |
| zm00 | [861 1005] | [845 986] | 25px |
| **zp03** | [413 881] | [421 880] | **8px** |
| zm03 | [414 894] | [421 880] | 16px |

**Su tutti e tre i mark i suoi click stanno più vicini alle posizioni `zp` che
alle `zm`, con un fattore ~2.** Cioè i NOMI che ha dato sono coerenti con dove
il modello mette i mark zp — mentre il test delle normali dice che quella faccia
è girata via di 44–48°. Le due prove si contraddicono e la foto non le concilia.

**Cosa resta accertato, e cosa no**:
- la sua REGOLA di lettura è giusta (sonda della chiralità, misurata: p =
  antiorario in pixel);
- i suoi click Z sono coerenti con le posizioni zp (prova indipendente sopra);
- il test camera-dietro dice il contrario con margine largo (44–48°) — ma la
  posa su cui poggia è fissata anche dall'anello **Y, la cui faccia è decisa da
  soli 13–17°**: quello sì è un caso limite, e se Y è girato, Z lo segue;
- **su questa foto il detector non vede NIENTE sull'anello Z** (dischetto
  rilevato più vicino a ogni predizione: 45–202px), quindi non c'è un terzo
  testimone.

**Difetto chiuso in questo giro** (quello che lui ha riportato): «mi dice di
cambiare il bottone Zm (ma è già Zp)». Il messaggio diceva «cambia il bottone
Zm», che si legge «cambia il bottone Zm» — e se il pannello mostra Zp quel
bottone non esiste. Ora dice **da → a**: «premi il bottone Zm al posto di Zp
(Xm Ym restano come sono)», e apre con «LA MOSSA (SE SEI D'ACCORDO)», perché su
questa foto l'app non ha titolo per dare un ordine.

**PROSSIMA MOSSA PER CHIUDERLA, non ancora fatta**: la gabbia è INCOLLATA e la
sessione ha altre foto registrate. Da quale lato del piano Z stava la camera in
foto 4 è un fatto che le altre pose della sessione possono arbitrare — è la
riconciliazione di sessione già in coda. Finché non c'è, su questa foto si segue
la stampa (Vincenzo), non il banco.

**BUCO RESIDUO, nominato**: il COLD START — una sessione senza nessun
montaggio noto non ha arbitro, e un gemello con fuori-anello ≥2 e zero di
luminanza può ancora registrare (misurato sul sintetico nel test
`the-session-mounting-convicts-the-machine-twin`, che lo asserisce come
fatto motivante). Le foto registrate a mano nutrono il voto, quindi in una
sessione reale la finestra è stretta; la chiusura vera è la prossima fetta:
**riconciliazione di sessione** (quando il montaggio matura, ri-giudicare le
registrazioni fatte prima — foto 1 insegna che vale anche per la mano) e/o
la **chiave anti-ribaltamento** in coda di ristampa, che ridà alla chiralità
il valore assoluto per cui era stata disegnata.

**STATO AL 30/8** (tutto ancora valido, la leva 1 in particolare): l'IDENTIFICAZIONE
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
2. ~~**Arbitro del gemello per semi macchina**~~ — **FATTA (31/8)**: il
   montaggio di sessione votato sulle coppie (senso,k) degli indici rilevati
   (vedi STATO AL 31/8). Quello che il 30/8 sembrava mancare («il detector
   manca gli indici») era falso: li vedeva, ma negli alloggi del montaggio
   REALE (Y ribaltato), non in quelli del modello. Confermato il NB del
   30/8: `explained` satura (19 il gemello), rms non arbitra — arbitra solo
   l'indice, e solo ATTRAVERSO la sessione.
3. **Riconciliazione di sessione + cold start** (il buco residuo della
   leva 2): quando il voto matura, ri-giudicare le registrazioni accettate
   prima — a mano comprese (foto 1 della sessione-verità È un gemello a
   mano) — e dare un principio d'ordine alle sessioni tutte-auto. La cura
   fisica parallela è la chiave anti-ribaltamento (coda di ristampa).
4. **Selezione dell'anello povero** (foto 3, e 4/5 da riverificare): il
   concentrico di oggi non lo fa emergere (misurato 30/8: stream identico al
   libero su tutte le 8). Serve un'idea nuova — o un pettine che PESCHI
   (denti noti → cerca i dischetti mancanti sulle posizioni previste), o la
   sorella con centro più libero.
5. **Recall del rilevatore** (foto 2 — max 3 candidati — e foto 6, che oggi
   rifiuta SOLO perché nessun indice è rilevato nel frame; +1 inlier
   ovunque): `cage-opts` fu tarato sulla gabbia vecchia senza pezzo dentro.

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
  GEMELLI a 548/764 — il motivo del gate di allora; col montaggio, 31/8, i
  gemelli muoiono); `CAGE_AUTO_CONC=1` lo stream concentrico (misurato 30/8:
  identico foto per foto). NUOVI del 31/8: il banco fa una PRIMA PASSATA che
  legge le osservazioni-indice sotto le pose a mano e arma il voto
  leave-one-out per ogni foto (stampa il contesto, denuncia la SESSIONE
  CONTESA con le foto dissidenti, e annota una LONTANA la cui verità è fuori
  dal voto come «LA VERITÀ QUI È IL SOSPETTO»); `CAGE_AUTO_NOCTX=1` spegne il
  contesto (cold start puro); `CAGE_AUTO_ZERO=1` stampa il testimone-zero per
  posa (mano e auto); `CAGE_AUTO_CTXONLY=1` si ferma dopo la prima passata.
  Le tracce ora portano `:gauge`, `:obs`, `:mounting-veto`,
  `:mounting-confirmed`, `:off-ring`, e si stampano anche sui successi
  LONTANI.
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
  `auto-read` (zero click; `:teeth?` = identità coi denti — in produzione si
  accende da solo col montaggio; `:concentric?` = stream concentrico;
  `:mounting`/`:blobs`/`:min-off-ring` = leva 2), `index-witness` (gli
  alloggi dell'indice, osservazioni (senso,k) pose-assolute),
  `mounting-of`/`vote-mounting` (il voto di sessione), `ring-faces`,
  `phase-probe`
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
  `:zero-picks`, filtra le proposte con `propose-clear-px`; ora accumula il
  montaggio con `remember-cage-mounting!`/`session-cage-mounting` e appende
  le diagnosi di `cage-mounting-suffix` — RIBALTATO / sessione col gemello),
  `cage-zero-phase-rescue!` (cabla `rescue-hand-zeros` in `solve-and-apply!`),
  `erase-pick-at!`/`pnp-on-contextmenu` (la gomma),
  `on-refine-session!` (le guardie della rifinitura),
  `toggle-cage-face!`/`visible-corner-set` (i tre toggle per-anello della
  faccia, `:cage-face-choice` per foto), `declared-cage-mounting` (la
  dichiarazione che arma l'arbitro dalla prima foto),
  `cage-obs-focal-ok?` (niente voto a lente non misurata), e in
  `solve-and-apply!` il ramo camera-dietro che riprova la catena di soccorso
  sui SOLI click a mano buttando le proposte bloccanti
- `test/ridley/photogrammetry/cage_auto_study.cljs` — il banco zero-click
- `test/ridley/photogrammetry/match_cage_test.cljs` — sintetici + foto vera
- `public/builtin-libraries/acquire-cage.clj` — stampa: anelli, chiave, slot,
  stick, punta-tricuspide, culla
- Commit della settimana: `834c92b` detector → `fee7154`/`ab79a8b` read-crown →
  `c2dbee1` tasto a → `06eee7e` zero-indice/90° → `1e300b6` chiave → `c5d7692`
  portapezzi → `c341015`…`fdbfe01` guardie rifinitura → `1626c0a` zero click →
  `b8a4bb8` leva 1 (denti) → `3a81ce4` leva 2 (arbitro del montaggio) →
  `61cb20f` discriminante → `d678f15` veto anche a mano → `67b8494` gabbia
  incollata → `b366a90` soccorso sui soli click → `12ba387` toggle delle
  facce.
