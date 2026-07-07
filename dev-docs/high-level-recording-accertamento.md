# Accertamento — Recording ad alto livello: censimento dei consumer e piano di fattibilità

Data: 2026-07-06. Stato: censimento statico completato (grep sui sorgenti); le domande aperte in fondo richiedono verifica di Code prima di qualunque brief di migrazione.

## La domanda

Il recorder oggi tessella le curve a record time: `(bezier-to …)` entra nel `:recording` come decine di micro-comandi `th`/`tv`/`tr`/`f`, e l'informazione analitica originale viene ricostruita a valle tramite tag (`:smooth`, `:arc-cap`, `:bez-cap`, `:veer-deg`, `:pure` — vedi Architecture §6.3.1). Questo contraddice il principio dichiarato in §6.3 ("il path è comandi, non campioni; il sample rate vive nel consumer") ed è la radice di tre classi di difetti recenti: falsi positivi risoluzione-dipendenti del guard rail-start, desync di frame editor↔eval, incoerenze corner tra emettitori (archi, bezier-as).

L'ipotesi da accertare: registrare le curve **ad alto livello** (`{:cmd :bezier-to :c1 … :c2 … :end …}`, `{:cmd :arc-h :args […]}`) e spostare la tessellazione in una funzione di lowering condivisa chiamata dal consumer alla sua resolution. Se regge, i tag di §6.3.1 diventano rimovibili uno a uno.

## Censimento dei consumer di `(:commands path)` (verificato sui sorgenti, 2026-07-06)

**`src/ridley/turtle/core.cljs`:**
- `run-path` (~1463) — esecuzione diretta dei comandi su una turtle.
- `rec-play-path` (~1405) — replay di un path dentro un altro recording (path annidati / side-trip).
- `path-segments` (~1494) — raggruppa rotazioni+movimenti in segmenti; base di varie analisi.
- `path-total-length` (~1756), `add-mark` (~1782), `sample-path-at-distance` (~1832), `rail-fraction` (~1929) — API di misura e campionamento esposte all'utente.

**`src/ridley/turtle/extrusion.cljs`:** `extrude-from-path` (~1733), `extrude-with-holes-from-path` (~1583), `extrude-closed-from-path` (~1982), `is-simple-forward-path?` (~1544), `resolve-2d-source-anchors` (~68). Più i lettori di tag: corner machinery (`corner-rotation?` ~1270), `split-leading-cap` + `validate-rail-start-frame!` (~292/~337).

**`src/ridley/turtle/loft.cljs`:** `loft-from-path` (~579).

**`src/ridley/turtle/shape.cljs`:** `path-to-2d-waypoints` (~567), `path-to-3d-waypoints` (~1176), `replay-path-to-recording` (~923), `path-has-mark?` (~426), `ensure-path-2d` (~1209), `fit` (~1529).

**`src/ridley/editor/macros.cljs` (il recorder stesso come consumer):** replay di path annidati nel recording (~97), `rec-bezier-as*` che legge i comandi dello skeleton (~282), un lettore 2D (~815).

**`src/ridley/editor/edit_path.cljs`:** `seed->nodes-3d` / seed 2D (~588, ~974, ~1071) — ricostruzione nodi dal recording, oggi via `:pure`/`:span`; `cmd->code` per side-trip (~351, ~539).

**Non consumano `:commands`:** `text.cljs`, `attachment.cljs`, `transform.cljs`, `api.cljs`, `env.cljs`, `path.cljs` (l'attach replay passa dal recorder, non legge path stored direttamente).

Nota: `walk-path-poses`, citata in Architecture §6.3 come residente in loft.cljs, non risulta più lì — verificare dove vive oggi e aggiornare il riferimento nel documento.

## Lo schema proposto (bozza da discutere, non decisione)

Il `:recording` contiene comandi al livello a cui l'utente li ha scritti:

- movimenti e rotazioni come oggi: `{:cmd :f :args [d]}`, `{:cmd :th :args [a]}`, `{:cmd :set-heading …}`, `{:cmd :mark :args [k]}`;
- curve come dato analitico: `{:cmd :bezier-to :c1 v :c2 v :end v}` (coordinate world a record time, come l'attuale `:pure`), `{:cmd :arc-h :args [r sweep]}`, `{:cmd :arc-v :args [r sweep]}`;
- `bezier-as` si risolve a record time nella sequenza di `:bezier-to` fittati (il fitting resta a record time, la tessellazione no).

Una funzione condivisa di **lowering** (`lower-commands`: comandi alto livello + resolution → micro-comandi identici all'output attuale del recorder, tag inclusi) è l'adapter di compatibilità. Il frame resta ancorato a `canonical-bezier-frame-impl` come oggi: il lowering la campiona, esattamente come fa ora il recorder.

Semantica che la struttura assorbe: una rotazione nel recording è dell'utente per definizione (niente `:smooth`); il guard rail-start diventa un confronto analitico su c1 del primo segmento (niente `:bez-cap`/`:veer-deg`/finestre); l'editor legge il segmento com'è (niente `:pure`); corner = rotazione esplicita tra segmenti (niente incoerenza archi/bezier-as).

## Strategia di migrazione (a fasi, ogni fase green)

1. **Fase 0 (questo accertamento):** rispondere alle domande aperte sotto.
2. **Fase 1:** recording alto livello + lowering pigro all'ingresso di ogni consumer. Criterio: output **byte-identico** all'attuale su tutta la suite e sulle pinned — nessun cambiamento osservabile.
3. **Fase 2:** migrare i consumer uno alla volta a leggere i segmenti alto livello, partendo da quelli che oggi pagano i tag: guard rail-start, editor (`seed->nodes-3d`), i tre punti di consumo rail.
4. **Fase 3:** rimuovere ogni tag quando muore il suo ultimo lettore; aggiornare Architecture §6.3/§6.3.1.

## Falsificazione

L'ipotesi è falsificata (o ridimensionata) se emerge un consumer per cui il lowering pigro non può riprodurre l'attuale byte-per-byte — il candidato tipico è chi dipende da **indici o conteggi dei micro-comandi** invece che dalla geometria. Sospetti da verificare esplicitamente: `add-mark`/`sample-path-at-distance` (camminano i micro-passi?), lo `:span` di edit-path (conteggio comandi del run), il replay annidato in `rec-play-path` (il path annidato si registra micro o alto livello?), `path-segments`. La falsificazione su uno di questi non uccide l'idea: la sposta ("quel consumer migra per primo invece che per ultimo"), ma va scoperta adesso, non a metà del guado.

## Domande aperte (per Code, prima di ogni brief)

1. Qualcosa **persiste** `:commands` fuori dal processo (registry/abstract, undo, sync WebRTC, export)? Se sì, serve una politica di versione del formato; se no — come sembra, dato che la fonte di verità è il sorgente Clojure — la migrazione è puramente in-memory.
2. `add-mark`, `sample-path-at-distance`, `rail-fraction`, `path-total-length`: misurano sui micro-passi o su geometria ricostruita? Con quale sensibilità alla resolution oggi?
3. Dove vive oggi `walk-path-poses` e chi la chiama?
4. Il lettore 2D in macros (~815) e `replay-path-to-recording` in shape: cosa assumono della granularità dei comandi?
5. Stima di Fase 1 dopo le risposte: la richiesta è un numero per consumer, non un numero totale.

## Risposte alle domande aperte (verificato sui sorgenti, 2026-07-06)

**1. Persistenza di `:commands` fuori dal processo — NO, migrazione puramente in-memory.**
Verificati tutti e cinque i candidati:
- Libreria (`library/storage.cljs:182-208`, `panel.cljs`): salva `{:name :requires :source}` — sorgente DSL testuale, ri-valutato al load. Nessun riferimento a `:commands`/`:recording`.
- Undo/redo: tre editor modali, tutti in-memory (`edit_path.cljs:1562-1585` snapshot di `{:nodes :selected}`, non `:commands` grezzi; `pilot_mode.cljs:176-183` un proprio DSL ad alto livello; `codemirror.cljs:1481-1486` undo di testo nativo CodeMirror). Nessuna scrittura su disco/localStorage.
- WebRTC sync (`sync/peer.cljs:232-250`, chiamato da `core.cljs:1163-1184`): trasmette il testo sorgente dell'editor (`cm/get-value`), non `:commands`.
- Export (`export/stl.cljs`, `threemf.cljs`, `library/stl.cljs`, `library/svg.cljs`): opera solo su `:vertices`/`:faces` già tessellati, mai su `:commands`.
- Workspace (`workspace/store.cljs:139-144`): persiste `:content` (sorgente testuale) su localStorage/file, mai `:commands`.

Conclusione: nessun artefatto persistito congela la forma attuale di `:commands`. Nessuna politica di versione necessaria — la migrazione è puramente in-memory.

**2. `add-mark`/`sample-path-at-distance`/`rail-fraction`/`path-total-length`/`path-segments` — TUTTE resolution-dependent, ma con rischio molto diverso.**
- `path-segments` (1489-1507): **rischio alto, strutturale**. Chiude un nuovo segmento a ogni `:cmd :f` incontrato — il *numero* di segmenti è letteralmente il numero di comandi `:f`. Una bezier tessellata a 8 passi produce 8 segmenti, a 32 passi ne produce 32; i consumer a valle (`bezier-as`, `compute-path-waypoints`) rifittano per segmento, quindi l'output cambia con la risoluzione. Questo è l'unico vero "conteggio di micro-comandi" tra i cinque.
- `sample-path-at-distance` (1811-1900) e `add-mark` (1765-1809): **rischio medio**. Accumulano su `:args` (non su indici) ma interpolano *linearmente sulla corda* del micro-`:f` che contiene il target — non sulla curva analitica. Convergono al punto geometrico vero solo al crescere della risoluzione; a bassa risoluzione la posizione risultante è quella della corda, non della curva.
- `path-total-length` (1755-1763): **rischio basso**. Somma pura di `|args|` su `:f`/`:u`/`:rt`/`:lt` — è un'approssimazione poligonale monotona crescente verso la lunghezza d'arco vera al crescere della risoluzione, ma non identica a nessuna risoluzione finita.
- `rail-fraction` (1921-1940): **rischio più basso**, rapporto tra due somme convergenti (numeratore e denominatore condividono lo stesso bias, che tende a cancellarsi).

Punto chiave per la Fase 1: se `lower-commands` usa **la stessa identica logica/risoluzione di tessellazione che il recorder usa oggi**, questi cinque consumer ricevono lo stesso identico vettore di micro-comandi di oggi — output byte-identico automatico, **zero modifiche a queste funzioni in Fase 1**. Il rischio sopra descritto riguarda solo una futura Fase 2/3 in cui si tentasse di farle leggere direttamente i segmenti alto livello.

**3. `walk-path-poses` — non esiste più, rimossa il 28 maggio 2026.**
Introdotta in `23fa647` come helper privato di `bloft` in `loft.cljs`, rimossa in `075900c` ("remove bloft definitively: stress battery shows loft + seam-weld is enough") insieme a `bloft` e ai suoi helper (391 righe). Non è stata "rinominata": il suo ruolo (camminare le pose di un path per stamparle) è stato reinlineato per-consumer:
- `loft-from-path` (`loft.cljs:560`) via `analyze-loft-path` (424) + loop inline con `apply-rotation-to-state`.
- `extrude-from-path` (`extrusion.cljs:1718`) via `analyze-open-path-dir` (1434) + loop inline (~1763).

Azione dovuta: `docs/Architecture.md:510,613` cita ancora `walk-path-poses` — va corretta per puntare a questi due loop.

**4. Il "lettore 2D" in macros (~815) e `replay-path-to-recording` in shape.cljs — trattamento opposto.**
- La riga ~815 di `macros.cljs` è dentro la macro `path-2d` (811-841): non legge mai `:commands`, è puro symbol-aliasing a expand-time (`th`→`tv`, `arc-h`→`arc-v`, `rt`→`u`) che poi delega ai `rec-*` esistenti. **Tollera comandi alto livello opachi banalmente**, perché non ispeziona affatto la granularità.
- Nota collaterale: `run-path` (885-892) nello stesso file fa `case` diretto su `:f`/`:th`/`:tv`/`:tr` con default `nil` — un `:bezier-to` opaco sparirebbe silenziosamente senza un `lower-commands` a monte.
- `replay-path-to-recording` (`shape.cljs`, 900-923): fa `case` diretto su `:f`/`:th`/`:set-heading` (default: comando scartato silenziosamente), e usa la tessellazione fine per ricostruire un poligono 2D via `rec-f`/`rec-th`. **Si romperebbe silenziosamente** con comandi opachi (nessun errore, solo geometria mancante) — serve `lower-commands` inserito prima del `reduce`.

**5. Stima Fase 1 per consumer** (recording alto livello + lowering pigro all'ingresso; criterio: byte-identico):

*Zero modifiche necessarie (se `lower-commands` replica esattamente la tessellazione odierna):*
- `path-segments`, `path-total-length`, `add-mark`, `sample-path-at-distance`, `rail-fraction` (turtle/core.cljs) — ricevono lo stesso vettore di micro-comandi di oggi.
- `path-2d` in macros.cljs — non legge `:commands`, non tocca la modifica.

*Modifica triviale — un'unica chiamata a `lower-commands` inserita all'ingresso (≈15-30 min l'una, sono "interpreti generici" con default-skip su `:cmd` sconosciuti):*
- `run-path`, `rec-play-path` (turtle/core.cljs)
- `extrude-from-path`, `extrude-with-holes-from-path`, `extrude-closed-from-path`, `is-simple-forward-path?`, `resolve-2d-source-anchors` (turtle/extrusion.cljs)
- `loft-from-path` (turtle/loft.cljs)
- `path-to-2d-waypoints`, `path-to-3d-waypoints`, `replay-path-to-recording`, `path-has-mark?`, `ensure-path-2d`, `fit` (turtle/shape.cljs)
- replay di path annidati in macros.cljs (~97)

*Modifica triviale ma da verificare con test mirato (lettori di tag — la Fase 1 non li tocca semanticamente, ma sono i primi candidati di Fase 2):*
- `corner-rotation?`, `split-leading-cap`, `validate-rail-start-frame!` (turtle/extrusion.cljs)
- `seed->nodes-3d`/seed 2D, `cmd->code` (editor/edit_path.cljs)
- `rec-bezier-as*` (editor/macros.cljs ~282) — legge i comandi dello skeleton, verificare che `lower-commands` produca uno skeleton compatibile

*Non-consumer, solo doc da correggere:*
- `walk-path-poses` in Architecture.md §6.3/§6.3.1 — non esiste più, va ri-puntata a `analyze-loft-path`/`analyze-open-path-dir`.

Nessuna falsificazione trovata: tutti i "sospetti" (add-mark, sample-path-at-distance, path-segments, span di edit-path) risultano gestibili in Fase 1 perché operano sul vettore di micro-comandi tessellato, che `lower-commands` riproduce identico. Il rischio reale è confinato alla Fase 2 (quando questi stessi consumer venissero fatti leggere i segmenti alto livello direttamente) e riguarda soprattutto `path-segments`, l'unico a contare i comandi anziché sommarne la geometria.

## Verifica delle risposte e punti residui (Claude, 2026-07-06)

Le cinque risposte sono verificate e il quadro regge: nessuna persistenza di `:commands`, nessuna falsificazione strutturale, stime piccole. I riferimenti stale a `walk-path-poses` in Architecture (§6.3 e §6.4) sono già stati corretti. Il trap test sulle risposte fa però emergere tre punti che il piano deve incorporare prima del brief di Fase 1.

**1. La risoluzione va catturata a record time, o il byte-identico è falso.** L'argomento «se `lower-commands` replica la tessellazione odierna l'output è byte-identico» ha un buco temporale: oggi la tessellazione avviene alla resolution in vigore *quando il path viene definito*; il lowering pigro avverrebbe alla resolution in vigore *quando il path viene consumato*. Con `(resolution 8) (def P (path (bezier-to …))) (resolution 64) (extrude … P)` i due momenti divergono e la Fase 1 cambierebbe il comportamento osservabile. Quindi: in Fase 1 il comando curva (o il path) porta con sé la resolution di record time e il lowering usa quella — byte-identico vero. Il passaggio alla resolution del consumer è la *destinazione* del redesign («il sample rate vive nel consumer»), ma è un cambio semantico deliberato di Fase 2, da documentare come tale, non un effetto collaterale silenzioso di Fase 1.

**2. Le coordinate delle curve devono essere relative al frame d'ingresso del segmento, non world.** La bozza dello schema («coordinate world a record time, come l'attuale `:pure`») rompe una proprietà che i micro-comandi hanno gratis: sono tutti *relativi* (angoli e distanze), quindi un path stored replayato da qualunque posa di consumo si compone correttamente. Un `:bezier-to` con c1/c2/end world funzionerebbe solo dalla posa di registrazione. Correzione alla bozza: control point ed endpoint espressi nel frame d'ingresso del segmento (semantica `:local`), così il comando alto livello resta pose-free come i micro-comandi che sostituisce. (`:pure` se la cava con le world solo perché l'editor lavora nella posa di registrazione — non è un precedente valido per il replay generale.)

**3. I default-skip silenziosi diventano errori in Fase 1.** Code ha trovato due interpreti che scartano silenziosamente i comandi sconosciuti: `run-path` in macros (~885, `case` con default `nil`) e `replay-path-to-recording` in shape (~900, comando scartato). Con comandi alto livello in circolazione, un punto di inserimento di `lower-commands` dimenticato produrrebbe geometria mancante senza errore — la violazione peggiore del principio «errori leggibili, mai normalizzazione silenziosa». Requisito di Fase 1: quei default diventano throw con messaggio esplicito («unknown command :bezier-to — missing lower-commands?»), così ogni consumer non migrato fallisce forte al primo incontro.

Con questi tre punti incorporati, la Fase 0 è chiusa e si può scrivere il brief di Fase 1.

## Esito Fase 1 (2026-07-06) — atterrata e accettata

Implementata e verificata: recorder ad alto livello (`:bezier-to` con `:c1/:c2/:end` nel frame d'ingresso + `:steps` risolti a record time, `:arc-h/:arc-v`, `:bezier-as :segments`), `lower-commands` puro in turtle/core come trasloco verbatim dell'emissione, accessor unico `path-micro-commands` (memoizzato via delay `:micro-commands`) su ~25 siti, throw sui default-skip di `run-path` e `replay-path-to-recording`. Suite 423/0/0, golden corpus completo (tre modi bezier-as, resolution cambiata tra def e consumo), nessuna pinned aggiornata.

**Deviazione enumerata dal byte-identico stretto:** rumore in virgola mobile dal roundtrip world↔local nei valori numerici (il golden confronta i numeri con tolleranza assoluta 1e-6; struttura, conteggi, tag e keyword restano a uguaglianza esatta). Unica deviazione ammessa; qualunque altra è regressione.

**Difetti preesistenti emersi durante la verifica, loggati in `dev-docs/code-issues.md` (non materia di Fase 1, comportamento identico prima/dopo):**
- il rider `:pure` di un bezier splice-ato da `follow` resta congelato nel frame del sotto-path: un re-edit del path esterno metterebbe l'handle nel punto sbagliato. Nota di piano: la Fase 2 lo **sussume** — quando `seed->nodes` leggerà i comandi alto livello (già nel frame d'ingresso del segmento) il rider sparisce e il difetto con lui; non merita un fix standalone, la voce in code-issues rimanda qui;
- `anim-parse-cmd` lascia passare un path intero dentro `span` e `preprocess.cljs` lo scarta in silenzio (dispatch su `:type` mai combaciante): stesso pattern dei default-skip curati al punto 4, ma di competenza del sistema di animazione — resta in code-issues, indipendente da questo piano.

**Item d'apertura della Fase 2** (in ordine suggerito):
1. Il bake dell'editor emette lo stesso schema alto livello del recorder — elimina la doppia forma di comandi emersa col sed-incident (recorder `:c1/:c2/:end` vs bake args-based) e sussume il difetto `:pure`/follow. È di nuovo la cucitura editor↔eval, il posto giusto da cui partire.
2. Guard rail-start analitico diretto sul primo segmento alto livello (via le finestre, i cap tag e la misura a corda).
3. Migrazione per-consumer alla lettura dei segmenti + passaggio alla resolution di consumo, come cambio semantico documentato (il punto 1 della verifica sopra smette di valere per scelta, non per sbaglio).

La Fase 3 (rimozione dei tag, aggiornamento Architecture §6.3.1) resta a valle: ogni tag cade quando muore il suo ultimo lettore.

## Cosa NON è in discussione

L'invariante rail-start (semantica del DSL: un rail eredita la posa di consumo), la distinzione corner/liscio nell'estrusione, il threading dello stato turtle tra segmenti, l'API utente (`bezier-to`, `arc-h`, `path`, …): tutto invariato. Cambia solo la rappresentazione interna del recording e il momento della tessellazione.
