# Brief: SpaceMouse in edit-attach

## Contesto

Ridley deve poter usare uno SpaceMouse Compact per pilotare la sessione `edit-attach`. I fatti misurati sono in `dev-docs/accertamento-spacemouse-risultati.md`; questo brief li dà per acquisiti e cita solo quelli che vincolano le scelte.

**Vincoli dall'accertamento.**

- **Solo app desktop** (decisione di Vincenzo, punto 6): niente WebHID. Nella build browser la funzione non esiste e non dà errori.
- Il device manda report `01` (tx ty tz) e `02` (rx ry rz) separati, 7 byte, int16 LE, range ±350, 125 report/s mentre è deflesso; tace a riposo dopo 3-4 report a zero per ID. Bottoni nel report `03`, bit 0 sinistro, bit 1 destro. Frame del device: X a destra, Y verso l'utente, Z in basso (punto 1).
- macOS usa il device come mouse. **Va aperto in esclusiva**, e solo finché serve (punto 3).
- Stacco = errore di lettura; riaggancio = riapertura in ciclo (punto 5).
- Tauri v2, nessun evento né comando esistente; i permessi per `listen` ci sono già (punto 7).
- Ogni `add-command!` rivaluta l'intero script (da 5 ms a 6 s, punto 11). `nudge-preview!` costa 0.003 ms. Il gizmo usa già lo schema giusto: anteprima rigida durante il drag, un solo `on-commit` al rilascio (punto 9).
- Il filtro "asse dominante" fra tutti e sei gli assi sbaglia sui gesti avanti/indietro, dove ty e rx sono accoppiati al 90-110% (punto 1).

**Scelte di design, con motivazione.**

1. **Un gesto = un comando.** Mentre il puck è deflesso si muovono solo gizmo e anteprima (nudge, nessuna rivalutazione); quando il puck torna a riposo si emette un solo `add-command!` con il valore accumulato, arrotondato allo step. È lo schema del gizmo, e il sorgente resta pulito senza dipendere dalla compattazione finale.
2. **La famiglia di assi è esplicita, non dedotta.** Il puck lavora sulla famiglia indicata da `:digit-target` della sessione, la stessa che governa le frecce: STEP → solo traslazioni (`f` `rt` `u`), ANGLE → solo rotazioni (`th` `tv` `tr`). Il bottone destro del puck fa quello che fa Tab (ciclo STEP → ANGLE → SCALE). Dentro la famiglia vince l'asse con il valore assoluto maggiore, e resta bloccato fino alla fine del gesto. Così l'accoppiamento traslazione/rotazione non conta più, perché le due famiglie non competono.
3. **Riferimento: la tartaruga, non lo schermo.** Il cappuccio "è" l'oggetto visto da dietro la tartaruga: spingere lontano da sé = `f+`, a destra = `rt+`, sollevare = `u+`; inclinare il bordo lontano in giù = muso in giù; inclinare il bordo destro in giù = rollio a destra; ruotare in senso orario visto dall'alto = imbardata a destra. Un riferimento allo schermo sarebbe più intuitivo quando la tartaruga guarda la camera, ma ogni gesto diventerebbe una combinazione di `f`/`rt`/`u` e il sorgente si sporcherebbe.
4. **Il puck è una velocità.** La deflessione, oltre la zona morta, diventa una velocità integrata nel tempo; l'anteprima segue l'integrale continuo (l'oggetto scorre, non scatta — Vincenzo, 2026-11-02); il valore mostrato nel pannello e quello emesso sono l'integrale arrotondato allo step corrente (con Shift premuto sulla tastiera: arrotondamento libero a 0.1, come `free-round` nel gizmo).
5. **Rust resta sottile.** Legge, mantiene lo stato dei sei assi, coalesce, emette valori grezzi nel frame del device. Conversione di frame, zona morta, curva di risposta e macchina a stati del gesto sono in ClojureScript, come funzioni pure testabili.
6. **Esclusiva solo durante la sessione.** Il frontend chiede il device all'ingresso in edit-attach e lo rilascia in `cleanup!`. Con la finestra di Ridley non in primo piano il device va rilasciato, e ripreso al ritorno del focus se la sessione è ancora aperta.

**Integrazioni concordate dopo la revisione (Vincenzo, 2026-11-01).**

7. **Modo Origin → `cp-*`.** In Origin il puck emette `cp-f` `cp-rt` `cp-u` `cp-th` `cp-tv` `cp-tr`, come il gizmo (`gizmo.cljs:156-161`); l'anteprima muove solo il marcatore della posa, come per il gizmo in Origin.
8. **Focus gestito in Rust.** Il thread tiene due condizioni: *voluto* (impostata da `spacemouse_acquire`/`spacemouse_release`) e *finestra a fuoco* (`WindowEvent::Focused`). È in esclusiva solo se sono vere entrambe. Il frontend non ascolta blur/focus. Con due istanze di Ridley aperte, l'apertura esclusiva che fallisce perché il device è occupato si tratta come "occupato", senza ripetere l'errore nel log ogni 500 ms.
9. **Niente gesti in fila durante la rivalutazione.** Dopo un commit, finché la rivalutazione non è arrivata, il puck non avvia nuovi gesti (l'anteprima ripartirebbe dalla posa vecchia). Il pannello mostra un'attesa.
10. **Cifre a metà.** All'avvio di un gesto si chiama `flush-digit-buffer!`, come fanno le frecce (`edit_attach.cljs:589`).
11. **Bottone durante un gesto.** Un bottone premuto a gesto attivo chiude il gesto (commit), poi esegue il suo effetto.
12. **Stato all'acquire.** `spacemouse-status` si emette subito dopo `spacemouse_acquire`, con `connected: false` se il device manca.
13. **Backspace e Shift.** Backspace a gesto attivo scarta il gesto (come Esc); a gesto finito annulla l'ultimo comando come oggi, cioè il gesto intero. Lo stato di Shift si legge con un piccolo listener keydown/keyup, che non tocca la mappa dei tasti.
14. **Due modi per gli assi, a scelta dell'utente (Vincenzo, 2026-11-02, dopo la prova).** Bottone nel pannello, scelta ricordata (localStorage), predefinito *all axes*: ogni asse che supera la soglia di avvio entra nel gesto, e al rilascio si scrivono tutti i valori non nulli, nell'ordine `f rt u th tv tr`; l'anteprima compone traslazione e rotazioni nello stesso ordine (`gizmo/preview-commands!`). *Tab axes* è il comportamento della scelta 2. Motivo: con la pratica si emettono rotazioni pulite, e un accoppiamento è facile da correggere; gli accoppiamenti deboli spariscono da soli nell'arrotondamento allo step.

## Lavoro richiesto

Tre commit, nell'ordine, ognuno verificato prima del successivo.

### Commit 1 — Logica pura (ClojureScript), test prima

Nuovo namespace, per esempio `src/ridley/input/spacemouse.cljs`, senza dipendenze da DOM, Tauri o Three.js.

- `device->turtle`: dai valori grezzi `{:t [tx ty tz] :r [rx ry rz]}` alle sei componenti nominate per comando, `{:f :rt :u :th :tv :tr}`, secondo la semantica fisica della scelta 3. I **segni** di `th`/`tv`/`tr` vanno ricavati dalla convenzione reale dei comandi (implementazione dei turtle command, non `Spec.md`) e scritti nel docstring con il riferimento `file:riga`.
- `pick-axis`: data la famiglia (`:step` o `:angle`) e le componenti, l'asse con il valore assoluto maggiore, o `nil` se sotto la soglia di avvio.
- `response`: zona morta e curva (per esempio potenza > 1 sul valore normalizzato), uscita in [-1, 1].
- `step-gesture`: riduttore puro `(step-gesture state input dt opts) → state'`, con `opts` = `{:family :step :angle-step :free?}`. Stati: `:idle` → `:active` (asse bloccato, valore accumulato) → al rilascio produce `{:commit [cmd value]}` e torna `:idle`. Valore emesso = accumulato arrotondato allo step; zero → nessun commit.
- **Isteresi**: soglia di avvio sopra il rumore dei bottoni (l'accertamento misura fino a ±52; partire da 80), soglia di rilascio più bassa (partire da 40) mantenuta per almeno ~120 ms, oppure stato tutto a zero. **Con un bottone premuto gli assi si ignorano**, perché la pressione muove il puck.
- Velocità massima proporzionale allo step corrente (per esempio 10 step/s a fondo corsa, sia per lo step lineare sia per quello angolare), così cambiare lo step cambia anche la sensibilità. Tutte le costanti in un'unica mappa in testa al namespace: le tarerà Vincenzo a schermo.

Test (in `test/ridley/input/spacemouse_test.cljs`), scritti prima del codice:

- **Fixture dai log reali.** Le mediane dei 12 gesti guidati (tabella del punto 1, compresi gli assi accoppiati: ad esempio "tira indietro" ty=+275 rx=+305) devono dare, nella famiglia corretta, l'asse e il segno attesi. In particolare: "tira indietro" in STEP → `f` negativo; "inclina indietro" in ANGLE → muso su.
- Rumore dei bottoni (±52) → nessun gesto.
- Gesto completo simulato (avvio, mantenimento, rilascio) → un solo commit con il valore arrotondato; Shift → arrotondamento a 0.1.
- Gesto che non supera mezzo step → nessun commit.
- Asse bloccato: a gesto avviato su `f`, una deflessione successiva più forte su `rt` non cambia asse.

### Commit 2 — Lettore Rust e canale verso il frontend

- Nuovo modulo `desktop/src-tauri/src/spacemouse.rs`. Dipendenza `hidapi` con la feature `macos-shared-device` (serve per `set_open_exclusive`). Si può partire da `reader_loop` del logger in `scratch/spacemouse-logger/`.
- Un thread avviato nel `.setup` di `main.rs` con un `AppHandle` clonato. Stati: **rilasciato** (device chiuso: macOS lo usa come mouse) e **acquisito** (aperto in esclusiva su usage page 1 / usage 8, lettura con timeout, ciclo di riapertura ogni 500 ms in caso di errore o device assente).
- Due comandi Tauri, `spacemouse_acquire` e `spacemouse_release` (il primo `invoke_handler` del progetto). Verificare se in Tauri v2 i comandi dell'app richiedono una voce nelle capabilities, e riportarlo.
- Evento `spacemouse` con payload `{t:[tx,ty,tz], r:[rx,ry,rz], buttons:n}` grezzo. **Coalescenza**: al massimo un emit ogni 16 ms con l'ultimo stato; il passaggio a tutto zero e ogni cambio dei bottoni si emettono subito. Evento `spacemouse-status` `{connected: bool}` all'apertura e allo stacco.
- Lato frontend, un piccolo modulo che: se `window.__TAURI__` manca non fa nulla; altrimenti espone `acquire!`/`release!` e, con `listen`, scrive l'ultimo stato in un atom (l'handler non fa altro).

### Commit 3 — Collegamento a edit-attach

- **Ingresso nella sessione**: il puck entra da `add-command!`, come il gizmo, non con eventi tastiera sintetici (la mappa dei tasti è modale, punto 10).
- **Anteprima**: oggi le funzioni di anteprima del gizmo (`apply-translate-preview!`, `apply-rotate-preview!`) sono private e legate allo stato del drag. Esporre da `gizmo.cljs` una coppia pubblica, per esempio `preview-command! [cmd value]` e `end-preview!`, che ricava asse e pivot dalla posa corrente e riusa quelle funzioni. Niente seconda copia della logica.
- **Loop**: un frame callback (`viewport/register-frame-callback!`) registrato in `enter!` e rimosso in `cleanup!`. A ogni frame legge l'atom del puck, fa avanzare `step-gesture`, chiama `preview-command!` durante il gesto e `add-command!` al commit. Ai callback oggi arriva solo la camera: calcolare `dt` nel callback o estendere la firma, senza rompere il gizmo.
- **Pannello**: mostrare il comando in corso (per esempio `(f 15)…`) mentre il gesto è attivo, e lo stato del device (collegato o no).
- **Bottoni**: destro = come Tab (ciclo della famiglia, aggiornando pannello e gizmo); sinistro = per ora nulla, è riservato al passaggio camera/oggetto del brief successivo. In SCALE il puck non fa nulla.
- **Ciclo di vita**: `acquire!` in `enter!`, `release!` in `cleanup!` (quindi su conferma, annullamento e `force-close!`). Rilascio quando la finestra perde il focus e nuova acquisizione al ritorno, se la sessione è ancora attiva: lo gestisce Rust (integrazione 8).
- **Gesto in corso alla conferma**: Enter con un gesto attivo committa prima il gesto, poi conferma. Esc lo scarta.

## Verifica

Commit 1: `npm test` verde, compresi i test della fixture.

Commit 2, nella console devtools dell'app desktop (`cargo tauri dev`):

- dopo `spacemouse_acquire` arrivano eventi `spacemouse` a ≤ 60/s con valori coerenti al gesto, e il puntatore di sistema **non** si muove;
- dopo `spacemouse_release` gli eventi cessano e il puck torna a muovere il puntatore come mouse (verifica di Vincenzo);
- staccare e ricollegare il cavo con il device acquisito: `spacemouse-status` false, poi true, e gli eventi riprendono;
- riportare se macOS ha chiesto permessi lanciando dall'app.

Commit 3, a schermo da Vincenzo, su una scena semplice (`(edit-attach (box 20))`) e su una scena da qualche secondo di rivalutazione:

- per ognuno dei sei gesti, l'oggetto si muove nel senso della scelta 3; un gesto produce **una sola** riga nel pannello;
- durante il gesto l'anteprima è fluida anche sulla scena pesante; la rivalutazione avviene una volta, al rilascio;
- il gesto "tira indietro" in STEP non produce rotazioni; inclinare in ANGLE non produce traslazioni;
- bottone destro e Tab restano allineati; cambiare lo step con le cifre cambia la sensibilità;
- Backspace annulla il gesto intero; Enter durante un gesto lo include; Esc lo scarta;
- passare a un'altra app durante la sessione restituisce il puntatore al puck; tornare a Ridley lo riprende;
- conferma, annullamento e cambio workspace rilasciano il device (verifica: il puck torna a muovere il puntatore);
- nella build browser nessun errore in console.

Le costanti (soglie, curva, velocità) si tarano a schermo dopo il commit 3, in commit separati.

## Fuori scope

- **Navigazione della camera col puck**, anche fuori dalle sessioni, e il bottone sinistro per passare da camera a oggetto: FATTO 2026-11-02 (`ridley.input.spacemouse-view`). Esclusiva: dall'avvio, finché la finestra ha il focus.
- Il puck in SCALE (`stretch-*`), in `tweak`, `edit-bezier`, `edit-path`.
- WebHID e build browser (decisione di Vincenzo).
- NavLib e comportamento con 3DxWare installato: non verificati, nessun supporto esplicito.
- Configurazione utente (sensibilità, inversione assi, mappatura dei bottoni).
- Firma del bundle ed entitlement.
- **Alternative scartate**: un comando per soglia superata (congela la preview sopra ~12 comandi/s e rivaluta l'intero script a ogni comando, punto 11); asse dominante sui sei assi (sbaglia sui gesti avanti/indietro, punto 1); eventi tastiera sintetici (mappa tasti modale, punto 10); polling dello stato via geo-server HTTP (un round-trip per frame, punto 7); riferimento allo schermo invece che alla tartaruga (sorgente sporco, scelta 3).
- Piccole derive emerse dall'accertamento, da sistemare a parte: `Spec.md:4315` cita `pilot-request!`, che non esiste più; il messaggio d'ingresso di edit-attach dice «Shift+arrows to rotate», ma in STEP Shift+frecce dà `u` (`edit_attach.cljs:861`); vari commenti in `viewport/core.cljs` dicono OrbitControls, ma il codice usa TrackballControls.
