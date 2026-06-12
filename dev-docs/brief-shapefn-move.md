# Brief — Spostamento shape-fn dal cap. 4 al cap. 6 (shell, woven-shell, embroid, capped)

**Per:** Code
**Da:** Vincenzo + Claude
**Data:** 2026-06-10
**Contesto:** ristrutturazione leggibilità delle Guide (piano §14.5, voci 2026-06-10). Decisione approvata da Vincenzo: il materiale shape-fn oggi spalmato fra cap. 4 (§4.6-4.7) e cap. 6 si consolida nel cap. 6, che ne diventa la casa unica. Il cap. 4 resta leggibile da cima a fondo da un principiante e chiude la parte shape-fn con una breve sezione-ponte.

Tutte le operazioni vanno eseguite **in parallelo su IT e EN** (`docs/manual/guides/it/` e `docs/manual/guides/en/`). I blocchi `example-source` si spostano verbatim, con i loro id e i loro flag (`:warning slow` ecc.): gli id sono già univoci e non cambiano. Nessuna modifica a Spec.md né alle schede Reference; non serve rigenerare `reference_index`.

## 1. Taglio nel cap. 4

In `04-extrusion.md` (IT e EN), rimuovere il blocco che va **dall'heading `## Shell, woven shell e embroid` / `## Shell, woven shell and embroid` (incluso)** fino **all'heading `## Revolve` (escluso)**. Il blocco comprende due sezioni: shell/woven/embroid (con le sue `###`) e `## Raccordi sui cap` / `## Fillets on the caps` (con le sue `###`).

Al posto del blocco rimosso, inserire la sezione-ponte qui sotto (stessa posizione: dopo "Risoluzione e dettaglio" / "Resolution and detail", prima di "Revolve").

### Sezione-ponte IT (verbatim)

````markdown
## Oltre l'estrusione piena

Fin qui abbiamo estruso profili pieni e costanti. Ma molti oggetti reali sono cavi (vasi, lampade, contenitori) o cambiano sezione lungo il percorso: si rastremano, si torcono, si gonfiano. In Ridley questo è il territorio di `loft` e delle shape-fn, funzioni che descrivono come il profilo varia da un capo all'altro del percorso. Un assaggio:

<!-- example-source: beyond-solid-extrusion -->
```clojure
(register cup
  (loft (shell (circle 20 64) :thickness 2 :style :solid :cap-bottom 2)
    (f 40)))
```

Dove prima scrivevi `(extrude shape ...)`, qui scrivi `(loft (shell shape ...) ...)`: il profilo passa da pieno a guscio, e il risultato è un bicchiere con pareti di spessore 2 e fondo chiuso. Le shape-fn non si fermano qui: gusci con aperture decorative, intrecci, rastremazioni, torsioni, superfici rugose, e qualsiasi combinazione di queste. Il cap. 6 è la loro casa: puoi leggerlo subito dopo questo capitolo, o tornarci quando un progetto lo richiede.
````

### Sezione-ponte EN (verbatim)

````markdown
## Beyond solid extrusion

So far we have extruded solid, constant profiles. But many real objects are hollow (vases, lamps, containers) or change cross-section along the path: they taper, twist, bulge. In Ridley this is the territory of `loft` and shape-fns, functions that describe how the profile varies from one end of the path to the other. A taste:

<!-- example-source: beyond-solid-extrusion -->
```clojure
(register cup
  (loft (shell (circle 20 64) :thickness 2 :style :solid :cap-bottom 2)
    (f 40)))
```

Where you previously wrote `(extrude shape ...)`, here you write `(loft (shell shape ...) ...)`: the profile goes from solid to shell, and the result is a cup with walls 2 thick and a closed bottom. Shape-fns go much further: shells with decorative openings, weaves, tapers, twists, rough surfaces, and any combination of these. Chapter 6 is their home: read it right after this chapter, or come back to it when a project calls for it.
````

Nota: l'esempio della sezione-ponte è deliberatamente identico (più `:cap-bottom`) al primo esempio della sezione spostata; gli id sono diversi (`beyond-solid-extrusion` vs `shell-solid`) e ogni example-source è valutato in isolamento, quindi non c'è conflitto.

## 2. Inserimento nel cap. 6

In `06-shape-fn.md` (IT e EN), inserire le due sezioni rimosse dal cap. 4, **nell'ordine: prima shell/woven/embroid, poi i raccordi sui cap**, fra la fine della sezione `## mesh-to-heightmap e heightmap tileabili` / `## mesh-to-heightmap and tileable heightmaps` e l'heading `## Comporre shape-fn` / `## Composing shape-fns`. Così il vincolo di composizione di shell e il caso embroid, discussi dentro "Comporre", diventano riferimenti all'indietro invece che in avanti.

## 3. Adattamenti testuali (sul materiale spostato e sul cap. 6)

1. **Apertura della sezione shell** (il "fin qui" non vale più nel nuovo contesto):
   - IT, sostituire: `Fin qui abbiamo estruso profili pieni. Ma molti oggetti reali sono cavi: vasi, lampade, contenitori, paralumi.` → `Le shape-fn viste finora deformano un profilo che resta pieno. Ma molti oggetti reali sono cavi: vasi, lampade, contenitori, paralumi.`
   - EN, sostituire: `So far we have extruded solid profiles. But many real objects are hollow: vases, lamps, containers, lampshades.` → `The shape-fns seen so far deform a profile that stays solid. But many real objects are hollow: vases, lamps, containers, lampshades.`

2. **Paragrafo di chiusura della sezione shell** (rimando al cap. 6, ora privo di senso): rimuovere per intero.
   - IT: `Il cap. 6 tratta le shape-fn in profondità: composizione, shape-fn custom, thickness-fn. Qui l'obiettivo era mostrare che l'estrusione cava è a portata di mano senza uscire dal flusso shape → loft.`
   - EN: `Chapter 6 covers shape-fns in depth: composition, custom shape-fns, thickness-fns. Here the goal was to show that hollow extrusion is within reach without leaving the shape → loft flow.`

3. **"Il caso di embroid" dentro "Comporre"**:
   - IT: `` `embroid` (cap. 4.6) è una shape-fn particolare`` → `` `embroid` (visto sopra) è una shape-fn particolare``
   - EN: `` `embroid` (chapter 4.6) is a special shape-fn`` → `` `embroid` (seen above) is a special shape-fn``

4. **Apertura della sezione thickness-fn**:
   - IT: `Nel cap. 4 abbiamo usato `shell` con gli stili built-in` → `Nella sezione su `shell` abbiamo usato gli stili built-in`
   - EN: `In chapter 4 we used `shell` with the built-in styles` → `In the section on `shell` we used the built-in styles`

5. **Sezione "Raccordi sui cap"**: l'apertura cita il cap. 3 (`fillet-shape`) e va bene così; verificare che non contenga altri riferimenti interni al cap. 4 ("come abbiamo visto sopra" che puntavano a 4.6 restano validi perché shell ora precede nello stesso capitolo).

6. **Note interne d'autore** (i commenti HTML in testa ai file, IT e EN):
   - Cap. 4: rimuovere le righe della struttura relative a 4.6 e 4.7, aggiungere la voce della sezione-ponte, e aggiornare la riga di decisione su embroid (la decisione "embroid aggiunto al 4.6" diventa storica: annotare "spostato al cap. 6 il 2026-06-10").
   - Cap. 6: aggiornare l'elenco della struttura inserendo le due nuove sezioni prima di "Comporre", e riscrivere la riga dei prerequisiti: il cap. 4 non introduce più shell/woven-shell/capped, introduce solo `loft` e il ponte.

## 4. Sweep dei riferimenti stale

Grep su tutte le guide IT e EN per `4.6`, `4.7`, e per le menzioni di shell/capped accompagnate da "cap. 4"/"chapter 4". Riferimenti noti da sistemare: quelli dei punti 3.3, 3.4 e 3.6 qui sopra. Riferimenti noti già corretti che NON vanno toccati: il cap. 7 IT/EN rimanda il `:softness` degli shell al "cap. 6" (diventa esatto con lo spostamento); la pagina `how-to-read.md` punta già "lampada traforata e cesto intrecciato" al cap. 6 (presuppone questo brief). Qualsiasi altro riferimento trovato: segnalare in chat se la correzione non è ovvia.

## 5. Badge

Il cap. 6 ha già il marker di capitolo `<!-- level: advanced -->` e il cap. 4 ha `base` a livello capitolo più `advanced` sulla sezione Chaining (annotati oggi, IT+EN). Le sezioni spostate **non** ricevono marker propri: ereditano l'advanced del cap. 6. La sezione-ponte nel cap. 4 non riceve marker (eredita base).

## 6. Accettazione

1. Cap. 4 IT e EN: niente più sezioni shell/capped; la sezione-ponte compare fra "Risoluzione e dettaglio" e "Revolve"; l'esempio `beyond-solid-extrusion` gira nel panel.
2. Cap. 6 IT e EN: le due sezioni compaiono prima di "Comporre shape-fn"; tutti gli esempi spostati girano (inclusi gli `:warning slow`).
3. `grep -rn "4\.6\|4\.7" docs/manual/guides/` non restituisce riferimenti di prosa alle vecchie sezioni (le note d'autore aggiornate possono citarle come storia).
4. IT e EN restano speculari sezione per sezione.
5. Nessuna modifica a `structure.cljs`, Spec.md, schede Reference.

---

## Addendum 2026-06-11 — risoluzioni alle osservazioni di Code (pre-esecuzione)

Via libera all'esecuzione con le decisioni seguenti.

1. **Rinomina della sottosezione spostata: sì, obbligatoria.** `### Comporre con altre shape-fn` → IT `### Shell in composizione`, EN `### Shell in composition`. In chiusura della sottosezione rinominata, aggiungere (o adattare, se il testo esistente già dice qualcosa di simile) una frase-ponte che renda esplicito il rapporto assaggio → trattamento canonico:
   - IT: `Il quadro generale della composizione, e il vincolo per cui \`shell\` chiude sempre la catena, è il tema della sezione "Comporre shape-fn" più avanti.`
   - EN: `The general picture of composition, including the rule that \`shell\` always closes the chain, is the subject of the "Composing shape-fns" section further on.`
   Se la frase risultasse ridondante col paragrafo finale esistente, fonderle a tuo giudizio: l'importante è che la sottosezione chiuda puntando in avanti, non che la frase sia verbatim.
2. **Nota d'autore cap. 6, riga su embroid**: aggiornare in `embroid non si compone in catena: stampa le proprie facce (vedi la sezione embroid in questo capitolo), eccezione più forte di shell`.
3. **how-to-read.md, nota d'autore**: rimuovere le due righe di hedge (`Finché lo spostamento non è eseguito, quei due esempi stanno ancora nel cap. 4.`) e riformulare il bullet al passato (lo spostamento è eseguito). Solo IT: la pagina non ha gemello EN, confermo che la specularità non si applica.
4. **Note d'autore in italiano nei file EN**: confermato, è la convenzione del progetto (i blocchi `NOTE INTERNE PER L'AUTORE` si preservano in italiano anche nei file EN). Non tradurle.
5. **Tono ponte/cartello cap. 6**: lasciare stare, concordo che non si contraddicono.

La riclassificazione di livello di shell/capped (da base ereditato del cap. 4 ad advanced ereditato del cap. 6) è intenzionale, confermo.
