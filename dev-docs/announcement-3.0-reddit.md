# Bozza annuncio r/RidleyCAD — Ridley 3.0 (da changelog 1.10→3.0)

Stato: bozza in revisione con Vincenzo, 2026-06-12.
Segnaposto da riempire prima di postare: comando Homebrew (nome cask), link GitHub Releases.
Immagini proposte: (1) lampada embroid o blend SDF, (2) GIF di una sessione edit-bezier (via anim-export-gif), (3) screenshot del manuale con i bottoni Run.

---

**Title:** Ridley 3.0 is out: desktop app, implicit (SDF) modeling, interactive curve editing, and a bilingual manual

Last time I posted a release here it was 1.10, back in March. Since then: 284 commits, two major versions, and a 3.0 that feels like a different tool. Here's the short version; the full changelog is on GitHub Releases.

**Ridley is now a desktop app too.** Native macOS app (Tauri), installable via Homebrew, with native file dialogs and the whole DSL evaluated in-app: browser and desktop now share the same architecture, no external processes. The browser version is still there and still requires zero install. Export gained 3MF alongside STL, including multimaterial 3MF that Bambu/Orca Studio picks up directly, and the desktop can export GIFs from procedural animations.

**Workspaces and libraries.** Documents are now first-class workspaces with proper Save/Open, so the editor never gets clobbered again. There's a library panel with install/delete, persisted on disk on desktop, and a few built-in libraries ship preinstalled: gears, weave, puppet, and a full parametric Multiboard tile set.

**Modeling.** The headliners:

- `embroid` perforates a thin extruded wall with honeycomb, voronoi, or custom patterns.
- A complete SDF (implicit modeling) toolkit: distance formulas, TPMS surfaces, rounded primitives, half-spaces and clipping, smooth blends. SDFs and meshes mix freely in booleans and in `attach`.
- `extrude+` and `revolve+` return their end face, so extrusions and revolutions chain into a `transform->` pipeline.
- Marks placed on a path now travel with the geometry: they become anchors of the resulting mesh through extrude/loft/revolve, they survive boolean operations, and `on-anchors` instantiates parts on them. Model the skeleton once, decorate it forever.

**Interactive editing.** Three modal tools share a live re-eval layer: `tweak` (sliders on the numeric parameters of your script), `pilot` (keyboard positioning of meshes and SDFs), and the new `edit-bezier` (draw the curve instead of computing it, with tension sliders). Plus interactive rulers and marks.

**The manual.** Possibly the thing I'm proudest of: a full in-app manual, English and Italian, where almost every example has a Run button (and an Edit button that opens it in a fresh workspace, so you can break things safely). It has reading paths depending on where you come from (never modeled before / OpenSCAD / Fusion-style CAD / "I just want to print something tonight") and difficulty levels on every chapter.

**Breaking changes** (the jump from 1.10 to 3.0 is honest):

1. `cone` / `sdf-cone`: the radius argument order is reversed (it now follows loft's reading order).
2. `translate` / `scale` / `rotate` are now polymorphic (mesh, SDF, and 2D shape; world axes, pivot on the creation pose). The old shape-specific transforms are `translate-shape` / `scale-shape` / `rotate-shape`.
3. `cp-*` (creation-pose shifting): the anchor now stays fixed and the geometry slides under it. It used to be the opposite.
4. `revolve` clamps the angle to ±360°: sweeps no longer self-overlap past a full turn.

Try it in the browser: https://vipenzo.github.io/ridley
Desktop (macOS): [Homebrew cask / GitHub Releases]
Full changelog: [link GitHub Releases]

Feedback, bug reports, and showcases welcome here.
