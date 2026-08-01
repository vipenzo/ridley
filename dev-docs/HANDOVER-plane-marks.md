# Handover — mark-piano (brief-plane-marks.md, gradino 1)

Stato al 2026-07-31. **Gate umano PASSATO** (Vincenzo: "ho provato e funziona").
Committato: `0055534` (mark-piano + UI), `1013161` (`edit-plane-mark`), e il
giro seguente (forma di riposo + confirm chirurgico).

Brief di riferimento: `dev-docs/brief-plane-marks.md`.

## Il gate e i suoi due esiti (2026-07-31)

Vincenzo ha creato un mark-piano su foto vere e ha ricalcato. Funziona. Due
rilievi, entrambi affrontati:

**(a) "Le ultime due foto probabilmente non erano allineate: vedevo sia i
puntini sia il dischetto in posti sbagliati."** Diagnosi confermata dai dati, e
il dato *c'era già*: in `test-assets/param-plate-paper/acquire-state.json` le
foto 8 e 9 portano `rms-px` 9.92 e 11.32 su 10 marker di 12, contro ~4.5 px su
12/12 di tutte le altre. Il palcoscenico semplicemente **non leggeva quel
campo**. Ora `parse-acquire-state` tiene `:registration {idx {:rms-px
:matched}}` e la qualità è esposta dove serve: ⚠ sul bottone `Foto` (vale per
tutto, non solo per il piano: una posa sbagliata riproietta storto proxy,
ricalchi e mark), riga rossa nella HUD, e avviso esplicito se ci clicchi
sopra. Soglia `poor-registration-px` = 8 px, che cade nel vuoto largo fra i due
regimi osservati.

**(b) "La UI con questo workflow e gli stati da far avanzare a keyword è un po'
ostica."** Vero: lo stato viveva in una scritta stretta sul bottone e in messaggi
di console che scorrono via, e ogni passo andava avanti con un tasto da
ricordare. Ora c'è una **HUD sul viewport** (`#eaq-plane-hud`): i tre passi della
procedura sempre tutti visibili col corrente evidenziato, il dettaglio di cosa
fare *adesso* coi numeri (scarto px, parallasse, planarità), l'elenco delle foto
già cliccate per il punto corrente, e **ogni azione è un bottone il cui stato
abilitato/disabilitato È la risposta a "cosa posso fare ora"**. I tasti
continuano a funzionare per chi li preferisce. La HUD si ricostruisce per intero
a ogni cambio di stato (è funzione pura dello stato, non può sfasarsi) ed è
agganciata a `update-toolbar!`, chiamata a ogni cambio di posa — così la riga
"foto N — reg. …" resta onesta mentre navighi.

## Il secondo giro (2026-07-31): "il mark è da tutt'altra parte"

Sintomo: `(def ma (:piano-1 (:marks A)))` + `(turtle ma (extrude (circle 10)
(f 2)))` disegnava il disco lontanissimo dal piano appena definito.

**Il mark era sano.** Verificato sui numeri emessi: heading ⊥ up a 1.3e-05,
`:up` esattamente la proiezione dell'`:up` della posa nel piano fittato, il
punto 12.0 mm sopra il piatto e 18.7 mm dal suo asse. Nulla da correggere lì.

**Il bug era in `turtle`.** `parse-turtle-opts` riconosceva una posa solo se il
primo argomento era un letterale, un vettore, `:pose <expr>`, `<path> :at
<nome>`, un accessore in linea `(:piano-1 …)` / `(get …)`, o un keyword-ancora.
**Un simbolo cadeva nel `:else` e diventava la prima forma del CORPO**: la
turtle restava alla posa del genitore e la geometria compariva lì — all'origine.
Silenzioso, perché valutare un simbolo non ha effetti collaterali. E la firma
documentata era già `(turtle pose-map & body)`: la documentazione prometteva
ciò che il macro non faceva.

Correzione: un simbolo in prima posizione viene valutato e, **a runtime**, usato
come posa se è una mappa con `:heading` e `:position`/`:pos`; altrimenti resta
esattamente ciò che era, una forma del corpo (una mesh porta `:vertices`/
`:faces`/`:creation-pose`, mai quella coppia, quindi non può essere scambiata
per una posa). Il caso `(turtle sym)` senza corpo continua a valere `sym`.

Resta tagliente — per scelta — il caso della **chiamata**: `(turtle (f x) …)`
non può essere valutata prima del corpo senza rischiare effetti collaterali, e
resta corpo. Per quello c'è `:pose`. Il test
`test/ridley/editor/turtle_pose_symbol_test.cljs` fissa entrambe le cose, e
quello sulla chiamata **è la prova del meccanismo**: `(turtle (identity ma) …)`
— stesso identico valore, forma non riconosciuta — atterra ancora a `[0 0 0]`.
`docs/manual/reference/en/turtle.md` ora dice da dove può venire una posa e cosa
succede quando il macro non la riconosce (indice rigenerato con `bb`).

**Feedback sul primo click** (l'altro rilievo): ora un click che aspetta la
seconda foto disegna un **pallino GIALLO** sul proprio raggio, alla minima
distanza dall'oggetto. Non è cosmetico: ogni punto del raggio riproietta
esattamente sul pixel cliccato, quindi sulla foto da cui hai cliccato il pallino
cade sotto il cursore (verificato: riproietta a `[2000 1500]` per un click a
`[2000 1500]`), mentre sulle altre scivola lungo la retta epipolare — che è il
ritratto onesto di ciò che si sa finora: direzione sì, profondità non ancora.
Diventa verde quando il punto è triangolato.

## Gli accertamenti chiesti dal brief (risposte)

**1. Come nasce oggi un mark nominato (modalità `k`)?**
Su un piano DICHIARATO. `mark-on-pointerdown` (`edit_acquire.cljs:2516`)
backproietta il click con `pcamera/pixel-ray` e lo interseca con la faccia del
box scelta (`plane-of (:mark-plane @session)`), esattamente come il ricalco.
Un click = un punto, perché il piano è già noto. **Non esisteva nessuna
triangolazione libera multi-foto**: era il primo mattone da scrivere, ed era
piccolo (`triangulate.cljs`, ~90 righe di sostanza).

**2. Cosa espone `(:faces A)` col proxy piatto?**
`face-poses` (`edit_acquire.cljs:3703`) chiavizza sui `:face-groups` DELLA MESH.
Il piatto è un cilindro, quindi i gruppi sono `:top`, `:bottom`, `:side`
(`faces/cylinder-face-groups`). Dopo la rotazione dei vertici in `plate.cljs`
(asse lungo +Z) **`:top` è la faccia marcata**, e la sua posa ha
`:heading` = normale del piatto: `(:heading (:top (:faces A)))` è quindi la
normale della base, in forma comoda — che è ciò che serviva al caso "piano
parallelo al piatto". (Nota a margine: `:side` è degenere — la media delle
normali di un cilindro completo è ~0. Non è usata da nulla, ma non fidarsene.)

**3. Da dove viene lo heading di un mark emesso oggi?**
Dalla normale della faccia dichiarata, salvata nel mark al momento del click
(`:normal normal`), poi portata in mondo da `marks-entries`
(`edit_acquire.cljs:3808`) con `:up` = asse box successivo (`normal-up-obj`).
Il mark-piano sostituisce ESATTAMENTE quella sorgente con la normale fittata:
nient'altro cambia a valle.

**4. Costo del write-back palcoscenico → sorgente.**
**Piccolo.** `modal/find-form-bounds`, `modal/replace-source!` e
`modal/run-definitions!` sono funzioni pure sul buffer CodeMirror, senza
nessun legame con la sessione modale: usabili dal palcoscenico così come sono.
Mancava solo un matcher di parentesi che capisse `{}` (quello esistente conta
solo le tonde) per delimitare il blocco `:marks {…}`. Totale: il namespace
`ridley.editor.source-edit` (~100 righe, puro, testato) + ~35 righe nel
palcoscenico. **Quindi ho seguito la regola del brief e scelto (A).**

Una precisazione onesta che cambia il peso della decisione: il write-back non
era l'unico costo di (A) — l'altro era "rifare sul palcoscenico la macchina dei
mark nominati (nome, elenco, cancella, persistenza)". Si è rivelato **nullo**,
perché una volta scritto nel sorgente il mark si rinomina e si cancella
modificando il sorgente. È il vantaggio decisivo di (A) e vale la pena
saperlo: il palcoscenico che scrive nel sorgente eredita gratis tutta
l'ergonomia dell'editor di testo.

**5. Residui e condizionamento.** Fatti, e uno è finito diverso da come me
l'aspettavo — vedi "Il ritrovamento" più sotto.

## Cosa è stato costruito

### `src/ridley/photogrammetry/triangulate.cljs` (nuovo, puro)

- `triangulate` — punto 3D ai minimi quadrati da N raggi, forma chiusa
  (`(Σ Pᵢ)x = Σ PᵢCᵢ` con `Pᵢ = I − dᵢdᵢᵀ`, sistema 3×3, `la/solve`). Riporta
  `:rms-px`, `:max-residual-px`, `:parallax-deg` (angolo massimo fra due raggi
  = baseline effettiva) e `:worst-obs`.
- `fit-plane-mark` — piano per ≥3 punti via `pnp/plane-frame` (resa pubblica),
  normale orientata verso le camere (`:toward`), `up` da una LISTA di
  candidati (`:up-hints`, il primo utilizzabile vince), più `:flatness-mm` e
  `:per-point`.
- `plane-through-point` — il caso 1 click / piano parallelo al piatto.
- `disc-mesh` — il dischetto di verifica.
- `min-parallax-deg` = 8° — soglia di conditionamento condivisa con la UI.

### `src/ridley/editor/source_edit.cljs` (nuovo, puro)

Chirurgia di testo: `find-matching-bracket` (tonde/quadre/graffe annidate,
stringhe, commenti), `map-value-bounds`, `append-map-entry`, `column-of`,
`fmt-number`/`fmt-vec3` (spostate qui da `edit_acquire`, ora condivise:
la forma emessa dal confirm e quella emendata dal palcoscenico devono
formattarsi identiche o il sorgente si biforca in due dialetti).

### `src/ridley/editor/acquire_stage.cljs` (modificato)

Il gesto, sul palcoscenico. Bottone **"Piano"** nella toolbar del viewport.
Click in posa = osservazione del punto corrente su quella foto (un secondo
click sulla stessa foto SOSTITUISCE, non accumula); `n` = punto successivo;
`Invio` = fit → **dischetto proposto, niente scritto**; `Invio` di nuovo =
accetta e scrive; `Backspace` = annulla; `Esc` = esce dal modo (prima di
`leave-pose!`, così non ti butta fuori dalla foto).

Due dettagli che valgono più di quanto costino:

- **I raggi dei click sono disegnati nel mondo.** Riproiettati su un'ALTRA
  foto sono esattamente la retta epipolare: dopo il primo click, `]` e la
  linea ti dice dove deve stare lo stesso punto. Costo: 10 righe.
- Il dischetto vive nel mondo e sta sul layer frustum (quello del
  palcoscenico), quindi convive con un `edit-path-2d` aperto e si ri-mostra
  dopo un Run.

## Il ritrovamento (accertamento 5)

**Il residuo per-vista NON dice quale click è sbagliato.** Sbilanciando di
120px il click della vista 2 su tre viste, i residui tornano `[62.5, 11.4,
61.9]px`: i minimi quadrati spostano il punto finché l'errore è SPALMATO, e la
vista che "sembra" peggiore è una innocente. La versione iniziale del codice
(e il messaggio UI "riclicca sulla foto sbagliata") sarebbe stata sbagliata.

Rimedio: `worst-observation` — leave-one-out. Si toglie una vista per volta e
si guarda quale rimozione fa tornare d'accordo le altre; il colpevole è
nominato solo se l'rms scende sotto 1/4 (altrimenti l'insieme è solo
rumoroso e non si accusa nessuno). Con 2 sole viste torna `nil` per
costruzione — e la UI lo dice invece di inventare: *"Con due sole foto non si
può dire quale: aggiungine una terza."*

Secondo dettaglio, minore ma da sapere leggendo i numeri: un piano ai minimi
quadrati **si inclina per assorbire** un bozzo locale. Uno scalino di 3mm su
6 punti si legge come 1.5mm di `:flatness-mm`, non 3. Il numero serve a dire
"questa zona non è piana", non a misurare il bozzo.

## Test

`test/ridley/photogrammetry/triangulate_test.cljs` (11 deftest) —
recupero esatto senza rumore, degrado con rumore di click a 2px (errore medio
0.12mm su 3 foto), il colpevole nominato da leave-one-out, l'insieme pulito
che non accusa nessuno, la baseline degenere (2° → 1.84° di parallasse, sotto
soglia), normale orientata verso le camere, up nel piano, zona non piana che
lo dichiara, degeneri che tornano nil, disco che giace nel piano.

`test/ridley/editor/source_edit_test.cljs` (5 deftest) — bracket matching
(stringhe, commenti, sbilanciati), il blocco `:marks` trovato e non confuso col
`:mark` annidato dentro `:shapes`, e soprattutto: **fuori dal blocco il
sorgente resta byte-identico**.

## Il terzo giro (2026-07-31): l'origine del mark

Domanda di Vincenzo: "la posizione in quel piano del mark da cosa dipende?
Sembra il centro dei tre punti". Esatto — ed è l'asimmetria che conta:

| componente | da dove viene | riproducibile? |
|---|---|---|
| `:heading` | fit ai minimi quadrati su tutti i punti | **sì** — è una misura, migliora con più punti |
| `:up` | `:up` della posa dell'oggetto, proiettato nel piano | **sì** — non dipende dai click |
| `:position` | centroide dei punti cliccati | **no** — artefatto del gesto |

L'origine cadeva sempre esattamente sul piano (un piano ai minimi quadrati passa
per il centroide dei punti che adatta), ma nulla la legava a una *feature* della
superficie: rifare il mark la spostava, e con lei tutto ciò che era stato scritto
contro di essa.

Scelta di Vincenzo: **un click la piazza**. Implementato in `place-origin!`, ed è
esatto con UN solo click su UNA foto — a piano noto un raggio incontra il piano
in un punto solo (`m/ray-plane-point`, la stessa inversa del ricalco): nessuna
triangolazione, nessuna seconda vista. Verificato dal vivo su tre bersagli:
errore 0, 3.55e-15, 0 mm. Se non clicchi resta il centroide; il bottone "Origine
al centro" torna indietro senza rifittare il piano. L'origine è disegnata come
**pallino magenta** (stesso colore dei mark altrove), distinta dal dischetto che
centra.

Un bug preso proprio grazie a questa verifica: `place-origin!` all'inizio si
ricalcolava le intrinsics invece di ricevere quelle già validate dal chiamante,
e in un contesto senza foto caricata falliva in silenzio. Ora le riceve —
una dipendenza in meno e una funzione testabile.

## Il quarto giro (2026-07-31): `(edit-plane-mark …)`

Design di Vincenzo (brief §Seguito): niente UI di selezione; il mark da editare
si marca NEL SORGENTE avvolgendolo. Costruito.

**Risposte agli accertamenti del §Seguito.**

1. **Nome: `edit-plane-mark`, non `edit-mark`.** Ogni membro della famiglia
   `edit-*` si accoppia con una forma esistente dello stesso nome
   (`edit-path`↔`path`, `edit-acquire`↔`acquire`…): `edit-mark` prometterebbe di
   editare `(mark :A)`, il comando di ancora dei path — cosa diversa. E la
   simmetria che il nome corto comprerebbe non c'è comunque, perché qui il
   cancel non rinomina una testa: **scarta l'involucro**. Deviazione accettata
   esplicitamente, ed è più onesta — l'impalcatura non sopravvive sotto altro
   nome, cade del tutto, che è l'"inline deliberato del letterale" di P4a.
2. **Meccanica.** Nessun meccanismo nuovo: dentro `:marks` la forma è una
   normale chiamata valutata da SCI *prima* di `acquire`. `request-mark-edit!`
   annota la richiesta e **restituisce il letterale intatto**, così la acquire
   resta valida mentre l'editor è aperto; `after-eval!` — il gancio che il
   palcoscenico già usa — apre. Su una acquire FRESCA le camere arrivano
   asincrone, quindi la richiesta aspetta il `load!` invece di essere persa.
   Con più forme: si apre la prima e si dice che le altre aspettano. Il nome del
   mark non serve al write-back (che possiede un RANGE di sorgente); si recupera
   all'indietro solo per scriverlo nella UI.
3. **Re-confirm di edit-acquire**: confermato, `:from` non c'entra. Il
   re-confirm riemette la forma da `acquire-state.json` e perde i mark-piano
   comunque. Residuo a sé, non risolto qui.

**`:from` nel sorgente**: emesso da ogni mark (creazione compresa), tiene i
punti TRIANGOLATI. `turtle` ignora la chiave in più.

**Origine attraverso il refit**: proiettata sul piano nuovo, non ricalcolata.
Un bug preso proprio qui dal collaudo: la tenevo dentro il candidato, che viene
scartato appena si torna ad aggiungere un punto — quindi si perdeva e l'origine
ricadeva sul centroide. Ora vive in `:origin-override`, fuori dal candidato.

**Collaudato end-to-end sul buffer vero** (dev-browser, CodeMirror reale):
apertura con nome e 3 punti ripresi; aggiunta del quarto punto → l'origine
piazzata a `[8 3 40]` torna `[8 2.9966 40.1499]`, cioè proiettata sul piano
nuovo e non il centroide `[0 3 40.15]`; accetta → letterale con `:from` a 4
punti, prefisso e coda del sorgente byte-identici, involucro sparito; Esc →
letterale ripristinato identico; forma vuota → apre in creazione e Esc lascia
`nil`.

**Nota di metodo (costata tempo)**: `sh/compile :app` via nREPL ha risposto
`warnings=0 failure=nil` mentre il sorgente aveva una parentesi sbilanciata, e
il browser ha servito per mezz'ora codice vecchio. Il compile-check non basta:
**verificare che il bundle emesso contenga davvero il simbolo nuovo**
(`grep public/js/cljs-runtime/<ns>.js`) prima di credere a un collaudo live. Il
watcher era anche incastrato dai compile concorrenti (la nota di memoria era
giusta): riavviato con `npx shadow-cljs stop` + `npm run dev`.

## Il quinto giro (2026-07-31): forma di riposo + confirm chirurgico

Due rifiniture chieste nel brief dopo l'esito.

**`(plane-mark …)`, la forma di riposo.** Costruttore puro che restituisce la
mappa intatta; valida chiavi attese e heading⊥up e SEGNALA senza toccare né
rifiutare (un mark storto è comunque il mark dell'utente: raddrizzarlo di
nascosto nasconderebbe un problema vero, rifiutarlo romperebbe un sorgente che
per il resto renderizza). Sta accanto ad `acquire` in `edit_acquire.cljs`.

Il guadagno non è cosmetico: **la deviazione dalla famiglia sparisce**. Con
`(plane-mark {…})` come forma di riposo, rieditare è "anteponi `edit-` alla
testa e Run" come ovunque, e il cancel torna a essere `modal/strip-head` —
la stessa funzione che usano tutti gli altri editor. Il codice si è
SEMPLIFICATO: `unwrap-edit-mark!` non ha più bisogno di ricostruire il
letterale. Il caso della forma vuota (dove `(plane-mark)` sarebbe un errore di
arità) rimuove l'intera voce chiave+valore — che chiude anche il minor aperto
nell'esito precedente.

**Confirm chirurgico (Seguito 2).** `emit-acquire-code` non rigenera più
`:shapes`/`:marks` in blocco: li FONDE per chiave con quelli che il marcatore
già porta (`preserved-entries` + `merge-entries`, su `src/map-entries`).

Un punto che il brief non prevedeva e che va sottolineato: **oggi anche
edit-acquire possiede `:marks`** — i mark del tasto `k`, persistiti in
`acquire-state.json` e ricaricati alla riapertura — e `:shapes` dai ricalchi.
Preservare il blocco *in blocco*, come la demarcazione suggerirebbe alla
lettera, avrebbe quindi cancellato i suoi. La fusione per chiave è la lettura
corretta della stessa demarcazione: ognuno rigenera le voci che possiede e
lascia intatte, byte per byte, tutte le altre. Verificato dal vivo: `:mark-1`
(della sessione) rigenerato, `:piano-1` conservato col suo `(plane-mark …)` e
il suo `:from`.

Collaudato live: identità di `plane-mark`; avvisi corretti (45° fuori squadra
riportato come 45°, chiave mancante nominata) nel buffer di stampa; cancel →
`(plane-mark {…})` col corpo identico e la coda del file intatta; cancel della
forma vuota → voce rimossa, nessun `nil`; accetta → `(plane-mark {…})` con
`:from`. 806 test verdi.

**Non collaudato da me**: il gate del Seguito 2 vero e proprio — riaprire
`edit-acquire` su una sessione con mark-piano e riconfermare — richiede una
sessione modale con foto vere. Ho verificato il meccanismo (fusione per chiave
su un marcatore reale nel buffer), non il giro completo.

## Seguito 3 (2026-08-01): geometrie spiazzate — misure

**Il discriminante proposto dal brief è confuso.** Due cilindri di altezze
diverse allo stesso mark danno la STESSA correzione — ma non perché ci sia un
offset di sistema: perché **l'asse di un cilindro corre lungo `up`, non lungo
`heading`**, quindi lungo la normale del mark il suo ingombro è il RAGGIO, che
in quel test non cambiava. Chi avesse eseguito quell'eval avrebbe concluso D2
guardando un fenomeno D1. Misurato (`test/ridley/geometry/primitive_anchoring_test.cljs`):

```
  cyl r2 h6    right -2…2 | up  -3…3  | HEADING -2…2   → sepolto 2.00mm
  cyl r2 h20   right -2…2 | up -10…10 | HEADING -2…2   → sepolto 2.00mm
  sphere r1.5  right -1.5…1.5         | HEADING -1.5…1.5 → sepolto 1.50mm
  box 4 6 10   right -2…2 | up  -3…3  | HEADING -5…5   → sepolto 5.00mm
```

**Il discriminante corretto varia la misura LUNGO LA NORMALE** (il raggio per un
cilindro, la terza dimensione per un box): raggio 2→sepolto 2, raggio 5→sepolto
5; d=10→5, d=3→1.5. Sempre metà dell'ingombro proprio lungo la normale.
**Verdetto: D1, convenzione di ancoraggio.** Ogni primitivo nasce CENTRATO
sulla posa; il mark sta sulla superficie; quindi metà solido finisce sotto.

**Conseguenza meno ovvia, misurata**: un cilindro piazzato su un mark-piano
**giace sulla superficie** (asse nel piano) invece di starci in piedi sopra. Per
farlo stare in piedi serve estrudere lungo heading, o girare la turtle.

### Le verifiche di frame chieste al codice

**Nessun mismatch mondo↔oggetto, nessun doppio trasporto.** Il palcoscenico
triangola in MONDO (`world-frame` identità, camere già riconciliate alla
`:emit-pose`) ed emette coordinate assolute; i mark di sessione arrivano allo
stesso posto per un'altra strada (`marks-entries` fa `bridge/local->world` con
la posa d'ancoraggio). `acquire` passa `:marks` **verbatim**
(`(or (:marks opts) {})`), e `resolve-proxy`/`apply-pose` toccano **solo la mesh
del proxy**. Quindi la famiglia (a) del brief è scagionata.

### Ma la verifica ne ha trovato un altro, e riguarda ciò che ho appena scritto

Le due strade differiscono in un punto che conta: **i mark di sessione vengono
RI-SOLLEVATI attraverso la posa a ogni confirm, i mark-piano no** — sono già
mondo, e il confirm chirurgico li conserva byte per byte. Finché la `:pose`
emessa non cambia, identico. Ma se cambia (una ri-registrazione, o una
`:build-pose` diversa), allora al confirm successivo i mark di sessione seguono
l'oggetto e **i mark-piano restano nel mondo vecchio**: spiazzati esattamente
del moto rigido posa-vecchia → posa-nuova.

Prima del confirm chirurgico questo non si vedeva perché i mark-piano venivano
*cancellati*. Non cancellandoli più, il difetto diventa visibile — cioè il
lavoro di ieri ha barattato una perdita con un possibile spiazzamento. La cura
è simmetrica al trasporto che il palcoscenico già fa sulle camere
(`attachment/transform-pose-rigid`): al confirm, se la posa emessa differisce da
quella che il marcatore portava, trasportare rigidamente anche le voci
conservate. NON implementato — è una decisione da prendere con Vincenzo, e non
è detto sia la causa di ciò che ha visto.

### Seguito 3, secondo giro: il codice vero di Vincenzo

Il frammento reale usa `extrude`, non primitivi piazzati — quindi **la
convenzione di ancoraggio NON spiega il suo caso**: la risposta D1 del giro
precedente era corretta come misura e irrilevante come diagnosi. Correzione
messa a verbale.

**Il sintomo esclude un offset costante.** "Migliora in alcune foto, peggiora in
altre" non può essere curato da nessuno spostamento rigido: un solido rigido
spostato è giusto o sbagliato *globalmente*. Quindi `(rt N)` è lo strumento
sbagliato e tararlo è rincorrere.

**Il difetto mio, trovato guardando i suoi dati**: entrambi i suoi mark hanno
ESATTAMENTE 3 punti, e con 3 punti **il piano ci passa esatto** — la planarità
vale 0.00 per aritmetica, non per merito. Lo strumento gli ha mostrato "0.00mm"
come se fosse una promozione. Corretto: `fit-plane-mark` ora riporta `:exact?`
e, al suo posto, il **condizionamento** — `:width-mm` (la presa più stretta del
set di punti nel piano; per un triangolo è la sua altezza minima) e
`:tilt-per-mm-deg` = quanti gradi ruota la normale per 1mm di errore su un
click. Numeri che dicono qualcosa anche quando la planarità non può.

Sui suoi due mark:

```
  piano-h  normale a  1.0° dall'asse del piatto, centro a 7.34mm sopra il piatto
           quote dei 3 punti: 7.56 / 7.79 / 7.11     presa 24.4mm → 2.3°/mm
  piano-1  normale a 89.3° (faccia verticale), quote: 15.38 / 16.01 / -0.23
                                                     presa 12.4mm → 4.6°/mm
```

**L'incompatibilità apparente si risolve**: Vincenzo chiarisce che `piano-h` non
è la faccia superiore ma una faccia perpendicolare a `piano-1`, a circa metà
della sua altezza. I dati lo confermano: 89.6° fra i due piani (atteso 90) e
`piano-h` a 7.34mm contro una mezzeria di `piano-1` a 7.89mm — scarto 0.56mm.
**I due mark sono coerenti fra loro e con l'oggetto: l'acquisizione non è in
causa.**

Un dato che invece merita attenzione: l'origine di ENTRAMBI i mark sta ~7.3mm
dal baricentro dei rispettivi punti, ed esattamente nel piano (0.0004 e 0.0001
mm fuori) — cioè sono state piazzate a mano col click, non lasciate al
centroide. Conta perché **tutto ciò che si costruisce al mark è centrato lì**:
un `(rect 20 19)` nasce a cavallo di quell'origine, non del gruppo di punti
cliccati.

### Lo strumento chiesto dal brief, ora costruito

`acquire` passa `:marks` al palcoscenico, che li disegna **come stanno scritti
nel sorgente**: piano (dischetto azzurro), origine, e i punti del `:from`.
Bottone "Mark" per accenderli/spegnerli. È il gemello di "il sorgente è l'unica
verità" — anche il display legge da lì, quindi una divergenza
emissione↔visualizzazione si vede subito invece di arrivare tre passi dopo come
geometria spiazzata. E risponde senza misure alla domanda che di solito causa
quell'impressione: DOV'È l'origine del mark.

Verificato: due mark → 6 item (disco+origine+punti ciascuno), vertici del disco
nel piano a 5e-15 mm, toggle 6↔0.

### Seguito 3, terzo giro: due difetti veri

**1. `(extrude … (f -h))` era rifiutato da una guardia su un angolo inesistente.**
Estrudere all'indietro è il modo naturale di far crescere un solido dall'altra
parte di un mark (la normale punta fuori, l'altro lato è semplicemente `f`
negativo) ed è la prima cosa che Vincenzo ha provato. La guardia di
realizzabilità degli spigoli confrontava il `:dist` CON SEGNO contro il miter
richiesto, quindi bocciava ogni segmento all'indietro — anche uno dritto e
solo, con `need` = 0, con un messaggio su un angolo che non c'era. Corretto:
la corsa che un segmento offre è la sua LUNGHEZZA; da che parte punta non è
affare di questa guardia. Un angolo che davvero non ci sta viene ancora
respinto in entrambi i versi (test).

Misurato che l'estrusione all'indietro è un solido sano: `(f 4)` occupa la
corsa 0→4, `(f -4)` occupa −4→0, stesso volume e **stesso segno del volume
orientato** — non è rovesciata (che nessun controllo di mesh vedrebbe).
`test/ridley/turtle/backward_extrude_test.cljs`.

**2. Movimento vincolato, sui tre assi del mark.** Frecce = sposta l'ORIGINE
**dentro** il piano (su/giù lungo l'`up` del mark, destra/sinistra lungo il suo
`right` = up×heading, la stessa convenzione con cui la geometria creata al mark
viene posata). **Shift+↑↓** = sposta il PIANO in profondità, lungo la normale.
0.25mm per passo.

La prima versione offriva solo la normale, per una lettura sbagliata della
richiesta: il bisogno vero era muovere sulla superficie, non attraverso. Le due
cose restano entrambe utili e sono tenute distinte perché *sono* distinte —
nel piano si sposta il punto da cui si misura, lungo la normale si sposta il
piano stesso; la HUD le nomina diversamente. Il motivo dietro la richiesta è giusto e vale la
pena scriverlo: una foto non osserva tutte le direzioni allo stesso modo — la
profondità lungo la propria linea di vista è quella che fissa peggio — quindi
poter muovere in UNA direzione dichiarata permette di giudicare la correzione
sulla vista che quella direzione la vede davvero, invece di trascinare in tre
dimensioni fidandosi di tutte allo stesso modo. E la normale è proprio la
direzione che un fit giusto d'orientamento ma mal posizionato sbaglia.

Implementato senza deriva: la posizione è ri-derivata ogni volta da una BASE
più lo scostamento accumulato, mai incrementata sul posto. Un refit azzera lo
scostamento (è una misura nuova, che supersede una correzione a mano) e la HUD
mostra sempre di quanto si è spostati. Verificato: +0.25 → +0.50 → −1.00 →
azzeramento tornano esatti, e solo la coordinata lungo la normale cambia.

**Non è un gizmo** — è la sua parte utile. Un gizmo a tre assi resta possibile
se serve trascinare anche nel piano.

**Difetto trovato al primo uso, e come l'ho lasciato passare.** Le frecce non
muovevano niente su un mark RIAPERTO: il contatore saliva, la HUD annunciava lo
spostamento, il piano stava fermo. Causa: `open-mark-edit!` posava `:candidate`
senza `:candidate-base-pos`, quindi `apply-offset!` non aveva da dove partire e
usciva in silenzio. Anche `recentre-origin!` spostava l'origine senza
ri-azzerare la base.

Il mio collaudo non l'aveva visto perché **avevo iniettato uno stato finto che
il campo lo conteneva già**: verificavo il calcolo e non il percorso che
costruisce lo stato. La lezione, generale: quando si collauda iniettando stato,
si sta collaudando ciò che sta a valle dell'iniezione — il codice che *produce*
quello stato resta fuori dal test. Ora la verifica passa da
`request-mark-edit!` + `open-pending-edit!` veri.

Rimedi: un solo `set-candidate-origin!` mantiene l'invariante (base = posizione
a scostamento zero) e ci passano tutti e tre i chiamanti; e `nudge-plane!`, se
la base manca, **lo dice** invece di non fare nulla.

### Seguito 3, quarto giro: l'`up` girato di 90° e il palcoscenico storto

Due sorprese segnalate da Vincenzo, con due risposte diverse.

**1. L'`up` di un mark su faccia verticale era ⊥ all'asse del giradischi.**
Difetto mio. `fit-plane-mark` riceve i candidati per l'up nell'ordine
`[(:up posa) (:heading posa)]` — giusto per un BOX, sbagliato per un PIATTO:
**il piatto ha l'asse del giradischi nel suo `:heading`** (plate.cljs costruisce
il cilindro lungo +Z con creation-pose identità), mentre il suo `:up` è una
direzione qualunque attraverso il disco. Su una faccia verticale quel primo
suggerimento è perfettamente utilizzabile e vinceva, girando il mark di 90°.
Corretto: con un piatto l'ordine è `[(:heading posa) (:up posa)]`. Verificato sui
punti veri di `piano-1`: up a **0.4°** dall'asse invece di 89.9°.

(Su una faccia ORIZZONTALE l'up resta ⊥ all'asse, e deve: lì l'asse è la
normale, non può stare anche nel piano.)

**2. Il palcoscenico storto rispetto alla turtle: nessuna ragione, un guardrail
che con un piatto non può scattare.** `canonicalize-orientation!` raddrizza il
sistema (asse giradischi → +Z) solo davanti a un ANELLO PULITO di camere:
≥4 camere e varianza minima < 0.15× la mediana. Sulla sessione
param-plate-paper, misurato:

```
  camere registrate 10
  varianze lungo gli assi del piatto: [17600.6, 20313.8, 6169.2]
  minima 6169.2, serve < 0.15 × 17600.6 = 2640.1   →  RIFIUTA
  ma l'asse a varianza minima È l'indice 2, cioè l'asse del piatto: quello giusto
```

Cioè: **individua correttamente l'asse e poi si rifiuta di usarlo**, perché le
foto sono state scattate da altezze diverse (σ≈78mm) e il test cerca un anello
planare da giradischi. Con un piatto la domanda non andrebbe nemmeno posta:
l'asse è quello del piatto, noto per costruzione.

NON corretto, perché è un cambio di FRAME e va deciso: raddrizzare cambia la
`:pose` emessa, quindi i mark-piano già scritti — coordinate mondo, conservate
byte per byte dal confirm chirurgico — resterebbero indietro. È la stessa
staleness già annotata sopra: la cura è il trasporto rigido delle voci
conservate, e le due cose vanno fatte insieme o non fatte.

## Quello che resta aperto

- **Ricollaudare la HUD dal vivo**: è verificata renderizzando stati finti in un
  browser vero (testo, stati dei bottoni, layout), non ancora usata in una
  sessione reale.
- Un mark-piano scritto dal palcoscenico vive SOLO nel sorgente. Se si rientra
  in `edit-acquire` e si riconferma, il confirm riemette la forma da
  `acquire-state.json` e **il mark-piano si perde**. Vale già oggi per
  qualsiasi modifica a mano della forma emessa, ma qui è più facile
  inciamparci. Se dà fastidio: persistere anche in `acquire-state.json`
  (serve invertire la riconciliazione emit→acq).
- Gradini 2 (click assistito) e 3 (omografia) restano fuori perimetro,
  come da brief.
- Se il gate passa: la guida utente cap. 19 §19.6 va riscritta attorno a
  questo gesto (nota di coda del brief).
