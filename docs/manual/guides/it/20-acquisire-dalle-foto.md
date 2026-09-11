<!--
Capitolo 20 (riscritto il 2026-09-06 da Claude-docs; sostituisce la bozza
"19-acquisire-dalle-foto.md" del 2026-07-30, che documentava il flusso
piatto+giradischi). Convenzioni:
- Prosa in italiano, codice esempi in inglese. No em-dash (come cap. 18).
- Decisioni di Vincenzo (2026-09-06): si documenta SOLO la gabbia (il piatto di
  registrazione resta nel codice e nella reference, non nella guida); entrano i
  controlli di posa e i badge della pellicola; entra lo scatto dal vivo (Grab);
  il ricalco passa dagli ancoraggi del tasto 'd' in edit-acquire e da
  (turtle A :at :ancora-1 (edit-path-2d)) sul palcoscenico.
- Il numero 19 resta riservato a "Estendere Ridley" (mai scritto): questo è il 20.
- Etichette dei bottoni riportate ESATTE dal codice (edit_acquire.cljs e
  acquire_stage.cljs del 2026-09-05): il pannello mescola italiano e inglese
  ("Registra per punti (p)", "Add anchor (d)", "Hide cage (v)", "Conferma (OK)",
  "Chiudi"); la toolbar del palcoscenico è in inglese ("Photo", "Marks", "Plane",
  "‹", "›"). Non uniformate qui: è un tema di UI per Code.
- VERIFICATI sul codice: tasti a/p/r/n/v/d/g/R/[/]/Esc su gabbia; k/m/b/F/C
  nascosti su gabbia; i tre cursori e i tre cerchi di posa (foto 0 muove la
  gabbia, le altre la camera); i quattro stati del badge; "faccia che vedi:" con
  i sei bottoni Xp..Zm; preset "Big/Medium/Small ring (1/2/3)" e slider
  "Plane offset (mm)"; nome di default "ancora-N"; gli ancoraggi emessi come
  mappe nude di posa in :marks; Grab su gabbia tenuta NON registrata; memoria
  focale ~/.ridley/cameras.json; il gesto "Plane" del palcoscenico non gatato
  sulla gabbia; [ e ] promossi sopra edit-path-2d.
- NON verificati / semplificazioni da confermare con Vincenzo o Code:
  (1) "misura col calibro e dichiara quel numero in :d" al posto di
  (measured cage 175.4) di print-cage.clj: equivalente sulle posizioni dei
  mark, ma evita di dipendere dal file di stampa dentro la form acquire;
  (2) 'R' su gabbia: il TASTO funziona, il bottone "Rifinisci insieme (R)" è
  offerto solo sul piatto (da allineare in UI); (3) (:faces A) su gabbia
  espone :ring-x/:ring-y/:ring-z, non documentato perché il segno della
  normale non è garantito; (4) ordine di [ ] sul palcoscenico con foto senza
  angolo: non angolare, probabilmente quello della pellicola; (5) ordine di
  incollaggio gen 2 preso da print-cage.clj (grandi, pezzo, piccolo): la
  reference registration-cage.md dice ancora l'ordine della gen 1 (piccolo e
  medio prima, grande per ultimo), da aggiornare.
- Verifica indipendente (2026-09-06) passata dopo le correzioni su: ordine
  incollaggio, chiave/tasche gen 2, badge ambra (= gemello o trattini, NON le
  foto escluse da R), :rim-marks? indipendente da :gen, "focale ricordata"
  all'apertura della CAMERA, Delete view a doppio click, solo jpg/png alla
  prima apertura.
- Versione EN: en/20-acquisire-dalle-foto.md (2026-09-09), da tenere in sync.
- Non citati apposta (in lavorazione o forensi): trattini sul bordo come
  meccanismo (compaiono solo come "gabbia gen 2 registra anche le foto di
  taglio"), voto del montaggio, gemello, phase-probe, acquire-union.
-->

# 20. Acquisire dalle foto

<!-- level: advanced -->

## 20.1 Fotografie come strumenti di misura

Il capitolo 18 parte da una mesh che esiste già: un STL scaricato, una scansione. Questo capitolo parte da un gradino più indietro: l'oggetto ce l'hai sulla scrivania, ma non ne hai nessun modello. La strada classica sarebbe la fotogrammetria densa: decine di foto, una nuvola di punti, una mesh pesante da riparare e ripulire, e alla fine comunque il lavoro del capitolo 18 per arrivare a un modello parametrico.

Ridley percorre una strada più diretta, che potremmo chiamare fotogrammetria parametrica: poche foto ben mirate diventano *viste misurate*, cioè sfondi con una camera calibrata alle spalle, e tu ricostruisci l'oggetto direttamente come sorgente Ridley nativo, disegnando sopra le foto con gli strumenti normali del linguaggio. Non esiste mai una nuvola di punti: il prodotto dell'acquisizione è codice, con le misure vere in millimetri.

Perché una foto sia una vista misurata, il software deve sapere da dove è stata scattata. Il trucco è fotografare l'oggetto insieme a un riferimento che il programma conosce per costruzione: la **gabbia di registrazione**, tre anelli stampati che avvolgono il pezzo. Trovare i dischetti della gabbia nella foto basta a calcolare la posa della camera, e da lì tutto quello che disegni sopra la foto è geometria 3D vera.

Il flusso ha tre momenti: preparare la gabbia e scattare, registrare le camere (dire a Ridley da dove è stata scattata ogni foto) e ricalcare la geometria sopra le viste registrate. I primi due si fanno una volta sola; il terzo è il lavoro di modellazione vero e proprio, e puoi tornarci quando vuoi.

## 20.2 La gabbia di registrazione

La gabbia è un pezzo Ridley: tre anelli piatti, di tre diametri diversi, montati ortogonali tra loro con sei linguette incollate, e il pezzo da acquisire ancorato al centro. Ogni anello porta su **entrambe le facce** una corona di dodici dischetti scuri, più un tredicesimo dischetto appena più interno, lo **zero**, che dice qual è il dischetto numero zero e da che parte si conta. Nel disegno lo zero forma un doppio pallino insieme al dischetto che gli sta accanto: è la figura che ti serve riconoscere nelle foto.

Perché tre anelli ortogonali. Un riferimento piatto, visto di taglio, non dice niente: la foto scattata dalla parte sbagliata è persa. La gabbia porta il riferimento **intorno all'oggetto**: da qualunque direzione guardi, almeno un anello ti guarda in faccia, spesso due, e i dischetti non stanno su un piano solo, quindi la posa è determinata in tutti e sei i gradi di libertà. Inoltre i dischetti stanno alla stessa profondità del pezzo, dove il dettaglio che vuoi ricalcare viene misurato, e non a un palmo di distanza. Il risultato pratico è che scatti da dove vuoi, retro e sotto compresi, in una sessione sola.

### Stamparla

Il codice che produce la gabbia è un esempio leggibile, non una libreria: `examples/print-cage.clj`. Aprilo, valutalo tutto (le definizioni da sole non producono niente), poi in fondo al file scommenta il comando che ti serve e rivaluta:

```clojure
(def diameter 176)
(def cage (registration-cage :d diameter :gen 2 :rim-marks? true))

;; (register Gabbia (make-cage-ring cage))   ; look at it assembled
;; (save-3mf cage "~/Downloads")             ; the three rings, flat, one dialog
;; (register Stick (stick cage 80))          ; a stick to hold the part
;; (register Punta (punta-tricuspide))       ; three-pointed foot for delicate parts
```

`cage` è l'unica dichiarazione del file: diametro, generazione, segmenti sul bordo. Tutto il resto (posizioni dei dischetti, linguette, sedi, chiave di montaggio) viene letto dalla stessa [registration-cage](ref:registration-cage) che poi userai nella sessione, quindi la gabbia stampata e quella che il programma cerca nelle foto non possono divergere. Lascia il file come lo trovi salvo il diametro: 176 mm è la misura collaudata, con un'apertura utile di circa 88 mm al centro, che è il pezzo più grande che ci entra. Puoi cambiarlo, ma non scendere sotto gli 85 mm: il diametro scala tutto tranne lo spessore degli anelli, e sotto quella misura le tasche dei segmenti sul bordo finiscono addosso a quelle dei dischetti.

`save-3mf` scrive tre file 3MF, un anello per file, già **coricati** nella posa di stampa, in due materiali: anello chiaro, dischetti scuri incassati a filo su entrambe le facce. Ogni anello sta all'origine del suo file, quindi importandoli tutti e tre nello slicer li trovi concentrici, uno dentro l'altro: non si stampano così. Disponili tu sul piatto, spostando ogni anello **insieme ai suoi dischetti**; se il piatto non li contiene affiancati (il grande da solo è largo quanto la gabbia), stampali in più sessioni. Stampa con **filamento opaco** (il rilevatore cerca "una macchia scura e tonda", e un riflesso lucido è esattamente il contrario) e con **brim** (un anello sottile e largo si imbarca raffreddandosi, e un anello imbarcato non è più piatto). Con `:rim-marks? true` gli anelli portano anche dodici segmenti scuri sul bordo, che condividono i layer col corpo dell'anello: servono due colori per oggetto, non un cambio colore per altezza. Stampa anche quattro o cinque stick.

### Incollarla

Sei giunzioni a sovrapposizione, incollate con un cianoacrilato qualunque. Nella gabbia di generazione 2 ogni linguetta **cade nella sua tasca di sede**, che fissa da sola l'angolo, e due chiavi (una spina e una tacca, in due punti del bordo dell'anello grande) rifiutano le rotazioni e i ribaltamenti sbagliati: se un anello non entra in sede, è girato. Niente da allineare a occhio. L'ordine è: prima i due anelli **grandi** tra loro, poi il pezzo al centro, per ultimo l'anello **piccolo**.

### Dirle quanto è venuta grande

Una stampante sbaglia la scala di qualche decimo di percento, e quell'errore non si presenta mai come errore: la registrazione riesce con residui ottimi e tutte le misure escono scalate. Per questo `:d` non ha un default. Misura col calibro il diametro esterno dell'anello grande della gabbia incollata, e quel numero è il diametro da dichiarare nella sessione:

```clojure
(registration-cage :d 175.4 :gen 2 :rim-marks? true)
```

Un numero solo basta: tre anelli stampati dalla stessa macchina condividono lo stesso fattore di scala (se sbagliasse in modo diverso sui due assi gli anelli verrebbero ovali, e si vede).

### Ancorare il pezzo

L'unico requisito è che il pezzo **non si muova rispetto alla gabbia** durante la sessione: dove sta nella gabbia non è mai un'ipotesi del software, è solo un sistema di riferimento. Gli stick entrano nelle sedi degli anelli, si spingono fino a toccare il pezzo e con un quarto di giro si bloccano da soli, senza viti. Quattro o cinque stick da anelli diversi tengono il pezzo in qualunque orientamento. Evita elastici (accumulano energia e spostano il pezzo appena giri la gabbia) e fili (tirano solo, e metà si allenta ogni volta che la ribalti). Se il pezzo si muove è un problema, e te ne accorgi: i dischetti continuano ad andare d'accordo tra le viste, i dettagli che ricalchi no.

## 20.3 La sessione fotografica

Alla gabbia serve un supporto che la tenga ferma nell'orientamento che vuoi fotografare, e che ti lasci cambiarlo senza rimontare niente. Quello collaudato è in `examples/cradle.clj`: un piedistallo, un cilindro che vi si infila, e in cima un arco con due clip che afferrano **uno** degli anelli. Scegli tu quale anello e in che punto agganciarlo, e il tutto ruota sul piedistallo: così porti davanti alla camera qualunque faccia della gabbia, retro e sotto compresi. Il diametro della gabbia è la `def` in testa al file. Con la gabbia sul supporto e il pezzo al centro, scatti da dove ti serve. Non c'è un protocollo di angoli: la gabbia viaggia col pezzo, quindi ogni foto porta con sé il proprio riferimento, e puoi fotografare da tutto intorno, compreso il retro e il sotto, in una sessione sola. Le foto buone sono quelle che servono al disegno: vicine, mirate sul dettaglio che vuoi ricalcare.

Quattro regole, e la prima è la più importante. **Una sola focale per tutta la sessione**: stessa camera, stesso obiettivo, niente zoom. La registrazione ricava una lente unica da tutte le foto insieme, e una foto scattata con un'altra focale non si presenta come un errore: registra pulita con la camera alla distanza sbagliata, e sposta le misure. Col telefono attenzione agli zoom che cambiano obiettivo da soli (0.5x, 1x, 2x) e alle funzioni che ritagliano l'inquadratura al volo: falli stare fermi. **Luce ambiente** diffusa, niente flash e niente riflessi sugli anelli. **Foto nitide**: un dischetto sfocato ha un centro incerto, e il residuo lo riflette. E **la gabbia tutta dentro l'inquadratura**: una foto con la gabbia mezza fuori a volte si recupera, ma dà al software metà dei riferimenti e a te metà degli appigli per orientarla. Per il resto la gabbia si legge da qualunque direzione: sugli anelli che ti guardano in faccia il software usa i dischetti, su quelli di taglio i segmenti scuri sul bordo. Se puoi, fa' che si veda un doppio pallino: non serve al software, serve a te, quando dovrai orientare a occhio la gabbia virtuale sopra la foto.

Le foto finiscono in una cartella, e la cartella è la sessione. Non serve nessun file di note: alla prima apertura Ridley prende tutte le immagini JPEG o PNG della cartella, in ordine di nome, e le scrive nel suo `session.json` (i file HEIC del telefono vanno convertiti prima; foto aggiunte alla cartella in seguito non entrano da sole). La focale la legge dall'EXIF della prima foto (la focale equivalente 35 mm che il telefono scrive in ogni scatto): non devi fare niente, e lo slider Focale della sessione resta come override manuale.

In alternativa alle foto si può scattare dal vivo, da una webcam o dal telefono usato come camera di sistema, dentro la sessione di registrazione: lo vediamo in 20.6.

## 20.4 Registrare le camere: edit-acquire

La registrazione ([edit-acquire](ref:edit-acquire)) si apre dichiarando la cartella e il proxy, cioè l'oggetto che il software cerca nelle foto. Scrivi la form nel pannello definizioni e valutala con Cmd+Invio (non dal REPL: la sessione riscrive proprio questa form, quindi deve trovarla nel buffer):

```clojure
(def A (edit-acquire "/Users/me/scans/valvola"
         {:proxy (registration-cage :d 175.4 :gen 2 :rim-marks? true)}))
```

Si apre una sessione modale sul viewport. Una pellicola di miniature mostra le foto, con un badge per ciascuna: **grigio** vuol dire da registrare, **verde** col residuo in pixel vuol dire registrata e usabile, verde con **bordo ambra tratteggiato** vuol dire registrata ma esclusa dalla misura della lente (il perché sta nel tooltip della miniatura). Sopra la foto corrente è disegnata la gabbia virtuale, così com'è stampata: anelli pieni, linguette, doppi pallini, anelli colorati per asse (X rosso, Y verde, Z blu). Lo scopo di tutta la sessione è far combaciare quella gabbia con la gabbia fotografata, foto per foto; quando combacia, la camera è registrata.

### Il gesto: posa a occhio, poi `a`

Sulla foto corrente hai tre **cursori a molla** ai bordi dell'immagine e tre **cerchi colorati** concentrici sulla gabbia. I cursori traslano: quello a sinistra sposta la gabbia su e giù, quello in alto a destra e a sinistra, quello in basso la avvicina o la allontana (la gabbia resta al centro e cambia taglia). Sono a molla: trascini, il pomello torna al centro, il movimento resta. I cerchi ruotano: trascinare lungo il cerchio rosso ruota la gabbia attorno al suo asse X, e così il verde e il blu, con un grado di trascinamento per un grado di rotazione, qualunque sia l'orientamento dell'anello. Non c'è un gizmo 3D: la gabbia è il gizmo di sé stessa.

Con questi controlli porti la gabbia disegnata **grosso modo** sopra quella fotografata: gli anelli sulla plastica, le linguette dalla parte giusta, i doppi pallini dove li vedi. Non serve precisione, serve che la posa sia quella giusta e non la sua gemella specchiata. Poi premi **`a`** (bottone **Auto — leggi la gabbia (a)**). Il software rileva i dischetti scuri in tutta la foto, parte dalla tua posa come seme, identifica gli anelli e risolve la camera; su una gabbia con i segmenti sul bordo legge anche quelli, e sono loro a registrare le foto in cui gli anelli sono di taglio e i dischetti si spengono. In qualche secondo, fino a mezzo minuto, la miniatura diventa verde con il suo residuo, e la gabbia disegnata si assesta sulla plastica al pixel. Guardala: se combacia, quella foto è fatta. Passi alla successiva con `]` e ripeti.

Se `a` rifiuta, lo dice e dice perché: di solito la foto mostra un anello solo, oppure la tua posa era troppo lontana da quella vera. Riavvicina la posa e ripremi, oppure passa alla via manuale.

### La via manuale: `p`

Per registrare a mano serve sapere come si chiamano i dischetti. I tre anelli si chiamano **X**, **Y** e **Z** (il grande, il medio, il piccolo), e ogni anello ha due facce, **p** e **m**, una per lato. Su ogni faccia ci sono dodici dischetti numerati da **00** a **11**: lo 00 è quello col secondo pallino accanto, più interno, e il pallino sta dalla parte del dischetto 01, quindi indica anche in che verso si conta. Il nome di un dischetto mette insieme le tre cose: `xm00` è il dischetto 0 della faccia m dell'anello X, `zp07` il dischetto 7 della faccia p dell'anello Z. Il doppio pallino ha un nome suo, `⊙xm`, `⊙zp` e simili.

Il bottone **Registra per punti (p)** apre la modalità a click. Nel pannello compare un bottone per ogni dischetto, una fila per anello: siccome di ogni anello puoi vedere una faccia sola alla volta, la fila è `xm00 ... xm11` oppure `xp00 ... xp11`, e la sessione sceglie quale offrirti leggendo la posa in cui hai messo la gabbia. Sopra le file c'è la riga **faccia che vedi:** con sei bottoni, `Xp Xm Yp Ym Zp Zm`, per correggerla quando sbaglia o quando un anello è quasi di taglio e non si pronuncia: la regola per leggere una faccia dalla foto è guardare il doppio pallino, dal dischetto grande verso il pallino piccolo, antiorario è `p`, orario è `m`; nel dubbio, il tasto `n` (vedi sotto) te lo mostra sulla foto.

Il gesto: premi il bottone di un dischetto, poi clicchi dove sta nella foto. Il click è assistito: il programma cerca il dischetto scuro intorno al punto cliccato e mette il click esattamente al suo centro, quindi non serve mirare al pixel. Se l'aggancio prende la macchia sbagliata (un'ombra, un riflesso, un dischetto vicino) lo vedi dal pallino che finisce altrove: **Alt+click** piazza il click letteralmente dove hai cliccato, senza automatismo. Alt funziona così solo con la gabbia disegnata nascosta (`v`). Con la gabbia visibile, Alt trascinato serve a un'altra cosa: fa rotolare la gabbia virtuale, e torna a posto da sola al rilascio, per vedere dov'è un dischetto che nella foto non si distingue, tipicamente il pallino interno del dischetto 00, coperto o in ombra. Non serve piazzarli tutti, e non serve nemmeno contarli: bastano il doppio pallino di un anello che lo mostra bene e quattro dischetti dello stesso anello, con i nomi che ti sembrano giusti. Poi premi il tasto **`a`** (in questa modalità il bottone non c'è). Una corona di dodici dischetti uguali si rilegge identica ruotata, quindi i nomi che hai dato possono essere sfasati senza che nessuna posa se ne accorga; è il resto della gabbia, confrontato coi dischetti trovati in tutta la foto, a decidere come si legge quella corona, e il programma ti dice se i tuoi nomi erano giusti o se li ha corretti, senza toccare i tuoi click. Il tasto **`n`** scrive i nomi dei dischetti sulla foto stessa: se cadono lontano dai dischetti, è la gabbia a essere fuori posa. Il clic destro è la gomma: toglie il click che gli sta sotto. **`v`** nasconde la gabbia disegnata, utile quando le linguette solide coprono proprio il dischetto da cliccare.

### Rifinire insieme: `R`

Ogni foto da sola risolve la propria camera, ma una foto sola non sa distinguere una focale sbagliata da una distanza sbagliata: le assorbe l'una nell'altra e riporta un residuo pulito comunque. Quando hai quattro o più foto registrate, il tasto **`R`** rifinisce **insieme** una sola focale e tutte le pose, sui click e sui dischetti che ci sono già. Il REPL scrive la focale trovata e il residuo prima e dopo; una foto che peggiorerebbe il fit perde il voto sulla lente ma resta nel film con la sua posa, e il messaggio lo dice. Se la rifinitura peggiora le cose nel complesso, non viene applicata, e il messaggio ti rimanda alle foto peggiori. Se invece dice che la lente ha sbattuto sul limite della passata, ripremi `R`: ogni passata si muove al massimo del 15%.

### Confermare

Il bottone **Conferma (OK)** scrive nel sorgente una form normale, al posto di quella che avevi valutato:

```clojure
(def A (acquire "/Users/me/scans/valvola"
         {:proxy (registration-cage :d 175.4 :gen 2 :rim-marks? true)
          :pose  {:position [0 0 0] :heading [0 0 1] :up [0 1 0]}
          :marks {}
          :edges {}}))
```

Nella form c'è ciò che appartiene al programma; le pose delle camere, i click, la focale e le foto posate a occhio restano in un file di sessione accanto alle foto, `acquire-state.json`. **Chiudi** esce senza scrivere le misure ma toglie comunque l'`edit-`: la form resta valida. Riaggiungendo `edit-` davanti e rivalutando, la sessione si riapre dov'era: è lo stesso round-trip di `edit-mesh-split` (cap. 18.3). Esc non chiude mai la sessione: esce di un passo dalla modalità in cui sei.

## 20.5 Gli ancoraggi: il tasto `d`

Registrate le camere, serve un posto su cui disegnare. La gabbia non sa nulla dell'oggetto che contiene, quindi il piano lo metti tu, e si chiama **ancoraggio**: una posa nominata, con una posizione e una normale, che finisce nella mappa `:marks` della form.

Il bottone **Add anchor (d)** apre la modalità. Compare un piano con un gizmo di traslazione e rotazione, e una pallina bianca che è il punto dell'ancoraggio, cioè l'origine da cui partirà la turtle. I tre preset **Big ring (1)**, **Medium ring (2)**, **Small ring (3)** mettono il piano al centro della gabbia, parallelo a quell'anello: è un punto di partenza noto, visto che il pezzo sta al centro per costruzione. Da lì lo porti sulla zona piana dell'oggetto col gizmo, e lo slider **Plane offset (mm)** lo fa scorrere lungo la sua normale a passi di mezzo millimetro. La verifica è sempre la stessa: cambi foto con `[` e `]` e guardi se il piano resta appoggiato alla superficie da ogni angolazione; la gabbia solida intorno ti dice se il piano passa davanti o dietro un anello, cioè a che profondità sta. Nel pannello dai un nome all'ancoraggio (il default è `ancora-1`), **New anchor (n)** ne aggiunge un altro, **Exit (d)** torna alla sessione.

Alla conferma gli ancoraggi sono voci di `:marks`:

```clojure
:marks {:coperchio {:position [12.4 -3.1 41.0] :heading [0 0 1] :up [0 1 0]}}
```

Da lì in poi rinominarli, spostarli di un millimetro o cancellarli è normale editing del testo, non un gesto dedicato.

Lo stesso gesto esiste anche **fuori** da edit-acquire, sul palcoscenico (20.8), con un vantaggio decisivo: lì la tua geometria è visibile sopra la foto e segue il piano mentre lo sposti. Scrivi `:coperchio (edit-plane-by-eye :big)` fra i `:marks` e premi Run.

## 20.6 Scattare dal vivo

La sessione può prendere le foto direttamente da una camera collegata: una webcam, o il telefono offerto al Mac come camera di sistema. Il riquadro in fondo al pannello ha il bottone **Camera**, che apre l'anteprima in un angolo del viewport (la spia della camera si accende solo quando lo premi tu), un menu per scegliere il dispositivo se ce n'è più di uno, e il bottone **Grab (g)**. Inquadri guardando l'oggetto, non l'anteprima, e premi `g`: il fotogramma viene salvato nella cartella della sessione come `grab-01.jpg`, `grab-02.jpg` e così via, e compare nella pellicola come foto da registrare. Poi lo registri come qualunque altra foto: posa a occhio e `a`. Uno scatto venuto male si butta con **Delete view N**: al primo click il bottone chiede "Sure?", al secondo cancella la vista e il suo file.

Un fotogramma dal vivo non ha EXIF, quindi la focale va misurata invece che letta. È `R` a farlo, e in una sessione tutta dal vivo la focale misurata viene annotata per quella camera a quella risoluzione in `~/.ridley/cameras.json`: la prossima volta che apri la stessa camera, la sessione parte dalla focale ricordata invece che dal default, e lo dice ("Focale ricordata per questa camera"). Prima di quella misura la focale è quella dello slider, e una focale sbagliata non si presenta come tale: registra pulita con la camera alla distanza sbagliata. Per questo, in una sessione tutta dal vivo, fai `R` presto. E tieni spente le funzioni di inquadratura automatica del telefono (Center Stage): ritagliano al volo, e la lente diventa un bersaglio mobile che nessuna memoria può assorbire.

Nell'app desktop la camera chiede il permesso di macOS la prima volta. In un browser funziona su `localhost`; su un indirizzo `http://` di un'altra macchina non può funzionare, perché la camera richiede un contesto sicuro.

## 20.7 Il palcoscenico

Valutare una `(acquire ...)` nel sorgente accende il palcoscenico: attorno all'oggetto compare un segnaposto a piramide, il frustum, per ogni camera registrata. Non è una sessione modale: è uno stato del viewport, e tutti gli strumenti normali di Ridley continuano a funzionare. Si spegne da solo al primo Run che non contiene più nessuna `acquire`.

Un click su un frustum porta la camera esattamente nella posa di quella foto, con la foto come sfondo e la geometria Ridley sopra, in proiezione corretta: quello che vedi allineato sullo schermo *è* allineato sull'oggetto reale. Nella toolbar del viewport compaiono i controlli: **‹** e **›** volano da una foto all'altra, il toggle centrale **Photo** mostra dove sei (`photo 3/12`) e commuta tra vista in posa e orbita libera, **Marks** mostra o nasconde gli ancoraggi e gli spigoli scritti nel sorgente, **Plane** apre il gesto del piano misurato (sotto). Da tastiera, `[` e `]` navigano le foto ed Esc torna all'orbita. In posa la rotellina zooma la foto attorno al cursore e il tasto destro la trascina: la camera resta bloccata sulla posa registrata, ti muovi solo dentro l'inquadratura. Se una foto è registrata male, il toggle lo segnala con un ⚠: su quella foto tutto si riproietta storto, ancoraggi compresi.

[acquire](ref:acquire) non è solo una dichiarazione: restituisce un valore, destrutturabile per nome:

```clojure
(:proxy A)   ; the cage, mounted as scaffold
(:marks A)   ; the anchors, by name
(:edges A)   ; measured edges, by name
```

La gabbia viene montata in scena come impalcatura: si vede, ci si aggancia, ma non entra mai in una CSG né in un export. E per la cosa che si fa in continuazione, piazzare la turtle su un ancoraggio, c'è la forma corta:

```clojure
(turtle A :at :coperchio
  (extrude (rect 18 16) (f 2)))
```

è l'abbreviazione di `(turtle (:coperchio (:marks A)) ...)`.

## 20.8 Ricalcare sulla foto

Il ricalco si fa con gli strumenti che già conosci, sopra l'ancoraggio:

```clojure
(turtle A :at :coperchio
  (edit-path-2d))
```

L'editor di path ([edit-path-2d](ref:edit-path-2d), cap. 5) si apre sul piano dell'ancoraggio, e i nodi si piazzano cliccando sopra la foto: il tratto è geometria 3D vera su quel piano. Qui entra in gioco la mossa che rende il ricalco affidabile: **cambiare foto mentre l'editor è aperto**, con `[` e `]`. Il tratto non si muove, la camera sì: se dal nuovo punto di vista il tratto continua a combaciare con l'oggetto, il ricalco è giusto; se si stacca, stai disegnando alla profondità sbagliata, e lo correggi guardandolo da lì. È la triangolazione fatta a occhio, con la geometria che si riproietta da sola. Un'avvertenza pratica: da certe angolazioni il piano di schizzo va quasi di taglio rispetto alla camera, e i click diventano mal condizionati (piccoli errori di mira, grandi salti sul piano); se i click sembrano impazzire, cambia foto. Chiuso il ricalco, la shape emessa nel sorgente è una `poly` normale: la estrudi, la usi in una CSG, la parametrizzi. Da qui in poi è il capitolo 4.

Quando il piano non lo sai mettere a occhio, il palcoscenico lo sa **misurare**: bottone **Plane** nella toolbar. In posa su una foto clicchi un punto di una zona piana dell'oggetto; compare un pallino giallo sul raggio del click, che visto dalle altre foto scorre lungo una retta. Cambi foto con `]`, riclicchi lo stesso punto fisico aiutandoti con la retta, e il pallino diventa verde: il punto è triangolato. Con `n` passi al punto successivo; dopo tre punti Invio adatta il piano e propone un dischetto traslucido appoggiato sulla zona, che controlli navigando le foto; un secondo Invio lo accetta e scrive nel sorgente un [plane-mark](ref:plane-mark) dentro `:marks`, con i punti da cui è nato. La HUD ti guida coi numeri che contano, parallasse e scarto in pixel. Un mark così si riapre anteponendogli `edit-` e rivalutando, come ogni altra sessione di edit. Il risultato si usa esattamente come un ancoraggio: `(turtle A :at :piano-1 (edit-path-2d))`.

E quando il piano lo sai mettere **a occhio**, c'è [edit-plane-by-eye](ref:edit-plane-by-eye): l'ancoraggio del tasto `d`, ma sul palcoscenico. Scrivi `:coperchio (edit-plane-by-eye :big)` fra i `:marks` della `acquire` e premi Run: la camera va in posa su una foto e compare un dischetto al centro della gabbia, parallelo all'anello grande (`:medium` e `:small` per gli altri due, oppure `:x` `:y` `:z`), con un gizmo di traslazione e rotazione. Lo porti sulla zona piana dell'oggetto; un click sulla foto mette l'origine dove hai cliccato; le frecce spostano l'origine nel piano e con Shift il piano in profondità; `[` e `]` cambiano foto per controllare che resti appoggiato. La differenza con la `d` è quello che vedi: la geometria che hai già costruito su quel mark resta sopra la foto e si muove con il piano a ogni gesto, così miri a lei e non al dischetto. Invio scrive [plane-by-eye](ref:plane-by-eye) nel sorgente, `(plane-by-eye :big {…})`; per ritoccarlo rimetti `edit-` davanti e Run; Backspace lo riporta dov'era partito; Esc lascia tutto com'era. Un `(plane-by-eye :big)` senza posa è già un mark valido: il piano dell'anello stesso.

## 20.9 La caduta dell'impalcatura

Come nel capitolo 18, l'impalcatura cade per sostituzione deliberata, non per cancellazione. Finché lavori, la `(acquire ...)` resta nel programma: è il tuo archivio di misure, e il palcoscenico è sempre a un eval di distanza. Quando un pezzo è finito, sostituisci l'accesso nominato col suo letterale (la posa di un ancoraggio, la posizione di un punto), e quando l'ultima dipendenza è sciolta la forma esce dal sorgente. Restano le foto nella cartella e il file di sessione: la falsariga si conserva, ma il programma non ne ha più bisogno.

Due limiti onesti, per sapere cosa aspettarsi. La precisione della registrazione è quella della gabbia: i dischetti sono punti ben definiti per costruzione, ed è per questo che il riferimento è la gabbia e non l'oggetto, ma una gabbia imbarcata o un pezzo che si è mosso tra due scatti non si vedono nel residuo di una foto sola, si vedono cambiando foto. E il ricalco resta un lavoro a occhio: la gabbia ti dà viste misurate, la geometria la disegni tu, e la verifica da un'altra foto è la parte del metodo che non si può saltare.
