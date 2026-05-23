# Brief per Code: schede Reference — Batch 1 (2D Shapes)

## Obiettivo

Produrre le schede Reference per tutte le funzioni della sezione §4 (2D Shapes) di Spec.md. Questo è il primo batch; i successivi verranno commissionati dopo revisione.

## Riferimenti

- **Formato**: seguire esattamente la struttura di `dev-docs/reference-manual/loft.md` (scheda modello).
- **Convenzioni cartella**: leggere `dev-docs/reference-manual/Readme.md`.
- **Source-of-truth per contenuto tecnico**: `docs/Spec.md`, sezione §4 (2D Shapes). In caso di dubbio su signature, parametri, comportamento: Spec vince.

## Lista funzioni da documentare

Ogni funzione diventa un file `.md` in `dev-docs/reference-manual/`. Il frontmatter `category` per tutte è `2d-shapes`.

### Costruttori

1. `circle` — cerchio (1 o 2 argomenti)
2. `rect` — rettangolo
3. `polygon` — poligono regolare
4. `poly` — poligono da coordinate
5. `star` — stella
6. `shape` — contorno da tartaruga 2D
7. `stroke-shape` — contorno da path con spessore
8. `path-to-shape` — conversione path 3D → shape 2D
9. `svg-shapes` — import da SVG (+ `svg-shape` come variante single-element)
9b. `make-shape` — costruttore low-level da mappa `{:points ... :holes ...}`

### Booleane 2D

10. `shape-union`
11. `shape-difference`
12. `shape-intersection`
13. `shape-xor`

### Modificatori

14. `shape-offset` — expand/contract
15. `fillet-shape` — raccordi
16. `chamfer-shape` — smussi
17. `shape-hull` — convex hull 2D
17b. `shape-bridge` — bridge tra N shapes via offset → union → unoffset

### Trasformazioni

18. `scale-shape` (alias type-specific di `scale` su shape)
19. `rotate-shape` (alias type-specific di `rotate` su shape)
20. `translate-shape` (alias type-specific di `translate` su shape)
21. `reverse-shape` — inversione winding
22. `morph-shape` — interpolazione tra shape
23. `resample-shape` — ricampionamento punti
24. `fit` — scala a dimensione target (funziona su shape e path)

### Pattern

25. `pattern-tile` — pattern tiling con sottrazione
26. `voronoi-shell` — shape perforata Voronoi

### Visualizzazione

27. `stamp` — preview 2D al turtle pose
28. `show-stamps` / `hide-stamps` / `stamps-visible?` — visibility toggle (scheda unica)

### Predicato

29. `shape?`

### Schede alias/stub

Per le trasformazioni polimorfiche (`scale`, `rotate`, `translate` su shape), le schede `scale-shape`, `rotate-shape`, `translate-shape` documentano il comportamento specifico 2D e rimandano alla scheda principale polimorfica (che sarà in un batch futuro). Nella sezione See also, indicare il rimando come placeholder: `→ scale (polymorphic, scheda futura)`.

## Formato della scheda

```markdown
---
name: <function-name>
category: 2d-shapes
since: ""
status: stable
---

# <function-name>

## Signature

Tutte le arity, una per riga, in backtick.

## Description

Prosa breve in inglese. Cosa fa, cosa restituisce, se modifica lo stato della turtle.

## Parameters

Lista dei parametri con tipo e descrizione breve.

## Example

{{example: <function-name>-basic}}

<!-- example-source: <function-name>-basic -->
```clojure
;; codice dell'esempio
```
<!-- /example-source -->

Prosa breve che spiega l'esempio.

## Variations

Esempi aggiuntivi con prosa (se la funzione ha usi diversi). Omettere la sezione se non ci sono variazioni significative.

## Notes

Bullet con avvertenze, performance, gotcha. Omettere se non ci sono note rilevanti.

## See also

- **Guide:** placeholder → cap. 3 (Lavorare con le forme 2D)
- **Related:** funzioni correlate della stessa categoria o di altre categorie
```

## Convenzioni

1. **Lingua: inglese.**
2. **Nome file** = nome funzione. Conversioni filesystem: `?` → `-p` (es. `shape-p.md`), `!` → `-bang`.
3. **Varianti di arity** nella stessa scheda, non schede separate.
4. **Alias**: `svg-shape` è una variante di `svg-shapes` → documentare nella stessa scheda, con una riga in Signature e un paragrafo in Description.
5. **`show-stamps` / `hide-stamps` / `stamps-visible?`**: scheda unica `stamp-visibility.md`, con tutte e tre in Signature.
6. **Esempi**: minimali, autocontenuti, illustrano l'uso primario. Ogni esempio ha uno shortcode `{{example: ...}}` e un blocco `<!-- example-source -->` con il codice. L'esempio deve essere eseguibile da solo in Ridley.
7. **See also → Guide**: per questo batch, tutti rimandano al cap. 3 come placeholder. I link precisi verranno aggiornati quando i capitoli saranno finalizzati.
8. **Status**: `stable` per tutto, salvo indicazione diversa dalla Spec.

## Output atteso

- 29 file `.md` (circa) in `dev-docs/reference-manual/`
- Aggiornamento di `dev-docs/reference-manual/PROGRESS.md` con checklist del batch

## Note per Code

- Non inventare comportamenti: se la Spec non dice qualcosa, non documentarlo. In caso di dubbio, lasciare un commento `<!-- TODO: verificare -->` nella scheda.
- Non documentare le shape-fn (`tapered`, `twisted`, `shell`, `woven-shell`, ecc.): appartengono alla categoria `generative-operations` e saranno in un batch futuro.
- `capped` appartiene a `generative-operations` (è un wrapper per loft), non a questo batch.
- `fit` opera sia su shape che su path: documentare entrambi gli usi nella stessa scheda, con la nota che il comportamento è polimorfico.
