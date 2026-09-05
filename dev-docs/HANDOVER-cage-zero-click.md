# HANDOVER — zero click: la gabbia si legge da sola

## In una riga

Vincenzo (2026-08-27): «se non riusciamo ad avere la registrazione automatica
delle foto sarà tutto inutile». **2/9 sera: battiscopa3 REGISTRA, 5 foto su 5**
— 2.9 / 5.8 / 5.1 / 8.5 / 4.3 px, rifinitura congiunta accettata (6.84→6.19),
**lente misurata 28.08mm**. Ci si è arrivati scoprendo che TUTTO ciò che la
sessione dichiarava era sbagliato: la gabbia vera è
`(registration-cage :d 176 :flips #{:y :z})` (due anelli incollati ribaltati,
ZERO fasi — le `:phases {:y 180 :x 180}` "verificate tre volte" erano false) e
la lente vera è ~28, non la 44 "misurata" (su una gabbia dichiarata male: la
circolarità che la memoria stessa ammoniva). Lo strumento che ha sbloccato
tutto è la GABBIA VIRTUALE disegnata COME STAMPATA sopra la foto — anelli
pieni, alette, box porta-stick, corone e doppi pallini — costruita in questa
settimana su direttiva e collaudo foto-per-foto di Vincenzo.

Entry point precedente, con tutta la storia della gabbia (detector, riletture,
anello incollato a 90°, chiave di montaggio, portapezzi, la settimana
dell'avvelenamento): `dev-docs/HANDOVER-cage-auto-detect.md`. Questo file è lo
stato dei lavori per ripartire.

---

## ATTERRAGGIO 5/9 — per la chat nuova, leggi PRIMA questo

**GATE LIVE DEI TRATTINI: PASSATO (5/9 mattina, log di Vincenzo).** Foto 2:
9/9 trattini, rms 1.21px. Foto 4: 8/8, rms 1.24px — entrambe le foto che i
dischetti rifiutavano, registrate premendo 'a' col solo seme d'occhio. Sulla
foto 4 la RICONCILIAZIONE ha fatto il suo primo intervento live: l'indice X
letto `fwd k7` a 8.4px contro il `fwd k0` votato da 2 pose, con l'alloggio
NOMINALE vuoto (il più vicino a 210px) — testimone AFFAMATO su frame povero,
non gemello quasi certamente; la foto resta nel film senza voto sulla lente,
lo spareggio è il click sul doppio pallino di X in una terza foto (il
messaggio lo dice). Da valutare in futuro se un testimone su frame affamato
debba proprio votare.

**OPZIONE (b) SCELTA DA VINCENZO E CABLATA (5/9 sera).** Tre pezzi nuovi,
suite 1014/0, fixture battiscopa5 invariato:

- **`match-cage/rim-confirm`** — la rete: ogni lettura-macchina dai
  dischetti (ramo <4 click) su gabbia con rim marks si fa CONFERMARE dai
  trattini prima dell'adozione. `:confirmed` (copertura ≥ 0.7) e `:silent`
  (meno di `rim-confirm-min-tried` 6 pezzi di faccia — tacere è onestà)
  adottano; `:disconfirmed` SCARTA la lettura e cade nel ramo trattini, col
  log che dice numeri e perché. Al banco: foto 6 (dischetti a 10.96px) è
  confermata 11/11.
- **Il normalizzatore È in produzione, dietro il suo arbitro**:
  `choose-eye-seed` — il seme di 'a' è la posa di Vincenzo O la sua gemella
  con le traslazioni rifatte dai dischetti, chi delle due ASSEGNA più
  dischetti (pareggio → l'umano). Il seme normalizzato entra in auto-read;
  se i trattini dal seme crudo rifiutano e i dischetti avevano promosso il
  normalizzato, `rim-register` riprova da lì (messaggio: «con le TRASLAZIONI
  rifatte dai dischetti»). La quiet lie da 54.8mm non passa più: la rete (b)
  la smentisce.
- **Rescue verticale del primo giro** (`read-rim :cross-reach-px`, in
  `rim-register` a 60px con cross multi-azimut ±1.2·hw): un seme oltre il
  raggio del pre-centraggio leggeva run corte ad alto contrasto (i bordi
  della barra contro la carta) e moriva `starved` — misurato su foto 5 live:
  0/9 → 3/9. ATTENZIONE al simbolo: dentro il doseq di read-rim `zero?` è
  OMBREGGIATO dal flag del pezzo — usare `==`.

**FOTO 5 (live): rifiuto ONESTO, e la diagnosi è la UX.** 18 dischetti, X e
Z quasi di taglio; la posa a occhio di Vincenzo è oltre il bacino ±12° (metà
passo del pettine — il muro dell'alias NON si allarga): overlay con
predizioni a 20-90px dai trattini veri, cross nullo su 7 pezzi su 9, run
corte = bordi barra. Nessuna scala di profondità la sblocca (sonda
`CAGE_AUTO_RIMSCALE`: 0-3/9 a ×0.7…×2.2) → non è la distanza, è
l'allineamento fine. La cura è il CONTROLLO DI POSA nuovo (sotto).

**IL CONTROLLO DI POSA disegnato da Vincenzo (5/9 sera, schizzo): «il proxy
è già gizmo di se stesso».** Rotazioni = trascinare gli ANELLI DISEGNATI
della gabbia (drag tangenziale su un anello = rotazione attorno al suo
asse); traslazioni = TRE CURSORI ai bordi della foto. Aggiustamenti
concordati («Perfetto»): (1) cursori in assi SCHERMO — sinistra su/giù,
sopra destra/sinistra, sotto vicino/lontano, col terzo che scala la
distanza camera↔pivot (pixel del pivot INVARIANTE: la gabbia resta ferma e
cambia taglia); (2) cursori A MOLLA (drag relativo, il pomello torna al
centro al rilascio).

**CURSORI COSTRUITI (5/9 sera, `install-pose-sliders!` in edit_acquire),
gate live pendente.** Vivono SOLO dove vive il gizmo (mai su
retrace/mark/stage; smontati a ogni cambio foto e alla chiusura). Un drag
muove il DISEGNO: su foto 0 trasla il proxy (trasporto rigido delle camere
registrate UNA volta al rilascio, col delta totale — pura traslazione,
compone), sulle altre muove la CAMERA all'inverso (vivo, è sicuro: un
cursore non fa raycast attraverso la camera, il feedback-loop di
on-inv-commit! non esiste qui). Il rilascio è un commit umano: :eye-posed,
:manual?, derive-faces, save — cioè il seme di 'a'. Mappatura 1:1
(px-cursore → px-immagine via Z/fx alla distanza del pivot); dolly
esponenziale, 300px = ×2 (`pose-dolly-k`). GEOMETRIA ANCORATA AL CANVAS in
px (il genitore contiene anche la toolbar del viewport: le % lo mettevano
sopra i bottoni — smoke DOM nel dev-browser, screenshot); il cursore alto
scende 40px per la toolbar in overlay. GATE LIVE DEI CURSORI: PASSATO
(«vanno molto bene così»).

**CERCHI DI ROTAZIONE COSTRUITI (5/9 notte, `install-pose-rings!`), gate
live pendente.** Il problema misurato da Vincenzo: sul gizmo 3D un anello
DI TAGLIO proietta il suo cerchio in una linea e il guadagno esplode
(«ogni piccolo movimento viene amplificato»). La sua soluzione, dal
suo schizzo: tre cerchi PLANARI ALLO SCHERMO, concentrici sul
centro-gabbia proiettato — Z dentro, Y in mezzo, X fuori, coi colori
degli assi — guadagno angolare COSTANTE 1° di trascinamento = 1° di
rotazione attorno all'asse MONDO dell'anello (bridge/box-basis), qualunque
sia il suo orientamento. Segno = il disegno segue il puntatore:
φ = −Δθ_client·sign(dot(asse, verso-camera)). Il pixel del pivot è
invariante in un'orbita rigida ⇒ i cerchi non si muovono durante la
rotazione; le TRASLAZIONI dei cursori invece lo spostano ⇒
`refresh-pose-rings-geometry!` li riaggancia vivo. SVG con hit sul solo
tratto (16px), etichette «ruota X/Y/Z»; stesso commit umano dei cursori.
VISIBILITÀ: cursori+cerchi vivono solo in modalità :gizmo
(`refresh-pose-controls-visibility!` agli 8 set di :mode) — in
pnp/retrace/mark/marker i click possiedono il frame. Lo spareggio del
GEMELLO di foto 4: Vincenzo ha cliccato xp00 a mano su foto 1 e 2 e
ri-registrato a 3.8/4.5px (il soccorso ha scartato da solo le proposte
stantie).

**IL GIZMO 3D È MORTO NELLA VISTA FOTO (5/9 notte, decisione di Vincenzo
dopo il collaudo dei cerchi: «meno intuitivo ma la soluzione migliore —
toglierei proprio il gizmo, un doppio modo confonde»).** `install-gizmo!`
eliminata; i 4 stop (pnp/retrace/mark/marker) non la reinstallano più; il
toggle 'v' ora nasconde/mostra i POSE CONTROLS (la visibilità onora
:hide-proxy?); on-photo0-commit!/apply-inverted/on-inv-commit! restano NON
chiamate come riferimento documentato degli invarianti (trasporto rigido,
inversione proxy↔camera) — da eliminare al gate chiuso. L'Alt-drag della
sbirciatina in PnP è un meccanismo separato, intatto. COL COLLAUDO DEI
CONTROLLI, FOTO 5 SI È REGISTRATA: seme d'occhio → 13 dischetti rms 4.6 →
PnP 3.5px, 17 marker — la foto «che non si riusciva a fargliela prendere».
NOTA sul GEMELLO di foto 4 (ora k7 contro k0 votato da 3 pose): l'alloggio
nominale di X in quel frame è VUOTO (il vero indice non è rilevato, lo
spurio a 8.4px è l'unico candidato) — rifare foto 4 può NON zittire
l'avviso, perché la rilettura rivede lo stesso spurio. La foto è comunque
quarantenata dal voto (non vota sulla lente). RISOLTO 5/9 chiusura:
l'avviso esce già solo alla TRANSIZIONE di condanna (`:twin-flagged`,
in-memory) — le ripetizioni viste erano i reload della pagina a ogni
consegna, non un difetto di dedup.

**DIRETTIVA UX di Vincenzo (5/9, chiusura serata): NASCONDERE IL LAVORIO.**
«Quello che interessa all'utente è se la foto è usabile o no (e semmai se
partecipa alla determinazione della focale), il resto sono dettagli per
addetti ai lavori che farei sparire.» → memoria
`feedback_hide_the_lavorio`. SECONDO GATE LIVE DEI TRATTINI passato in
serata: foto 7 (sfocata, gabbia mezza fuori inquadratura, 30 dischetti
rilevati ma rifiutati) registrata dai trattini 10/13, rms 1.04px, dal seme
dei nuovi controlli («posizionare il proxy coi nuovi controlli è piuttosto
facile»). La R della serata: 4 foto (le due rim e la condannata escluse),
28.15 → 27.66mm annotata in memoria camera.

**BADGE + DIETA COSTRUITI (5/9 notte, «sì, mettiamo il badge e rendiamo
meno loquace l'operatività»).** (1) Filmstrip: quarto stato
`eaq-badge-nolens` — corpo VERDE (usabile) e bordo ambra TRATTEGGIATO =
registrata ma NON concorre alla lente (condannata dal voto `:twin-flagged`,
oppure `:rim?` — i trattini non hanno pick per la R); il PERCHÉ sta nel
tooltip del bottone, il forense resta nel log. Tooltip anche per gli altri
stati (prevista dal fit / posata a occhio / da registrare, col gesto).
`:rim?` ora PERSISTE in acquire-state.json (come `:manual?`) — senza, al
reload il badge avrebbe mentito e il verdetto-dischetti sarebbe tornato a
processare i rim. Le foto rim salvate PRIMA di stanotte non hanno il flag
nel file: si risana da solo al prossimo salvataggio della sessione. (2)
Dieta: «facce lette dalla posa» esce SOLO quando la lettura CAMBIA la
dichiarazione della foto o sposta/toglie click — usciva a ogni commit dei
controlli e dominava il log. Criterio permanente in
`feedback_hide_the_lavorio`: un messaggio user-facing deve cambiare la
risposta a «usabile?» o «vota sulla lente?», sennò va nel log.

**CABLAGGIO dei trattini nel ramo di 'a' FATTO.** Tre pezzi, tutti compilati
puliti (`:app` 16 warning pre-esistenti altrove, `:cage-auto` zero):

- **`match-cage/rim-register`** — la funzione di produzione. Due giri come
  l'ICP del seme (lettura sotto l'occhio → solve seminato → RILETTURA sotto
  la posa risolta → solve), e l'accettazione NON è il residuo da solo: (a)
  `eye-compatible?` — la camera deve atterrare dove l'occhio l'ha messa (gli
  alias del pettine spediscono la camera a 77-208mm); (b) **asticella di
  copertura al 2° giro** (`rim-coverage-min` 0.7 — le vere rileggono 8-9/9,
  gli alias 4-5/9); (c) la barra rms del canale occhio
  (`eye-accept-rms-px`, 8). Ogni rifiuto torna coi numeri
  (`:starved | :no-solve | :far-from-eye | :coverage | :rms`).
- **In `edit_acquire`**: nel ramo <4 click, quando `auto-read` rifiuta E c'è
  il seme dell'occhio, 'a' prova i trattini. Successo →
  `apply-rim-registration!`: posa applicata direttamente (nessun pick di
  dischetti la regge, quindi niente `on-solve-pnp!`), risultato
  `{:pnp? true :rim? true}`, proposte stantie purgate, testimone dell'indice
  + voto del montaggio come sempre, log e status coi numeri. Rifiuto → la
  frase dei trattini coi numeri in coda al messaggio di rifiuto
  (`rim-refusal-phrase`). **`cage-pose-verdict!` è SPENTO sui risultati
  `:rim?`**: «dischetti spiegati» lì non ha giurisdizione (misurato 5→3 con
  posa giusta) — il verdetto è già pagato dall'asticella di copertura.
- **Banco della funzione di produzione**: sonda `CAGE_AUTO_RIMREG=1` su
  battiscopa5 — foto 4 (rifiutata dai dischetti, seme a occhio) ACCETTATA
  8/8 trattini, rms 1.23px, camera a 2.6mm dal seme; foto 2 (il seme CATTIVO
  congelato nel fixture, 5-7°/24mm) RIFIUTATA onesta (`starved` 0/9), zero
  falsi agganci; foto 1/3 (registrate) accettate 12/12 e 11/12 a 1.1-1.3px,
  camera a 3-4mm. **GATE LIVE PENDENTE**: una sessione vera dove una foto
  rifiutata dai dischetti si registra premendo 'a' col solo seme d'occhio —
  il banco dice che succede, serve l'occhio di Vincenzo sul disegno.

**AUTOMATISMO TRASLAZIONI — misurato, NON cablato.** Osservazione di
Vincenzo (5/9): al gizmo le rotazioni vengono facili, la fatica sono le
TRASLAZIONI (avvicinare/allontanare la gabbia finché è grande quanto quella
in foto). `match-cage/eye-normalize-translation`: tiene la ROTAZIONE
dell'occhio e rifà la traslazione dai dischetti rilevati — baricentro
mediano → spostamento laterale, rapporto delle dispersioni → profondità;
forma chiusa, due passate, trim anti-spazzatura, scala fuori [⅓,3] → posa
restituita INTATTA. Sonda `CAGE_AUTO_EYETRANS=1`. NUMERI (battiscopa5):
sulla foto RICCA (29 dischetti) guarisce OGNI guasto di traslazione
(profondità ×0.6…×1.8, laterale 40mm, combinati, anche con 3° di rotazione
sopra): da 100-300px a ~46px, e l'ICP aggancia — 16 corr, rms 2.8px, camera
a 3.2mm — dove il seme guasto muore o finisce a 48-222mm. Sui frame POVERI
non aggancia, e va bene così: lì i dischetti non misurano la gabbia
(territorio dei trattini) e i gate rifiutano. **TRAPPOLA MISURATA prima di
cablare**: su foto 4, seme 3°+z×1.5 normalizzato → ICP 9 corr rms 6.3px
camera a **54.8mm** — passerebbe le barre del canale occhio (la bugia
silenziosa che quel canale teme), MENTRE senza automatismo lo stesso caso
cade nei trattini e registra a 2.6mm. Quindi NON si cabla davanti ai
trattini; le opzioni sul tavolo: (a) normalizzatore solo come ULTIMO
ripiego dopo dischetti+trattini; (b) ogni posa accettata da seme-macchina
su gabbia con rim marks si fa CONFERMARE dai trattini (read-rim come rete
di verdetto, la copertura come arbitro) — l'opzione (b) darebbe una rete
anche al canale occhio di oggi. Decisione a Vincenzo. Nota di banco: la
VECCHIA battiscopa non fa da banco per questa sonda (dichiarazioni pre-2/9
avvelenate + scala 4032px).

---

## ATTERRAGGIO 4/9 — per la chat nuova, leggi PRIMA questo

**LO STATO, in breve.** battiscopa4: 9/9 foto registrate a 1.55-2.43px,
lente C922 = 28.15mm (quarta misura concorde), store sano. La maratona
3/9→4/9 ha chiuso, IN ORDINE (ogni blocco qui sotto ha il dettaglio):
multi-start della R guarito (briglia-chimera, pick avvelenati, asticella
sullo store) con GATE LIVE PASSATO; budget outlier proporzionale in tutti
i solve per-foto; 'a' con 0-3 click prova da sola; ASTICELLA RELATIVA
(read-crown a frazione, rename-bar a mediana, cage-pose-verdict! come rete
sull'esito); RICONCILIAZIONE DI SESSIONE (l'ultimo dei «difetti storici» —
il voto condanna il gemello per nome e gli toglie il voto sulla lente);
SEGMENTI SUL BORDO (modello+disegno, dietro `:rim-marks?`); GIUNTI GEN 2
(seconda chiave + tasche d'invito, dietro `:gen 2`); STICK PARAMETRICI
((d/176)^0.75, giochi assoluti); e la stampa è ora
**`examples/print-cage.clj`** (in inglese, commenti operativi in fondo —
la libreria acquire-cage è morta).

**ARCHITETTURA DA TENERE A MENTE** (domanda di Vincenzo, 4/9): il file di
stampa e la gabbia disegnata in edit-acquire sono due RENDERER della
stessa fonte (il proxy `registration-cage`): il file non contiene misure,
le legge. La divergenza possibile è PLASTICA↔MODELLO (un edit geometrico
nel file stampa una gabbia che il programma non disegna né cerca — stessa
classe del flip non dichiarato, si scopre allo stesso modo); le uniche
cose ristatate nel file sono `inlay`, la regola −0.4 dello stick e
`to-ring-frame`, elencate nell'intestazione del file.

**GATE LIVE PENDENTI**: (1) riconciliazione — serve una sessione dove una
registrazione vecchia contraddica il voto; (2) asticella relativa su una
foto storta (metà passata: zitta sulle buone); (3) luce ambiente — 2 punti
dato (48 candidati ma selezione morta su inquadratura di taglio; il seme
dell'occhio resta il recovery), prescrizione che si forma: «luce ambiente
E due anelli di faccia».

**CODA**: ~~geometria di stampa dei segmenti sul bordo~~ FATTA (4/9
pomeriggio): `rim-spans` nel modello è l'unica fonte (la leggono
`rim-segments` per il disegno E `printable-ring` → `:rim-segments` per la
stampa); in print-cage.clj le fasce sono settori di guscio incassati a filo
(`inlay` radiale, metà spessore verso p, zero interrotto), tasca+nastro con
la regola dell'overshoot; smoke dal vivo: 12 bande, gap dello zero vuoto,
z=[0,1.5] esatto in posa piatta E assemblata. ATTENZIONE stampa: le fasce
condividono i layer col corpo — due colori PER OGGETTO (AMS), il
cambio-colore per altezza non copre più il bordo (save-3mf lo dice).
Vincolo dichiarato: sotto ⌀≈85 la tasca del bordo morde le tasche dei
dischetti (inlay assoluto vs corona che scala).

**DETECTOR dei segmenti — fetta-BANCO FATTA (4/9 sera).** Gabbia gen 2
stampata, incollata, sessione live battiscopa5 (4 foto, congelata in
`test-assets/cage-battiscopa5`); i segmenti blu disegnati COMBACIANO con la
plastica (gate visivo passato da Vincenzo). `photogrammetry/rim_detect.cljs`:
lettura GUIDATA dalla posa (mai unguided: l'identità viene dal seme), per
pezzo: pre-centraggio TRASVERSALE (la fascia è mezza altezza, l'errore
verticale della posa ammazza prima di quello tangenziale), profilo in
azimut a passi da 1px, run scure filtrate PER LUNGHEZZA e poi per
vicinanza (l'ordine inverso faceva vincere un blip vicino allo 0 sul
trattino vero a 40px — misurato), contrasto per-run. Sonda
`CAGE_AUTO_RIM=1|2` (+`CAGE_AUTO_RIM_OVERLAY=<dir>`) in cage_auto_study.
NUMERI battiscopa5: foto REGISTRATE 11-12/12 letti, mediana 2.9-6.3px
(plastica=modello al pixel, seconda conferma dopo l'occhio); foto 4
(RIFIUTATA dai dischetti, posa a occhio) → 8/8 trattini, solve rms
1.21px, dischetti spiegati 10→15 su 26 — LA FOTO CHE NON SI REGISTRAVA È
REGISTRATA DAI TRATTINI, al banco. Foto 2, primo giro: posa a occhio
troppo storta (overlay: predizioni fuori dalla plastica) — ricerca locale
onestamente muta; Vincenzo RIALLINEA a occhio (coi segmenti blu come guida)
e il banco aggancia: 8/9 al primo giro, 9/9 al secondo, rms 1.21px, verdi
sui trattini di ENTRAMBI gli anelli di taglio. ENTRAMBE le foto rifiutate
dai dischetti si registrano dai trattini con un seme d'occhio decente.
Caveat misurato: su frame così (14 dischetti rilevati appena) la metrica
«dischetti spiegati» è affamata e NON fa da arbitro (5→3 a 12px con posa
palesemente giusta) — l'arbitro lì è il disegno sovrapposto + l'rms dei
trattini su due anelli. BACINO DI CATTURA misurato (5/9, sonda `CAGE_AUTO_RIM_SWEEP=1`, verità =
posa dai trattini, perturbazione rigida attorno al centro gabbia):
riaggancio pulito fino a **3-6° / 26-45px sull'immagine** (foto 2 tiene i
6°, foto 4 li perde — dipende da quali anelli guardano la camera), morto a
9°+; le due pose buone di Vincenzo predicevano a 7.7-10.5px mediani, la
cattiva era a 5-7°/24mm. **TRAPPOLA MISURATA: falsi agganci a 15-20°** —
il pettine dei trattini è periodico (passo 30°), oltre metà passo la
lettura aggancia il trattino SBAGLIATO e converge a 77-208mm dalla verità
con pochi trattini (4-5 su 9): per questo la ricerca resta ±12° (< metà
passo), e il cablaggio DEVE avere (a) un gate di distanza dal seme (le
pose vere atterrano a 0.4-3.2mm dal seme, le false a 77+; mc/eye-gate-frac
esiste già) e (b) un'asticella di copertura al 2° giro (vere: 8-9/9,
false: ≤5/9); l'arbitro definitivo dell'identità sarà lo ZERO INTERROTTO
(un alias di un passo mette un trattino intero dove va la coppia corta) —
da leggere quando serve, non costruito. CODA detector: CABLAGGIO nel ramo
seminato di 'a' (il caso d'uso è provato; browser = stesso lum-at di
blob-detect su ImageData; messaggi nel log come il resto di 'a'; su frame
poveri la metrica «dischetti spiegati» NON fa da arbitro — vedi sopra);
correlazione a PETTINE declassata a rifinitura zero-click (e va costruita
con lo zero-check, vista la trappola dell'alias). Fixture con la POSA
CATTIVA di foto 2 congelata in test-assets/cage-battiscopa5 (banco del
pettine); la posa buona vive nella sessione viva. Restano anche:
zero-click selezione/recall (luce); punta non parametrica.

**TRAPPOLE nuove di stanotte** (le vecchie nei blocchi sotto): mai
avvolgere un corpo grosso in una nuova arity coi piccoli edit (parinfer
ribilancia — firma variadica); se la nREPL si incastra dopo un kill della
suite, ciclo pulito di shadow (`npx shadow-cljs stop` + `npm run dev`);
con più tab connessi gli eval del REPL browser atterrano su tab diversi —
batch atomico in UNA evaluate (il collaudo di print-cage.clj è
l'esempio); lo smoke di examples/ NON è nella suite, è la tecnica manuale.

---

## ATTERRAGGIO 3/9 sera — per la chat nuova, leggi PRIMA questo

**LO STATO.** Il run del pomeriggio ha sciolto il filo: il multi-start È
partito (la riga di decisione c'era) ma «battuto — rms 32.79px su 4 viste
contro 9.43 su 6». Riprodotto ESATTO al banco (sonda nuova
`CAGE_AUTO_RESTART=1`, stesso 32.79) e trovati TRE difetti concatenati,
tutti curati e verificati:

1. **La briglia restituiva una chimera.** `bundle/refine-session`, quando il
   fit sbatteva sul ±15%, tornava focale CLAMPATA + pose del fit in fuga:
   uno stato che nessuno ha mai fittato, rms 28-33px su viste che da sole
   stavano a 2-11 — e il leave-one-out, giudicando su quel numero, buttava
   fuori le viste BUONE. Cura: al clamp le pose si RI-FITTANO con la focale
   congelata al bordo (bundle.cljs).
2. **I pick portano l'impronta della lente a cui sono nati.** Foto 1/4/6 di
   battiscopa4 hanno 7-8 etichette sbagliate CIASCUNA (assegnate da 'a'
   quando la sessione era a 34-40mm su camera 27.35) — sopra il tetto
   storico di 2 outlier; il refine congiunto le mangiava tutte e voleva 34.
   Sonda `CAGE_AUTO_BUDGET=1`: a f27.35 con budget alto TUTTE le foto
   crollano d'accordo a 1.5-2.6px; a f34 le foto sane restano a 4.6-6.4 e il
   greedy SI RIFIUTA di scartare — l'errore da lente sbagliata si spalma su
   tutti i residui, non spicca. È questa asimmetria che tiene onesto il
   budget: la lente sbagliata non può barare a colpi di scarti. Cura: il
   reseed del secondo start scarta con budget proporzionale (40% dei pick)
   e al refine congiunto vanno solo i sopravvissuti; se il restart VINCE,
   gli scarti freschi finiscono in `:pnp-outliers` (rossi sulla foto, fuori
   dal voto, riassegnabili con 'a'). Lezione, gemella di quella del 3/9
   mattina: rifare le pose senza rifare i PICK non riparte niente.
3. **La bugia sigillava la propria via di fuga.** Il run perdente ha
   ri-archiviato 34.05 «refined» sopra il 27.35 appena rimesso (QUARTA
   volta) — e con memoria ≈ sessione il multi-start non sarebbe mai più
   partito. Cura: asticella di plausibilità — un refined che dista >15%
   dalla misura in memoria si RIFERISCE a voce alta, non si archivia
   («cancella la voce da ~/.ridley/cameras.json se la camera è cambiata»).
   Store rimesso a 27.35 (source `measured`, RDCam agosto).

**VERIFICA AL BANCO** (stessa sonda, dopo le cure): reseed 1.5-2.6px su
6/6 viste, refine congiunto 27.35 → **28.12mm, rms 2.06px su 6 viste**,
niente briglia, nessuna vista scartata → la dominanza (6≥6 E 2.06<9.43) fa
VINCERE il restart. E 28.12 combacia col 28.08 misurato su battiscopa3 con
la stessa C922: due sessioni indipendenti, stesso numero.

**GATE LIVE PASSATO (3/9 sera, log di Vincenzo)**: una R su battiscopa4 →
«RIPARTITA dalla lente in memoria», focale 34.0455 → **28.1209mm**,
riproiezione 9.4342 → **2.0639px**, sei foto a 1.55-2.42px, **26 click
segnati come scarti** — esattamente gli 8+2+0+7+2+7 contati dal banco, e
il 28.12 combacia col 28.08 di battiscopa3 (stessa C922, sessione
indipendente). Store: 28.12 «refined». Un difetto cosmetico trovato nel
log del gate e corretto subito: «RIPARTITA dalla lente in memoria,
28.1209mm» — il messaggio leggeva `:remembered-focal-mm` DOPO che
l'annotazione l'aveva già aggiornata; ora il valore di partenza (27.35) si
cattura prima. Il log è lo strumento di misura: non deve mentire nemmeno
per sbaglio.

**CODA**: quella del 3/9 mattina, più: il tetto di 2 outlier resta il
difetto storico nei solve per-foto ('a'/'r') fuori dal restart — valutare
il budget proporzionale anche lì, con la stessa asimmetria a fare da
guardiano.

**AGGIORNAMENTO 3/9 notte — due voci di coda CHIUSE (decisione di
Vincenzo: «parti pure»):**

1. **Budget proporzionale nei solve per-foto.** `outlier-budget` (40% dei
   pick, pavimento 2 — sotto gli 8 pick NON cambia niente) + `solve-photo`,
   usati da TUTTI i solve di `solve-and-apply!` (primo tentativo, specchio,
   seeded, flip di faccia, soli-click-a-mano) e dal rescue a mano; il
   reseed del multi-start ora usa lo stesso helper. Esclusi di proposito:
   `cage-relabel-rescue` (lì il solve GIUDICA un'ipotesi di nomi — non deve
   poterla comprare scartando i pick che la contraddicono), il flusso ad
   anello del piatto e il live-frame (pick rifatti freschi dall'immagine a
   ogni giro, l'avvelenamento non si accumula). La guardia è l'asimmetria
   misurata dalla sonda `CAGE_AUTO_BUDGET`.
2. **La trappola di 'a' con 1-3 click.** Era il ramo `<4` che (a) flashava
   solo la status line di 4 secondi, zero log — «'a' non fa niente» era
   questo messaggio che evaporava — e (b) consigliava di CANCELLARE i click
   per provare l'automatico. Ora 0-3 click vanno tutti nel ramo zero-click:
   la macchina prova da sola, i click restano (troppo pochi per seminare ≠
   avvelenati; se contraddicono la lettura finiscono rossi), il rifiuto va
   ANCHE nel log col nome della foto, e una proposta non sovrascrive MAI un
   click a mano sullo stesso mark.

Gate live di entrambe da fare alla prossima sessione di scatti (non
c'è fretta: battiscopa4 è già registrata).

**Restano in coda** (famiglia R/solve): accettazione senza asticella,
rinomina prima del solve, riconciliazione di sessione. Famiglia zero-click:
selezione (foto 3 del bench), recall del detector (foto 2, e foto 6 che
rifiuta solo per mancanza di indice rilevato).

**COLLAUDO LIVE grab-07 (3/9 notte, log di Vincenzo) — i difetti in coda
hanno un caso misurato.** Foto nuova, zero-click rifiuta due volte (bene, e
il rifiuto ora è NEL LOG — la cura della trappola funziona), 4 click ALT
giusti su ring X (xm00/01/06 + ⊙xm, li legge dal pezzo stampato). Da lì la
catena del disastro, tutta PRE-esistente: read-crown adotta una rilettura
flip-face che spiega **3 dischetti su 29 rilevati** (sopra il min-off-ring
ASSOLUTO di 2 — rumore, ma passa) e RINOMINA i click giusti xm→xp; il
solve planare su 4 click coplanari dice «fit pulito, 1.59px» (il gemello
fitta identico — 1.59 uguale per entrambe le facce, sonda); propose-and-snap
allucina 16 proposte dalla posa sbagliata; il rescue camera-dietro rinomina
altri 6 e adotta 11.7px (sotto la barra ASSOLUTA di 12). Sonda nuova
`CAGE_AUTO_TWIN=<n>` (+ `CAGE_AUTO_FOCAL`): la posa adottata spiega **4/39
visibili** contro i **21/39** della foto 3 sana — e nemmeno il flip salva
(4-7/39 comunque: non è un gemello di faccia, è la POSA sbagliata di lato,
il mirror del piano dell'anello). L'arbitro che mancava è la FRAZIONE
SPIEGATA (spiegati/rilevati): relativa, non assoluta — la stessa cura per
«accettazione senza asticella» e «rinomina prima del solve». Recovery live:
Azzera foto 7 → gabbia a occhio col gizmo → 'a' a zero click (il seme
dell'occhio, già provato su foto 1).

**RECOVERY RIUSCITO (stessa notte, log di Vincenzo):** Azzera + gizmo a
occhio + 'a' zero click → «Gabbia letta dalla TUA posa a occhio: 16
dischetti piazzati (rms 1.9px)», solve a 2.0px con 8 proposte deboli
ESCLUSE e dette per nome, R accettata: 7/7 foto a 1.56-2.38px, lente
27.99mm (terza misura concorde: 28.08 battiscopa3, 28.12 ieri). Sonda TWIN
sullo stato nuovo: foto 7 spiega **16/39 a 2.00px** (era 4/39 a 11.7) —
camera passata dal lato vero, e coi 19 pick sparsi il residuo ora
DISTINGUE le facce (2.0 vs 9.8 del flip): la cecità era tutta nel seme
coplanare di un anello solo. Il seme dell'occhio è ufficialmente il
recovery di elezione per le inquadrature dove lo zero-click rifiuta.

**ASTICELLA RELATIVA — COSTRUITA (via di Vincenzo, «certo, vai pure»),
tre pezzi:**

1. **read-crown**: la soglia di accettazione sale coi dischetti rilevati —
   `max(min-explained, min(12, rilevati/3))`. Il min-explained assoluto (6)
   è quasi gratis su un frame ricco (i 4 click del seme sono candidati
   anche loro): il 7-su-29 di grab-07 ora viene RIFIUTATO (bar 9), le
   letture sane (16-24 spiegati) passano larghe, i frame poveri (≤18
   rilevati) tengono la soglia vecchia.
2. **rename-bar in solve-and-apply!**: un rescue può RINOMINARE i click
   solo sotto `min(accept, max(accept/2, 3×mediana-rms delle altre foto
   registrate))` — l'11.7px «accettabile» in assoluto era un fuori-scala
   5.8× in una sessione a 2px. Stesso moltiplicatore 3× dell'esclusione
   della R; sessione giovane (nessun'altra foto) = barra vecchia.
3. **cage-pose-verdict!** — la rete di sicurezza sull'ESITO: dopo ogni 'a'
   che registra, la posa finale si misura contro i dischetti RILEVATI
   (match-cage/explained, tol 26) e sotto la soglia relativa il log dice
   «ATTENZIONE: spiega solo N dei M — guarda la gabbia disegnata, se è
   storta: Azzera, gizmo a occhio, 'a' senza click». Parla solo quando il
   numero è brutto (un caveat che stampa sempre smette di essere letto);
   qualunque anello della catena abbia mentito, l'esito non passa muto.
   In più: la lettura seminata dice SEMPRE la sua frazione («la lettura
   spiega E dei M dischetti rilevati») e il rifiuto del ramo seminato va
   nel log col suggerimento del seme dell'occhio.

Gate live: alla prossima foto difficile (grab-07 è già recuperata a mano).

**grab-08 (3/9, subito dopo): PRIMA PRESA ZERO-CLICK DAL VIVO — e la
scoperta della LUCE.** Foto in sola luce ambiente (niente luci ad hoc,
scelta di Vincenzo), inquadratura che a occhio era difficile: «Gabbia
letta DA SOLA: anello x + zero-indice trovati, 14 dischetti (rms 1.2px)»,
solve a 2.1px, 8 proposte deboli escluse per nome. Sonda TWIN: 15
dischetti spiegati, facce distinte nette (2.10 vs 9.37 del flip), camera
da un punto di vista nuovo (lato opposto, quasi a livello tavolo). Il dato
che regge l'ipotesi di Vincenzo («forse è la via giusta»): **42 dischetti
rilevati contro i 29-31 di tutte le foto con luci ad hoc** — le rifiutate
storiche dello zero-click morivano allo stadio delle ellissi per fame di
candidati, e la luce diffusa ha alzato il raccolto del detector di un
terzo (meccanismo plausibile: niente riflessi speculari sulla plastica).
UN punto dato: da confermare scattando le prossime foto in luce ambiente.
E la rete di sicurezza è rimasta ZITTA su una foto buona — mezzo gate
dell'asticella passato (l'altra metà aspetta una foto storta).

**grab-09 (punto dato 2 sulla luce):** ambiente, 48 candidati (raccolto
ancora su) ma zero-click RIFIUTATO — Y a 9-17° e Z a 6-20°, due anelli
quasi di taglio: la selezione non assembla l'anello. Il SEME DELL'OCCHIO
l'ha presa (19 dischetti, 2.1px; R: 9/9 foto a 1.55-2.43px, lente
28.15mm, quarta misura concorde). Lettura: la luce cura la FAME DI
CANDIDATI, non le inquadrature di taglio — lì il recovery resta gizmo+'a'.
Prescrizione di scatto che si forma: luce ambiente E almeno due anelli
ben di faccia.

**RICONCILIAZIONE DI SESSIONE — COSTRUITA (via di Vincenzo), l'ultimo dei
«difetti storici di sempre».** Il voto di montaggio ora CONDANNA per nome:
`match-cage/convict-mounting` (pura, node-testata: `the-vote-names-its-twin`)
fa leave-one-out — per ogni posa registrata, il voto di TUTTE le altre
(fuso sulla dichiarazione con `merge-mounting`, la stessa politica di
`session-cage-mounting`, ora UNA sola) e condanna sugli assi dove la sua
lettura contraddice un vincitore con ≥2 voti. In sessione:
`reconcile-cage-mounting!` gira a ogni osservazione nuova
(`remember-cage-mounting!`) — condanna annunciata UNA volta con prove e
cura («⚠ GEMELLO in sessione: foto N legge X in rev k6 contro il fwd k0
votato da D…»), assoluzione detta forte quando la foto rifatta concorda;
la R esclude le condannate dal VOTO sulla lente (mai dal film — il rms di
un gemello è esemplare sui SUOI pick, la barra delle avvelenate non lo
vede). Limite dichiarato: le obs muoiono con la lente (si azzerano dopo
ogni R adottata) e rinascono coi prossimi 'a' — l'esclusione vive finché
vive l'evidenza; sulla gabbia DICHIARATA (:flips/:phases) il quorum c'è
sempre (la dichiarazione vota 2). Caso motivante misurato: foto 1 della
sessione-verità del banco, gemello a mano scoperto dal voto delle altre
cinque — fino a stanotte da NESSUNO nell'app. Gate live: alla prossima
sessione, quando una registrazione vecchia contraddirà il voto.

**SEME DI PROGETTO — mark sul BORDO degli anelli (idea di Vincenzo, 3/9
notte; era nata per il piatto singolo).** Il bordo è massimamente visibile
esattamente quando la faccia sparisce: il segnale si accende nel caso più
difficile (anelli di taglio — grab-09). Vincoli già ragionati: (a) il
cambio colore stampa per STRATO, quindi un motivo azimutale sul bordo
verticale NON è stampabile a colori → la forma giusta sono FORI RADIALI
passanti (2-3mm su bordo ~6mm), scuri con ogni luce come i fori di faccia,
e SENZA gemello attraverso-la-plastica (si vedono solo dal loro arco
esterno); stessi azimut della corona + doppio-foro come zero del bordo.
(b) Alle distanze di lavoro: bordo ~15-20px, foro ~9px — stessa scala dei
dischetti attuali, stesso detector. (c) Percorso software dal più
economico: ancore nel modello + bande disegnate sulla gabbia virtuale →
assign/ICP nei percorsi seeded e seme dell'occhio (nessun assemblatore
nuovo) → zero-click da ellissi degeneri solo se serve. (d) Limiti: i fori
di bordo sono quasi complanari col loro anello (da soli non rompono il
gemello del singolo anello — valore INCROCIATO), e servono anelli
ristampati (gabbia nuova). Non urgente: luce ambiente + due anelli di
faccia + seme dell'occhio coprono; da progettare per la prossima
iterazione fisica.

**AGGIORNATO dalla discussione con Vincenzo (stessa notte):** i fori erano
il ripiego per stampa mono-estrusore (cambio colore per strato → nessun
motivo azimutale su parete verticale); con una multi-materiale vera il SUO
disegno è migliore e più ricco: **12 segmenti orizzontali sul bordo, a
mezza altezza dello spessore** (la metà dice la faccia m/p — la PRIMA
asimmetria di faccia fisica della gabbia: i dischetti sono fori, identici
dai due lati per costruzione), agli azimut dei mark, **zero interrotto a
2/3** (letto ribaltato appare a 1/3: testimone di chiralità e zero del
bordo). Vincolo suo: le alette limitano la lunghezza a <1/25 di giro
(«forse meno») → `cage/rim-seg-deg` 12° (1/30, ≥9° dal centro-aletta).

**MODELLO + DISEGNO COSTRUITI («vederli prima di stamparli»):**
`cage/rim-segments` (pura, as-built: fasi e flip come le ancore — flip poi
turn; banda verso la faccia p DI STAMPA, su anello ribaltato finisce
dall'altro lato come la plastica vera; test
`rim-segments-are-declared-and-as-built`), opzione **`:rim-marks?` su
registration-cage** — DICHIARATA, mai assunta: la gabbia disegnata deve
essere quella incollata, e le gabbie di oggi non li hanno.
`cage-feature-items*` li disegna (nastri BLU 0x4455ee + spigoli, r-off
0.15 anti z-fight) solo quando dichiarati. Per la revisione del disegno:
aggiungere `:rim-marks? true` alla forma del proxy della sessione. Resta
per il futuro: la geometria a due colori nella libreria `acquire-cage`
(quando deciderà di ristampare) e il detector dei segmenti (barre scure
~70×6px, ricerca guidata dalla posa nei percorsi seminato/occhio).
Collaudo visivo di Vincenzo: nessun conflitto con le alette.

**GIUNTI GEN 2 — seconda chiave + tasche d'invito (richieste di Vincenzo,
stessa notte).** Due difetti fisici della gabbia attuale: (1) il flip
dell'anello PICCOLO non è vietato da niente — le sue quattro alette
incollano altrettanto bene ribaltate, ed è così che la battiscopa ha preso
i suoi `:flips`; (2) l'azimut di ogni incollaggio si trova a occhio mentre
l'epossidica prende («il risultato è sempre un pressapoco» — fasi misurate
1-2°, e a R85 un grado è 1.5mm). Cure nel modello, TUTTE dietro
**`:gen 2`** su `registration-cage` (default 1 = le gabbie stampate di
oggi: la gabbia disegnata deve restare quella incollata — un secondo pino
rosso o alette affondate su un modello della gabbia vecchia farebbero
allineare l'occhio a plastica inesistente):
- **Seconda chiave** (`joint-tabs` gen 2): spina sull'aletta di Z verso X
  + tacca nel bordo di X, a 90° dalla prima — «una tacca a 90 gradi oltre
  a quella già messa». Con Y→X e Z→X inchiodati, ogni coppia è fissata
  per transitività: mai più `:flips` non dichiarati.
- **Tasche d'invito** (`:seat`, tagli di `seat-depth` 0.8mm nella faccia
  del partner, pareti a tab-clearance ±0.25 ≈ ±0.16° a R88): l'aletta ci
  AFFONDA (glue-face ribassata di 0.8) entrando assialmente durante la
  stessa corsa d'assemblaggio — l'azimut lo tengono le pareti, non
  l'occhio. La libreria `acquire-cage` ora taglia `#{:key-notch :seat}`
  (era «la tacca è l'unico taglio della famiglia»).
- Test: chiavi su ENTRAMBE le generazioni (gen 1 = una chiave, niente
  tasche — invariato), `seats-locate-the-glue-azimuth`,
  `gen2-laps-sink-only-into-their-seats` (fondo tasca, terzo anello,
  collisioni fra giunti).
Lezione ripagata di nuovo: mai avvolgere un corpo grosso in una nuova
arity coi piccoli edit (parinfer ribilancia) — firma variadica
`[d h & [gen]]` e il corpo resta intatto. E il ciclo pulito di shadow
quando la sessione nREPL si incastra dopo un kill della suite.
Prossima stampa: dichiarare `(registration-cage :d … :gen 2
:rim-marks? true)` e stampare da lì.

**4/9 — LA STAMPA DIVENTA UN EXAMPLES E GLI STICK SCALANO (decisioni di
Vincenzo).** (1) La libreria builtin `acquire-cage` è MORTA: «di uso una
tantum, ingombra la lista per sempre, e il codice che produce la gabbia
merita di essere letto e modificato». Ora è `examples/print-cage.clj` —
definizioni in testa, comandi operativi COMMENTATI in fondo (da
scommentare all'uso), e UNA dichiarazione (`gabbia`, in fondo: diametro +
:gen + :rim-marks?) così stampa e sessione non possono divergere. Le fn
prendono la GABBIA, non il diametro (make-print-ring/make-cage-ring/
save-3mf/measured/stick) — il gen viaggia con la dichiarazione. Manifest
builtin aggiornato, manuale registration-cage.md riscritto nei punti
acquire-cage, reference index rigenerato (bb). COLLAUDATO ATOMICO nel
runtime SCI vero via dev-browser (la trappola dei tab multipli: batch in
UNA evaluate): anello :big gen2 = [4064 3380] vertici, canale [2 2.2],
stick 130 — tasche e tacche scavano davvero. Il file NON è auto-testato
dalla suite: lo smoke è la tecnica manuale in memoria.
(2) STICK PARAMETRICI: domanda di Vincenzo («più grandi = stick più
lunghi, troppo fini si spezzano») — sì, da ~⌀220. `stick-scale` =
(d/176)^0.75 pavimento 1 su TUTTE le sezioni (body-w/len, rise, lift,
canale via `stick-major` 4.0 — l'unica misura madre), GIOCHI 0.4mm
ASSOLUTI (tolleranza di stampante: scalarli romperebbe il quarto di
giro); `stick-section-r` pubblica per lo stick; a 176 numeri collaudati
alla virgola (test `stick-sections-scale-with-the-cage`). Lo stick nel
file di stampa legge la sezione dal CANALE della gabbia: camma garantita
a ogni diametro. Resta fuori: `punta-tricuspide` non parametrica (pezzo
di collaudo, detto nella docstring).

---

## ATTERRAGGIO 3/9 — per la chat nuova, leggi PRIMA questo

**LO STATO.** La coda del 2/9 è TUTTA CHIUSA e committata (`a454732..`,
~20 commit su `grab-and-register`, suite 1009/0, warning `:app` 16): commit
spezzato, memoria focale per-camera (`~/.ridley/cameras.json`, chiave
«label @ w×h»), testimone curato (un anello = UNA lettura), e IL SEME
DELL'OCCHIO — la posa appaiata a mano come seme diretto di 'a' (assign+ICP,
niente ellissi), **provato dal vivo da Vincenzo**: «Gabbia letta dalla TUA
posa a occhio: 12 dischetti piazzati (rms 7.9px)» su battiscopa4 (sessione
nuova, C922, gabbia `:flips #{:y :z}`). Il collaudo live ha fruttato altri
fix committati: il leak `::refused` («Doesn't support name»), lo slider
della focale che non salvava, la R robusta (leave-one-out: una vista che
peggiora il fit perde il VOTO sulla lente, MAI il posto nel film —
direttiva di Vincenzo), la briglia della R spiegata (±15% per passata).

**IL FILO APERTO — la R di battiscopa4 inchiodata a un minimo locale.**
La R lì «converge» a 34.05mm (prima 40.67, prima ancora briglia a 40.8: tre
minimi locali in una sera) su una camera che sta a **27.35** (misure RDCam
di agosto). La verità è al banco: sonda nuova `CAGE_AUTO_SWEEP=1` (i PICK
salvati — pixel, lens-free — risolti a una scala di focali): **f27.35
mediana 2.36px contro 9.60 a f40.7**, foto 2/3/5 a 1.5-2.4px. Lezioni
cablate: l'appaiamento perfetto della gabbia NON giudica la lente (posa ↔
focale si compensano, l'errore muto fondante); ripartire dalla focale senza
rifare le POSE non riparte niente (dal 29 a mano è SALITA a 34 — il bacino
delle pose vecchie vince). Cura committata (`ec56436`+successivo): la R fa
MULTI-START — riparte anche dalla lente in memoria CON LE POSE RIFATTE
(per-view DLT sui pick), confronto per dominanza (≥ viste E rms più basso).
MA nell'ultimo run di Vincenzo il multi-start NON risulta partito (nessuna
riga «RIPARTITA», 34.05 ri-archiviato): sospetto primo il TAB con build
vecchio (l'hot-reload può mancare un tab in silenzio — memoria nota);
adesso il ramo logga SEMPRE la sua decisione (nessuna memoria / ≈ uguale /
battuto coi numeri / vince), quindi il prossimo run si spiega da solo.

**PRIMA MOSSA della chat nuova**: fargli fare hard-reload della pagina,
aprire battiscopa4, UNA R. Atteso: «RIPARTITA dalla lente in memoria
(27.35mm)…», focale ~27-28, foto buone a ~2-3px, «lente annotata» col
numero vero. Se resta a 34: il log ora dice il perché — leggerlo, non
tirare a indovinare. Lo store è stato rimesso a 27.35 (tre volte: 40.80
briglia, 40.67 e 34.05 minimi locali ci sono finiti dentro — la guardia
attuale blocca solo il clamp e il no-op, non un minimo locale convergente;
se ricapita, valutare un'asticella di plausibilità sullo scarto).

**CODA dopo il filo**: i difetti storici di sempre (max-outliers fisso a 2,
accettazione senza asticella, rinomina prima del solve, riconciliazione di
sessione), 'a' con 1-3 click che non fa niente (trappola UX vista live),
foto 4 di battiscopa4 mediocre anche alla lente giusta (~7.5px: inquadratura
con tutti gli anelli di taglio — capitolo «vantage warning» possibile: la
derivazione facce sa già dire quando tutto è di taglio PRIMA dello scatto).

**TRAPPOLE OPERATIVE**: le solite (MAI compile `:app` a mano col watcher —
via nREPL 7888 `(shadow.cljs.devtools.api/compile :app)`; warning 16 è
l'invariante; suite = compile :test via nREPL + `node out/test.js`,
1009/0); il formatter/parinfer sui .cljs RIBILANCIA le parentesi
dall'indentazione — mai wrappare un blocco grosso senza re-indentarlo
(pasticcio fatto e risolto su match_cage, i write da bash/python NON
triggherano il hook); le sessioni nREPL si incastrano — ciclo pulito della
memoria REPL; il collaudo UI vero lo fa Vincenzo (i suoi log sono lo
strumento di misura del canale).

## ATTERRAGGIO 2/9 — per la chat nuova, leggi PRIMA questo

**LO STATO.** Sessione `~/Pictures/RidleyScan/battiscopa3`: 5/5 registrate
(sopra), focale 28.08 accettata dalla rifinitura, gabbia `:flips #{:y :z}`.
TUTTO IL LAVORO È NEL WORKING TREE, NON COMMITTATO (branch
`grab-and-register`): prima mossa della chat nuova, con l'ok di Vincenzo,
spezzare e committare — c'è dentro: gabbia virtuale col gizmo (facce lette
dalla posa, guardia 20° con suggerimento `:geo-sign`), `:flips` in
`registration-cage` (flip = rotazione propria + fase, id = etichette di
stampa), disegno della gabbia come stampata (alette/spina/box forati solidi +
corone/pallini, misure slot spostate dalla libreria al proxy — stampa
verificata bit-identica), sbirciatina Alt+drag in 'p' (solo-preview,
snap-back; Alt+click letterale quando la gabbia è nascosta), 'v' completo in
'p', testimone dell'indice strumentato (senso/k/px nel messaggio), banco
flip-aware (`declared-flips`), impronta del voto con `:flips`
(`cage-flips-tag`), suite a 1005/0, warning `:app` 16 invariati.

**LA CODA (ordine suggerito).**
1. **Commit** — FATTO 2/9 (chat successiva): spezzato in 8 commit tematici
   `a454732..ad69b03`, working tree byte-identico, suite 1005/0, warning 16.
2. **Memoria per-camera della focale** — FATTA 2/9 (chat successiva):
   `~/.ridley/cameras.json`, chiave «label @ w×h» (la stessa Continuity è una
   lente DIVERSA a 4032px e a 1920×1440 — è così che è nata la 44 sbagliata).
   Si scrive quando la lente viene misurata (:live e 'R' :refined; la sessione
   ricorda `:grab-camera` anche a camera chiusa, persistito in
   acquire-state.json); si ripropone all'apertura della camera come
   `:focal-source :remembered` (dentro own-lens-sources: un singolo grab non
   la scavalca, riporta solo la divergenza — che è il sintomo di Center
   Stage). NOTA: battiscopa3 è precedente alla chiave — il 28.08 entra in
   memoria alla prossima sessione live che rifà 'R', o a mano nel JSON.
3. **Anomalia del testimone su foto 2** — SPIEGATA AL BANCO E CURATA 2/9
   (chat successiva). La sonda nuova `CAGE_AUTO_WITNESS=<n>` (posa salvata,
   per faccia: ogni slot col suo candidato libero e CHI è quel candidato) ha
   nominato i colpevoli: l'indice vero di X stava ESATTAMENTE al suo posto
   (`fwd k0 a 1.4px` = zero-xp), il «rev k2» era un candidato a [673 495]
   SENZA NESSUN mark entro 30px (riflesso/stick — terzo sospetto confermato);
   idem lo «Z rev k3» ([897 562]), e l'indice vero di Z non era proprio tra i
   dischetti rilevati. La malattia era a valle: il testimone riportava TUTTI
   gli slot vicini a un candidato libero, e voto + ATTENZIONE contavano ogni
   hit — la spazzatura votava contro una lettura onesta. Cura: un anello = UN
   indice = UNA lettura (index-witness piega `:obs` al hit più nitido per
   faccia, il rapporto completo resta in `:faces` per il banco), e ogni
   lettura porta `:zero-d` così quando contraddice A POSTO NOMINALE VUOTO il
   messaggio lo dice coi numeri («l'indice vero è coperto o non rilevato,
   pesa il terzo sospetto»). Migliora gratis anche m-check e auto-read: uno
   stray non può più uccidere una posa onesta.
4. **Il seme del gizmo al ramo 'a'** — FATTO 2/9 (chat successiva), e il
   banco ha corretto il piano: come solo GATE l'occhio misurava ZERO su
   battiscopa3 (0/5 con e senza pettine — quei frame muoiono a IDENTIFY,
   non c'è niente da filtrare). Il colpo grosso vero è l'occhio come SEME
   DIRETTO: assign mutuo sotto la posa a occhio → solve → ICP (60→26px),
   niente ellissi. Misurato (occhio simulato a 17-30px di riproiezione,
   com'è un allineamento a mano sull'immagine): **4/5 registrate in
   ~200-280ms** (camera 0.7-4.2mm dalla verità, rms 1.7-4.1) contro lo 0/5
   in 17-36 SECONDI; zero falsi positivi a ogni deviazione provata (3/6/10°).
   Barre dedicate: `eye-accept-rms-px` 8 (il seme non porta prove d'identità,
   paga in qualità del fit — il degenerato a rms 11.8 con camera a 32mm
   muore lì), ≥8 corr, ≥2 anelli, guardia fisica, testimone, gate camera
   (`eye-gate-frac` 0.4). In più: filtro-facce dall'occhio (il gemello
   attraverso-plastica non si tenta nemmeno) e pettine acceso anche a cold
   start quando c'è l'occhio. Editor: i commit del gizmo stampano
   `:eye-posed` (persistito), 'a' lo passa; foto 3 rifiuta onesta (un solo
   anello rilevato — i suoi pick sono la spazzatura nota). Sonde nuove:
   `CAGE_AUTO_EYE=<deg>` (auto-read con occhio simulato),
   `CAGE_AUTO_EYESEED=<deg>` (l'ICP spogliato, per round).
5. I difetti aperti storici: `max-outliers` fisso a 2 (oggi era la focale, ma
   il difetto resta), accettazione senza asticella, rinomina prima del solve,
   riconciliazione di sessione, derivazione live durante il drag (probabilmente
   non serve: al commit basta, collaudato).

**TRAPPOLE OPERATIVE per la chat nuova.** Le solite due dell'handover (MAI
compile `:app` a mano, MAI compile via clj-nrepl-eval) più: le sessioni nREPL
di questa chat sono rimaste incastrate due volte — ripartire col ciclo pulito
della memoria REPL; il collaudo UI si fa con Playwright su `localhost:9000`
(pattern in questa chat: swap del session atom + chiamata diretta delle fn
private, ripristino nel finally); Center Stage («Inquadratura automatica»)
VA SPENTO nelle sessioni di grab, rende la lente variabile per costruzione.

---

## LA LENTE DEI GRAB NON È 44: È ~30 (2/9, sweep per-foto al banco)

Vincenzo sospettava una focale DIVERSA PER FOTO (Center Stage). Lo sweep
per-foto (set intero, senza scarti, gabbia flip-aware) dice: ottimi a
**f30 (foto 1, rms 3.6!), ~29 (foto 2, 8.6), ~29 (foto 4, 12.2), 34
(foto 5, 9.6)** — un grappolo attorno a 30, e NESSUNA foto vuole 44. La
percezione «sulla 1 la 44 è quasi giusta» era la posa che compensava (a 44 la
foto 1 sta a 11.1; rifittata a 30 crolla a 3.6). Il 44 viene da un'altra
filiera (le tarature erano legate alle foto 4032px); i grab 1920×1440
viaggiano sull'1x dell'iPhone (26mm equiv) più il ritaglio di Center Stage →
~29-34. La variazione residua per-foto (29↔34) può essere davvero Center
Stage: se dopo aver dichiarato 30 una foto resta alta, la focale per-foto
diventa la feature da discutere — per ora il grosso era UN numero sbagliato
condiviso. FOTO 3 (grab-04): i pick salvati sono spazzatura a QUALUNQUE
focale (86-91px) — residui di giri falliti, vanno azzerati prima di 'R'
(sospetto che siano loro, o la 44, dietro l'esplosione 12.9→88 dell'ultima
rifinitura). Piano dato a Vincenzo: focale a mano 30 → 'r' su 1/2/4/5 →
Azzera+riclick pulito su 3 → 'R' per la rifinitura fine → il numero che esce
è LA LENTE DEI GRAB, da annotare (memoria per-camera ancora in coda). Fisica
per il futuro: spegnere «Inquadratura automatica» (Center Stage) nelle
sessioni di grab — rende la lente variabile per costruzione.

## LA FOTO 4 A 13 E IL SOSPETTO FOCALE (2/9 mattina) — misure al banco

Vincenzo: «la 4 non riesco a portarla sotto il 13». Tre cose fatte e una
misura che indirizza la prossima mossa:

1. **`declared-cage-mounting` si armava solo con `:phases`** — col form vero
   (`:flips`, zero fasi) l'arbitro del gemello sarebbe rimasto in cold start.
   Ora si arma anche coi flip, e l'atteso resta `:fwd k0` per TUTTI gli anelli
   (gli alloggi vengono dagli anchor, che portano il flip: una gabbia
   dichiarata giusta legge se stessa come nominale).
2. **Il messaggio «l'indice contraddice» ora mostra la PROVA**: «Z visto rev
   k11 a 4.2px invece di fwd k0» — e aggiunge il terzo sospetto (candidato del
   detector scambiato per indice: riflesso, stick). Il prossimo log di
   Vincenzo dirà cosa vede davvero il testimone — le contraddizioni ripetute
   su Z (foto 2 e 4) e X (foto 5) restano DA SPIEGARE, ora con i numeri.
3. **Il banco era flip-cieco**: ogni sonda costruiva il proxy dalle sole fasi
   (`declared-flips` ora legge l'impronta o `CAGE_AUTO_FLIPS`). Prima di
   questa correzione qualunque sonda su battiscopa3 misurava contro la gabbia
   sbagliata.

**LA MISURA (sonda `CAGE_AUTO_FIT`, gabbia flip-aware, copia in scratchpad):**
foto 4: il set intero a f44 sta a 20.6 e scende solo a 11.4 dopo QUATTRO
scarti — errore sparso, non un colpevole (il peggiore è però un click A MANO:
`xm10` a 56.6px, da togliere con la gomma). E la focale spinge, su TUTTE:
foto 4 (n=15) f44→11.4, f40→10.8, f38→9.2, f36→8.3, f32→6.0; foto 2 (n=22)
f44→8.8, f38→6.7; foto 5 (n=15) f44→7.4, f41→6.9, f38→6.9. Tutte e tre
migliorano sotto 44; l'ottimo per-foto è mal condizionato (scivola con la
distanza camera), quindi il giudice è la rifinitura CONGIUNTA 'R' — rifiutata
ieri perché partiva con la foto 4 avvelenata. Sospetto di fondo, dalla
memoria del progetto: le tarature della focale erano legate alle foto da
4032px — la 44 «misurata» per Continuity potrebbe non valere per i GRAB
1920×1440 (altro crop). Piano: gomma su xm10 → 'r' su foto 4 → 'R' e lasciare
che sia lei a dire la lente (attesa: sotto 44, zona 38-41).

## LA GABBIA VERA DI battiscopa3 (2/9) — `:flips #{:y :z}`, NESSUNA fase

Fine della caccia: **`(registration-cage :d 176 :flips #{:y :z})`**. Due anelli
su tre incollati ribaltati, zero fasi. Verifica di Vincenzo: «i doppi pallini
sono a posto», foto 4 registra a 11px, foto 1 (grab-01) a **11.08px, fit
pulito** col DLT. Le `:phases {:y 180 :x 180}` dell'handover — «verificate tre
volte» — erano entrambe false: il 180 di Y era il numero con cui il modello
SENZA `:flips` assorbiva un ribaltamento, il 180 di X era falso e basta.
Perché sono sopravvissute tanto: `crown-misreadings` include rot 6 (=180°),
quindi la rietichettatura le assorbiva foto per foto lasciando residui buoni —
**una fase "verificata" da un rms basso non è verificata**. L'arbitro vero,
disponibile solo da ieri, è il doppio pallino disegnato sopra la foto.

CONSEGUENZA DA NON DIMENTICARE: tutte le registrazioni precedenti di questa
sessione sono state fatte contro una gabbia SBAGLIATA — vanno rifatte, e la
rifinitura della focale ('R', la 44 è ancora "a mano") va rifatta DOPO, su
foto sane.

## ATTERRAGGIO — 31/8 sera, leggi PRIMA questo

**AGGIORNAMENTO 1/9 sera, secondo giro: 'v' in 'p' ora spoglia TUTTO il
modello.** Il primo taglio nascondeva wireframe+alette ma lasciava i punti
PREDETTI (coi nomi accesi ≈40 pallini = tre corone che «sono la gabbia») e le
etichette dei nomi (DOM). Regola chiusa: 'v' in 'p' = foto nuda + i CLICK
PIAZZATI dell'utente (overlay DOM, dati suoi); tutto ciò che è predetto dal
modello — wireframe, alette, punti, nomi — va giù insieme (`draw-mark-names!`
ha la guardia :pnp+hide, `toggle-proxy!` :pnp ridisegna anche l'overlay).
**CONFERMATO DA VINCENZO (1/9 notte): le DUE fasi erano sbagliate.** Tolte
`:phases {:y 180 :x 180}` (X e Y a 0, Y resta `:flips #{:y}`) la sessione
quadra e la foto 4 si registra — a rms 21.3, con la diagnosi «l'errore è
sparso su tutti» e l'indice dell'anello Z che contraddice la dichiarazione.
Storia: il 180 di Y era il valore che la vecchia macchina, senza `:flips`,
usava per assorbire un RIBALTAMENTO; il 180 di X era falso e nessun
allineamento poteva smentirlo (vedi sotto). Conseguenze CABLATE stanotte:
(a) i messaggi del montaggio non mandano più a RISTAMPARE — dicono la forma
da incollare, costruita su misura dalla gabbia corrente
(`flips-suggestion`, additiva: aggiunge l'asse ai flip già dichiarati e
conserva le fasi); (b) il messaggio «l'indice contraddice la DICHIARAZIONE»
non accusa più solo la registrazione: ora elenca DUE sospetti, il gemello e
la dichiarazione stessa — il 1/9 ha provato che una dichiarazione sbagliata
non è ipotetica; (c) BUG MIO CORRETTO: l'impronta della gabbia che valida il
voto del montaggio non conteneva `:flips` — cioè proprio la cosa che il voto
misura (legge l'ALLOGGIO dell'indice, che il flip specchia). Dichiarare un
flip senza toccare le fasi NON invalidava il voto, e la sessione di Vincenzo
ha portato osservazioni misurate prima del flip. Ora `cage-flips-tag` (nomi
ordinati, nil se vuoto → sopravvive al JSON e non scarta i voti delle
sessioni senza flip; verificato il round-trip). PROSSIMA IPOTESI DA PROVARE
CON LUI per il rms 21: l'indice di Z dice specchiato → `:flips #{:y :z}`; e
leggere il rapporto «fase degli anelli (da questa foto)» che il pannello
stampa nel REPL a ogni solve accettato (misura leave-one-ring-out).

**LA FASE DELL'ANELLO X NON HA TESTIMONI OLTRE LA CORONA (1/9 notte) — da
verificare con Vincenzo, possibile errore di DICHIARAZIONE vecchio.** Lui, foto
4: la gabbia virtuale combacia (alette e porta-stick al posto giusto) ma Xm0 è
sfasato di 180°. Misurato: con `:phases {:x 180}` il modello mette xm00 a 195°
invece che a 15° — cioè esattamente dove sta xm06 nel nominale (6 dischetti).
E NIENTE di ciò con cui lui allinea può contraddirlo: le feritoie stanno a
60°/240°, quindi una mezza rotazione mappa la coppia su se stessa (verificato:
slots identici a 180°, spostati a 150/330 con 90°); l'anello X non possiede
linguette proprie (`joint-tabs`: 4 sulla Z, 2 sulla Y, 0 sulla X — la X ha solo
la TACCA della chiave). Quindi «alette e slot combaciano MA i mark sono a 180°»
non è una contraddizione: è la firma esatta di una fase X sbagliata di mezzo
giro, che nessun allineamento poteva smentire e che la rietichettatura
(`crown-misreadings` include rot 6) può aver assorbito in silenzio foto per
foto. ARGOMENTO FISICO da usare con lui: se la sua gabbia HA la chiave
(spina+tacca, gabbie stampate dopo il 24/8) allora la rotazione della X è
imposta dal montaggio e `:phases {:x 180}` NON può essere giusta; se la gabbia
è anteriore alla chiave, la X è libera e decide la stampa. Corretto intanto un
difetto che questo ha fatto emergere: la FASE ora ruota anche le FERITOIE
(prima no: erano «dati di fabbricazione», ma ora si DISEGNANO come riferimento
d'allineamento, e a 90° si sarebbe allineato su una bugia). Firma di stampa
invariata (7408 vertici). I mark (celesti + doppi pallini arancio) ora si
disegnano anche in 'p' — mancavano, ed è lì che servono per contare.

ALT ERA GIÀ PRESO (1/9 notte, regressione mia trovata da Vincenzo): Alt+click
significa «prendi il click ALLA LETTERA, niente aggancio automatico» da quando
esiste il piatto (`click-pixel`) — la sbirciatina gliel'ha rubato la sera
stessa. Regola sua, adottata alla lettera: gabbia VISIBILE → Alt rotola la
gabbia; gabbia NASCOSTA ('v') → Alt torna a essere il click letterale, e la
sbirciatina NON riaccende la gabbia da sola. Si divide pulito perché ciascun
gesto è inutile nello stato dell'altro. Corretto anche il consiglio del
pannello («riclicca tenendo ALT»), che con la gabbia accesa mandava a rotolare
la gabbia: ora premette «prima premi 'v'». Verificato nei due stati.

LA GUARDIA ORA SUGGERISCE (1/9 notte). Vincenzo, foto 4: «vengono aggiornati
Xm/Xp e Zm/Zp ma non Ym/Yp, restano deselezionati entrambi». È la guardia del
profilo che funziona (l'anello Y di grab-05 è il caso 13–17° per cui è stata
scritta) — ma lasciava due bottoni spenti e nessun indizio. `cage-faces-from-
pose` ora riporta anche `:geo-sign`, la lettura geometrica SENZA guardia:
`:sign` è ciò che si può DICHIARARE (muove i pick, deve essere sicuro),
`:geo-sign` è ciò che si può SUGGERIRE. Pannello e messaggio ora dicono «Y è
quasi di taglio (15°): non la dichiaro io — direbbe Ym, premilo tu se lo
confermi». Il suggerimento rispetta i `:flips`. Misurato live: X a 14° e Z a
17° taciuti ma suggeriti, Y a 68° dichiarato.

IL PROXY IN 'p' È SOLIDO (1/9 notte). Il wireframe c'era e si vedeva (provato
a schermo: cerchi grigi sottili) ma Vincenzo vedeva alette e box solidi
galleggiare senza anelli in mezzo, e la sua richiesta è stata netta: «in
wireframe si vedono, devono essere PIENI». Il motivo storico del wireframe —
la foto deve restare cliccabile sotto — è caduto lo stesso giorno per due
strade: 'v' ora funziona in 'p', e la LENTE ingrandisce i pixel della FOTO,
non il render, quindi un dischetto si mira anche col modello sopra. Verificato
inoltre che `backdrop/pixel-under-pointer` fa `intersectObject` sul SOLO piano
della foto: un solido davanti non intercetta i click, il picking è intatto.
Stato 'v' in 'p' ora: acceso = gabbia piena + alette + box + punti predetti +
nomi; spento = foto nuda + i soli click piazzati.

DOPO IL COLLAUDO DELLA SBIRCIATINA (1/9 sera): (a) il peek disegnava il
WIREFRAME (eredità del picking, dove è trasparente APPOSTA per cliccarci
sotto) — ora disegna la gabbia PIENA, che per giunta OCCLUDE, e l'occlusione è
essa stessa la lettura (un mark dietro un anello è nascosto anche sulla
stampa, da quel lato). (b) I BOX PORTA-STICK ora si disegnano davvero —
scatole solide grigio-azzurre con la BOCCA ELLITTICA del canale alle due
estremità («box forati»), non più losanghe piatte: richiesta di Vincenzo,
«anche loro sono elementi chirali riconoscibili», e ha ragione due volte
perché il corpo sale da UNA faccia sola, quindi è un altro tratto che il
gemello non sa riprodurre. (c) Le MISURE degli slot (body-w 8, body-len 14,
body-h 9, body-lift 3, channel-lift 2.5, channel-r [2 2.2]) erano l'ultimo
pezzo di gabbia descritto FUORI dal proxy: vivevano nella libreria
`acquire-cage`. Spostate in `cage/stick-slots` (che ora prende anche `h`) e la
libreria le legge da lì — tre consumatori (stampa, anello piatto, disegno) di
un numero solo. PROVA CHE CONTA: la firma geometrica dell'anello stampato è
IDENTICA prima e dopo (⌀176 :big → 2 mesh, 7408 vertici, 14720 facce, bbox
z −1.5…7.5), misurata valutando la libreria vera nel browser.

SBIRCIATINA Alt+drag COSTRUITA (variante scelta da Vincenzo: «molto più
chiaro», niente maniglie sopra i dischetti): in 'p', Alt+trascina rotola la
gabbia virtuale come una trackball (0.4°/px, camera-right/up come assi) e
mostra wireframe + alette + TUTTI i puntini dei mark (culling sulla copia
ruotata: le facce che rotolano verso la camera svelano le corone); al
rilascio, dopo 1.2s (`peek-return-ms`), torna da sola. NIENTE è mai
committato: il preview è una COPIA ruotata (attachment/rotate-mesh), la posa
di sessione non si tocca per costruzione, lo snap-back è un redraw ritardato.
Pointer capture al via (il rilascio fuori-canvas si sente), un nuovo Alt+drag
nella finestra riparte da dov'è, stop-pnp!/navigazione uccidono timer e
angoli. Verificato headless (angoli, item, vertice ruotato, mesh intatta,
timer). Da collaudare dal vivo: il SEGNO dei drag (trackball «palla sotto la
mano»: destra = la faccia vicina va a destra) — se a Vincenzo pare invertito,
sono due segni in pnp-move-peek!.

**AGGIORNAMENTO 1/9 sera: 'v' in PnP + facce lette all'INGRESSO di 'p'.**
Collaudo di Vincenzo: (1) in modalità 'p' le alette solide restavano FISSE
('v' era cablato solo in :gizmo/:retrace — giusto quando il proxy in 'p' era
solo wireframe trasparente, rotto dalle alette solide che coprono i dischetti
da cliccare) → 'v' ora attivo anche in :pnp (`toggle-proxy!` ha il ramo :pnp
senza toccare il gizmo che lì non esiste; il preview PnP nasconde wireframe+
feature ma MAI i punti di picking). (2) Chiedeva se i sei bottoni si settano
da soli: sì, a ogni RILASCIO del gizmo (verificato live: choice {:x 1 :y -1
:z 1} su gabbia ribaltata + messaggio) — ma una foto mai trascinata (il seme
del giradischi basta spesso) non li riceveva MAI → ora `start-pnp!` deriva
anche all'INGRESSO di 'p', solo se la foto non ha già una scelta, SENZA
reface (il reface resta legato ai commit, dove la posa è esplicitamente sua).
Warning 16 invariati.

**AGGIORNAMENTO 1/9: `:flips` — il montaggio ribaltato si DICHIARA, non si
ristampa.** Col disegno delle alette Vincenzo ha visto che la gabbia virtuale
non combacia con la stampata: è l'anello Y montato RIBALTATO (che la leva 2
aveva già scoperto dagli alloggi specchiati; test fisico 28/8). Costruito:
`registration-cage :flips #{:y}` — il flip è una ROTAZIONE PROPRIA di 180°
attorno a un diametro dell'anello (`cage/flip-in-ring`, niente specchi: un
anello fisico non si specchia), poi il giro di fase misurato (`mount-anchor`,
ordine flip→fase = ordine del montaggio); gli id restano ETICHETTE DI STAMPA
(yp guarda −y da ribaltato) perché la chiralità del passetto è della stampa e
il montaggio non la cambia — è `bridge/cage-faces-from-pose` che inverte il
segno sull'anello dichiarato (`:cage-flips` sulla mesh); alette e feritoie
dell'anello seguono il flip (la gabbia DISEGNATA = quella INCOLLATA), la fase
continua a non muoverle (esatto per multipli di 180°). ATTENZIONE: dichiarare
il flip CAMBIA cosa misura la fase di quell'anello — il {:y 180} storico era
fittato senza flip e va RIMISURATO. 5 test nuovi (chiralità invariante =
nessuno specchio accidentale; alloggio specchiato = firma leva 2; ordine
flip→fase; feature che seguono; segno flip-aware con guardia), suite 1004/0,
warning 16 invariati, smoke live ok. Manuale: sezione «The ring glued turned
over» in registration-cage.md, indice rigenerato. Gate live: Vincenzo deve
dichiarare `:flips #{:y}` nel suo sorgente e ricollaudare fase Y a vista sui
doppi pallini.

**AGGIORNAMENTO 31/8 notte: la fetta è COSTRUITA, manca il gate live.**
Risposte di Vincenzo alla domanda di design: manipola la GABBIA (metafora
oggetto-in-mano — che è già come si comporta il gizmo, live drag sul proxy e
inversione sulla camera solo al commit, quindi nessun cambio di gesto); e la
gabbia virtuale deve mostrarsi COME STAMPATA (alette, spina, feritoie) perché
sono ciò con cui lui riconosce la posa quando gli zero-indice non si vedono —
e sono anche ciò che uccide il gemello nell'appaiamento a occhio (le alette
stanno su UNA faccia sola, la spina è asimmetrica). Costruito:
`bridge/cage-faces-from-pose` (pura, guardia `cage-face-margin-deg` 20°,
riporta i gradi anche quando non sentenzia — 4 deftests in bridge_test);
`derive-faces-from-pose!` cablata in ENTRAMBI i commit del gizmo (riscrive
`:cage-face-choice idx` e porta i pick con `reface-picks-to-declaration!`,
override manuale = ultimo atto umano vince fino al prossimo commit);
`cage-feature-items` (alette+fermi VERDI e spina ROSSA come SOLIDI
semitrasparenti con spigoli sopra — il primo taglio era solo-spigoli e
Vincenzo non li leggeva: «si vedono, ma in wireframe… dovrebbero essere più
evidenti»; semitrasparenti perché l'aletta disegnata si appaia a quella
FOTOGRAFATA, la foto deve restare leggibile sotto; feritoie grigio-azzurre a
losanga, solo spigoli: sono aperture — da `:tabs`/`:stick-slots`; la
key-notch NON si disegna, è un taglio; gli stick nemmeno, il modello non sa
quali sono infilati) nei preview gizmo e PnP; `cage-marks-item` (1/9, due
giri): TUTTI i mark delle facce frontali sul proxy virtuale — corone azzurre
complete («dalla foto è difficile stabilire che numero è un certo pallino: sul
virtuale si contano guardando dietro gli ostacoli») + i DOPPI PALLINI arancio
(mark 0 grande 1.8 + zero-indice piccolo 1.1), culled per faccia sulla camera
corrente, e SPARISCONO col proxy ('v') — i puntini sono parte della gabbia
virtuale, non un overlay sulla foto (deciso da Vincenzo, ribaltando il primo
taglio che li teneva sempre accesi; il problema-coperta del 27/8 era il
picking, che tiene i suoi punti radi). Su gabbia `plate-crown-item` ora non
disegna niente (il suo dump di TUTTE le ancore mostrava entrambe le facce
attraverso la plastica); sul piatto invariato. Collaudo di Vincenzo su alette
e flip: «ora è a posto»; la spina rossa non la vede (mezza sepolta tra aletta
e anello grande — non essenziale, detto suo);
nota gialla nel pannello per gli assi sotto guardia, coi gradi. Verificato:
suite 999/0, warning `:app` 16 invariati (letti dalla UI shadow su :9630),
smoke live nel browser (camera obliqua → Xp a 76.5°, Y a 13.5° → nil: il caso
grab-05 ora tace). NON committato, NON collaudato da Vincenzo su battiscopa3.
In coda restano: derivazione live durante il drag (oggi solo al commit — scelta
del file, «probabilmente basta»), punto 5 (posa a occhio come SEME del ramo
`a`), e i due giri di nREPL incastrati da un mio compile via clj-nrepl-eval
(la trappola scritta qui sotto: al prossimo giro, ripartire con la sequenza
della memoria REPL).

**LA DECISIONE DI VINCENZO (31/8, dopo tredici giri live): basta prove coi
toggle a mano, si costruisce la GABBIA VIRTUALE COL GIZMO.** Parole sue: «mi
sono un po' stufato di continuare a fare prove, mi sembra che giriamo in tondo.
Partirei con l'implementazione della gabbia virtuale col gizmo e il valore dei
tre toggle Xm/p Ym/p Zm/p lo deriverei dalla posizione della gabbia». È la
fetta che questo stesso file aveva in coda come «la fetta grossa», e la
settimana le ha dato ragione nel modo più caro: su TRE foto di fila (grab-04,
grab-05, grab-06) una o due facce dichiarate a mano erano sbagliate, ogni
errore di faccia avvelena tutto il valle (solve, soccorsi, diagnosi), e il
dibattito foto-per-foto non converge. Il suo flusso fisico reale — «prendo in
mano la gabbia e la appaio alla foto» — va virtualizzato: orienti la gabbia a
occhio finché combacia, e le facce non si DICHIARANO più, si LEGGONO dalla posa.

**Sessione di lavoro**: `~/Pictures/RidleyScan/battiscopa3` (5 grab 1920×1440,
lente 44 A MANO, gabbia `(registration-cage :d 176 :phases {:y 180 :x 180})`).
Stato foto: 1 registrata (8.3px sui soli click); 2 registrata ma
SOSPETTA-GEMELLO (11.9px, X/Z letti specchiati su gabbia incollata); 3
(grab-04) le facce vere sono Xp Ym Zm, da rifare; 4 (grab-05) CONTESA su Z
(sotto); 5 (grab-06) facce vere Xp Ym Zp, da rifare. La rifinitura `R` va
rifatta SOLO a foto sane: l'ultima è stata rifiutata (10.9 → 47px) perché
misurava la lente su una posa avvelenata.

### La fetta: come costruirla (impianto già esistente, nominato)

1. **Il gesto c'è già.** `install-gizmo!` (edit_acquire, ~1042) apre il gizmo
   su OGNI foto: su foto 0 i commit muovono il PROXY (`on-photo0-commit!`),
   sulle altre si invertono sulla CAMERA (`on-inv-commit!`). La wireframe del
   proxy è già disegnata sopra la foto. "Appaiare a occhio" = orbitare finché
   combacia: meccanicamente esiste, oggi il commit muove solo la posa e non
   dice niente alle facce.
2. **La derivazione delle facce è una funzione pura che esiste quasi tutta.**
   `bridge/pnp-target-points` calcola già `:visible?` per-mark dal segno di
   `(dot heading (- cam-pos world))` (culling della gabbia). La faccia di un
   anello sotto una posa = il segno il cui indice/normale guarda la camera.
   Da scrivere: `faces-from-pose` (per ciascun asse → 1/-1/nil) e il cablaggio
   nei commit del gizmo: dopo ogni commit si ricalcola `:cage-face-choice idx`
   e si passa da **`reface-picks-to-declaration!`** (già costruita, 31/8: porta
   i click GIÀ FATTI sulla faccia derivata, pixel fermi, e toglie i doppioni
   dicendolo — senza questa il derivato lascerebbe pick a due facce, il bug
   pagato su grab-04).
3. **GUARDIA DEL PROFILO, obbligatoria**: un anello quasi di taglio non
   dichiara la faccia. Su grab-05 la faccia Y era decisa da **13–17°** e quella
   sentenza ha retto mezza giornata di diagnosi sbagliate; sotto ~20° di
   margine l'asse resta nil (culling per-mark della posa, nessuna
   dichiarazione) e il pannello lo dice («Y è quasi di profilo: da questa
   angolazione la faccia non si legge»).
4. **I tre bottoni RESTANO**, come display del derivato e override: la regola
   «mi fido dei tuoi occhi, non della posa» resta vera — ma ora la posa è la
   SUA, fatta a occhio, quindi il conflitto dovrebbe sparire nel caso normale.
5. **La posa a occhio è anche un SEME**: `pnp/solve-pnp` accetta già `:seed`
   («e.g. the gizmo pose» — è nel docstring) e la catena di soccorso già
   riprova `:method :seeded` dalla posa a schermo. Il colpo grosso in coda:
   passarla al ramo `a` (auto-read) come gate delle ipotesi — un seme umano
   grossolano vale più di quattro click (cold start).
6. **Domanda di design da fare a Vincenzo PRIMA di scrivere**: vuole
   trascinare la GABBIA (come tiene in mano la stampa) o la camera? Sulle foto
   ≥1 il commit si inverte comunque sulla camera — è solo questione di quale
   metafora mostrano le maniglie. E: derivazione live durante il drag o al
   commit? (Il redraw live della wireframe c'è già; la derivazione al commit è
   più semplice e probabilmente basta.)

### I QUATTRO fatti di dominio, verificati (dimenticarne uno = codice sbagliato, è successo)

1. **La gabbia è INCOLLATA** (attack). Il montaggio è una costante fisica come
   `:d`; su gabbia DICHIARATA un indice specchiato/girato accusa la
   REGISTRAZIONE, mai la gabbia.
2. **Le fasi `{:y 180 :x 180}` sono vere** (verificate tre volte). Dichiarate
   → tutti gli anelli nominali.
3. **La lente è 44mm** (iPhone Continuity), MAI l'EXIF (i grab non ce l'hanno);
   il default 48 ha avvelenato tre serate. Gate `cage-obs-focal-ok?` attivo.
4. **La regola del passetto è GIUSTA e il modello la rispetta** (nuovo, 31/8,
   sonda `CAGE_AUTO_CHIR`): dal dischetto grande al pallino piccolo,
   ANTIORARIO in pixel = faccia p, ORARIO = m, identico sui tre anelli.
   Il metodo di lettura di Vincenzo sulla stampa NON è invertito.

### La CONTESA APERTA su grab-05 (foto 4), lasciata onesta

Il banco dice Zm (test delle normali, margine 44–48°); Vincenzo legge Zp sulla
stampa. La sonda della predizione (`CAGE_AUTO_PREDICT`: posa dai soli 8 click a
1px dai dischetti rilevati, NESSUN pick Z) mette i suoi click Z più vicini alle
posizioni **zp** che alle zm su tutti e tre i mark (9/14/8px contro 22/25/16) —
ma la stessa posa poggia sull'anello Y deciso da 13–17°, e il detector su
questa foto non vede NIENTE sull'anello Z. Le due prove si contraddicono e la
foto non le concilia. Regola in vigore: si segue la STAMPA (lui), non il banco.
La gabbia orientata a occhio dovrebbe risolverla di passaggio; l'arbitro di
fondo resta la riconciliazione di sessione (in coda).

### Difetti aperti, nominati (in ordine di rendimento)

- **Memoria per-camera della focale** (`~/.ridley/`): la 44 misurata due volte,
  ogni sessione nuova riparte da 48. La trappola che ha morso più di tutto.
- **`max-outliers` 2 tarato su set piccoli**: su 13+ pick il pulitore si ferma
  a 14px quando la verità sta a 6.8 quattro scarti più in là. Non toccato
  (prove di una foto sola); con la gabbia a occhio i set cresceranno e il
  difetto morderà di più.
- **Accettazione senza asticella**: il retry senza outlier persiste QUALUNQUE
  rms (72px visti). La bugia è tolta (niente ✓ né colpevoli sopra l'asticella)
  ma la posa selvaggia si persiste ancora.
- **Rinomina prima del solve**: `relabel-picks!` rinomina PRIMA e un solve
  rifiutato lascia i nomi del gemello sui pick (rollback attraverso il confine
  asincrono di `on-solve-pnp!`).
- **Seme congiunto** (misurato 31/8, non cablato): `cage-relabel-rescue` semina
  solo dall'anello d'ancoraggio (2 letture, rot 0) e serve un anello con ≥4
  pick; una posa seminata da TUTTI i pick con un anello riletto ha trovato
  12.2px dove la produzione stava a 120 (grab-06, secondo classificato 29.3, e
  su grab-01/04 non sposta le risposte giuste). Sonda `CAGE_AUTO_JOINT`
  (+`CAGE_AUTO_SEEDALL=1`). Il seme del gizmo potrebbe renderla superflua.
- **COLD START**: chiuso per gabbie dichiarate, aperto per le altre.
- **Banco ≠ app su battiscopa2 foto 4** (campionatore luminanza): i RIFIUTI del
  banco non sono un pavimento del tasso vero.

### La settimana in tre lezioni (i dettagli nei «giri» sotto, 8°–13°)

1. **Test del CROLLO**: uno scarto è un colpevole solo se toglierlo fa crollare
   l'rms sotto l'asticella; sennò è il capro espiatorio di un fit brutto
   dappertutto (quattro coppie diverse accusate su grab-01, tutti click a 1px).
2. **Una diagnosi non può essere più forte delle prove**: `gross-pick-px` — una
   lettura che regge solo buttando un punto a 508px non ha titolo per
   giudicare una faccia. E il verdetto va dato come GESTO eseguibile («premi
   Zm al posto di Zp»), non come stato da confrontare a memoria.
3. **Il dubbio di Vincenzo ha ribaltato quattro sentenze su quattro**. Prima di
   sentenziare: SONDA — e sonda anche il TUO strumento (la sonda della
   chiralità è nata così).

### Strumenti al banco (build `:cage-auto`, tutti in `cage_auto_study.cljs`)

`CAGE_AUTO_SEED=<n>` (click → distanza dal rilevato, solve sui soli click,
rietichettatura per anello POSSIBLE-FIRST) · `CAGE_AUTO_FIT=<n>` (la traccia
onesta del pulitore; `CAGE_AUTO_FLIP=xz` prova anelli sull'altra faccia) ·
`CAGE_AUTO_FACE=<n>` (le 8 dichiarazioni col test fisico e i GRADI di margine)
· `CAGE_AUTO_JOINT=<n>` (ricerca congiunta) · `CAGE_AUTO_CHIR=1` (chiralità
del modello in pixel) · `CAGE_AUTO_PREDICT=<n>` + `CAGE_AUTO_ASK=id,id`
(predice mark da pick fidati) · `CAGE_AUTO_PICKS_FILE=<json>` (set dal log:
i rifiuti non salvano) · `CAGE_AUTO_PHASES` / `CAGE_AUTO_FOCAL` /
`CAGE_AUTO_DIR` / `CAGE_AUTO_ZERO` / `CAGE_AUTO_NOCTX` / `CAGE_AUTO_CTXONLY`.
Compilare col CLI (`npx shadow-cljs compile cage-auto`), MAI `:app` — e MAI
compile via nREPL con clj-nrepl-eval (il timeout del client incastra la
sessione; la memoria `feedback_shadow_cljs_concurrent_compile` ha il dettaglio).

**Come lavorare con Vincenzo**: i suoi log incollati sono lo strumento di
misura principale; il suo dubbio va preso sul serio (4/4 questa settimana);
i messaggi devono nominare il GESTO, mai chiedergli un diff a memoria.

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
