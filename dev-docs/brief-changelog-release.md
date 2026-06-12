# Brief — Changelog 1.10.0 → 3.0.0 per l'annuncio di release

**Per:** Code
**Da:** Vincenzo + Claude
**Data:** 2026-06-12
**Contesto:** Vincenzo sta per rilasciare la 3.0.0 e annunciarla su r/RidleyCAD. L'ultima versione annunciata lì è la 1.10.0. Serve la ricostruzione autoritativa del delta dalla storia git; Claude la userà come base per l'annuncio (narrativo, non un dump del log).

## Lavoro richiesto

1. Individuare il commit/tag della 1.10.0 (`git tag` / `git log`; se la tag non esiste, usare il commit del bump di versione in `package.json`).
2. Produrre `dev-docs/changelog-1.10-to-3.0.md` con i cambiamenti dal quel punto a HEAD, **categorizzati e in linguaggio utente** (cosa può fare ora l'utente che prima non poteva), non commit-per-commit. Categorie suggerite, adattale a ciò che trovi:
   - **Piattaforma** (versione desktop/Tauri, distribuzione, performance)
   - **Workspace e organizzazione** (sistema Workspace, librerie per-workspace, ...)
   - **Modellazione** (nuove funzioni del DSL: embroid, capped, lay-flat, slice-mesh :on, path-to-shape, reflect/reverse/mirror-path, bezier-to :local, add-mark, ruler :at, propagazione anchor nelle booleane, ...)
   - **Interattività** (edit-bezier e stato degli evaluator modali, picking, ...)
   - **Accessibilità** (i comandi della 1.9/1.10 se rientrano nel range o se mai annunciati)
   - **Manuale e documentazione** (manuale bilingue, how-to-read, livelli, Reference, tooltip)
   - **Fix rilevanti** (solo quelli che un utente noterebbe)
3. Per ogni voce: una riga, eventualmente con il nome della funzione. Marcare con ⭐ le 5-8 voci che a tuo giudizio sono le più significative per un annuncio.
4. Segnalare separatamente eventuali **breaking change** fra 1.10.0 e 3.0.0 (il salto di due major lo suggerisce): vanno dichiarati nell'annuncio.
5. In coda al file: il conteggio grezzo (numero di commit, intervallo di date) come dato di colore.

## Fuori scope

La scrittura dell'annuncio (Claude) e la pubblicazione su GitHub Releases (Vincenzo deciderà il formato a valle).
