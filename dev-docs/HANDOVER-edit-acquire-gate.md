# Handover — gate ingegneristico `edit-acquire` (chat lunga → nuova chat)

Aggiornato 2026-07-22. Questo documento basta a riprendere il lavoro senza
rileggere la chat precedente. Leggi anche, nell'ordine: `dev-docs/brief-param-acq-v1.md`
(il brief che ha originato questo lavoro — Parti P0-P5, coda) e
`dev-docs/HANDOVER-acquisizione-parametrica.md` (il canale foto→geometria,
capitolo precedente, chiuso).

---

## In una riga

Il **gate ingegneristico** del brief (prototipo `edit-acquire`: la
"manipolazione invertita" — dalla foto 2 in poi il gesto che sembra ruotare il
pezzo in realtà muove la camera) è **SUPERATO**, 2026-07-22, verdetto di
Vincenzo dopo 6 giri di bug-fixing dal vivo: "quasi sufficienza" — il
meccanismo regge, la fatica residua è quasi certamente la **simmetria del
proxy** (un parallelepipedo è invariante sotto 4 rotazioni — stesso "gemello
di Klein" già noto per il solver automatico), non il meccanismo della camera
invertita. **TUTTO NON COMMITTATO** — vedi "Stato git" sotto.

## Cosa esiste

`(edit-acquire proxy-mesh session-dir)` — sessione modale (famiglia
modal-evaluator, come `edit-mesh-split`) che apre un pannello con pellicola
di foto numerate:

- **Foto 0**: gizmo normale, muove il proxy — l'utente lo allinea alla foto,
  fissando la sua posa canonica.
- **Foto 1..N-1**: il proxy resta congelato; il gizmo (visivamente sullo
  stesso punto) applica il delta del trascinamento **invertito** alla camera
  invece che al proxy. La posa iniziale di ogni foto è pre-seminata ruotando
  la camera della foto 0 attorno all'asse verticale, dell'angolo θ del
  giradischi (da `session.json`).
- Focale (mm) tarabile con uno slider **solo sulla foto 0** (poi disattivato)
  — converte in FOV orizzontale via `photogrammetry.camera/focal-mm->fov-deg`
  e ridimensiona lo sfondo-foto (agganciato alla camera, riempie il frustum).
- `[`/`]` cambia foto, Escape/"Chiudi" esce **senza salvare nulla** — non
  c'è ancora un primitivo canonico da emettere (quello è P4, non iniziato).

`session-dir` deve essere un **path assoluto** a una cartella con
`session.json` (vedi `test-assets/param-acq-box-tape/`), letta via il server
Rust `geo_server` — quindi serve l'app desktop **installata** aperta in
background (anche senza usarla, basta che sia in esecuzione) mentre si lavora
nel browser Chrome su `localhost:9000` per lo sviluppo/REPL.

Comando di prova usato con Vincenzo:
```clojure
(edit-acquire (box 60.2 20.2 40.1) "/Users/vipenzo/Progetti/Ridley/test-assets/param-acq-box-tape")
```

## I sei bug trovati e corretti dal vivo (2026-07-21/22)

Tutti riprodotti e diagnosticati SOLO tramite le descrizioni di Vincenzo — il
REPL headless di questa chat non è mai riuscito a mantenere una connessione
browser stabile per abbastanza tempo da verificare visivamente (vedi
"Limite ambientale" sotto). Utile leggerli in ordine: ognuno ha smascherato il
successivo.

1. **Feedback loop camera↔raycasting**: una prima versione spostava la
   camera *live*, dentro `:on-drag`, ad ogni pixel di trascinamento — ma il
   gizmo stesso proietta il mouse nello spazio 3D attraverso la camera
   corrente, quindi muoverla a metà calcolo la rendeva instabile
   ("posizioni casuali"). **Fix**: `:nudge-mesh? true` anche sulle foto
   invertite (nudge della sola anteprima, come la foto 0), l'inversione si
   applica **una sola volta**, su `:on-commit`, a camera ferma.
2. **Controlli orbit riattivati dal gizmo**: il gizmo, a fine di *ogni*
   trascinamento, chiama incondizionatamente
   `viewport/set-controls-enabled! true` — corretto per l'editing normale,
   ma qui basta un fotogramma con i controlli attivi perché il loro
   `.update()` interno sovrascriva la posa appena impostata (traslazioni
   "sempre respinte", rotazioni "con una logica incomprensibile"). **Fix**:
   `viewport/register-frame-callback!` che forza `set-controls-enabled!
   false` a ogni fotogramma per tutta la sessione.
3. **Segno invertito nel pre-seed del giradischi**: `seed-camera-pose`
   ruotava la camera di **+θ** invece di **-θ** — fisicamente è l'oggetto a
   girare sul piatto (camera reale ferma), qui è il contrario (camera
   virtuale che gira, oggetto fermo), quindi il verso va invertito. Senza
   il fix, l'errore di posa iniziale cresceva con l'angolo (sembrava
   "casuale" tra una foto e l'altra).
4. **Vista della foto 0 ricalcolata ad ogni modifica**: prima legata alla
   base h/r/u del proxy (per evitare anelli di rotazione di taglio, vedi
   punto 6) — ma ricalcolarla *comunque*, a qualunque timing, la rendeva
   instabile: tornare sulla foto 0 o seminare un'altra foto dopo ulteriori
   modifiche atterrava su un'inquadratura diversa da quella appena vista.
   **Fix definitivo**: `camera-poses[0]` si calcola **una sola volta**, al
   primo ingresso in sessione, e non si tocca mai più (né su re-entry né sui
   commit). Le altre foto vengono invalidate **solo se il pivot si sposta**
   (traslazione sulla foto 0), non per le rotazioni (che non muovono
   `:creation-pose :position`).
5. **Focale mai convertita**: il campo "Focale (mm)" passava il numero
   direttamente come se fosse la FOV orizzontale in gradi — mai chiamata
   `focal-mm->fov-deg`, funzione scritta ma mai usata. Il default (52) era
   anche sbagliato rispetto al valore nominale già noto nel progetto per
   questa fotocamera (48mm-eq, da `HANDOVER-acquisizione-parametrica.md`).
6. **Vista iniziale degenere**: la prima vista guardava esattamente lungo
   uno degli assi del box di default, mettendo la camera di taglio rispetto
   a 2 dei 3 anelli di rotazione del gizmo (appaiono come segmenti,
   impossibili da afferrare). **Fix**: direzione di vista fissa
   `normalize([1 -1 1])` — diagonale rispetto a tutti e 3 gli assi.

Aggiunto anche un **puntino di riferimento** (sempre visibile, anche dentro/
dietro il box) al centro del proxy — Vincenzo aveva segnalato la mancanza di
un punto di riferimento visivo.

## Bug APERTO, non correlato a edit-acquire: `mesh-union`

Scoperto per caso testando un proxy con marcatori asimmetrici (per l'ultimo
esperimento, vedi sotto): `(mesh-union (box ...) (translate (box 8) ...) ...)`
su 3 box (1 grande + 2 marcatori) risulta in **24 vertici** — esattamente
8+8+8, cioè i pezzi vengono **giustapposti**, non fusi in un solido booleano
vero (un'unione reale avrebbe un conteggio diverso, dipendente
dall'intersezione). Il bounding box risultante È corretto (si estende
esattamente dove dovrebbe per i marcatori), quindi la matematica delle
posizioni è giusta — il problema è nella funzione `mesh-union`/
`manifold/union` stessa, o in come viene invocata in un `let` (magari
un'operazione che normalmente è asincrona via Manifold/WASM e qui va gestita
diversamente). **Non investigato oltre** — Vincenzo ha scelto di procedere
sul gate senza risolverlo, perché fuori scope. Da riprendere se serve un vero
CSG dentro un `let` fuori da uno script/REPL normale.

## Limite ambientale di questa chat (per la prossima)

`scripts/dev-browser.sh` (headless Chrome + CDP) non è mai riuscito a
mantenere una connessione stabile al relay di shadow-cljs abbastanza a lungo
da eseguire verifiche — il client si registra a intermittenza (a volte 0
runtime browser su `(count (:clients ...))`, nonostante la pagina carichi
correttamente titolo/DOM). Ogni fix in questa sessione è stato verificato
**solo per derivazione matematica a mano** (in un caso, tre derivazioni
indipendenti dello stesso conto) e poi confermato o smentito dal test dal
vivo di Vincenzo — mai da me in autonomia. Se la prossima chat ha lo stesso
problema, conviene chiedere a Vincenzo un controllo via REPL con output
testuale (come fatto per `mesh-union` sopra) invece di insistere con
`dev-browser.sh`.

## File

| File | Stato | Cosa |
|---|---|---|
| `src/ridley/editor/edit_acquire.cljs` | nuovo | la sessione: stato, loader `session.json`, gizmo normale/invertito, filmstrip, pannello |
| `src/ridley/editor/acquire_backdrop.cljs` | nuovo | piano-foto agganciato alla camera, dimensionato sul frustum; `set-hfov!` per ricalibrare senza ricaricare la foto |
| `src/ridley/math.cljs` | +`pose-around-axis` | ruota una posa turtle attorno a un pivot ESTERNO (non in-place come `turtle/f`/`th`/`tv`) |
| `src/ridley/photogrammetry/camera.cljs` | +`focal-mm->fov-deg` | mirror di `intrinsics-from-fov` già presente |
| `src/ridley/viewport/core.cljs` | +`get-camera`, +`set-camera-fov!` | `set-camera-fov!` prende FOV **verticale** (nativa di THREE), non orizzontale |
| `src/ridley/editor/bindings.cljs` | +voce | simbolo SCI `edit-acquire` → `edit-acquire/enter!` |
| `test/ridley/math_test.cljs` | nuovo | 3 test per `pose-around-axis` (713 test totali, verde) |
| `dev-docs/brief-param-acq-v1.md` | nuovo (non mio) | il brief originale, fonte del gate |

## Stato git — NON COMMITTATO

Nessun commit fatto in questa chat (solo con permesso esplicito, mai dato).
`git status` mostra tutti i file sopra come modificati/nuovi, più
`CLAUDE.md` (modificato PRIMA di questa chat, non da questo lavoro — la
sezione "Comunicare con Vincenzo", vedi handover precedente). **Chiedere a
Vincenzo se vuole il commit prima di continuare a modificare altro.**

## Prossimi passi (dal brief, `dev-docs/brief-param-acq-v1.md`)

Il gate è superato → si passa a **P1** (viewport: FOV parametrica — già
in parte fatta qui, andrà probabilmente estesa a lettura EXIF vera invece
del campo manuale — camera lock/unlock — già fatto — foto come sfondo in
posa — già fatto, riusabile). Poi **P2** (sessione vera e propria:
`edit-acquire` esiste già come nome/struttura ma senza edge-snap, senza
priori θ oltre al seed rigido attuale, senza file di sessione persistito).
La simmetria del proxy (limite emerso dal gate) va tenuta a mente per P2/P3:
serve probabilmente uno strumento di aggancio ai bordi (`s`, già previsto)
o un modo per il proxy di portare indizi di orientamento — non ancora
progettato.

## Decisioni prese in questa chat (non ri-litigare)

- `edit-acquire` è una **funzione**, non una macro: nessun marker nel
  sorgente, nessun commit-to-buffer. Va bene finché non c'è un formato di
  emissione (P4) — quando arriva, probabilmente serve un wrapper macro
  sopra questa stessa sessione, non riscriverla da capo.
- La camera per la foto 0 si calcola **una sola volta** e non si tocca più
  (punto 4 sopra) — qualunque tentativo di "tenerla aggiornata" reintroduce
  l'instabilità. Se in futuro serve che segua il proxy quando si trasla di
  molto, va ripensato da zero (es. ricentrare SOLO su richiesta esplicita
  dell'utente, mai automaticamente).
- Il puntino di riferimento e il default FOV (48mm) sono scelte pragmatiche
  per il gate, non necessariamente quelle giuste per P1/P2 finale.
