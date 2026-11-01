# Accertamento: integrazione SpaceMouse Compact

## Contesto

Vogliamo integrare in Ridley uno SpaceMouse Compact di 3Dconnexion (USB, vendor `0x256F`, product atteso `0xC635`). L'uso principale previsto è pilotare la sessione modale edit-attach (quella che alla conferma si riscrive in un `attach`); in seguito anche la navigazione della camera e, forse, `tweak` / `edit-bezier`.

Il design di massima, ancora da validare:

- Lettura del dispositivo lato Rust nella build Tauri (crate `hidapi`), inoltro al frontend come evento normalizzato `{:t [x y z] :r [rx ry rz] :buttons n}`. Nella build browser lo stesso evento verrebbe da WebHID.
- Nella sessione edit-attach il puck è una velocità: un accumulatore per asse emette un comando discreto (`f`, `rt`, `u`, `th`, `tv`, `tr`) ogni volta che supera lo step corrente, così il sorgente resta pulito e la compattazione esistente fonde i comandi consecutivi.
- Filtro sull'asse dominante, per evitare comandi spuri dovuti a movimenti involontari degli altri assi.
- Bottone sinistro: commuta puck tra camera e oggetto. Bottone destro: modalità stretch.

Prima di scrivere il brief di implementazione servono fatti misurati, non inferiti. Questo documento chiede solo di misurare e riportare.

## Lavoro richiesto

Per ogni punto: risposta, evidenza (output di log, riferimenti `file:riga`), e se l'ipotesi implicita risulta falsa, dirlo esplicitamente.

### A. Dispositivo e sistema operativo

1. **Formato dei report.** Logger Rust usa-e-getta (fuori dall'app, es. in `scratch/`) basato su `hidapi`: enumera i device `0x256F`, conferma il product ID, stampa i report grezzi in esadecimale con report ID e timestamp. Determinare: report ID usati; se traslazione e rotazione arrivano in un unico report da 12 byte o in due report separati; ordine e segno degli assi rispetto al gesto fisico (spingi avanti, solleva, ruota, ecc.); range effettivo dei valori; formato del report dei bottoni.
2. **Frequenza.** Quanti report al secondo con il puck deflesso e a riposo. Il device manda report anche a riposo o solo al cambiamento?
3. **Driver 3DxWare.** È installato sulla macchina? Se sì, verificare se `hidapi` riceve dati con il driver attivo e con il driver chiuso. Se il driver blocca la lettura, documentare come si manifesta (errore all'apertura, apertura riuscita ma nessun report, ecc.).
4. **Permessi macOS.** Verificare se macOS chiede permessi (Input Monitoring o altro) sia lanciando il logger da terminale, sia dall'app Tauri in dev, sia dal bundle firmato. Servono entitlement nel bundle?
5. **Hotplug.** Cosa succede scollegando e ricollegando il device con il lettore attivo.
6. **WebHID nella build browser.** Con Chrome su `localhost:9000`, `navigator.hid.requestDevice` vede il device? I report coincidono con quelli letti da `hidapi`? Con il driver 3DxWare attivo arriva qualcosa?

### B. Tauri

7. Versione di Tauri in uso (v1 o v2) e struttura di `src-tauri`: comandi ed eventi già esistenti, dove andrebbe un thread di lettura in background, cosa serve (capabilities / permissions in v2) per emettere eventi verso il frontend.
8. Con un emit per report alla frequenza misurata al punto 2, il frontend regge? Misurare la latenza o almeno verificare che non si accumuli coda. Serve throttling lato Rust?

### C. Ridley

9. **edit-attach.** Nome reale della sessione e file sorgente. È la stessa cosa che `Spec.md` / `Architecture.md` chiamano `pilot`? Descrivere: dove vive lo stato della sessione, come i tasti diventano comandi, come è gestito lo step (traslazione e rotazione), dove avviene la compattazione, cosa succede nel viewport a ogni comando (wireframe preview o rivalutazione).
10. **Layer modal_evaluator.** Come viene installato il keyhandler. Esiste un punto di ingresso per input non da tastiera, o la sessione è legata a `keydown`? Cosa servirebbe per inviare alla sessione comandi da un'altra sorgente senza duplicare la logica dei tasti?
11. **Costo per comando.** Quanto costa aggiornare la preview per un singolo comando emesso (ms). Con un puck che emette, diciamo, 10–20 comandi al secondo, la preview resta fluida?
12. **Camera.** Che controlli usa il viewport (OrbitControls, TrackballControls, custom)? Il render è continuo (requestAnimationFrame sempre attivo) o on-demand? Dove si inserirebbe un input di velocità applicato a ogni frame?

## Verifica

- Ogni punto da 1 a 12 ha una risposta con evidenza allegata (log grezzi incollati, `file:riga`). "Non determinabile" è una risposta accettabile se motivata.
- I log del punto 1 includono almeno una sequenza per ciascuno dei sei gesti fisici isolati e per ciascun bottone.
- Nessuna modifica al codice di produzione; il logger e gli esperimenti WebHID restano in `scratch/` o su un branch dedicato da non mergiare.
- Output: un documento `dev-docs/accertamento-spacemouse-risultati.md` con le risposte nell'ordine dei punti.

## Fuori scope

- Implementazione del livello di input, dell'integrazione con edit-attach e della navigazione della camera: saranno brief successivi, scritti sulla base di questi risultati.
- NavLib di 3Dconnexion (libreria ufficiale via WebSocket del driver): scartata per ora perché obbliga all'installazione del driver e l'API è verbosa e poco documentata. Riconsiderarla solo se il punto 3 mostra che il driver rende impraticabile la lettura diretta.
- Supporto ad altri modelli 3Dconnexion oltre alla Compact.
- Configurazione utente (sensibilità, inversione assi, mappatura bottoni).