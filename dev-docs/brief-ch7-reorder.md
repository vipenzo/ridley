# Brief — Cap. 7: riparazione in coda; cap. 3: riduzione di "Generare shape da mesh"

**Per:** Code
**Da:** Vincenzo + Claude
**Data:** 2026-06-12
**Contesto:** ristrutturazione leggibilità (piano §14.5). Due decisioni di Vincenzo dopo rilettura del cap. 7: (a) il capitolo apre con la diagnostica/riparazione delle mesh, e questo dà di Ridley un'idea di oggetto fragile mentre nell'uso reale le mesh non-manifold sono rare — il materiale va spostato in coda al capitolo, con un framing che parta dalla rarità; (b) la sezione "Generare shape da mesh" del cap. 3 duplica la 7.5 con gli stessi esempi — si applica la regola "una casa per argomento": casa al 7.5, il cap. 3 riduce a un cenno. È la prima mossa eseguita dello sweep di dedup concordato.

Tutte le operazioni **in parallelo IT e EN** (i file sono speculari riga per riga, verificato). I blocchi `example-source` si spostano verbatim con id e flag, salvo dove indicato che vanno rimossi. Il badge `<!-- level: advanced -->` a livello capitolo sul 7 è già stato aggiunto (IT+EN): la nuova sezione 7.7 non riceve marker, eredita. Nessuna modifica a `structure.cljs`, Spec.md, schede Reference.

## Parte A — Cap. 7 (`07-mesh.md` IT e EN)

### A1. Taglio dalla 7.1

Rimuovere dalla 7.1 le tre sottosezioni: da `### Guardare dentro una mesh` / `### Looking inside a mesh` (incluso) fino a `## 7.2 Operazioni booleane 3D` / `## 7.2 3D boolean operations` (escluso). Comprende `### Manifold e non-manifold` / `### Manifold and non-manifold` e `### Riparare una mesh` / `### Repairing a mesh`.

La 7.1 resta quindi: intro + struttura dati + campi opzionali + paragrafo sul default di `:creation-pose`. In coda alla 7.1 così ridotta, aggiungere questo paragrafo di transizione:

- IT: `Il resto del capitolo lavora su questo dato: le booleane combinano le mesh (7.2), raccordi e smussi le rifiniscono (7.3), le facce si trovano e si modificano (7.4), le sezioni riportano una mesh in 2D (7.5), `warp` la deforma nello spazio (7.6). In coda, la 7.7 raccoglie diagnosi e riparazione per i rari casi in cui una mesh non è sana.`
- EN: `The rest of the chapter works on this data: booleans combine meshes (7.2), fillets and chamfers refine them (7.3), faces are found and modified (7.4), sections bring a mesh back to 2D (7.5), `warp` deforms it in space (7.6). At the end, 7.7 gathers diagnosis and repair for the rare cases where a mesh is not healthy.`

### A2. Nuova sezione 7.7 in coda al capitolo

Dopo la fine della 7.6 (il file chiude con `### Quando usare warp vs altre tecniche` / `### When to use warp vs other techniques`), aggiungere la sezione `## 7.7 Diagnosi e riparazione` / `## 7.7 Diagnosis and repair`, composta da: il paragrafo di apertura qui sotto, poi le tre sottosezioni rimosse in A1, nell'ordine originale.

Apertura IT:

`Chiudiamo con gli strumenti per i casi in cui una mesh non è sana. Va detto subito: nell'uso reale sono casi rari. Le primitive, le estrusioni, i loft, i revolve e i risultati delle booleane producono mesh corrette per costruzione; puoi modellare per mesi senza incontrarne mai una rotta. Le eccezioni arrivano quasi sempre da tre porte: geometrie accostate senza booleane (`concat-meshes`), mesh importate dall'esterno, e combinazioni estreme di shape-fn. Per quei momenti, Ridley ha una diagnostica completa.`

Apertura EN:

`We close with the tools for the cases where a mesh is not healthy. Let us say it right away: in real use these cases are rare. Primitives, extrusions, lofts, revolves, and the results of boolean operations produce correct meshes by construction; you can model for months without ever meeting a broken one. The exceptions almost always come through three doors: geometries juxtaposed without booleans (`concat-meshes`), meshes imported from outside, and extreme combinations of shape-fns. For those moments, Ridley has a complete diagnostic toolkit.`

### A3. Adattamenti nel materiale spostato

1. **"Manifold e non-manifold", prima frase** (il "subito" non vale più in coda al capitolo, e il concetto è ormai già comparso in 7.2):
   - IT: `La parola "manifold" compare spesso quando si lavora con le mesh, e vale la pena chiarirla subito perché determina cosa puoi fare con un oggetto.` → `La parola "manifold" è già comparsa con le booleane (7.2); qui la chiariamo fino in fondo, perché determina cosa puoi fare con un oggetto.`
   - EN: `The word "manifold" comes up often when working with meshes, and it is worth clarifying right away because it determines what you can do with an object.` → `The word "manifold" already appeared with the booleans (7.2); here we clarify it in full, because it determines what you can do with an object.`
2. **"Manifold e non-manifold", lancio degli strumenti rapidi** (sono ora già stati visti in 7.2):
   - IT: `Per verificare rapidamente lo stato di una mesh hai due strumenti:` → `Per verificare rapidamente lo stato di una mesh ci sono i due strumenti già visti in 7.2:`
   - EN: `To quickly check the state of a mesh you have two tools:` (o formulazione equivalente presente nel file) → `To quickly check the state of a mesh there are the two tools already seen in 7.2:`
   Il blocco di codice, l'esempio `mesh-manifold-check` e la spiegazione di `manifold?`/`mesh-status` restano: il dettaglio (il `nil` invece di `false`, il ruolo di triage di `mesh-diagnose`) sta solo qui.
3. **"Riparare una mesh", chiusura** (le "sezioni successive" non esistono più, la 7.7 è l'ultima):
   - IT: `servono strategie diverse che vedremo nelle sezioni successive.` → `servono strategie diverse, da valutare caso per caso: intervenire a monte sulla costruzione, o ricostruire la geometria della zona problematica.`
   - EN: `different strategies are needed, which we will see in the following sections.` → `different strategies are needed, judged case by case: intervening upstream in the construction, or rebuilding the geometry of the problem area.`

### A4. Ritocco alla 7.2, sottosezione "Controllare lo stato di una mesh" / "Checking a mesh's status"

Post-spostamento è il primo punto del capitolo dove il lettore incontra "manifold": serve la definizione inline e il framing di normalità. Sostituire la prima riga di prosa:

- IT: `Le booleane richiedono input manifold. Se una mesh non lo è, l'operazione fallisce. Due strumenti per verificare prima:` → `Le booleane richiedono input *manifold* (o *watertight*): mesh che rappresentano un solido chiuso, senza buchi né spigoli anomali. È il caso normale: tutto ciò che esce da primitive, estrusioni, loft, revolve e booleane lo è per costruzione. Se una mesh non lo è, l'operazione fallisce. Due strumenti per verificare prima:`
- EN (formulazione attuale equivalente): → `Booleans require *manifold* (or *watertight*) input: meshes that represent a closed solid, with no holes and no anomalous edges. This is the normal case: everything that comes out of primitives, extrusions, lofts, revolves, and booleans is manifold by construction. If a mesh is not, the operation fails. Two tools to check beforehand:`

E la riga di chiusura della stessa sottosezione:

- IT: `Se una booleana fallisce e non capisci perché, `mesh-diagnose` (sezione 7.1) è il passo successivo.` → `Se una booleana fallisce e non capisci perché, la 7.7 in fondo al capitolo ha gli strumenti di diagnosi e riparazione.`
- EN: equivalente con `(section 7.1)` → `section 7.7 at the end of the chapter has the diagnosis and repair tools.`

### A5. Nota d'autore del cap. 7

Aggiornare il commento in testa (IT e EN, resta in italiano): aggiungere una riga del tipo `Riorganizzazione 2026-06-12: diagnosi/riparazione spostate dalla 7.1 alla 7.7 (in coda), framing dalla rarità; la 7.2 definisce manifold inline.`

## Parte B — Cap. 3 (`03-working-with-2d-shapes.md` IT e EN)

Sostituire l'intera sezione `## Generare shape da mesh` / `## Generating shapes from meshes` (dall'heading incluso fino a `## Più forme alla volta` / heading EN equivalente, escluso) con la versione ridotta qui sotto. Gli example-source interni alla sezione (fra cui `slice-mesh-bowl`) vengono rimossi: i gemelli vivono nella 7.5 del cap. 7. L'heading resta identico per non rompere le ancore.

Versione IT:

`## Generare shape da mesh`

`Finora abbiamo costruito le shape da zero: coordinate, comandi tartaruga, primitive, composizioni. Ma a volte il profilo che serve esiste già, nascosto dentro una mesh 3D: la sezione di un vaso a una certa altezza, la sagoma di un pezzo vista dall'alto, il contorno di una faccia. Tre operazioni lo estraggono: `slice-mesh` taglia la mesh con il piano della tartaruga e restituisce il contorno della sezione, `project-mesh` ne proietta la silhouette sul piano, `face-shape` estrae il contorno di una singola faccia. Il risultato è sempre una shape standard (o un vettore di shape), pronta per `extrude`, `loft` o qualsiasi operatore di questo capitolo: il flusso 2D → 3D funziona anche all'inverso.`

`Queste operazioni lavorano sulle mesh e sui loro piani di taglio, concetti del cap. 7: la loro casa è la sezione 7.5, con gli esempi e i casi particolari. Qui basta sapere che esistono: quando una mesh contiene già il profilo che ti serve, non devi ridisegnarlo.`

Versione EN:

`## Generating shapes from meshes`

`So far we have built shapes from scratch: coordinates, turtle commands, primitives, compositions. But sometimes the profile you need already exists, hidden inside a 3D mesh: the section of a vase at a certain height, the outline of a piece seen from above, the contour of a face. Three operations extract it: `slice-mesh` cuts the mesh with the turtle's plane and returns the contour of the section, `project-mesh` projects its silhouette onto the plane, `face-shape` extracts the outline of a single face. The result is always a standard shape (or a vector of shapes), ready for `extrude`, `loft`, or any operator in this chapter: the 2D → 3D flow also works in reverse.`

`These operations work on meshes and their cutting planes, concepts of chapter 7: their home is section 7.5, with the examples and the special cases. Here it is enough to know they exist: when a mesh already contains the profile you need, you do not have to redraw it.`

Se la sezione attuale contiene materiale che nella 7.5 **non** c'è (verificare in particolare `slice-at-plane` e l'eventuale menzione di `slice-mesh :on`), segnalarlo in chat prima di buttarlo: la regola è ridurre il duplicato, non perdere contenuto unico.

## Parte C — Sweep dei riferimenti

Grep su tutte le guide IT e EN per `7.1` (e `7\.1` in prosa tipo "sezione 7.1" / "section 7.1"): i riferimenti che puntano a `mesh-diagnose`, `manifold?`, `merge-vertices` o alla riparazione vanno aggiornati a 7.7 (candidati noti: cap. 10 §10.4, cap. 15). I riferimenti alla 7.1 come "cos'è una mesh / mesh come dato" restano 7.1. Verificare anche eventuali riferimenti alla sezione del cap. 3 rimossa ("Generare shape da mesh", §3.8) in altri capitoli: vanno ripuntati alla 7.5. Correzioni non ovvie: segnalare in chat.

## Accettazione

1. Cap. 7 IT/EN: la 7.1 chiude col paragrafo di transizione; la 7.7 esiste in coda con apertura + tre sottosezioni; gli esempi spostati girano.
2. La 7.2 definisce manifold inline e rimanda alla 7.7; nessun riferimento residuo a "sezione 7.1" per la diagnostica.
3. Cap. 3 IT/EN: la sezione ridotta è in piedi, ancore invariate, nessun example-source residuo della vecchia versione.
4. IT e EN speculari; `grep` di parte C pulito.
