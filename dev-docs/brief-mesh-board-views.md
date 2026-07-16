# Brief: viste di confronto per mesh-board (inset multipli)

> **Revisione 2026-07-16 — iterazione 2 (feedback dal primo uso)**: aggiunta la
> **Parte 4** in fondo — un bug (contenuto vuoto non svuota la finestrella) e
> tre migliorie (drag, zoom, wireframe del riferimento dentro la vista).
> Parti 1-3 invariate.

## Contesto

Ridisegno del modo confronto di `mesh-board`, da feedback d'uso reale (Vincenzo,
2026-07-15, caso falsariga: pezzo da STL affettato → costruzione del sostituto
ridley `SOST` sopra di esso, iterando finché combacia). Cosa non funziona oggi:

- **Il wireframe in-place non fa cogliere le differenze**: riferimento e
  risultato-del-modo sono due wireframe sovrapposti indistinguibili; con
  `:intersection` il riferimento intero sembra un'intersezione sbagliata.
  Verdetto d'uso: "poco utile, se mai come opzione".
- **La fedeltà non si vede mai**: `mesh_board.cljs:143` usa il `println` di
  ClojureScript (→ console devtools), non `state/capture-println` (→ pannello
  dell'app). Dentro SCI solo il println *dell'utente* è rimappato
  (`bindings.cljs:550`); il codice compilato no. L'utente itera senza il numero.
- Concettualmente: le viste di confronto sono **del confronto, non della scena**
  — non devono vivere alla posizione dei pezzi. I pezzi stanno sovrapposti in
  place (necessario: la posizione del sostituto è parte della messa a punto),
  ma le viste possono essere finestrelle ovunque.

Infrastruttura già pronta: `viewport/inset.cljs` — picture-in-picture
tool-agnostic ("le viste sono del dato, non di uno strumento"), canvas proprio,
scena propria, camera che copia il quaternion della principale a ogni frame.
Nato per edit-mesh-split, dichiaratamente pensato per il riuso. **Limite: oggi è
un singleton** (un `defonce` atom, un canvas): per viste multiple va
generalizzato a N istanze.

## Parte 1 — Fix println (subito, una riga)

`mesh_board.cljs`: `println` → `state/capture-println` (il ns `state` è già
requirato). La fedeltà appare nel pannello output dell'app a ogni valutazione.

## Parte 2 — Viste di confronto come inset

`(mesh-board riferimento candidato opts)` monta **una finestrella per vista**,
simultanee:

- `:intersection` — parte comune.
- `:missing` — riferimento − candidato: il materiale del pezzo che il sostituto
  non copre ancora (quanto manca).
- `:excess` — candidato − riferimento: dove il sostituto deborda (quanto è di
  troppo).

Due diff **direzionali**, non la differenza simmetrica: per la messa a punto
sono informazioni diverse e complementari.

Dentro gli inset il rendering è **solido** (non wireframe): scene isolate,
niente z-fighting, il solido fa cogliere la forma. Ogni inset ha un'etichetta
con il nome della vista e il **volume** del risultato (es. `missing: 12.3 mm³`)
— più azionabile della sola percentuale. Camera: comportamento inset esistente
(orientamento sincronizzato con la vista principale: ruoti l'oggetto, ruotano i
confronti), fit iniziale sul bounding box del risultato.

Lavoro infrastrutturale: generalizzare `inset.cljs` da singleton a N istanze
(manager con mount!/unmount!/set-content! per-istanza; il frame-callback può
restare uno che aggiorna tutte). Layout: colonna di finestrelle su un bordo del
viewport, nell'ordine di `:views`.

## Parte 3 — Opzioni e default

```clojure
(mesh-board P SOST)                                ; tutte e tre le viste + fedeltà
(mesh-board P SOST {:views [:excess]})             ; solo il debordo
(mesh-board P SOST {:ghost true})                  ; + wireframe in-place, colori per ruolo
(mesh-board P SOST {:views [:missing] :label "…"}) ; label nella stampa fedeltà
```

- **`:views`** — default: tutte (`[:intersection :missing :excess]`), il caso
  d'uso le vuole simultanee. Serve a *ridurre*: ~1 booleana per vista per eval
  (30-100 ms l'una, misurate), su mesh dense poter tenere solo la vista che
  interessa ha valore.
- **`:ghost`** — il wireframe in-place sovrapposto (riferimento + candidato,
  **colori diversi per ruolo**: la coppia grigio/azzurro dell'inset è il
  precedente) diventa **opt-in**, default off. Serve al posizionamento
  grossolano iniziale, poi è rumore.
- **`:mode`** (`:overlay`/`:intersection`/`:diff` in-place) — sostituito da
  `:views` + `:ghost`. Se ne resta un alias di compatibilità o muore, a
  discrezione di Code (la forma è nel manuale da poco; i sorgenti emessi non la
  contengono).
- La stampa fedeltà resta invariata (con Parte 1), una per eval, `:label` per
  disambiguare confronti coesistenti.
- Pass-through invariato: `mesh-board` restituisce il primo argomento.
- La forma show a un argomento (`(mesh-board t)` / `{:only …}`) non cambia:
  scaffold wireframe in place — lì il wireframe è giusto (riferimento nella
  scena, convive con geometria vera).

## Rapporto con edit-mesh-board (futuro)

`edit-mesh-board` come sessione modale è **scartato** da mesh-board-design.md;
sotto quel nome resta il futuro brief di ispezione foglie/ciclo di acquisizione
(stato `:nativo`, caduta degli split). Queste viste sono un tassello di quella
visione — "le viste sono del dato" — e devono restare riusabili tal quali
quando quel brief arriverà.

## Parte 4 — Iterazione 2 (feedback dal primo uso, 2026-07-16)

### 4.1 — BUG: il contenuto vuoto non svuota la finestrella

Quando il risultato di una vista è vuoto (es. intersezione nulla perché i pezzi
non si sovrappongono ancora), l'inset continua a mostrare **l'ultimo contenuto
non vuoto**. Probabile causa: `set-content!` (o il chiamante) salta
l'aggiornamento su mesh vuota (`{:vertices [] :faces []}`) invece di pulire la
scena. Il vuoto è un risultato informativo, non un non-evento — intersezione
vuota durante il posizionamento significa "non vi state ancora toccando", che è
esattamente ciò che l'utente deve vedere. Fix: contenuto vuoto → scena svuotata
+ etichetta esplicita (es. `intersection: vuoto` / `0.0 mm³`).

### 4.2 — Wireframe del riferimento dentro la vista

Oltre al solido della vista (diff/intersezione), ogni finestrella mostra il
**wireframe ghost del riferimento**: dà l'ancoraggio spaziale — fa capire *da
che parte dell'oggetto* sta la diff. Mappa 1:1 sul modello di contenuto che
`inset.cljs` già ha (ghost grigio + evidenziato): ghost = riferimento,
solido = risultato della vista. Il fit iniziale della camera a questo punto va
fatto sul bbox del **riferimento** (stabile), non del risultato (che cambia a
ogni ritocco): le tre finestrelle restano inquadrate uguali mentre si itera.

### 4.3 — Finestrelle trascinabili

Drag col mouse per spostarle altrove nella finestra (sono canvas DOM in
overlay: pointer events su una piccola barra-titolo). Le posizioni sono **view
state**, non linguaggio (stesso principio del "muting è view state" in
mesh-board-design.md): persistono nella sessione, non nel sorgente; un reload
riparte dal layout di default.

### 4.4 — Zoom minimo

Rotella sopra la finestrella = zoom (distanza camera), niente di più. L'inset
resta non-interattivo per rotazione/pan (l'orientamento sincronizzato con la
vista principale è la feature, non un limite). Nota: l'inset oggi è pensato
come non-interattivo — drag (4.3) e wheel (4.4) sono la sua prima interattività;
verificare che gli eventi non filtrino al viewport principale sottostante.

## Documentazione da aggiornare

- `docs/manual/reference/en/mesh-board.md` — nuove opts, semantica delle viste,
  `:ghost`, default.
- `docs/Spec.md` se il confronto vi è descritto.
- `dev-docs/brief-mesh-board.md` — nota in testa che rimanda qui per il modo
  confronto.

## Verifica

- Sul caso reale (STL `mount`, `:piece-2` vs `SOST`): tre finestrelle visibili e
  leggibili, volumi sensati, fedeltà stampata **nel pannello dell'app** a ogni
  eval.
- `:views [:excess]` monta una sola finestrella; rieval incrementale aggiorna
  le viste senza accumulare canvas orfani (unmount pulito, come l'inset attuale).
- `:ghost true` mostra i due wireframe con colori distinti per ruolo.
- Ruotando la vista principale, le finestrelle seguono l'orientamento.
- Costo per eval con le tre viste su mesh realistica: nell'ordine dei 100-300 ms,
  senza degradare l'editing (le viste si calcolano alla valutazione, mai live).
- Un secondo `mesh-board` di confronto nello stesso programma: due gruppi di
  finestrelle distinti, label distinte nella stampa.
- (4.1) Pezzi non sovrapposti → finestrella intersezione VUOTA con etichetta
  esplicita; riavvicinandoli il contenuto ricompare.
- (4.2) Il wireframe del riferimento appare in ogni vista e il fit camera resta
  stabile mentre il candidato cambia.
- (4.3/4.4) Drag e rotella funzionano senza far ruotare/zoomare il viewport
  principale sottostante.
