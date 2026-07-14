# Brief: viste di contesto e di processo — dove sono nell'oggetto, dove sono nel processo

## Contesto

Primo brief della fase 3 (mesh-board), come da ordine in `dev-docs/mesh-board-design.md`. Diagnosi che lo motiva (Vincenzo, dall'uso reale): i meccanismi della decomposizione sono sofisticati ma si perde il filo — mancano la visione dell'oggetto ("dove sono nell'oggetto") e la visione del processo ("quanti rami, quante foglie chiuse, quanto manca"). Le viste sono **del dato** (l'albero della decomposizione), non di uno strumento: nascono nella sessione di `edit-mesh-split` e serviranno identiche a `edit-mesh-board`.

Fondamenta dall'accertamento mesh-board:
- **Q1**: il valore reificato è la mappa piatta nome→mesh; la topologia è ri-derivabile via `mtree` — i dati per le viste sono nello stato di sessione, gratis.
- **Q3**: il pannello è DOM; la lista-albero è un widget da costruire, ma senza ostacoli di substrato.

Convenzioni ereditate: addendum 3 (il click in *viewport* resta monofunzione — il click nel *pannello* è legittimo ed è già il canale dei bottoni; nessun no-op silenzioso; nomi coerenti scena/pannello/emissione).

## Lavoro richiesto

### Parte 1 — Vista contesto: inset con l'originale

Un **inset** (mini-viewport in un angolo, stile picture-in-picture) sempre disponibile durante la sessione, che mostra: la **mesh originale ghosted** + il **pezzo corrente acceso al suo posto** dentro di essa. Risponde a "dove sono nell'oggetto" *mentre* si lavora, senza uscire dal focus.

- **Camera sincronizzata** con la viewport principale: stessa orientazione → la mappa mentale tra inset e scena è immediata, nessun secondo orbit da gestire.
- **Non interattivo**: è un aiuto d'orientamento, non una seconda scena. Nessun picking, nessun gesto.
- Aggiornamento: al cambio di pezzo corrente (`n`/`p`, click su foglia della Parte 2), dopo taglio/separazione/undo.
- Toggle per nasconderlo (default: visibile); lo stato del toggle non tocca la scena principale.
- La regola di focus della vista di lavoro (solo corrente + piano) resta **invariata**: l'inset è cornice, non scena.

### Parte 2 — Vista processo: l'albero nel pannello

Widget DOM nel pannello, costruito da `mtree`:

- **Struttura**: l'albero della decomposizione, con per ogni nodo nome (quello dell'emissione), stato — aperto / finito, con l'enum **predisposto** per lo stato `:nativo` che arriverà col board — e il corrente evidenziato.
- **Conteggi** in testa: `N aperte · M finite` — il "quanto manca" a colpo d'occhio.
- **Click su una foglia aperta = selezione** (accesso diretto, supera la navigazione cieca per adiacenza di `n`/`p`); le foglie finite sono in stile disabilitato e il click è inerte per ora (l'ispezione dei finiti arriverà col board, non qui).
- **L'ordine visuale dell'albero È l'ordine del ciclo `n`/`p`**: una sola verità sull'ordinamento, così il tasto e il click raccontano la stessa storia.
- Sincronizzazione con ogni gesto strutturale (taglio, separazione, undo, replay a specchio): l'albero non mostra mai uno stato stantio.

## Verifica

- Live end-to-end su una sessione multi-ramo (sintetico tipo mount): l'inset mostra il corrente al suo posto nell'originale e la sincronizzazione della camera si verifica ruotando la vista principale; i conteggi dell'albero restano corretti attraverso tagli, separazione componenti, undo e replay a specchio.
- Click su foglia aperta → selezione e aggiornamento coerente di focus, inset, riga di posizione; click su foglia finita → inerte, stile disabilitato.
- Coerenza dei nomi: albero del pannello, riga di posizione, etichette in reveal e forma emessa mostrano gli stessi nomi.
- La vista di lavoro resta solo corrente + piano (regola addendum 3 invariata); il toggle dell'inset non ha effetti collaterali sullo stato di sessione.
- Nessun cambiamento al formato emesso; test WASM con idioma skip, citare l'entry di `code-issues.md`; suite completa verde.

## Fuori scope

- Lo stato `:nativo` funzionante e l'ispezione delle foglie finite: arrivano col brief `edit-mesh-board` (qui solo l'enum predisposto).
- Inset interattivo (orbit indipendente, picking nell'inset).
- Minimap/vista schematica alternative; qualsiasi cambiamento a semantica di taglio, undo, emissione.

## Alternative considerate e scartate

- **Arricchire il reveal come vista contesto** (originale ghosted dentro lo stato reveal): scartato — il reveal è un toggle che si visita e si abbandona, mentre "dove sono" è una domanda *continua* durante il lavoro; l'inset risponde senza mai lasciare il focus.
- **Inset con camera indipendente**: scartato — un secondo orbit da gestire e una mappa mentale più cara; la sincronizzazione dà l'orientamento gratis.
- **Vista albero come overlay 3D in viewport**: scartato — l'albero è informazione astratta, il pannello DOM è il suo posto (e Q3 conferma che i dati sono gratis via `mtree`); la viewport resta monofunzione.
