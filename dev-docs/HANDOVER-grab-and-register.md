# Handover: "scatta e registra" — la camera in diretta come sorgente di viste

Aperto 2026-08-11, alla chiusura della fase dei **bordi dichiarati**. Niente di
questo è ancora costruito: è il punto di partenza della prossima chat, con i
vincoli già accertati per non scoprirli a metà strada.

Brief del canale: `dev-docs/brief-observation-driven-acquire.md`.
Fase appena chiusa: `dev-docs/HANDOVER-edge-declared.md`.

## Cosa ha chiesto Vincenzo

> «Una cosa interessante poteva essere la connessione di una webcam/cellulare in
> diretta per costruire la scena da zero senza passare da fotografie. Pensavo al
> cellulare usato come webcam.»

E, sul come: **"scatta e registra"** prima della diretta vera. Inquadri, premi
un tasto, il fotogramma entra nella sessione GIÀ REGISTRATO. È lì che sta quasi
tutto il guadagno — sparisce il giro scarica-copia-rinomina — e si innesta su
ciò che esiste senza toccare né i gesti né il solutore.

La diretta vera (wireframe sovrapposto al video, click sull'immagine viva) è
un'altra cosa, molto più grossa: serve la posa a ritmo interattivo, il video
come sfondo, e la gestione del mosso. Non è il primo passo.

## Il pezzo difficile c'è già

Il piatto di registrazione **è** una marker board. Da un fotogramma in cui si
vede, la posa della camera si ricava con la strada di adesso, che è tutta
costruita e collaudata:

| pezzo | dove |
|---|---|
| rilevamento dischetti (globale, sub-pixel) | `src/ridley/photogrammetry/blob_detect.cljs` |
| identità della corona + zero-indice | `src/ridley/photogrammetry/match_plate.cljs` |
| PnP planare (omografia + LM) | `src/ridley/photogrammetry/pnp.cljs` |
| focale UNICA per sessione, FITTATA dai dati | bundle di gradino 1 |
| stato della sessione | `session.json` + `acquire-state.json` nella cartella |
| scrittura file (desktop) | `stl/desktop-write-file` → rotta Rust `/write-file` |

**La focale è il punto che rende la cosa possibile.** Una foto di telefono porta
l'EXIF; un fotogramma di webcam no. Ma il gradino 1 ha già portato la focale a
essere *fittata da tutte le osservazioni della sessione* invece che letta: una
vista senza EXIF non è più un caso speciale, è il caso normale.

## Vincoli accertati (non ipotesi)

- **Il telefono come webcam: Continuity Camera.** Su macOS recente un iPhone si
  presenta al sistema come una camera qualunque, quindi `enumerateDevices` /
  `getUserMedia` lo vedono senza app né rete. È di gran lunga la strada più
  corta, e va provata PER PRIMA.
- **Il telefono come client web NON è la strada corta.** Se il telefono aprisse
  una pagina servita dal Mac via `http://<ip>:9000`, la camera sarebbe bloccata:
  `getUserMedia` vuole un contesto sicuro, e `localhost` lo è mentre un IP in
  chiaro no. Servirebbe HTTPS con certificato — attrito vero, da evitare finché
  Continuity basta.
- **"Scatta e registra" è una funzione DESKTOP.** Il fotogramma va scritto nella
  cartella della sessione, e il browser non ha filesystem: la scrittura passa da
  `desktop-write-file` (rotta Rust `/write-file`), che esiste. Nel browser si
  potrà al massimo tenere il fotogramma in memoria per la sessione corrente.
- **Nell'app Tauri i permessi camera non ci sono ancora.** In
  `desktop/src-tauri/` non compare nulla di camera: servono la usage description
  (`NSCameraUsageDescription`) e, se l'app è sandboxata, l'entitlement
  `com.apple.security.device.camera`. Da mettere in conto PRIMA di promettere
  che funziona nel DMG: in Chrome su `localhost:9000` funzionerà comunque.
- **La precisione è da MISURARE, non da assumere.** Un fotogramma di webcam ha
  meno pixel di una foto e può essere mosso, e la precisione della misura scala
  coi pixel. L'uso onesto è *inquadrare* e *aggiungere una vista dove serve*;
  se poi regga anche come misura lo dice il residuo di registrazione (px), che
  la sessione già calcola e mostra per ogni foto.

## Prima fetta proposta

1. Un bottone **Grab** sul palcoscenico, abilitato solo quando c'è una camera
   disponibile e una sessione aperta.
2. Anteprima del video in un angolo, così si inquadra guardando l'oggetto e non
   il Mac.
3. Alla pressione: fotogramma → JPEG → `desktop-write-file` nella cartella della
   sessione, con un nome che segue la convenzione delle altre foto.
4. Registrazione immediata con la strada zero-click (`blob-detect` +
   `match-plate` + PnP planare) e **verdetto ad alta voce**: registrata con
   residuo N px, oppure rifiutata con il motivo. Un fotogramma che non si
   registra non deve entrare nella sessione.
5. `session.json` aggiornato, e la nuova vista compare fra i frustum come le
   altre — da lì in poi non è più un caso speciale per nessuno.

Il criterio di riuscita, in una riga: **scattare una vista nuova e misurarci
sopra uno spigolo senza toccare il Finder.**

## Domande aperte da decidere insieme

- L'angolo θ del giradischi: le foto lo portano da `NOTE.md`. Un fotogramma
  scattato a mano non ha un θ — va lasciato `nil` («foto libera», che il canale
  già gestisce) oppure chiesto?
- Quante viste servono davvero, se aggiungerle costa un tasto? Forse conviene
  scattare POCO e mirato — una vista in più *dove serve allo spigolo che stai
  misurando* — invece del giro completo del giradischi.
- Vale la pena, appena una vista è registrata, dire di quanto MIGLIORA la misura
  in mano (il giro attorno allo spigolo passa da X° a Y°)? È il numero che
  trasformerebbe "scatta" in "scatta QUI".
