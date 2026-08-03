<!--
Bozza del capitolo 19 (2026-07-30, redatta da Claude-docs, DA VERIFICARE con Code).
Convenzioni adottate:
- Prosa in italiano, codice esempi in inglese. No em-dash (come cap. 18).
- Il flusso documentato è il v1 consegnato (gate funzionale passato): registrazione
  in edit-acquire (Auto/anello/PnP), palcoscenico eval-driven della (acquire ...),
  ricalco con edit-path-2d NORMALE. Il vecchio :retrace modale NON è documentato
  (in rimozione).
- Il piatto di registrazione è presentato come via primaria; il proxy-box come
  alternativa/fallback (storicamente è nato prima, ma l'ergonomia migliore è il piatto).
- VERIFICATI sul working tree (2026-08-02, Code): tasti a/f/p/s/m/k/r/[/] in
  edit-acquire; tasti n/Invio/Backspace/Esc del gesto Piano e [/] sul
  palcoscenico; etichette "Auto — rileva e registra (a)", "Piano", Prev/Next e
  toggle "foto i/N · θ°"; (registration-plate) come binding built-in; :faces dai
  face-groups della mesh (il piatto espone :top/:bottom/:side, quindi la
  scorciatoia del piano parallelo alla base regge); :shapes ancora emessa.
  Corretto qui il limite ormai falso "riconfermare cancella i mark": il confirm
  fonde :shapes/:marks per chiave e lascia intatto ciò che non possiede.
- RESTA da fare prima di pubblicare: registrare il capitolo in
  src/ridley/manual/structure.cljs (oggi si ferma al 18, quindi questa guida NON
  compare nel manuale) e decidere la versione inglese; verificare i rimandi ai
  capitoli 4 e 5; shift+click misura e righelli di calibro (previsti dal brief)
  restano non citati perché non confermati.
- §19.6 riscritta il 2026-07-31 attorno al mark-piano: gradino 1 del
  brief-plane-marks COSTRUITO e gate umano PASSATO (HANDOVER-plane-marks.md),
  scelta (A): write-back dal palcoscenico nella mappa :marks della form.
- Il numero 19 era riservato a "Estendere Ridley" (mai scritto): slitta a 20,
  aggiornare manual-redesign-plan e structure.cljs quando si registra il capitolo.
-->

# 19. Acquisire dalle foto

<!-- level: advanced -->

## 19.1 Fotografie come strumenti di misura

Il capitolo 18 parte da una mesh che esiste già: un STL scaricato, una scansione. Questo capitolo parte da un gradino più indietro: l'oggetto ce l'hai sulla scrivania, ma non ne hai nessun modello. La strada classica sarebbe la fotogrammetria densa: decine di foto, una nuvola di punti, una mesh pesante da riparare e ripulire, e alla fine comunque il lavoro del capitolo 18 per arrivare a un modello parametrico.

Ridley percorre una strada più diretta, che potremmo chiamare fotogrammetria parametrica: poche foto scattate su un piatto girevole diventano *viste misurate*, cioè sfondi con una camera calibrata alle spalle, e tu ricostruisci l'oggetto direttamente come sorgente Ridley nativo, disegnando sopra le foto con gli strumenti normali del linguaggio. Non esiste mai una nuvola di punti: il prodotto dell'acquisizione è codice, con le misure vere in millimetri.

Il flusso ha tre momenti: scattare la sessione fotografica, registrare le camere (dire a Ridley da dove è stata scattata ogni foto), e ricalcare la geometria sopra le viste registrate. I primi due si fanno una volta sola; il terzo è il lavoro di modellazione vero e proprio, e puoi tornarci quando vuoi.

## 19.2 La sessione fotografica

Servono tre cose: un piatto girevole (va bene anche uno manuale), un telefono, e il piatto di registrazione stampato.

Il piatto di registrazione è un pezzo Ridley: lo trovi in `examples/param-acq-plate.clj`. È un disco con una corona di dischetti a due colori incassati a filo, più un dischetto fuori corona che fa da zero, cioè da riferimento per l'orientamento. Si stampa in due colori e si esporta con `save-3mf`. La sua funzione è essere un bersaglio che il software sa riconoscere da solo in ogni foto: i dischetti sono a posizioni note per costruzione, quindi trovarli nell'immagine basta a calcolare esattamente da dove la foto è stata scattata.

La sessione si scatta così: l'oggetto sta fermo sul piatto di registrazione, il piatto sta sul girevole, e tu scatti una foto ogni rotazione di circa 30 gradi, per un giro completo. Foto aggiuntive da posizioni libere (un dettaglio dall'alto, un lato difficile) sono benvenute: si registrano anche loro. Le foto finiscono in una cartella, che è la sessione.

Sulla focale non devi fare niente: Ridley la legge dall'EXIF delle foto (la focale equivalente 35mm che il telefono scrive in ogni scatto). Lo slider Focale nella sessione di registrazione resta come override manuale; attenzione però che una focale impostata a mano viene salvata e ai rientri successivi vince sull'EXIF. Se la registrazione automatica non aggancia nessuna foto, la prima cosa da controllare è proprio la focale.

## 19.3 Registrare le camere: edit-acquire

La registrazione si apre dichiarando la cartella e il proxy, cioè l'ancora geometrica che il software cerca nelle foto:

```clojure
(edit-acquire "scans/reader/" {:proxy (registration-plate)})
```

Si apre una sessione sul viewport: una pellicola di miniature mostra le foto, con un badge per ciascuna (grigia = non registrata, verde con il residuo in pixel = registrata). Con il piatto di registrazione, il gesto principale è uno solo: il bottone **Auto** (tasto `a`). Per ogni foto il software trova i dischetti della corona su tutto il fotogramma, li identifica usando lo zero come riferimento, e risolve la posa della camera. Il progresso scorre nel pannello REPL. Le foto ben inquadrate si registrano da sole in qualche secondo; gli scatti molto radenti, dove i dischetti diventano ellissi sottili, possono restare fuori, e vengono lasciati indietro apposta: meglio nessuna posa che una posa sbagliata.

Per le foto rimaste indietro ci sono due rifiniture. Il tasto `f` propaga la registrazione lungo l'anello del girevole: conoscendo gli angoli della sessione, predice la posa delle foto non registrate da quelle vicine già registrate. Il tasto `p` è la registrazione manuale per corrispondenze: armi un punto noto del proxy, clicchi dove sta nella foto, e con almeno sei corrispondenze il software risolve la posa in forma chiusa. Se una corrispondenza ha l'identità sbagliata, il fit la individua, la scarta e te la segnala in rosso perché tu la riclicchi.

Il proxy non deve per forza essere il piatto. Se il piatto non c'è (una sessione vecchia, un oggetto troppo grande), l'ancora può essere una scatola di ingombro misurata col calibro:

```clojure
(edit-acquire "scans/reader/" {:proxy (box 60.2 20.2 40.1)})
```

Con un box i gesti sono quelli manuali: `p` sui suoi spigoli, `s` per l'aggancio sub-pixel della silhouette, e il tasto `m` per bloccare il ramo di simmetria su un segno fisico (un tratto di pennarello sull'oggetto): un box è simmetrico, e senza un segno che rompa la simmetria due pose specchiate sono indistinguibili. È il flusso storico del canale, funziona, ma il piatto rende tutto questo superfluo: se puoi, usa il piatto.

Alla conferma, la sessione scrive nel sorgente una forma normale:

```clojure
(acquire "scans/reader/"
  {:proxy (registration-plate)
   :pose  {:position [0 0 0] :heading [0 1 0] :up [0 0 1]}
   :shapes {}
   :marks  {}})
```

e le pose delle camere, i click e la focale restano in un file di sessione accanto alle foto (`acquire-state.json`). La demarcazione è deliberata: nella forma c'è ciò che appartiene al programma, nel file di sessione c'è la fotografia dell'impalcatura. Riaggiungendo `edit-` davanti alla forma, la sessione si riapre dov'era: è lo stesso round-trip di `edit-mesh-split` (cap. 18.3).

## 19.4 Il valore di acquire

`acquire` non è solo una dichiarazione: restituisce un valore, destrutturabile per nome come l'albero di `split-tree`:

```clojure
(let [A (acquire "scans/reader/" {...})]
  ;; (:proxy A)  the anchor mesh, mounted as scaffold
  ;; (:faces A)  named poses on the proxy faces (:top, :bottom, ...)
  ;; (:marks A)  named points measured on the photos
  ...)
```

Il proxy viene montato in scena come impalcatura: si vede, ci si aggancia, ma non entra mai in una CSG né in un export. I nomi in `:faces` seguono la convenzione dei face-group della primitiva (gli stessi nomi che usa `flash-face`), quindi `:top` è la stessa faccia dappertutto.

Per la cosa che si fa in continuazione — piazzare la turtle su un mark — c'è la forma corta, la stessa che vale per i mark di un path o di una mesh:

```clojure
(turtle A :at :piano-1
  (extrude (rect 18 16) (f 2)))
```

è l'abbreviazione di `(turtle (:piano-1 (:marks A)) …)`. Valgono anche i nomi delle facce (`:at :top`), e se un mark ha lo stesso nome di una faccia vince il mark.

## 19.5 Il palcoscenico

Valutare una `(acquire ...)` nel sorgente accende il palcoscenico: attorno all'oggetto compare l'anello dei frustum, un segnaposto per ogni camera registrata. Non è una sessione modale: è uno stato del viewport, e tutti gli strumenti normali di Ridley continuano a funzionare.

Un click su un frustum porta la camera esattamente nella posa di quella foto, con la foto come sfondo e la geometria Ridley sopra, in proiezione corretta: quello che vedi allineato sullo schermo *è* allineato sull'oggetto reale. Nella toolbar del viewport compaiono tre controlli: Prev e Next volano da una foto all'altra in ordine di angolo, e il toggle centrale mostra dove sei (foto i/N e angolo) e commuta tra vista in posa e orbita libera. Da tastiera, `[` e `]` navigano le foto. In posa, la rotellina zooma attorno al cursore e il tasto destro trascina: la camera resta bloccata sulla posa registrata, ti muovi solo dentro l'inquadratura.

## 19.6 Ricalcare sulla foto

Il ricalco si fa con gli strumenti che già conosci, sopra un piano di lavoro. Col proxy box i piani erano gratis (le facce del box abbracciano l'oggetto); col piatto no: il piatto registra le camere ma sta sotto il pezzo, e delle sue facce non sa nulla. Il gesto che colma il buco è il **mark-piano**: dal palcoscenico, bottone **Piano** nella toolbar.

Funziona così. In posa su una foto, clicchi un punto di una zona piana dell'oggetto. Un click solo non basta a dare la profondità, e il palcoscenico lo mostra con onestà: compare un pallino giallo sul raggio del click, che visto dalle altre foto scorre lungo una retta (è la retta su cui il punto deve stare). Cambi foto con `]` e riclicchi lo stesso punto fisico, aiutandoti con la retta: il pallino diventa verde, il punto è triangolato. Con `n` passi al punto successivo. Dopo almeno tre punti, Invio adatta il piano e propone un **dischetto traslucido** appoggiato sulla zona: navighi le foto, e se il dischetto resta incollato alla superficie da ogni angolazione il piano è giusto; un secondo Invio lo accetta, e il mark viene scritto nella mappa `:marks` della form `acquire`, direttamente nel sorgente. Da lì in poi rinominarlo o cancellarlo è normale editing del testo, non un gesto dedicato.

Un dettaglio che conta: la normale del piano è una misura (più punti clicchi, migliore diventa), ma l'origine del mark, per default, è il centroide dei tuoi click, cioè un artefatto del gesto: rifare il mark la sposterebbe. Per questo un click dedicato la piazza dove vuoi tu, su una feature riconoscibile della superficie (a piano noto basta una foto sola, e il punto è esatto). Ancorata l'origine a qualcosa di vero, il mark è riproducibile.

La HUD sul viewport ti guida passo per passo, coi numeri che contano: la parallasse (sotto gli 8 gradi circa il punto triangola male: usa foto più distanti tra loro), lo scarto in pixel, la planarità in millimetri. Con tre o più foto, se un click è sbagliato il software indica quale togliere; con due sole non si può stabilire il colpevole, e te lo dice invece di tirare a indovinare. Un avviso sul bottone Foto segnala le foto registrate male (residuo alto): una posa cattiva riproietta storto tutto quello che vedi, mark compresi, quindi meglio saperlo prima di fidarsi di ciò che sembra allineato.

Il mark-piano è un'ancora come le altre:

```clojure
(def ma (:piano-1 (:marks A)))

(turtle ma
  (edit-path-2d))
```

I nodi si piazzano cliccando sopra la foto, e il tratto è geometria 3D vera sul piano. Qui entra in gioco la mossa che rende il ricalco affidabile: **cambiare foto mentre l'editor è aperto**, con `[` e `]` o con la toolbar. Il tratto non si muove, la camera sì: se dal nuovo punto di vista il tratto continua a combaciare con l'oggetto, il ricalco è giusto; se si stacca, stai disegnando alla profondità sbagliata, e lo correggi guardandolo da lì. È la triangolazione fatta a occhio, con la geometria che si riproietta da sola. Un'avvertenza pratica: da certe angolazioni il piano di schizzo va quasi di taglio rispetto alla camera, e i click diventano mal condizionati (piccoli errori di mira, grandi salti sul piano); se i click sembrano impazzire, cambia foto.

Due note per finire. Per le zone parallele alla base c'è la scorciatoia: l'oggetto sta appoggiato sul piatto, quindi la normale della base è nota (`(:heading (:top (:faces A)))`), e basta un punto per definire il piano. E un mark si rieredita: `(plane-mark {...})` è la sua forma di riposo nel sorgente, e anteponendole `edit-` (`(edit-plane-mark {...})`) più un Run la riapri per spostarne l'origine, aggiungere un punto e rifittare il piano, esattamente come si riapre qualsiasi altra sessione di edit.

Chiuso il ricalco, la shape emessa nel sorgente è una `poly` normale: la estrudi, la usi in una CSG, la parametrizzi. Da qui in poi è il capitolo 4.

## 19.7 La caduta dell'impalcatura

Come nel capitolo 18, l'impalcatura cade per sostituzione deliberata, non per cancellazione. Finché lavori, la `(acquire ...)` resta nel programma: è il tuo archivio di misure, e il palcoscenico è sempre a un eval di distanza. Quando un pezzo è finito, sostituisci l'accesso nominato col suo letterale (la posa di una faccia, la posizione di un mark), e quando l'ultima dipendenza è sciolta la forma esce dal sorgente. Restano le foto nella cartella e il file di sessione: la falsariga si conserva, ma il programma non ne ha più bisogno.

Due limiti onesti, per sapere cosa aspettarsi. La precisione della registrazione è quella dei punti di riferimento veri: su un oggetto con spigoli raccordati "l'angolo" non esiste come punto, e il residuo in pixel lo riflette; il piatto di registrazione esiste esattamente per questo, perché i suoi dischetti sono punti ben definiti per costruzione. E il canale oggi lavora su cartelle di foto: sorgenti dal vivo (webcam, companion app) sono nella direzione di marcia ma non ancora nel prodotto.
