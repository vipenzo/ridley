# Addendum 4 — "finito" è una decisione dell'utente, non un fatto geometrico

Riguarda la semantica di chiusura di `edit-mesh-split` (brief albero e successivi). **Norma che questo addendum fissa**: la convessità è un *segnale* (il semaforo), la finitezza è una *decisione* (dell'utente, informata dal segnale). Nessun gate geometrico sull'uscita, mai.

## Contesto

Problema dall'uso reale (Vincenzo): la sessione esce solo quando tutte le foglie sono convesse — situazione **irraggiungibile per alcune mesh** (superfici curve concave: tori, raccordi interni, gusci; producono foglie rosse per sempre o polverizzazione infinita). Il gate era una coincidenza del mondo Level A (dove la conversione convessa era lo scopo); il workflow mesh-board lo rende insensato: una foglia concava è un cittadino legittimo — verrà ricostruita da uno script, e della sua convessità non importa a nessuno.

Nota storica: il modello lineare aveva già la valvola (accettare un `behind` concavo = permesso con warning; Ctrl-Enter = uscita anticipata con remaining concavo, per design). Se l'implementazione corrente gate-a l'uscita sul tutto-verde, è una deriva del refactor ad albero, dove "chiuso" è diventato di fatto sinonimo di "verde". Questo addendum corregge la deriva e promuove la semantica a norma.

## Lavoro richiesto

### Parte A — Gesto "accetta così com'è" (per foglia)

- Tasto + bottone (convenzioni addendum 3: ragione scritta, mai silenzio) sul **pezzo corrente**: lo dichiara **finito per decisione**, qualunque sia il suo colore.
- Su un pezzo rosso: warning visibile o conferma esplicita che nomina il fatto ("il pezzo è concavo / ha N componenti concave — accettarlo così com'è?"), mai blocco, mai silenzio. Su un pezzo verde il gesto è equivalente alla chiusura naturale (nessuna conferma).
- È un **gesto strutturale**: entra nel log cronologico, si annulla con undo (il pezzo torna aperto).
- Il pezzo accettato esce dal ciclo `n`/`p`, si ghosta con la sua etichetta ed entra nei conteggi come finito — identico a una foglia verde. Nessuno stato visivo speciale permanente: finito è finito (l'albero della vista processo può distinguere internamente `:done-by-decision` se costa zero, ma nessun requisito).

### Parte B — Chiusura di sessione sempre disponibile

- Il **commit non ha precondizioni geometriche**: disponibile in ogni momento. Se ci sono pezzi ancora aperti, la conferma li **elenca** ("chiudi con 2 pezzi aperti che diventano foglie così come sono: piece-3 (rosso), piece-5 (2 componenti)?") — informata, mai bloccante, mai silenziosa.
- Il tutto-verde perde ogni ruolo di gate e mantiene il ruolo originario di **suggerimento** ("tutto finito, puoi chiudere").
- L'emissione è invariata: i pezzi sono pezzi, aperti-diventati-foglie compresi. Nessun commento speciale, nessun formato nuovo (la finitezza-per-decisione non interessa a nessun consumatore a valle: il board lavora su qualunque foglia).

### Parte C — Coerenza con le viste e il resto

- Vista processo: i finiti-per-decisione contano come finiti; il ciclo `n`/`p` e la vista contesto seguono (il corrente passa al prossimo aperto dopo il gesto).
- Ctrl-Enter (zucchero del piazzamento terminale) resta com'è: era una via legittima e lo rimane; il gesto della Parte A è la forma diretta dello stesso intento senza ginnastica del piano.
- Verificare che nessun altro punto del codice assuma "chiuso ⇒ convesso" (ricerca esplicita, non assunzione).

## Verifica

- Mesh genuinamente non-convessificabile (toro tassellato): sessione aperta → "accetta così com'è" subito → commit → forma emessa valida, round-trip invariato. Il caso che oggi è impossibile deve diventare un flusso di due gesti.
- Sessione mista (pezzi verdi + un rosso accettato per decisione): conteggi corretti, il deciso esce dal ciclo, undo lo riapre, commit con conferma che elenca correttamente.
- Commit con pezzi ancora aperti: la conferma elenca nomi e stati; annullare la conferma non tocca nulla; confermare emette con gli aperti come foglie.
- Il warning sull'accettazione di un rosso è presente e non-silente (test di comportamento, non solo di presenza del testo).
- Nessun cambiamento al formato emesso; suite verde; ricerca "chiuso ⇒ convesso" riportata nel report.

## Fuori scope

- Qualsiasi rilassamento del semaforo (epsilon, quasi-verde): falsificato da A4, resta falsificato — il segnale dice il vero, è il gate che era sbagliato.
- Stati o metadati di emissione per le foglie accettate-per-decisione: nessun consumatore li richiede.

## Alternative considerate e scartate

- **Mantenere il gate con Ctrl-Enter come unica valvola**: scartato — la valvola esiste ma è indiretta (ginnastica del piano per pezzo) e non scala sull'albero; il problema è concettuale, non ergonomico: nessun gate geometrico ha diritto di esistere sull'uscita.
- **Auto-accettare i rossi al commit senza conferma**: scartato — mai silenzio su una decisione che accetta concavità; la conferma con elenco è il compromesso tra fluidità e consapevolezza.
- **Stato visivo permanente "accettato concavo"**: scartato — finito è finito; la distinzione non serve a nessun flusso a valle e aggiungerebbe una categoria visiva al sistema a due stati appena ripulito.
