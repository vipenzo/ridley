# Brief: chiarire il comportamento di shell :style :lattice

## Contesto

Durante la revisione del manuale, l'esempio di `shell` con `:style :lattice` produce un risultato diverso da quello che la descrizione suggerisce.

La descrizione nel manuale dice: "`:lattice` produce una griglia regolare di aperture". Ma l'output visivo sono una serie di tacche scollegate tra loro — sembra il *negativo* di una griglia: la parte solida è dove dovrebbero essere le aperture, e le aperture sono dove dovrebbe essere la griglia.

## Esempio

```clojure
(register vase
  (loft-n 512 (shell (circle 15 512) :thickness 2 :style :lattice :openings 8 :rows 12)
    (f 60)))
```

## Domanda

1. Il comportamento attuale è intenzionale? Cioè: `:lattice` produce i "pilastri" (materia tra le aperture) e l'utente deve usarlo come cutter dentro una `mesh-difference` con un cilindro pieno per ottenere il guscio con aperture?

2. Oppure è invertito e dovrebbe produrre un guscio continuo con aperture regolari (come fa `:voronoi` che produce un guscio con celle vuote)?

## Cosa ci serve

- Una risposta chiara su quale sia il design intent.
- Se il comportamento è intenzionale: un esempio d'uso corretto che mostri il pattern completo (es. cilindro pieno + lattice come cutter).
- Se è un bug: una fix o una segnalazione per fix futura.

La risposta determina come scriviamo la descrizione nel manuale.
