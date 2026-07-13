# Brief: cut-candidates — generatore di candidati di taglio (traslazione e rotazione) con profilo di sezione

## Contesto

Quinto brief della famiglia "acquisizione STL". Fondamenta misurate nell'accertamento fase 2:

- **B1**: l'area della sezione è leggibile dai contorni di `slice-mesh` (shoelace con winding dei buchi, o `.area()` nativa del cross-section quando disponibile).
- **B2**: ammortizzando la conversione (Manifold vivo — il keep-alive per pezzo esiste dal brief albero): campione di profilo traslazionale ~0.039 ms (live libero), rotazionale ~0.61 ms (on-demand/debounce). Il costo vero era l'unica conversione da 8 ms, già pagata dal keep-alive.
- **B3**: i candidati-vertice lungo un asse "buono" sono una manciata (5 sul sintetico); off-axis o su superfici curve diventano centinaia → **il ranking per salienza è parte del generatore, non un extra**.
- **B5**: i generatori sono funzioni pure di (mesh, parametri di posa) — nessuno stato di sessione; il tool interattivo è un consumatore sottile.

Design concordato in discussione: il segnale percettivo è l'**area della sezione** A lungo il grado di libertà attivo — A(t) in traslazione lungo l'heading, A(θ) in rotazione attorno a un asse del frame. Gli eventi utili sono i **gradini** (salto di A: facce a filo, il taglio "a filo faccia") e i **colli** (minimo locale di A: la vita del manubrio, dove due feature si toccano appena). In traslazione i candidati esatti sono le proiezioni dei vertici sull'heading (O(V), noti a priori, ampiezza del gradino = area delle facce complanari in quel punto, leggibile dalla mesh senza affettare); in rotazione ogni vertice attraversa il pencil di piani a un angolo preciso (di nuovo O(V)), e il cambio di segno di dV/dθ — il gesto "ruota finché la derivata cambia segno" — avviene quando l'asse passa per il baricentro della sezione. I colli, in entrambi i modi, si trovano campionando il profilo.

Interazione concordata: **"salta al prossimo evento", sensibile al modo** — deterministico, mai in lotta con le frecce (lo snap magnetico è scartato per la tastiera). In step mode si salta tra i candidati traslazionali, in angle mode tra i rotazionali. Più la **striscia di profilo** nel pannello: il grafico di A lungo il DOF attivo, con la posizione del piano marcata e i candidati come tacche — i colli e i gradini non si cercano, si *vedono*.

Convenzioni ereditate: piano = posa della turtle; pose restituite nel formato consumabile del sistema (come `symmetry-planes`); UI secondo addendum 3 (bottone per ogni gesto, ragioni scritte, nessun no-op silenzioso, stati pending — i colori non mentono mai).

## Lavoro richiesto

### Parte 1 — section-area (reader)

`(section-area mesh)`: area della sezione della mesh col piano della posa corrente (slice + area secondo l'esito B1). Reader della famiglia di `bounds`/`area`, consumatore concreto immediato: il profilo della Parte 4. Mesh non intersecata dal piano → 0, documentato.

### Parte 2 — cut-candidates (generatore, funzione DSL pura)

`(cut-candidates mesh)` / `(cut-candidates mesh opts)`:

- **Modo traslazione (default)**: candidati lungo l'heading corrente. Fonti: proiezioni dei vertici (dedup in tolleranza, pinnata e parametrizzabile via opts) per i gradini — con salienza = |ΔA| dall'area delle facce complanari; minimi locali del profilo campionato per i colli — con salienza = profondità della valle.
- **Modo rotazione** (`{:mode :rotation :axis :up}`, asse ∈ `#{:up :right}`, default `:up`): pencil di piani attorno all'asse per la posizione corrente; candidati dagli angoli di attraversamento dei vertici e dai minimi di A(θ). Ruotare attorno all'heading non cambia il piano: non è un asse ammesso, errore leggibile.
- **Ritorno**: vettore di mappe `{:pose <posa> :kind :step|:neck :salience <num>}`, ordinato per salienza decrescente. Le pose nel formato standard consumabile (turtle piazzabile). Il kind e la salienza sono parte del contratto: B3 dice che senza ranking i candidati off-axis sono centinaia, e il chiamante (tool o script) deve poter filtrare.
- Funzione **pura** di (mesh, posa corrente, opts): nessuno stato di sessione, stessi input → stessi output (B5).

### Parte 3 — Gesto "salta al prossimo evento"

- Tasti next/prev-candidato + bottoni equivalenti (addendum 3), **sensibili al modo**: step mode → candidati traslazionali lungo l'heading; angle mode → rotazionali attorno all'asse attivo (in questa iterazione: `:up`; estensione ad asse selezionabile solo se l'uso la chiede).
- Il salto teletrasporta il piano sulla posa del candidato — deterministico, ordinato per posizione lungo il DOF (non per salienza: il ranking serve al filtro e alla striscia, la navigazione segue l'asse).
- Precondizioni visibili: nessun candidato oltre la posizione corrente → bottone disabilitato con ragione, tasto → messaggio in riga di stato.
- Cache per pezzo aperto: profilo e candidati traslazionali per (pezzo, heading) — invalidati dalla rotazione del piano, ricalcolo throttled; rotazionali per (pezzo, posizione, asse) — on-demand con debounce e stato pending. Il Manifold vivo del pezzo c'è già.

### Parte 4 — Striscia di profilo nel pannello

- Grafico di A lungo il DOF attivo (t in step mode, θ in angle mode), con: marcatore della posizione corrente del piano, tacche dei candidati differenziate per kind (gradino/collo), aggiornamento al cambio di modo (Tab) e al cambio di pezzo.
- **Click su una tacca → teletrasporto su quel candidato.** Il click nel pannello è legittimo (la monofunzione riguarda la viewport); è il gesto coerente col "si vede, ci si salta sopra".
- Durante il ricalcolo: stato pending sulla striscia, mai dati stantii presentati come correnti.

## Verifica

- `section-area`: cilindro cavo a metà altezza → corona circolare attesa (replica B1); piano fuori dal pezzo → 0.
- `cut-candidates` traslazionale su prisma a gradini con quote note: candidati alle quote esatte, salienze = aree note dei gradini; manubrio → collo alla vita con salienza = profondità; cilindro lungo l'asse → nessun gradino interno; replica B3 (manciata di candidati on-axis dopo dedup).
- Rotazionale su caso costruito con baricentro di sezione noto: l'evento di cambio segno di dV/dθ cade dove previsto; asse `:heading` → errore leggibile.
- Purezza: chiamate ripetute con stessi input → output identici; nessuna dipendenza dalla sessione.
- Ranking: ordinamento per salienza verificato contro |ΔA| calcolato indipendentemente.
- Live end-to-end: salto next/prev nei due modi, striscia col marcatore e le tacche, click-su-tacca, pending durante i ricalcoli, bottoni con ragioni; budget rispettati (traslazionale libero, rotazionale debounced senza freeze).
- Emissione invariata: saltare su un candidato e accettare emette un taglio ordinario — round-trip esistente intatto.
- Test WASM con idioma skip; citare nel report l'entry di `code-issues.md`.
- **Accettazione sul pezzo reale**: sul mount di Vincenzo (desktop, `import-stl`), il taglio "naturale" dev'essere raggiungibile in pochi salti di next-event; riportare frizioni e conteggi.
- Suite completa verde.

## Fuori scope

- Il generatore da spigoli riflessi (V2): brief suo, sugli esiti B4 (clustering refinement-invariante) — i tre generatori condividono il contratto di ritorno fissato qui.
- Snap magnetico per il drag col mouse: si valuta dopo l'uso del salto deterministico, non prima.
- Helper di ricerca scriptabile (`seek-cut mesh pred`, "scorri gli eventi finché il predicato scatta"): orizzonte dichiarato del "linguaggio vero" — `cut-candidates` puro lo rende banale da scrivere a mano nel frattempo.
- Ricerca multi-DOF (ottimizzare posizione e orientamento insieme).

## Alternative considerate e scartate

- **Snap magnetico sulla tastiera**: scartato — litiga col controllo fine delle frecce che è il pregio del tool; il salto deterministico dà lo stesso valore senza lotta.
- **Ritorno a pose nude** (come `symmetry-planes`): scartato qui — kind e salienza sono il ranking che B3 dimostra necessario; senza, il chiamante non può filtrare le centinaia di candidati off-axis.
- **Gradini rilevati campionando il profilo** invece che dalle facce complanari: scartato — la lettura diretta dalla mesh è esatta e gratis, il campionamento li smusserebbe e può mancarne di sottili tra due campioni.
- **Navigazione next/prev per salienza** invece che per posizione: scartato — saltare "al più saliente" disorienta spazialmente; l'ordine lungo l'asse è prevedibile, il ranking vive nella striscia e nel filtro.
