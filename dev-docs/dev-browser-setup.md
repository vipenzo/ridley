# Dev browser dedicato per Code — report + guida operativa

Data: 2026-07-15. Riferimento: `dev-docs/brief-dev-browser-setup.md`.

Obiettivo: dare a Code un browser tutto suo, che può lanciare/chiudere/ricaricare
in autonomia, con **un solo** tab connesso all'app durante il lavoro alla REPL, e
con verifica visiva (screenshot) e lettura console senza dipendere da Vincenzo.

## TL;DR — come si usa

```bash
scripts/dev-browser.sh start        # lancia Chrome headless su localhost:9000, marker incluso
scripts/dev-browser.sh status       # processo up? CDP up? quale target?
scripts/dev-browser.sh screenshot out.png
scripts/dev-browser.sh eval 'document.title'   # JS nel tab, risultato JSON
scripts/dev-browser.sh reload       # ricarica il tab (Page.reload, re-inietta il marker)
scripts/dev-browser.sh stop         # chiusura pulita, nessun orfano
scripts/dev-browser.sh restart
```

Poi, alla REPL shadow-cljs (via `clj-nrepl-eval`):

```
(shadow/repl :app)                       ; si aggancia al runtime del browser (primo colpo)
js/window.__ridley_dev_browser__         ; => true  ⇒  la REPL parla col tab di Code
(+ 40 2)                                 ; => 42     round-trip nel runtime browser
```

## Due percorsi

1. **Playwright MCP** — `claude mcp add playwright -- npx @playwright/mcp@latest`.
   Registrato e il server risponde (`claude mcp list` → `playwright … ✓ Connected`).
   Browser scaricato (`npx playwright install chromium` → chromium-headless-shell 149).
   ⚠️ **I tool Playwright (navigate/screenshot/evaluate/console) NON sono disponibili
   nella sessione in cui è avvenuta la registrazione: si caricano all'avvio della
   sessione successiva.** Serve un riavvio della sessione di Code perché Code possa
   chiamarli. Fino ad allora si usa il percorso 2.

2. **Fallback `scripts/dev-browser.sh`** — Chrome headless con profilo dedicato,
   remote-debugging su :9222, pilotato via Chrome DevTools Protocol. È il runtime
   REPL stabile e **indipendente da Playwright**, ed è quello verificato end-to-end
   in questa sessione. Funziona *adesso*, senza riavvii.

## Configurazione WebGL headless (verificata)

Chrome è lanciato con `--headless=new` + ANGLE/SwiftShader software rendering:

```
--headless=new --remote-debugging-port=9222 --user-data-dir=/tmp/ridley-dev-browser
--use-gl=angle --use-angle=swiftshader --enable-unsafe-swiftshader --ignore-gpu-blocklist
--no-first-run --no-default-browser-check --disable-background-timer-throttling
--disable-backgrounding-occluded-windows --disable-renderer-backgrounding
--window-size=1600,1000
```

- WebGL 2.0 attivo: `WebGL 2.0 (OpenGL ES 3.0 Chromium)`, renderer
  `ANGLE (…SwiftShader Device… )`. Three.js renderizza il viewport (box visibile
  nello screenshot). `--enable-unsafe-swiftshader` è necessario sui Chrome recenti
  perché il WebGL via SwiftShader è opt-in.
- Unico rumore in console: warning benigni `GL Driver Message … GPU stall due to
  ReadPixels` (SwiftShader software, non un errore).
- **Manifold WASM si inizializza** nel browser headless (console: `Manifold WASM
  initialized`).

## Smoke test end-to-end (fallback) — ESEGUITO

Catena completa verificata:
- dev server attivo (:9000), Chrome di Code aperto su `localhost:9000/index.html`;
- connessione REPL shadow-cljs **al primo tentativo**: `(shadow/repl :app)` → `[:selected :app]`;
- eval nel runtime del browser, atomico: `[(+ 40 2) js/window.__ridley_dev_browser__
  (.-userAgent js/navigator)]` → `[42 true "…HeadlessChrome/150…"]`
  (lo UA prova che il runtime è il browser headless, non la JVM);
- render di una funzione turtle esistente: `(ridley.core/run-manual-code
  "(register d (box 25 25 25) (rt 20) (tv 15))")` → box visibile nel viewport (screenshot);
- lettura console: `Manifold WASM initialized`, `shadow-cljs: #NN ready!`, nessun
  errore/eccezione inatteso.

Sessione dimostrativa (avvio → REPL primo colpo → eval/screenshot/console → chiusura
pulita) **ripetuta due volte di fila senza intervento umano** (`demo-cycle.sh`),
entrambe con `ORPHAN CHECK: PASS`.

## Verifica script

- `start` / `stop` / `restart` / `status` / `reload` / `screenshot` / `eval`: tutti OK.
- `stop` non lascia orfani: `pgrep -f ridley-dev-browser` **vuoto** dopo lo stop
  (verificato ad ogni ciclo; i processi figli Chrome portano `--user-data-dir` nel
  cmdline, quindi vengono catturati). Porta CDP :9222 chiusa dopo lo stop.
- `restart` mantiene il marker (`window.__ridley_dev_browser__ === true` dopo restart).

## Convivenza runtime (procedura riusabile) — IMPORTANTE

Esperimento con 2 tab connessi (Code + un secondo tab "manuale"):

- shadow-cljs aggancia la REPL a **UN** runtime: **il più vecchio / primo connesso**
  (client-id più basso). Con 2 runtime, `(shadow/repl :app)` seleziona silenziosamente
  quello più vecchio — **nessun warning** su "multiple runtimes".
- Un runtime **nuovo NON ruba** il binding di una REPL già agganciata.

**Conseguenza operativa (regola d'oro):** Code lancia il suo browser **per primo**
(`scripts/dev-browser.sh start`), prima che esista qualsiasi tab umano. Così il tab di
Code è il runtime più vecchio e la REPL vi si aggancia e vi resta, anche se Vincenzo
apre un tab dopo.

**Auto-verifica:** dopo `(shadow/repl :app)`, valutare `js/window.__ridley_dev_browser__`.
- `true` ⇒ la REPL sta parlando col tab dedicato di Code. Procedi.
- `nil` ⇒ è selezionato un altro tab. Rimedio: **chiudere gli altri tab** finché il tab
  di Code è l'unico runtime (poi `:cljs/quit` + `(shadow/repl :app)` per riagganciare).

Caveat: se il tab di Code si disconnette e riconnette (es. reload) mentre un tab umano è
aperto, al ritorno prende un client-id più alto (diventa il *più recente*) e la REPL
potrebbe agganciare il tab umano (più vecchio). Perciò, quando conta, vale la regola del
brief: **il tab di Code deve essere l'unico client connesso**.

## Checklist reentrata edit-attach ×10 — ESEGUITA, tutta verde

Verifica applicativa reale (era "left to manual browser verification" nel fix del
2026-07-09, `project-edit-attach-reentry-glitch`). 10 cicli consecutivi di
`open → gesto(live-preview) → confirm(OK) → reopen`, guidati nel runtime del browser:

| esito per ogni ciclo (1–10) | valore |
|---|---|
| `opened` (apre alla prima) | true |
| `flagAfterPreview` (skip flag dopo live-preview) | **false** |
| `confirm` (OK) | ok |
| `activeAfterConfirm` (sessione chiusa dopo OK) | false |
| **`reopened`** (il rientro ingaggia la sessione) | **true** |

Zero errori. Il `skip-next` resta **disarmato** dopo la live-preview (`false`), quindi il
primo rientro dopo un OK **apre** la sessione in tutti e 10 i cicli. Il fix del
2026-07-09 regge nel browser live → **nessun issue applicativo da loggare** in
`dev-docs/code-issues.md`.

## Nota su un problema trovato e risolto durante il setup

All'inizio della sessione il dev server era già in esecuzione (`shadow-cljs watch app`),
ma il tab headless si caricava **senza mai connettersi come runtime** (client relay fermi
a 2, nessun log di connessione shadow). Causa: il build `:app` era nello stato corrotto
noto — `public/js/manifest.edn` conteneva solo `shadow.cljs.devtools.client.console` e
**non** `…client.browser` (il client che apre la WebSocket runtime per REPL/hot-reload).
In quello stato **nessun** browser (headless o umano) può agganciare la REPL.

Rimedio (procedura documentata): full stop + restart pulito —
`npx shadow-cljs stop` → `npm run dev`. Dopo il restart il manifest include
`…client.browser`/`…client.env`/`…client.websocket` e il tab si aggancia (client relay
→ 3). **Diagnostica rapida:** `grep -o "shadow/cljs/devtools/client/[a-z]*\.cljs"
public/js/manifest.edn` — se manca `browser.cljs`, il build è corrotto, serve restart.

## File

- `scripts/dev-browser.sh` — start/stop/restart/status/reload/screenshot/eval (deliverable).
- Helper CDP dependency-free usati per la verifica (node ≥21 ha `WebSocket` globale):
  screenshot/eval/console via Chrome DevTools Protocol, senza Playwright.

## Fuori scope (come da brief)

Nessuna modifica a `shadow-cljs.edn`/nREPL. Nessuna suite E2E Playwright con assertion.
Nessun intervento sul webview interno di Tauri (qui solo la webapp su localhost:9000).
