# Mesh-board — documento di design (v2)

Stato: architettura rivista (Vincenzo + Claude, 2026-07) dopo la messa a fuoco "direttiva, non strumento". Sostituisce integralmente la v1; le scelte della v1 superate sono nelle alternative scartate. Incognite residue in fondo.

## Visione

Il mesh-board è il Livello B della famiglia acquisizione: un STL importato viene **convertito progressivamente da dati a codice**. L'endpoint: un programma Ridley che compone pezzi nativi, con zero geometria originale nel file. La conversione è per-pezzo e interrompibile: fermarsi a metà produce un ibrido funzionante.

Principio portante, dall'esperienza dello split: **non si ricostruisce l'oggetto, si ricostruiscono i pezzi**. `mesh-split` produce i problemi piccoli; il board li fa risolvere uno alla volta.

## Architettura: il board è una direttiva di visualizzazione, non uno strumento

Decisione (Vincenzo): `mesh-board` governa la visualizzazione di mesh **scaffold** — come `stamp`, ma tridimensionale e alimentato dall'albero della decomposizione. Non è una sessione modale: è una famiglia di forme nel linguaggio, e il flusso di lavoro è scrivere codice normale accanto a esse.

- `(mesh-board albero)` — mostra tutto l'albero come scaffold, in place.
- `(mesh-board albero {:only [:piece-2 :piece-3]})` — solo alcuni rami/foglie.
- `(mesh-board foglia candidato {:mode :overlay|:intersection|:diff})` — la foglia scaffold **combinata** con la mesh destinata a sostituirla, per il confronto; alla valutazione viene stampata la **fedeltà** (differenza simmetrica %, il macchinario di `mirror?`), e in `:diff` la deviazione è *renderizzata come geometria* — si vede dove lo script devia, non solo quanto.

Il "mini-script del pezzo sostitutivo" è **codice normale nel programma**, valutato come tutto il resto: nessun buffer speciale, nessuna sessione-editor. Lo stato del board vive nel sorgente — riapri il file, rivedi gli stessi scaffold e gli stessi confronti. Riproducibilità per costruzione.

### Primitiva generale di scaffolding, non solo acquisizione

Niente lega l'argomento di `mesh-board` a `mesh-split`: qualunque mesh può fare da scaffold, e più direttive coesistono (accumulatore per-valutazione, come `stamp`). L'acquisizione è il consumatore fondatore, non il confine. Usi generali già identificati: **sagome di controllo** (volume di stampa, ingombri, envelope di tolleranza — geometria di servizio che non deve mai finire nell'output); **controparti di accoppiamento** (progettare il mount col tile multiboard come scaffold: la geometria contro cui combaciare, visibile e misurabile, mai esportata); **regressione visiva dei refactoring** (`(mesh-board versione-vecchia versione-nuova {:mode :diff})` — fedeltà 100% = refactoring pulito, altrimenti il diff mostra dove il pezzo è cambiato: il trap-test geometrico per qualunque programma Ridley).

### Cittadinanza degli scaffold: leggibili, non incorporabili — fondata sull'inerzia referenziale

Gli scaffold appaiono nel viewport ma non sono nel registry: non nominati, non pickabili, esclusi dallo snap strutturale. Restano però **misurabili**: raycast/picking di misura, `face-at`, `distance` — è lettura, non incorporazione (e il mini-accertamento ha verificato che questa parte è gratis: il raycast di misura è scena-based, quello strutturale filtra su registryName).

La garanzia "il riferimento non entra mai nell'output" è fondata sull'**inerzia referenziale del board**, non su un divieto di CSG sui dati (registry e ammissibilità CSG sono ortogonali — scoperta del mini-accertamento — e i pezzi dell'albero DEVONO restare CSG-abili: l'ibrido li usa, `mesh-union` dei pezzi è legittima). Concretamente: (1) `mesh-board` è pass-through — ritorna il suo argomento identico, non altera il flusso dei valori del programma; (2) la pipeline scaffold porta solo dati di display che non rientrano mai in userland; (3) l'export non include la geometria scaffold (da verificare e testare nel brief). L'output dipende solo da ciò che il sorgente calcola, e il board non lo tocca.

### Ciclo di vita: per-valutazione, come stamp

Il board appare perché il sorgente lo dice e scompare quando il sorgente smette di dirlo (call rimossa o commentata + rivalutazione). **Nessun `:off` nel linguaggio**: sarebbe equivalente a cancellare la riga lasciando codice morto — pensiero imperativo in un medium dichiarativo. Il bisogno "zittiscilo senza editare" è **view state**: un toggle di viewport "boards on/off", parente del reveal, che non tocca il sorgente. Due interruttori, due nature: il sorgente decide cosa esiste, la UI decide cosa si guarda.

### Posizionamento: in place di default, si sposta il dato, non il display

Default: **in place** — i pezzi hanno coordinate mondo, lo scaffold sta dove sta l'oggetto, ed è lì che i confronti di fedeltà hanno senso. Ri-posizionamento: **si trasforma l'albero, non il board** — `(mesh-board (attach t (f 10)))`. La posa appartiene al dato (le foglie portano la creation-pose condivisa, che qui trova il suo terzo ruolo: maniglia del gruppo), quindi il movimento appartiene al dato: `attach` su un albero è la trasformazione rigida di gruppo che `transform` già fa sui vettori di mesh (disposizioni relative preservate, replay dalla creation-pose), col vestito funzionale di `attach`. `mesh-board` resta una direttiva di display **pura**: mostra l'albero che riceve, dove giace. L'albero spostato è un valore ordinario, riusabile anche fuori dal display (misura, `split-parts`, confronti). Nota per il brief: `mesh-board` ritorna il suo argomento (pass-through), così si compone nei threading — `(-> t (attach (f 10)) (mesh-board))`.

### Accettazione = riscrittura del sorgente

Confermato da Q1+Q6 dell'accertamento: le foglie non hanno un RHS (nascono per destructuring da una `mesh-split`), quindi "riempire la casella" è una riscrittura — sollevare la foglia nativa in un binding, sostituirne l'uso a valle, e (a ramo completo) far cadere gli split. **Prima manuale, poi assistita**: all'inizio la riscrittura la fa l'utente nell'editor (il confronto resta finché serve, poi si toglie la call); il gesto assistito — con il localizzatore preciso che il tracker già richiede — è una comodità successiva, non un prerequisito.

## Le tre viste (invariate)

1. **Vista contesto** — l'originale ghosted col pezzo corrente acceso al suo posto (inset, brief-acquisition-views).
2. **Vista processo** — l'albero nel pannello, cliccabile, con stati e conteggi (idem). Con l'architettura a direttive, la vista processo deve poter vivere **anche fuori dalla sessione split**, quando un board è attivo nel programma: legge lo stesso dato.
3. **Vista focus** — la vista di lavoro esistente.

## Ciclo di vita dell'acquisizione e caduta dell'impalcatura

Le foglie si convertono una alla volta (l'albero traccia mesh/nativa con fedeltà); quando tutte le foglie di un ramo sono native gli split del ramo cadono; quando cade l'ultimo, cade l'import. La meccanica assistita della caduta richiede il localizzatore preciso (entry nel tracker); la versione manuale è possibile da subito.

## Canali d'ingresso e profili di qualità

(Invariato dalla v1.) Il medium è la mesh; i canali (CAD, scan, fotogrammetria) entrano con profili di qualità diversi. Sopravvivono al rumore gli strumenti volumetrici (profilo A(t), simmetria, `convex?`, `decompose`, `mesh-split`); muoiono i face-based (spigoli riflessi, dedup complanari, converter esatto). Il confronto del board è il caso d'uso nativo dello scan: la deviazione è il rumore che si butta via — fedeltà 97% su uno scan è successo. Requisiti del canale: repair (i buchi sono il gate, non il rumore — Q7), formati PLY/OBJ (loader three.js già in bundle — Q8), calibrazione di scala (erede del righello 2D, componibile — Q9), provenienza come hint. Confine: Ridley inizia alla mesh.

## Alternative considerate e scartate

- **`edit-mesh-board` come sessione modale con workplace** (v1 di questo documento): scartata — sessione-editor con buffer dedicato quando il linguaggio già fa tutto; le direttive stile stamp danno riproducibilità (lo stato è il sorgente), dissolvono il grosso della superficie nuova di Q2, e rendono la garanzia "il riferimento non entra nell'output" strutturale invece che convenzionale.
- **Workplace dentro la sessione split** (v0): scartata prima ancora — sessione monstre, due attività mentali in uno stato solo.
- **`(attach (mesh-board t) …)` — il board come quarto cittadino di attach** (v2 di questo documento): scartato — la posa appartiene al dato, non al display; dare una posa alla direttiva costringeva alla semantica "display eager + ri-posa funzionale" per non mostrare due volte. Trasformare l'albero prima del display (`(mesh-board (attach t …))`) dissolve la sottigliezza e riusa il precedente di `transform` su vettori di mesh.
- **`(mesh-board t :off)` nel linguaggio**: scartato — la presenza nel sorgente È l'interruttore; il muting senza editing è view state (toggle di viewport), non una forma del linguaggio.
- **Posizionamento turtle-relative di default** (board mostrato alla posa corrente, stile stamp puro): scartato — i pezzi hanno coordinate mondo e i confronti sono in place; la posa entra solo quando si ri-posa esplicitamente via attach.
- **Scaffold totalmente inerti** ("non usabili in nessun modo" alla lettera): ammorbidito in "leggibili, non incorporabili" — senza misura sul riferimento il caso fondativo muore.
- **L'albero come struttura dati runtime con slot e API di riempimento**: scartato (v1) — il sorgente è la struttura; confermato a maggior ragione ora.

## Incognite residue (delta rispetto all'accertamento fatto)

1. **`attach` su un valore-albero**: costo di estendere `attach` alle collezioni di mesh (mappa/vettore) con trasformazione rigida di gruppo — il macchinario è quello di `transform` su vettori; verificare il dispatch e la sorte delle pose. Piccola.
2. **Layer scaffold nel renderer**: dove vivono le mesh display-only (fuori dal registry, dentro il viewport) e come si agganciano picking/misura senza aprirle alla CSG. Probabile parentela col ghosting esistente.
3. **Q2 ridimensionata**: del vecchio workplace resta solo l'aggancio del confronto (calcolo fedeltà + mesh differenza alla valutazione — costi già misurati in Q5) e la stampa della fedeltà.
4. Il resto degli esiti dell'accertamento (Q1, Q4, Q5, Q6, Q7–9) resta valido e già acquisito.

## Ordine

1. **Brief viste** (`brief-acquisition-views.md`, già scritto): invariato — l'albero nel pannello serve lo split oggi e le direttive domani; aggiungere in corso d'opera solo la predisposizione a vivere fuori dalla sessione.
2. **Mini-accertamento** sulle incognite 1–2 (valore-board e layer scaffold).
3. **Brief `mesh-board`**: le direttive (board, only, confronto con fedeltà), la cittadinanza, il toggle di viewport.
4. **Brief caduta dell'impalcatura** (riscrittura assistita; prerequisito: localizzatore preciso dal tracker).
