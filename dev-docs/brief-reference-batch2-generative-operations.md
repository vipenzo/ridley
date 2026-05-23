# Brief per Code: schede Reference — Batch 2 (Generative Operations)

## Obiettivo

Produrre le schede Reference per tutte le funzioni della sezione §6 (Generative Operations) di Spec.md. Secondo batch; il formato è validato dal Batch 1 (2D Shapes).

## Riferimenti

- **Formato**: `dev-docs/reference-manual/loft.md` (scheda modello, già nella cartella).
- **Convenzioni cartella**: `dev-docs/reference-manual/Readme.md`.
- **Source-of-truth per contenuto tecnico**: `docs/Spec.md`, sezione §6 (Generative Operations).

## Lista funzioni da documentare

Ogni funzione diventa un file `.md` in `dev-docs/reference-manual/`. Il frontmatter `category` per tutte è `generative-operations`.

### Operazioni generative

1. `extrude` — estrusione lungo path
2. `extrude-closed` — estrusione chiusa (toro)
3. `extrude-z` / `extrude-y` — estrusione asse-allineata (scheda unica `extrude-axis.md`)
4. `loft` — **scheda già esistente** (`loft.md`), non rifare. Verificare che sia allineata alla Spec corrente; se serve un aggiornamento, segnalarlo in PROGRESS.md.
5. `loft-n` — loft con step count esplicito (stub che rimanda a `loft`)
6. `loft-between` — alias two-shape mode (stub che rimanda a `loft`)
7. `bloft` — bezier-safe loft
8. `bloft-n` — bloft con step count esplicito (stub che rimanda a `bloft`)
9. `revolve` — rivoluzione
9a. `extrude+` — extrude chainable che ritorna `{:mesh :end-face}`
9b. `revolve+` — revolve chainable che ritorna `{:mesh :end-face}`
9c. `transform->` — macro di chaining per `extrude+`/`revolve+`

### Shape-fn

10. `shape-fn` — costruttore custom shape-fn
11. `shape-fn?` — predicato
12. `tapered`
13. `twisted`
14. `fluted`
15. `rugged`
16. `noisy`
17. `displaced`
18. `morphed`
19. `profile`
20. `heightmap`
21. `heightmap-to-mesh`
21a. `mesh-to-heightmap` — companion di `heightmap-to-mesh`
21b. `sample-heightmap` — sampling bilineare di un heightmap
21c. `weave-heightmap` — generatore analitico di heightmap "weave"
21d. `noise` — 2D continuous noise
21e. `fbm` — fractal Brownian motion
22. `woven`
23. `shell`
24. `woven-shell`
25. `capped`

### Utility correlate

26. `joint-mode` — modalità giunzione (`:flat`, `:round`, `:tapered`)

### Helper shape-fn

27. `angle` — angolo 2D di un punto dall'origine
28. `displace-radial` — displacement radiale

## Formato della scheda

Stesso del Batch 1. Sezioni: Signature, Description, Parameters, Example, Variations (se utile), Notes, See also. Lingua inglese. Esempi con shortcode `{{example: ...}}` e blocco `<!-- example-source -->`.

## Convenzioni specifiche di questo batch

1. **`loft.md` esiste già.** Non ricrearla. Rileggere e verificare allineamento con Spec corrente. Se è allineata, spuntarla in PROGRESS.md e basta. Se servono aggiornamenti, segnalarli come diff in PROGRESS.md e attendere validazione.
2. **Stub per varianti.** `loft-n`, `loft-between`, `bloft-n` sono schede brevi che documentano la signature, una riga di descrizione, e rimandano alla scheda madre (`loft` o `bloft`). Seguire il pattern "scheda madre + stub" stabilito nel piano (§4 del manual-redesign-plan.md).
3. **Shape-fn come categoria.** Ogni shape-fn built-in ha la sua scheda. La Description spiega cosa fa, i Parameters documentano le opzioni keyword, l'Example mostra un `loft` che la usa. Nella sezione Notes, indicare sempre che le shape-fn si compongono con `->`.
4. **`shell` e `woven-shell` sono complesse.** Hanno molte opzioni (stili, cap, fn custom). Documentare tutte le opzioni in una tabella Parameters, con una Variation per ogni stile principale. Non lesinare: queste schede saranno lunghe, ed è giusto così.
5. **`capped`** è un wrapper per loft che arrotonda le estremità. Documentare che lavora con `loft` (non con `extrude`), e che la `fraction` è auto-calcolata.
6. **`joint-mode`** modifica lo stato della turtle (non è una funzione pura). Segnalarlo in Description.
7. **See also → Guide**: per le operazioni generative, rimandare come placeholder a cap. 4 (Estrusione) e cap. 6 (Da funzioni matematiche a forme). Per le shape-fn, cap. 6.

## Output atteso

- ~28 file `.md` nuovi in `dev-docs/reference-manual/`
- Aggiornamento di `PROGRESS.md` con checklist Batch 2
- Nota su `loft.md` se serve aggiornamento
