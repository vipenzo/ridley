<!--
=========================================================================
NOTE INTERNE PER L'AUTORE — NON DESTINATE AL LETTORE FINALE
(in italiano per convenzione, anche nei file EN)

Traduzione EN di how-to-read.md, 2026-06-12, speculare all'IT.

Pagina di orientamento, fuori numerazione (come about-ridley).
Origine: manual-redesign-plan §3.0 (mappa per fasi di lavoro, mai
pubblicata prima) + decisione 2026-06-10 sui percorsi di lettura.

- La mappa riproduce §3.0 del piano, senza il cap. 18 (non esiste ancora).
- I cinque percorsi sono la risposta al problema "a chi è rivolto il
  manuale": invece di un lettore unico, cinque itinerari dichiarati.
  Il quinto (il primo nell'ordine della pagina) è il curioso che deve
  ancora decidere se Ridley gli interessa: il suo percorso non è fatto
  di capitoli ma di esempi eseguibili.
- Il tour del curioso punta la lampada traforata e il cesto intrecciato
  al cap. 6, dove shell e woven-shell ora vivono (spostate dal cap. 4 il
  2026-06-10, brief dev-docs/brief-shapefn-move.md).
- I titoli di capitolo nella mappa e i titoli di sezione citati nei
  percorsi corrispondono ai titoli EN reali (structure.cljs e file).
- I riferimenti ai livelli usano i marker `<!– level: … –>`; rendering
  implementato (brief dev-docs/brief-level-badges.md e -2).
=========================================================================
-->

# How to read this manual

This manual has two souls. The **Guides** are the numbered chapters: prose you read, with runnable examples, meant for learning. The **Reference** is the set of per-function cards: dense, factual, meant for looking things up. The two talk to each other: in the guides, function names link to their cards, and in the editor itself the symbols you type show a hint with the signature and a link to the full card. When you need to know *how to use* something, the card is faster than the guide; when you need to understand *when and why*, it is the other way around.

And then there is the thing that makes this manual different from a book: **almost every example runs**. The button next to a code block evaluates it on the spot, and the result appears in the viewport; the "Edit" button opens the same example in a new Workspace, where you can change it, break it, and start over without touching your own work. Just reading is the worst way to use these pages.

The chapters are numbered, but the numbering is a rough teaching order, not a mandatory route. Nobody needs the whole manual to use Ridley, and different readers need different pieces in different orders. Below you will find first a map of the chapters by phase of work, then five recommended itineraries depending on where you come from.

## The map

```
Learn          1.  Getting started
               16. Clojure for Ridley

Raw material   3.  Working with 2D shapes
               5.  Paths
               7.  Mesh

Build          2.  Modeling with primitives
               4.  Extrusion
               6.  From mathematical functions to shapes
               12. Working with SDFs

Compose        8.  Assembly
               11. Advanced curves

Reuse          9.  Workspaces and Libraries

Understand     10. Analyzing and measuring
               15. Focusing in and troubleshooting

Polish         13. Text
               14. Color and materials

Finish         17. Exporting and printing
```

The map reads like this: the "Raw material" chapters describe the data Ridley works with (2D shapes, paths, meshes), the "Build" ones the techniques that turn that data into solids, and so on down to export. The numbers do not follow the phases because the teaching sequence is a different thing: for example chapter 2 (primitives) comes before 3 (2D shapes) because it is more rewarding to build something solid right away, even though conceptually 2D shapes are raw material.

## Five readers, five routes

### You want to find out whether Ridley is for you

You have not yet decided whether it is worth learning: you want to see what it does. Then don't read: **run**. Open any chapter, press the button next to an example, and watch the viewport. If you prefer a guided tour, these six examples tell well what the system is capable of: the **sailboat** in ch. 2 (a few primitives and four operations becoming a recognizable object), the **hexagonal side table** in ch. 5 (a single path acting at once as the profile of the top *and* as the skeleton for the legs: one piece of data, two uses, no way for them to diverge), the **perforated lamp and the woven basket** in ch. 6 (decorated surfaces described by functions), the **wall key holder** in ch. 8 (a real, parametric object to print and use), the **text spiraling upward** in ch. 13, and the **shapes melting together** in ch. 12 (SDFs and their smooth blends). On each one, "Edit" lets you change a number and run again: that is where you find out whether this way of modeling suits you. If the answer is yes, pick below the route that matches where you come from.

### You have never modeled in 3D

Maybe you have never programmed either. Your route is the most linear: **1 → 2 → 3 → 4 → 17**. Chapter 1 gets your hands on the interface and the turtle, 2 has you build the first real objects, 3 and 4 add free shapes and extrusion, 17 takes you to printing. When the code starts to feel tight, chapter **16** (Clojure) consolidates the basics that chapter 1 gave you in pill form. From there, **5** (paths) and **10** (measuring) are the natural next steps. Leave chapters 6, 7, 11, and 12 for a second pass: none of them is needed for your first twenty objects.

### You come from OpenSCAD or another code CAD

You already know the idea of model-as-program; what you need is the map of differences. Read `about-ridley`, then of chapter **1** only the sections on the interface and the turtle (the turtle paradigm is the big difference from OpenSCAD's global coordinate system). In chapter **2** go straight to the final section "For those coming from a traditional CAD": `translate`, `rotate`, and `scale` exist and work as you expect, but there you see how much more expressive `attach` and the local commands can be. Then **3 → 4 → 5 → 8** in order: shapes, extrusion, paths with marks, assembly. The marks and the skeletons of chapter 8 are the thing OpenSCAD does not have, and the one that repays the study soonest. Open 7 and 10 when you need them.

### You come from a sketch CAD (Fusion, SolidWorks, FreeCAD)

For you the hurdle is not the syntax, it is a change of mental model: in Ridley there are no sketches anchored to planes, no constraints, no timeline. The right entry door is the section **"Profiles as values"** in chapter 3, which tackles exactly this difference. From there read chapter **3** in full, then **4** (extrusion is your familiar ground), **5** (`mark`s in paths do the job that constraints and reference planes do in sketch CADs), and **8** (skeletons are the equivalent of assemblies). Chapter **10** gives you back the dimensions and measurements you are used to.

### You want to print something tonight

Half an hour, one object on the plate. Read the first section of chapter **1** (just to know where to type and what to press), then the first three sections of chapter **2**: primitives, composition, and the pen holder, which is already a useful, printable object. Jump to chapter **17**, STL section, and export. End of the short route. Once the object is on the plate, come back and start over with one of the other itineraries.

## The levels

Every chapter declares its level at the top: **basic**, **intermediate**, or **advanced**. Where a chapter mixes levels, the individual sections declare it: it often happens that a basic chapter has an advanced tail (the last section of chapter 4, for example) or that an advanced chapter has an accessible opening. The level does not measure how hard the reading is, but how long you can postpone it: "advanced" means "you can build a lot without this", not "incomprehensible".

## The examples

Almost all code blocks in the guides are runnable, as said above. Two practical warnings: some examples are marked as slow (computations of several seconds, typically high-resolution shells and weaves), and a few are not directly runnable because they open interactive sessions (`tweak`, `pilot`, `edit-bezier`): in those cases the text says so. For everything else the earlier rule applies: if an example intrigues you, press Edit and get your hands on it.
