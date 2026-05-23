# Brief: navigazione locale (TOC) nei capitoli del manuale

## Contesto

I capitoli del manuale sono lunghi (8-10 sezioni ciascuno) e scrollare per trovare un punto preciso è scomodo. Serve una navigazione locale che permetta di saltare a una sezione senza scrollare tutto il capitolo.

## Obiettivo

Un bottone discreto nell'header del capitolo che apre un piccolo popup con l'elenco delle sezioni. Cliccando su una voce, il pannello scrolla alla sezione corrispondente e il popup si chiude.

## Design

- **Bottone**: un'icona compatta (es. "§", "≡", o un'icona outline tipo "list") posizionata nell'header del capitolo (accanto al titolo `#` o nella barra di navigazione del pannello). Deve essere visibile ma non dominante.
- **Popup**: una lista delle sezioni del capitolo, derivata dagli heading Markdown (`##` e `###`). I `###` sono indentati rispetto ai `##` per mostrare la gerarchia. Il popup si chiude al click su una voce o al click fuori.
- **Scroll**: al click su una voce, scroll smooth alla sezione corrispondente nel pannello. L'heading target deve finire visibile in cima all'area di scroll (o vicino).
- **Generazione automatica**: il TOC viene generato dal renderer a partire dagli heading presenti nel Markdown. Non serve nessun markup aggiuntivo nei file `.md`.

## Implementazione

Il renderer Markdown del pannello già parsa gli heading per il rendering. Il lavoro è:

1. Durante il parsing, raccogliere gli heading `##` e `###` con il loro testo e generare un id di ancoraggio per ciascuno (se non già presente).
2. Renderizzare il bottone TOC nell'header del capitolo.
3. Al click, mostrare un popup posizionato sotto il bottone con la lista delle sezioni.
4. Al click su una voce, scrollare al relativo ancoraggio e chiudere il popup.

## Fuori scope

- TOC visibile permanentemente (sidebar, colonna laterale). Troppo invasivo per il pannello.
- TOC nei file `.md` stessi (non vogliamo duplicazione manuale).
- Navigazione inter-capitolo (quella c'è già nel pannello).

## Riferimenti

I capitoli più lunghi dove il TOC sarà più utile: cap 2 (8 sezioni), cap 3 (11 sezioni), cap 4 (9 sezioni), cap 5 (8 sezioni), cap 6 (10 sezioni).
