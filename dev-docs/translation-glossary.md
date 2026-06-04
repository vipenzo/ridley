# Glossario di traduzione IT → EN (guide narrative)

Scopo: tradurre le guide narrative del manuale dall'italiano all'inglese in modo coerente. In uso da 2026-06. Decisione di scope e priorità in `docs/manual-redesign-plan.md` (§14.4 backlog, §14.5 quaderno). Direzione: solo guide IT→EN; le schede Reference sono già in EN e non si traducono.

## Regole generali

- Si traduce solo la PROSA. Il codice degli esempi (variabili, keyword, nomi di funzione, blocchi `clojure`, i commenti `<!-- example-source -->`) è condiviso fra le due lingue e non si tocca mai.
- I termini tecnici già in inglese dentro la prosa italiana restano identici: shape, path, mesh, loft, extrude, revolve, shell, attach, register, anchor, e tutti i nomi di funzione e keyword.
- Seconda persona "you" (rende il "tu" e l'imperativo italiani).
- Composti inglesi col trattino lasciati identici: creation-pose, shape-fn, thickness-fn, woven-shell, heightmap, side-trip, quick-path, half-space.
- Invariati: manifold, watertight, slicer, voxel, SDF, blend, gyroid, PBR, STL, 3MF, font, metalness, roughness, mm.
- Niente em-dash, neanche in inglese.
- Tono concreto e sobrio, stesso registro dell'originale. Niente accademichese, niente calchi dall'italiano.
- Le frasi-template ("L'esempio mostra...", "Una nota:...") si rendono in modo naturale e idiomatico, non parola per parola.

## Termini IT → EN (parole italiane di dominio)

| Italiano (prosa) | Inglese |
|---|---|
| tartaruga | turtle |
| scheletro | skeleton |
| avvolgimento | winding |
| posa | pose |
| profilo | profile |
| piano | plane |
| spigolo | edge |
| faccia | face |
| vertice | vertex |
| parete | wall |
| apertura | opening |
| guscio | shell |
| rastremazione / rastremare | taper / to taper |
| torsione | twist |
| scanalatura | flute |
| solido di rivoluzione | solid of revolution |
| marcatore | marker |
| ancora (punto di aggancio) | anchor |
| maniglia (la creation-pose) | handle |
| sezione trasversale | cross-section |
| sezione (taglio, slice) | section |
| smusso | bevel |
| raccordo | fillet |
| giunzione (joint-mode) | joint |
| nastro | ribbon |
| nodo (SDF) | node |
| materializzare / materializzazione | materialize / materialization |
| gabbia | cage |
| contorno (esterno) | (outer) contour |
| glifo | glyph |
| carattere (tipografico) | character |
| rigo di scrittura | baseline |
| testo in rilievo | raised text / relief |
| testo inciso | engraved text |
| testo incassato | recessed text |
| maschera (binaria) | (binary) mask |
| piastra | plate |
| filamento | filament |
| colore | color |
| materiale | material |
| opacità | opacity |
| trasparenza | transparency |
| margine | margin |

## Note di contesto

- `smusso`→bevel e `raccordo`→fillet valgono per la PAROLA di prosa, non per i nomi di funzione: `chamfer`, `fillet`, `shell`, `blend`, `slats`, `bars`, `grid` restano invariati perché sono codice.
- Caso particolare SDF: "raccordo morbido" che descrive `sdf-blend` si rende "smooth blend" (non "smooth fillet"), perché lì il termine indica l'operazione blend, non il fillet sulle mesh.
- "rigo di scrittura" → "baseline" è il termine tipografico per il punto di partenza in basso a sinistra del testo; alternativa più letterale "writing line" se preferisci.

## Stato

Documento vivo. Il nucleo è validato (2026-06). I termini specifici dei capitoli non ancora tradotti in profondità (9 Librerie, 10 Analizzare e misurare, 11 Curve avanzate, 15 Debug, 16 Clojure per Ridley) si aggiungono qui man mano che emergono durante la traduzione.
