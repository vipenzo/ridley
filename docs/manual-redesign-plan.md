# Manual Redesign — Piano di Ristrutturazione

Documento di consolidamento delle decisioni prese in fase di pianificazione, prima dell'apertura dei brief di implementazione per Code. Aggiornato al **2026-06-02** (draft narrativi cap. 2-17 + about completati e rivisti dall'autore; definita la v1 del manuale; esempi inline nel Markdown).

Questo documento è il **riferimento unico** del piano. Tutte le decisioni prese durante la discussione sono qui; nuove decisioni vanno consolidate qui prima di procedere.

---

## 1. Scopo e motivazione

Il manuale online di Ridley viene ristrutturato per evolvere da documento lineare tutorial-style a un sistema a doppia traccia: **Guide** (narrative, per imparare) e **Reference** (a scheda, per consultare). Inoltre, l'editor CodeMirror viene arricchito con una **search contestuale** che riconosce simboli mentre l'utente digita, mostra tooltip con signature e descrizione, e permette di aprire la scheda completa.

Le motivazioni di fondo:

- Il manuale attuale serve male l'utente esperto, che deve sfogliare prosa per trovare la signature di una funzione.
- Il manuale attuale serve male anche il principiante, che vede troppo paradigma (turtle + estrusione) prima di poter fare qualcosa di proprio.
- L'editor non sa nulla del manuale: la conoscenza viaggia solo dal manuale all'utente, mai dall'utente al manuale.
- Il sorgente attuale è un file CLJS monolitico (`content.cljs`, ~3000 righe) inadatto a un workflow d'autore in Markdown.

---

## 2. Architettura del manuale

### 2.1 Tracce

Il manuale si articola in tre parti complementari:

- **Guide narrative** (cap. 1-17) — pagine organizzate per *task progressione* (cosa-vuoi-imparare-a-fare). Si leggono in ordine. Una guida è prosa con esempi embedded.
- **Guide tematiche** — task-oriented case study che attraversano più paradigmi. Non hanno posto nell'ordine progressivo: rispondono a un *problema* concreto (es. "come ottenere superfici parallele?") mostrando le tecniche disponibili e i loro trade-off. Vivono nel manuale con la stessa dignità dei capitoli numerati.
- **Reference** — schede per funzione, organizzate per *categoria* (le 18 categorie di `Spec.md`). Si consultano on-demand. Una scheda è densa, fattuale, autocontenuta.

Le tre parti si linkano vicendevolmente. Una scheda Reference ha "Vedi anche → Guide" (narrative e tematiche). Una guida narrativa può linkare a schede Reference e a guide tematiche.

### 2.2 Articolazione della Reference

La Reference si articola in tre sezioni:

- **Functions** — una scheda per ogni funzione utente di Ridley, raggruppata per le 18 categorie di Spec.md.
- **Clojure core** — ~20 simboli di uso frequente nei programmi Ridley, con scheda *leggera* (signature + 1-frase descrizione + esempio Ridley-side + link a ClojureDocs). Resto della Clojure core: auto-link a ClojureDocs senza scheda interna.
- **Internals** — sezione dedicata a chi estende Ridley con codice proprio. Vedi §6.

### 2.3 Architettura ibrida del sorgente

Il sorgente del manuale è organizzato in modo *ibrido*: la navigazione strutturata vive in ClojureScript, prosa ed esempi vivono nei file Markdown.

**Cosa vive dove (aggiornato 2026-06-02):**

- **`structure.cljs`** (o file analogo): albero gerarchico del manuale e metadati di navigazione (ordine dei capitoli, sezioni, riferimenti incrociati). Resta in ClojureScript perché è dato strutturato di sua natura.

- **File Markdown su disco**: prosa narrativa *e codice degli esempi* di tutte le pagine. Un file per pagina per lingua. Organizzati in cartelle per sezione e capitolo. Il codice degli esempi vive inline nel Markdown dentro commenti `<!-- example-source: id ... -->`, che il renderer estrae ed esegue (vedi §2.4). Non viene trasferito in `structure.cljs`: la decisione originale di tenere il codice in `structure.cljs` è superata.

**Struttura cartelle proposta:**

```
docs/manual/
├── structure.cljs                       # albero + codice esempi + caption
├── guides/
│   ├── en/
│   │   ├── 01-getting-started/
│   │   │   ├── 01-hello-ridley.md
│   │   │   ├── 02-interface.md
│   │   │   └── ...
│   │   ├── 02-modeling-with-primitives/
│   │   └── ...
│   └── it/
│       ├── 01-getting-started/
│       └── ...
└── reference/
    ├── en/
    │   ├── functions/
    │   │   ├── 2d-shapes/
    │   │   │   ├── circle.md
    │   │   │   └── ...
    │   │   ├── 3d-primitives/
    │   │   └── ...
    │   ├── clojure-core/
    │   │   ├── let.md
    │   │   └── ...
    │   └── internals/
    │       ├── shape-fn-contract.md
    │       ├── thickness-fn-contract.md
    │       ├── register-pattern.md
    │       └── animation-hooks.md
    └── it/
        └── ...
```

**Razionale:**

- La prosa vive in Markdown veri, scritti in Ulysses, con il workflow d'autore intatto.
- Il codice degli esempi sta accanto alla prosa che lo spiega, nello stesso file: chi scrive un capitolo controlla testo ed esempi senza saltare tra file. Il renderer riconosce i blocchi `example-source` senza parser fragili, perché il marcatore è esplicito e delimitato.
- `structure.cljs` resta il posto della navigazione e dei metadati strutturali, compatibile con la pratica esistente (`structure` esiste già in `content.cljs`).

### 2.4 Esempi inline nel Markdown

Il codice degli esempi vive nel file Markdown della pagina, dentro un commento `example-source`:

```markdown
<!-- example-source: first-cube
(register Cube (box 20 20 20))
-->
```

Il renderer riconosce il marcatore, estrae il codice e produce il blocco CodeMirror read-only con bottoni Run/Edit. Essendo dentro un commento HTML, il codice è invisibile quando il Markdown viene letto come testo normale (es. in Ulysses o su GitHub), ma resta nello stesso file della prosa che lo spiega.

La prosa che spiega un esempio sta attorno al blocco, non dentro. Esempio:

```markdown
## Un semplice cubo

Il comando `register` dà un nome alla tua forma così appare nel viewport.
`box` crea un cubo della dimensione specificata.

<!-- example-source: first-cube
(register Cube (box 20 20 20))
-->

Questo è quello che vedrai nel viewport.
```

Per pagine con più esempi, più blocchi `example-source` in successione, ciascuno preceduto dalla prosa che lo introduce.

**Storia di questa decisione.** Il piano originale prevedeva uno shortcode `{{example: id}}` nel Markdown che puntava al codice tenuto in `structure.cljs`, con un passo di estrazione al momento del Flusso B. In pratica il codice è rimasto inline nel Markdown e il renderer lo esegue direttamente: funziona, e tiene codice e prosa nello stesso file. La decisione è stata quindi di non estrarre nulla e lasciare gli esempi dove sono (2026-06-02). Lo shortcode `{{example: id}}` e il trasferimento a `structure.cljs` sono superati; i riferimenti residui nel resto del piano (es. §4.1, §7.1, §9.2) vanno letti in questa chiave.

### 2.5 Distinzione eseguibile / illustrativo

Ogni blocco di codice è eseguibile per default (bottoni Run/Edit attivi). Per i frammenti illustrativi non eseguibili, esiste un flag esplicito.

Nel modello attuale di `content.cljs` esiste già `:no-run true`. Resta: lo shortcode supporta un flag analogo (sintassi da definire), oppure il flag vive nel `structure.cljs` accanto al codice.

---

## 3. Sommario delle Guide

18 capitoli più una galleria con destino aperto.

### 3.0 Visione d'insieme

Prima del sommario lineare, vale la pena offrire una mappa concettuale. Lo schema seguente raggruppa i capitoli per *fase di lavoro*, e legge in modo narrativo come arco d'apprendimento: dai materiali alla costruzione, dalla composizione alla cura del dettaglio, fino all'estensione del sistema. Questo schema andrà riprodotto nell'introduzione del manuale stesso come prima pagina della traccia "Guide narrative":

```
Imparare       1. Per iniziare
               16. Clojure per Ridley

Materia prima  3. Lavorare con le forme 2D
               5. Path: registrare il movimento
               7. Mesh

Costruire      2. Modellare per primitive
               4. Estrusione
               6. Da funzioni matematiche a forme
               12. Lavorare con gli SDF

Comporre       8. Assemblaggio
               11. Curve avanzate

Riusare        9. Workspaces e Librerie

Capire         10. Analizzare e misurare
               15. Mettere a fuoco e risolvere i problemi

Curare         13. Testo
               14. Colore e materiali

Concludere     17. Esportare e stampare

Estendere      18. Estendere Ridley
```

I numeri dei capitoli non rispecchiano l'ordine narrativo dello schema, perché la numerazione segue la *didattica progressiva* (es. cap. 2 viene prima di cap. 3 perché didatticamente è utile mostrare le primitive prima delle forme 2D). Lo schema concettuale e la sequenza didattica convivono.

### 3.1 Sommario dettagliato

```
1.  Per iniziare
    1.1  Ciao Ridley
    1.2  L'interfaccia
    1.3  Eseguire: Run e REPL (paredit non riscontrato nell'app: non menzionato)
    1.4  La tartaruga (f, rt, u, th, tv — presentazione contenuta)
    1.5  Basi di Clojure (def, defn, dotimes; confine esplicito col cap. 16)

2.  Modellare per primitive
    2.1  Le primitive: box, cyl, sphere, cone (con scale e attach come verbi minimi)
    2.2  Comporre: union, difference, intersection, hull
         (intersection presentata anche come operazione costruttiva, non solo come taglio;
          hull come strumento generativo, non come variante di union;
          mini-progetto barchetta come chiusura della sezione)
    2.3  Mini-progetto: portapenne (difference + scale)
    2.4  Parametrizzare con def
    2.5  Pezzi riutilizzabili con defn
    2.5b Tante primitive alla volta (for + range, spirale come esempio)
    2.6  Per chi viene da un CAD tradizionale
         (sezione-ponte: translate, rotate, scale come polymorphic; quando preferire turtle+attach)

3.  Lavorare con le forme 2D
    3.1  A cosa servono le shape (motivazione + esempio extrude)
    3.2  Forme predefinite (circle, rect, polygon, star + stamp come strumento di visualizzazione)
    3.3  Forme personalizzate (poly, shape)
    3.4  Profili come valori (concetto: shape è valore nel piano locale turtle)
    3.5  Booleane 2D
    3.6  Raccordi e smussi 2D (fillet-shape, chamfer-shape, shape-offset)
    3.7  Dove si usano le shape (mappa completa dei consumatori: extrude, loft, revolve, stamp, operatori 2D)
    3.8  Generare shape da mesh (ridotta a cenno con rimando alla 7.5, 2026-06-12)
    3.9  Più forme alla volta (vettori di shape)
    3.10 Forme di testo (rimando a cap. 13)
    3.11 Forme riutilizzabili

4.  Estrusione
    4.1  Il concetto
    4.2  Percorsi curvi e composti
    4.3  Quick path ed estrusione chiusa
    4.4  Transizioni di forma e modalità giunzione
    4.5  Risoluzione e dettaglio
    4.6  Oltre l'estrusione piena (sezione-ponte verso il cap. 6, 2026-06-12)
    4.7  Revolve
    4.8  Chaining: extrude+, revolve+, transform->

5.  Path: registrare il movimento
    5.1  Cos'è un path (registrazione di movimenti turtle come dato)
    5.2  Visualizzare un path (follow-path, button UI)
    5.3  Marks: tag dentro un path
    5.4  Side-trip: rami che tornano alla spina
    5.5  Comporre path: follow e splicing
    5.6  Path da coordinate: poly-path, quick-path
    5.7  Path come embrioni di forma (path-to-shape, stroke-shape)
    5.8  Cose che accettano un path (extrude, loft, move-to, with-path/goto/path-to,
         text-on-path, anchors, bezier-as, fit)

6.  Da funzioni matematiche a forme
    6.1  Cosa sono le shape-fn
    6.2  Trasformazioni geometriche (tapered, twisted, fluted, rugged)
    6.3  Displacement (noisy, displaced)
    6.4  morphed: interpolare tra due profili
    6.5  profile: il profilo come silhouette
    6.6  heightmap: displacement da mappa di altezze
    6.7  mesh-to-heightmap e heightmap tileabili
    6.8  Shell, woven shell e embroid (← dal cap. 4, 2026-06-12)
    6.9  Raccordi sui cap: capped (← dal cap. 4, 2026-06-12)
    6.10 Comporre shape-fn
    6.11 Thickness-fn
    6.12 Scrivere una shape-fn propria

7.  Mesh
    7.1  Cos'è una mesh
    7.2  Operazioni booleane 3D
    7.3  Raccordi e smussi 3D
    7.4  Modificare le facce di una mesh
         (selezione, attach-face, clone-face)
    7.5  Sezioni
    7.6  Warp: deformazione spaziale
    7.7  Diagnosi e riparazione (mesh-diagnose, manifold, merge-vertices — spostata qui dalla 7.1, 2026-06-12)

8.  Assemblaggio
    8.1  I marcatori come strumento di scoperta    ← guida esistente
    8.2  Creation-pose e creation-pose shifting    ← guida esistente
    8.3  Attach su mesh e marcatori
    8.4  Skeleton-driven assembly                   ← guida esistente
    8.5  Stack di stato e branching

9.  Workspaces e Librerie
    9.1  Workspaces (documenti multipli, switch/rename/duplicate/close, dirty desktop, Save/Save As/Open, Edit-su-esempio in nuovo ws, integrazione con le librerie attive)
    9.2  A cosa servono le librerie
    9.3  Usare una libreria (pannello, attivazione, prefisso namespace)
    9.4  Librerie built-in (SVG import, STL import)
    9.5  Creare una libreria propria
    9.6  Condividere librerie (export/import .clj)

10. Analizzare e misurare
    10.1 Misurare distanze e dimensioni (bounds, distance, area, ruler)
    10.2 Misurazione interattiva (Shift+Click, lifecycle dei ruler)
    10.3 Identificare facce (find-faces: semantica, threshold, predicati where)
    10.4 Diagnostica della mesh (mesh-diagnostics, find-sharp-edges)
    10.5 AI describe (cenno: describe, ai-ask, sessioni)
    10.6 Visualizzare in XR (cenno con rimando alla documentazione app)

11. Curve avanzate
    11.1 Archi
    11.2 Curve di Bezier
    11.3 Curve strette e auto-intersezione

12. Lavorare con gli SDF                            ← guida esistente
    12.1 Primitive e operatori
    12.2 Il blend come operazione scultorea
    12.3 Risoluzione e auto-meshing
         (sdf-resolution!, auto-bounds, auto-boost per thin features,
          cap totale, controllo esplicito con sdf->mesh e sdf-ensure-mesh)
    12.4 sdf-offset: l'offset come operazione di prima classe
         (cenno; per il quadro generale vedi Guide tematiche)
    12.5 Il pattern SDF-then-mesh
    12.6 Quando SDF, quando mesh

Guide tematiche
    Sezione che raccoglie guide task-oriented che attraversano più paradigmi.
    Esistono come livello intermedio tra Guide narrative (cap. 1-17) e Reference.
    Vivono nel manuale con la stessa dignità dei capitoli numerati, ma non hanno
    posto nell'ordine progressivo.

    - Superfici parallele
      Le quattro vie all'offset in Ridley: shape-offset (su shape 2D),
      shape-fn shell (su lofts), woven-shell (su lofts di rivoluzione),
      sdf-offset + sdf-difference (su SDF). Ciascuna abbinata al paradigma
      di costruzione corrispondente. Nota: per mesh arbitrarie non esiste
      via diretta (vedi §11.2 dei punti di pianificazione per mesh→SDF).

    - (altre guide tematiche emergeranno durante la stesura)

13. Testo
    13.1 Forme di testo 2D
    13.2 Testo come geometria estrusa
    13.3 Heightmap e testo in rilievo

14. Colore e materiali
    14.1 Colorare le forme
    14.2 Multi-material: il pattern register + color
    14.3 Esportare per stampanti multi-materiale

15. Mettere a fuoco e risolvere i problemi
    15.1 Pannelli 3D di testo
    15.2 Tecniche di debug
    15.3 Tweaking interattivo
    15.4 Anteprima forme (rimando a 3.6 stamp)
    15.5 Follow path (rimando a cap. 5)
    15.6 Misurazione (rimando a cap. 10)

16. Clojure per Ridley
    16.1 Strutture di controllo
    16.2 Let e variabili locali
    16.3 Funzioni anonime
    16.4 Map e reduce
    16.5 Usare la REPL

17. Esportare e stampare
    17.1 STL
    17.2 3MF multi-materiale

18. Estendere Ridley                                (espansione differita)
    18.1 Scrivere shape-fn personalizzate
    18.2 Scrivere thickness-fn personalizzate
    18.3 Funzioni geometriche di alto livello
    18.4 Manipolazione programmatica di percorsi
    18.5 Pattern Clojure avanzati per la modellazione
    18.6 Il sistema librerie sotto il cofano (modello SCI, namespace, limiti,
         differenze col Clojure "vero", scope globale)

Galleria (decisione differita: probabilmente assorbita come case-study)
```

### 3.2 Note sul sommario

- **Capitolo 2 "Modellare per primitive"** è il punto di ingresso pedagogico per l'utente Tinkercad/OpenSCAD-refugee. Usa `attach` + comandi turtle base (`f`, `rt`, `u`) per posizionare. La sezione 2.2 "Comporre" presenta le quattro operazioni di composizione mesh→mesh (union, difference, intersection, hull) ognuna con un esempio piccolo, più una barchetta a vela in chiusura che le mette in cooperazione. La barca è sotto-sezione di 2.2, non sezione a sé: porta in primo piano la cooperazione delle operazioni appena viste, e anticipa `def` (usato per leggibilità, non ancora come strumento di parametrizzazione — quello arriva in 2.4). Il portapenne resta come 2.3 in sezione propria (difference + scale). La sezione 2.5b "Tante primitive alla volta" introduce `for`+`range` come strumento per generare geometria ripetitiva (spirale di sfere sul portapenne). La sezione 2.6 fa da ponte verso lo stile CAD tradizionale (translate/rotate/scale polymorphic, riconoscibili a chi viene da Fusion/OpenSCAD, ma in Ridley turtle+attach è più espressivo).
- **Capitolo 1.4** introduce solo i 5 comandi turtle base. Rollio (`tr`), `set-heading`, e i comandi di percorso restano per i capitoli successivi.
- **Capitolo 3 "Lavorare con le forme 2D"** apre con una sezione motivazionale (3.1) che mostra il ciclo shape→extrude, così il lettore capisce a cosa servono le shape prima di imparare a costruirle. `stamp` è introdotto nella 3.2 come strumento di visualizzazione, non in una sezione dedicata. La 3.4 è la sezione concettuale ("una shape è un valore nel piano locale della tartaruga, non un'entità ancorata a un piano"). La 3.7 è la mappa completa dei consumatori, non un cenno. La guida `defining-2d-profiles.md` è assorbita nelle sezioni 3.3-3.4.
- **Capitolo 4 "Estrusione"** include revolve (4.8) e chaining (4.9: extrude+, revolve+, transform->), non previsti nel piano originale. Revolve è stato spostato qui perché è concettualmente un'operazione generativa parallela a extrude. Chaining è la sua estensione naturale per geometrie multi-segmento. Nota: l'asse di revolve è up della tartaruga (Spec corretta il 2026-05-20, era sbagliata). `loft+` non è ancora implementata (Roadmap §1.5).
- **Capitolo 5 "Path: registrare il movimento"** presenta i path come cittadino di prima classe, parallelo alle shape del cap. 3. Il lettore arriva dopo aver visto path inline in `extrude` (cap. 4) e scopre che quei comandi possono essere registrati come dato. La 5.8 include `with-path`/`goto`/`path-to` come consumatori di path (pattern path-driven assembly). Questa è anche la sede della dualità path↔shape (sezione 5.7).
- **Capitolo 6 "Da funzioni matematiche a forme"** è il blocco shape-fn. Include `thickness-fn` come cenno con rimando agli Internals (18.2) per chi vuole scriverne di proprie. Vedi §6.
- **Capitolo 7 "Mesh"** sezione 7.4: `inset-face` e `scale-face` rimossi (non esistono come binding SCI). Restano `attach-face` e `clone-face`.
- **Capitolo 9 "Workspaces e Librerie"** raccoglie due cose: i Workspace (i documenti su cui lavori, sezione 9.1) e il sistema librerie (9.2-9.6). La parte librerie copre l'uso pratico: attivazione, librerie built-in, creazione, condivisione. I Workspace ricordano l'elenco delle librerie attive: l'integrazione è documentata in 9.1 e 9.3. I dettagli tecnici (modello SCI, namespace, limiti, differenze col Clojure "vero") vanno nel cap. 18 "Estendere Ridley" (§18.6) o nelle Internals della Reference, non nel cap. 9.
- **Capitolo 10 "Analizzare e misurare"** (ex cap. 9) riconosce l'analisi come fase di lavoro autonoma, parallela a modellare/assemblare/esportare. Raccoglie misurazione (distance, bounds, ruler), identificazione di facce (find-faces come concetto autonomo, non solo helper di chamfer), diagnostica mesh, AI describe (cenno; AI tenuta sotto tono finché non maturerà), XR (cenno con rimando).
- **Capitolo 15 "Mettere a fuoco e risolvere i problemi"** (ex cap. 14) è il vecchio "Debug" alleggerito: si occupa di debug attivo (pannelli, tweak, tecniche), e rimanda al cap. 10 per misurazione e ispezione (che hanno trovato casa propria).
- **Capitolo 18 "Estendere Ridley"** (ex cap. 17) ha priorità più bassa: il primo giro di scrittura del manuale può lasciarlo come segnaposto e svilupparlo dopo. Aggiunta sezione 18.6 per i dettagli tecnici del sistema librerie.

### 3.3 Guide esistenti da integrare

Cinque file Markdown EN-only vivono in `docs/examples/guides/` ma non sono linkati dal manuale in-app. Sono **materiale d'autore** da integrare nelle Guide del nuovo manuale:

- `defining-2d-profiles.md` → cap. 3.3-3.4
- `marks-as-discovery.md` → cap. 8.1
- `creation-pose-shifting.md` → cap. 8.2
- `skeleton-driven-assembly.md` → cap. 8.4
- `working-with-sdfs.md` → cap. 12

Vanno tradotti in italiano (workflow §5) e adattati al formato pagina-Markdown del nuovo manuale.

---

## 4. Schema della scheda Reference

Ogni scheda Reference è un file Markdown autonomo nella sezione appropriata.

### 4.1 Struttura della scheda

```markdown
---
name: extrude
category: 6. Generative Operations
since: ""
status: stable
---

# extrude

## Signature

`(extrude shape & path-commands)`

## Description

Drag a 2D shape along a path defined by turtle commands, producing a 3D solid.

## Parameters

- `shape` — a 2D shape (the cross-section).
- `path-commands` — one or more turtle commands defining the path
  (e.g., `(f 30)`, `(th 45)`, `(arc 20 90)`).

## Example

{{example: extrude-basic-tube}}

## Variations

{{example: extrude-bent-tube}}
Cambiando direzione a metà percorso si ottiene un tubo piegato.

## Notes

- L'orientamento del profilo dipende dall'orientamento iniziale della tartaruga.
- Per percorsi con curve strette o cambi bruschi, smussa il path con `bezier-as` o `arc-h` prima di estrudere.

## See also

- **Guide:** [4.1 Il concetto di estrusione](../../guides/04-extrusion/01-concept.md)
- **Related functions:** `extrude-closed`, `loft`, `loft-n`, `revolve`
```

### 4.2 Frontmatter

Il blocco YAML in testa contiene metadati strutturali:

- `name` — simbolo Ridley (chiave dell'indice)
- `category` — categoria di Spec.md (parsa programmaticamente)
- `since` — versione di introduzione, opzionale
- `status` — `stable`, `experimental`, `deprecated`, eccetera

Altri campi possono essere aggiunti in seguito. Lo schema precise si definisce nel brief B.

### 4.3 Lingue

Schede in `reference/en/` e `reference/it/`. La versione EN è il source-of-truth (vedi §5). Quella IT si genera per traduzione assistita e si rifinisce a mano.

### 4.4 Reference vs Spec.md

In caso di divergenza su firma, parametri, o comportamento documentato: **Spec.md vince**. Le schede Reference si allineano. Sul resto (esempio, note, vedi-anche): la scheda è autonoma e Spec.md non si esprime.

---

## 5. Bilinguismo

### 5.1 Direzione di traduzione per tipo di contenuto

- **Guide**: italiano è source-of-truth, inglese è tradotto.
- **Reference**: inglese è source-of-truth, italiano è tradotto.
- **Spec.md**: solo inglese (contract tecnico stretto).
- **Codice degli esempi**: sempre invariato (i nomi delle funzioni sono in inglese sempre).
- **Commenti nel codice degli esempi**: sempre in inglese.
- **Caption corte (in `structure.cljs`)**: bilingui, formato da definire (probabilmente mappa `{:en "..." :it "..."}`).

### 5.2 Workflow di traduzione

**Assistita una tantum**, poi le coppie di file diventano source-of-truth gemelli.

Pratica operativa:

- Una nuova guida si scrive prima nell'italiano (in Ulysses). La versione inglese si genera con assistenza (può essere prodotta in conversazione con Claude, oppure da uno script che chiama un'API di traduzione, da decidere).
- Una nuova scheda Reference si scrive prima in inglese. La versione italiana si genera con assistenza.
- Modifiche successive: si aggiornano **entrambi i file** in coppia. Per modifiche brevi, manualmente. Per modifiche estese, con assistenza puntuale (es. presentare la diff italiana e ottenere la diff inglese corrispondente).
- Nessun fallback automatico tra lingue è previsto come architettura — entrambe le lingue devono esistere per ogni pagina. (Esiste oggi un fallback `:it`→`:en` a livello di pagina mancante; va deciso se preservarlo.)

### 5.3 Glossario

Per ora nessun glossario formale. Convenzioni che emergono dalla pratica:

- Termini tecnici del codice (`extrude`, `loft`, `mesh`, `turtle`, `marker`, ecc.) restano invariati anche in italiano.
- Termini concettuali si traducono (`shape` → `forma`, `path` → `percorso`, `boolean` → `booleano`).
- Calchi che producono parole italiane con altro significato vanno evitati (es. "imports" → non "importi"; preferire "import" invariato o riformulare).
- Em-dash evitati nell'output destinato a pubblicazione/community.

In futuro questo set di convenzioni può diventare un `CONVENTIONS.md` formale.

---

## 6. Internals della Reference

Sezione della Reference dedicata a chi estende Ridley con codice proprio. Quattro candidati identificati durante la discovery:

### 6.1 Contratto shape-fn

Una shape-fn è `(fn [t] -> shape)` con metadata `{:type :shape-fn}`, dove `t ∈ [0, 1]` lungo il percorso di loft o di revolution.

Contratto formale, helper accessibili (`displace-radial`, `angle`, `noise`, `fbm`, `sample-heightmap`), dynamic var `*path-length*`, ordine di composizione, cache `:point-count` come fragilità nota da segnalare. Materiale di partenza: `dev-docs/ShapeFn.md` (interno) — va portato in forma utente-finale.

### 6.2 Contratto thickness-fn

Specializzazione della shape-fn usata da `shell` (e simili). Variazione di spessore lungo un percorso. Merita scheda Internals propria, distinta da shape-fn, perché:

- Il dominio di applicazione è specifico (shell e woven shell).
- Il contratto può differire (segnale di output: scalare di spessore, non shape).
- Documentare in modo unitario con shape-fn confonderebbe il lettore.

Material di base da preparare durante la stesura. Va anche aggiunto al capitolo 17.2 delle Guide come capitolo dedicato.

### 6.3 Pattern di registry

`register` è il meccanismo centrale per esporre mesh/path/shape/value per nome. Dispatch implicito via `:type` nel valore. Va documentato per chi vuole scrivere librerie di pezzi riutilizzabili.

### 6.4 Animation hooks

`register-animation!`, `register-procedural-animation!`, `register-collision!`. Oggi non in Spec.md, solo commenti nel sorgente. Documentare almeno a livello di scheda Reference Internals.

### 6.5 Pattern: collection inputs

Molti operatori di Ridley accettano indifferentemente un input singolo o una collezione. La regolarità si manifesta in due aree principali:

- **Shape vs vettori di shape**: alcune funzioni producono vettori di shape (`text-shape`, `slice-mesh`, `project-mesh`, `shape-xor`); gli operatori che consumano shape (`extrude`, `loft`, `revolve`, `stamp`, `shape-offset`) li accettano direttamente. Le mesh prodotte da una estrusione di vettore di shape vengono fuse in una singola mesh, così le booleane downstream funzionano senza `concat-meshes` manuale.
- **Mesh singole vs vettori di mesh**: pattern analogo per le mesh (da approfondire quando si arriva a documentarlo).

Vale la pena documentare la regolarità come pattern di sistema, non solo caso per caso nelle singole funzioni. Scheda Internals da popolare quando si arriverà a scrivere la Reference; in parallelo, le sezioni 3.9 e cenni nelle Guide rimandano qui.

### 6.6 Esclusi dagli Internals (per ora)

- Attachment operators (`attach`) → coperti dalla Reference standard (sono funzioni user-facing).
- `attach-face`, `clone-face`, `inset-face`, `scale-face` → coperti dalla Reference standard (sono funzioni user-facing). La micro-disambiguazione con `attach` vive nel cap. 7.4 delle Guide (vedi §11.1).
- Modi pilot/test → non user-facing oggi.

### 6.7 Criterio editoriale di destinazione

Audit condotto da Code (2026-05-15): dei 435 simboli bound in SCI, 204 (47%) non sono citati in Spec.md. Filtrando le categorie evidentemente plumbing (implementazioni di macro, shadow, recording helpers, namespace turtle low-level, dynamic vars, costruttori-helper), restano ~70 simboli sospetti, da classificare con criterio editoriale chiaro.

Il criterio: ogni simbolo va in **una di tre destinazioni** in base alla domanda *"per chi è scritto?"*.

**Destinazione A — Spec + Reference standard (Functions).**
Funzioni che sono *materia ordinaria di scrittura Ridley*. Un utente normale che scrive programmi Ridley deve trovarle. Vanno in Spec.md (per il RAG e per il contract tecnico) e diventano schede della Reference standard.

Esempi dall'audit (~15 simboli):

- SDF: `sdf-ensure-mesh`, `sdf-node?`
- Mesh ops: `chamfer-edges`, `chamfer-prisms`, `shape-bridge`, `slice-at-plane`, `find-sharp-edges`, `lay-flat`, `extrude-text`
- Predicati: `shape?`, `path?`
- Import/Export: `svg-shapes`, `decode-mesh`, `save-3mf`, `save-stl`, `save-mesh`
- Text: `text-width`
- Aliases utili: `down` (alias di `d`), `transform`

**Destinazione B — Spec + Reference Internals.**
API per chi *estende Ridley* con codice proprio: pattern di registry, introspezione, hooks. Vanno in Spec.md (per completezza del contract) e diventano schede *Internals*, non standard.

Esempi dall'audit (~35 simboli):

- Registry tipizzato: `register-mesh!`, `register-path!`, `register-shape!`, `register-value!`, `register-panel!`
- Introspezione: `get-mesh`, `get-shape`, `get-path`, `registered-names`, `shape-names`, `path-names`, `visible-names`, `visible-meshes`, `all-meshes-info`, `origin-of`, `last-op`, `get-turtle-resolution`, `get-turtle-joint-mode`
- Selection: `selected`, `selected-face`, `selected-mesh`, `selected-name`
- Visibility: `show-turtle`, `hide-turtle`, `tweak-start!`, `tweak-start-registered!`
- Animation API: `anim-make-cmd`, `anim-make-span`, `anim-register!`, `anim-proc-register!`, `anim-preprocess`, `anim-clear-all!`
- Collisions/pilot: `on-collide`, `off-collide`, `reset-collide`, `list-collisions`, `clear-collisions`, `pilot-request!`
- Runtime/settings: `desktop?`, `env`, `audio-feedback?`, `set-audio-feedback!`, `run-definitions!`
- Source tracking & metaprogrammazione: `source-of`, `source-ref`, `add-source`, `get-source-form`, `set-source-form!`

**Destinazione C — Allowlist.**
Plumbing interno esposto a SCI per ragioni tecniche, ma non parte del linguaggio. Non va in Spec, non va in Reference. Va in una *allowlist* documentata (file dedicato) con un commento per ciascun simbolo che spiega *perché* è esposto.

Esempi dall'audit (~150 simboli, di cui ~12 emersi dal sottoinsieme dei 70 sospetti):

- Supporto macro anchor: `save-anchors*`, `restore-anchors*`, `resolve-and-merge-marks*`
- Debug interno: `perf-now`, `print-bench`, `pr`
- Init: `init-turtle`, `set-creation-pose`
- Costruttori interni: `anonymous-count`, `anonymous-meshes`, `path-`, `path-from-recorder`, `ops-extrude`, `ops-loft`
- Tutto il resto del plumbing (implementazioni `-impl`, shadow `pure-*`, recording `rec-*`, namespace `turtle-*`, dynamic vars `*…*`, ecc.)

### 6.8 Articolazione delle Internals

Data la dimensione attesa della sezione Internals (~50 schede dopo l'audit), va articolata in sotto-categorie per renderla consultabile.

Articolazione proposta:

```
Reference / Internals/
├── Contracts (extending the system)
│   ├── shape-fn contract
│   ├── thickness-fn contract
│   └── Pattern: collection inputs
├── Registry pattern
│   ├── register (polymorphic — link a Reference standard)
│   ├── register-mesh!, register-path!, register-shape!,
│   │   register-value!, register-panel!
│   └── Introspection (get-mesh, registered-names, ...)
├── Scene visibility
│   (show-mesh!, hide-mesh!, show-mesh-ref!, hide-mesh-ref!,
│    show-all!, hide-all!, show-only-registered!,
│    show-panel!, hide-panel!,
│    show-turtle, hide-turtle, refresh-viewport!)
├── Picking & selection
│   (selected, selected-face, selected-mesh, selected-name,
│    origin-of, last-op, source-of)
├── Interactive testing
│   (tweak-start!, tweak-start-registered!,
│    get-turtle-resolution, get-turtle-joint-mode)
├── Animation API
├── Collisions & pilot
├── Source tracking & metaprogramming
└── Runtime settings
```

Ciascuna sotto-categoria è una pagina indice con schede figlie.

**Nota.** La sotto-categoria originaria "Selection & visibility" è stata articolata in tre dopo l'audit di Code (T-001, 2026-05-16): con 22 simboli era una bucket-categoria che raccoglieva cose semanticamente diverse — gestione visibilità, picking, tweak interattivo. Le tre sotto-categorie attuali separano l'atto di rendere visibile (Scene visibility), l'atto di interrogare cosa è selezionato (Picking & selection), e l'atto di entrare in un modo interattivo di test (Interactive testing). La sotto-categoria Contracts resta tipicamente vuota nell'audit perché shape-fn / thickness-fn sono macro-driven, non simboli bound diretti; le schede verranno scritte a mano in fase di stesura.

### 6.9 Workflow di gestione

Tre lavori paralleli devono procedere coordinati per chiudere il gap Spec↔codice:

1. **Audit fine dei ~70 sospetti** — Code applica il criterio editoriale di §6.7 a ciascun simbolo della lista, producendo una classificazione A/B/C per ognuno. Vincenzo valida.
2. **Lint script Babashka** — script che dato Spec.md + allowlist riesce a dire "questi simboli sono bound ma non documentati né in allowlist". Run regolare in CI o pre-release. Indipendente da (1) — può procedere in parallelo.
3. **Aggiornamento Spec.md** — aggiunta dei simboli di categoria A (Spec standard) e B (Spec con tag Internals). Da fare prima che inizi la generazione delle schede Reference nel Flusso B.

(1) e (2) sono lavori paralleli che possono partire subito, lato Code. (3) dipende da (1). Tutti e tre sono prerequisiti del Flusso B (infrastruttura documentazione).

---

## 7. Esempi

### 7.1 Esempi inline nel Markdown

Vedi §2.4. Il codice degli esempi vive inline nei file Markdown dentro commenti `<!-- example-source: id ... -->`; il renderer li estrae ed esegue. (Il modello shortcode + `structure.cljs` descritto nelle prime versioni del piano è superato.)

### 7.2 Eseguibili per default

Bottoni Run/Edit visibili per default. Frammenti illustrativi marcati esplicitamente (sintassi da definire in brief B; oggi `:no-run true`).

### 7.3 Autocontenuti

Ogni esempio è eseguito in contesto SCI fresco (`reset-ctx!`). I `def`/`defn` di un esempio non sopravvivono al successivo. Conseguenza per la scrittura: ogni esempio deve definire inline tutto ciò che usa.

### 7.4 Bug Edit pendente

Il bottone Edit oggi sovrascrive l'intero contenuto dell'editor utente senza preservazione né conferma. Comportamento da modificare nel brief B. Strade percorribili identificate dalla discovery:

- Confirm dialog se l'editor è non-vuoto.
- "Apri in nuovo buffer" come azione alternativa.
- "Inserisci al cursore / appendi alla fine" invece di sostituire.
- Rinominare "Edit" → "Open in editor" per esplicitare la sostituzione.

Decisione da prendere nel brief B.

### 7.5 Galleria

Decisione differita. Probabilmente la galleria attuale viene assorbita come case-study di fine capitolo nelle Guide. Esempi più "vetrina" e contributi community potrebbero meritare una sezione separata. Da rivalutare dopo che le Guide sono state scritte.

---

## 8. Search contestuale in CodeMirror

### 8.1 Funzionalità target

- Completion popup: digitando un simbolo, suggerimenti dai simboli Ridley + Clojure core annotati.
- Tooltip al hover: signature + descrizione breve.
- Shortcut per aprire la scheda completa nel pannello manuale.
- Per simboli Clojure non nei ~20 annotati: link esterno a ClojureDocs.

### 8.2 Indice simbolo → scheda

L'infrastruttura di indicizzazione segue il modello esistente del RAG su Spec.md: uno script di build (Babashka) legge le schede Reference + Spec.md e produce un `reference-index.cljs` con la mappa simbolo → metadati. Questo file è bundled e fornisce dati strutturati al tooltip CodeMirror.

Conseguenza: niente parsing di Markdown a runtime. Il Markdown viene parsato al build per produrre l'indice.

### 8.3 Conseguenza inattesa

Una volta che la search contestuale è in CodeMirror, funziona anche dentro al pannello del manuale (gli esempi sono in CodeMirror read-only). Hover su `extrude` in un esempio di guida → tooltip della scheda Reference. Obiettivo gratuito da non perdere.

### 8.4 Clojure core annotati

Lista da definire. Criterio: ricorrenza negli esempi del manuale e nei programmi Ridley di riferimento. Indicativamente: `let`, `def`, `defn`, `dotimes`, `loop`, `recur`, `for`, `map`, `reduce`, `if`, `when`, `cond`, `range`, `vec`, `first`, `rest`, `last`, `concat`, `count`, `assoc`, `get`, `get-in`. Lista definitiva da derivare in seguito da analisi degli esempi.

### 8.5 Infrastruttura editor

L'infrastruttura CodeMirror esistente è già parzialmente pronta:

- `autocompletion()` è attivo ma senza completion source custom: aggancio naturale per i simboli Ridley.
- Pattern AI Focus (StateField + StateEffect + Decoration + UpdateListener) come template per tooltip-hover.
- Helper esistenti per posizionamento: `get-word-at-cursor`, `get-element-at-cursor`, `coordsAtPos`.

---

## 9. Tre flussi di lavoro

### 9.1 Flusso A — Ristrutturazione dei contenuti

Autori: Vincenzo + Claude (in conversazione).

Output: file Markdown per Guide e Reference nella nuova struttura. Parte indipendentemente dagli altri due flussi.

Punti di partenza:

- Il manuale attuale (`content.cljs` esportato come `Manuale_it.md` e `Manual_en.md`).
- Le cinque guide standalone in `docs/examples/guides/`.
- Spec.md per la Reference.

Pratica: si lavora capitolo per capitolo, partendo dai più stabili. Il materiale prodotto è "pronto da migrare" quando il Flusso B avrà preparato l'infrastruttura.

### 9.2 Flusso B — Infrastruttura della documentazione

Autore: Code, su brief preparato dopo questa fase di pianificazione.

Scope:

1. Nuovo schema `structure.cljs` (Guide + Reference + Internals).
2. Renderer Markdown per le pagine (parser proper, non regex).
3. Shortcode `{{example: id}}` con risoluzione al rendering.
4. Pipeline di build che produce `reference-index.cljs`.
5. Riscrittura del pannello manuale per la nuova struttura (con search testuale integrata).
6. Migrazione di `content.cljs` esistente nella nuova struttura.
7. Risoluzione bug Edit.
8. Sorte di `manual-output/`: build artifact, non più committato.
9. Integrazione delle 5 guide orfane di `docs/examples/guides/`.

Vedi §10 per i punti aperti che il brief B dovrà chiudere.

### 9.3 Flusso C — Integrazione search in CodeMirror

Autore: Code, su brief separato dopo che il Flusso B ha prodotto l'indice.

Scope:

1. Completion source CodeMirror che legge `reference-index.cljs`.
2. Tooltip al hover su simboli.
3. Shortcut per aprire la scheda completa.
4. Comportamento per simboli Clojure (annotati interni vs link esterno a ClojureDocs).

---

## 10. Punti aperti per il brief B

Decisioni che il brief B per Code dovrà chiudere, ma che richiedono input da Vincenzo + Claude prima:

1. **Sintassi precisa dello shortcode** (`{{example: id}}` oppure `::: example id :::` oppure altro).
2. **Schema frontmatter dei file Markdown** (campi per guide vs reference).
3. **Formato delle caption bilingui** in `structure.cljs` (mappa `{:en ... :it ...}` oppure suffisso `:caption-it`).
4. **Workflow di traduzione concreto**: assistenza in conversazione caso per caso, oppure script che produce bozze EN da IT e viceversa?
5. **UX del bug Edit**: quale delle quattro strade adottare.
6. **Strategia di migrazione** di `content.cljs` esistente.
7. **Fallback `:it` → `:en` a livello di pagina mancante**: preservare o rimuovere?
8. **Lista dei ~20 Clojure core annotati**.
9. **Sintassi precisa del flag "frammento illustrativo"** nello shortcode (oppure: solo flag in `structure.cljs`?).

### 10.1 Prerequisiti paralleli (audit Spec↔codice)

Tre lavori lato Code devono essere conclusi prima del brief B, perché il Flusso B genererà la Reference a partire da Spec.md. Procedono in parallelo al lavoro di chiusura dei punti 1-9 sopra:

- **Audit fine dei ~70 simboli sospetti** (vedi §6.9): classificazione A/B/C secondo il criterio di §6.7. Output: una lista validata di simboli da aggiungere a Spec.md e una allowlist.
- **Lint script Babashka**: pipeline di check Spec↔codice da integrare in CI/pre-release.
- **Aggiornamento Spec.md**: integrazione dei simboli classificati A e B con le rispettive intestazioni.

### 10.2 Chiusura dei punti aperti (2026-06-02)

I nove punti di §10 sono stati chiusi o rinviati. Sintesi:

- **1, sintassi shortcode** — superato. Gli esempi vivono inline nel Markdown dentro `<!-- example-source: id ... -->` (§2.4); niente shortcode.
- **2, frontmatter** — la Reference mantiene il suo frontmatter (`name`, `category`, `since`, `status`); le guide hanno frontmatter zero, aprono con un heading `# N. Titolo`, e i metadati di navigazione (ordine, titolo, slug, lingua) vivono in `structure.cljs` indicizzati sul path. Le coppie di lingua si appaiano per convenzione di path.
- **3, caption bilingui** — decaduto. Con gli esempi inline la prosa attorno al blocco fa da didascalia; non servono caption separate in `structure.cljs`.
- **4, workflow di traduzione** — rinviato oltre la v1, si decide a prosa congelata.
- **5, UX bug Edit** — bug confermato ancora presente (l'editor viene sovrascritto senza conferma). Da risolvere nel Brief B. Approccio raccomandato: dialog di conferma quando l'editor non è vuoto; la scelta finale fra le opzioni di §7.4 si fissa nel brief.
- **6, migrazione di `content.cljs`** — audit dei contenuti del vecchio manuale fatto: tutto ha una collocazione nei capitoli nuovi. Orfani gestiti: `bloft` rimosso dal DSL (niente da portare), galleria preservata in `docs/examples/gallery/`, `T` e ispezione turtle aggiunti al cap. 15. La migrazione si riduce allo switch del Brief B.
- **7, fallback `:it`/`:en`** — fallback multilingua bidirezionale come meccanismo di transizione finché le traduzioni non sono complete (mostra la lingua disponibile quando l'altra manca). Stato a regime: entrambe le lingue per ogni pagina (§5.2).
- **8, lista Clojure core annotati** — rinviato a T-009 (search, fast-follow post-switch).
- **9, flag frammento illustrativo** — risolto dalle convenzioni d'autore: un blocco è eseguibile se ha il marker `example-source`, illustrativo se non ce l'ha. Il marker porta due flag in uso: `:no-run` (solo Edit, nessun Run, per esempi che scrivono sulla REPL senza output nel viewport) e `:warning slow` (esecuzione di parecchi secondi).

---

## 11. Punti aperti di pianificazione

Decisioni di pianificazione ancora aperte, non bloccanti per il brief B:

- **Sorte definitiva della galleria attuale**: decisione dopo la stesura delle Guide.
- **Capitolo 16 "Estendere Ridley"**: espansione differita; primo giro di scrittura come segnaposto.
- **Pagina "Idiomi di Ridley"**: pensiero futuro, da rivalutare quando il manuale è più maturo. Raccoglierebbe pattern trasversali (collection inputs, naming caveats, regolarità ricorrenti). Potrebbe vivere come pagina di sintesi nel cap. 14 o come capitolo a sé.

### 11.1 Note di disambiguazione editoriale

Casi in cui il manuale ha il compito esplicito di fare chiarezza dove i nomi suggeriscono confusione. Da raccogliere una micro-nota di disambiguazione nei posti chiave:

- **`attach-face` vs `attach`** — nomi simili, operazioni diverse: `attach-face` modifica mesh, `attach` assembla. Micro-nota in apertura del cap. 7.4. (Il nome `attach-face` riflette l'operatività ma non la semantica; cambiarlo non vale il costo, va disambiguato in prosa.)
- **`extrude-y` / `extrude-z` vs `extrude`** — le varianti `extrude-y` e `extrude-z` usano gli assi del **mondo**, non quelli della turtle: `extrude-z` estrude lungo l'asse Z mondo, indipendentemente da come è orientata la turtle. `extrude` invece estrude seguendo i comandi turtle (che possono includere `f`, `u`, `rt`, ecc.). Disambiguazione da fare al cap. 4 (Estrusione) nel momento in cui si introducono le varianti, e nelle schede Reference di `extrude-y` / `extrude-z`. Origine: diagnosi di Code in chat T-006 (2026-05-16).
- **(altri casi emergeranno durante la stesura)**

### 11.2 Punti di contatto con la Roadmap

Feature in roadmap che, una volta implementate, troveranno naturale collocazione nel manuale già strutturato dal piano:

- **Face cutting** (Roadmap riga 127) — disegnare una shape 2D su una faccia e usarla come taglio. Vivrà nel cap. 7.4 come quarta operazione dopo `attach-face`, `clone-face`, `inset`/`scale`. Nessuna pre-disposizione necessaria nel piano corrente — il capitolo 7.4 è già il contenitore giusto.
- **Adaptive loft step density per shape-fn transitions** (Roadmap riga 125) — debito tecnico sul rendering di shape-fn rapide. Da tenere a mente quando si scrive cap. 6: oggi alcune transizioni possono produrre faceting grezzo (`capped` è il caso noto), in futuro saranno più lisce.
- **Attach structure-preserving** (Roadmap riga 129) — variante di `attach` che non flattens i vettori di mesh. Quando si documenterà `attach` con input multipli (cap. 8.3), si menzionerà che oggi è flattening e che structure-preserving è in roadmap.
- **mesh→SDF** (non in Roadmap, candidato a esserci) — oggi esiste solo `sdf->mesh`. La direzione opposta non è disponibile: una mesh arbitraria non può essere convertita in SDF e quindi non beneficia di operazioni SDF (offset, blend). Se in futuro venisse implementata (algoritmi possibili: signed distance via raycast, voxelizzazione, distance transform da BVH), la guida tematica "Superfici parallele" guadagnerebbe una quinta via, e si aprirebbero scenari di blend/morphing mesh↔SDF oggi impossibili.

---

## 12. Note di rollout

- **Deploy del manuale online è `on: release`**, non `on: push main`. La nuova versione del manuale apparirà tutta in un colpo alla prima release post-ristrutturazione. Buono per stabilità, da pianificare per il momento dello switch.

- **Manuale desktop (Tauri)**: bundled nell'app, segue il ciclo release dell'app. Stesso meccanismo del web.

- **Migrazione non distruttiva**: il vecchio `content.cljs` può convivere con la nuova struttura durante lo sviluppo, finché lo switch non è pronto. Da pianificare nel brief B.

---

## 13. Stato delle decisioni

### 13.1 Chiuse

- Manuale sdoppiato in Guide + Reference.
- Sommario delle Guide (17 capitoli + galleria differita).
- Reference articolata in Functions / Clojure core / Internals.
- Internals include shape-fn, thickness-fn, register pattern, animation hooks.
- Architettura ibrida: `structure.cljs` per albero+codice, Markdown per prosa.
- Esempi inline nel Markdown: il codice degli esempi vive dentro commenti `<!-- example-source: id ... -->` nello stesso file della prosa; il renderer li estrae ed esegue. Niente trasferimento a `structure.cljs`, niente shortcode `{{example: id}}` (modello originale superato, 2026-06-02). Vedi §2.3-§2.4.
- Bilinguismo: IT source per Guide, EN source per Reference; commenti codice sempre EN.
- Workflow traduzione: assistita una tantum, poi coppie di file.
- Eseguibilità: default eseguibile, flag esplicito per frammenti.
- Tre flussi (A contenuti, B infrastruttura, C search).
- Guide orfane di `docs/examples/guides/` → integrate.
- `manual-output/` → build artifact, non più committato.
- Source-of-truth: Spec.md vince su firma/parametri/comportamento; schede autonome sul resto.
- **Architettura a tre tracce**: Guide narrative (cap. 1-17), Guide tematiche (non numerate, on-demand), Reference (Functions / Clojure core / Internals).
- **Criterio editoriale per simboli**: tre destinazioni (Reference standard, Reference Internals, Allowlist) basate sulla domanda "per chi è scritto?". Audit Spec↔codice come prerequisito del brief B.
- **Source-of-truth del piano**: il repo (`docs/manual-redesign-plan.md`) è la copia di lavoro viva. Il Project di Claude viene ricaricato dal repo a inizio sessione quando necessario. Decisione presa il 2026-05-16, dopo che Claude ha acquisito accesso in lettura/scrittura alla cartella del repo: un unico posto da sincronizzare, sia per Vincenzo sia per Claude.
- **Convenzione `/docs/` vs `/dev-docs/`**: `/docs/` contiene materiale stabile destinato a pubblicazione o consultazione anche da terzi (Spec.md, Architecture.md, Roadmap.md, `manual-redesign-plan.md`, esempi). `/dev-docs/` contiene materiale interno o temporaneo (brief per Code, audit, spec di feature in lavorazione, e `reference-manual/` come cartella di lavoro durante la fase di stesura). Le schede Reference vivono in `/dev-docs/reference-manual/` finché il Flusso B non ne fissa la destinazione definitiva.
- **Stato dei draft narrativi (2026-06-02)**: i capitoli 2-17 più `about-ridley` sono redatti in bozza e rivisti dall'autore. Considerati a posto salvo rifiniture (pulizia nomi italiani in 2.1/2.2, placeholder `[→ cap. N]` in 2.6, schede `goto`/`look-at`, scheda Internals "Naming patterns"). Le rifiniture sono rinviabili e non bloccano la v1.
- **Definizione della v1 del manuale (2026-06-02)**: la prima versione pubblicabile comprende lo switch da `content.cljs` alla nuova struttura Markdown (cutover `on: release`) e la Reference consultabile dentro il manuale, ossia browser per categoria più search interna. Questo è il contenuto del Brief B (T-007). La Reference sfogliabile è dentro la v1 perché è una traccia primaria, non un nice-to-have come la galleria.
- **Fast-follow dopo lo switch (2026-06-02)**: i due tipi di link verso le schede Reference, tooltip dell'editor verso scheda e prosa delle guide verso scheda, sono trattati come sessione separata successiva allo switch. La v1 regge senza: prosa eseguibile più Reference sfogliabile, due tracce affiancate ma non ancora collegate dai link.
- **Parcheggiati fuori dalla v1 (2026-06-02)**: galleria di progetti, guide tematiche, cap. 18 "Estendere Ridley", e le rifiniture dei draft. La traduzione (guide IT verso EN, schede EN verso IT) resta dopo, a prosa congelata, con il glossario come primo passo.

### 13.2 Aperte (vedi §10 e §11)

Decisioni di forma minore in §10 e di pianificazione futura in §11.

---

## 14. Programma di lavoro

Sezione operativa che traccia lo stato del lavoro di esecuzione del piano. È pensata per **funzionare come stato condiviso tra chat diverse**: ogni nuova chat di lavoro inizia leggendo questa sezione per orientarsi.

### 14.1 Rituale di chat

Quando si apre una nuova chat di lavoro:

1. L'autore (Vincenzo) dice a Claude su quale task si sta lavorando, con riferimento al codice del task in §14.3 (es. "lavoriamo su T-005").
2. Claude legge `/Users/vipenzo/Progetti/Ridley/docs/manual-redesign-plan.md` — in particolare §14, lo stato del task in §14.3 e le decisioni del quaderno (§14.5) — e si orienta.
3. Si lavora. Claude legge direttamente dal repo i file di supporto (Spec.md, Architecture.md, esempi `.clj`, guide esistenti) e scrive direttamente nel repo i deliverable (schede Reference in `dev-docs/reference-manual/`, brief per Code in `dev-docs/`, ecc.).
4. A fine sessione, Claude **aggiorna il piano** direttamente nel repo: spostare il task da "In corso" a "Fatto" se concluso, aggiornare lo stato se ancora aperto, annotare nel quaderno (§14.5) le decisioni emerse durante l'esecuzione, aggiungere una riga allo storico revisioni.

Se il piano non viene aggiornato a fine sessione, ricominciano i problemi di memoria al turno successivo. L'aggiornamento è il rituale di chiusura, non opzionale.

### 14.2 Convenzioni

- Ogni task ha un codice `T-NNN` progressivo.
- Stato: **Da fare** / **In corso** / **Fatto** / **Bloccato**.
- Owner: **Vincenzo** (autore), **Claude** (Claude in chat), **Code** (assistente coding), **V+C** (coppia in chat), **V+Code** (autore valida output di Code).
- Riferimento al piano: sezione di §… o capitolo del manuale che il task tocca.

### 14.3 Task

#### In corso

- **T-007 (preparazione)** — Definizione della v1 e preparazione del Brief B
  Owner: V+C
  Rif: §9.2, §13.1 (definizione v1)
  Stato: **Fatto**. v1 definita (2026-06-02) e Brief B scritto (`dev-docs/brief-manual-infrastructure.md`). Implementazione conclusa (vedi T-007 e T-009 sotto).

- **T-011** — Gap-fill copertura guide (gruppo A dell'analisi di copertura)
  Owner: V+C
  Rif: analisi copertura di Code (51 simboli mai citati nelle guide); metro fissato sul cap. 3
  Scope: per i simboli del gruppo A mai citati, aggiungere nelle guide una menzione o un esempietto alla prima menzione significativa nella sezione che li tratta. Peso pieno con esempio per quelli che lo meritano, menzione onesta quando ci sono limiti, raggruppamento quando emerge una famiglia. Predicati (`shape?`, `path?`, `mesh?`, ecc.) e gruppo C (export di basso livello, helper) restano alla Reference. Niente link manuali (auto-link, T-010).
  Progresso:
  - cap. 3 ✓ (`voronoi-shell` + `pattern-tile` come sottosezione "Operatori generativi", verificati funzionanti; `svg-shapes`/`svg-shape` in §3.3 col vincolo del `"`; `make-shape`, `reverse-shape`; `stamp-visibility` già coperto da show/hide-stamps in §3.2)
  - cap. 4 ✓ (`loft-between` riassegnato al cap. 6, è solo un alias del `loft` a due shape; `extrude-axis`/`extrude-z`/`extrude-y` testati no-op da Vincenzo e tolti dalla guida: la scheda Reference `extrude-axis.md` documenta una funzione che non fa nulla, da correggere o rimuovere)
  - cap. 5 ✓ (`subpath-y` in §5.8, esempio sistemato da Vincenzo e funzionante; il 2° esempio della scheda Reference è ancora sotto indagine di Code; `bounds-2d` rimosso dalla guida perché è l'implementazione per le shape della `bounds` polimorfica, si cita `bounds` non `bounds-2d` → Reference; `path?` → Reference)
  - cap. 6 ✓ (`fbm` come mattone in §6.3 displaced, esempio rocky verificato funzionante; `loft-between` come nota in §6.4 morphed, forma grezza del morphing a due shape, senza esempio nuovo)
  - cap. 7 ✓ (`find-sharp-edges` in §7.3 come ispezione di basso livello, prosa senza esempio; il ricordo di "averne già parlato" era probabilmente `chamfer-edges`/`chamfer-prisms`, già in §7.3 e concettualmente identici; `lay-flat` spostato al cap. 17, vedi sotto; `mesh?` → Reference)
  - cap. 8 ✓ (famiglia di rotazione `cp-th`/`cp-tv`/`cp-tr` aggiunta in §8.2 accanto a `cp-f`/`cp-u`/`cp-rt`, esempio `cp-rotation-bracket` verificato; `reset-creation-pose` lasciata solo in Reference, Vincenzo non le trova un uso; `link!`/`unlink!` → gruppo B, binding a tempo di animazione)
  - `get-anchor` e `pen` ✓ (collocati nel cap. 5, non "cap. 11/turtle" della triage: `get-anchor` accanto ad `anchors` in §5.8, `pen` in §5.2 con show/hide-lines)
  - cap. 12: niente del gruppo A (`sdf-revolve` derubricato a Reference, è l'implementazione SDF della `revolve` polimorfica, stesso caso di `bounds-2d`)
  - cap. 15: niente del gruppo A. Le quattro funzioni di visibilità (`line-visibility`, `show-only-objects`, `show-turtle`, `fit-camera`) sono quasi internals: per l'uso normale sono mappate sui bottoni della UI, da codice interessano solo a chi fa qualcosa di molto particolare. Vanno al cap. 18 (Estendere Ridley) quando lo faremo, non al cap. 15.
  - cap. 17 ✓ (`lay-flat` come nuova sezione 17.3 "Orientare per la stampa"; esempi default e direzione runnable, modalità anchor in prosa; esempi da verificare)
  Fuori: gruppo B (animazione/interattivo) come decisione separata, ora include anche `link!`/`unlink!` (rigging parent/child a tempo di animazione); predicati e gruppo C alla Reference.
  NB verifica: gli esempi del gap-fill sono presi dalle schede Reference e non eseguiti da Claude nel REPL; vanno fatti girare (i blocchi example-source) per confermarli, perché le schede possono essere sbagliate (caso `extrude-axis`, no-op silenzioso).
  Stato: ritmo rallentato su richiesta (2026-06). Gruppo A CHIUSO: tutti i simboli collocati o rimandati. Cap. 3-8 e 17 fatti e confermati (`fbm`, `cp-rotation-bracket` verificati; esempi `lay-flat` da verificare); `get-anchor`/`pen` nel cap. 5. Le 4 funzioni di visibilità spostate al cap. 18 (non cap. 15). Aperti: capitolo 18 (internals/estendere, che ora raccoglie anche le 4 di visibilità) e la decisione sul capitolo Animazione (gruppo B + `link!`/`unlink!`).

- **T-012** — Traduzione guide IT→EN
  Owner: Claude (traduzione) → Vincenzo (revisione)
  Rif: glossario `dev-docs/translation-glossary.md`; decisione 2026-06 (§14.4 backlog, §14.5)
  Scope: tradurre le 17 guide narrative da IT a EN, a mano capitolo per capitolo, applicando il glossario. Per ogni capitolo servono due cose: il file EN in `docs/manual/guides/en/<file>.md` E l'aggiornamento del suo entry in `src/ridley/manual/structure.cljs` con `:langs #{:it :en}` + titolo `:en` (il fallback bilingue è metadata-driven, il file da solo non basta: senza `:langs` il renderer continua a servire l'italiano). Codice degli esempi copiato verbatim. Schede Reference NON tradotte (restano EN).
  Progresso:
  - Glossario nucleo committato e validato (2026-06): `dev-docs/translation-glossary.md`. `smusso`→bevel; resto come proposto.
  - Freeze pulito IT: placeholder `[→ cap. N]` per `cp-*` nella 2.6 risolto a cap. 8; ID esempio `primitive-scale-uniforme`/`-non-uniforme` → `-uniform`/`-non-uniform`.
  - cap. 1 (`about-ridley`) ✓: file EN creato, `structure.cljs` aggiornato (`:ch-about` → `:langs #{:it :en}`, titolo EN "Ridley at a glance").
  - cap. 2 (`02-modeling-with-primitives`) ✓: file EN creato, `:ch-02` → `:langs #{:it :en}` + titolo EN "2. Modeling with primitives". Freeze addendum: commento italiano nell'esempio `cad-vs-turtle` (`; stessa staffa…`) corretto in inglese nella sorgente IT (gli esempi sono condivisi e vanno in inglese). Da verificare con Vincenzo: (a) la nota interna dell'autore in cima al file è stata lasciata in italiano anche nel file EN; (b) l'esempio `spiral-vase` ha il codice DENTRO il commento `<!-- example-source … -->` invece che in un blocco separato dopo il marker, anomalia rispetto alla convenzione, mirrorata verbatim in EN ma forse è un bug di sorgente; (c) la descrizione del box del portapenne in 2.3 (`(box 40 40 80)` = "largo 40, profondo 40, alto 80") da ricontrollare contro l'ordine (r u f).
  Restano: cap. 3-17 in ordine.
  Stato: in corso.

#### Da fare (prossimi)

- **T-007** — Brief B per Code (infrastruttura documentazione, = v1 del manuale)
  Owner: V+C
  Rif: §9.2, §13.1 (definizione v1)
  Scope v1: switch da `content.cljs` alla nuova struttura Markdown (cutover `on: release`, migrazione non distruttiva); renderer Markdown con esecuzione dei blocchi `example-source` inline; Reference consultabile nel manuale (browser per categoria + search interna); pipeline di build per `reference-index.cljs`; risoluzione bug Edit (§7.4); integrazione guide orfane (§3.3). Fuori dalla v1: i due link verso le schede (vedi T-009), galleria, guide tematiche.
  Prerequisiti: T-001 (chiuso ✓), T-008 (chiuso ✓), T-008b (chiuso ✓); tutti i punti §10 chiusi o rinviati (✓, vedi §10.2).
  Stato: **Fatto** (giugno 2026). Brief in `dev-docs/brief-manual-infrastructure.md`, implementato. Realizzato: `structure.cljs` (nav unica, slug senza `NN-`); contenuti migrati in `docs/manual/guides/{it}/` e `docs/manual/reference/{en}/`; `draft_renderer` legge il manifest da `structure.cljs` e fa il fetch a runtime; Reference nel manuale (`reference_browser.cljs`) con browser per categoria, search interna e scheda completa renderizzata via `draft_renderer` (Clojure core in vista compatta; Internals nascosta finché senza schede); `reference_index.cljs` generato da `scripts/build_reference_index.bb` (input `docs/manual/reference/en/`); fallback bilingue metadata-driven; fix bug Edit con modale di conferma WKWebView-safe; cutover dietro `config/new-manual?` (`goog-define`, default true dev, pin in `:release`); `manual-output/` declassato a build artifact. Riferimenti: commit `1897cfd`, `e028190`, `3ee8c1b`. Architecture.md §11.6 aggiornato. Resta la rimozione di `content.cljs` a cutover consolidato.

- **T-009** — Fast-follow: link verso le schede Reference (sessione separata, dopo lo switch)
  Owner: V+C → Code
  Rif: §8 (search contestuale), §2.1 (link tra tracce)
  Scope: link dal tooltip dell'editor alla scheda completa (Flusso C, §9.3) e link dalla prosa delle guide alle schede. Sono il tessuto connettivo tra Guide e Reference; la v1 funziona senza.
  Stato: **Fatto** (giugno 2026), commit `4788f49`. Realizzato: `reference-browser/open-card!` apre il manuale sulla scheda di un simbolo (risolve nome, `ref:NOME`, o link relativo `*.md` dei "See also"); bottone "apri nel manuale" nel tooltip in hover dell'editor (`codemirror/set-reference-handler!`); intercettazione dei link `ref:`/`*.md` nella prosa di guide e schede (`draft-renderer/set-link-handler!`); in più i tooltip Reference compaiono ora anche sui nomi di funzione nei blocchi di codice degli esempi (renderizzati in `document.body` per non essere clippati). L'editor non dipende dal manuale: collegamento via callback in `setup-manual`.

- **T-010** — Auto-linking dei riferimenti Reference nella prosa
  Owner: V+C → Code
  Rif: estende T-009; brief in `dev-docs/brief-autolink-reference.md`
  Scope: a render time, ogni code span in backtick nella prosa il cui contenuto è esattamente un simbolo dell'indice diventa un link alla scheda, riusando l'handler di T-009. Solo dentro i backtick (mai parole nude), match esatto, risoluzione per nome anche per i filename sanificati (`extrude-plus.md`, `transform-arrow.md`).
  Stato: **Fatto** (giugno 2026). Implementato sui `<code>` della prosa; criteri di accettazione verdi: match esatto su set, niente link a parole nude, `extrude+`/`revolve+`/`transform->` risolti per nome, niente doppio wrapping (`.closest("a")`). I 17 link manuali di calibrazione nel cap. 4 sono stati rimossi, così l'auto-link è l'unico meccanismo. Nota: i simboli di `clojure-core-index` sono esclusi dal set; aggiungerli renderebbe cliccabili anche `map`, `let`, ecc. nei backtick (opzione aperta, vedi backlog).

#### Fatto

- **T-003 / T-004 / T-005** — Chiusura dei punti aperti §10
  Owner: V+C
  Rif: §10, §10.2
  Esito: i nove punti §10 chiusi o rinviati nella sessione del 2026-06-02 (dettaglio in §10.2). I tre task erano raggruppamenti dei punti e si chiudono insieme.
  Chiuso: 2026-06-02.

- **T-006** — Cap. 2 "Modellare per primitive": stesura completa
  Owner: V+C
  Rif: cap. 2 del sommario; draft in `dev-docs/manual-drafts/02-modeling-with-primitives.md`
  Esito: cap. 2 completo (2.1-2.6 più 2.5b), esempi popolati. Rifiniture rinviate e tracciate in §13.1/backlog: pulizia nomi inglesi in 2.1/2.2, placeholder [→ cap. N] per cp-* nella 2.6.
  Chiuso: 2026-05-19.

- **Draft narrativi cap. 3-17** — stesura e revisione d'autore
  Owner: V+C
  Rif: §3.1; `dev-docs/manual-drafts/`
  Esito: capitoli 03-17 redatti e rivisti dall'autore; con `about-ridley` (cap. 1) e il cap. 2, l'intero arco narrativo è in bozza. Esempi `example-source` inline considerati a posto salvo rifiniture. Cap. 18 "Estendere Ridley" deliberatamente non scritto (parcheggiato fuori dalla v1). Stesura distribuita su più sessioni non loggate singolarmente qui; questa voce le consolida.
  Chiuso: 2026-06-02.

- **T-008b** — Consolidamento numerazione di Spec.md (chiusura del gap §17 → §19 → §20)
  Owner: Claude (drafting brief) → Code (esecuzione) → V+C (validazione)
  Rif: deliverable di T-008; brief in `dev-docs/brief-spec-numbering.md`
  Esito: no-op. Code ha verificato che il working tree di `docs/Spec.md` era già in stato consecutivo §17 → §18 (Internals) → §19 (Not Yet Implemented). T-008 era stato applicato direttamente alla numerazione corretta, bypassando lo stadio "gap transitorio" che il piano e i deliverable di T-008 avevano descritto. I cross-reference interni a §18 erano già coerenti (slug `#188-source-tracking--metaprogramming`). `grep` esteso al repo (*.md, *.cljs, *.bb, *.edn): nessun riferimento residuo a `#19`/`#20`/`§19`/`§20` da aggiornare. `bb scripts/spec_lint.bb` exit 0. Lezione: il piano descriveva uno stato intermedio che non si è mai manifestato su disco; lo stato del file non era stato osservato direttamente. Per task simili in futuro, conviene verificare lo stato corrente dei deliverable prima di scrivere brief correttivi.
  Chiuso: 2026-05-16.

- **T-008** — Brief per Code: Fronte 2 (aggiornamento Spec.md + popolamento allowlist)
  Owner: Claude (drafting) → Code (esecuzione) → V+C (validazione)
  Rif: §6.7, §6.9 punto 3, §10.1; brief in `dev-docs/brief-spec-update.md`
  Esito: Code ha integrato in `docs/Spec.md` i 30 simboli A nelle sezioni §2-§17 (con nuove sub "Predicates" in §4 e §11, "Edge analysis" e "Orientation utilities" in §7, "Vector math (3D)" in §17, "Import" in §4 e §7) e ha aggiunto la nuova `## 18. Internals` con 62 simboli B distribuiti in 9 sotto-sezioni (la sub "Contracts" non riceve simboli e viene scritta a mano in fase di stesura Reference). `## 18. Not Yet Implemented` traslata a `## 19`. `scripts/spec_allowlist.edn` popolato con 112 simboli plumbing in 9 famiglie commentate. `bb scripts/spec_lint.bb` esce con exit 0 (432 simboli bound, tutti coperti). Decisioni di Code accettate: §16 lasciato invariato, import distribuito (svg-shapes in §4, decode-mesh in §7); `source-of` in §18.8 con rimando da §18.4; `show-by-prefix!`/`hide-by-prefix!` esclusi perché non bound in SCI nonostante esistano in `scene/registry.cljs`; `show-panel!`/`hide-panel!` aggiunti in §18.3 (12 simboli totali, non 11 — §6.8 corretta in coerenza). **Nota**: nel report originale di Code la numerazione era riportata come §19/§20, ma la verifica diretta del file in T-008b ha mostrato che il working tree era stato applicato con la numerazione finale consecutiva §17 → §18 → §19. Convenzioni acquisite in §14.5.
  Chiuso: 2026-05-16.

- **T-001** — Brief per Code: Fronte 1 (audit Spec↔codice + lint script)
  Owner: Claude (drafting) → Code (esecuzione) → V+C (validazione)
  Rif: §6.7, §6.9 punti 1-2, §10.1
  Esito: Code ha prodotto `docs/symbol-audit-report.md` (107 simboli classificati: 30 A, 62 B, 14 C, 1 da chiarire risolto come C — `claim-mesh!`) e `scripts/spec_lint.bb` + `scripts/spec_allowlist.edn` (432 simboli bound, 201 orfani al primo run, exit code 1, output formattato come da brief). Pattern emersi dall'audit incorporati in §6.8 (rifattorizzazione di "Selection & visibility" in Scene visibility / Picking & selection / Interactive testing). Convenzioni acquisite in §14.5.
  Chiuso: 2026-05-16.

- **T-002** — Scheda Reference di `loft` come modello canonico
  Owner: V+C
  Rif: §4 (schema scheda), §4.1
  Esito: scheda `loft.md` prodotta in EN, salvata in `dev-docs/reference-manual/loft.md`. README della cartella aggiunto. Schema §4.1 validato con cinque estensioni acquisite in §14.5: monolingua per scheda, pattern "scheda madre + stub" per varianti di arity, modi alternativi presentati senza etichetta peggiorativa, slug stabile in `category`, struttura cartella piatta + sottocartella `internals/`.
  Chiuso: 2026-05-16.

### 14.4 Backlog

Task identificati ma non ancora pianificati. Da promuovere in "Da fare" quando ci si arriverà.

- Cap. 3-17 delle Guide narrative: **stesura completata** (2026-06-02), rivista dall'autore. Resta solo rifinitura.
- Schede Reference: il grosso è stato scritto da Code a blocchi per categoria (vedi storico). `goto`, `look-at` e `turtle-state` esistono già. Resta da scrivere la scheda Internals panoramica "Naming patterns in scene mutation". Rifiniture rinviabili, fuori dalla v1.
- Rifiniture draft (fuori dalla v1): pulizia nomi italiani negli esempi 2.1/2.2; placeholder `[→ cap. N]` per `cp-*` in 2.6.
- Cap. 18 "Estendere Ridley" (guida): il lato narrativo dell'Internals. Copre come estendere Ridley: sistema librerie, modello SCI, limiti dei namespace, differenze da Clojure reale. Parcheggiato. Si accoppia alla sezione Internals della Reference (l'una insegna, l'altra cataloga). Da affrontare con il metodo interview-then-write, perché parte di quel codice ha avuto stesura vibe-coded con poca visibilità diretta.
- Guide tematiche: "Superfici parallele" come prima. Parcheggiate fuori dalla v1.
- Galleria di progetti: parcheggiata fuori dalla v1; il codice dei sei esempi è preservato in `docs/examples/gallery/` perché sopravviva allo switch. Decisione se unirla alle guide tematiche rimandata.
- Traduzione: **priorità all'inglese completo** = guide IT→EN (le schede sono già in EN, quindi tradotte le guide il lato inglese è chiuso). La versione italiana delle schede (EN→IT, 259 schede) è a **bassissima priorità, forse non si fa affatto** (decisione 2026-06): il fallback bilingue mostra già le schede EN agli utenti IT. Glossario come primo passo, a prosa congelata. Metodo asimmetrico: le guide a mano capitolo per capitolo con revisione; le schede, se mai, un batch separato guidato dal glossario.
- Brief C per Code (search contestuale CodeMirror): confluisce in T-009 (fast-follow), dopo lo switch della v1.
- Auto-link dei simboli Clojure core (`map`, `let`, ecc.) nei backtick: l'auto-linker (T-010) opera solo sul `reference-index`. Aggiungere le chiavi di `clojure-core-index` al set li renderebbe cliccabili. Opzione, da valutare se utile.
- **Sezione Internals della Reference ("capitolo internals")**: scrivere le schede dei ~62 simboli B classificati in T-001/T-008 e raccolti in Spec §18 (9 sotto-sezioni). Oggi la sezione Internals del browser è nascosta perché senza schede (residuo di T-007). Blocco di lavoro sostanzioso, da pianificare a batch come gli altri della Reference; la prima scheda è quella panoramica qui sotto. Si accoppia al cap. 18 delle guide ("Estendere Ridley"): stesso dominio, l'una cataloga i simboli, l'altra li insegna.
- **Scheda Internals "Naming patterns in scene mutation"**: scheda panoramica che documenta le regolarità emerse dall'audit T-001 (tutti i `register-X!` → Registry pattern, tutti i `get-X` di lookup → Registry/introspection, tutti i `show-X!`/`hide-X!` → Scene visibility, tutti gli `anim-*` → Animation API, tutti i `*-anchors*` → C/plumbing). Da scrivere come prima scheda della sezione Internals.

### 14.5 Quaderno delle decisioni emerse durante l'esecuzione

Annotazioni operative non previste nella pianificazione, prese durante la scrittura. Da consultare prima di iniziare un task per non re-decidere.

- **2026-05-16 (T-002)** — *Una scheda Reference = un file monolingua.* La scheda canonica si produce in inglese (EN è source-of-truth per la Reference, §5.1). La versione IT è un task successivo, da pianificare quando il workflow di traduzione (punto 10.4) sarà chiuso.

- **2026-05-16 (T-002)** — *Funzioni con varianti di arity: pattern "scheda madre + stub".* Per simboli che Spec.md raggruppa sotto un'unica voce con più varianti (es. `loft` / `loft-n` / `loft-between`), si produce una scheda madre che copre le firme correlate, più schede stub per le varianti che contengono solo signature minimale e rimando alla scheda madre. Decisione applicabile a tutti i casi analoghi (`extrude`/`extrude-closed`, `box` con due arity, `sphere` con due arity, `cyl`/`cone` con segments opzionali, ecc.).

- **2026-05-16 (T-002)** — *Modi secondari di una funzione: documentati senza etichetta peggiorativa.* Le funzioni che ammettono uno stile di chiamata "diretto" affiancato a uno "più libero" (es. `loft` shape-fn mode vs transform-fn mode) presentano entrambi i modi nella scheda. Il modo che chiama una funzione di trasformazione esplicita è descritto come "transform-fn mode" (non "legacy mode"): è di fatto la via più flessibile, indicata quando si vogliono trasformazioni custom non incapsulate in una shape-fn riusabile. Lo si presenta in coda alla sezione `Description`, non in `Notes`.

- **2026-05-16 (T-002)** — *Frontmatter `category`: slug stabile.* Il campo `category` nel frontmatter usa lo slug (`generative-operations`), non la stringa testuale numerata di Spec.md (`6. Generative Operations`). Lo slug è stabile rispetto a rinumerazioni future di Spec.md. La label leggibile e l'ordine di presentazione vengono ricostruiti a build time da `structure.cljs`. Aggiornare §4.1 al prossimo refresh del piano in coerenza.

- **2026-05-16 (T-002)** — *Cartella di lavoro per le schede Reference.* Le schede prodotte fra T-002 e il completamento del Flusso B vivono in `dev-docs/reference-manual/`. Struttura: piatta per la Reference standard (un file per scheda nella root), con sottocartella `internals/` per le schede della sezione Internals. Il `Readme.md` della cartella documenta la convenzione e indica `loft.md` come scheda di riferimento per lo schema. La struttura definitiva delle schede nel manuale finale è decisione del Flusso B; questa è solo l'organizzazione del materiale d'autore in transito.

- **2026-05-16 (post T-002)** — *Inversione del source-of-truth del piano.* Il repo (`docs/manual-redesign-plan.md`) è ora il source-of-truth. Il Project di Claude viene ricaricato a inizio sessione se necessario. Claude ha accesso in lettura/scrittura a `/Users/vipenzo/Progetti/Ridley/` e legge/scrive direttamente lì: piano, file di supporto, deliverable. Conseguenza pratica: §13.1 e §14.1 aggiornati di conseguenza nello stesso refresh.

- **2026-05-16 (T-001)** — *Pre-filtro plumbing più conservativo della stima del piano.* Il pre-filtro sintattico (suffisso `-impl`, prefissi `pure-`/`rec-`/`turtle-`, dynamic vars `*…*`, costruttori-helper noti) classifica come plumbing 97 simboli su 204, lasciando 107 sospetti — più dei ~70 stimati a §6.7. La differenza non è un errore della stima ma una scelta: il filtro sintattico è prudente per non perdere falsi negativi (simboli classificabili A o B nascosti dietro un pattern lessicale). Il numero di B reale (62) e A (30) ricalibra l'attesa per le sezioni Reference Internals e Standard.

- **2026-05-16 (T-001)** — *Rifattorizzazione di "Selection & visibility" in tre sotto-categorie.* La sotto-categoria originaria §6.8 raccoglieva 22 simboli semanticamente eterogenei. Articolata in: **Scene visibility** (`show-X!`, `hide-X!`, `refresh-viewport!`, ~11 simboli); **Picking & selection** (`selected*`, `origin-of`, `last-op`, `source-of`, ~7 simboli); **Interactive testing** (`tweak-start*`, `get-turtle-resolution`, `get-turtle-joint-mode`, ~4 simboli). §6.8 aggiornata.

- **2026-05-16 (T-001)** — *Pattern di naming emersi dall'audit, da rivendicare nelle Internals.* Le regolarità sono nette: tutti i `register-X!` → Registry pattern; tutti i `get-X` di lookup → Registry/introspection; tutti i `show-X!`/`hide-X!` → Scene visibility; tutti gli `anim-*` → Animation API; tutti i `vec3-*` → A/math utility; tutti i `*-anchors*` (suffisso star) → C/plumbing macro. Da documentare in una scheda Internals panoramica ("Naming patterns in scene mutation") prima delle schede di famiglia. Aggiunto al backlog §14.4.

- **2026-05-16 (T-001)** — *`claim-mesh!` classificato C (Allowlist).* Letto il sorgente `scene/registry.cljs:114-117`, è chiaro che `claim-mesh!` è un meccanismo di mutual exclusion fra due percorsi di registrazione (`register` esplicito vs `set-definition-meshes!` automatico), non un'API per chi scrive macro register-like. L'utente non lo chiama mai direttamente. Chi vuole scrivere un macro register-like userebbe `register-mesh!` / `register-path!` / etc. direttamente.

- **2026-05-16 (T-001)** — *Pattern dei `vec3-*` come math utility user-facing.* I sei simboli `vec3+`, `vec3-`, `vec3*`, `vec3-cross`, `vec3-dot`, `vec3-normalize` (definiti in `turtle/core.cljs` come `v+`, `v-`, `v*`, `cross`, `dot`, `normalize`) sono operatori vettoriali per coordinate 3D, gemelli dei `Math/*` (sin, cos, ...) già esposti. Vanno in A/Reference standard. Da inserire in Spec.md nella categoria "Math utilities" o equivalente, in T-008.

- **2026-05-16 (T-001)** — *Convenzione bang `!` per la versione user-facing del macro creation-pose.* `set-creation-pose` (plain) è plumbing del macro `set-creation-pose!` (con bang). La versione user-facing è il macro, non la funzione. Il pattern "plain function = plumbing, bang macro = user-facing" può ripetersi altrove e va tenuto in mente durante T-008.

- **2026-05-16 (post T-001)** — *Decisione sul path di `symbol-audit-report.md`*. Il report di audit prodotto in T-001 sta oggi in `docs/symbol-audit-report.md`. La convenzione `/dev-docs/` per i report interni è entrata in vigore dopo, quindi il file è in posizione formalmente incoerente. Decisione: **lasciare dov'è**, la convenzione vale per i nuovi documenti. Eventuale pulizia in seguito ("se mai puliamo dopo"). Casi analoghi futuri vanno direttamente in `/dev-docs/`.

- **2026-05-16 (post T-001)** — *Convenzione `dev-docs/manual-drafts/` per i drafts del manuale narrativo.* Cartella gemella di `dev-docs/reference-manual/`. Convenzione di naming: i capitoli numerati (cap. 1-17 del sommario §3.1) usano prefisso `NN-` a due cifre (`01-getting-started.md`, ..., `17-extending-ridley.md`); i pezzi fuori-numerazione (about, FAQ, eventuali pagine di servizio) non hanno prefisso (`about-ridley.md`). Un capitolo = un file; i sotto-capitoli vivono come `##` interni al file, non come file separati. Quando il Flusso B sarà pronto, i prefissi potrebbero sparire negli slug pubblicati (decisione di Flusso B); per ora aiutano solo l'ordinamento in cartella durante la stesura.

- **2026-05-16 (T-008)** — *§16 "Export" lasciata invariata, import distribuito.* Per i tre simboli A che hanno natura di import (`svg-shapes`, `decode-mesh`, eventualmente `save-*`), il brief T-008 lasciava a Code la scelta tra rinominare §16 in "Import/Export" oppure distribuire (svg-shapes in §4 nuova sub "Import", decode-mesh in §7 nuova sub "Import"). Code ha scelto la distribuzione per evitare di rinominare un titolo di sezione che si propagherebbe a TOC esterne. Decisione validata.

- **2026-05-16 (T-008)** — *Doppia natura di `source-of` risolta con rimando in prosa.* Il simbolo ha natura di picking (legge dalla selezione corrente) ma è anche source-tracking (espone la storia delle operazioni sul mesh). Code l'ha collocato in §19.8 Source tracking & metaprogramming (categoria primaria nell'audit), con un rimando in prosa da §19.4 Picking. Pattern utile per casi futuri: scegliere la categoria primaria e rimandare, anziché duplicare l'entry. Conteggio resta 62.

- **2026-05-16 (T-008)** — *Verifica di bindings.cljs come check definitivo di "simbolo user-facing".* Durante T-008 Code ha trovato che `show-by-prefix!`/`hide-by-prefix!` esistono in `scene/registry.cljs` ma non sono bound in SCI (verifica in `bindings.cljs`). L'audit T-001 li aveva classificati B/Scene visibility presumendo fossero esposti. Lezione per il futuro: la presenza in un sorgente non garantisce che un simbolo sia user-facing; il check definitivo è la presenza in `bindings.cljs`. Tenere a mente per audit successivi e per le schede Reference.

- **2026-05-16 (T-008)** — *§19.3 Scene visibility: 12 simboli, non 11.* Il piano §6.8 elencava 11 simboli per Scene visibility, ma il brief T-008 ha omesso `show-panel!`/`hide-panel!` (che erano nell'audit). Code li ha correttamente aggiunti in §19.3, portando il conteggio a 12. §6.8 del piano aggiornata di conseguenza.

- **2026-05-16 (T-008)** — *Sub "Contracts" delle Internals resta vuota nella Spec.* Confermato in implementazione: shape-fn e thickness-fn sono macro-driven, non emergono dall'audit come simboli bound. La sotto-sezione "Contracts" di §6.8 del piano non riceve simboli da Spec.md. Le schede Internals dei contratti si scriveranno a mano in fase di stesura Reference, partendo da `dev-docs/ShapeFn.md` per shape-fn e dal nulla per thickness-fn.

- **2026-05-16 (T-008 / T-008b)** — *Numerazione di Spec.md consecutiva da §17 a §19, gap mai esistito su disco.* La narrazione del piano descriveva un gap transitorio (§17 → §19 → §20) introdotto da T-008 e da chiudere con T-008b. Verifica di Code in T-008b: il working tree di `docs/Spec.md` era già in stato consecutivo §17 → §18 (Internals) → §19 (Not Yet Implemented), con slug interni coerenti. T-008 era stato applicato direttamente alla numerazione finale. Il piano stava descrivendo uno stato intermedio mai manifestato su disco. T-008b chiuso come no-op. Lezione: nei lavori che dipendono dallo stato corrente di un file, verificare lo stato osservato prima di redigere brief correttivi — la descrizione del piano può essere disallineata dalla realtà se l'osservazione iniziale è stata indiretta o presunta.

- **2026-05-16 (T-006)** — *Convenzione: prove interattive in Ridley come fonte di verità più affidabile della lettura del codice.* Durante la stesura del cap. 2.1 (orientamento delle primitive) la lettura del codice da parte di Claude ha prodotto due interpretazioni sbagliate consecutive. Diagnosi finale di Code: per `cyl` e `cone` esiste un wrapper (`transform-mesh-to-turtle-upright`) che applica uno swap heading↔up prima di `apply-transform`, invisibile leggendo solo `primitives.cljs`. Convenzione presa: quando un punto di documentazione tocca il comportamento osservabile di operazioni geometriche (orientamenti, ordini di argomenti, semantica di operazioni complesse), chiedere a Vincenzo una verifica interattiva in Ridley. Le prove costano pochi secondi e azzerano il rischio di documentare interpretazioni errate del codice.

- **2026-05-16 (T-006)** — *Regolarità box↔extrude come fatto sistemico, non come analogia di un caso.* Tutte le primitive con asse di sviluppo (box, cyl, cone) si estendono lungo l'avanti della turtle, con la sezione (dove c'è) nel piano destra-alto. Corrispondenza precisa con `extrude`: `(box w h d) ≈ (extrude (rect w h) (f d))`, `(cyl r h) ≈ (extrude (circle r) (f h))`, `(cone r1 r2 h) ≈ (extrude (circle r1) → (circle r2) (f h))`. La regolarità va dichiarata in apertura della sezione "Le primitive" del cap. 2 e ripresa al cap. 4. Per il `cone`: la "base" è la sezione vicina alla turtle (r1, davanti), il "top" è quella lontana (r2, dietro) — coerente con la metafora "primitiva che nasce dalla turtle e si estende in avanti".

- **2026-05-16 (T-006)** — *Convenzione `dev-docs/code-issues.md` come raccoglitore di discrepanze sorgente↔realtà.* File interno aperto per tracciare piccole incoerenze tra codice e comportamento osservabile (es. docstring stale di `cyl-with-resolution`, inconsistenza `turtle-cyl`/`cyl` riguardo allo swap heading↔up, ordine `[w d l]` non ancorato in Spec.md per `box`). Non sono bug funzionali — il comportamento osservabile è corretto — ma sono trappole per chi legge il codice (autore, futuro collaboratore, AI). Quattro voci iniziali aperte in T-006. Quando si toccano queste zone di codice per altre ragioni, allinearle.

- **2026-05-16 (T-006)** — *Convenzione: nota interna in cima ai draft del manuale.* Quando una sezione del manuale dipende da un comportamento accertato e non immediatamente leggibile dal codice (es. orientamento delle primitive 3D), si inserisce in cima al file di draft un commento HTML `<!-- ... -->` con le convenzioni accertate. Invisibile nel rendering, presente come reminder per chi modifica il file. Applicato a `02-modeling-with-primitives.md`.

- **2026-05-16 (T-006)** — *Strategia esempi durante la stesura: codice inline nel draft con flag di trasferimento.* Durante la stesura dei capitoli, il codice degli esempi vive provvisoriamente *dentro* il file `.md` del draft, in blocchi `clojure` marcati con commenti HTML `<!-- example-source: <id> → src/ridley/manual/examples/structure.cljs -->` e `<!-- /example-source -->`. Il blocco di codice segue immediatamente lo shortcode `{{example: <id>}}`. Quando il Flusso B sarà pronto, gli esempi verranno estratti e trasferiti in `structure.cljs`, e i blocchi `clojure` con i loro marker verranno rimossi dal `.md` (mentre lo shortcode resta). Vantaggi: la chat resta self-contained turno per turno; il `.md` è self-sufficient come deliverable d'autore; il trasferimento finale è automatizzabile via grep sui marker. Applicato a partire dalla sezione 2.2 del cap. 2.

- **2026-05-16 (T-006)** — *Barchetta integrata nella 2.2, non sezione propria.* Decisione strutturale presa durante la stesura: il piano originale prevedeva la barchetta come sezione 2.4 separata. La barchetta è stata invece integrata come sotto-sezione finale di 2.2 "Comporre", come mini-progetto di chiusura che mette in cooperazione le quattro operazioni appena viste. Conseguenza: la numerazione del cap. 2 è scorsa di -1 dalla 2.4 in poi (portapenne resta 2.3, def diventa 2.4, defn 2.5, CAD-bridge 2.6). Aggiornati §3.1 e §3.2 di conseguenza.

- **2026-05-16 (T-006)** — *`mesh-hull` aggiunta alla 2.2 come quarta operazione di composizione.* `mesh-hull` (inviluppo convesso) non era nel sommario originale del cap. 2, era prevista per il cap. 7 §7.2 insieme alle altre operazioni mesh-to-mesh. Promossa al cap. 2 perché è effettivamente uno strumento di composizione di prima classe, comune nella pratica (smussature, capsule, basette con bordi morbidi), e completa la panoramica delle operazioni mesh→mesh che il Tinkercad-refugee si aspetta di trovare. Presentata come operazione *generativa* (costruisce attorno alle mesh), distinta dalle tre booleane (operano sui volumi). Esempio: basetta con bordi morbidi via `mesh-hull` di quattro sfere ai vertici di un rettangolo. La presentazione nel cap. 7.2 dovrà essere coerente con quella del cap. 2: rimando in avanti per gli aspetti più avanzati (vettori di mesh come argomento, casi con mesh complesse, simmetria sistemica con `shape-hull` del cap. 3).

- **2026-05-16 (T-006)** — *Bug accertato: `scale` con determinante negativo produce mesh non-manifold.* Durante la stesura della barchetta abbiamo voluto usare `(scale vela -1 0.7 0.9)` per specchiare la vela sull'asse `right`. La mesh prodotta da `scale` con un fattore negativo (riflessione) ha facce con orientazione invertita e non è manifold; le operazioni booleane successive falliscono. Brief redatto e passato a Code per implementare il fix: applicare un `Reverse()` Manifold dopo la scale quando il determinante della matrice di scale è negativo. Idioma comune (es. `scale -1 1 1` per specchiare), va supportato. La barca nel draft resta scritta con la riflessione: funzionerà dopo il fix.

- **2026-05-17 (T-006, consolidamento pre-2.3)** — *Convenzione: parametri direzionali delle primitive nominati `r u f` (right, up, forward).* Per le funzioni la cui signature richiede di nominare assi nel frame della turtle (`box`, `rect`, e in futuro analoghe), i parametri formali in Spec.md (e nelle docstring quando vengono aggiornate) sono nominati con le lettere corrispondenti ai comandi turtle: `r` per right, `u` per up, `f` per forward. Quindi `(box r u f)` invece di `(box w d l)` o `(box width depth length)`, e `(rect r u)` invece di `(rect width height)`. Decisione di livello B nel senso discusso: cambia il *nome del parametro nel contratto documentale*, non l'API (le funzioni restano posizionali, l'utente continua a scrivere `(box 10 20 30)`). Beneficio: chi legge la signature vede subito di che asse si parla; coerenza con i comandi turtle (`(rt 10)`, `(u 5)`, `(f 30)`); leggibilità di una signature in cui assi e dimensioni sono allineati. Non si applica a `translate`/`scale`/`rotate` perché operano nel frame del mondo, non in quello della turtle, e mantengono `dx/dy/dz` e `sx/sy/sz`. Non si applica a `cyl`/`cone`/`sphere` perché hanno un solo parametro direzionale (la height/raggio), descrivibile in modo non ambiguo come "along forward". Applicato in Spec.md per `box` e `rect` durante questo consolidamento. Per il futuro: quando si introducono nuove primitive con parametri direzionali multipli, seguire la stessa convenzione.

- **Pendente** — Convenzione di naming per simboli con caratteri non-ASCII o problematici (`?`, `!`, `*…*`). Da fissare al primo simbolo che lo richiede. Casi noti: `shape?`, `path?` (interrogativi), `register-mesh!` (bang), dynamic vars `*path-length*` (asterischi). Verificare se `shape?.md` regge ovunque (filesystem, link Markdown, GitHub web) prima di adottare convenzioni alternative come `shape-p.md`.

- **2026-05-19 (T-006)** — *Lingua del codice negli esempi.* Decisione: il codice negli esempi del manuale usa nomi inglesi (variabili, register, keyword). La prosa è in italiano (source) e sarà tradotta in inglese. Gli esempi sono comuni alle due lingue e non devono essere toccati durante la traduzione. Applicato retroattivamente alle sezioni 2.3 e 2.4; la 2.5 era già in inglese. Vale per tutto il resto del manuale.

- **2026-05-19 (T-006)** — *Brief transform pivot.* Il comportamento di `scale` su mesh sembra usare la creation-pose come frame di riferimento, non world axes come dice la Spec. Brief consegnato a Code (`dev-docs/brief-transform-pivot-audit.md`) per audit del sorgente e eventuale correzione della Spec. La sezione 2.6 è bloccata in attesa della risposta.

- **2026-05-19 (T-006)** — *Allineamento transform mesh↔SDF + stretch-* locale.* L'audit di Code ha confermato l'asimmetria tra mesh e SDF (mesh: scale su assi creation-pose/pivot centroid; SDF: scale su assi world/pivot creation-pose; rotate: mesh pivot centroid, SDF pivot creation-pose). Decisione: unificare tutto su assi mondo + pivot creation-pose sia per mesh che SDF. Rimuovere `scale` dentro attach (breaking change accettata). Introdurre `stretch-f`, `stretch-rt`, `stretch-u` come comandi attach-only per scale lungo assi locali della turtle, in simmetria con `f`/`rt`/`u` (movimento) e `th`/`tv`/`tr` (rotazione). Brief consegnato a Code: `dev-docs/brief-transform-align-and-stretch.md`. Il vecchio brief di audit (`brief-transform-pivot-audit.md`) è superato.

- **2026-05-20 (T-006, cap. 7.1)** — *Da chiarire: quali costruzioni Ridley producono mesh non-manifold e perché.* La Spec afferma che `shell` con stili voronoi/lattice/checkerboard e thickness-fn a zero produce mesh rifiutate da Manifold con status 2 (NotManifold). In realtà il comportamento è più sfumato: lattice produce sotto-mesh manifold disgiunte, e thickness-fn a zero dovrebbe cucire le facce laterali. Il motivo esatto del rifiuto (singolo solido non connesso? artefatti topologici?) non è accertato. Serve una verifica interattiva caso per caso con `mesh-diagnose` per capire cosa succede realmente. Nel draft del cap. 7.1 il passaggio è stato scritto in modo generico senza fare affermazioni specifiche su shell. Il TODO è nel commento HTML del draft.

- **2026-06-02 (consolidamento stato + definizione v1)** — *Tre fatti e due decisioni messi a verbale.* (1) I draft narrativi cap. 2-17 più `about-ridley` sono completi e rivisti dall'autore; gli esempi `example-source` sono considerati a posto salvo rifiniture; l'incidente del cap. 7 (file sovrascritto con un placeholder) è risolto, il capitolo è in buono stato. (2) *Esempi inline nel Markdown, in via definitiva.* Gli esempi vivono dentro commenti `<!-- example-source: id ... -->` nello stesso file della prosa e il renderer li esegue direttamente. Niente estrazione verso `structure.cljs`, niente shortcode `{{example: id}}`. Questo supera la voce §14.5 del 2026-05-16 "Strategia esempi durante la stesura" (che prevedeva il trasferimento) e il modello di §2.4 originale. §2.3, §2.4, §7.1 e §13.1 aggiornati; riferimenti residui in §4.1 e §9.2 da leggere in questa chiave, ripulibili quando si tocca il Flusso B. (3) *Definizione della v1.* La prima versione pubblicabile è lo switch da `content.cljs` più la Reference consultabile nel manuale (browser per categoria + search interna): è il contenuto del Brief B (T-007). I due link verso le schede (tooltip→scheda, prosa→scheda) sono fast-follow in sessione separata dopo lo switch (T-009). Galleria, guide tematiche, cap. 18 e rifiniture restano parcheggiati; la traduzione resta dopo, a prosa congelata, glossario per primo.

- **2026-06 (avvio traduzioni)** — *Priorità inglese; schede IT forse mai.* Deciso di chiudere prima l'inglese completo: si traducono le guide IT→EN (le schede sono già in EN). La versione italiana delle schede (EN→IT) è a bassissima priorità e potrebbe non farsi affatto, anche perché il fallback bilingue mostra già le schede EN agli utenti IT. Metodo asimmetrico: le guide sono prosa narrativa, si traducono a mano capitolo per capitolo con revisione d'autore; le schede, se mai, sarebbero un batch separato guidato dal glossario. Primo passo operativo: glossario dei termini-prosa italiani → inglese (i termini tecnici già in inglese nella prosa IT restano identici; il codice degli esempi non si tocca, è condiviso). Freeze pulito prima di tradurre: chiudere le rifiniture italiane residue (placeholder `[→ cap. N]` per `cp-*` nella 2.6, nomi inglesi negli esempi 2.1/2.2).

- **2026-06-02 (chiusura §10 + migrazione contenuti)** — *Punti §10 chiusi, contenuti orfani gestiti.* I nove punti aperti di §10 sono chiusi o rinviati (dettaglio in §10.2); T-003/004/005 chiusi di conseguenza, T-007 sbloccato. Decisioni salienti: frontmatter zero per le guide, fallback multilingua bidirezionale di transizione, flag esempi `:no-run` e `:warning slow` documentati. Migrazione `content.cljs`: confronto fatto fra `manual-output/Manuale_it.md` e i draft, tutto ha casa. Orfani: `bloft` confermato rimosso dal DSL da Vincenzo (verifica REPL), galleria (sei esempi) preservata in `docs/examples/gallery/`, `T` e `turtle-position`/`heading`/`up` aggiunti al cap. 15.2. Verifica importante: Spec.md, Architecture.md e il piano sul disco erano già puliti da `bloft`; era il `project_knowledge` a servire un indice vecchio. Lezione ribadita: per lo stato corrente dei file fidarsi del filesystem reale (grep), non del project_knowledge né del ricordo. In Architecture.md resta solo una riga storica (1915) sul port JVM, lasciata come record di sviluppo. `goto.md`/`look-at.md`/`turtle-state.md` già presenti tra le Reference.

- **2026-06-05 (Workspaces nel manuale)** — *Workspaces documentati nel cap. 9, capitolo rinominato.* La feature Workspaces (documenti multipli con switch/new/rename/duplicate/close, pallino dirty, Save/Save As/Open desktop, Edit-su-esempio che apre un nuovo Workspace invece di sovrascrivere) è stata documentata come nuova sezione **9.1**, e il cap. 9 è stato rinominato **"Workspaces e Librerie"** (titolo in IT, EN e `structure.cljs`; slug invariato `librerie` per non rompere gli URL). Le sezioni librerie scalate a 9.2–9.6. Collocazione decisa da Vincenzo: scartati intro/`about-ridley` (capitolo-manifesto, mal si presta a UI operativa) e un capitolo "Interfaccia" dedicato (costerebbe rinumerazione mid-traduzione); vince il cap. 9 per il contrasto naturale Workspace/libreria. Documentata anche l'integrazione Workspace↔librerie (estensione UI portata da Vincenzo+Code): ogni Workspace ricorda l'elenco delle librerie attive (le librerie restano asset globale condiviso, nessuna duplicazione), lo switch riproietta la lista, un ws nuovo eredita quella corrente, l'elenco viaggia col file (header strippato) e si ripristina alla riapertura; aggiunta una riga nella sezione attivazione (9.3) sul fatto che la selezione è per-workspace. Rimossa la sottosezione "Librerie come workpad" (l'uso "libreria come scratchpad" è superato dai Workspace). File-backed trattato leggero e lato desktop: sul web l'aggancio ai file è limitato e il pallino dirty non compare (segnala disallineamento file-su-disco vs memoria). Tenuto **fuori dal manuale** il limite transitorio dei file salvati prima dell'header (alla riapertura ereditano le librerie correnti finché non risalvati): è migrazione, non comportamento durevole. Corretti due refusi pre-esistenti nel cap. 3 (IT+EN): `find-faces`/`largest-face` da "(cap. 9)" a "(cap. 10)"; rimando all'import SVG aggiornato al nuovo titolo del cap. 9. Tutte le modifiche speculari IT+EN. Lavoro fuori dal flusso T-task. Sommario §3.0/§3.1/§3.2 allineato alla nuova struttura del cap. 9.

- **2026-06-10 (cap. 11, capstone bezier)** — *Nuova §11.3 "Lo stesso profilo, cinque modi"; limite del loft a §11.4.* Aggiunta una sezione capstone al cap. 11 che costruisce lo stesso spigolo arrotondato in cinque modi ordinati su uno spettro, da "tutta matematica" (cubica risolta a mano) a "tutto interattivo" (`edit-bezier` a occhio). Tesi: i modi convergono, e lo strumento incontra l'utente a livelli diversi di formalità. Conteggio a tre livelli come payoff: cinque metodi, quattro muretti (il modo 3 è la rotta interattiva verso il 2), due curve distinte (1/2/4 identici, 5 control-polygon diverso). Fonte: l'esempio `examples/spigolo-quattro-modi.clj` scritto da Code e verificato da Vincenzo nel panel. È entrata come §11.3 con il "limite del loft" rinumerato a §11.4 (arco bezier compatto: basi, capstone, caveat). Un solo `example-source` eseguibile (`cinque-modi-confronto`, autosufficiente) più un frammento `:no-run` interattivo (`cinque-modi-edit`); gli esempi ripetono le definizioni perché il renderer valuta ogni example-source in isolamento (vincolo riportato da Vincenzo). Helper `wall` auto-misurante: costruisce lo scaffold della diagonale con un `side-trip` di soli mark (`:center`, `:D`), marca l'apice reale con `(add-mark … :apex 0.5)`, ed estrae il bombaggio con un righello `(ruler w :at :center w :at :apex)` agganciato agli anchor della mesh. I numeri magici del modo 4 (`half`) restano letterali per scelta: sono l'artefatto di `edit-bezier` disegnando solo la metà, non una formula da derivare; derivarli dalla cubica del modo 1 ne ucciderebbe l'identità di costruzione simmetrica indipendente. Rinominato il register `supporto` in `corner` (identificatore inglese; `supporto` rendeva male in EN). Micro-aggiunte a monte in §11.2: `bezier-as :control` (poligono di controllo, duale dell'interpolazione, paragrafo dedicato), forma path-first di `bezier-to-anchor` senza `with-path`, menzione di `:tension-end`.

- **2026-06-10 (anticipazione add-mark e ruler :at)** — *API nuove introdotte fuori dal capstone per non essere "a sorpresa".* Le due aggiunte user-facing di sessione (confermate in Spec.md e nelle schede reference da Code: `add-mark` tra le Path utilities, e la forma `<target> :at <name>` per `ruler`/`distance` in Measurement) comparivano per la prima volta nella §11.3. Anticipate con menzione breve e rimando in avanti: `add-mark` nel cap. 5 (§5.3 Marks, come complemento di `mark` per marcare un path già costruito a posizione frazionaria per lunghezza d'arco; più una riga nella tabella consumatori §5.8) e `ruler … :at`/`distance … :at` nel cap. 10 (§10.1 Distance per la forma argomento, §10.2 Ruler con rimando a §11.3 e il punto che i mark seguono la mesh dopo `attach`/`mesh-union`). Tutte le modifiche speculari IT+EN. La pulizia del ruler durante `edit-bezier` resta un fix interno, non entra in reference. `structure.cljs` invariato: la §11.3 è una sezione interna a una pagina già bilingue.

- **2026-06-10 (cap. 11, rifinitura: 4-bis e refactor `diag`)** — *`edit-bezier` per la metà, e `diag` come scaffold nominato.* Aggiunto a fine modo 4 il 4-bis: `edit-bezier` che disegna la metà mirando a `:D` (l'apice) invece che a `:end`, gemello interattivo del modo 4 come il modo 3 lo è del 2. Per renderlo possibile serviva un `:D` raggiungibile: `diag` è stato estratto da `wall` come scaffold nominato e a `:D` è stato dato un heading (`th 90` dopo `f D`, cioè +45°, la tangente nell'apice), così `(edit-bezier diag :at :D)` ha estremi e tangenti fissi e resta una sola tension. `wall` ora antepone `diag` via `follow-path` invece di costruire lo scaffold inline. Aggiornati di conseguenza il confronto e l'esempio del modo 3; i due interattivi (`cinque-modi-edit` e `cinque-modi-bis-edit`) sono `:no-run` autosufficienti. Allinea il manuale alla revisione di `examples/spigolo-quattro-modi.clj` fatta con Code. IT+EN speculari.

- **2026-06-10 (ristrutturazione leggibilità: cap. 1, how-to-read, badge, dedup)** — *Diagnosi e primo lotto di interventi sulla leggibilità delle Guide.* Diagnosi condivisa con Vincenzo dopo rilettura integrale dei cap. 2-17: (a) il lettore implicito cambia da sezione a sezione (neofita / OpenSCAD / sketch-CAD / Clojurista) senza che il manuale dichiari a chi parla; (b) mancava il cap. 1 (previsto in §3.1 fin dall'inizio, mai steso: `register`, Cmd+Enter e i comandi turtle base venivano usati dal cap. 2 senza introduzione); (c) ripetizioni sistematiche per la struttura "a spirale" non disciplinata (sezioni-da-mesh in 3.8 e 7.5 con lo stesso esempio; find-faces in 7.4 e 10.3; archi/bezier in 4.2 e 11.1-11.2; qp in 4.3 e 5.6; side-trip in 5.4 e 8.5; shape-fn spalmate fra 4.6-4.7 e 6; cap. 15 per metà rimandi). Interventi eseguiti in sessione: **(1) cap. 1 "Per iniziare" scritto** (`docs/manual/guides/it/01-getting-started.md`, scaffold §3.1: Ciao Ridley / Interfaccia / Run-vs-REPL / Tartaruga 5 comandi / Clojure minimo con confine esplicito vs cap. 16; i dettagli UI non verificabili da documenti — controlli camera, tooltip editor, Alt+Click, paredit assente — sono elencati nella nota interna del file per verifica interattiva di Vincenzo prima di considerarlo stabile). **(2) pagina `how-to-read`** (`docs/manual/guides/it/how-to-read.md`, fuori numerazione dopo about): pubblica finalmente la mappa per fasi di lavoro di §3.0 e definisce quattro percorsi di lettura per persona (neofita, OpenSCAD/code-CAD, sketch-CAD, "stampare stasera"); il principio chiave è che la numerazione resta stabile (slug immutabili, niente rinumerazioni) e l'ordine di lettura lo fanno i percorsi. **(3) `structure.cljs` aggiornato**: about a `:order 0`, nuova entry `:ch-howto` (`:order 0.5`, slug `how-to-read`), nuova entry `:ch-01` (`:order 1`, slug `getting-started`), entrambe `:langs #{:it}` (traduzioni EN da fare nel workflow T-012). **(4) Convenzione badge di livello** `<!-- level: base|intermediate|advanced [| prereq: …] -->`, valida a livello di capitolo e di singola sezione con ereditarietà capitolo→sezioni; brief di rendering consegnato a Code (`dev-docs/brief-level-badges.md`); il cap. 1 è il primo file annotato; l'annotazione dei cap. 2-17 è sweep editoriale successivo, IT+EN speculari. **(5) Regola "una casa per argomento" adottata** per il futuro sweep di deduplicazione: ogni strumento ha un capitolo proprietario, altrove al massimo una frase con rimando. Mosse concordate: sezioni-da-mesh → casa 7.5 (3.8 ridotta a cenno); find-faces → casa 7.4 (10.3 solo ottica analisi); archi → casa 4.2 (11.1 smette di re-introdurli e tiene il materiale nuovo); bezier → casa 11.2 (4.2 ridotta ad assaggio); qp → casa 4.3; side-trip → casa 5.4; 15.4-15.6 collassano in una tabella "dove trovare gli strumenti". **Pendenti**: consolidamento shape-fn (spostare 4.6 shell/woven/embroid e 4.7 capped nel cap. 6, lasciando nel 4 una sezione-ponte "Oltre l'estrusione piena") — proposta sul tavolo, da confermare con Vincenzo prima dell'esecuzione perché è l'unico spostamento di contenuto pesante; traduzione EN di how-to-read e cap. 1; annotazione badge dei cap. 2-17; eventuale aggiornamento di §3.1/§3.2 a valle dello sweep.

- **2026-06-10 (ristrutturazione leggibilità, seguito: quinto lettore, badge concreti, brief shape-fn)** — *Tre avanzamenti dopo la review di Vincenzo su how-to-read.* **(1) Quinto percorso di lettura** aggiunto a `how-to-read.md`: "Vuoi capire se Ridley fa per te", il curioso che deve ancora decidere. Il suo percorso non è fatto di capitoli ma di esempi eseguibili: tour guidato di cinque esempi significativi (barchetta cap. 2, lampada traforata e cesto intrecciato cap. 6, portachiavi a parete cap. 8, testo in spirale cap. 13, blend SDF cap. 12), con Run e poi Edit come gesto chiave. Rafforzata anche l'apertura della pagina: gli esempi eseguibili dichiarati come la caratteristica distintiva del manuale ("leggere e basta è il modo peggiore di usare queste pagine"). Nota: il tour punta lampada e cesto al cap. 6, presupponendo lo spostamento shape-fn (vedi punto 3). **(2) Badge annotati su un caso concreto** perché Code sviluppi il rendering su materiale reale: cap. 4 con `level: base` a livello capitolo + `level: advanced` sulla sezione Chaining; cap. 6 con `level: advanced` a livello capitolo (il marker rende ridondante, e a regime sostituibile, il blockquote-cartello in testa al capitolo — per ora convivono). IT+EN speculari. **(3) Spostamento shape-fn approvato da Vincenzo ed esecuzione delegata a Code**: brief consegnato (`dev-docs/brief-shapefn-move.md`) con punti di taglio heading-based, sezione-ponte "Oltre l'estrusione piena" / "Beyond solid extrusion" fornita verbatim in entrambe le lingue (nuovo example-source `beyond-solid-extrusion`), ordine di inserimento nel cap. 6 (shell+capped prima di "Comporre", così vincolo-di-shell e caso-embroid diventano riferimenti all'indietro), lista degli adattamenti testuali (apertura shell, chiusura da rimuovere, "cap. 4.6"→"visto sopra", apertura thickness-fn, note d'autore) e sweep dei riferimenti stale `4.6`/`4.7`. A valle dell'esecuzione: aggiornare §3.1 (elenchi sezioni cap. 4 e 6) e verificare lo stato come da rituale.

- **2026-06-11 (badge implementati; rifiniture prosa; addendum al brief shape-fn)** — *Tre fatti di giornata.* **(1) Rendering dei badge di livello implementato e verificato da Code** (`draft_renderer.cljs`: pre-pass `replace-level-markers` con sentinella div prima dello strip dei commenti, post-pass DOM `apply-level-badges!` con aggancio al nearest-heading e regola primo-marker-vince; `style.css` con chip capitolo/sezione in tre tinte smorzate; lang propagato per le label localizzate; TOC e id-ancora non assorbono la label). Tutti e 5 i criteri di accettazione del brief passano nel runtime live; gli example-source convivono coi marker. Il paragrafo "I livelli" di how-to-read è quindi operativo; nota aggiornata. Conseguenza da decidere a valle dell'annotazione dei cap. 2-17: il blockquote-cartello del cap. 6 è ora ridondante col badge. **(2) Rifiniture su segnalazione di Vincenzo**: in how-to-read "capisci quando" → "capisci quanto" (percorso OpenSCAD); in 1.2 del cap. 1 "La sidebar raccoglie il resto" → "Le barre di contorno" (gli elementi sono distribuiti fra top bar e barra laterale; la prosa ora non li attribuisce a una barra precisa, e la distribuzione esatta è aggiunta alla lista di verifiche UI nella nota d'autore). **(3) Brief shape-fn: addendum 2026-06-11** con le risoluzioni alle osservazioni pre-esecuzione di Code: rinomina obbligatoria della sottosezione spostata (`Shell in composizione` / `Shell in composition`) con frase-ponte in chiusura verso "Comporre shape-fn" (testo fornito, fondibile a giudizio di Code se ridondante); fix della riga embroid nella nota d'autore del cap. 6; rimozione dell'hedge nella nota d'autore di how-to-read a spostamento avvenuto (solo IT); note d'autore dei file EN restano in italiano per convenzione; tono ponte/cartello invariato. Via libera all'esecuzione dato.

- **2026-06-12 (cap. 7: riparazione in coda, badge advanced; prima mossa dello sweep dedup)** — *Tre decisioni di Vincenzo dopo rilettura del cap. 7, tutte in linea con la diagnosi del 2026-06-10.* **(1) Il cap. 7 è advanced**: marker `level: advanced` aggiunto a livello capitolo, IT+EN (fatto). In how-to-read il percorso del neofita ora include il 7 nella lista "secondo giro" (6, 7, 11, 12); il percorso OpenSCAD già lo differiva ("il 7 e il 10 li apri quando ti servono"). **(2) La diagnostica/riparazione si sposta in coda al capitolo**: aprire il cap. 7 con mesh-diagnose, manifold e merge-vertices dava di Ridley un'idea di oggetto fragile, mentre le mesh non-manifold sono rare nell'uso reale (primitive, estrusioni, loft, revolve e booleane producono mesh corrette per costruzione). Nuovo assetto: la 7.1 resta "mesh come dato" e chiude con un paragrafo-mappa del capitolo; nasce la 7.7 "Diagnosi e riparazione" in coda, con apertura che parte dalla rarità e le tre sottosezioni spostate (Guardare dentro / Manifold e non-manifold / Riparare); la 7.2 definisce manifold inline al primo uso. Esecuzione delegata a Code: `dev-docs/brief-ch7-reorder.md`, con tutti i testi di raccordo forniti verbatim IT+EN e sweep dei riferimenti a "7.1". **(3) Prima mossa dello sweep dedup eseguita via brief**: la sezione "Generare shape da mesh" del cap. 3 (slice-mesh/project-mesh/face-shape con gli stessi esempi della 7.5, ciotola inclusa) si riduce a due paragrafi di cenno con rimando alla 7.5, heading invariato per le ancore; testo sostitutivo fornito verbatim IT+EN nello stesso brief, con clausola di salvaguardia per eventuale contenuto unico non presente nella 7.5 (slice-at-plane da verificare). Nota a margine: corretto nel cap. 7 IT il calco "facendo rivolvere un profilo" → "facendo ruotare un profilo attorno a un asse" (occorrenza unica, segnalata da Vincenzo; "rivoluzione" come sostantivo resta ovunque, è italiano tecnico corretto).

- **2026-06-12 (verifica post-esecuzione dei brief shape-fn e cap. 7)** — *Giro di verifica di Claude su entrambi i lavori di Code, esito: pulito con una integrazione.* Verificato su IT+EN (grep strutturali sui file): cap. 7 con 7.7 in coda (apertura dalla rarità + tre sottosezioni), 7.1 chiusa dal paragrafo-mappa, 7.2 con definizione manifold inline e rimando alla 7.7, zero residui "sezione 7.1"; cap. 3 con la sezione ridotta e ancore intatte, example-source duplicati rimossi (il conflitto d'id `project-mesh-silhouette` ch3↔ch7 si è risolto con la riduzione); cap. 4 con sezione-ponte fra "Risoluzione" e "Revolve" e nessuna traccia di shell/capped; cap. 6 con le due sezioni innestate prima di "Comporre", adattamenti a posto ("visto sopra", "Nella sezione su shell"); puntatori diagnostici di cap. 10 e 17 aggiornati a 7.7; hedge rimosso dalla nota di how-to-read; heading IT/EN speculari (conteggi identici sui quattro file). Buona anche la correzione autonoma di Code in 7.1 ("come un `(box 20)`", l'esempio era migrato). **Unica integrazione**: la frase-ponte in chiusura di "Shell in composizione" prevista dall'addendum non era stata aggiunta; inserita da Claude in IT+EN ("Il quadro generale della composizione, e il vincolo per cui `shell` chiude sempre la catena, è il tema della sezione \"Comporre shape-fn\" più avanti"). Aggiornato §3.1 alla struttura reale post-ristrutturazione: cap. 1 (1.3 "Eseguire: Run e REPL", niente paredit), cap. 3 (3.8 ridotta), cap. 4 (ponte 4.6, otto sezioni), cap. 6 (dodici sezioni con shell/capped innestate), cap. 7 (7.7 in coda). Resta da fare: render live degli esempi spostati (`mesh-diagnose-box`, `mesh-manifold-check`, `beyond-solid-extrusion`) — in carico a Vincenzo o Code nel panel — e le verifiche UI del cap. 1.

- **2026-06-12 (cap. 1 verificato; issue di sincronizzazione tracciata; badge fase 2)** — *Revisione interattiva di Vincenzo sul cap. 1: confermato con quattro correzioni, applicate.* "A un passaggio di mouse" → "a un click di mouse"; la REPL è "in basso a sinistra" (due occorrenze, 1.2 e 1.3); il source tracking si attiva con **Opzione (⌥)**, non Alt (Architecture.md dice "Alt", corretto nella prosa con nota per tastiere non Apple); "`(b 20)` o `(f -20)` indietro". Nota d'autore aggiornata: la checklist UI passa da "da verificare" a "verificata 2026-06-12", conservata come storico. **Issue scoperta durante la verifica e tracciata** in `dev-docs/code-issues.md`: il salto status bar → editor del source tracking non è sincronizzato col workspace di provenienza (mesh da Run di un esempio o da altro workspace → editor e viewport divergono silenziosamente); impatto minore ma mina la fiducia nella feature, candidato Roadmap perché tocca l'architettura viewport↔workspace. **Badge fase 2 decisa**: catalogazione completa dei capitoli (finora solo 1, 4, 6, 7: era il set di test, nessun default di rendering per gli altri) e chip di livello anche nel sommario delle guide. Brief consegnato (`dev-docs/brief-level-badges-2.md`) con la tabella dei livelli proposta da Claude (2,3,5,9,10,14,16,17 base; 8,13,15 intermediate; 11,12 advanced; about e how-to-read senza marker; unica deviazione di sezione: 10.3 find-faces intermediate) soggetta a veto di Vincenzo pre-lancio, e il vincolo di fonte unica di verità per il livello (estrazione dal marker consigliata, in alternativa `:level` in structure.cljs con rimozione dei marker di capitolo — scelta a Code, da comunicare perché cambia la procedura editoriale). **Emendamento alla tabella (stesso giorno, review di Vincenzo)**: il 10 sale a intermediate (bounds/ruler per tutti, ma il capitolo richiede più Clojure: predicati, mappe di risultato) e il marker di sezione sulla 10.3 decade perché ridondante; il 12 scende a intermediate (primitive, booleane e blend ricalcano l'equivalente mesh e blend è un'aggiunta preziosa anche per l'utente medio — argomento di Vincenzo, condiviso) con marker advanced sulla sottosezione "Formule custom" della 12.1, applicando il pattern del cap. 4: capitolo al livello della porta d'ingresso, coda difficile marcata a sezione. Tabella finale: base 2,3,5,9,14,16,17; intermediate 8,10,12,13,15; advanced 11. **Esecuzione verificata** (spot check di Claude): marker presenti (cap. 12: intermediate a livello capitolo, advanced su "Formule custom"), e fonte unica implementata da Code con l'opzione consigliata — `scripts/build_manual_levels.bb` genera `src/ridley/manual/manual_levels.cljs` (mappa id→livello, conforme alla tabella per tutti i 17 capitoli) dal primo marker di capitolo dei file sorgente. Procedura editoriale da ricordare: dopo ogni modifica ai marker di capitolo va rigenerato con `bb scripts/build_manual_levels.bb`, come per il reference_index.

- **2026-06-12 (traduzioni EN di cap. 1 e how-to-read)** — *Completata la copertura bilingue delle guide.* Tradotti `01-getting-started.md` e `how-to-read.md` in `docs/manual/guides/en/`, speculari agli IT correnti (incluse le quattro correzioni del cap. 1 verificate da Vincenzo e il tour a sei esempi del curioso). Scelte di traduzione: titoli di capitolo nella mappa presi verbatim dai `:title :en` di structure.cljs; titoli di sezione citati nei percorsi verificati sui file EN reali ("For those coming from a traditional CAD", "Profiles as values"); fasi della mappa rese Learn / Raw material / Build / Compose / Reuse / Understand / Polish / Finish; esempi del tour resi descrittivi (sailboat, hexagonal side table, perforated lamp, woven basket, wall key holder, text spiraling upward, shapes melting together); label dei livelli basic/intermediate/advanced come da renderer. Note d'autore in italiano anche nei file EN, per convenzione, con riga di provenienza della traduzione. `structure.cljs` aggiornato: `:langs #{:it :en}` per `:ch-howto` e `:ch-01`. Nessuna rigenerazione necessaria (manual_levels legge la lingua sorgente; reference non toccata). Con questo le guide sono integralmente bilingui: about + how-to-read + capitoli 1-17.

- **2026-06-12 (brief per lo sweep di deduplicazione finale)** — *Ultimo pezzo grosso del piano di leggibilità, consegnato a Code.* Ricognizione preliminare di Claude sulle zone interessate, con esito migliore del previsto: 10.3 e 8.5 hanno già il rimando alla casa e duplicano solo i dettagli; la coda Bezier della §4.2 è già un assaggio ma punta al cap. 5 invece che all'11; la 11.1 ri-insegna gli archi da zero con un esempio fotocopia (`arc-s-tube` ≡ `extrude-s-curve`); le 15.4-15.6 sono tre sezioni-segnaposto (con un "(se presente)" residuo nel testo). Brief `dev-docs/brief-dedup-sweep.md` con testi sostitutivi verbatim IT+EN: (A) 11.1 riscritta in apertura — niente re-introduzione, recap di una riga su assi/:steps/raccordo tangente, e ponte verso "Archi dentro path ed extrude" che resta il cuore della sezione; (B) puntatore Bezier di §4.2 → cap. 11; (C) §5.6 quick-path compattata al solo criterio di scelta (qp/poly-path/path); (D) §8.5 Side-trip con rimando a §5.4 al posto della ri-spiegazione, esempio di branching invariato; (E) §10.3 senza i blocchi :threshold/:where (riga di rimando alla 7.4), esempio e ottica-analisi invariati; (F) 15.4-15.6 collassate in "15.4 Dove trovare gli strumenti" con tabella a quattro righe (stamp→§3.2, follow-path→§5.2, misure→cap. 10, diagnosi→§7.7); (G) sweep dei riferimenti al vecchio titolo "(Librerie)" del cap. 9; (H) verifica REPL del winding di `scale -1` su mesh e shape con conferma/correzione della prosa §8.3 (voce storica dell'orizzonte, finalmente in carico). Esempi persi dichiarati nel brief: `arc-s-tube`, `quick-path-zigzag`, `stamp`, `path` (tutti duplicati delle rispettive case). Nessun marker di capitolo toccato, manual_levels invariato. Decisione collaterale ancora aperta: cartello-blockquote del cap. 6 vs badge.

- **2026-06-12 (about-ridley: sezione "Basato su Clojure" ampliata)** — *Su segnalazione di Vincenzo, la sezione era striminzita e non coglieva il vero valore della scelta di Clojure.* Decisione collaterale risolta nel frattempo: il cartello-blockquote del cap. 6 resta (dice *dove andare* e *quando tornare*, il badge dice solo il livello). Ampliamento IT+EN: ai due paragrafi esistenti (funzioni utente = comandi predefiniti, da `(box 20)` a `(la-mia-cornice)`) si aggiungono quattro paragrafi nell'ordine narrativo: (1) le funzioni come materiale da costruzione (shape-fn, thickness-fn, SDF custom: "il confine fra usare Ridley ed estenderlo non c'è"); (2) il poco rumore sintattico ("un modello si legge come una distinta di operazioni"); (3) i dati che si portano dietro la propria storia (mesh ispezionabili in REPL, i mark del path ripescabili dalla mesh anche dopo le booleane — il piggyback segnalato da Vincenzo); (4) in chiusura, l'eredità Lisp delle forme di controllo estensibili con `on-anchors` come esempio più riuscito ("per ogni punto con questo ruolo, costruisci qui questo pezzo"), posizionato per ultimo perché sfrutta il punto 3. Sezione passata da ~130 a ~300 parole, tono manifesto conservato. In parallelo Code sta eseguendo `brief-dedup-sweep.md`.

- **2026-06-12 (sweep di deduplicazione eseguito e verificato: il piano di leggibilità è completo)** — *Code ha eseguito `brief-dedup-sweep.md`, tutti gli 8 punti, IT+EN; spot check di Claude pulito (apertura 11.1 conforme, "Archi dentro path ed extrude" intatta, cap. 15 chiude sulla tabella 15.4 in entrambe le lingue).* Risoluzione dei tre punti segnalati da Code: **(1) voce H chiusa senza modifiche** — la prosa su `scale -1` non sta in §8.3 (che non fa affermazioni sul winding) ma nella scheda Reference `scale.md` e nell'esempio della vela del cap. 2, ed è corretta; verifica REPL di Code: un fattore negativo (riflessione, det<0) inverte il winding e la mesh resta watertight, due negativi (rotazione 180°, det>0) non lo invertono — conforme a `attachment.cljs:136` e alla scheda. Decisione: nessuna riga aggiunta a §8.3 (non è la casa naturale del tema, la scheda copre il caso; coerente con la preferenza per il manuale asciutto). La voce storica dell'orizzonte "verifica winding scale -1 in §8.3 EN" è chiusa: il riferimento a §8.3 era impreciso fin dall'origine. **(2)** Il cap. 15 non ha blocco note d'autore: il sotto-passo relativo era un no-op, ok. **(3) `stamp` → §3.2 confermato**: è introdotto in "Forme predefinite" (seconda sezione del cap. 3), come dice la stessa nota d'autore del capitolo; il "3.6 (se presente)" della vecchia prosa era il riferimento errato. — **Con questo si chiude il piano di leggibilità avviato con la diagnosi del 2026-06-10**: cap. 1 scritto e verificato, pagina how-to-read con mappa e cinque percorsi, guide integralmente bilingui, badge di livello su tutti i capitoli (pagina + sommario, fonte unica con rigenerazione bb), regola "una casa per argomento" applicata (shape-fn→cap. 6, riparazione→§7.7, sezioni-da-mesh→§7.5, archi→§4.2, bezier→§11.2, qp→§4.3, side-trip→§5.4, find-faces→§7.4, cap. 15 a tabella), sweep dei riferimenti stale (4.6/4.7, 7.1, "(Librerie)") puliti, e about-ridley con la sezione Clojure all'altezza del design. Restano fuori, per scelta: cap. 18 e Guide tematiche (parcheggi storici), issue sync viewport↔workspace (code-issues, candidato Roadmap).

---

## Storico revisioni

- **2026-05-14** — Prima stesura. Consolidamento di tutta la discussione di pianificazione fino alla discovery di Code.
- **2026-05-15** — Aggiunti capitoli "Modellare per primitive" (cap. 2), "Path: registrare il movimento" (cap. 5), sezione "Da funzioni matematiche a forme" (cap. 6), articolazione "Modificare le facce di una mesh" (7.4 → 6.4 nella versione corrente). Rinominato cap. 3 in "Lavorare con le forme 2D". Introdotta architettura a tre tracce (con Guide tematiche). Cap. 10 SDF arricchito con sezioni su risoluzione e auto-meshing, sdf-offset, sdf-ensure-mesh. Prima guida tematica: "Superfici parallele". Internals della Reference articolati in 7 sotto-categorie post-audit di Code (47% simboli SCI non in Spec). Criterio editoriale formalizzato per la classificazione dei simboli. Aggiunto in §11.2 il punto di contatto futuro con mesh→SDF.
- **2026-05-16** — Aggiunto capitolo "Analizzare e misurare" (cap. 9) per misurazione (distance, bounds, ruler), identificazione facce (find-faces come concetto autonomo), diagnostica mesh, AI describe (cenno) e XR (cenno). Rinominato cap. 14 ex-Debug in "Mettere a fuoco e risolvere i problemi", alleggerito (rimanda al cap. 9 per misurazione/ispezione). Tutti i capitoli successivi traslati di +1: ora 17 capitoli totali (era 16). AI tenuta deliberatamente sotto tono finché non maturerà. Aggiunto §3.0 "Visione d'insieme" con lo schema concettuale per fasi di lavoro (Imparare, Materia prima, Costruire, Comporre, Capire, Curare, Concludere, Estendere), da riprodurre nel manuale come prima pagina della traccia Guide narrative. Aggiunta §14 "Programma di lavoro" come stato condiviso tra chat: rituale di chat, convenzioni, lista task, backlog, quaderno delle decisioni emerse durante l'esecuzione. Source-of-truth del piano: Project di Claude (copia di lavoro viva), repo aggiornato periodicamente per visibilità a Code. Pianificazione formalmente conclusa, inizio fase di esecuzione: T-001 (brief Fronte 1 per Code) in corso, T-002 (scheda Reference di `loft` come modello) pianificato per il turno successivo.
- **2026-05-16 (T-002 chiuso)** — Scheda `loft.md` prodotta come modello canonico della Reference, salvata in `dev-docs/reference-manual/`. Cinque convenzioni acquisite in §14.5: monolingua per scheda, pattern "scheda madre + stub" per varianti di arity, modi secondari documentati senza etichetta peggiorativa, slug stabile in `category`, struttura cartella piatta + sottocartella `internals/`. Un punto pendente: naming per simboli con caratteri problematici. Inversione del source-of-truth del piano: il repo diventa la fonte canonica, il Project di Claude viene ricaricato a inizio sessione. §13.1 e §14.1 aggiornati di conseguenza. Convenzione `/docs/` (stabile) vs `/dev-docs/` (interno/temporaneo) dichiarata in §13.1.
- **2026-05-16 (T-001 chiuso)** — Code ha completato l'audit Spec↔codice (`docs/symbol-audit-report.md`: 107 simboli classificati — 30 A, 62 B, 14 C, 1 chiarito post-validazione come C) e il lint script Babashka (`scripts/spec_lint.bb` + `scripts/spec_allowlist.edn`: 432 simboli bound, 201 orfani, exit code 1). Validazione completata in chat. §6.8 rifattorizzato dopo l'audit: la sotto-categoria "Selection & visibility" è ora articolata in **Scene visibility** / **Picking & selection** / **Interactive testing** per gestire i 22 simboli emersi senza accorpamenti forzati. Sei voci aggiunte al quaderno §14.5 con i pattern di naming emersi (i `register-X!`/`get-X`/`show-X!`/`hide-X!`/`anim-*`/`vec3-*`/`*-anchors*` hanno destinazioni omogenee per famiglia). Backlog §14.4 aggiornato con la scheda Internals panoramica "Naming patterns in scene mutation" da scrivere come prima della sezione. Definito T-008 come task successivo: brief Fronte 2 per Code (aggiornamento Spec.md con i 30 A + 62 B, popolamento allowlist con i 14 C).
- **2026-05-16 (T-008 chiuso)** — Code ha integrato in `docs/Spec.md` i 30 simboli A nelle sezioni §2-§17 (con sei nuove sub: Predicates in §4 e §11, Edge analysis e Orientation utilities in §7, Vector math (3D) in §17, Import in §4 e §7) e ha aggiunto la nuova `## 19. Internals` con 62 simboli B distribuiti in 9 sotto-sezioni. `## 18. Not Yet Implemented` traslata a `## 20`, lasciando un gap di numerazione transitorio. `scripts/spec_allowlist.edn` popolato con 112 simboli plumbing in 9 famiglie commentate. `bb scripts/spec_lint.bb` esce con exit 0. Sei nuove voci aggiunte al quaderno §14.5: §16 lasciato invariato con import distribuito; `source-of` collocato in §19.8 con rimando da §19.4; `bindings.cljs` come check definitivo di "user-facing"; §19.3 a 12 simboli (non 11, §6.8 corretta); sub "Contracts" delle Internals resta vuota; gap di numerazione accettato come transitorio. Definito T-008b: consolidamento numerazione di Spec.md (chiusura del gap §17 → §19 → §20). Definite anche le convenzioni `dev-docs/manual-drafts/` per i draft narrativi e la decisione di lasciare `symbol-audit-report.md` dove sta.
- **2026-05-16 (T-006 in corso, 2.1 completata)** — Apertura del lavoro sul cap. 2 "Modellare per primitive". Stesura completata della sezione 2.1 "Le primitive" in `dev-docs/manual-drafts/02-modeling-with-primitives.md` (apertura del capitolo + sotto-sezioni cubo, cilindro, cono, sfera, spostare e ridimensionare). Acquisita la regolarità sistemica box/cyl/cone ↔ extrude: tutte le primitive con asse di sviluppo si estendono lungo l'avanti della turtle, sezione nel piano destra-alto. Verità accertata tramite diagnosi di Code dopo che due letture del codice da parte di Claude avevano prodotto interpretazioni sbagliate (esiste un wrapper `transform-mesh-to-turtle-upright` non immediatamente visibile in `primitives.cljs`). Aperto `dev-docs/code-issues.md` con quattro voci iniziali (docstring stale, inconsistenza turtle-X/X, Spec.md `[w d l]` per box). Cinque nuove voci nel quaderno §14.5: prove interattive come fonte di verità più affidabile della lettura del codice; regolarità sistemica box↔extrude; convenzione `code-issues.md`; convenzione nota interna in cima ai draft; nota disambiguazione `extrude-y`/`extrude-z` in §11.1. Prossimo passo: sezione 2.2 "Comporre".
- **2026-05-16 (T-008b chiuso, no-op)** — T-008b avrebbe dovuto chiudere il gap di numerazione transitorio §17 → §19 → §20 in Spec.md. Verifica di Code: il working tree era già consecutivo §17 → §18 (Internals) → §19 (Not Yet Implemented). T-008 era stato applicato direttamente alla numerazione finale, e la narrazione di T-008 (che parlava di "gap transitorio") descriveva uno stato intermedio che non si era mai manifestato su disco. Nessuna modifica necessaria: zero file modificati, lint exit 0. Esito di T-008 in §14.3 corretto in coerenza (la nuova sezione era `## 18. Internals`, non `## 19`; le sub sono `§18.x`). Voce §14.5 sul gap aggiornata da "accettato come transitorio" a chiarimento storico. Lezione: nei task correttivi, verificare lo stato osservato del deliverable prima di redigere il brief, perché la descrizione del piano può essere disallineata dalla realtà se l'osservazione iniziale era stata indiretta o presunta.
- **2026-05-17 (T-006, 2.2 completata)** — Sezione 2.2 "Comporre" del cap. 2 scritta in `dev-docs/manual-drafts/02-modeling-with-primitives.md`: quattro operazioni (union, difference, intersection, hull) ognuna con un esempio piccolo (croce/T, cubo forato, mezzo cilindro, basetta con bordi morbidi), più una barchetta a vela in chiusura che mette in cooperazione le quattro operazioni. Decisioni strutturali emerse: (a) la barchetta diventa sotto-sezione finale di 2.2 invece di sezione propria, numerazione del capitolo scorsa di -1 dalla 2.4 in poi; (b) `mesh-hull` aggiunta alla 2.2 come quarta operazione (non era nel sommario originale), presentata come strumento generativo distinto dalle tre booleane; (c) strategia esempi: codice inline nel draft taggato `<!-- example-source: ... -->` per trasferimento successivo in `structure.cljs`. Brief separato consegnato per il bug `scale` con determinante negativo (riflessione → mesh non-manifold). Aggiunta nuova voce in `docs/Roadmap.md` §1.5: "Wireframe della mesh sotto il cursore". Quattro voci aggiunte al quaderno §14.5. §3.1 e §3.2 aggiornati alla nuova struttura del cap. 2.
- **2026-05-17 (T-006, consolidamento pre-2.3)** — Consolidamento dei buchi lasciati durante la scrittura di 2.1 e 2.2 prima di proseguire con 2.3 in nuova chat. Tre interventi: (1) ricostruiti e popolati gli otto blocchi `example-source` mancanti del 2.1 (`primitive-cubo`, `primitive-parallelepipedo`, `primitive-cilindro`, `primitive-cono`, `primitive-sfera`, `primitive-attach`, `primitive-scale-uniforme`, `primitive-scale-non-uniforme`). (2) Adottata la convenzione di naming `r u f` per i parametri direzionali nelle signature di `box` e `rect` in Spec.md (decisione §14.5); aggiornato anche il paragrafo "Orientation" di §5 con la correzione di `cyl`/`cone` da "UP axis" a "forward (heading)". (3) Aggiornato `dev-docs/code-issues.md`: voce su Spec.md `box` chiusa, mantenute aperte le voci su `cyl-with-resolution`/`cone-with-resolution` docstring e su `turtle-X` vs `X` perché riguardano il codice sorgente. Stato T-006: 2.1 e 2.2 entrambe completate. Prossima sessione (nuova chat): 2.3 "Un portapenne".
- **2026-05-19 (T-006, sezioni 2.3–2.5)** — Stesura delle sezioni 2.3 (Un portapenne), 2.4 (Parametrizzare con def) e 2.5 (Pezzi riutilizzabili con defn) del cap. 2. Pulizia nomi: codice negli esempi rinominato in inglese (convenzione fissata in §14.5). Sezione 2.6 (Per chi viene da un CAD tradizionale) bloccata: dubbio sul comportamento pivot di `scale`/`rotate` su mesh (Spec dice world/centroid, comportamento osservato è creation-pose). Brief consegnato a Code (`dev-docs/brief-transform-pivot-audit.md`). Due voci aggiunte al quaderno §14.5.
- **2026-05-20 (consolidamento piano + stesura cap. 2.5b, 3, 4, 5)** — Stesura completa dei capitoli 3 (Lavorare con le forme 2D, 11 sezioni), 4 (Estrusione, 9 sezioni inclusi revolve e chaining), 5 (Path, 8 sezioni). Aggiunta sezione 2.5b "Tante primitive alla volta" al cap. 2. Consolidamento del piano: inserito nuovo cap. 9 "Librerie" dopo cap. 8, rinumerati tutti i capitoli da 9 in poi (ora 18 capitoli). Aggiornata mappa concettuale §3.0 (aggiunta fase "Riusare"). Aggiornato §3.1 (sommario) e §3.2 (note) con la struttura effettiva dei capitoli scritti. Decisioni consolidate: cap. 3 apre con motivazione (3.1), stamp in 3.2, profili come valori in 3.4, 3.7 mappa completa consumatori; cap. 4 include revolve (asse = up, Spec corretta) e chaining (extrude+, revolve+, transform->); cap. 5 include with-path/goto/path-to nella 5.8; cap. 7 rimossi inset-face/scale-face (non esistono); cap. 9 librerie: dettagli tecnici in cap. 18 o Internals; loft+ segnata in Roadmap §1.5. Reference: 152 schede in 4 batch (2D shapes, generative-operations, core, path+mesh). Correzioni Spec: asse revolve, refuso square→rect. Schede mancanti note: goto, look-at.
- **2026-06-02 (consolidamento stato + definizione v1)** — Messo a verbale lo stato reale dopo diverse sessioni di stesura non loggate qui: draft narrativi cap. 2-17 più `about-ridley` completi e rivisti dall'autore, esempi inline a posto salvo rifiniture, incidente cap. 7 risolto. Decisione architetturale: gli esempi restano inline nel Markdown dentro commenti `example-source` ed eseguiti dal renderer, niente trasferimento a `structure.cljs` e niente shortcode `{{example: id}}` (§2.3, §2.4, §7.1, §13.1 aggiornati; supera la strategia esempi del 2026-05-16). Definita la v1 del manuale: switch da `content.cljs` + Reference consultabile con search interna (Brief B, T-007); i due link verso le schede diventano fast-follow post-switch (T-009). Parcheggiati fuori dalla v1: galleria, guide tematiche, cap. 18, rifiniture; traduzione dopo a prosa congelata. Aggiornati §13.1, §14.3 (T-006 chiuso, milestone draft cap. 3-17, T-007 ridefinito come v1, nuovo T-009), §14.4, §14.5.
- **2026-06-02 (chiusura §10 + migrazione contenuti)** — Chiusi o rinviati i nove punti §10 (nuova §10.2); T-003/004/005 chiusi, T-007 sbloccato. Frontmatter zero per le guide; fallback multilingua bidirezionale; flag esempi `:no-run` e `:warning slow` documentati. Migrazione: audit `Manuale_it.md` vs draft completato; galleria preservata in `docs/examples/gallery/` (sei file), `T` + ispezione turtle aggiunti al cap. 15.2, `bloft` confermato rimosso dal DSL. Spec/Architecture/piano verificati già puliti da `bloft` sul disco (project_knowledge stale). Aggiornati §10.2 (nuova), §14.3, §14.4, §14.5.
- **2026-06 (implementazione v1 + T-009)** — Brief B scritto (`dev-docs/brief-manual-infrastructure.md`) e v1 implementata. Backbone (commit `1897cfd`, `e028190`): `structure.cljs` come nav unica (slug senza `NN-`), contenuti migrati in `docs/manual/guides/it/` e `docs/manual/reference/en/`, `draft_renderer` agganciato a `structure.cljs`, `reference_index.cljs` rigenerato dalla nuova posizione, `package.json`/`build_reference_index.bb`/`.gitignore` aggiornati. UI (commit `3ee8c1b`): `reference_browser.cljs` con browser per categoria + search interna + scheda completa (riuso `draft_renderer`, Clojure core compatto, Internals nascosta), fallback bilingue metadata-driven (robusto vs SPA/Tauri 200), fix bug Edit con modale WKWebView-safe, cutover dietro `config/new-manual?` (`goog-define` + pin `:release`). T-009 (commit `4788f49`): `open-card!` + cross-link tooltip-editor→scheda e prosa/See-also→scheda, più tooltip Reference negli esempi (anti-clipping via `document.body`). Decisione di scope emersa: schede Reference renderizzate **per intero** a runtime via il pipeline delle guide (non solo i metadati dell'indice) — l'indice resta la fonte di search/tooltip, il dettaglio fa fetch del `.md`. `Architecture.md` §11.6 riscritto allo stato attuale. Resta: rimozione di `content.cljs` a cutover consolidato, traduzioni complete, schede `internals/`.
- **2026-06 (auto-linking prosa, T-010)** — Auto-link a render time dei code span in backtick che combaciano con un simbolo dell'indice (brief `dev-docs/brief-autolink-reference.md`), estensione di T-009. Solo dentro i backtick, match esatto, risoluzione per nome, niente doppio wrapping. Rimossi i 17 `ref:` manuali di calibrazione dal cap. 4 (`docs/manual/guides/it/04-extrusion.md`), l'auto-link resta l'unico meccanismo. Aperto in backlog: estendere l'auto-link ai simboli Clojure core.
- **2026-06-05 (Workspaces nel manuale)** — Documentata la feature Workspaces e la sua integrazione con le librerie attive nel cap. 9, rinominato "Workspaces e Librerie". Nuova sezione 9.1 (documenti multipli, switch/rename/duplicate/close, pallino dirty desktop, Save/Save As/Open, Edit-su-esempio in nuovo ws; integrazione: ogni ws ricorda le librerie attive, librerie come asset condiviso, switch/eredità, elenco salvato in header e ripristinato alla riapertura). Sezioni librerie scalate a 9.2–6; nota per-workspace nella 9.3. Rimossa "Librerie come workpad". File-backed leggero/desktop, pallino assente sul web. Refusi cap. 3 corretti (`find-faces` (cap. 9→10), rimando import SVG). Modifiche speculari IT+EN; titolo aggiornato in `structure.cljs`. Limite transitorio dei file pre-header tenuto fuori dal manuale. §14.5 e sommario §3.0/§3.1/§3.2 aggiornati.
- **2026-06-10 (cap. 11 capstone + anticipazioni)** — Aggiunta la §11.3 "Lo stesso profilo, cinque modi" al cap. 11 (capstone bezier: stesso spigolo in cinque modi su uno spettro matematica→interattività; conteggio a tre livelli), con il "limite del loft" rinumerato a §11.4. Integrato l'esempio `examples/spigolo-quattro-modi.clj` (Code, verificato da Vincenzo) con helper `wall` auto-misurante (`add-mark :apex` + righello `:center→:apex` via forma `:at`); un example-source eseguibile autosufficiente più un `:no-run` interattivo; register `supporto`→`corner`. Micro-aggiunte a §11.2: `bezier-as :control`, path-first di `bezier-to-anchor`, `:tension-end`. Anticipate le due API nuove di sessione (`add-mark`, `ruler`/`distance :at`, già in Spec e schede da Code) nei cap. 5 (§5.3 + tabella §5.8) e 10 (§10.1, §10.2) con rimando a §11.3, per non introdurle a sorpresa nel capstone. Modifiche speculari IT+EN; `structure.cljs` invariato (sezione interna a pagina già bilingue). Due voci aggiunte al quaderno §14.5. Lavoro fuori dal flusso T-task.
- **2026-06-10 (cap. 11, rifinitura 4-bis)** — Aggiunto il 4-bis a fine Way 4 (`edit-bezier` sulla metà mirando a `:D`/apice, gemello del modo 4 come il modo 3 lo è del 2) e adottato il refactor di Code: `diag` estratto come scaffold nominato con `:D` dotato di heading (+45°, tangente nell'apice), `wall` lo antepone via `follow-path`. Confronto ed esempio del modo 3 aggiornati; due `:no-run` autosufficienti (`cinque-modi-edit`, `cinque-modi-bis-edit`). Allinea alla revisione di `examples/spigolo-quattro-modi.clj`. IT+EN speculari.