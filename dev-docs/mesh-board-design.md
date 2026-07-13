# Mesh-board — documento di design

Stato: architettura concordata (Vincenzo + Claude, 2026-07), pre-accertamento. Questo documento fissa la visione e le decisioni; le incognite in fondo vanno misurate prima dei brief.

## Visione

Il mesh-board è il Livello B della famiglia acquisizione: un STL importato viene **convertito progressivamente da dati a codice**. L'endpoint: un programma Ridley che compone pezzi nativi, con zero geometria originale nel file. La conversione è per-pezzo e interrompibile: fermarsi a metà (tre foglie native, due ancora mesh) produce comunque un ibrido funzionante e più leggero.

Il principio che regge tutto, emerso dall'esperienza dello split: **non si ricostruisce l'oggetto, si ricostruiscono i pezzi**. `mesh-split` non produce il risultato — produce *i problemi piccoli*; il mesh-board li risolve uno alla volta. La simmetria dimezza il lavoro, `decompose` separa il separabile, e il pezzo singolo (convesso o quasi) è alla portata di "prendo due misure e scrivo `(extrude (hexagon 8) 12)`".

## Architettura: due strumenti sequenziali, un dato condiviso

Decisione primaria (proposta Vincenzo): `edit-mesh-split` e `edit-mesh-board` restano **due strumenti distinti e sequenziali**, e il tramite tra loro è **un dato: l'albero della decomposizione** — che coincide con la forma emessa. Il palleggio è tra due editor dello stesso sorgente:

- **mesh-split scompone**: crea le caselle. Emette il let a catena che produce i pezzi nominati.
- **mesh-board riempie**: prende in rassegna i pezzi e, uno (o più) alla volta, li confronta con mesh costruite da un mini-script; all'accettazione la casella si riempie: il binding del pezzo diventa codice costruttivo.

Meno UI, più codice: ogni strumento resta piccolo, lo stato intermedio vive nel sorgente (ispezionabile, versionabile, riapribile domani), e nessuna sessione deve sapere tutto. Costo accettato: scoprire durante il board che serve un taglio in più significa uscire e rientrare nello split su quel pezzo — il re-entry è economico, è il prezzo della semplicità dei tool.

### La reificazione dell'albero (decisione da completare in accertamento)

La forma emessa dallo split ha sempre avuto il body del let non specificato (`…`). La proposta: **il body restituisce la decomposizione come valore** — i pezzi nominati in una struttura (mappa nome→mesh, con o senza la topologia dell'albero). Quel valore è ciò che `edit-mesh-board` riceve e prende in rassegna. Da definire in accertamento: forma esatta (mappa piatta di foglie basta? serve la struttura dei rami per le viste?), e come il board localizza nel sorgente il binding da sostituire.

## Le tre viste (infrastruttura condivisa, non proprietà di un tool)

Diagnosi (Vincenzo): i meccanismi sono sofisticati ma si perde il filo — manca la visione del processo nel suo insieme. Due viste mancanti, più quella esistente:

1. **Vista contesto — "dove sono nell'oggetto"**: l'originale ghosted con il pezzo corrente acceso al suo posto. Il contesto spaziale che l'addendum 3 ha correttamente tolto dalla vista di lavoro vive qui, in una vista dedicata.
2. **Vista processo — "quanto manca"**: l'albero come diagramma nel pannello — rami, foglie chiuse/aperte/native, conteggi. È anche **navigazione**: click su una foglia nel pannello = accesso diretto (legittimo: la monofunzione del click riguarda la viewport). Supera il limite della navigazione cieca per adiacenza di `n`/`p`.
3. **Vista focus — "cosa sto facendo"**: quella esistente (solo pezzo corrente + piano nello split; pezzo + script nel board).

Le viste sono **del dato** (l'albero), non di uno strumento: le stesse tre servono split e board. Le prime due, da sole, sono un brief piccolo e utile subito.

## Il workplace (l'operazione nuova del board)

Per la foglia corrente:

- La foglia fa da **riferimento ghosted**.
- Un **mini-script** — Ridley vero, tutto il linguaggio, non un sotto-linguaggio da tool — viene scritto in un buffer di sessione e valutato; il risultato si mostra **in confronto** col pezzo: sovrapposizione, intersezione, differenza (tre modi di vista sulle stesse booleane).
- La **metrica di fedeltà** riusa la differenza simmetrica di `mirror?`: "lo script copre il 99.2% del pezzo" — e la differenza *renderizzata come geometria* mostra **dove** lo script devia, non solo quanto.
- **Accettazione = sostituzione**: il binding della foglia diventa lo script (con la fedeltà a commento). Il pezzo-dato resta disponibile finché gli split girano — è il riferimento per i confronti futuri.

Strumenti di misura al servizio dello script: picking, `face-at`, `distance`, `bounds`, `section-area` sul riferimento — l'equivalente 3D del righello di calibrazione di `edit-image-board`.

## Ciclo di vita e caduta dell'impalcatura

- Le foglie si convertono una alla volta; l'albero traccia lo stato (mesh / nativa, con fedeltà).
- Quando **tutte** le foglie di un ramo sono native, gli split di quel ramo non servono più: l'impalcatura può **cadere** — la forma si riscrive componendo i pezzi nativi (le pose dei tagli non servono più a tagliare, al più restano come frame di assemblaggio). Quando cade l'ultimo split, cade anche il `decode-mesh`/`import-stl`: acquisizione completa.
- La meccanica della caduta (chi riscrive la forma, quando, con che conferma) è materia d'accertamento.

## Canali d'ingresso e profili di qualità

Il meccanismo di acquisizione è agnostico rispetto alla provenienza: il medium è la mesh. I canali previsti — STL da CAD, scansioni 3D, mesh da fotogrammetria — entrano nella stessa pipeline con profili di qualità diversi, e gli strumenti si dividono di conseguenza:

- **Sopravvivono al rumore gli strumenti volumetrici/integrali**: profilo di sezione A(t) (integrale, media il rumore), simmetria (differenza simmetrica volumetrica, tollerante per costruzione), `convex?` (hull-ratio), `decompose`, `mesh-split`.
- **Muoiono sul rumore gli strumenti face-based**: candidati da spigoli riflessi (ogni spigolo di uno scan è pseudo-riflesso per rumore), dedup dei complanari, converter esatto facce→piani. Assumono la verità CAD ("le facce piatte esistono").
- **Il workplace è il caso d'uso nativo dello scan**: sulla scansione la deviazione dello script non è errore — è il rumore che si butta via. "Fedeltà 97%" su uno scan è successo, non fallimento. La semantica della metrica cambia col canale, il meccanismo no.

Requisiti nuovi del canale scan: **condizionamento all'ingresso** (Manifold esige watertight/manifold; gli scan spesso no — serve una storia di repair, o un rifiuto con errore leggibile); **calibrazione di scala** (la fotogrammetria è scale-free: è il righello di `edit-image-board` portato in 3D — "questo spigolo è 50 mm"); **formati** (PLY/OBJ oltre a STL); **provenienza come hint** (`:cad`/`:scan` sulla mesh importata: i gesti face-based si disabilitano con la ragione scritta, stile addendum 3).

Confine dichiarato: **Ridley inizia alla mesh**. Foto→mesh (fotogrammetria, app di scansione) resta a monte in strumenti esterni; si documenta il percorso, non lo si implementa — lo stesso confine di `edit-image-board`, che prende l'immagine e non la fotocamera.

## Alternative considerate e scartate

- **Workplace dentro la sessione split** (un solo mega-tool con l'operazione "sostituisci"): scartato — sessione monstre, due attività mentalmente diverse (scomporre vs modellare) forzate in uno stato solo, e il medium diventa lo stato di sessione invece del sorgente. La versione a due strumenti è più Ridley: meno UI, più codice.
- **Mesh-board come scaffold libero sull'oggetto intero** (l'idea originale del Livello B, pre-split): superata — ricostruire l'intero è il problema grande; la decomposizione lo riduce a problemi piccoli. Resta possibile *anche* sull'oggetto intero (un albero a foglia sola).
- **L'albero come struttura dati runtime separata dal sorgente** (slot da riempire con API dedicate): scartato — introdurrebbe una seconda verità; il sorgente emesso È la struttura, i tool ne sono editor.

## Incognite per l'accertamento

1. **Reificazione**: forma esatta del valore-decomposizione restituito dal body (mappa piatta vs topologia); come il board localizza il binding da sostituire nel sorgente; compatibilità col re-entry per-call esistente.
2. **Workplace**: substrato per un buffer di script con valutazione live dentro una sessione (quanto del substrato modale esistente è riusabile? il REPL/editor esistente può fare da buffer con contesto?).
3. **Vista processo**: substrato UI disponibile per un diagramma-albero cliccabile nel pannello; costo di tenerlo sincronizzato col dato.
4. **Posa d'ingresso dello script**: il pezzo giace in coordinate mondo — da dove parte la turtle del mini-script? Le pose dei tagli che delimitano la foglia sono frame naturali già noti; la creation-pose condivisa è un'altra candidata. Serve una convenzione, misurata sull'ergonomia reale.
5. **Costo del confronto**: booleane per la differenza renderizzata — a ogni valutazione dello script (accettabile) o live durante la digitazione (da misurare/throttlare)?
6. **Caduta dell'impalcatura**: riscrittura della forma quando un ramo è tutto nativo — meccanica, conferme, cosa resta delle pose.
7. **Repair**: cosa espone il binding per riparare mesh non-watertight/non-manifold (o cosa costa costruirlo); comportamento attuale di `import-stl`+CSG su una scansione vera; criteri di rifiuto leggibile.
8. **Formati scan**: costo di import PLY (lingua franca degli scanner) e OBJ accanto a STL.
9. **Calibrazione di scala 3D**: gesto e convenzione (misura su spigolo/coppia di punti del riferimento → fattore); riuso dell'ergonomia del righello di `edit-image-board`.

## Ordine proposto

1. Brief "viste": vista contesto + vista processo (utili subito, anche solo per lo split).
2. Accertamento sulle incognite 1–6.
3. Brief `edit-mesh-board` (workplace + sostituzione).
4. Brief "caduta dell'impalcatura" (chiusura del ciclo di acquisizione).
