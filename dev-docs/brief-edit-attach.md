# Brief: edit-attach — rename di pilot, riapertura degli attach, voce di menu Edit

Data: 2026-07-08. Stato: da implementare. Prerequisito per il brief successivo sugli handles di viewport (brief-edit-attach-handles.md, non ancora scritto).

## Contesto

Tutti i modal evaluator della famiglia seguono la grammatica "edit-X si riscrive in X alla conferma", e X è ri-editabile riapplicando il prefisso: edit-path ↔ path, edit-path-2d ↔ path-2d, edit-image-board ↔ image-board. Pilot è l'anomalia: produce `(attach expr (path cmds))` ma non si chiama edit-attach e non accetta comandi preesistenti, quindi il round-trip è a senso unico. Questo brief completa il pattern: rename in edit-attach, precaricamento dei comandi esistenti, e una voce di menu "Edit" che rende la grammatica un gesto.

Fatti verificati a sorgente, su cui il lavoro poggia:

- La macro pilot è in `src/ridley/editor/macros.cljs` (circa riga 2086): `(defmacro pilot [arg] \`(pilot-request! '~arg ~arg))`. Il modulo è `src/ridley/editor/pilot_mode.cljs`, registrato in modal-evaluator con kind `:pilot` e hook requested?/enter!/active?/cancel!/close!.
- La macro attach è `(defmacro attach [mesh & body])` (macros.cljs circa riga 811) e wrappa da sé il body in `(path ~@body)`. La forma canonica utente è quindi flat: `(attach cubo (f 10) (th 45))`, non `(attach cubo (path ...))`.
- `build-replacement-code` in pilot_mode.cljs genera invece `(attach expr (path cmds))` con un `(path ...)` interno, con un commento che lo motiva ("to ensure commands like scale/cp-f are properly scoped inside a path context"). Poiché attach wrappa già il body in un path, il wrapper interno è probabilmente ridondanza storica. Va verificato, non assunto: vedi accertamento A1.
- `request!` di pilot trova la form nel sorgente con `indexOf` del testo esatto `"(pilot arg)"`. Funziona perché l'argomento è unico e su una riga; con un body arbitrario (multi-form, multi-riga) il matching a stringa esatta non regge.
- Il cancel! attuale di pilot lascia `(pilot expr)` nel sorgente, arma lo skip flag per una sola re-eval, e il run manuale successivo riapre la sessione a sorpresa. Lo stesso comportamento è presente in edit-path e edit-image-board (verificato: entrambi i cancel! fanno arm-skip! + run-definitions! senza toccare il sorgente). Questo brief introduce la regola per edit-attach e la estende all'intera famiglia head-rename (edit-path, edit-path-2d, edit-image-board), per decisione del 2026-07-08: la mina è la stessa e si cura una volta sola, accettando il cambio di un comportamento rilasciato.
- Il menu contestuale dell'editor è `editor-context-menu-items` in `src/ridley/core.cljs` (circa riga 2845), ricalcolato a ogni apertura; la voce Tweak è `{:label "Tweak" :enabled? sel? :run #(tweak-mode/tweak-selection!)}`.
- CodeMirror espone già gli helper necessari: `get-form-at-cursor` (ritorna `{:from :to :text}` della form che contiene il cursore) e `parse-form-elements` per spezzare una form nei suoi elementi testuali.
- Il vocabolario cp-* è completo (traslazioni e rotazioni, mesh e SDF) e replayed in `editor/impl.cljs`; non è materia di questo brief ma del successivo, si cita per contesto.

## Accertamenti preliminari (da fare prima di scrivere codice, risultati nel report)

A1. Verificare in REPL che la forma flat funziona per l'intero vocabolario che pilot emette: `(attach cubo (f 10) (stretch-f 1.2) (cp-f 5) (th 45))` senza `(path ...)` interno deve produrre lo stesso risultato della forma col wrapper. Se la verifica fallisce, capire perché il commento storico aveva ragione e mantenere il wrapper interno nell'output (il resto del brief non cambia).

A2. Verificare che una form `(attach cubo (path (f 10)))` con path interno (i sorgenti generati dal pilot attuale) continui a valutare correttamente, perché edit-attach la incontrerà come body preesistente e la deve preservare verbatim.

## Lavoro richiesto

### 1. Rename del modulo e della macro

- `src/ridley/editor/pilot_mode.cljs` → `src/ridley/editor/edit_attach.cljs`, namespace `ridley.editor.edit-attach`, kind modal-evaluator `:edit-attach`. Aggiornare i punti di registrazione e gli require (core.cljs, bindings/esposizione SCI, ovunque `pilot-mode` compaia).
- Nuova macro in macros.cljs: `(defmacro edit-attach [mesh & body])`. La macro passa a request! tre cose: la source form quotata della mesh (come oggi), il valore valutato della mesh, e il testo sorgente del body (le form dei comandi preesistenti, non valutate). Nota: durante la prima eval il body NON va eseguito due volte; la macro si espande in modo che l'eval corrente produca il risultato dell'attach completo (mesh + body), così la scena mostra lo stato attuale, e la sessione parte da lì. La forma più semplice: espandere in `(edit-attach-request! '~mesh ~mesh '~body (attach ~mesh ~@body))` o equivalente, dove il quarto argomento è il valore corrente da mostrare e da cui leggere la creation-pose. Decidere il dettaglio in implementazione, il vincolo è: una sola valutazione del body, preview iniziale = attach applicato.
- Alias retrocompatibile: `(defmacro pilot [arg])` delega a edit-attach con body vuoto. Docstring che lo marca come alias legacy.

### 2. Localizzazione robusta della form nel sorgente

Sostituire l'indexOf a stringa esatta con un matching a bilanciamento di parentesi: cercare `"(edit-attach"` (e `"(pilot"` per l'alias) e chiudere la form contando i delimitatori, alla maniera di find-marker di edit-image-board o di get-form-at-cursor. Il matching deve tollerare newline e spaziatura arbitraria nel body. Se nel sorgente esistono più occorrenze, vale la prima non ancora consumata nell'eval corrente (stesso criterio degli altri editor; verificare come find-marker disambigua e riusare quel criterio).

### 3. Precaricamento dei comandi preesistenti come item verbatim

- Il body preesistente viene spezzato in form testuali top-level (una per comando: `(f 10)`, `(arc-h 90 20)`, `(mark :a)`, ...) usando parse-form-elements o equivalente. Ogni form entra nella lista comandi della sessione come item verbatim: una stringa sorgente opaca che la sessione non interpreta.
- La lista comandi della sessione diventa eterogenea: item verbatim (stringhe) e item gestuali (le coppie `[cmd val]` attuali). `commands->code-str` giustappone: i verbatim passano com'è, i gestuali si serializzano come oggi.
- `compact-commands` fonde solo item gestuali consecutivi; un verbatim interrompe la catena di fusione e non viene mai toccato.
- Undo (Backspace e bottone) poppa l'ultimo item indipendentemente dal tipo: poppare un `(arc-h 90 20)` preesistente è legittimo quanto poppare un `(f 5)` appena aggiunto. Il pannello mostra la lista completa (verbatim inclusi), quindi l'utente vede sempre cosa sta per perdere.
- Il re-eval durante la sessione usa la lista completa (verbatim + gesti), quindi la preview riflette sempre lo stato reale.

### 4. Output alla conferma

- Forma flat canonica: `(attach mesh-expr cmd1 cmd2 ...)`. Il wrapper `(path ...)` interno del pilot attuale è ritenuto ridondante, perché attach wrappa già da sé il body in un path; A1 resta come verifica pre-volo perché costa un minuto e il commento storico potrebbe aver avuto una ragione oggi non visibile. Se A1 smentisce l'ipotesi, fermarsi e riportare l'evidenza prima di ripiegare sul wrapper.
- Con lista comandi vuota (tutto poppato, o pilot nudo annullato con Ok): riscrittura nella sola `mesh-expr`, come oggi.
- REPL mode invariato: stampa dell'espressione, nessuna riscrittura.

### 5. Cancel che strippa il prefisso

- In script mode, cancel! riscrive la form nel sorgente: `(edit-attach mesh body...)` → `(attach mesh body...)` con il body originale intatto; `(edit-attach mesh)` senza body (e l'alias `(pilot mesh)`) → la sola `mesh`. Poi run-definitions, senza skip flag: al run successivo non c'è più nessuna sessione da riaprire, perché la form edit- non esiste più.
- Questo elimina la mina del pilot attuale (cancel + run successivo = sessione a sorpresa). L'alias pilot adotta il comportamento nuovo: il comportamento legacy non è un contratto da preservare, è un difetto noto.
- Estensione alla famiglia (decisione 2026-07-08): stessa regola in edit-path, edit-path-2d, edit-image-board. Cancel riscrive il solo simbolo di testa (edit-path → path, edit-path-2d → path-2d, edit-image-board → image-board) lasciando il body carattere per carattere com'era all'apertura della sessione, poi run-definitions senza skip flag. Attenzione: il body da preservare è quello originale all'ingresso, non lo stato corrente dell'editing; oggi il sorgente non viene toccato durante la sessione quindi dovrebbe bastare il rename della testa, ma va verificato editor per editor che nessuno riscriva il sorgente prima della conferma.
- Accertamento A3 contestuale: verificare che le forme minime strippate siano valide, in particolare `(image-board PATH)` col solo path (l'ingresso tipico è `(edit-image-board PATH)` senza calibrazione) e `(path)` con body vuoto. Se una forma minima non valuta, documentare nel report la riscrittura di ripiego scelta per quel caso invece di inventarla in silenzio.
- edit-bezier resta fuori da questa regola: il suo confirm-target è bezier-to con argomenti calcolati, non un head-rename, quindi il cancel-strip non ha una forma ovvia. Comportamento invariato.
- In REPL mode cancel resta come oggi (nessun sorgente da toccare).

### 6. Voce di menu "Edit"

- In `editor-context-menu-items` (core.cljs), nuova voce "Edit" accanto a "Tweak".
- Meccanismo a tabella, non a prefisso cieco: `{"path" "edit-path", "path-2d" "edit-path-2d", "image-board" "edit-image-board", "attach" "edit-attach"}`. La tabella è il punto di estensione: il primo candidato futuro (bezier-to → edit-bezier) rompe già la regola del prefisso, e con la tabella entra con una riga.
- Abilitazione: la voce è enabled quando `get-form-at-cursor` trova una form il cui simbolo di testa è una chiave della tabella. Su form già edit-* la voce è disabled. Nessuna dipendenza dalla selezione: basta il cursore dentro la form (diverso da Tweak, che richiede selezione perché avvolge; qui si riscrive solo la testa).
- Azione: riscrivere il simbolo di testa col valore della tabella (replace-range sui soli caratteri della testa, non dell'intera form) e rilanciare le definitions. La sessione si apre da sé al run, per il flusso two-phase già esistente.
- Guardia: la voce è disabled se un modal evaluator è già attivo (mutex claim), per non provocare il conflitto di sessioni concorrenti.

## Verifica

- Round-trip identità: `(register x (edit-attach cubo (f 10) (arc-h 90 20) (mark :a) (stretch-f 1.2)))` aperto e confermato con Ok senza nessun gesto riscrive `(attach cubo (f 10) (arc-h 90 20) (mark :a) (stretch-f 1.2))` con il body carattere per carattere identico all'originale.
- Riapertura: sulla riga risultante, menu Edit → la testa torna edit-attach, il run apre la sessione, il pannello mostra i quattro comandi preesistenti, la preview corrisponde alla scena.
- Append dopo verbatim: nella sessione riaperta, due frecce (o gesti) aggiungono comandi in coda; Ok produce il body originale seguito dai nuovi comandi, compattati solo tra loro.
- Undo attraverso il confine: nella sessione riaperta con un gesto aggiunto, tre Backspace rimuovono il gesto, poi `(stretch-f 1.2)`, poi `(mark :a)`; la preview segue a ogni passo.
- Cancel: su `(edit-attach cubo (f 10))` Esc lascia `(attach cubo (f 10))`; su `(pilot cubo)` Esc lascia `cubo`; in entrambi i casi il run manuale successivo NON riapre nessuna sessione.
- Alias: `(pilot cubo)` apre la sessione come oggi, Ok produce l'attach flat.
- Menu: Edit abilitato con cursore dentro `(attach ...)`, `(path ...)`, `(path-2d ...)`, `(image-board ...)`; disabilitato dentro `(box 10)`, dentro form già edit-*, e con una sessione modale attiva.
- Body multi-riga: una form edit-attach col body distribuito su tre righe viene trovata, aperta e riscritta correttamente (test del matching a bilanciamento).
- Cancel di famiglia: `(edit-path (f 30) (th 45))` con nodi editati, Esc → `(path (f 30) (th 45))` col body originale intatto e nessuna sessione al run successivo; analogo per edit-path-2d e per edit-image-board, incluso il caso minimo `(edit-image-board PATH)` subordinato ad A3.
- REPL mode: `(edit-attach cubo (f 10))` da REPL si comporta come il pilot REPL attuale (sessione con preview, Ok stampa l'espressione senza toccare l'editor).
- SDF: `(edit-attach sdf-node)` continua a funzionare come `(pilot sdf-node)` oggi.

## Fuori scope

- Gli handles di viewport (frecce, anelli, stretch, toggle oggetto/origin): brief successivo, che assume questo già atterrato.
- Il cancel di edit-bezier (confirm-target bezier-to, non head-rename): se durante il lavoro emerge una semantica ovvia, loggarla in dev-docs/code-issues.md invece di implementarla qui.
- La voce di tabella bezier-to → edit-bezier nel menu: la tabella la predispone, l'attivazione richiede di verificare che edit-bezier sappia fare da editor di una bezier-to arbitraria già scritta, che oggi non è garantito.
- Il "simplify" della storia dei comandi alla conferma (sostituzione con la decomposizione canonica del delta netto): discusso e rimandato, vedi alternative rigettate del brief handles quando verrà scritto.
- L'aggiornamento di Architecture.md (11.2.3 e le altre menzioni di pilot): a valle dell'implementazione, non parte di questo brief.

## Alternative rigettate

- Parsare i comandi preesistenti nella rappresentazione interna della sessione invece che tenerli verbatim: rigettata. Richiederebbe che edit-attach conosca l'intero DSL dei path (arc, bezier, mark, move-to, side-trip, ...), cioè un secondo interprete da tenere allineato al primo. Gli item verbatim preservano tutto per costruzione e il costo è solo una lista eterogenea.
- Prefisso "edit-" applicato ciecamente dal menu invece della tabella: rigettata. La forma prodotta da edit-bezier è bezier-to, e il suo editor non si chiama edit-bezier-to; il primo candidato futuro rompe già la regola.
- Conservare il comportamento legacy del cancel sull'alias pilot: rigettata. Il comportamento attuale (form intatta + skip flag monouso + sessione a sorpresa al run successivo) è un difetto documentato, non un contratto.
- Richiedere la selezione per abilitare la voce Edit, come fa Tweak: rigettata. Tweak avvolge la selezione, quindi la selezione è l'input; Edit riscrive solo la testa della form che contiene il cursore, e get-form-at-cursor è sufficiente e meno macchinoso per l'utente.
- Mantenere il nome pilot come nome primario e aggiungere edit-attach come alias: rigettata. Il nome primario deve seguire la grammatica della famiglia (edit-X ↔ X); pilot resta come alias per i sorgenti esistenti e per l'affetto.
