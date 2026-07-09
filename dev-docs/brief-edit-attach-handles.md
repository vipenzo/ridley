# Brief: handles di viewport per edit-attach

Data: 2026-07-08. Stato: da implementare. Prerequisito: brief-edit-attach.md e brief-image-board-defaults.md, entrambi atterrati. Questo brief aggiunge la manipolazione diretta via mouse alla sessione edit-attach, mantenendo la tastiera come layer di precisione.

## Contesto

Il keyboard pilot (ora edit-attach) è risultato poco ergonomico all'uso: l'intenzione dell'utente è spaziale e olistica ("lo voglio lì, girato così") ma la tastiera lo costringe a decomporla in quanti frame-relative, che è il lavoro mentale che la manipolazione diretta dovrebbe risparmiare. Il drag inverte la direzione: l'utente esprime il risultato, il sistema decompone in comandi.

L'intuizione che guida il design: il gizmo è il frame della turtle reso visibile. Un gizmo posizionato sulla creation-pose e orientato sui suoi assi locali (heading, right, up), non sugli assi mondo, ha una traduzione uno-a-uno tra gesti e comandi esistenti: drag lungo la freccia heading → `(f d)`, right → `(rt d)`, up → `(u d)`; drag su un anello → `(th a)` / `(tv a)` / `(tr a)`; maniglie di stretch per asse → `(stretch-f k)` / `(stretch-rt k)` / `(stretch-u k)`. L'output della sessione resta identico: lista comandi, compattazione, riscrittura in `(attach mesh cmds...)` flat. Cambia solo il dispositivo di input. Effetto collaterale voluto: l'utente che dragga e vede apparire `(f 15)` nel pannello impara il DSL guardando.

Fatti verificati a sorgente, su cui il lavoro poggia:

- edit-path fa già drag 3D di nodi nel viewport con il pattern completo: hit-test in screen space via `world->screen`, piano di drag ancorato alla profondità del punto via `raycast-plane-point` con drag-anchor, Shift+drag come axis-lock (drag vincolato, la meccanica di una freccia di gizmo), orbit disabilitato solo durante il grab via `set-controls-enabled!` (drag sul vuoto orbita), push di undo all'inizio del gesto (un drag = un passo di undo). Il pattern va preso in prestito da lì, non reinventato.
- edit-image-board ha il pattern di preview economica: durante il drag aggiornamento visivo throttled senza re-eval, re-eval completo al pointer-up.
- Il viewport espone `lock-interaction!` (soppressione di Alt+click picking e Shift+click measure durante le sessioni, già usata da edit-path), `raycast-plane-point` (origin e normal nel frame world-group-local), `world->screen`, `set-controls-enabled!`, `get-canvas`.
- Il vocabolario cp-* è completo (cp-f/cp-rt/cp-u e cp-th/cp-tv/cp-tr), replayed in editor/impl.cljs, sia mesh sia SDF. Semantica del segno verificata: dopo `(cp-f n)` la pose risulta avanzata di n relativamente alla geometria (i vertici traslano di −n nel mondo, la pose resta ferma). Il modello mentale è "muovi la pose", quindi in modalità origin il gizmo emette il delta del drag così com'è, senza inversioni di segno.
- La sessione edit-attach ha già: lista comandi eterogenea (verbatim + gesti), compattazione che non attraversa i verbatim, undo che li attraversa, parametri step/angle/scale editabili a cifre, riscrittura flat, cancel head-strip.
- Il keyboard layer attuale (frecce, Tab per i modi MOVE/ROTATE/SCALE, Alt+frecce per le cp traslazioni, cifre per i parametri) resta e non si tocca: diventa il layer di precisione accanto agli handles.

## Accertamenti preliminari (da fare prima di scrivere codice, risultati nel report)

A1. Verso delle rotazioni: per ognuno dei tre anelli, verificare visivamente (REPL + scena) che il verso del drag sul cerchio corrisponda al segno di th/tv/tr, e fissare il mapping in modo che draggare in senso orario visto dal semiasse positivo produca la rotazione che l'occhio si aspetta. Documentare la convenzione scelta nel report.

A2. Latenza del re-eval al rilascio su SDF: misurare su un modello SDF rappresentativo (desktop, geo-server) il tempo tra pointer-up e preview aggiornata. Se il ritardo è percepibile, mantenere la trasformazione THREE della preview finché l'eval non completa, invece di uno stato intermedio vuoto.

A3. Indicatore turtle esistente: verificare come il viewport disegna oggi l'indicatore della turtle/pose (geometria, colori degli assi) e riusarne le convenzioni cromatiche per gli assi del gizmo, così heading/right/up hanno lo stesso colore ovunque nell'app.

A4. Dimensione apparente costante: verificare se esiste già nel codebase una tecnica per geometria a dimensione schermo costante (scala legata alla distanza camera); se no, adottare la tecnica standard (riscalare il gruppo del gizmo in funzione della distanza a ogni frame) e annotarla.

## Lavoro richiesto

### 1. Modulo gizmo

Nuovo modulo `src/ridley/editor/gizmo.cljs`, consumato da edit_attach.cljs. Costruito per edit-attach ma tenendo edit-bezier a mente come secondo consumer futuro; nessuna estrazione generica ora (nessuna astrazione prima di più casi concreti), ma niente dipendenze gratuite dallo stato di edit-attach: il gizmo riceve una pose, opzioni, e callback sui gesti.

### 2. Rendering

- Gizmo combinato, non modale: tre frecce di traslazione, tre anelli di rotazione e tre maniglie di stretch visibili insieme, posizionati sulla creation-pose e orientati sui suoi assi locali. Colori assi da A3.
- Geometria effimera in overlay: non entra nel registro della scena né negli export; creata a enter!, rimossa a close!/cancel!, mai presente fuori sessione.
- Dimensione apparente costante al variare dello zoom (A4).
- Hit-zone invisibili ingrassate attorno a ogni handle (cilindri per le frecce, tori per gli anelli), così il grab non richiede precisione al pixel. Hover state visivo (evidenziazione dell'handle sotto il puntatore).
- In modalità origin (punto 5) il gizmo si aggancia al marker della pose e la resa deve rendere evidente il cambio di modalità (per esempio variando lo stile del marker), perché il significato dei gesti cambia.

### 3. Interazione pointer

- Pointer down: raycast contro le hit-zone del gizmo. Grab su un handle → `set-controls-enabled!` false per la durata del gesto, push dello stato per undo (un gesto = un passo). Pointer down sul vuoto → orbit normale, come edit-path.
- Frecce: il delta si calcola proiettando il ray del puntatore sulla retta dell'asse (punto più vicino sulla retta al ray), non su un piano. Anelli: proiezione sul piano dell'anello via `raycast-plane-point`, angolo dal centro, delta rispetto all'angolo di grab. Stretch: proiezione sulla retta dell'asse, fattore = rapporto tra distanza corrente e distanza di grab (moltiplicativo, come il keyboard scale mode emette oggi).
- Durante il drag: preview economica, si trasforma direttamente l'oggetto THREE della preview (e il gizmo con lui quando la pose si muove), throttled; nessun re-eval per frame. Al pointer-up: commit del comando nella lista di sessione e re-eval completo (pattern edit-image-board). Esito A2 per il caso SDF.
- `lock-interaction!` attivo per la sessione, come già fa edit-path.

### 4. Mapping gesti → comandi e snap

- Ogni gesto concluso emette un comando nella lista di sessione con il meccanismo esistente (add-command! e compattazione: due drag consecutivi sulla stessa freccia si fondono, un verbatim interrompe la catena).
- Snap attivo di default: le traslazioni scattano sui multipli di step, le rotazioni sui multipli di angle, lo stretch sui passi di scale, riusando i tre parametri di sessione già editabili a cifre (che cambiano mestiere: da "quanto per pressione" a "griglia di snap", le pressioni di frecce continuano a usarli come oggi).
- Modifier per il movimento libero (proposta: Shift tenuto durante il drag; deciderlo in implementazione se confligge, documentando la scelta). In libero, arrotondamento comunque a 0.1 (mm e gradi) prima dell'emissione: il codice è l'artefatto che l'utente rilegge, `(f 12.3847)` non deve poter arrivare nel sorgente.
- Le frecce di tastiera restano operative durante la sessione, stessa lista comandi, stessa compattazione: handles per il grosso, tastiera per la rifinitura quantizzata.

### 5. Modalità origin (la famiglia cp-*)

- Toggle esplicito nel pannello: oggetto / origin. Non un modifier nascosto: è un cambio di semantica dei gesti e merita visibilità. Il keymap esistente (Alt+frecce per le cp traslazioni) resta invariato e indipendente dal toggle.
- In modalità origin gli stessi handle emettono la famiglia cp: frecce → cp-f/cp-rt/cp-u, anelli → cp-th/cp-tv/cp-tr (le rotazioni cp diventano così raggiungibili per la prima volta, il keymap non le espone). Le maniglie di stretch in modalità origin sono disabilitate (non esiste una cp di scala).
- Segno: il gizmo dragga visivamente il marker della pose e emette il delta così com'è, coerente con la semantica verificata ("muovi la pose relativamente alla geometria").
- Presentazione durante il gesto: vista di intento, il marker della pose segue il cursore e la mesh resta ferma. Al rilascio, il re-eval mostra lo stato world-true: la pose torna alla sua posizione mondo (è inchiodata dal programma circostante) e la mesh risulta slittata dell'opposto. La relazione relativa pose-mesh è identica nelle due viste; il riallineamento al rilascio è accettato e va documentato nel manuale quando sarà il momento. L'alternativa (world-true anche durante il drag) è rigettata sotto.

### 6. Pannello

- Aggiunta del toggle oggetto/origin, con indicazione visibile della modalità corrente.
- Indicazione dei valori di snap correnti (step/angle/scale) accanto ai parametri già editabili.
- Il resto invariato: lista comandi (verbatim e gesti), Ok/Cancel/Undo, help tastiera. Se il testo di help cita solo la tastiera, aggiornarlo per menzionare gli handles in una riga.

### 7. Integrazione con la sessione

- enter! installa gizmo e listener pointer accanto al keydown esistente; close! e cancel! rimuovono tutto (listener, geometria overlay, hover state). Nessuna traccia del gizmo fuori sessione, incluse le uscite anomale (eval fallita a metà sessione).
- Undo (Backspace e bottone) e Ok/Esc si comportano come oggi; un gesto di drag è un item esattamente come una pressione di freccia.
- REPL mode: gli handles funzionano anche lì (la sessione REPL ha già preview e pannello; la riscrittura resta assente come oggi).

## Verifica

- Drag della freccia heading con snap attivo → nella lista appare `(f k)` con k multiplo di step; la preview segue il cursore durante il gesto; il re-eval al rilascio coincide con la preview; un Backspace lo rimuove e la scena torna indietro.
- Due drag consecutivi sulla stessa freccia → un solo comando fuso; drag su frecce diverse → comandi distinti.
- Drag di un anello → `(th a)` (o tv/tr) con il verso visivo concorde alla rotazione (esito A1); snap sui multipli di angle.
- Drag di una maniglia di stretch → `(stretch-f k)` moltiplicativo con pivot sulla pose; la mesh non trasla durante lo stretch.
- Modifier di movimento libero: il valore emesso non è multiplo di step ma è arrotondato a 0.1.
- Riapertura: `(attach cubo (f 10) (arc-h 90 20))` aperto dal menu Edit, drag di una freccia → il nuovo comando si accoda dopo i verbatim; Ok produce il body originale seguito dal nuovo comando.
- Tastiera in coesistenza: nella stessa sessione, drag poi freccia poi drag; la lista riflette l'ordine dei gesti e la compattazione corretta.
- Modalità origin: toggle attivo, drag del marker di +5 lungo heading → `(cp-f 5)`; al rilascio la relazione pose-mesh coincide con quella prodotta da Alt+freccia equivalente; drag di un anello in origin → `(cp-th a)`; le maniglie di stretch risultano disabilitate.
- Orbit: drag sul vuoto orbita normalmente; durante il grab di un handle l'orbit è disabilitato e si riabilita al rilascio; Alt+click e Shift+click sono soppressi per tutta la sessione.
- Hover: l'handle sotto il puntatore si evidenzia; a sessione chiusa non resta nessuna geometria overlay nella scena (verificare anche dopo un cancel e dopo un errore di eval indotto).
- SDF: sessione su un nodo SDF con drag funzionante; latenza al rilascio secondo esito A2.
- Zoom: il gizmo mantiene dimensione apparente costante avvicinando e allontanando la camera; le hit-zone restano afferrabili.
- REPL mode: drag funzionante, Ok stampa l'espressione senza toccare l'editor.
- Suite `npm test` verde; per la matematica di proiezione (ray-retta, ray-anello-angolo) test unitari dedicati, scritti prima dell'implementazione delle proiezioni.

## Fuori scope

- Estrazione del gizmo come layer generico riusabile (edit-bezier per i punti di controllo, edit-path per i nodi): si estrae quando c'è il secondo consumer, non prima.
- Il "simplify" della storia alla conferma (sostituzione con la decomposizione canonica del delta netto): rimandato, vedi alternative rigettate.
- Modifiche al keymap esistente (Tab dei modi, Alt+frecce, cifre): resta tutto com'è; questo brief aggiunge, non riorganizza.
- Stretch con faccia opposta fissa (compensazione con cp automatica): rigettato per ora, vedi alternative.
- Gizmo su selezioni multiple o su oggetti fuori sessione.
- Aggiornamento di Architecture.md e del manuale: a valle, in un passaggio documentale unico su tutto l'arco edit-attach.

## Alternative rigettate

- TransformControls di Three.js al posto degli handle custom: rigettata. È modale (un tipo di handle alla volta), l'estetica non è controllabile, e soprattutto non può ospitare il doppio target oggetto/origin che è la feature Ridley-specifica; il pattern pointer necessario è già in casa (edit-path, edit-image-board), quindi il risparmio sarebbe modesto.
- Gizmo modale (mostrare solo frecce, o solo anelli, con uno switch): rigettata. Reintrodurrebbe il Tab-cycling che gli handles vogliono eliminare; il gizmo combinato è lo standard dei tool di riferimento e le hit-zone separate lo rendono praticabile.
- Modifier nascosto (Alt+drag) al posto del toggle per la modalità origin: rigettata per il gizmo. Un cambio di semantica dei gesti dev'essere visibile e persistente, non dipendere da un tasto tenuto; il keymap Alt+frecce resta però invariato per compatibilità.
- Simplify alla conferma (registrare il delta netto invece della storia dei gesti): rimandata. Non è sicura in presenza di stretch (lo stretch non commuta con le rotazioni) e la storia con compattazione è coerente con il comportamento attuale; se all'uso i programmi risulteranno rumorosi, si riapre con un brief dedicato.
- Vista world-true durante il drag in modalità origin (mesh che slitta in direzione opposta al cursore mentre la pose resta ferma): rigettata. Il gesto diventa incoerente con l'occhio; la vista di intento durante il drag con riallineamento al rilascio preserva la relazione relativa ed è spiegabile.
- Re-eval continuo durante il drag: rigettata. Costo per frame ingiustificato e già risolto dal pattern preview-economica + eval-al-rilascio di edit-image-board; per gli SDF sarebbe proibitivo.
