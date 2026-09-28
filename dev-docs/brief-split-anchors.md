# Brief: anchor di taglio su mesh-split

## Contesto

Decisione del 2026-09-15 (Vincenzo). Obiettivo di lungo periodo: tagliare una mesh e generare giunzioni (spine, tenoni, battute) che permettano di rimontare i pezzi stampati. Prima di progettare qualsiasi contratto per le giunzioni serve il dato che oggi manca: `mesh-split` restituisce i pezzi ma butta via l'informazione su dove li ha tagliati. Questo brief aggiunge solo quel dato. Le giunzioni verranno scritte a mano da Vincenzo con i mezzi ordinari di Ridley (`on-anchors`, `slice-mesh`, `shape-offset`, `attach`, `mesh-difference`) e il contratto, se mai, si estrarrà da quegli esempi. Nessuna API per le giunzioni in questo brief.

Vincolo assoluto: **retrocompatibilità totale** con la `mesh-split` di oggi. Il valore di ritorno non cambia (`{:behind :ahead}`, composito annidato con path, ecc.), `split-tree` e `split-parts` non cambiano, il codice emesso da `edit-mesh-split` non cambia. Gli anchor vivono sulla mesh, non nel valore di ritorno: chi non li usa non nota nulla.

Fatti verificati sul sorgente prima di questo brief:

- I pezzi ereditano già `creation-pose`, `material` e `anchors` della mesh sorgente via `carry-meta` (`manifold/core.cljs`, `split-by-plane` e `split-live`). Questa ereditarietà resta tale e quale: gli anchor di taglio si **aggiungono** a quelli ereditati.
- `split-plan` (`editor/implicit.cljs`) lavora su un piano risolto da `resolve-mesh-split-plan`, i cui step sono `{:mark :pose :sub}` con `:pose` = `{:position :heading :up}`. Il nome del mark e la posa completa del taglio sono quindi già disponibili nel punto in cui si taglia.
- La forma a 1 arità e la forma `(mesh-split m nil nil opts)` tagliano alla posa viva della tartaruga, senza mark: lì un nome va inventato.

## Lavoro richiesto

### Parte 1: l'anchor di taglio

Ogni taglio produce un anchor su **entrambi** i pezzi che genera:

- **Posizione**: la posizione del piano di taglio (la `:position` della posa del taglio).
- **Heading**: la normale del piano, orientata **uscente dal pezzo**. Sul pezzo `:ahead` coincide con l'heading del taglio negato (il materiale di `:ahead` sta dalla parte dell'heading, quindi la normale uscente punta indietro); sul pezzo `:behind` coincide con l'heading del taglio. Da verificare allo schermo, non per deduzione: la regola operativa è che `(attach x (on-anchor ...) (f 10))` deve portare `x` fuori dal pezzo, mai dentro.
- **Up**: l'`:up` della posa del taglio, identico sui due pezzi, così il frame 2D della faccia (right, up) è lo stesso da entrambi i lati a meno del segno di right. Se la convenzione di Ridley per un anchor con heading negato impone di rifare right/up, documentare la scelta.

Stesso nome sui due pezzi. È voluto: una giunzione si scrive una volta e si applica simmetricamente.

### Parte 2: i nomi

- **Con path**: il nome dell'anchor è il nome del mark (`:cut-1` resta `:cut-1`). Nessun prefisso, nessuna trasformazione.
- **Taglio singolo** (1 arità, o `path` nil con opts): nome `:cut`.
- **Collisione** con un anchor ereditato dello stesso nome: vince l'anchor di taglio. Da segnalare in console come warning, non come errore.
- **Rami**: il pezzo staccato a `:cut-1` e poi ritagliato a `:cut-1-1` porta entrambi gli anchor, `:cut-1` (dalla faccia che lo ha staccato) e `:cut-1-1`. Il pezzo di mezzo di una catena lineare porta `:cut-1` e `:cut-2`. In generale un pezzo porta un anchor per ogni faccia di taglio che lo delimita.
- **Pezzo vuoto** (il piano manca la mesh): nessun anchor, la mesh vuota resta com'è.

### Parte 3: dove passa

- `split-plan` è il punto naturale: ha mark e posa. La 1-arità e la forma opts-senza-path passano da `split-by-plane` direttamente e vanno coperte anche loro.
- `edit-mesh-split` non ha bisogno di modifiche per il round-trip (emette una `mesh-split` che, rivalutata, porta gli anchor). Se la sessione interattiva costruisce i pezzi via `split-live` e li consegna direttamente, valutare se conviene annotare anche lì o se basta la rivalutazione. Non è richiesto in questo brief, ma va detto quale delle due strade si è presa.
- `mesh-components` non aggiunge anchor: non è un taglio. Le componenti ereditano gli anchor del pezzo da cui vengono, compresi quelli di taglio, anche se per alcune componenti l'anchor punta a una faccia che non è la loro. Accettato, per ora: separare gli anchor per componente è un'altra funzionalità.

## Verifica

Allo schermo, non solo a REPL:

1. Taglio singolo su un `(box 40 40 40)`: `(mesh-split b)` dà due pezzi con anchor `:cut`; `(on-anchors (:ahead halves) :cut (cyl 5 10))` e lo stesso su `:behind` mettono il cilindro **fuori** dal rispettivo pezzo, da parti opposte del piano.
2. Path con due mark su un pezzo lungo: `split-tree` dà tre pezzi; `:piece-1` ha `:cut-1`, `:piece-2` ha `:cut-1` e `:cut-2`, `:piece-3` ha `:cut-2`. Normali uscenti verificate come al punto 1.
3. Ramo (`{:cut-1 (path ...)}`): il pezzo staccato e ritagliato porta sia `:cut-1` che il mark del sotto-percorso.
4. Ereditarietà: una mesh con un anchor `:foot` tagliata in due: `:foot` è presente su entrambi i pezzi come prima del brief, accanto a `:cut`.
5. Collisione: mesh con anchor `:cut` preesistente, taglio singolo: warning in console, l'anchor risultante è quello di taglio.
6. Non regressione: tutti gli esempi del manuale che usano `mesh-split` (cap. 7.8, cap. 18) producono la stessa geometria di prima; `edit-mesh-split` ri-entra sulla sua emissione come oggi.
7. Il primo uso reale: `(slice-mesh piece)` dopo `(move-to piece :at :cut)` (o l'equivalente con la tartaruga portata sull'anchor) restituisce il contorno della faccia di taglio. È il mattone con cui Vincenzo scriverà le giunzioni a mano; se non funziona in modo naturale, dirlo nel report.

## Fuori scopo

Contratto joint-fn, giunzioni built-in, posizionamento automatico dei perni, anteprima delle giunzioni nell'editor. Tutto dopo, a valle degli esempi scritti a mano.
