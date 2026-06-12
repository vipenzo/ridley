# Brief — Sweep di deduplicazione: archi, bezier, qp, side-trip, find-faces, cap. 15, più spiccioli

**Per:** Code
**Da:** Vincenzo + Claude
**Data:** 2026-06-12
**Contesto:** ultime mosse della regola "una casa per argomento" (piano §14.5, diagnosi 2026-06-10). Sono ritocchi più leggeri dei brief precedenti: in quasi tutti i casi il rimando giusto esiste già e va solo tolta la ri-spiegazione. Tutte le modifiche **in parallelo IT e EN**; per le stringhe EN indico il testo atteso (i file sono speculari, verificare sul posto). Clausola generale: se in una zona da tagliare trovi contenuto che non esiste nella "casa" dell'argomento, segnala in chat prima di buttarlo. Nessun marker di capitolo viene toccato: `manual_levels` non va rigenerato.

## A. Cap. 11, §11.1 — gli archi non si re-introducono

In `11-curve-avanzate.md`, sostituire tutto il contenuto della 11.1 **dall'heading `## 11.1 Archi` (incluso) fino a `### Archi dentro path ed extrude` (escluso)** con il testo qui sotto. Si perde l'example-source `arc-s-tube` (duplicato di `extrude-s-curve` del cap. 4); la sottosezione `### Archi dentro path ed extrude` resta intatta ed è il cuore della 11.1.

Versione IT:

```markdown
## 11.1 Archi

Gli archi li hai già usati nel cap. 4: `arc-h` curva nel piano orizzontale (attorno all'asse up della tartaruga), `arc-v` in quello verticale (attorno all'asse right); angolo positivo nella direzione standard di rotazione, negativo nell'opposta. Sotto il cofano un arco è una sequenza di piccoli `f` + `th` (o `tv`): il numero di passi dipende da `resolution` e si sovrascrive per singola chiamata con `:steps`:

```clojure
(arc-h 10 90 :steps 32)    ;; 32 passi invece del default
```

Due archi consecutivi si raccordano lisci da sé: la tartaruga esce dal primo con l'heading tangente alla curva, e il secondo parte da lì. Quello che il cap. 4 non diceva è che gli archi sono comandi tartaruga a tutti gli effetti, e questo ha una conseguenza preziosa per i path.
```

Versione EN (l'heading EN atteso è `## 11.1 Arcs` e `### Arcs inside paths and extrude` o equivalente — verificare e mantenere l'esistente):

```markdown
## 11.1 Arcs

You have already used arcs in chapter 4: `arc-h` curves in the horizontal plane (around the turtle's up axis), `arc-v` in the vertical one (around the right axis); a positive angle goes in the standard direction of rotation, a negative one the opposite way. Under the hood an arc is a sequence of small `f` + `th` (or `tv`) steps: their number depends on `resolution` and can be overridden per call with `:steps`:

```clojure
(arc-h 10 90 :steps 32)    ;; 32 steps instead of the default
```

Two consecutive arcs join smoothly by themselves: the turtle leaves the first one with its heading tangent to the curve, and the second one starts from there. What chapter 4 did not say is that arcs are turtle commands in every respect, and this has a precious consequence for paths.
```

Nota: i blocchi qui sopra contengono fence annidate; nel file finale il blocco clojure è una fence normale.

## B. Cap. 4, §4.2 — il puntatore della coda Bezier

La chiusura della sottosezione Bezier del cap. 4 oggi rimanda al capitolo sbagliato. Sostituire:

- IT: `I dettagli sono nel cap. 5 (Path) e nella Reference.` → `La casa delle curve è il cap. 11: punti di controllo espliciti, coordinate locali, curve verso i marcatori, e il disegno interattivo con `edit-bezier`.`
- EN (atteso `The details are in chapter 5 (Paths) and in the Reference.` o equivalente): → `The home of curves is chapter 11: explicit control points, local coordinates, curves toward marks, and interactive drawing with `edit-bezier`.`

## C. Cap. 5, §5.6 — quick-path in breve

In `05-paths.md`, sezione `## Path da coordinate`, sostituire l'intera sottosezione `### quick-path` (heading incluso, fino a fine sezione) con:

Versione IT:

```markdown
### quick-path

`quick-path` (alias `qp`) è il costruttore compatto del cap. 4: numeri e angoli si alternano, e `(qp 20 60 20 -120 20 60 20)` è uno zigzag scritto in una riga. Qui basta il criterio di scelta: `qp` è comodo per percorsi rettilinei con angoli noti, `poly-path` quando hai le coordinate, e `path` con comandi tartaruga è il più flessibile: archi, bezier, side-trip, mark.
```

Versione EN (atteso `### quick-path` con prosa equivalente):

```markdown
### quick-path

`quick-path` (alias `qp`) is the compact constructor from chapter 4: numbers and angles alternate, and `(qp 20 60 20 -120 20 60 20)` is a zigzag written in one line. Here the selection rule is enough: `qp` is handy for straight runs with known angles, `poly-path` when you have coordinates, and `path` with turtle commands is the most flexible: arcs, beziers, side-trips, marks.
```

Si perde l'example-source `quick-path-zigzag` (duplicato concettuale dello zigzag del cap. 4).

## D. Cap. 8, §8.5 — side-trip senza ri-insegnarlo

Nella sottosezione `### Side-trip` della 8.5, sostituire il paragrafo introduttivo:

- IT: ``side-trip` è il meccanismo principale. All'interno di un `path`, esegue una sequenza di comandi e poi ripristina lo stato della tartaruga al punto di partenza:` → ``side-trip` (§ 5.4) è il meccanismo principale: esegue il blocco e riporta la tartaruga al punto di partenza. In un assemblaggio diventa lo strumento di branching:`
- EN (atteso equivalente di "is the main mechanism. Inside a `path`, it runs a sequence of commands and then restores the turtle's state to the starting point:"): → ``side-trip` (§ 5.4) is the main mechanism: it runs its block and brings the turtle back to the starting point. In an assembly it becomes the branching tool:`

L'esempio `side-trip-branching` e tutto il resto della 8.5 (Branching annidato, With-path come scope) restano invariati.

## E. Cap. 10, §10.3 — solo l'ottica analisi

In `10-analizzare-e-misurare.md`, §10.3, sostituire i due blocchi su `:threshold` e `:where` (dal paragrafo ``:threshold` controlla la tolleranza dell'allineamento (default 0.7, dove 1.0 è perfetto):` fino alla fine del blocco di codice di `:where` incluso) con la singola riga:

- IT: `Le opzioni di raffinamento (`:threshold` per la tolleranza dell'allineamento, `:where` per i predicati sulla faccia) sono nella 7.4.`
- EN: `The refinement options (`:threshold` for alignment tolerance, `:where` for predicates on the face) are in 7.4.`

Restano: l'apertura (già corretta: "L'hai già incontrato nella 7.4... qui lo vediamo come strumento di analisi"), l'esempio `find-faces-basic` con `flash-face`, e il paragrafo di chiusura sull'ottica analisi.

## F. Cap. 15 — le tre sezioni-rimando diventano una tabella

In `15-debug.md`, sostituire le tre sezioni `## 15.4 Anteprima forme`, `## 15.5 Follow path`, `## 15.6 Misurazione` (dall'heading 15.4 incluso fino a fine file) con un'unica sezione. Si perdono gli example-source `stamp` e `path` (gli strumenti sono dimostrati nelle rispettive case).

Versione IT:

```markdown
## 15.4 Dove trovare gli strumenti

Alcuni strumenti che usi in fase di debug hanno la loro casa in altri capitoli. Il promemoria, in una tabella:

| Strumento | A cosa serve quando indaghi | Casa |
|---|---|---|
| `stamp` | vedere un profilo 2D prima di estruderlo o loftarlo | § 3.2 |
| `follow-path` | vedere un path come tracciato, senza costruire geometria | § 5.2 |
| `ruler`, `distance`, `bounds`, Shift+Click | misure e quote, anche interattive | cap. 10 |
| `mesh-diagnose`, `manifold?` | capire perché una mesh non è sana | § 7.7 |
```

Versione EN:

```markdown
## 15.4 Where the tools live

Some of the tools you reach for while debugging have their home in other chapters. The reminder, in one table:

| Tool | What it is for when investigating | Home |
|---|---|---|
| `stamp` | seeing a 2D profile before extruding or lofting it | § 3.2 |
| `follow-path` | seeing a path as a trace, without building geometry | § 5.2 |
| `ruler`, `distance`, `bounds`, Shift+Click | measures and dimensions, interactive too | ch. 10 |
| `mesh-diagnose`, `manifold?` | understanding why a mesh is not healthy | § 7.7 |
```

Aggiornare la nota d'autore del cap. 15 (struttura: 15.4-15.6 → 15.4 tabella, 2026-06-12).

## G. Sweep "(Librerie)"

Il cap. 9 si chiamava "Librerie", oggi "Workspaces e Librerie". Grep su tutte le guide IT e EN per riferimenti col vecchio titolo (es. `cap. 9 (Librerie)`, `chapter 9 (Libraries)`, o frasi che presentano il 9 come "il capitolo sulle librerie") e allinearli al titolo attuale o al semplice numero. Correzioni non ovvie: segnalare in chat.

## H. Verifica `scale -1` (winding) in §8.3

Voce aperta da tempo: verificare in REPL il comportamento di `scale` con fattori negativi su mesh e su shape (il mirroring inverte il winding delle facce? La mesh resta manifold e renderizza correttamente?) e confrontare l'esito con quanto afferma la prosa della §8.3, in particolare nella versione EN. Se la prosa è corretta, confermare in chat; se è sbagliata, correggere IT+EN e riportare cosa è stato cambiato.

## Accettazione

1. 11.1 IT/EN: nuova apertura, niente example `arc-s-tube`, "Archi dentro path ed extrude" intatta; gli esempi residui girano.
2. §4.2: il puntatore Bezier indica il cap. 11, in entrambe le lingue.
3. §5.6: sottosezione quick-path compatta, niente example `quick-path-zigzag`.
4. §8.5: paragrafo introduttivo di Side-trip col rimando a § 5.4; esempio invariato e funzionante.
5. §10.3: niente blocchi `:threshold`/`:where`; riga di rimando presente; esempio invariato.
6. Cap. 15: unica sezione 15.4 con tabella; nessun riferimento residuo a 15.5/15.6 altrove (grep).
7. Grep "(Librerie)"/"(Libraries)" pulito; esito della verifica H riportato in chat.
8. IT e EN speculari su tutti i file toccati.
