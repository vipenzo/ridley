# Addendum 3 — controlli visibili, due stati di scena, billboard solo in reveal

Riguarda la UI di sessione di `edit-mesh-split` (brief albero + brief simmetria). **Supersede**: la Parte B di `addendum-brief-mesh-components-tree.md` (evanescenza dei non-correnti) e la prescrizione world-anchored di `addendum-2-brief-mesh-components-tree.md`. Entrambe falsificate dall'uso reale: l'evanescenza non basta a dare focus, e le etichette ancorate nel mondo finiscono occluse e illeggibili.

## Contesto

Feedback d'uso (Vincenzo): `y`/`d` apparentemente inerti — la simmetria non è risultata usabile; le etichette world-anchored in reveal sono illeggibili (fisse nella geometria, nascoste da altri pezzi); il focus con pezzi evanescenti è ancora troppo affollato.

Ipotesi da verificare su `y`/`d` prima di ogni fix: **precondizioni silenziose**, non bug. `d` richiede un gemello col fratello decomposto (quasi mai vero in una prova casuale → no-op muto); `y` richiede piani verificati dalla PCA (se il pezzo non ne ha, il feedback previsto era forse troppo discreto). La cura è la stessa in entrambi i casi — stato visibile — ma il triage decide se c'è anche un bug da correggere.

## Lavoro richiesto

### Parte A — Triage y/d + controlli visibili con stato esplicito

- **Triage**: determinare e riportare se `y` e `d` sono inerti per bug (focus tastiera, binding) o per precondizioni non soddisfatte. Se bug, correggere.
- **Ogni gesto di sessione ha un bottone nel pannello**: proponi simmetria, decomponi a specchio, separa componenti, undo, reveal, next/prev. La tastiera resta; i bottoni sono l'equivalente scopribile.
- **Stato esplicito sui bottoni**: abilitato/disabilitato; disabilitato mostra la ragione, inline o tooltip ("nessun piano di simmetria verificato su questo pezzo", "il gemello non è ancora decomposto", "nessun gesto da annullare").
- **Nessun no-op silenzioso**: un tasto premuto che non può agire scrive la stessa ragione nella riga di stato del pannello. Mai il silenzio.

### Parte B — Due stati di scena, senza vie di mezzo

- **Stato di lavoro (default)**: visibili SOLO il pezzo corrente (col semaforo) e il piano di taglio. Tutto il resto — aperti, finiti, ghost — nascosto del tutto. Alla navigazione `n`/`p` il nuovo corrente appare e il precedente sparisce: la transizione è il feedback.
- **Stato reveal (toggle)**: tutto visibile — pezzi aperti, finiti ghosted — per orientarsi; si torna al lavoro spegnendo il toggle. Nessuno stato intermedio, nessuna alpha graduata da calibrare.

### Parte C — Etichette billboard, solo in reveal

- Nello stato reveal le etichette tornano **billboard camera-facing** (leggibili sempre): reveal è consultazione, non lavoro — l'occlusione momentanea è accettabile, l'illeggibilità no.
- Nello stato di lavoro: **nessuna etichetta**, mai (l'identità del corrente è nella riga del pannello).
- Il contratto sui nomi (scena in reveal ↔ pannello ↔ emissione) resta invariato.

## Verifica

- Triage y/d riportato con causa accertata (bug vs precondizione) e fix se dovuto.
- Con un pezzo senza piani di simmetria: bottone "proponi simmetria" disabilitato con ragione leggibile; `y` premuto → messaggio in riga di stato, nessun silenzio. Idem per `d` senza gemello decomposto.
- Flusso simmetria completo via soli bottoni (proponi → taglia → decomponi metà → decomponi a specchio sul gemello) su una U simmetrica: usabile senza conoscere la keymap.
- Stato di lavoro: in scena solo corrente + piano; `n`/`p` scambiano la visibilità; nessun altro pezzo mai visibile.
- Reveal: tutto visibile, etichette billboard leggibili da qualunque angolo di camera; spegnendo, si torna al solo corrente senza residui.
- Formato emesso invariato; suite verde.

## Fuori scope

- Flash di contesto automatico alla transizione di focus (mostrare brevemente il reveal quando cambia il corrente): possibile rifinitura se il lavoro "al buio" disorienta — si decide dopo l'uso, non ora.
- Etichette interattive; qualsiasi cambiamento a semantica di taglio, undo, emissione.

## Alternative considerate e scartate

- **Etichette world-anchored con depth-fade** (addendum 2): falsificata dall'uso — fisse nella geometria, occluse, illeggibili.
- **Evanescenza dei non-correnti** (addendum focus): falsificata dall'uso — riduce ma non elimina l'affollamento; i due stati netti la sostituiscono.
- **Solo tastiera con feedback migliore, senza bottoni**: scartata — la scopribilità è il problema tanto quanto il feedback; il bottone disabilitato-con-ragione comunica le precondizioni prima ancora di premere.
