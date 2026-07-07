# Brief — Recording ad alto livello, Fase 2a: follow ed editor sullo schema del recorder

## Contesto

Prosecuzione di `dev-docs/high-level-recording-accertamento.md` (Fase 1 atterrata e accettata, vedi «Esito Fase 1»). Questo brief attua il primo item d'apertura della Fase 2, con un'aggiunta emersa dalla verifica dei fatti: la parte follow.

**Stato attuale verificato sui sorgenti (2026-07-06):**

- `rec-follow*` (`src/ridley/editor/macros.cljs` ~37) splice-a `(path-micro-commands sub-path)` — i **micro-comandi già tessellati** del sotto-path — dentro il `:recording` del path esterno. I th/tv/tr/f relativi compongono correttamente da qualunque posa; i rider `:pure` no: restano congelati nel frame del sotto-path (voce in `dev-docs/code-issues.md` con riproduzione REPL, 2026-07-06). Conseguenza: dentro un path con `follow` convivono comandi alto livello e micro splice-ati — la forma doppia, dentro un singolo `:recording`.
- Il bake dell'editor 3D (`nodes->commands-3d`, `src/ridley/editor/edit_path.cljs` ~763) emette ancora la forma args-based pre-Fase-1: `{:cmd :bezier-to :args [end c1 c2 :local]}`, `{:cmd :set-heading :args […]}` — la seconda metà della forma doppia, quella che il sed-incident di Fase 1 ha rivelato.
- `seed->nodes-3d` (~586) fa un giro assurdo reso obsoleto dalla Fase 1: chiama `path-micro-commands` (abbassa l'alto livello a micro) e poi ri-collassa i run bezier leggendo i rider `:pure`/`:span` (~886) — ricostruendo faticosamente l'informazione che il `:recording` ora contiene già in chiaro.
- `cmd->code` per i side-trip (~356, e ~544 nel 2D) serializza il sotto-path espandendone i **micro**: un side-trip con una curva dentro viene confermato nel sorgente come muro di th/tv/f, perdendo la semantica della curva nel testo.
- Il valore `live` pre-confirm di una sessione 3D porta i comandi args-based: non consumabile da extrude/loft (voce dedicata in `code-issues.md`, ~19).

**Cosa ottiene questo brief:** una sola forma di comandi in tutto il sistema — quella del recorder (`{:cmd :bezier-to :c1 :c2 :end :steps}`, coordinate nel frame d'ingresso). Muoiono insieme: la forma doppia, il difetto `:pure`/follow (alla radice, non a valle), il giro micro→`:pure`→nodi dell'editor, i muri di micro nel sorgente confermato, e la non-consumabilità del valore pre-confirm. È la cucitura editor↔eval — il filone aperto dalla domanda «perché l'ultimo punto della bezier non coincide con la fine dell'estrusione» — chiusa per costruzione invece che per sincronizzazione.

**Alternative considerate e rigettate:**

- *Fare solo l'editor e lasciare follow ai micro* — l'editor dovrebbe mantenere per sempre il percorso di lettura `:pure`/`:span` come fallback per i micro splice-ati: la forma doppia sopravvivrebbe dentro i seed, che è esattamente ciò che si voleva eliminare.
- *Ri-esprimere i rider `:pure` al momento dello splice* (il «fix possibile» annotato in code-issues) — cura il sintomo tenendo in vita sia lo splice micro sia il rider; con lo splice alto livello il rider non serve proprio, per il principio che i risarcimenti si eliminano, non si perfezionano.

## Lavoro richiesto

### Parte 1 — `follow` splice-a alto livello

1. `rec-follow*` splice-a `(:commands sub-path)` — i comandi alto livello — invece dei micro. Le coordinate sono nel frame d'ingresso del segmento (pose-free per costruzione, requisito 2 della Fase 1), quindi compongono da qualunque posa senza rider da aggiustare. La turtle locale del recorder avanza come oggi (applicando il lowering dello splice), così la posa a valle resta identica.
2. **Impatto atteso sui golden, enumerato:** nel caso `follow` del corpus (c8b) i micro di movimento/rotazione devono restare **identici** (gli angoli relativi di una curva local-frame sono pose-invarianti — è il trap test della parte 1), mentre i rider `:pure` cambiano nei valori: ora corretti nel frame del path esterno. Aggiornamento del golden deliberato, limitato ai rider di quel caso, con motivazione nel commit. Qualunque differenza nei micro di movimento è una regressione.
3. La voce code-issues sul rider congelato si chiude con test di regressione: il repro REPL della voce (re-edit di `outer` dopo `(th 90) (follow sub)`) mette l'handle nel punto giusto.

### Parte 2 — l'editor parla lo schema del recorder

4. `nodes->commands-3d` emette lo schema alto livello: `{:cmd :bezier-to :c1 v :c2 v :end v :steps n}` nel frame d'ingresso del segmento per i nodi bezier; segmenti dritti come oggi (`set-heading`/`f` sono già primitive). Gli `:steps` si risolvono alla resolution corrente al momento del bake — lo stesso valore che produrrebbe la rivalutazione del testo confermato, così valore pre-confirm e post-confirm coincidono.
5. `seed->nodes-3d` legge i comandi alto livello direttamente da `(:commands seed-path)`: un comando curva = un nodo bezier, niente `path-micro-commands`, niente `:pure`/`:span`. Dopo la parte 1 non esistono più micro splice-ati nei recording, quindi nessun fallback: un micro-comando di curva in un seed è un errore forte, non un caso da gestire.
6. Serializzazione a testo (confirm e `cmd->code` per side-trip, 3D e 2D): dai comandi alto livello — un `bezier-to` resta `(bezier-to [end] [c1] [c2] :local)` nel sorgente, non un muro di micro. La forma testuale utente non cambia.
7. Il valore `live` pre-confirm porta i comandi alto livello: consumabile da extrude/loft via l'accessor. Chiude la voce code-issues «valore live non consumabile», con test.
8. Bake 2D (`nodes->commands`): stesso allineamento di forma per i comandi curva, **nessun cambio semantico 2D** (il `corr0` e il frame 2D restano come sono). Se l'allineamento 2D rivela complicazioni proprie, si spezza in brief separato invece di forzarlo qui — segnalare, non improvvisare.
9. `:pure`/`:span` perdono qui il loro ultimo lettore. **Non rimuoverli dal lowering in questo brief**: i golden di Fase 1 li asseriscono e la rimozione è Fase 3 (aggiornamento golden e Architecture §6.3.1 contestuali). Annotare in Architecture §6.3.1 solo lo stato: «senza lettori dal 2026-07, rimozione in Fase 3».

## Verifica

- Rete prima del codice; test nuovi che falliscono sul codice attuale dove applicabile (repro `:pure`/follow, pre-confirm).
- **Parte 1:** golden c8b — micro di movimento/rotazione identici, soli rider `:pure` cambiati (diff del golden ispezionata a mano, non rigenerata alla cieca); tutti gli altri casi del corpus intatti; repro della voce code-issues verde.
- **Parte 2, punto fisso del round-trip:** aprire un seed del corpus in edit-path, bakearlo senza toccare nulla, confermare → sorgente equivalente e geometria identica (tolleranza fp di Fase 1); riaprire → stessi nodi. Su path a più bezier concatenati ad alta curvatura, fine della mesh coincidente con l'ultimo nodo a resolution 4, 16, 64 (il test del frame canonico resta il canarino della cucitura).
- Pre-confirm: `(extrude (circle 5) P)` con sessione aperta non confermata produce la stessa mesh del post-confirm.
- Side-trip con curva dentro: il sorgente confermato contiene `(bezier-to …)`, non micro; rivalutato, produce la stessa geometria.
- Suite completa 0 failures; pinned intatte (nessuna geometria cambia in questo brief — cambiano rappresentazioni e rider, mai mesh).
- Grep di guardia: nell'editor nessun residuo di lettura `:pure`/`:span` né di emissione args-based.

## Fuori scope

- Guard rail-start analitico sul primo segmento alto livello (item 2 della Fase 2, brief successivo — dopo questo diventa una riga di confronto su c1).
- Resolution di consumo (item 3) e rimozione dei tag (Fase 3), inclusi `:pure`/`:span` ora orfani.
- Il buco `anim`/`span` (code-issues, competenza animazione) e il `corr0` 2D.
- Qualunque cambio alla forma testuale del DSL o all'API utente.
