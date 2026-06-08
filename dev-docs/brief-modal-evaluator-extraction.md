# Brief — Estrazione del modal evaluator (Architecture §15.2.1)

## Contesto

`tweak` (`editor/test_mode.cljs`, ~770 righe) e `pilot` (`editor/pilot_mode.cljs`, ~579 righe) sono due implementazioni parallele dello stesso pattern, cresciute separate per cronologia (tweak prima, pilot copiato e fatto divergere). Lo scheletro condiviso, oggi duplicato in entrambi i moduli e non astratto, è: stato in atom `defonce` locale al modulo; flag `skip-next-*` per evitare il rientro della macro durante la rivalutazione che la sessione stessa triggera; mutex centrale `claim-interactive-mode!` / `release-interactive-mode!` (in `editor/state.cljs`) che impedisce sessioni concorrenti; pannello DOM inserito sotto al pannello REPL; keydown handler globale in capture phase; distinzione script mode vs REPL mode; costruzione testuale del replacement; riscrittura del sorgente alla conferma via `cm/replace-range` seguita da `run-definitions-fn`. A questo si aggiunge l'ingresso in due fasi di pilot (`request!` / `enter!`): durante l'eval la macro restituisce un valore al flusso in corso (la mesh esistente) senza installare l'handler, e solo dopo che l'eval è completo il driver, vista la flag `requested?`, entra davvero in sessione. In Ridley l'eval è il padrone del flusso e la sessione lo attende, non lo interrompe.

Pagare questo debito ora è giustificato dall'arrivo del terzo caso concreto: `edit-bezier` (brief separato, dipendente da questo). Senza l'astrazione, `edit-bezier` sarebbe un terzo clone che fa divergere ulteriormente l'insieme — lo scenario che §15.2.1 mette esplicitamente in guardia di evitare.

Riferimenti: `Architecture.md` §11.2.2–11.2.4 e §15.2.1; `Roadmap.md` §1.3 (voce costo medio) e §2.2.

## Lavoro richiesto

1. Rinomina `editor/test_mode.cljs` → `editor/tweak_mode.cljs` per allineare il nome modulo al nome utente del modal evaluator (parte di 15.2.1). Aggiornare i require dipendenti.

2. Crea `editor/modal_evaluator.cljs` che assorbe lo scheletro condiviso: ciclo di vita della sessione e stato; claim/release del mutex centrale; mount/unmount del pannello DOM nel punto di layout sotto la REPL; install/remove del keydown handler globale in capture phase; ingresso in due fasi `request!` / `enter!`; costruzione del replacement e commit via `cm/replace-range` + `run-definitions-fn`; gestione script mode vs REPL mode; guard `skip-next-*` contro il rientro. Definire un'interfaccia (protocollo o mappa di callback) che le sessioni concrete implementano, lasciando fuori solo la loro logica specifica.

   Nota sull'ingresso in due fasi: oggi **solo pilot** è a due fasi. La macro espande in `pilot-request!` (`editor/macros.cljs`), e il loop driver che dopo l'eval controlla `requested?` e chiama `enter!` vive **inline in `core.cljs`** (`(when (pilot-mode/requested?) (pilot-mode/enter!))`), cablato su `pilot-mode`. Tweak invece apre la sessione **sincronicamente** dentro l'impl della macro (`claim-interactive-mode!` diretto, nessun `request!`/`enter!`, nessun hook nel driver). Il layer estratto deve quindi (a) **possedere il loop driver post-eval**, sostituendo il riferimento hardcoded a `pilot-mode` con un dispatch sul modal evaluator attivo registrato; (b) modellare l'apertura sincrona di tweak come il **caso degenere** dell'ingresso in due fasi — `request!` seguito immediatamente da `enter!` — così che entrambi passino per lo stesso percorso. La distinzione script mode vs REPL mode va promossa a coppia di hook espliciti dell'interfaccia (`run-script-mode-fn` / `run-repl-mode-fn`, cfr. Architecture 11.2.4). Effetto collaterale: con stato e driver centralizzati in un punto solo, la fragilità #3 di tweak (singleton, fallisce alla seconda chiamata nello stesso eval) diventa fixabile in un posto solo quando si affronterà 15.3.1 — non risolverla qui, ma disegnare il confine perché 15.3.1 sia poi un intervento localizzato.

3. Migra `tweak` e `pilot` sopra il layer estratto, riducendo ciascun modulo alla sola logica specifica: per tweak la camminata dei literal e la generazione degli slider; per pilot l'interpretazione del keymap (pattern vim-like a tre modi) e la compattazione dei comandi prima della conferma. Obiettivo di grandezza: ~150 righe ciascuno.

4. Comportamento invariato. La migrazione non deve cambiare nulla di osservabile nelle due sessioni esistenti. In particolare i tre punti di fragilità noti di tweak (offset assoluti catturati una volta sola e stale dopo edit durante la sessione; rifiuto del nuovo Cmd+Enter da parte del mutex; fallimento alla seconda chiamata nello stesso eval) restano come sono — la loro correzione è la voce separata 15.3.1, fuori scope qui. Non regredirli, non risolverli.

## Verifica

- Sessioni tweak e pilot identiche a prima: apertura, preview in tempo reale, conferma che riscrive il sorgente, cancel che ripristina.
- Mutex: una seconda sessione modal mentre la prima è aperta viene rifiutata con la stessa eccezione di oggi; Cmd+Enter durante una sessione resta rifiutato.
- I due moduli `tweak_mode` e `pilot_mode` importano il layer condiviso e scendono nell'ordine di grandezza delle ~150 righe di logica specifica.
- Il test harness dei modi interattivi (cfr. copertura 15.2.7) passa; aggiungere, se fattibile a costo ragionevole, copertura sul layer estratto (ingresso in due fasi, claim/release del mutex, commit del replacement).

## Fuori scope

- `edit-bezier` (brief separato, da fare dopo questo: deve essere costruito sopra `modal_evaluator`, non clonando pilot).
- Correzione delle fragilità di tweak / graceful degradation del mutex (15.3.1).
- Qualunque layer di manipolazione diretta del viewport via mouse (maniglie, picking, proiezione schermo-mondo, drag).
- Multimethod per il dispatch dei provider AI (15.2.2) e unificazione del flusso storia AI (15.2.3): voci di consolidamento distinte, non toccate qui.

## Stima

Due giornate: una per estrarre l'astrazione, una per migrare i due moduli esistenti.