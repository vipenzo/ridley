# Addendum a brief-stretch-material-frame.md: code di verifica da chiudere

Data: 2026-07-09. Da eseguire alla prima sessione utile, prima di qualsiasi lavoro nuovo. Tre richieste, la prima è sostanziale.

## Esito — CHIUSO 2026-07-09

1. **Mismatch 9-vs-533 root-causato**: è un artefatto di `sdf/auto-bounds` (ramo `rotate` = sfera-bounding, si accumula moltiplicativamente su `rotate` annidati; l'ordine dei comandi cambia il conteggio di `rotate` → B `(cp-th)(stretch)` ha 6 rotate in più di A `(stretch)(cp-th)` → (√3)^6 ≈ 27× → bounds ±322 vs ±11.8 → auto-resolution affamata → 9 vertici). Il confronto geometrico via `ensure-mesh` era **compromesso** da quei bounds, non provava niente sul fix. La geometria è identica (bbox coincidente a 6 decimali; rimeshando con bounds sani uguali entrambi danno ~5900 vertici, stessa superficie). L'invariante SDF è stato **rifatto su mesh sane e regge**; a livello dati `:material-heading`/`:material-up` sono identici fra i due ordinamenti. Non è un difetto del fix stretch; è il limite noto `project_sdf_auto_bounds_overinflate`. Evidenza REPL + entry in `dev-docs/code-issues.md`.
2. **Due test confermati presenti** in `test/ridley/editor/stretch_material_frame_test.cljs`: regressione = `stretch-no-cp-matches-plain-pose-axis`; fattore negativo/riflessione = `stretch-negative-factor-after-cp-stays-watertight`. Suite verde (465 test, 0 fallimenti).
3. **Difetti in code-issues.md**: l'artefatto SDF aggiunto (con esito del punto 1); il glitch di rientranza di edit-attach era **già** presente in code-issues.md (loggato durante brief-edit-attach-handles).

## 1. Root-causing del mismatch 9-vs-533 vertici, e validità della verifica SDF

Il report di implementazione segnala un artefatto emerso "while verifying SDF, when meshing two orderings for comparison": una delle due mesh a 9 vertici contro 533. Quel confronto tra ordinamenti è esattamente la verifica dell'invariante di commutazione sul backend SDF, quindi l'artefatto non è un loose end esterno: è dentro il percorso di verifica del brief. Una mesh a 9 vertici da un SDF che ne produce 533 è quasi certamente degenere (firma della famiglia "apparentemente valida ma priva del contributo", come il caso mesh-hull: bounds che non contengono la geometria, o coercizione).

Richiesta, in ordine:
- Ricostruire su quali coppie di mesh è stato dichiarato verificato l'invariante SDF, e se il confronto contenente l'artefatto era tra queste.
- Root-causare il mismatch (bounds del meshing? coercizione? altro?), con evidenza REPL.
- Se il root-causing rivela che il confronto era compromesso, rifare la verifica dell'invariante SDF su mesh sane e riportare l'esito. Se rivela un difetto reale e indipendente da questo fix, va bene: ma a quel punto è un difetto noto e documentato, non un sospetto in memoria.

## 2. Conferma esplicita di due test richiesti dal brief

Il report non menziona due test che il punto 4 del brief richiedeva scritti prima del codice:
- Regressione: un path senza cp rotazionali (stretch dopo f e th semplici) produce una mesh identica a prima del cambio. È il test che blinda il breaking change per tutti i modelli pre-gizmo.
- Fattore negativo: la riflessione (flip del winding) funziona con gli assi materiali.

Se sono già dentro stretch_material_frame_test.cljs, basta confermarlo indicando i nomi dei test. Se mancano, scriverli e riportare l'esito.

## 3. Scarico dei difetti da memoria a code-issues.md

Due difetti scoperti fuori scope vivono oggi solo nella memoria di sessione: il glitch di rientranza di edit-attach (segnalato a fine brief-edit-attach-handles) e l'artefatto SDF del punto 1. Il posto canonico è dev-docs/code-issues.md, perché il tracking non deve dipendere da quale istanza si ricorda cosa.

Richiesta: una entry per ciascuno, con riproduzione e contesto. Per l'artefatto SDF, l'entry incorpora l'esito del punto 1 (root cause se trovata, o stato del sospetto se no, e se la verifica dell'invariante è stata rifatta). D'ora in avanti: i difetti fuori scope si loggano in code-issues.md nel momento in cui si scoprono, la memoria è un di più, non il registro.
