<!--
=========================================================================
NOTE INTERNE PER L'AUTORE — NON DESTINATE AL LETTORE FINALE
(in italiano per convenzione, anche nei file EN)

Traduzione EN di 01-getting-started.md, 2026-06-12, speculare all'IT.

Capitolo 1 "Per iniziare", scritto a giugno 2026 a colmare la lacuna
(era nel piano §3.1 fin dall'inizio, mai steso). Scaffold: 1.1 Ciao
Ridley, 1.2 L'interfaccia, 1.3 Run e REPL, 1.4 La tartaruga (5 comandi
base), 1.5 Basi di Clojure (minimo per leggere il cap. 2; il cap. 16
resta l'approfondimento).

DETTAGLI UI — VERIFICATI da Vincenzo il 2026-06-12 sull'IT (capitolo
confermato con quattro correzioni: click di mouse, REPL in basso a
sinistra, Opzione ⌥ al posto di Alt, b ≡ f negativo); la traduzione
le recepisce. Lista conservata come storico:
- Controlli camera nel viewport: descritti in forma generica
  "trascina / rotella".
- Tartaruga visibile di default nel viewport (la 1.4 lo afferma).
- Autocompletamento e tooltip con firma nell'editor: descritti in
  forma generica.
- Opzione(⌥)+Click su una mesh -> catena di link nella status bar
  (verificato 2026-06-12; Architecture.md dice "Alt", su Mac è ⌥).
  NOTA APERTA: il salto status bar -> editor non è sincronizzato col
  workspace di provenienza (tracciato in dev-docs/code-issues.md,
  candidato Roadmap).
- Distribuzione esatta fra top bar e barra laterale degli elementi
  citati in 1.2: la prosa li elenca senza attribuirli a una barra.
- Cmd+Enter: il resto del manuale dice solo Cmd+Enter, qui idem.
- Paredit / editing strutturale: non riscontrato, non menzionato.
- Beep di feedback a fine eval: menzionato di sfuggita nella 1.2.

I marker <!– level: ... –> seguono la convenzione dei brief
dev-docs/brief-level-badges.md e -2 (rendering implementato).
=========================================================================
-->

# 1. Getting started

<!-- level: base -->

This chapter takes you from the first launch to your first object: where the code is written, what to press to run it, how the turtle moves, and the bare minimum of Clojure you need to read the rest of the manual. No theory that is not used right away. If Ridley is already open in front of you, all the better: every example is meant to be tried.

## 1.1 Hello Ridley

Open Ridley. The screen is split in two halves: a text editor on the left, an empty 3D view on the right. Everything you will do in Ridley goes through here: you write a program on the left, you run it, and the object the program describes appears on the right.

Type this line in the editor:

<!-- example-source: hello-ridley -->
```clojure
(register hello (box 20))
```

and press **Cmd+Enter**. A cube of side 20 appears in the viewport.

That line says three things. `(box 20)` builds a cube. `register` gives it a name, `hello`, and puts it on stage: it is the command that makes a shape visible. The parentheses are how Clojure, Ridley's language, writes every operation: first the name of the operation, then its arguments. We come back to this in 1.5; for now the pattern is enough.

Now change `20` to `35` and press Cmd+Enter again. The cube grows. This is Ridley's fundamental loop, the one you will repeat thousands of times: **change the code, run, look**. There is nothing to drag and no property to set in a panel: the program is the model, and changing the model means changing the program.

One detail worth noticing right away: when you press Cmd+Enter the scene is not "updated", it is **rebuilt from scratch** by running the whole program from the beginning. It sounds like a technical detail, but it is Ridley's most important guarantee: what you see is always, exactly, what the code describes. No hidden state, no leftovers from previous operations.

## 1.2 The interface

The pieces you will use from day one are four.

**The editor** (the left panel) holds the model's program. It is a real code editor: line numbers, syntax highlighting, and active help while you type: as you write the name of a function, Ridley recognizes it and can show you its signature and a link to its card in the manual's Reference. When a name does not come back to you, the answer is one mouse click away.

**The viewport** (the right panel) shows the scene. Drag with the mouse to orbit around the model, use the wheel to move closer and farther, drag with the other button to pan the view. The camera position survives runs: you can frame a detail and keep editing the code without losing your point of view. In the viewport toolbar you will find a few toggles you will meet in the next chapters, such as "Lines" and "Stamps" for the turtle's traces and the previews of 2D profiles.

**The REPL** (the command line at the bottom left) runs a single expression at a time, without rebuilding the scene. It is the tool for asking the model questions while you work. The difference between the REPL and Cmd+Enter is the subject of the next section.

**The surrounding bars.** Around the two panels, spread between the top bar and the side bar, is everything else: this manual, the Workspaces (the documents you work on, ch. 9), the libraries (reusable code, again ch. 9), and the settings. For now you need none of it: a single Workspace, the editor, and the viewport are enough for the next chapters.

Two conveniences worth knowing from the start. If you run and hear a short sound, it is the end-of-run feedback (it can be turned off in the settings). And if in a crowded scene you do not remember which code generated a piece, a click with the Option key held down (⌥, Alt on non-Apple keyboards) on the mesh in the viewport shows in the status bar the links to the lines of code that produced it.

## 1.3 Running code: Run and the REPL

Ridley has two ways of running code, and it pays to fix them in mind right away because they are deliberately different.

**Run** (Cmd+Enter in the editor) executes the whole program from scratch: it empties the scene, restarts from a clean state, and rebuilds everything. It is the main loop. You have already seen the advantage: the result depends only on the text of the program, always.

**REPL** (Enter in the command line at the bottom left) evaluates a single expression *in the context left by the last Run*, without emptying anything. It is for inspecting and trying things. A few examples of what one does in the REPL, with functions you will meet in the next chapters:

```clojure
(bounds :hello)        ; how big is the cube I registered?
(objects)              ; what is on stage?
(hide :hello)          ; hide it for a moment
(show :hello)          ; there it is again
```

The practical rule: the program lives in the editor and runs with Run; the REPL is for questions and experiments. If in the REPL you find a value you like (a measure, a position), copy it back into the program by hand: at the next run the REPL restarts from the new state, and only what is in the editor persists.

## 1.4 The turtle

In `about-ridley` you read the philosophy; here are the commands. The turtle is Ridley's 3D cursor: it has a position, a direction it looks in (the *heading*), and an up, and you can see it in the viewport. Everything in Ridley is born where the turtle stands: the cube of 1.1 appeared centered on her, at the origin.

The movement commands are three, plus their opposites:

`(f 20)` forward by 20, in the direction the turtle is looking. `(b 20)` or `(f -20)` backward.

`(rt 20)` move right by 20, without turning. `(lt 20)` left.

`(u 20)` move up by 20. `(d 20)` down.

And two rotations:

`(th 90)` turn the head by 90° in the horizontal plane (to the left; negative angles turn right).

`(tv 30)` tilt the head 30° upward, in the vertical plane.

As it moves, the turtle leaves a trace: a construction line in the viewport (the pen is down by default; it lifts with `(pen :off)`, and the "Lines" toggle in the toolbar hides all the traces). The most classic program of turtle geometry is a square:

<!-- example-source: turtle-square -->
```clojure
(f 20) (th 90)
(f 20) (th 90)
(f 20) (th 90)
(f 20) (th 90)
```

Forward, turn left, four times. Notice that there is no coordinate anywhere: the square is drawn *where the turtle stands, oriented like the turtle*. Try adding `(tv 30)` as the first line and run again: the very same code draws the square on a tilted plane. This is the idea that holds up all of Ridley: commands are relative to the turtle, not to the world, and anything you build can be repositioned and reoriented simply by taking the turtle somewhere else first.

Lines are traces, not objects: they help you see paths and constructions, and they do not end up in exported files. Real objects are created by the primitives, and they too are born on the turtle:

<!-- example-source: turtle-snowman -->
```clojure
(register body (sphere 12))
(u 16)
(register head (sphere 8))
(u 11)
(register hat (box 9 5 9))
```

A sphere at the origin, the turtle climbs by 16, a smaller sphere, climbs again, a little box: a snowman. The turtle is the positioning system: move it, build, move it again. In the next chapters you will meet more powerful ways of placing things (`attach`, recorded paths, marks), but they are all elaborations of this same gesture.

There is a third rotation, `tr`, which rolls the turtle on itself like a banking airplane. You will not need it for the first chapters: you will meet it when paths become three-dimensional.

## 1.5 Just enough Clojure

Ridley is programmed in Clojure. Chapter 16 is the reference for those who want to really understand it; here is the bare minimum to read the next chapters without stumbling.

**Everything is `(operation arguments...)`**. Clojure's syntax is one: open parenthesis, name of the operation, arguments separated by spaces, close parenthesis. `(box 20)`, `(f 20)`, `(th 90)`. Arithmetic too: `(+ 2 3)` is 5, `(* 10 3)` is 30. Expressions nest: `(box (* 10 3))` is a cube of side 30. It feels strange for a day, then it becomes invisible.

**`def` gives a name to a value.**

```clojure
(def size 20)
(register cube (box size))
```

From here on `size` is 20, and every use of `size` in the program updates by changing a single number. It is the first step toward parametric models, and chapter 2 builds on it.

**`defn` gives a name to a recipe.** Where `def` names a value, `defn` names a function with parameters:

```clojure
(defn tower [width height]
  (box width height width))

(register small-tower (tower 10 30))
```

`tower` is a function of yours, and it is used exactly like `box`: in Ridley there is no difference between the built-in commands and the ones you define.

**`dotimes` repeats.** The square of the previous section, written by someone who knows a loop:

<!-- example-source: clojure-square-dotimes -->
```clojure
(dotimes [_ 4]
  (f 20) (th 90))
```

"Four times: forward and turn." The underscore is the name of the counter, which we do not need here. With a real counter, `(dotimes [i 6] ...)`, the value of `i` changes at every turn: you will use it to generate series of objects.

**Comments start with `;`**. Everything after a semicolon, to the end of the line, is ignored. You will find them in all the manual's examples.

There is much more (`let`, `if`, `for`, anonymous functions), and sooner or later you will need it: when that happens, chapter 16 is the place to go. But with `def`, `defn`, and `dotimes` you can read and understand all of chapter 2, and that is where we are going now: to build real objects.
