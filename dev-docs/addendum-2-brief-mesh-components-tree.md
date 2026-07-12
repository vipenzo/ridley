# Addendum 2 a brief-mesh-components-tree — etichette in scena non invasive

## Contesto

Questo documento **supersede** le prescrizioni sulle etichette in scena di `addendum-brief-edit-mesh-split.md` (Parte C, "pezzi consumati etichettati in scena") e `addendum-brief-mesh-components-tree.md` (Parte B, "ghosted con etichetta"). Quelle prescrizioni sono la ragione per cui i billboard coi nomi sono stati (correttamente) reintrodotti dopo una richiesta verbale di rimozione: i documenti vincono sulle richieste verbali, quindi la decisione nuova va a verbale qui.

Il problema misurato sull'uso reale: i pannelli billboard che seguono la camera **occludono la vista e rendono impossibile lavorare**. Il valore che le etichette dovevano dare — la corrispondenza dei nomi tra scena, pannello e codice emesso — resta un requisito; il veicolo "cartelli permanenti camera-facing" è bocciato.

## Lavoro richiesto

- **Default: nessuna etichetta flottante in scena.** L'identità dei pezzi vive nel pannello (riga di posizione con nome, come da addendum focus) — è lì che si legge "chi è" il pezzo corrente.
- **Etichette on-demand, sotto il toggle di rivelazione** (quello dell'addendum focus): quando il toggle è attivo, le etichette appaiono; quando si spegne, spariscono. Nessuno stato intermedio persistente.
- **Mai billboard, mai camera-facing.** Quando visibili, le etichette sono ancorate nel mondo (orientamento fisso, es. giacenti su un piano scelto una volta o allineate agli assi mondo), piccole, posate al centroide del pezzo, con dissolvenza in profondità o occlusion-test perché non si accumulino davanti alla geometria. Il requisito minimo non negoziabile: non seguono la camera.
- La corrispondenza dei nomi scena↔pannello↔emissione resta invariata come contratto: cambia solo quando e come le etichette si mostrano.

## Verifica

- Sessione di lavoro completa (tagli, navigazione, mouse sul piano) con toggle spento: nessuna etichetta visibile, nessuna occlusione.
- Toggle acceso: etichette visibili, world-anchored, verificato ruotando la camera che NON la seguono; toggle spento: spariscono senza residui.
- I nomi mostrati coincidono con pannello e forma emessa (contratto invariato).
- Nessun cambiamento al formato emesso; suite verde.

## Fuori scope

- Qualsiasi altro elemento di presentazione (focus, evanescenza, semaforo): invariati dagli addendum precedenti.
- Etichette interattive/cliccabili: no — il click in viewport resta monofunzione (piano).

## Alternative considerate e scartate

- **Billboard permanenti camera-facing** (stato attuale): bocciati dall'uso reale — occludono e rendono il lavoro impossibile.
- **Billboard più piccoli/trasparenti ma sempre visibili**: mitigazione, non soluzione — la densità di pezzi cresce con la decomposizione e l'occlusione torna; il default pulito è nessuna etichetta.
- **Rimozione totale delle etichette anche dal toggle**: scartata — la corrispondenza visiva dei nomi resta utile per orientarsi prima del commit; on-demand è il compromesso giusto.
