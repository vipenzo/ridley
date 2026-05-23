# Brief per Code: schede Reference — Batch 5 (Faces, SDF, Text, Warp)

## Obiettivo

Produrre le schede Reference per quattro categorie:
- §8 Faces
- §11 SDF Modeling
- §12 Text
- §10 Spatial Deformation (warp)

Quinto batch; il formato è validato dai batch 1–4.

## Riferimenti

- **Formato**: `dev-docs/reference-manual/loft.md` (scheda modello).
- **Convenzioni cartella**: `dev-docs/reference-manual/Readme.md`.
- **Source-of-truth**: `docs/Spec.md` per signature, parametri, comportamento. Dove la scheda diverge dalla Spec, la Spec vince.
- **Lingua**: inglese (EN è source-of-truth per la Reference, §5.1 del piano).
- **Destinazione**: `dev-docs/reference-manual/` (flat, un file `.md` per scheda).

## Convenzioni dai batch precedenti

- Una scheda = un file monolingua (EN).
- Pattern "scheda madre + stub" per varianti di arity (es. `sdf-blend` madre, `sdf-blend-difference` scheda propria perché semantica distinta).
- Slug stabile nel campo `category` del frontmatter (usare gli slug esatti di Spec.md).
- Modi alternativi presentati senza etichetta peggiorativa.
- Niente wrapping forzato delle righe: ogni paragrafo è una singola riga.
- Esempi autocontenuti (ogni esempio definisce inline tutto ciò che usa).

## Lista funzioni

### Faces (`faces`)

1. `list-faces` — lista tutte le facce con metadata
2. `face-ids` — lista solo gli id delle facce
3. `get-face` — info base di una faccia per id
4. `face-info` — info dettagliata (area, edges, vertex positions)
5. `find-faces` — selezione per direzione geometrica + `:threshold` + `:where`
6. `face-at` — faccia il cui piano passa più vicino a un punto
7. `face-nearest` — faccia il cui centroide è più vicino a un punto
8. `largest-face` — faccia più grande (opzionalmente per direzione)
9. `auto-face-groups` — raggruppamento per adiacenza coplanare
10. `ensure-face-groups` — aggiunge `:face-groups` se mancanti
11. `face-shape` — estrae il contorno di una faccia come 2D shape + pose
12. `highlight-face` — evidenziazione permanente di una faccia
13. `flash-face` — evidenziazione temporanea
14. `clear-highlights` — rimuove tutte le evidenziazioni
15. `attach-face` — muove i vertici di una faccia (f, inset, scale dentro il body)
16. `clone-face` — estrude una faccia creando nuova geometria
17. `distance` — distanza tra mesh, facce, punti (polimorfica)
18. `area` — area di una faccia
19. `ruler` — ruler visuale (stesse forme di distance)
20. `clear-rulers` — rimuove tutti i ruler

**Nota**: `distance`, `area`, `ruler`, `clear-rulers` sono nella sezione Measurement di §8 della Spec. Collocarli nella category `faces` del frontmatter. Se preferisci una category separata `measurement`, va bene uguale — l'importante è la coerenza interna al batch.

### SDF Modeling (`sdf-modeling`)

#### Primitives

21. `sdf-sphere` — sfera SDF
22. `sdf-box` — box SDF
23. `sdf-cyl` — cilindro SDF
24. `sdf-rounded-box` — box con angoli arrotondati (true SDF)
25. `sdf-torus` — toro SDF

#### Booleans

26. `sdf-union`
27. `sdf-difference`
28. `sdf-intersection`

#### SDF-specific operations

29. `sdf-blend` — smooth union con raggio di blend k
30. `sdf-blend-difference` — smooth subtraction
31. `sdf-half-space` — semispazio definito dalla posa turtle. Documentare il default (mantiene il lato dietro l'heading) e la variante `:cut-ahead`
32. `sdf-clip` — shortcut per `(sdf-intersection shape (sdf-half-space))`
33. `sdf-shell` — guscio cavo
34. `sdf-offset` — espandi/contrai superficie. **Nota nella scheda**: produce un non-SDF lontano dalla superficie; preferire `sdf-rounded-box` per box arrotondati
35. `sdf-morph` — interpolazione tra due SDF
36. `sdf-displace` — displacement via formula quotata

#### Materialization

37. `sdf->mesh` — conversione esplicita SDF→mesh
38. `sdf-ensure-mesh` — coerce condizionale (mesh passthrough, SDF materializzato). Documentare le 4 arity
39. `sdf-resolution!` — imposta la resolution globale per auto-meshing
40. `sdf-node?` — predicato: è un nodo SDF?

#### Formulas

41. `sdf-formula` — compila espressione Clojure quotata in un nodo SDF. Documentare le variabili disponibili (x, y, z, r, rho, theta, phi) e il gotcha `pow` con basi negative
42. `sdf-revolve` — revolve di un SDF 2D attorno a Z

#### TPMS

43. `sdf-gyroid`
44. `sdf-schwarz-p`
45. `sdf-diamond`

#### Periodic patterns

46. `sdf-slats` — muri piatti paralleli infiniti
47. `sdf-bars` — barre cilindriche parallele infinite
48. `sdf-bar-cage` — gabbia di barre allineate a un box
49. `sdf-grid` — reticolo 3D. Documentare il warning sul blend (non è un true SDF)

### Text (`text`)

50. `text-shape` — stringa → vettore di 2D shapes. Documentare la tabella contorni/glifi compositi
51. `text-shapes` — stringa → vettore di shapes (una per carattere, no composite handling)
52. `char-shape` — singolo carattere → shape
53. `extrude-text` — shortcut text-shape + extrude in un solo call
54. `text-width` — larghezza orizzontale di una stringa a una data size
55. `load-font!` — carica font custom (path o keyword built-in)
56. `font-loaded?` — predicato: font default caricato?
57. `text-on-path` — testo 3D lungo un path curvo. Documentare le opzioni `:align`, `:overflow`, `:spacing`

### Spatial Deformation / Warp (`spatial-deformation`)

58. `warp` — deformazione mesh dentro un volume. Documentare la signature del deform-fn e l'opzione `:subdivide`
59. `inflate` — preset: push vertici verso l'esterno lungo le normali
60. `dent` — preset: push verso l'interno
61. `attract` — preset: pull verso il centro del volume
62. `twist` — preset: rotazione attorno a un asse
63. `squash` — preset: appiattimento verso un piano
64. `roughen` — preset: displacement noise lungo le normali
65. `smooth-falloff` — helper esportato come binding standalone, scheda corta (signature + esempio + nota su uso nei custom deform-fn)

**Totale: ~63 schede da scrivere** (escluse `attach-face.md` e `clone-face.md` che esistono già dal batch 4 → solo audit + integrazione).

## Decisioni per questo batch

### Faces: attach-face e clone-face

Le operazioni disponibili nel body di `attach-face`/`clone-face` sono `f`, `inset`, `scale`. Non sono funzioni standalone — sono comandi di un micro-DSL face. Documentarli **nella scheda madre** (`attach-face.md` e `clone-face.md`), non come schede separate.

**Aggiornamento post-audit (2026-05-20)**: `attach-face.md` e `clone-face.md` esistono già dal batch 4. In questo batch eseguire solo **audit + integrazione mirata** (verifica signature contro Spec, aggiungere riferimenti a `find-faces` / `face-at` se mancanti, sistemare divergenze). NB: `inset` è anche un binding SCI top-level, ma resta documentato solo nelle schede madre per evitare rumore.

### SDF: attach su SDF

La Spec documenta estesamente il comportamento di `attach` con SDF (tabella comandi, comportamento incrementale, mark su SDF). `attach` ha già una scheda dal batch 4. **Non** duplicarla qui. Nella scheda `sdf-sphere` (o in una nota generale "SDF primitives" se preferisci) aggiungere un rimando: "SDF nodes support `attach` — see `attach.md`".

### SDF: translate/scale/rotate su SDF

Le transform polimorfiche hanno già schede dal batch 4 (`translate.md`, `scale.md`, `rotate.md`). Non duplicare. Nei "See also" delle schede SDF rimandare alle schede polimorfiche.

### SDF: sdf-resolution!

Non è una funzione con return value nel senso classico; è un side-effect globale. Fare comunque una scheda (signature + description + note su interazione con auto-meshing).

### Text: text-shape vs text-shapes

Sono funzioni distinte (non varianti di arity): `text-shape` gestisce glifi compositi, `text-shapes` no. Schede separate.

### Text: extrude-text return type

`extrude-text` ritorna un **vettore di mesh** (uno per carattere), non un singolo mesh. La scheda deve dichiararlo nella signature e nelle Notes per non ingannare l'utente.

### Warp: i preset sono schede separate

`inflate`, `dent`, `attract`, `twist`, `squash`, `roughen` sono funzioni che restituiscono un deform-fn. Ognuna ha una scheda propria (corta — signature + 1 paragrafo + esempio). La scheda madre `warp.md` le linka tutte.

### Measurement

`distance`, `ruler`, `area`, `clear-rulers` appaiono nella Spec sotto §8 (Faces, sezione Measurement). Metterle nel frontmatter con `category: 8. Faces` oppure, se preferisci, `category: 8. Measurement`. L'importante è la coerenza: scegli una e tienila per tutte e quattro.

## Schede mancanti note

- `goto` e `look-at` non hanno ancora scheda. Sono nella sezione §2 (Turtle, Anchors & Navigation) e andranno nel batch successivo (o in un mini-batch Turtle).
- `with-path` (§2) — stessa situazione.
- Le funzioni §9 Positioning & Assembly non coperte nel batch 4 (hierarchical assemblies, `link!`, `attach-path`) andranno in un batch successivo.

## Ordine di lavoro suggerito

1. Faces (20 schede) — categoria più contenuta, riscaldamento
2. Warp (7 schede) — brevissimo
3. Text (8 schede) — medio
4. SDF (29 schede) — la più corposa, lasciarla per ultima

## Test di verifica

Per ogni scheda:
- Il campo `name` del frontmatter corrisponde al simbolo Ridley.
- La `category` è coerente con la sezione Spec.
- La signature è verificata contro Spec.md.
- L'esempio è autocontenuto (copiabile nella REPL senza errori).
- I "See also" linkano a schede esistenti (controllare che i nomi file siano corretti).
