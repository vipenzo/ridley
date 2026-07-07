# Brief — Recording ad alto livello, lettura 2D: l'ultimo lettore di `:pure` (gate della Fase 3)

## Contesto

Prosecuzione di `dev-docs/high-level-recording-accertamento.md`. Questo è il brief annunciato nella decisione di scope della Fase 2a («Solo bake 2D, lettura invariata») e annotato in Architecture §6.3.1 come gate: finché la lettura 2D consuma micro+rider, `:pure`/`:span` non si possono rimuovere e la Fase 3 resta bloccata. È il gemello 2D della Parte 2 della Fase 2a (`seed->nodes-3d` è il modello, già atterrato e provato): **refactor a semantica invariata** salvo un miglioramento enumerato sotto.

**Stato attuale verificato sui sorgenti (2026-07-07), tre funzioni in `src/ridley/editor/edit_path.cljs`:**

- `project-2d-to-xy` (~1092): normalizza un path `:2d` in comandi piano-(a,b) per il seed 2D. Opera su `path-micro-commands`; droppa il seed `(th -90)` del macro `path-2d`; rinomina `tv`→`th`, `arc-v`→`arc-h`; proietta le coordinate 3D con `[x y z]→[-y z]`, **inclusi i rider `:pure`**; contiene l'hack documentato del `move-to`: i rider portano coordinate in frame-origine (il recorder non applica `move-to` registrando), quindi vengono offsettati a mano dall'anchor; la conversione dei side-trip ricorre ancora sui micro del sotto-path.
- `group-curve-runs` (~942): ri-collassa i run tessellati — bezier via `:pure`/`:span`, archi via la finestra `:arc-cap :lead…:trail` — in pseudo-comandi (`:bezier-node`, `:arc-run`) per il seed.
- `seed->nodes` 2D (~991): costruisce i nodi dai comandi raggruppati; per gli archi ricava raggio/sweep **ricostruendoli dal run tessellato**.

**Il disegno della migrazione** (specchia la 2a; la complicazione segnalata da Code — «le coordinate alto livello sono local al frame d'ingresso, la proiezione oggi riceve world già risolte» — si risolve consumando le pose da chi già le traccia, non reimplementando):

- `seed->nodes` 2D diventa un interprete diretto sui comandi alto livello con il **pose walk già stabilito da `seed->nodes-3d`** (reduce che threada `{pos heading up}`, decodifica `:c1/:c2/:end` con `turtle/local->world` sulla posa d'ingresso, avanza il frame d'uscita con `canonical-bezier-frame` a t=1), seguito dalla proiezione sul piano. Nessun tracker di posa parallelo: gli stessi helper del 3D, più la proiezione.
- Un `:bezier-to` alto livello È un nodo bezier; un `:arc-h`/`:arc-v` alto livello È un nodo arco con raggio e sweep letti **dagli args, esatti**. `group-curve-runs` muore come `group-arc-runs-3d` è morto nella 2a.
- L'interprete threada `:move-to` nella posa: l'hack dell'offset sparisce da solo (le coordinate alto livello sono relative al frame d'ingresso del segmento, non all'origine).
- `project-2d-to-xy` si riduce alla normalizzazione di specie (drop del seed `(th -90)`, rinomine `tv`→`th`/`arc-v`→`arc-h`) sui comandi alto livello; la conversione dei side-trip mappa su `(:commands sub)` (come il fix di coda della 2a per la serializzazione).

**Miglioramento enumerato, non silenzioso:** i nodi arco riaperti recuperano parametri esatti dagli args invece di ricostruirli dalla tessellazione. I valori nei nodi possono differire dagli attuali entro l'errore di ricostruzione: è una correzione, va dichiarata nel commit, e il round-trip bake→confirm→riapertura deve risultare un punto fisso *migliore* (esatto), mai peggiore.

**Alternative considerate e rigettate:**

- *Riscrivere il tracciamento di posa dentro `project-2d-to-xy`* — duplica ciò che il walk del 3D già fa; la storia di questo filone (tre leggi di propagazione dell'up) insegna cosa succede con i walk paralleli.
- *Tenere `:pure` solo per il 2D e rimuovere il resto in Fase 3* — lascerebbe il tag emesso e letto per un solo consumer, cioè lo status quo con più asterischi; il gate esiste per essere chiuso.

## Lavoro richiesto

1. Riscrivere `seed->nodes` 2D come interprete diretto sui comandi alto livello, con il pose walk del 3D + proiezione piano-(a,b); `:move-to` threadato nella posa; nodi arco dagli args esatti; `:mark`/`:side-trip` in tail come oggi.
2. Ridurre `project-2d-to-xy` alla normalizzazione di specie su alto livello; side-trip convertiti mappando su `(:commands sub)`.
3. Rimuovere `group-curve-runs` e ogni lettura di `:pure`/`:span`/finestre `:arc-cap` nel percorso di lettura 2D — codice morto via, come da metodo, decidendo leggendo.
4. Un micro-comando di curva in un seed 2D è un errore forte (come nel 3D post-2a): dopo la Fase 1 non esistono più recording micro, nessun fallback silenzioso.
5. Architecture §6.3.1: `:pure`/`:span` passano a «senza lettori, rimozione in Fase 3» (il gate è chiuso); la riga `:arc-cap` aggiorna il lettore residuo (resta lo stamping del cap in extrusion, non più la lettura editor).
6. Aggiornare l'«Esito» in `high-level-recording-accertamento.md`: gate chiuso, Fase 3 sbloccata.

## Verifica

- Rete prima del codice: se i round-trip test 2D non esistono, vanno scritti **prima** contro il comportamento attuale (corpus: dritti, archi, bezier, `move-to` in testa, side-trip con curva, mark) e devono restare verdi dopo — salvo i valori dei nodi arco, il cui delta è l'unico enumerato e va asserito come *migliorato* (confronto con i parametri esatti dell'arg).
- Il caso `move-to` + bezier in `path-2d` — quello che oggi vive dell'hack dell'offset — round-trippa correttamente senza hack.
- Punto fisso: aprire un seed 2D, bakearlo senza toccarlo, confermare, riaprire → stessi nodi (tolleranza fp).
- Grep di uscita, che è il senso del brief: **zero** letture di `:pure`/`:span` in tutto `src/` (la whitelist dell'editor si svuota); `path-micro-commands` nell'editor resta solo dove serve davvero il micro (se da grep non resta nulla, meglio).
- Golden corpus e pinned intatti (si cambia solo lettura); suite completa 0 failures; commit proprio, verde.

## Fuori scope

- La rimozione effettiva di `:pure`/`:span`/`:veer-deg` dall'emissione e l'aggiornamento dei golden (è la Fase 3, che questo brief sblocca).
- La 2c (resolution di consumo) e lo stamping del cap.
- La semantica 2D preesistente: `corr0`, logica di flip, formato della specie `:2d` — invariati.
- Il buco `anim`/`span` e la voce STL (code-issues, indipendenti).
