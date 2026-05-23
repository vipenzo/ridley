# Brief per Code: schede Reference — Batch 4 (Path + Mesh Operations)

## Obiettivo

Produrre le schede Reference per due categorie:
- §3 Path
- §7 Mesh Operations

## Riferimenti

Stessi dei batch precedenti: `loft.md` come modello, Spec.md come source-of-truth, `Readme.md` per convenzioni.

## Lista funzioni

### Path (`path`)

1. `path` — macro, registra comandi turtle come dato
2. `mark` — tag una posa dentro un path
3. `follow` — splice di un path in un altro
4. `side-trip` — sub-path scoped che non avanza la spina
5. `quick-path` / `qp` (scheda unica, `qp` come alias)
6. `poly-path` — path da coordinate
7. `poly-path-closed` — path chiuso da coordinate (stub che rimanda a `poly-path` o scheda unica)
8. `follow-path` — esegue un path sulla turtle
9. `anchors` — mappa mark→pose di un path
10. `move-to` — snap della turtle alla posa di un mark/anchor
11. `path-to` — path dalla posizione corrente a un target (se esiste in Spec)
12. `mark-pos` / `mark-x` / `mark-y` (scheda unica `mark-query.md`)
13. `bounds-2d` — bounding box 2D di un path
14. `subpath-y` — estrae porzione di path per altezza
15. `offset-x` — shift orizzontale di un path
16. `path?` — predicato
17. `with-path` (se esiste come alias/wrapper di `path`)

Nota: `fit` è già nel batch 1 (2D shapes). `path-to-shape` e `stroke-shape` idem. Non rifare.

### Mesh Operations (`mesh-operations`)

18. `mesh-union` — unione booleana
19. `mesh-difference` — differenza booleana
20. `mesh-intersection` — intersezione booleana
21. `mesh-hull` — convex hull 3D
22. `concat-meshes` — concatenazione senza booleana
23. `mesh-smooth` — smoothing tangent-based (Manifold)
24. `mesh-refine` — subdivision senza smoothing
25. `attach` — posiziona mesh via comandi turtle
26. `attach!` — attach in-place su mesh registrata
27. `attach-face` — estrusione/inset da una faccia
28. `clone-face` — clona una faccia come mesh separata
29. `inset-face` — inset di una faccia
30. `scale-face` — scala una faccia
31. `chamfer` — smusso 3D per direzione
32. `fillet` — raccordo 3D per direzione
33. `chamfer-edges` — smusso 3D lower-level
34. `chamfer-prisms` — prisms diagnostici per chamfer
35. `find-sharp-edges` — trova spigoli vivi
36. `mesh-diagnose` — diagnostica mesh
37. `lay-flat` — orienta mesh per stampa
38. `decode-mesh` — import mesh da buffer
39. `translate` — trasla (polimorfico mesh/SDF/shape, scheda per il comportamento mesh; la scheda shape è già nel batch 1)
40. `scale` — scala (polimorfico, scheda per mesh/SDF)
41. `rotate` — ruota (polimorfico, scheda per mesh/SDF)
42. `reset-creation-pose` — riancora il pivot
43. `transform` — path-driven transform su mesh
44. `mesh?` — predicato (se esiste)

Nota: `slice-mesh`, `project-mesh`, `slice-at-plane` sono in §7 ma li abbiamo già trattati nel cap. 3 come "generare shape da mesh". Documentarli come schede in questo batch (categoria `mesh-operations`).

45. `slice-mesh`
46. `slice-at-plane`
47. `project-mesh`

### Schede per operazioni `cp-*`

48. `cp-f` / `cp-rt` / `cp-u` (scheda unica `cp-position.md`)
49. `cp-th` / `cp-tv` / `cp-tr` (scheda unica `cp-rotation.md`)

## Formato

Identico ai batch precedenti. Lingua inglese.

## Convenzioni specifiche

1. **`attach`**: scheda ricca. Documentare tutti i comandi disponibili nel body (movimento, rotazione, curve, attach-specific, cp-*). Tabella come nella Spec. Documentare che `scale` è rifiutato nel body.
2. **`move-to`**: documentare le varie forme (keyword, mesh, path + `:at` + `:align`). Scheda ricca.
3. **`mesh-smooth`**: documentare il vincolo manifold. Includere la nota sui mesh perforati (shell voronoi ecc.) e le quattro alternative.
4. **`translate`/`scale`/`rotate`**: queste sono le schede polimorfiche. Documentare il comportamento su mesh, SDF, e shape (con rimando alle schede `*-shape` del batch 1). Documentare la tabella pivot (mesh: creation-pose + world axes; SDF: creation-pose + world axes; shape: centroid/origin + local axes). Le schede `scale-shape`/`rotate-shape`/`translate-shape` del batch 1 hanno placeholder "scheda futura": ora possiamo popolare il rimando.
5. **`cp-*`**: due schede (posizione e rotazione), con la spiegazione "muovi la geometria sotto un'ancora ferma".
6. **See also → Guide**: `path` → cap. 5 (placeholder); `mesh-operations` → cap. 7 (placeholder); `attach` → cap. 8 (placeholder).

## Output atteso

- ~49 schede `.md` in `dev-docs/reference-manual/`
- Aggiornamento di `PROGRESS.md`
- Aggiornamento dei placeholder nelle schede `scale-shape.md`, `rotate-shape.md`, `translate-shape.md` del batch 1 (rimando alla scheda polimorfica)
