# Brief — Badge di livello, fase 2: annotazione completa dei capitoli e chip nel sommario

**Per:** Code
**Da:** Vincenzo + Claude
**Data:** 2026-06-12
**Contesto:** il rendering dei marker `level` è implementato e verificato (brief `brief-level-badges.md`). Finora sono annotati solo quattro capitoli (1 base; 4 base + Chaining advanced; 6 advanced; 7 advanced): era il set di test. Decisione di Vincenzo: catalogare tutti i capitoli, e mostrare il livello anche nel sommario delle guide, così la complessità si vede prima di aprire un capitolo. Nota semantica: non esiste un default di rendering (capitolo senza marker = nessun chip); dopo questa annotazione ogni capitolo è esplicito, e per le sezioni resta l'ereditarietà dal capitolo.

## Parte 1 — Annotazione dei capitoli rimanenti

Inserire il marker `<!-- level: … -->` su riga propria subito dopo il titolo `#`, **IT e EN** (riga identica nelle due lingue). Capitoli e livelli:

| Cap. | Livello | Razionale breve |
|---|---|---|
| 2 Modellare per primitive | `base` | porta d'ingresso |
| 3 Lavorare con le forme 2D | `base` | secondo gradino del percorso neofita |
| 5 Path | `base` | concetto centrale, presentazione piana |
| 8 Assemblaggio | `intermediate` | marks/skeleton richiedono 2-5 |
| 9 Workspaces e Librerie | `base` | operativo, nessun prerequisito concettuale |
| 10 Analizzare e misurare | `intermediate` | bounds/ruler sono per tutti, ma il capitolo richiede qualche conoscenza in più di Clojure (predicati, mappe di risultato) |
| 11 Curve avanzate | `advanced` | lo dice il titolo; §11.3 è un saggio |
| 12 SDF | `intermediate` | primitive, booleane e blend ricalcano l'equivalente mesh e sono alla portata di tutti; la coda difficile è marcata a livello di sezione |
| 13 Testo | `intermediate` | text-shape è facile, rilievi e text-on-path no |
| 14 Colore e materiali | `base` | cosmetico e lineare |
| 15 Mettere a fuoco | `intermediate` | presuppone tweak/pilot e un modello su cui indagare |
| 16 Clojure per Ridley | `base` | è scritto per il principiante |
| 17 Esportare e stampare | `base` | tutti ci passano |

`about-ridley` e `how-to-read` restano senza marker: sono pagine fuori percorso, il chip non avrebbe senso.

**Unico marker di sezione aggiuntivo**: nel cap. 12, sottosezione `### Formule custom` (dentro la 12.1): `<!-- level: advanced -->` sotto l'heading, IT+EN (heading EN da verificare sul file, atteso `### Custom formulas`). Le altre deviazioni di sezione si aggiungeranno strada facendo durante lo sweep editoriale, non in questo brief.

I livelli sono proposte editoriali di Claude: Vincenzo può correggere la tabella prima del lancio; in esecuzione fa fede la tabella così come la trovi qui.

## Parte 2 — Chip di livello nel sommario delle guide

Requisito: nell'indice delle guide (sidebar/TOC del manuale), accanto al titolo di ogni capitolo compare lo stesso chip di livello della pagina, stessa palette e stesse label localizzate (`base/intermedio/avanzato`, `basic/intermediate/advanced`). Capitoli senza livello (about, how-to-read): nessun chip, nessun placeholder.

Vincolo architetturale: **una sola fonte di verità per il livello di capitolo**, che oggi è il marker nel `.md`. Due strade accettabili, scelta a tuo giudizio:

1. **Estrazione dal marker** (consigliata): il livello di capitolo viene letto dal primo marker del file, o al caricamento della struttura o in uno step di generazione tipo `reference_index` (lo script Babashka esistente è il pattern: un piccolo `manual_levels` generato analogamente andrebbe benissimo, con l'avvertenza che come il reference_index va rigenerato dopo gli edit). I marker restano dove sono.
2. **Spostare la fonte in `structure.cljs`** (campo `:level` per entry): in tal caso i marker chapter-level nei `.md` vanno **rimossi** (i quattro esistenti inclusi) e il chip in pagina deve derivare dal campo, altrimenti restiamo con due fonti che divergono. I marker di sezione restano comunque nei `.md`.

Se scegli la 2, segnala in chat: cambia la procedura editoriale di annotazione (structure.cljs invece dei file), e va detto a Claude per i futuri sweep.

## Accettazione

1. Tutti i capitoli 1-17 mostrano il chip in pagina, IT e EN, coi livelli della tabella (o della tabella corretta da Vincenzo).
2. Il sommario mostra il chip accanto a ogni capitolo 1-17; about e how-to-read senza chip.
3. La sottosezione `Formule custom` / `Custom formulas` del cap. 12 mostra il chip `avanzato`/`advanced` di sezione.
4. Una sola fonte di verità per capitolo (verificabile: cambiando il livello in quella fonte, pagina e sommario cambiano insieme).
5. Lingua dei chip coerente con la lingua dell'interfaccia/pagina.
