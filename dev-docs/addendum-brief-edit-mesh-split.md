# Addendum a brief-edit-mesh-split — mouse handles e leggibilità della scena

## Contesto

Feedback dal primo uso reale di `edit-mesh-split` (Vincenzo, 2026-07-11), tre punti:

1. L'assenza di handle mouse sulla turtle ha sorpreso: `edit-attach` offre già il drag della turtle, e chi viene da lì se lo aspetta anche qui. Il "fuori scope: manipolazione mouse/drag" del brief originale era scritto su una mappa incompleta della famiglia (per analogia con `pilot`/`edit-bezier`, senza contare `edit-attach`): il buco è del brief, non dell'implementazione, che è fedele. Le decisioni portanti non si oppongono al mouse — "gizmo = turtle" significa *una sola verità sulla posa*, e gli handle di `edit-attach` muovono la turtle stessa, rispettandola per costruzione; l'emissione canonica era stata disaccoppiata dall'input proprio in previsione di questo ("senza toccare il formato emesso").
2. Non è chiaro quale lato del piano sia `:behind` e quale `:ahead` — il cono c'è ma non comunica il significato.
3. Dopo alcuni tagli non è chiaro quali pezzi siano già consumati e quale sia il remaining.

I punti 2 e 3 hanno la stessa radice: troppe dimensioni semantiche sullo stesso canale visivo. Principio guida di questo addendum: **una variabile visiva per dimensione semantica** — tinta = convessità (solo sulle metà live), solidità = behind/ahead, stile = live/consumato.

La tastiera resta com'è: funziona (verificato), e resta il canale per i passi esatti. Il mouse si aggiunge, non sostituisce.

## Lavoro richiesto

### Parte A — Handle mouse sulla turtle

Prima domanda, da rispondere nel report prima di implementare: il layer di handle/drag di `edit-attach` è fattorizzato e riusabile, o è inline nel tool? Se è inline, il primo passo è estrarlo in un modulo condiviso (l'astrazione ora ha i suoi due casi concreti: `edit-attach` e `edit-mesh-split`) senza cambiarne il comportamento in `edit-attach`, con verifica di non-regressione lì. Se è già condiviso, si riusa e basta.

Stessi gesti di `edit-attach` dove hanno senso qui (traslazione lungo gli assi del frame, rotazioni). Nessun gesto nuovo inventato per questo tool: coerenza di famiglia prima di tutto.

**Policy di ricalcolo durante il drag** (l'unica decisione di design nuova della Parte A): i tasti sono eventi discreti, il drag è continuo, e split + 2×`convex?` costa ~12 ms per aggiornamento — dentro il frame per le mesh dell'accertamento, stretto su mesh grosse. Decidere e documentare una policy: throttle del semaforo durante il trascinamento (il piano segue fluido a ogni frame, i colori si aggiornano a cadenza ridotta) oppure ricalcolo pieno solo al rilascio con preview economico durante. Può essere adattiva sul conteggio triangoli. Requisito non negoziabile: i colori non devono mai mentire — se il semaforo non è aggiornato rispetto alla posa mostrata, deve essere visivamente neutro/pending, mai mostrare verde/rosso stantio come se fosse corrente.

### Parte B — Leggibilità behind/ahead

- Le due metà live mantengono la tinta di convessità (verde/rosso), ma si distinguono per **solidità**: `:behind` (ciò che Enter accetta) reso pieno — più opaco/saturo, eventualmente con outline; `:ahead` lavato, quasi ghost. La lettura voluta è figura/sfondo: "Enter prende quello pieno".
- La mnemonica del cono va dichiarata, non lasciata all'intuizione: **il cono punta verso ciò che continua** (heading = `:ahead`); Enter accetta ciò che sta alle spalle della turtle — stessa convenzione di `extrude` (il materiale è dietro). Scriverla nel cheat-sheet del pannello.
- Riga di stato nel pannello con i lati nominati e quantificati, es. `behind 42% · convesso — ahead 58%` (percentuali di volume, già calcolate dal semaforo). Il testo disambigua quando la prospettiva inganna.

### Parte C — Gerarchia live/consumato

- I pezzi accettati perdono **ogni** tinta di convessità al momento dell'accept: la tinta verde/rossa appartiene esclusivamente alle due metà live del taglio corrente. I consumati assumono un unico stile "fatto": wireframe grigio o alpha molto bassa (scegliere quello che regge meglio su scene affollate), uniforme per tutti.
- Il remaining è l'unica mesh piena della scena quando il piano non lo interseca; quando lo interseca, le sue due metà seguono la Parte B.
- Ogni pezzo consumato è etichettato in scena col nome che avrà nella forma emessa (`piece-1`, `piece-2`, …), così la corrispondenza scena→sorgente è visibile prima ancora del commit. Le etichette seguono i nomi effettivi dell'emissione (inclusi quelli preservati in re-entry), non una numerazione parallela.

## Verifica

- Parte A: se il layer viene estratto da `edit-attach`, non-regressione su `edit-attach` verificata live (stessi gesti, stesso comportamento). Drag su mesh piccola e su mesh grossa (~12k+ tri) con la policy di ricalcolo documentata nel report; nessuno stato in cui il semaforo mostra colori non corrispondenti alla posa corrente.
- Parte B/C: verifica live con una sessione multi-taglio sulla forma a plus del brief — a tre pezzi consumati, la scena deve rispondere a colpo d'occhio a: quale lato prende Enter? cosa è già fatto? cosa resta? Le etichette in scena devono coincidere coi nomi nella forma emessa al commit (verificabile confrontando scena e output).
- Round-trip di emissione invariato: il formato emesso non cambia in nulla (l'input mouse produce solo pose, come i tasti) — i test esistenti devono passare senza modifiche.
- Suite completa verde.

## Fuori scope

- Vista esplosa dei pezzi consumati (resta rimandata: il ghosting in place + etichette deve bastare; si riapre solo se l'uso reale dice il contrario).
- Gesti mouse nuovi non presenti in `edit-attach`.
- La proposta V2 (spigoli riflessi) e tutto il resto del fuori scope del brief originale, invariato.

## Alternative considerate e scartate

- **Distinguere behind/ahead con una seconda coppia di tinte.** Scartato: due codifiche cromatiche sullo stesso oggetto (convessità + lato) si sommano in ambiguità; il lato usa la solidità, canale ortogonale alla tinta.
- **Tenere la tinta di convessità sui pezzi consumati.** Scartato: è la causa probabile della confusione attuale — la scena si riempie di verde e il semaforo del taglio corrente annega. La convessità di un pezzo consumato è informazione morta: è già stato accettato.
- **Frecce/etichette 3D sul quad del piano al posto della mnemonica del cono.** Non escluso in linea di principio, ma il cono c'è già e punta già nella direzione giusta: prima si prova a dargli significato (mnemonica documentata + solidità delle metà + riga di stato), che è a costo quasi zero; ornamenti aggiuntivi sul quad solo se questo non basta all'uso.
