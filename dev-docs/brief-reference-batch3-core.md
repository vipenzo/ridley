# Brief per Code: Batch 3 + verifica asse revolve

## Parte A: Verifica asse di rivoluzione di `revolve`

### Problema

La Spec §6 (Revolve) dice: "Full 360 deg revolution around turtle heading" e "2D X = radial distance from axis (perpendicular to heading), 2D Y = position along axis (in heading direction)".

Un test manuale suggerisce che l'asse di rivoluzione sia l'asse **up** della tartaruga, non il heading. Se confermato, la Spec è sbagliata.

### Richiesta

1. Scrivere un test diagnostico che verifichi l'asse effettivo di `revolve`. Suggerimento: creare una shape asimmetrica (es. `(poly 5 0  10 0  10 5  5 5)` — un quadratino a X positivo), fare `revolve` con la tartaruga nella posa di default (heading +X, up +Z), e ispezionare la mesh risultante per capire attorno a quale asse è avvenuta la rotazione (se attorno a heading +X, la mesh si sviluppa in YZ; se attorno a up +Z, si sviluppa in XY).
2. Se l'asse è up (non heading): correggere la Spec §6, sezione Revolve. Aggiornare sia la descrizione testuale sia le note sull'interpretazione delle coordinate del profilo.
3. Se l'asse è heading: segnalare il risultato. Il manuale si adeguerà alla Spec.
4. Verificare anche che la scheda Reference `revolve.md` appena prodotta sia coerente con il risultato della verifica. Correggere se necessario.

---

## Parte B: Schede Reference — Batch 3

### Obiettivo

Produrre le schede Reference per tre categorie trasversali:
- §1 Registration & Visibility
- §2 Turtle Movement
- §5 3D Primitives

### Riferimenti

Stessi del batch 1 e 2: `loft.md` come modello, Spec.md come source-of-truth, `Readme.md` per convenzioni.

### Lista funzioni

#### Registration & Visibility (`registration-visibility`)

1. `register` (incluso alias `r`)
2. `show` / `hide` (scheda unica)
3. `show-all` / `hide-all` (scheda unica)
4. `show-only-objects`
5. `objects` / `registered` / `scene` (scheda unica `registry-query.md`)
6. `panel` — costruttore
7. `out` / `append` / `clear` (scheda unica `panel-io.md`)
8. `panel?`
9. `fit-camera`
10. `show-lines` / `hide-lines` / `lines-visible?` (scheda unica)

#### Turtle Movement (`turtle-movement`)

11. `f` — avanti/indietro
12. `u` / `d` — su/giù (scheda unica)
13. `rt` / `lt` — destra/sinistra (scheda unica)
14. `th` — yaw
15. `tv` — pitch
16. `tr` — roll
17. `arc-h` — arco orizzontale
18. `arc-v` — arco verticale
19. `bezier-to`
20. `bezier-to-anchor`
21. `bezier-as`
22. `pen` / `pen-up` / `pen-down` (scheda unica)
23. `reset`
24. `resolution`
25. `turtle` — scope isolato
26. `set-heading` (se esiste in Spec)

#### 3D Primitives (`3d-primitives`)

27. `box`
28. `cyl`
29. `cone`
30. `sphere`

### Formato

Identico ai batch precedenti. Lingua inglese. Shortcode `{{example: ...}}` con blocco `<!-- example-source -->`.

### Convenzioni specifiche

1. **`register`**: documentare il polimorfismo (mesh, path, shape, panel, value). Documentare il flag `:hidden`. Documentare l'alias `r`.
2. **`show`/`hide`**: accettano keyword e reference. Documentare entrambe le forme.
3. **`turtle`**: documentare le opzioni (`:reset`, `:preserve-up`, override di posizione/heading/up). Questa è una scheda ricca.
4. **`resolution`**: documentare le tre forme (`:n`, `:a`, `:s`), la lista delle operazioni influenzate, e il fatto che `loft`/`extrude` non ne sono influenzati.
5. **Primitive 3D**: documentare l'orientamento (asse di sviluppo = heading, sezione nel piano destra-alto). Documentare i semantic face ID (`:top`, `:bottom`, ecc.) con rimando al cap. 8 (Faces).
6. **See also → Guide**: `registration-visibility` → cap. 1 (placeholder); `turtle-movement` → cap. 1 (placeholder); `3d-primitives` → cap. 2.

### Output atteso

- ~30 schede `.md` in `dev-docs/reference-manual/`
- Aggiornamento di `PROGRESS.md`
- Risultato della verifica asse revolve (con eventuale correzione Spec e `revolve.md`)
