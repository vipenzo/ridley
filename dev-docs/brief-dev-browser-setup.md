# Brief: browser di sviluppo dedicato per Code (Playwright MCP + fallback headless)

## Contesto

Oggi le prove browser di Code dipendono da un tab aperto manualmente da Vincenzo su `localhost:9000`. La REPL shadow-cljs si collega a un runtime JS nel browser, e con tab gestiti a mano (aperti, chiusi, in sleep, duplicati) la selezione del runtime è inaffidabile: connessioni fallite, tentativi ripetuti, dipendenza da un umano per ogni refresh.

Obiettivo: dare a Code un browser tutto suo, che possa lanciare, chiudere e ricaricare autonomamente, con esattamente un tab connesso all'app durante il lavoro. Beneficio aggiuntivo per Ridley: verifica visiva autonoma del viewport 3D (screenshot) e lettura della console senza chiedere a Vincenzo cosa vede.

Assetto attuale rilevante (da `shadow-cljs.edn`): dev server su `localhost:9000` (`:dev-http`), nREPL su 7888, build `:app` browser con Manifold WASM e Three.js (WebGL necessario). Regola operativa da rispettare a regime: quando Code lavora alla REPL, il suo tab deve essere l'unico client shadow-cljs connesso.

## Lavoro richiesto

1. Registrare il server MCP Playwright per Claude Code: `claude mcp add playwright -- npx @playwright/mcp@latest`. Nota: la registrazione ha effetto sulle sessioni successive; se serve un riavvio della sessione di Code, segnalarlo a Vincenzo nel report invece di darlo per scontato.
2. Verificare che i tool Playwright siano disponibili (navigate, screenshot, evaluate, lettura console) e che il browser installato da Playwright supporti WebGL. Se il rendering WebGL headless fallisce, provare il lancio in modalità headed o con flag di rendering software, e documentare quale configurazione funziona.
3. Creare lo script di fallback `scripts/dev-browser.sh` con i comandi `start`, `stop`, `restart`: lancia Chrome headless con profilo dedicato in `/tmp/ridley-dev-browser`, `--remote-debugging-port=9222`, `--use-angle=swiftshader`, aprendo `http://localhost:9000`. Lo script serve come piano B se l'MCP non fosse utilizzabile in qualche sessione, e come runtime REPL stabile indipendente da Playwright.
4. Prova di fumo end-to-end della catena completa: dev server attivo, browser di Code aperto su `localhost:9000`, connessione REPL shadow-cljs riuscita al primo tentativo, valutazione di un'espressione semplice nel runtime del browser (es. una chiamata a una funzione turtle esistente), screenshot del viewport che mostra il risultato, lettura della console senza errori inattesi. Verificare in particolare che Manifold WASM si inizializzi nel browser headless.
5. Verificare la convivenza dei runtime: con un secondo tab aperto manualmente sulla stessa app, documentare come shadow-cljs seleziona il runtime e qual è la procedura per garantire che la REPL parli col tab di Code (chiusura degli altri tab, o selezione esplicita del runtime). Scrivere la procedura risultante nel report.
6. Come verifica applicativa reale, eseguire la checklist di reentrata per edit-attach: 10 cicli di reload/reeval consecutivi documentando esito di ciascuno (item già in coda come verifica manuale, ora eseguibile autonomamente). Se la checklist fallisce, il fallimento va loggato in dev-docs/code-issues.md come issue applicativa, senza considerarlo un fallimento del setup browser.

## Verifica

- Sessione dimostrativa nel report: avvio browser, connessione REPL al primo colpo, evaluate + screenshot + console, chiusura pulita, ripetuta due volte di fila senza intervento umano.
- Lo script `scripts/dev-browser.sh` funziona nei tre comandi e `stop` non lascia processi orfani (`pgrep -f ridley-dev-browser` vuoto dopo stop).
- Esito documentato della checklist di reentrata x10 (passi, esiti, eventuali issue loggate).
- La procedura di convivenza runtime (punto 5) è scritta nel report in forma direttamente riusabile.

## Fuori scope

- Automazione di test E2E veri e propri (suite Playwright con assertion): questo brief copre solo l'infrastruttura interattiva per lo sviluppo.
- Modifiche a shadow-cljs.edn o alla configurazione nREPL.
- Il browser interno dell'app desktop Tauri: qui si parla solo della webapp di sviluppo su localhost:9000.
