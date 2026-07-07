# Brief — Recording ad alto livello, Fase 1: rappresentazione nuova, comportamento identico

## Contesto

Questo brief attua la Fase 1 del piano in `dev-docs/high-level-recording-accertamento.md` (censimento completo, risposte di Code e verifica con i tre punti residui: leggerlo prima di iniziare — questo brief non rideriva, punta). In sintesi: oggi il recorder tessella le curve a record time e l'informazione analitica viene ricostruita a valle con i tag di Architecture §6.3.1; la Fase 1 sposta la rappresentazione del `:recording` ad alto livello e introduce una funzione di lowering condivisa, **senza alcun cambiamento osservabile**. Il criterio di accettazione è byte-identico: stessi micro-comandi, stessi tag, stesse mesh, stesse pinned.

La Fase 1 è deliberatamente solo rappresentazione: i benefici semantici (guard analitico senza finestre, editor senza `:pure`, resolution del consumer) sono Fase 2 e restano fuori. Separare i due passi permette di verificare il cambio di rappresentazione in isolamento.

**Alternative considerate e rigettate:**

- *Registrare doppio (micro come oggi + comando alto livello come rider)* — mantiene due fonti di verità nello stesso vettore, non elimina mai i tag, e ogni nuovo emettitore dovrebbe tenere le due forme coerenti: è il debito attuale, raddoppiato.
- *Saltare direttamente alla Fase 2 (resolution del consumer)* — mescolerebbe un cambio di rappresentazione con un cambio semantico osservabile, rendendo impossibile attribuire una regressione all'uno o all'altro. Prima il byte-identico, poi la semantica.

## Lavoro richiesto

### 1. Schema del recording alto livello

Il `:recording` contiene i comandi al livello a cui l'utente li ha scritti:

- movimenti, rotazioni, mark, set-heading: **invariati** (`{:cmd :f :args [d]}`, `{:cmd :th :args [a]}`, `{:cmd :tv …}`, `{:cmd :tr …}`, `{:cmd :u/:rt/:lt …}`, `{:cmd :set-heading …}`, `{:cmd :mark :args [k]}`);
- curve: `{:cmd :bezier-to :c1 v :c2 v :end v :steps n}` e `{:cmd :arc-h/:arc-v :args […] :steps n}` — con due vincoli dai punti 1 e 2 della verifica dell'accertamento:
  - **coordinate nel frame d'ingresso del segmento** (semantica `:local`): il comando deve restare pose-free come i micro-comandi che sostituisce, replayabile da qualunque posa di consumo. Le world coords stile `:pure` sono esplicitamente escluse;
  - **step di tessellazione risolti a record time** (`:steps`, calcolati dalla resolution in vigore al momento della registrazione, incluso il modo `:s` che dipende dalla lunghezza): il lowering usa questi, mai la resolution corrente. È ciò che rende vero il byte-identico quando la resolution cambia tra `def` e consumo. Il passaggio alla resolution del consumer è un cambio semantico deliberato di Fase 2;
- `bezier-as` si risolve a record time nella sequenza di `:bezier-to` fittati (il fitting resta a record time); il ramo auto di `bezier-to-anchor` idem nel suo bezier equivalente.

### 2. `lower-commands` e la turtle del recorder

Una funzione pura (posizione suggerita: accanto a `canonical-bezier-frame-impl` in `src/ridley/turtle/bezier.cljs` o modulo dedicato) che trasforma la lista alto livello nei micro-comandi **identici all'output attuale del recorder, tag inclusi** (`:smooth`, `:arc-cap :lead/:trail`, `:bez-cap :lead`/`:veer-deg`, `:pure`/`:span`). Non è una reimplementazione: è il **trasloco** dell'attuale logica di emissione di `rec-bezier-to*`/`rec-bezier-as*`/`rec-bezier-to-anchor*`/arc (macros.cljs) in funzione pura. Il recorder delle curve si riduce a: registra il comando alto livello e avanza la propria turtle locale **applicando il lowering appena registrato**, così la posa a valle (e quindi ogni comando successivo) resta identica a oggi.

Nota di composizione: se `brief-bezier-as-rail-lead.md` non è ancora atterrato quando questo lavoro parte, i due si compongono — chi atterra secondo modifica il punto dove a quel momento vive l'emissione (il recorder o `lower-commands`). Coordinarsi sull'ordine, non duplicare.

### 3. Accessor unico al posto dell'accesso diretto

I consumer non chiamano `lower-commands` ciascuno per conto proprio e non leggono più `(:commands path)` direttamente: passa tutto da un accessor unico (es. `path-micro-commands`), con il lowering calcolato una volta per path (es. `delay` nella mappa path costruito alla creazione — meccanismo a scelta di Code, il requisito è: una sola casa, calcolo una sola volta, nessun accesso diretto residuo). I 20+ siti del censimento (core, extrusion, loft, shape, macros, edit_path) migrano all'accessor: è la "modifica triviale" stimata nell'accertamento, e con l'accessor anche i cinque consumer di misura di core.cljs (path-segments, path-total-length, add-mark, sample-path-at-distance, rail-fraction) ricevono lo stesso vettore di oggi senza altre modifiche.

### 4. Errori forti sui default-skip (punto 3 della verifica dell'accertamento)

`run-path` in macros (~885) e `replay-path-to-recording` in shape (~900) oggi scartano silenziosamente i comandi sconosciuti. In Fase 1 quei default diventano throw con messaggio esplicito (es. `"run-path: unknown command :bezier-to — missing lower-commands?"`). Qualunque punto di lowering dimenticato deve fallire forte al primo incontro, mai produrre geometria mancante in silenzio.

### 5. Lettori di tag: invariati

Guard rail-start, corner machinery, `split-leading-cap`, `seed->nodes-3d` (`:pure`/`:span`): nessuna modifica semantica — leggono i micro-comandi dall'accessor, che li produce identici. La loro semplificazione è Fase 2.

## Verifica

- **Rete prima del codice, con golden pre-fix.** Prima di toccare il recorder: costruire un corpus di path rappresentativi (segmenti dritti; corner `th`/`tv`/`tr`; `arc-h`/`arc-v`; `bezier-to` esplicito/quadratico/auto; `bezier-as` nei tre modi default/`:cubic`/`:control`; `bezier-to-anchor`; `path-2d`; `mark` e side-trip/path annidati; resolution `:n` e `:s`; il caso resolution-cambiata-tra-def-e-consumo) e serializzarne i vettori `:commands` attuali come golden. Post-fix, il vettore prodotto dall'accessor deve essere **strutturalmente uguale** (tag e ordini inclusi) al golden, caso per caso.
- Suite completa: 0 failures. **Nessun aggiornamento di pinned ammesso in questa fase** — una pinned che cambia è una regressione per definizione del criterio byte-identico.
- Grep di guardia nel codice: nessun accesso diretto a `(:commands` fuori dall'accessor e dal modulo di lowering (whitelist esplicita e corta).
- Test degli errori forti: un comando sconosciuto iniettato in `run-path` e `replay-path-to-recording` lancia con il messaggio previsto.
- Round-trip editor invariato: aprire in edit-path un seed del corpus e bakearlo produce lo stesso sorgente e la stessa geometria di oggi.
- Performance di massima: il lowering non degrada l'eval in modo percettibile (script di riferimento del corpus, criterio lasco: entro ~10% dei tempi attuali).

## Fuori scope

- Fase 2 (consumer che leggono i segmenti alto livello, resolution del consumer, guard analitico diretto, editor senza `:pure`) e Fase 3 (rimozione dei tag e aggiornamento di Architecture §6.3.1).
- Qualunque cambiamento osservabile di comportamento, incluse migliorie "gratis" che il nuovo assetto renderebbe facili: si annotano, non si fanno.
- L'allineamento `:smooth` di `bezier-as` e degli archi (accertamenti già tracciati in code-issues e Roadmap).
- L'API utente del DSL.
