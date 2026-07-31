# Brief: piani ancorati al palcoscenico (mark-piano)

Aperto 2026-07-30 (Vincenzo + Claude-docs). Figlio diretto del v1 di
edit-acquire: nasce da un buco scoperto scrivendo la guida utente
(`docs/manual/guides/it/19-acquisire-dalle-foto.md`, bozza).

## Il buco

Col proxy **box** il ricalco ha sempre avuto i suoi piani di lavoro gratis: le
facce del box abbracciano l'oggetto, e `(turtle (:top (:faces A)) (edit-path-2d))`
lavora su una superficie che coincide (circa) con una faccia vera del pezzo.

Col **piatto di registrazione** — che è la via primaria consigliata dal v1 in
poi — il proxy sta *sotto* l'oggetto: l'unico piano che offre è il proprio top
(z=0, la base d'appoggio). Per ricalcare una faccia dell'oggetto non c'è nessun
piano dichiarabile. Il piatto ha vinto come **ancora di registrazione** ma non
dà nulla come **ipotesi di misura** (la distinzione dei due ruoli è già nel
brief v1, sezione "Due astrazioni obbligatorie" — questo brief riempie il
secondo ruolo per il caso piatto).

## La rappresentazione (decisa, non riaprire)

La rappresentazione Ridley di una zona piana dell'oggetto è **un mark con la
normale come heading**: `{:position <centroide> :heading <normale> :up <in-piano>}`.

Motivi:
- è l'ancora universale già esistente: niente DSL nuovo;
- finisce nello slot `:marks` che la form `(acquire …)` già emette e già
  destruttura per nome;
- `(turtle (:zona-x (:marks A)) (edit-path-2d))` funziona **oggi**, senza
  toccare il ricalco;
- la caduta dell'impalcatura resta identica (inline deliberato del letterale).

Un mark-piano non è un tipo nuovo: è un mark nominato la cui direzione è la
normale di una superficie invece che l'euristica corrente. Al consumatore non
importa la differenza.

## La scala dei tre gradini (design)

### Gradino 1 — piano per punti (IL PERIMETRO DI QUESTO BRIEF)

Gesto manuale, robusto, senza computer vision:

1. L'utente clicca **≥3 punti** della zona piana, ciascuno su **≥2 foto**
   registrate (stesso punto fisico, foto diverse).
2. Ogni punto si **triangola** dai raggi delle camere registrate (camere
   calibrate, scala mm dal piatto): minimi quadrati lineare minuscolo,
   `linalg/solve` basta.
3. Ai punti triangolati si adatta il piano con **`plane-frame`**
   (`photogrammetry/pnp.cljs`) — centroide + normale senza autovettori,
   già scritta, già testata.
4. Il risultato è il mark-piano, nominato dall'utente, emesso in `:marks`.

**Caso speciale a 1 click — piano parallelo al piatto**: se la zona è
parallela alla base (oggetto appoggiato → vale per quasi tutte le facce
superiori), basta UN punto triangolato + la normale del piatto. Gesto
separato o opzione del gesto base, da decidere in implementazione.

**Verifica ergonomica gratis (stesso trucco della riproiezione live)**: alla
creazione, il palcoscenico disegna un **dischetto traslucido** (o griglia) sul
piano candidato; l'utente naviga le foto con `[`/`]`; se il dischetto resta
incollato alla superficie da ogni angolazione, il piano è giusto. Accetta →
mark emesso; altrimenti si riclicca il punto peggiore (riportare il residuo
per-punto della triangolazione, come fa già il PnP con `:per-point`).

**Guardie minime**:
- un punto con raggi quasi paralleli (foto angolarmente vicine) triangola male
  → richiedere un angolo minimo tra i raggi o segnalare il condizionamento;
- 3 punti quasi collineari non definiscono un piano → `plane-frame` torna già
  nil sui degeneri, propagare l'errore in modo leggibile;
- residuo di planarità (distanza max punto-piano) riportato accanto al nome:
  una "zona piana" che piana non è deve dirlo subito.

### Gradino 2 — click assistito (fuori perimetro, non riaprire il design)

Stesso gesto del gradino 1, ma il click viene raffinato cercando la
corrispondenza precisa nelle altre foto attorno al punto indicato (la
geometria epipolare tra camere note restringe la ricerca a una retta).
Gemello concettuale di `blob-snap`. Riduce la precisione richiesta alla mano,
non cambia né gesto né rappresentazione: si innesta dopo, senza buttare nulla.

### Gradino 3 — riconoscimento automatico della superficie (fuori perimetro)

Tra due camere registrate una zona piana lega le immagini con un'omografia
completamente determinata dal piano: corrispondenze di feature in una regione
+ fit dell'omografia (RANSAC) → piano in forma chiusa, senza click.
**Caveat onesto**: richiede texture. La plastica liscia uniforme — il bersaglio
tipico del canale — è il caso peggiore per il matching fotometrico (il piatto
funziona proprio perché i dischetti sono contrasto *per costruzione*). Quindi:
brillerebbe sugli oggetti texturati, deve fallire con grazia sugli altri e
ricadere sul gradino 1. Esplorazione, non impegno.

## La decisione architetturale aperta (Vincenzo)

Dove vive il gesto? Due opzioni, con una tensione nota:

- **(A) Sul palcoscenico** — coerente col principio non negoziabile del v1
  ("edit-acquire = SOLO registrazione, poi si chiude"; la misura appartiene al
  palcoscenico). MA il palcoscenico oggi **non scrive nel sorgente**: creare
  mark da lì richiede il write-back nella form `(acquire …)` (aggiornare la
  mappa `:marks` nel buffer). Capacità nuova, da pesare.
- **(B) In edit-acquire (modalità `k` estesa)** — riusa la macchina dei mark
  nominati che già scrive la form al confirm. Pragmatica, ma allunga la vita a
  un pezzo di sessione modale che la direzione P4b vorrebbe prosciugare (il
  `:retrace` modale è già in rimozione).

Il costo del write-back (A) va stimato da Code PRIMA di scegliere: se è
piccolo, (A) è la strada giusta; se è grosso, (B) come ponte con migrazione
dichiarata.

### Appendice — la questione di naming (Vincenzo, 2026-07-30)

Se vince (A), il palcoscenico acquisisce un gesto che scrive nel sorgente e
si apre la domanda sui nomi. Proposta discussa: `register-acquire` (o
`align-acquire`) per la registrazione, `acquire` come palcoscenico puro,
`edit-acquire` per il palcoscenico che edita. Punti fermi emersi dalla
discussione (Claude-docs + Vincenzo):

- una forma `edit-*` PERMANENTE nel sorgente romperebbe la convenzione di
  prodotto (i marcatori edit- sono transitori: vivono solo a sessione aperta
  e si strippano al confirm) → il nome della forma persistente resta `acquire`;
- rinominare la registrazione rompe il gesto uniforme "aggiungi edit- davanti
  per riaprire" (il round-trip di edit-mesh-split e famiglia);
- se un rinomino della registrazione servisse davvero ("edit" promette più
  della sola registrazione camere), `register-acquire` > `align-acquire`:
  "registrazione" è il termine di dominio del canale, "align" descrive solo
  il gesto del box.

Decisione: il naming si decide INSIEME ad A/B, non prima. Con (B) i nomi
attuali restano giusti così come sono.

## Accertamenti per Code (working tree, prima di partire)

1. **Come nasce oggi un mark nominato** (modalità `k` in `edit_acquire.cljs`):
   il click avviene su un piano già dichiarato o esiste già una triangolazione
   libera multi-foto? Se il primo: la triangolazione da raggi è il primo
   mattone da scrivere (stimare: piccolo).
2. **Cosa espone `(:faces A)` col proxy piatto**: solo il top o anche
   bottom/rim? (Per il caso "piano parallelo al piatto" serve la normale della
   base in forma comoda.)
3. **Direzione dei mark attuali**: da dove viene lo heading di un mark emesso
   oggi? (Il mark-piano deve solo sostituire quella sorgente con la normale
   fittata.)
4. **Costo del write-back palcoscenico → form** (per la decisione A/B):
   l'infrastruttura di `replace-source!`/`find-form-bounds` usata al confirm di
   P4a è riusabile fuori dalla sessione modale?
5. **Residui e condizionamento**: la triangolazione riporti per-punto il
   residuo (px) e l'angolo minimo tra i raggi, nello stile `:per-point` del PnP.

## Gate umano (giudice: Vincenzo)

Sessione vera col piatto (es. `test-assets/param-plate-paper`): creare un
mark-piano sulla faccia superiore dell'oggetto con 3 click × 2 foto, verificare
col dischetto traslucido navigando le foto, aprire
`(turtle (:zona (:marks A)) (edit-path-2d))` e ricalcare un contorno.
Criteri: (1) il gesto è ragionevole? (2) il dischetto resta incollato alla
superficie in tutte le viste? (3) il ricalco sul piano fittato combacia con
l'oggetto almeno quanto combaciava sul box?

## Nota di coda

Se il gradino 1 passa, la guida utente (cap. 19, §19.6) va aggiornata: oggi
presenta i mark nominati al passato prossimo del box; col mark-piano il piatto
diventa autosufficiente e la sezione si riscrive attorno a questo gesto.
