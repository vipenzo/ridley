# HANDOVER — la gabbia di registrazione

Aperto 2026-08-18. **Fetta 0 costruita e committata; il gate umano NON è stato
fatto.** Documento di governo: `dev-docs/brief-registration-ring.md`.

## In una riga

Il riferimento della fotogrammetria smette di stare SOTTO l'oggetto (il piatto)
e gli va INTORNO: tre anelli piatti ortogonali, il pezzo ancorato al centro,
ogni foto ben mirata e auto-registrata. È **l'ultimo esperimento prima
dell'archivio** dell'acquire, e il gate è spietato per scelta.

## Perché esiste

Il 2026-08-17 il primo caso reale è fallito. Vincenzo ha rinunciato all'acquire
su un pezzo di elettrodomestico rotto e l'ha rifatto con `edit-image-board`
senza problemi. La sua diagnosi, che è quella giusta: *«le tante foto
obbediscono a criteri boilerplate: servono a far star su il meccanismo, non a
disegnare l'oggetto»*.

Il concorrente dell'acquire non è "niente": è **image-board + calibro**. Per
pezzi piatti o con un piano dominante, una foto nelle condizioni migliori vince
meritatamente. L'acquire paga solo quando la PROFONDITÀ è il problema — e il
protocollo piatto+giradischi penalizzava proprio le foto che servono al disegno.

## Stato: cosa c'è

Tre commit sul branch `grab-and-register`:

- `053fd22` **fix(export)** — indipendente dalla gabbia. `(export :Grande :3mf)`
  restituiva `nil` senza fare niente; ora `export` rimette insieme le mesh
  registrate come vettore (`register` archivia `Nome/0`, `Nome/1`), il picker
  che rifiuta ripiega sullo scaricamento, e nessun ramo torna più nil in
  silenzio. Più `save-3mf-set-at` (N file, UNA domanda sulla cartella).
- `0779e69` **feat(registration-cage)** — il proxy, le guardie nel bridge e in
  edit-acquire, i test.
- `3db88fd` **feat(acquire-cage)** — la libreria di fabbricazione, il manuale,
  Roadmap e brief.

Suite: **947 test, 0 fallimenti**, 123 warning (invariati).

### La geometria, e perché è quella

- **⌀112 / ⌀144 / ⌀176**, fascia 12mm, gioco 4mm, spessore 3mm, apertura libera
  ⌀88. Tutto in FRAZIONI di `:d` come il piatto (così una gabbia di qualunque
  taglia, inquadrata a pieno campo, presenta al detector la stessa geometria in
  pixel); non scala solo lo spessore.
- **Tre anelli** perché per ogni direzione `max|v·asse| ≥ 1/√3`: almeno uno è
  visto a ≥35° dal suo piano, e da una direzione generica si vedono tutti e
  tre → mark NON complanari → torna in servizio il DLT, e il gemello specchiato
  dell'omografia planare non si presenta.
- **Diametri diversi** perché gli *anuli* hanno larghezza: due cerchi ortogonali
  di raggio diverso non si toccano mai, ma due anuli collidono se le fasce
  radiali si sovrappongono. Fasce disgiunte → nessun incastro, nessun intaglio,
  niente da forzare.
- **Identità degli anelli gratis dalla dimensione**: il rapporto fra due ellissi
  riprese insieme è invariante alla distanza. Niente colori né forme diverse (la
  proposta iniziale del brief decade).
- **Mark su ENTRAMBE le facce**, obbligatorio: marcandone una sola, una camera
  nell'ottante opposto non vede *un solo mark*.
- **Corone ruotate di mezzo passo** (`cage/crown-phase`): le sei linguette
  corrono lungo gli assi condivisi, e senza quella rotazione sette mark — uno
  dei quali uno zero-indice — cadevano esattamente sotto una linguetta.

### L'unica famiglia degenere, ed è nota

Sparando **esattamente lungo uno dei tre assi**, i due anelli che lo contengono
sono di taglio e i loro mark stanno oltre 3mm di plastica: si vede un anello
solo, cioè un piatto, gemello specchiato incluso. Dieci gradi fuori asse e
tornano tutti (misurato: 12 mark su 1 anello sull'asse, 36 su 3 anelli a 10°).

`bridge/camera-sees-marks?` la sorveglia — generalizza il vincolo fisico «quel
dischetto l'hai fotografato, quindi la camera stava davanti» da una faccia a
sei. In `edit-acquire` una soluzione che mette la camera dietro un dischetto
cliccato viene prima ritentata dall'allineamento a schermo, poi rifiutata per
nome. **Il ramo del piatto non è stato toccato.**

## Come si usa

```clojure
;; guardarla / esportarla
(register Grande (acquire-cage/make-cage-ring 176 :big))   ; :big :medium :small
(register Gabbia (acquire-cage/make-cage-ring 176))        ; :all, il default
(export :Grande :3mf)

;; oppure i tre file in un colpo (una sola domanda sulla cartella)
(acquire-cage/save-3mf 176 "~/Downloads")
(acquire-cage/save-cradle 176 "~/Downloads")

;; la sessione
(edit-acquire "/…/testina" {:proxy (registration-cage :d 176)})
;; dopo il calibro:
(edit-acquire "/…/testina" {:proxy (acquire-cage/measured 176 175.4)})
```

Un anello per file, non uno con sei oggetti: in uno slicer un anello e i suoi
dischetti restano oggetti distinti, e uno spostato senza l'altro dà un pezzo che
si stampa benissimo e va buttato.

## IL GATE — è questo che manca

Vincenzo sta stampando i tre file (`~/Downloads/gabbia-176-{big,medium,small}.3mf`).
Poi va montata, e poi si scatta. Il confronto è col risultato image-board **già
fatto** sullo stesso pezzo di elettrodomestico. Cinque foto mirate + una da
dietro (la promessa nuova).

1. preparazione, ancoraggio incluso, sotto i 10 minuti?
2. **si registra** ogni foto, compresa quella dal retro? (a mano col tasto `p`:
   il detector automatico è DELIBERATAMENTE rinviato, vedi sotto)
3. il ricalco batte l'image-board in fedeltà, o la eguaglia con la profondità in
   più?
4. le bande di occlusione hanno impedito qualche vista che serviva?

**Se NO alle prime tre → l'acquire si archivia**, con gli asset che restano al
progetto. Se SÌ → la forma giusta del canale è *image-board registrate*, e il
cap. 19 del manuale si scrive attorno a quel flusso.

### Il montaggio (da fare prima del gate)

Sei giunti incollati con **epossidica**. I quattro dell'anello piccolo hanno la
battuta: si spinge finché non si ferma. I due fra medio e grande no, e non è una
dimenticanza — una battuta ferma qualcosa solo se attraversa il piano
dell'anello, ed è esattamente la direzione da cui l'anello entra: lì avrebbe
bloccato il montaggio invece dell'anello. Quei due si allineano a occhio, punta
della linguetta a filo del bordo esterno.

Ordine: prima i due anelli grandi, poi il pezzo al centro, poi l'anello piccolo
(porta quattro linguette su sei).

**Ancoraggio del pezzo**: il vincolo è SOLO la rigidità durante la sessione — la
posizione nella gabbia non viene mai assunta, solo usata come frame. Quindi il
supporto può essere arbitrario, ma non cedevole: elastici no (immagazzinano
energia e spostano il pezzo appena la gravità gira), fili di nylon no (tirano
soltanto, e metà si allentano a ogni capovolgimento). Aste sì — spiedini di
bambù che convergono, o un montante rigido, con pongo adesivo sulla punta. Se
il pezzo si muove **non è silenzioso**: i mark continuano a concordare fra le
viste, i ricalchi no.

## Cosa NON è stato fatto, e perché

- **Il detector automatico (multi-ellisse) è rinviato di proposito.** Tre delle
  quattro domande del gate si rispondono cliccando i mark a mano; se la gabbia
  non batte l'image-board si archivia senza aver scritto una riga di quel
  codice. Quando servirà: `match-plate/crown-ring-hypotheses` restituisce già le
  top-5 ellissi in classifica — quel che va tolto è l'assunto `ring-slack`
  («la corona è l'anello più grosso, il resto è spazzatura»), che con tre anelli
  è falso. L'identità dell'anello si risolve per coerenza incrociata
  (riproiettare gli altri anelli e vedere quale ipotesi li fa cadere su blob
  rilevati) oltre che per dimensione.
- **Calibrazione della gabbia**: `plate-calib` (tasto `C`) è ancora
  piatto-specifico. Sulla gabbia la triangolazione dei mark sarebbe MEGLIO
  condizionata (punti non complanari), e il vincolo di gauge è più semplice —
  un solo fattore di scala, non due. Da generalizzare se il gate passa.
- **Nessuna verifica visiva automatica**: le mesh in Ridley si vedono solo se
  REGISTRATE (`extract-render-data` non mostra le mesh di extrude/loft da sole).
- **La faccia superiore** degli anelli ha i bordi meno netti di quella contro il
  piano di stampa; è inevitabile in una stampata sola. L'upgrade, se servisse:
  ogni anello in due metà da 1.5mm, ciascuna coi mark verso il piano, incollate
  schiena contro schiena.

## Trappole del banco di prova (costate mezza giornata)

- **Playwright MCP e il browser dell'utente possono essere LO STESSO**, e
  Playwright dirotta gli scaricamenti in `<repo>/.playwright-mcp/`, non in
  `~/Downloads`. Prima di indagare qualunque percorso di salvataggio, guardare
  lì. Il segno rivelatore: i `console.log` dell'agente compaiono nella console
  dell'utente.
- **Non fingere il meccanismo sospetto.** Ho sostituito `showSaveFilePicker` con
  un finto che riusciva sempre, e per tre round ho dichiarato "verificato dal
  vivo" mentre all'utente non arrivava niente. Un mock serve a esercitare il
  RAMO DI FALLIMENTO, non a dichiarare che il percorso funziona.
- `showSaveFilePicker` rifiuta **sempre** se chiamato dalla console devtools: non
  c'è nessun gesto utente dietro. Non è un difetto.
- **Il ricaricamento a caldo salta**, e una build rotta lascia servire il bundle
  vecchio: leggere il log di `npm run dev`, e verificare `fn.toString()` prima
  di credere a un collaudo.
- `box` prende **(destra, su, avanti)**, non (x, y, z): le linguette uscivano
  ruotate di 90°, e a dirlo è stato misurare l'ingombro del pezzo prodotto, non
  rileggere il codice.

## File

- `src/ridley/photogrammetry/cage.cljs` — il proxy: `registration-cage`,
  `ring-radii`, `joint-tabs`, `crown-phase`, `aperture`
- `src/ridley/photogrammetry/bridge.cljs` — `index-anchor?`,
  `camera-sees-marks?`, culling per-mark dichiarato
- `src/ridley/editor/edit_acquire.cljs` — la guardia nel ramo `p` (cerca
  `per-mark-faces?`)
- `public/builtin-libraries/acquire-cage.clj` — `make-cage-ring`, `files`,
  `save-3mf`, `cradle`, `measured`
- `test/ridley/photogrammetry/cage_test.cljs` — fasce disgiunte, sei corone,
  linguette e battute, nessun mark sotto una linguetta, posa recuperata da
  cinque direzioni, degenerazione sull'asse
- `docs/manual/reference/en/registration-cage.md`

## Nota per il manuale, valida in entrambi gli esiti del gate

La guida deve guidare con onestà: per pezzi piatti la strada maestra è
`edit-image-board` + calibro; l'acquire è lo strumento per quando la profondità
è il problema. Il cap. 19 va impostato su questa gerarchia, non sull'acquire
come default.
