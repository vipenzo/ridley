# Addendum a brief-mesh-components-tree — focus di sessione e navigazione esplicita

## Contesto

Feedback dal primo uso reale della sessione ad albero (Vincenzo): UI confusa, operatività poco chiara, transizioni non volute. Diagnosi della radice: il click-per-selezionare i pezzi è in **collisione di gesti** con gli handle mouse del piano — da quando il mouse manipola il piano, un click in viewport ha due significati sovrapposti, e la selezione accidentale è il passaggio non voluto. Il rimedio non è disambiguare meglio: è dare a ogni canale un solo mestiere. Principio guida: **lo stato visivo e interattivo è focalizzato sull'operazione in corso**; tutto il resto arretra.

## Lavoro richiesto

### Parte A — Navigazione esplicita, mouse monofunzione

- **Rimuovere la selezione via click sui pezzi.** Il mouse in viewport fa una cosa sola: manipolare il piano di taglio (handle esistenti).
- La navigazione tra pezzi aperti passa a: tasti **`n`/`p`** (next/previous) e **bottoni equivalenti nel pannello**. Il ciclo è **ordinato e deterministico sui soli pezzi aperti** (ordine stabile dell'albero, depth-first; i finiti non sono operabili e non entrano nel giro). Nessun accesso casuale: si sa sempre dove porta il prossimo `n`.
- Verificare che `n`/`p` non collidano con la keymap esistente dei tool modali; in caso, allinearsi alla convenzione di famiglia e riportare la scelta.
- La selezione resta fuori dal log undo (invariato dal brief).

### Parte B — Focus visivo: corrente pieno, resto evanescente

- **Pezzo corrente**: pieno, col semaforo (tinte di convessità sulle metà live, badge componenti).
- **Altri pezzi aperti**: molto tenui (alpha bassa), ma **presenti** — servono da contesto spaziale, non spariscono. Niente tinte di convessità su di loro: il semaforo appartiene solo al corrente.
- **Pezzi finiti**: ghosted con etichetta, come da addendum precedente.
- **Toggle di rivelazione** (tasto): riporta temporaneamente tutti i pezzi a visibilità piena per ri-orientarsi, poi si torna al focus. Stato non persistente.
- Alla pressione di `n`/`p` la transizione di focus deve essere visivamente inequivocabile (il nuovo corrente si accende, il precedente arretra).

### Parte C — Ancoraggio testuale nel pannello

- Riga di posizione sempre visibile: `pezzo 2/5 · aperto · 1 componente` (o `· 2 componenti` col badge), col **nome che il pezzo avrà nell'emissione** — la stessa identità di scena, pannello e codice emesso.
- I bottoni next/prev della Parte A vivono accanto a questa riga.

## Verifica

- Live end-to-end sul sintetico tipo mount del brief: sessione multi-taglio multi-ramo usando solo tastiera (`n`/`p`, frecce, Enter) e solo mouse-sul-piano; nessuna transizione di focus non richiesta deve essere possibile.
- Il click su un pezzo NON seleziona (verificare esplicitamente che il gesto sia inerte o riservato al piano).
- Toggle di rivelazione: attiva/disattiva senza effetti collaterali sullo stato di sessione.
- Coerenza dei nomi: riga del pannello, etichette in scena e forma emessa mostrano gli stessi nomi.
- Round-trip di emissione invariato: nessun cambiamento al formato emesso (i test esistenti passano intatti).
- Suite completa verde.

## Fuori scope

- Qualsiasi cambiamento alla semantica di taglio, undo, criterio di finitezza, emissione: solo navigazione e presentazione.
- Minimap/vista schematica dell'albero nel pannello: possibile evoluzione se il focus non basta, non ora.
- Selezione via click come opzione configurabile: no — un canale, un mestiere; si riapre solo con un caso d'uso forte.

## Alternative considerate e scartate

- **Disambiguare click-selezione da click-piano** (zone morte, modificatori, doppio click): scartato — cura il sintomo lasciando due significati sullo stesso gesto; la collisione rinascerebbe a ogni nuovo elemento cliccabile.
- **Nascondere del tutto i pezzi non correnti**: scartato — senza contesto spaziale non si capisce dove si è nell'oggetto; l'evanescenza + toggle dà focus senza cecità.
- **Selezione nel log undo**: già scartata nel brief (selezionare non modifica nulla), confermata qui.
