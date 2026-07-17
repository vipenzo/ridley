# Nota per Code — registrare il capitolo 18 delle guide

Scritti (2026-07-16, Claude/documentazione, approvati da Vincenzo):

- `docs/manual/guides/it/18-acquisire-e-sostituire.md` (nuovo)
- `docs/manual/guides/en/18-acquisire-e-sostituire.md` (nuovo)
- `docs/manual/guides/it/07-mesh.md` — nuova sezione 7.8 "Decomporre una mesh:
  mesh-split" in coda
- `docs/manual/guides/en/07-mesh.md` — idem, "7.8 Decomposing a mesh"

Serve da parte tua (è codice, non lo tocco io):

1. **Entry in `src/ridley/manual/structure.cljs`**, dopo `:ch-17`:

   ```clojure
   {:id :ch-18 :slug "acquisire-e-sostituire" :order 18
    :file "18-acquisire-e-sostituire.md"
    :langs #{:it :en}
    :title {:it "18. Acquisire e sostituire" :en "18. Acquiring and replacing"}}
   ```

2. **Conflitto di numerazione, deciso con Vincenzo**: il redesign plan riservava
   il 18 a "Estendere Ridley" (mai scritto, "espansione differita") — slitta a
   19. Aggiornare `docs/manual-redesign-plan.md` (§ elenco capitoli) e la nota
   in `how-to-read` ("senza il cap. 18" non è più vero).

3. **Verifica di coerenza contenuti** (ho documentato il working tree, che è in
   movimento): la 7.8 e il cap. 18 assumono la spec ad albero di `mesh-split`
   (mappa mark→sub-spec), `split-tree`/`split-parts`, l'editor senza gesto
   separa, e le viste inset di mesh-board con fedeltà stampata nel pannello
   (fix capture-println). Se qualcosa di questo cambia prima del commit,
   toccare anche le guide.

4. NON documentati di proposito (instabili): drag/zoom delle finestrelle
   (iterazione 2 di brief-mesh-board-views), `:heal-slivers` come opt DSL,
   canale scanner.
