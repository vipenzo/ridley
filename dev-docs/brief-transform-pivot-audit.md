# Brief: audit del comportamento pivot di translate/scale/rotate su mesh

**Contesto.** Stiamo scrivendo il capitolo 2 del manuale ("Modellare per primitive") e dobbiamo spiegare come funzionano `translate`, `scale` e `rotate` su mesh. La tabella "Pivot conventions" nella Spec.md (sezione 9, "Top-level transforms") dice:

| Type | translate | scale around | rotate around |
|------|-----------|--------------|---------------|
| Mesh | world axes | mesh centroid | mesh centroid |
| SDF  | world axes | creation-pose | creation-pose |

Il comportamento osservato su `scale` per le mesh sembra contraddire la Spec: `scale` lavora sulla **creation-pose**, non su world axes passando per il centroid. In particolare:

```clojure
(scale (box 10 20 30) 1 2 3)
(scale (attach (box 10 20 30) (th 45)) 1 2 3)
```

Questi due producono lo stesso risultato (il box viene scalato lungo i propri assi locali, non lungo gli assi mondo). Il che indica che `scale` su mesh usa la creation-pose come frame di riferimento, esattamente come per SDF.

## Cosa serve

1. **`scale` su mesh.** Confermare che gli assi di scala sono quelli della creation-pose della mesh, non world axes. Il test decisivo:
   - `(scale (box 10 20 30) 1 2 3)` → box scalato lungo i propri assi → risultato equivalente a `(box 10 40 90)`
   - `(scale (attach (box 10 20 30) (th 45)) 1 2 3)` → stesso box, ruotato di 45° e poi scalato lungo i propri assi (non lungo world) → risultato equivalente a `(attach (box 10 40 90) (th 45))`, **non** un parallelepipedo deformato obliquamente
   - Verificare nel sorgente (`src/ridley/geometry/` o dove vive la dispatch di `scale` su mesh) quale frame viene usato per la matrice di scala.

2. **`rotate` su mesh.** Stesso dubbio. La Spec dice "mesh centroid" come pivot, ma:
   - Il pivot è il centroid geometrico o il punto della creation-pose?
   - Gli assi di rotazione (`:x`, `:y`, `:z`) sono world o creation-pose?
   - Test: `(rotate (attach (box 20) (f 30)) :z 45)` ruota attorno al centroid del box (che è a f 30) o attorno all'origine? E `:z` è lo Z mondo o lo Z della creation-pose?

3. **`translate` su mesh.** Qui la Spec dice "world axes" e sembra plausibile (dx/dy/dz assoluti non avrebbero senso in un frame locale). Confermare.

4. **Confronto mesh vs SDF.** Se il comportamento reale è che mesh e SDF usano entrambi la creation-pose per scale e rotate, la tabella nella Spec va unificata. Se ci sono differenze (es. mesh usa centroid, SDF usa creation-pose origin), documentare esattamente quale.

## Output atteso

- Per ciascuna delle tre funzioni: descrizione precisa di quale punto è il pivot e in quale frame agiscono gli assi. Con riferimento al codice sorgente.
- Se la Spec è da correggere: testo sostitutivo per la tabella e per i paragrafi circostanti nella sezione "Top-level transforms".
- Se ci sono edge case (mesh senza creation-pose? mesh prodotte da boolean, il cui centroid e la cui creation-pose divergono?), documentarli.

## Perché serve adesso

La sezione 2.6 del manuale ("Per chi viene da un CAD tradizionale") deve spiegare `translate`, `rotate`, `scale` come alternativa a `attach` + turtle. Non possiamo scriverla con la Spec che potrebbe essere sbagliata su questo punto.
