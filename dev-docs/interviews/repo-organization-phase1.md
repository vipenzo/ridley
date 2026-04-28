---
name: Repo organization — phase 1 reconnaissance
description: Ricognizione descrittiva di GitHub Pages, /docs/, /dev-docs/ e link interni
type: project
---

# Ricognizione: organizzazione documenti e GitHub Pages

Risposte basate esclusivamente su quello che è osservabile nel repository
al commit corrente. Le scelte non vengono proposte qui.

## 1. GitHub Pages: configurazione attuale

### 1.1 Source folder, branch, custom domain

La configurazione **viva** di GitHub Pages **non è nel repo**: vive nei
settings di GitHub. Il workflow [deploy.yml](.github/workflows/deploy.yml)
usa il modello "Pages via Actions" (`actions/upload-pages-artifact@v3` a
[deploy.yml:43-46](.github/workflows/deploy.yml#L43-L46) e
`actions/deploy-pages@v4` a [deploy.yml:55-57](.github/workflows/deploy.yml#L55-L57)).
In questo modello Pages non legge più da una cartella di un branch: il
deploy viene da un artifact pubblicato dal job. Quindi non si può dedurre
"source folder" o "source branch" leggendo il repo.

Nessun `CNAME` presente nel repo (`find` su tutto il workspace dà solo
risultati dentro `desktop/src-tauri/vendor/libfive/...`, non a livello
repo, non in `/docs/`, non in `/public/`). Quindi nessun custom domain
dichiarato dal repo. L'URL pubblico esposto è
`https://vipenzo.github.io/Ridley/`, citato in [README.md:5](README.md#L5).

### 1.2 Workflow Actions che pubblica la webapp

Il workflow è [.github/workflows/deploy.yml](.github/workflows/deploy.yml).
In sintesi:

- Trigger: `release: types: [published]` ([deploy.yml:3-5](.github/workflows/deploy.yml#L3-L5))
  e `workflow_dispatch` ([deploy.yml:6](.github/workflows/deploy.yml#L6)).
  Quindi il deploy parte solo quando si pubblica una release GitHub o si
  lancia il workflow a mano.
- Job `build` ([deploy.yml:18-46](.github/workflows/deploy.yml#L18-L46))
  installa Node 20, fa `npm ci`, calcola un tag (`GITHUB_REF_NAME`) e
  builda con
  `npx shadow-cljs release app --config-merge '{:closure-defines {ridley.version/VERSION ...}}'`
  ([deploy.yml:38](.github/workflows/deploy.yml#L38)).
  Il build `:app` di [shadow-cljs.edn:6-13](shadow-cljs.edn#L6-L13) ha
  `:output-dir "public/js"`, quindi il build scrive dentro `public/`.
- Upload dell'artifact: `actions/upload-pages-artifact@v3` con
  `path: './public'` ([deploy.yml:43-46](.github/workflows/deploy.yml#L43-L46)).
- Job `deploy` ([deploy.yml:48-57](.github/workflows/deploy.yml#L48-L57))
  pubblica l'artifact via `actions/deploy-pages@v4`.

Esiste un secondo workflow [test.yml](.github/workflows/test.yml) che
lancia `npx shadow-cljs compile test` su push/PR; non tocca Pages.
Esiste un terzo workflow [desktop-build.yml](.github/workflows/desktop-build.yml)
ma riguarda Tauri.

### 1.3 Il bundle compilato finisce in una cartella commessa?

**No, non per il deploy attivo.** Il job `build` produce in
`public/js/` ([shadow-cljs.edn:7](shadow-cljs.edn#L7)) e quella cartella
è gitignorata: [.gitignore:5-6](.gitignore#L5-L6) elenca `public/js/`.
Il `git ls-files public/` mostra **tracked** solo
`public/css/style.css`, `public/fonts/Roboto-Regular.ttf`,
`public/fonts/RobotoMono-Regular.ttf`, `public/index.html`,
`public/scripts/{example-box.clj, example-torus.clj, index.json}`.
Il bundle JS non è committato. Pages riceve il bundle come artifact in CI.

**Però** `/docs/` esiste, è tracciata, e contiene un bundle compilato
(vedi sezione 2). È un residuo: l'`output-dir "docs/js"` è ancora
presente in [shadow-cljs.edn:16](shadow-cljs.edn#L16) sotto un build
chiamato `:release`, marcato dal commento
"Release build for GitHub Pages (legacy, not used)" a
[shadow-cljs.edn:14](shadow-cljs.edn#L14).
[package.json:11-12](package.json#L11-L12) ha ancora gli script
`build:gh-pages` e `copy-static` che scrivono in `docs/`, ma
[deploy.yml:38](.github/workflows/deploy.yml#L38) non li invoca.

Il fatto che `/docs/` sia "legacy, non usato" è dichiarato anche in
[dev-docs/architecture-recon.md:197](dev-docs/architecture-recon.md#L197).

## 2. Struttura della cartella `/docs/`

Esiste. Contiene **bundle compilato** (più asset statici copiati da
`/public/`), tracciato in git.

Top-level di `/docs/`:

- `.nojekyll` — file vuoto, segnale a Pages di non passare per Jekyll
- `index.html` (2 646 byte)
- `css/`
- `js/`
- `scripts/`

Subdirectory:

- `docs/css/style.css` (5 327 byte)
- `docs/js/main.js` (1 616 261 byte, **bundle compilato**, ultima
  modifica file `Jan 17 22:27`)
- `docs/js/manifest.edn` (3 054 byte)
- `docs/scripts/example-box.clj`, `example-torus.clj`, `index.json`

Quindi è un **mix** di sorgenti/asset (HTML, CSS, esempi) e di **bundle
compilato** (`js/main.js`, `js/manifest.edn`).

`_config.yml` (Jekyll): **assente**. Una grep cross-repo non trova
nessun file `_config*`.

`.nojekyll`: **presente**, [docs/.nojekyll](docs/.nojekyll), file vuoto.

`index.html`: **presente**, [docs/index.html](docs/index.html). Carica
Manifold WASM via `import` da CDN ([docs/index.html:9-12](docs/index.html#L9-L12))
e include `js/main.js` come `<script>` a
[docs/index.html:61](docs/index.html#L61). È in pratica la stessa
struttura di [public/index.html](public/index.html), ma una versione
più vecchia: l'`index.html` di `/public/` pesa 5 000 byte, quello di
`/docs/` 2 646 byte.

Storia git (dal commit log):

- Ultimo commit che tocca [docs/index.html](docs/index.html):
  `2e8ca9e Add native export dialog with STL/3MF format choice`.
- Ultimo commit che tocca [docs/js/main.js](docs/js/main.js):
  `a2d2e28 Build GitHub Pages with closed extrusion support`.
- Primo commit che introduce `.nojekyll`/main.js dentro `/docs/`:
  `b870f81 Add WebXR VR/AR support, GitHub Pages deployment`.

Nessun commit recente tocca `/docs/` (i commit più recenti del repo
sono `74d9041`, `8ff98af`, `22117fc`, `f990f70`, `c81f00f`).

## 3. Struttura della cartella `/dev-docs/`

Tutto il listato qui sotto viene da `ls -la dev-docs/`.

### 3.1 File `.md` al top level di `/dev-docs/` (52 file)

Categorizzazione informale (non interpretativa, basata sui nomi e
posizione):

**Documenti "di riferimento" / ufficiali del progetto**

- [Architecture.md](dev-docs/Architecture.md) (49 350 byte)
- [Roadmap.md](dev-docs/Roadmap.md) (32 232 byte)
- [Spec.md](dev-docs/Spec.md) (93 054 byte)
- [Examples.md](dev-docs/Examples.md) (36 401 byte)
- [Accessibility.md](dev-docs/Accessibility.md) (8 925 byte)
- [ShapeFn.md](dev-docs/ShapeFn.md) (11 771 byte)
- [FilletChamfer2D.md](dev-docs/FilletChamfer2D.md) (2 233 byte)
- [FilletChamfer3D.md](dev-docs/FilletChamfer3D.md) (4 487 byte)

I primi quattro sono esplicitamente elencati come da mantenere in
[CLAUDE.md](CLAUDE.md) sotto "Documentation Maintenance"
(Architecture, Roadmap, Examples, Spec).

**Documenti "di lavoro" — TASK\_\* (15 file)**

- TASK_ai_focus_indicator_layer, TASK_animation_system,
  TASK_create_ai_extension_skeleton, TASK_disambiguate_commands_with_prefixes,
  TASK_extend_registry_for_shapes, TASK_fillet_chamfer,
  TASK_fix_codemirror_selection_and_ai, TASK_fix_loft_manifold,
  TASK_fix_wrap_value_field, TASK_handle_it_lo_reference,
  TASK_implement_ai_handlers, TASK_implement_structured_replace,
  TASK_improve_ai_system_prompt, TASK_integrate_clipper2_shape_booleans,
  TASK_puppet_library, TASK_restore_full_ai_panel, TASK_shell_shape_fn,
  TASK_voice_ux_improvements
- Inoltre due varianti capitalizzate: [Task_Bounds_Function.md](dev-docs/Task_Bounds_Function.md)
  (4 096 byte) e [Task_Tier_System.md](dev-docs/Task_Tier_System.md)
  (11 534 byte). Convenzione di naming inconsistente (`TASK_` vs
  `Task_`).
- Tutti datati gennaio-marzo, prima della grande rotazione architetturale
  di aprile.

**Documenti "di lavoro" — \*-spec.md / audit / recon (10 file)**

- [describe-spec.md](dev-docs/describe-spec.md) (21 612 byte)
- [help-system-spec-v2.md](dev-docs/help-system-spec-v2.md) (32 061 byte)
- [spec-prompt-editor-and-history.md](dev-docs/spec-prompt-editor-and-history.md) (14 834 byte)
- [subdivide-spec.md](dev-docs/subdivide-spec.md) (8 160 byte)
- [test-tweak-spec.md](dev-docs/test-tweak-spec.md) (9 328 byte)
- [turtle-scope-spec.md](dev-docs/turtle-scope-spec.md) (13 846 byte)
- [turtle-scope-spec-v2.md](dev-docs/turtle-scope-spec-v2.md) (26 252 byte)
- [turtle-surface-constraints-spec.md](dev-docs/turtle-surface-constraints-spec.md) (16 937 byte)
- [voice-input-spec.md](dev-docs/voice-input-spec.md) (20 726 byte)
- [warp-spec.md](dev-docs/warp-spec.md) (17 506 byte)
- [task-viewport-picking.md](dev-docs/task-viewport-picking.md) (25 391 byte)
- [manifold-backends-audit.md](dev-docs/manifold-backends-audit.md) (18 124 byte)
- [transport-audit.md](dev-docs/transport-audit.md) (19 406 byte)
- [architecture-recon.md](dev-docs/architecture-recon.md) (49 482 byte)

**Documenti "storici" / contesto chiuso**

- [Architecture_Assessment_2026-02-06.md](dev-docs/Architecture_Assessment_2026-02-06.md) (3 557 byte) — data nel nome
- [AI_Extension.md](dev-docs/AI_Extension.md) (18 932 byte)
- [AI_Tier1_SystemPrompt.md](dev-docs/AI_Tier1_SystemPrompt.md) (10 876 byte)
- [DESIGN_procedural_animations.md](dev-docs/DESIGN_procedural_animations.md) (24 596 byte)
- [ProceduralDisplacement.md](dev-docs/ProceduralDisplacement.md) (25 314 byte)
- [ProceduralDisplacement2.md](dev-docs/ProceduralDisplacement2.md) (20 567 byte)
- [Voice_and_Help_Architecture.md](dev-docs/Voice_and_Help_Architecture.md) (12 957 byte)
- [ideas-for-a-ridley-aware-llm.md](dev-docs/ideas-for-a-ridley-aware-llm.md) (5 839 byte)
- [cosedafare.md](dev-docs/cosedafare.md) (2 252 byte) — italiano, "cose da fare"

**File non chiaramente classificabili**

- [Architecture_1.md](dev-docs/Architecture_1.md) (332 731 byte). È il
  file che hai aperto nell'IDE. Non tracciato (vedi `git status` in
  prologo: `?? dev-docs/Architecture_1.md`). Dimensione ~10x quella di
  Architecture.md.
- [interviews/](dev-docs/interviews/), nuovo, non tracciato (`?? dev-docs/interviews/`).
- [archive/](dev-docs/archive/), tracciato, ha un suo
  [README.md](dev-docs/archive/README.md) di 618 byte che dice
  "Historical artifacts kept for reference. Not part of the active
  documentation" e contiene `multiboard-native.clj`.
- [libraries/](dev-docs/libraries/) — non sono `.md` ma codice di
  libreria utente: `gears.clj`, `puppet.cljs`, `weave.clj`.

**Asset binari**

- [dev-docs/screenshot.png](dev-docs/screenshot.png) (515 374 byte),
  referenziato da [README.md:7](README.md#L7).
- `dev-docs/.DS_Store` (rumore macOS, non gitignorato esplicitamente
  per dev-docs ma il `.gitignore` globale a [.gitignore:24](.gitignore#L24)
  lo include).

### 3.2 Subdirectory presenti

- [dev-docs/archive/](dev-docs/archive/) — tracciata, README + 1
  artifact storico
- [dev-docs/interviews/](dev-docs/interviews/) — **non tracciata**, 12
  file `.md`, tutti generati da metà aprile in poi, capitoli numerati
  `ch05`–`ch12`
- [dev-docs/libraries/](dev-docs/libraries/) — tracciata, 3 file di
  codice `.clj`/`.cljs`

## 4. Link relativi fra documenti Markdown

Conteggio (regex `]\(...md\)`, eventualmente con anchor `#L...`):

- Totale link da `.md` a `.md` dentro `/dev-docs/`: **25**
- Di questi, **22** stanno dentro `dev-docs/interviews/`
- Solo **3** stanno nel resto di `/dev-docs/` (tutti in `Spec.md`)

### 4.1 Stile dei link nei doc "di riferimento"

I tre link in `Spec.md` sono **bare relative same-directory**:

- [dev-docs/Spec.md:1163](dev-docs/Spec.md#L1163):
  `[FilletChamfer3D.md](FilletChamfer3D.md)`
- [dev-docs/Spec.md:2563](dev-docs/Spec.md#L2563):
  `[Accessibility.md](Accessibility.md)`
- [dev-docs/Spec.md:2616](dev-docs/Spec.md#L2616):
  `[FilletChamfer3D.md](FilletChamfer3D.md)`

Niente `./`, niente `../`, niente prefisso `dev-docs/`.

### 4.2 Stile dei link dentro `dev-docs/interviews/`

Le interview usano una forma **repo-rooted**: il path scritto inizia con
`dev-docs/...` invece che con `../...`. Esempi:

- [dev-docs/interviews/ch10-webrtc-phase2.md:81](dev-docs/interviews/ch10-webrtc-phase2.md#L81):
  `[Roadmap.md](dev-docs/Roadmap.md)`
- [dev-docs/interviews/ch09-webapp-desktop-phase1.md:125](dev-docs/interviews/ch09-webapp-desktop-phase1.md#L125):
  `[transport-audit.md](dev-docs/transport-audit.md)`
- [dev-docs/interviews/ch11-clarifications.md:9](dev-docs/interviews/ch11-clarifications.md#L9):
  `[`dev-docs/architecture-recon.md`](dev-docs/architecture-recon.md)`

Da segnalare: questi link, **scritti come `(dev-docs/Foo.md)` da dentro
`dev-docs/interviews/`**, sui renderer che risolvono i link rispetto
alla directory del file (incluso GitHub) puntano in realtà a
`dev-docs/interviews/dev-docs/Foo.md` — percorso che non esiste. È una
forma di link che funziona solo se interpretata come "relativa alla
root del repository".

C'è anche almeno un riferimento a [README.md#L174](README.md#L174) dentro
[dev-docs/interviews/ch12-test-phase1.md](dev-docs/interviews/ch12-test-phase1.md),
con la stessa caratteristica (scritto come bare `README.md` dall'interno
di `dev-docs/interviews/`).

### 4.3 Link relativi che usano `../`

Esistono in alcune interview, ma puntano al **codice sorgente**, non a
`.md`. Esempi tutti da
[dev-docs/interviews/ch06-raw-arrays-clarification.md](dev-docs/interviews/ch06-raw-arrays-clarification.md):

- riga 13, 29: `[manifold/core.cljs:...](../../src/ridley/manifold/core.cljs#L...)`
- riga 33: `[sdf/core.cljs:...](../../src/ridley/sdf/core.cljs#L...)`
- righe 42, 50, 81, 82, 83: idem `../../src/ridley/manifold/core.cljs`

Questi sì sono path corretti, calcolati partendo da
`dev-docs/interviews/`.

### 4.4 Link da `/dev-docs/` verso `/docs/` o verso path assoluti

- Verso `/docs/`: nessun link `.md` di `/dev-docs/` punta a contenuti di
  `/docs/`. L'unica menzione di `docs/` in `/dev-docs/` è
  [dev-docs/architecture-recon.md:197](dev-docs/architecture-recon.md#L197)
  ("`:release` - legacy GitHub Pages build (`docs/js/`), NON usato"),
  ed è prosa, non un link cliccabile.
- Path assoluti (forma `](/...)`): **zero** trovati in `/dev-docs/`.

### 4.5 Link dal `README.md` di root verso `/dev-docs/`

L'unico riferimento a `dev-docs/` da [README.md](README.md) è l'immagine
a [README.md:7](README.md#L7): `![Ridley Screenshot](dev-docs/screenshot.png)`.
Nessun link cliccabile da `README.md` a un singolo `.md` dentro
`/dev-docs/`.

[CLAUDE.md](CLAUDE.md) menziona Architecture, Roadmap, Examples, Spec
per nome (sotto "Documentation Maintenance" a
[CLAUDE.md:21-26](CLAUDE.md#L21-L26)) ma non li linka.

## 5. URL pubblici di documenti

- Il `README.md` non linka **nessun** documento specifico dentro
  `/dev-docs/`. L'unico contenuto di `/dev-docs/` referenziato è
  l'immagine `screenshot.png` a [README.md:7](README.md#L7).
- L'unico URL pubblico esterno è
  `https://vipenzo.github.io/Ridley/` a [README.md:5](README.md#L5),
  che porta alla webapp, non a documenti.
- Nessun "badge" è presente in `README.md`.
- Nessuna ricerca di `vipenzo.github.io` in `*.md`/`*.json`/`*.html`/`*.yml`
  trova altre occorrenze oltre quella di [README.md:5](README.md#L5).
- Non ci sono shortlink, redirect, o file `_redirects`/`vercel.json`/
  `netlify.toml` nel repo.
- Non vedo evidenze nel repository (commit message, contenuto file) di
  documenti specifici di `/dev-docs/` pubblicizzati su Reddit, Twitter,
  o blog esterni. I commit message recenti riguardano feature/fix
  tecnici. Non posso verificare ulteriormente senza ricerca esterna,
  che esula dalla ricognizione locale.

## 6. Altre osservazioni

### 6.1 Pipeline di build verso `/docs/` legacy ma non rimossa

Il vecchio percorso "build in `/docs/`, commit, Pages legge da branch"
è documentato dal codice ma non più usato:

- [package.json:11-12](package.json#L11-L12) ha ancora
  `"build:gh-pages": "npm run copy-static && shadow-cljs release release"`
  e `"copy-static": "rm -rf docs && mkdir -p docs && cp -r public/css public/scripts public/index.html docs/ && touch docs/.nojekyll"`.
  Nessuno script di CI o release lo invoca.
- [shadow-cljs.edn:14-19](shadow-cljs.edn#L14-L19) ha il build
  `:release` con `:output-dir "docs/js"` e commento "legacy, not used".
- Il bundle in `docs/js/main.js` ha `mtime` `Jan 17 22:27`, quindi è
  vecchio rispetto al codice attuale.

### 6.2 Convenzioni di naming non uniformi nei `.md`

Si trovano in coabitazione: `Architecture.md`/`Roadmap.md`/`Spec.md`
(PascalCase, ufficiali), `TASK_*.md` (UPPER_SNAKE), `Task_*.md`
(Title_Snake), `*-spec.md` (kebab), `architecture-recon.md` /
`transport-audit.md` (kebab), `ch05-...md` / `ch11-phase2-int1.md`
nelle interview (kebab numerato), `cosedafare.md` (italiano), e
`Architecture_1.md` (variante numerata, untracked).

### 6.3 File untracked rilevanti per la decisione di organizzazione

Da `git status` in prologo:

- `?? dev-docs/Architecture_1.md` (332 731 byte, ~7x Architecture.md
  attuale)
- `?? dev-docs/architecture-recon.md` (in realtà tracked, vedi `git ls-files`;
  il `??` qui è una imprecisione del prologo perché il file è tracked)
- `?? dev-docs/interviews/` (intera subdirectory di 12 file, non
  ancora committata)
- `?? dev-docs/manifold-backends-audit.md`
- `?? dev-docs/transport-audit.md`

(Verifica: alcuni di quelli che il prologo elenca come `??` sono in
realtà tracked secondo `git log`. Il prologo viene da uno snapshot
all'inizio della conversazione e potrebbe non riflettere lo stato
corrente al 100%.)

### 6.4 Nessun workflow di link-checking né di generazione HTML da MD

- Nessun workflow GitHub Actions valida link nei `.md`.
- Nessun tooling di generazione (mkdocs, docusaurus, mdbook, vitepress,
  jekyll) presente. `_config.yml` assente, `.nojekyll` presente solo in
  `/docs/`.
- Nessun `index.md` o pagina indice in `/dev-docs/`.

### 6.5 Posizione "ufficiale" dei documenti dichiarata

L'unica dichiarazione che ho trovato sulla posizione "canonica" dei
documenti di progetto è in [CLAUDE.md:18](CLAUDE.md#L18):
"Keep the following documents in `/dev-docs` up to date: Architecture.md,
Roadmap.md, Examples.md, Spec.md". Quindi `/dev-docs/` è dichiarato in
CLAUDE come la home dei documenti vivi, ma senza schema più fine
(nessuna distinzione fra "reference" e "working" / "archive").
