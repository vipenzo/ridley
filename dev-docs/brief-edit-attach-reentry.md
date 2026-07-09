# Brief: glitch di rientranza di edit-attach — diagnosi con cancello, poi fix

Data: 2026-07-09. Stato: da implementare. Fonte: entry "edit-attach: il primo rientro dopo un OK non apre la sessione" in dev-docs/code-issues.md. Questo brief è in due fasi separate da un cancello: la Fase 1 produce un report intermedio con l'evidenza sul meccanismo, e la Fase 2 (il fix) parte solo dopo, sul meccanismo confermato. La falsificazione del sospetto attuale è un esito valido della Fase 1.

## Contesto

Sintomo (dalla entry): confermando con OK una sessione edit-attach e reinvocandola subito dopo (stesso mesh o un altro), il primo tentativo di riapertura non fa nulla — nessun pannello, nessun indicatore turtle, nessun errore in console. Il secondo tentativo funziona. Il gizmo è scagionato: pannello e indicatore non dipendono da gizmo.cljs e mancano anch'essi, quindi la sessione non si apre proprio e il codice del gizmo non viene mai eseguito.

Sospetto registrato, non verificato: una race tra la coda di confirm! (modal/release!, reset! della sessione, run-definitions! per il re-eval completo) e la request! del successivo edit-attach — in particolare clear-orphan-state! o modal/claim! che girerebbero prima che il re-eval abbia sistemato state/interactive-mode, con claim fallito in silenzio.

Nota sul silenzio: qualunque sia il meccanismo, il fallimento senza traccia è parte del danno — ha reso il glitch non diagnosticabile sul posto. La Fase 2 include il rimedio a questo, indipendentemente dalla causa.

## Fase 1: diagnosi (report intermedio prima di qualsiasi fix)

D1. Riproduzione caratterizzata. Sequenza minima (OK, reinvoco subito) su un modello piccolo e su uno pesante: se la frequenza del fallimento cresce con la durata del re-eval, è un indizio forte di race; se il fallimento è deterministico anche su eval istantanee, il sospetto race perde quota e va cercato un meccanismo di stato (flag residuo, mutex non rilasciato, stato orfano). Annotare anche se il glitch richiede l'immediatezza o sopravvive a una pausa tra OK e rientro.

D2. Tracciamento dell'ordine reale degli eventi. Logging temporaneo attorno a: modal/claim! (esito incluso), modal/release!, clear-orphan-state!, state/interactive-mode (letture e scritture rilevanti), l'ingresso in request! e il punto in cui la catena si interrompe sul tentativo fallito. Confrontare la traccia del tentativo fallito con quella del tentativo riuscito: la differenza tra le due È il meccanismo. Candidati da tenere aperti senza sposarne uno: claim rifiutato per mutex non ancora rilasciato o stato interactive-mode stantio; request! mai raggiunta (la macro non arriva a chiamarla); un flag di skip residuo di qualche percorso; clear-orphan-state! che pulisce troppo (uccide il claim appena fatto) o troppo poco (lascia stato che blocca il successivo).

D3. Perimetro del difetto. Verificare se il glitch esiste anche: dopo cancel (che ora fa anch'esso rewrite + run-definitions, stessa coda di confirm!); dopo l'OK degli altri editor della famiglia (edit-path, edit-image-board), che condividono lo scheletro modal_evaluator; rientrando via menu Edit invece che da run manuale. Se il difetto è di famiglia, la causa e il fix stanno nello scheletro, non in edit_attach.cljs, e la Fase 2 va indirizzata lì.

Esito della Fase 1: report con la traccia degli eventi, il meccanismo confermato (o il sospetto falsificato e il meccanismo alternativo trovato), e il perimetro D3. Il fix si concorda su quell'evidenza.

## Fase 2: fix (dopo il cancello)

- Il fix agisce sul meccanismo confermato dalla Fase 1. Requisito negativo esplicito: niente rimedi temporali (sleep, delay, riprova-dopo-N-ms). Se la cura funziona solo spostando il timing, non è la cura: un fix il cui meccanismo non può in principio toccare la causa è un no-op, e con le race i no-op sembrano funzionare finché il timing non cambia di nuovo.
- Rumore sul fallimento: dopo il fix, un claim! che fallisce quando nessuna sessione è legittimamente attiva deve loggare un warning in console (il fallimento per mutex con sessione attiva resta silenzioso: è il funzionamento normale della guardia). Questo vale qualunque sia stato il meccanismo: il prossimo difetto di questa famiglia deve lasciare una traccia.
- Il logging diagnostico temporaneo della Fase 1 si rimuove; il warning del punto sopra resta.

## Verifica

- Sequenza OK → rientro immediato: la sessione si apre al primo colpo, ripetuto almeno 10 volte, su stesso mesh, su mesh diverso, su un nodo SDF.
- Stesso test dopo cancel invece di OK, e rientrando via menu Edit invece che da run.
- Se D3 ha rivelato che il difetto è di famiglia: stesso test su edit-path ed edit-image-board.
- Nessuna regressione sul mutex: con una sessione attiva, un secondo edit-attach non parte e la voce di menu Edit resta disabled; il warning non compare in questo caso.
- Modello pesante: la sequenza regge anche quando il re-eval è lungo (il caso che allargava la finestra, se di race si trattava).
- Suite `npm test` verde; se il meccanismo lo consente, un test di regressione automatico sul ciclo release/claim (se richiede il browser e non è praticabile in suite, dirlo nel report e coprire con la checklist manuale sopra).
- Aggiornare la entry in code-issues.md: meccanismo trovato, fix, data; spostarla sotto "Chiuso".

## Fuori scope

- Refactor generale del lifecycle di modal_evaluator oltre il necessario per il fix.
- Documentazione (Architecture.md sul lifecycle delle sessioni): nel passaggio documentale a valle dell'arco edit-attach.

## Alternative rigettate

- Fixare a tentativi sul sospetto senza la Fase 1: rigettata. Il sospetto è plausibile ma dichiaratamente non verificato, e le race si prestano ai fix-placebo; il costo della diagnosi strumentata è un'ora, il costo di un placebo è il ritorno del glitch tra un mese con il timing cambiato.
- Documentare "riaprire due volte" come comportamento noto: rigettata, è il workaround della entry, non una cura.
- Delay o retry temporizzati: rigettata in Fase 2 per requisito esplicito, vedi sopra.
- Lasciare silenzioso il claim fallito fuori mutex: rigettata, il silenzio è ciò che ha reso questo difetto costoso da diagnosticare.
