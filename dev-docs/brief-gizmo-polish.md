# Brief: polish del gizmo e snap della pose su vertice

Data: 2026-07-08. Stato: implementato 2026-07-09 (codice + test verdi); restano il tuning visivo a mano in browser con Vincenzo e la verifica funzionale in-browser. Segue brief-edit-attach-handles.md (atterrato). Due lavori: alleggerimento visivo del gizmo, e aggancio preciso della pose a elementi della mesh in modalità origin. Se implementato insieme a brief-stretch-material-frame.md, tenere conto che le maniglie di stretch passeranno sugli assi materiali.

## Contesto

Feedback d'uso sul gizmo appena atterrato: troppo massiccio, occlude la mesh, e non c'è modo di posizionare la pose con precisione su un elemento voluto (per esempio un vertice), che è il prerequisito per usare bene il pivot di stretch e i centri di rotazione.

Il secondo punto non è una feature di visualizzazione: il gizmo sta sulla creation-pose, quindi "centrare il gizmo su un vertice" è spostare la pose su quel vertice, cioè un'operazione della famiglia cp. La forma naturale è un acceleratore della modalità origin: click su un elemento della mesh, la pose ci va, emettendo le cp traslazionali corrispondenti nella lista comandi come qualsiasi altro gesto.

Fatti utili: il raycast di un click sulla mesh restituisce la faccia colpita, e i candidati per lo snap su vertice sono i tre vertici di quella faccia, il più vicino al punto di impatto; niente ricerca globale sul vertex buffer. Il centro faccia è il baricentro della stessa faccia colpita: viene quasi gratis. La semantica del segno cp è già verificata ("il segno segue la pose"): le componenti del delta pose→target sul frame della pose sono direttamente i valori di cp-rt/cp-u/cp-f.

## Lavoro richiesto

### 1. Alleggerimento visivo

- Raggruppare le costanti visive del gizmo (opacità, spessori, raggi, lunghezze) in un unico blocco nominato in testa a gizmo.cljs, così il tuning è un diff di poche righe.
- Obiettivo: la mesh deve restare leggibile attraverso il gizmo. Indicativamente: solidi degli handle semitrasparenti, frecce e anelli più sottili, opacità piena solo sull'handle in hover. I valori finali si fissano a mano in browser con Vincenzo; il criterio di accettazione è la sua approvazione, non un numero.
- Le hit-zone invisibili non si riducono: l'alleggerimento è solo visivo, l'afferrabilità resta quella attuale.

### 2. Snap della pose su vertice (modalità origin)

- In modalità origin, un click senza drag sul corpo della mesh: raycast, faccia colpita, vertice della faccia più vicino al punto di impatto entro una soglia in pixel schermo. Se nessun vertice è entro soglia, il click non fa nulla (e il drag sul vuoto continua a orbitare come oggi).
- Allo snap: si calcola il delta dalla posizione corrente della pose al vertice, se ne prendono le componenti sul frame della pose, e si emettono le cp traslazionali corrispondenti nella lista comandi (solo le componenti non nulle). Arrotondamento a 0.01, più fine del free-round dei drag: qui l'esattezza del bersaglio è il punto, e 0.01 mm è sotto la risoluzione di stampa.
- Un click può emettere fino a tre comandi, quindi fino a tre passi di undo. Accettato e documentato nel pannello di help; raggruppare l'undo per gesto è fuori scope.
- Feedback di hover: in modalità origin, un piccolo marker sul vertice candidato sotto il puntatore (stesso calcolo del click: faccia colpita, vertice più vicino), throttled, attivo solo in origin mode. Senza feedback la feature non è scopribile né mirabile.
- Centro faccia: Alt+click aggancia al baricentro della faccia colpita invece che al vertice (Alt è libero durante la sessione, il picking di viewport è già soppresso da lock-interaction!). Stesso meccanismo, stesso arrotondamento.

## Verifica

- In origin mode, click su un vertice: la pose ci atterra esattamente (entro l'arrotondamento), nella lista compaiono le cp con le componenti giuste, la preview al re-eval coincide, e i Backspace le rimuovono una alla volta riportando indietro la scena.
- Click lontano da ogni vertice (oltre soglia): nessun comando emesso; drag sul vuoto orbita come prima.
- Alt+click su una faccia: la pose atterra sul baricentro.
- Hover in origin mode: il marker appare sul vertice candidato e sparisce uscendo dalla mesh o dalla modalità; in object mode nessun marker e i click sulla mesh non emettono nulla.
- Dopo lo snap, uno stretch dal gizmo stira attorno al vertice agganciato (il pivot segue la pose; con brief-stretch-material-frame.md atterrato, lungo gli assi materiali).
- Tuning visivo approvato in browser; a sessione chiusa nessun residuo (marker di hover inclusi), anche dopo cancel ed errori di eval.
- Suite `npm test` verde.

## Fuori scope

- Allineare l'orientamento della pose alla normale della faccia (snap rotazionale): naturale evoluzione, ma è un'altra semantica (emetterebbe cp rotazionali calcolate) e merita un suo momento.
- Snap su vertice in object mode (sposterebbe l'oggetto invece della pose): nessun caso d'uso chiaro per ora.
- Undo raggruppato per gesto multi-comando.
- Estrazione del gizmo come layer generico: invariato, si estrae al secondo consumer.

## Alternative rigettate

- Ricerca globale del vertice più vicino sull'hover: rigettata, costo O(n) per mousemove su mesh grandi; i tre vertici della faccia colpita dal raycast danno lo stesso risultato percepito a costo O(1).
- Snap dei valori cp sulla griglia di step: rigettata, vanificherebbe l'esattezza del bersaglio che è lo scopo della feature; arrotondamento fine a 0.01 al suo posto.
- Un comando composito unico per lo snap (traslazione arbitraria in un colpo): rigettata, non esiste nel vocabolario e introdurlo per una convenienza di undo non vale il costo; fino a tre cp è onesto e leggibile nel sorgente.
- Ridurre le hit-zone insieme alla stazza visiva: rigettata, l'ergonomia del grab è indipendente dall'ingombro visivo e non va sacrificata.
