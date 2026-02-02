# Voice & Help System — Architecture

## Design Vision

Ridley targets three interaction contexts — desktop, VR headset, and (eventually) mobile — each with different input affordances. The voice system is designed as a **hybrid input layer** where voice, keyboard/mouse, and XR pointer all converge on the same command dispatch, so users can mix modalities freely:

- **Voice** excels at search and mode switching: "aiuto extrude", "struttura", "annulla"
- **Keyboard** excels at fast, precise selection: press `3` to pick the third result
- **Mouse/pointer** excels at direct manipulation: click an item, hover for feedback
- **XR pointer** maps naturally to spatial selection in a 3D help panel

The goal is not to make any single modality self-sufficient, but to let each do what it does best within the same interaction flow.

## Why Deterministic Parsing (No ML)

Voice commands are parsed with a **deterministic pattern matcher**, not a language model. Reasons:

1. **Latency**: Pattern matching is instant. LLM round-trips add 200ms-2s, unacceptable for navigation commands that should feel like keystrokes.
2. **Predictability**: The same utterance always produces the same action. No temperature, no prompt drift, no hallucinated commands.
3. **Offline**: Works without network. Web Speech API handles recognition; everything after that is local.
4. **Debuggability**: When a command fails, the log shows exact tokens → parse result → action. Easy to trace.
5. **Vocabulary is bounded**: Ridley has ~40 voice commands and ~130 help entries. This fits phrase tables perfectly. An LLM adds complexity without value here.

The `:ai` mode exists as an escape hatch for open-ended natural language instructions (e.g., "make the box wider"), which _do_ need LLM reasoning. But structured navigation and editing stay deterministic.

## Architecture Overview

```
                    ┌──────────────────┐
                    │  Web Speech API  │
                    │   (microphone)   │
                    └────────┬─────────┘
                             │ transcript
                    ┌────────▼─────────┐
                    │   speech.cljs    │ push-to-talk + continuous
                    └────────┬─────────┘
                             │ on-utterance / on-event
     ┌───────────────────────▼────────────────────────┐
     │              voice/core.cljs                    │
     │  on-utterance → tokenize → parse → execute     │
     │                                                 │
     │  dispatch-action! ◄── keyboard / mouse / XR     │
     └──────────┬──────────────────────────────────────┘
                │ handler callbacks
     ┌──────────▼──────────────────────────────────────┐
     │              core.cljs (main)                   │
     │  ai-insert-code / ai-edit-code / ai-navigate   │
     │  → CodeMirror operations → auto-save → sync    │
     └─────────────────────────────────────────────────┘
```

Key property: **`dispatch-action!`** is the single entry point for all input modalities. Voice, keyboard, mouse clicks, and XR pointer all call the same function with the same `{:action :params}` structure. This means:

- Adding a new input source (e.g., MIDI controller, gamepad) requires zero changes to the command system
- The action log is modality-agnostic — useful for debugging and replay
- State transitions are guaranteed consistent regardless of input source

## Module Responsibilities

### `voice/state.cljs` — Single State Atom

All voice-related state lives in one atom (`voice-state`), including:

| Key | Purpose |
|-----|---------|
| `:mode` | Current mode: `:structure`, `:help`, `:ai`, `:turtle` |
| `:sub-mode` | Overlay: `:dictation`, `:selection`, or `nil` |
| `:language` | `:it` or `:en` — affects parsing, TTS, and help docs |
| `:voice` | Listening state, transcripts, pending speech |
| `:help` | Results, page, query, view (`:categories`/`:search`/`:browse`) |
| `:buffer` | Mirror of editor content (for context-aware commands) |
| `:cursor` | Current position, form, parent form |
| `:scene` | Known meshes, shapes, paths (for "select mesh X" future) |

Why a single atom: Reagent-style watches drive all UI updates. The voice panel, help panel, and mode indicator all re-render when relevant keys change. No pub/sub, no event bus — just `add-watch` on one atom.

### `voice/speech.cljs` — Web Speech API Wrapper

Two modes:

- **Push-to-talk**: Single recognition session, returns one final transcript. Good for deliberate commands.
- **Continuous**: Auto-restarting recognition with a stability timer. Fires `:utterance` events as speech finalizes. Commands can be chained with delimiters ("box poi avanti dieci" → two commands).

The stability timer (1500ms) handles a Web Speech API quirk: sometimes `onresult` fires with `isFinal: false` but no final result follows. The timer promotes stable interim results to avoid dropped commands.

### `voice/parser.cljs` — Tokenizer + Router

Pipeline: `text → normalize → tokenize → strip fillers → route to mode parser`

**Filler stripping** is essential for Italian, where articles and prepositions appear between command words: "cancella **il** prossimo" → `["cancella" "prossimo"]`. Without this, every phrase table would need variants with and without articles.

**Parse priority** (checked in order, first match wins):
1. Mode switch — always available ("aiuto", "struttura")
2. Language switch — always available ("inglese", "italiano")
3. Meta commands — always available ("annulla", "esegui", "stop")
4. Dictation entry — only from structure mode
5. Mode-specific parser — delegates to `structure/parse`, `help/parse`, etc.

### `voice/i18n.cljs` — Bilingual Command Tables

Structure: nested maps keyed by command → language → phrases.

```clojure
{:navigation
  {:next     {:it ["prossimo" "avanti"] :en ["next" "forward"]}
   :previous {:it ["precedente" "indietro"] :en ["previous" "back"]}
   ...}}
```

Phrases are matched **longest first** to handle multi-word commands correctly: "fine dettatura" must match before "fine". Word boundary enforcement prevents "avanti" from matching inside "avantino".

Adding a language requires adding entries to phrase maps — no code changes needed.

### `voice/modes/structure.cljs` — Paredit-by-Voice

The structure mode parser handles code editing commands inspired by paredit/vim:

- **Navigation**: next/previous/into/out/first/last + optional count
- **Editing**: delete, copy, cut, paste (with position), change (with value)
- **Structural**: slurp, barf, wrap (with optional head), unwrap, raise, join
- **Insertion**: insert, append, new-form/list/map (with position)
- **Selection**: enter sub-mode, or immediate select (word/line/all)
- **Transforms**: keyword, symbol, hash, deref, capitalize, uppercase, number

The "change" command is the most complex — it parses prepositions ("cambia **in** dieci") and handles negative numbers ("meno cinque" → `-5`).

### `voice/help/` — Interactive Symbol Reference

#### `help/db.cljs` — Entry Database

~130 entries across three tiers:

| Tier | Content | Count |
|------|---------|-------|
| `:ridley` | DSL commands (f, th, box, extrude...) | ~45 |
| `:clojure-base` | Core Clojure (def, fn, map, filter...) | ~35 |
| `:clojure-extended` | Extended stdlib (atom, async, string...) | ~50 |

Each entry carries:
- **`:template`** — Insertable code with `|` cursor marker: `"(extrude | (f 20))"`
- **`:doc`** — Bilingual doc strings
- **`:aliases`** — Voice-friendly names: `{:it ["avanti"] :en ["forward"]}` for `f`
- **`:tier`** and **`:category`** — For browsing

#### `help/core.cljs` — Search + Parse

**Fuzzy search scoring:**
- Exact match: 100
- Prefix: 80
- Contains: 50
- Alias match: -5 from direct score
- Doc match: 30

Why this scoring: When a user says "box", exact match on `box` (100) beats "mailbox" containing "box" (50). Alias matches score slightly lower than direct name matches because the canonical name is what appears in code.

**Help mode parser** (voice commands while in help):
- Numbers 1-7 → select item on current page (page-relative)
- "prossimo"/"precedente" → paginate
- "esci"/"indietro" → exit help, return to structure mode
- Tier names → browse that tier
- Anything else → fuzzy search query

#### `help/ui.cljs` — HTML Rendering

Three views rendered as HTML strings (innerHTML):
- **Categories**: Lists the 3 tiers with entry counts
- **Search**: Query results with pagination (7 per page)
- **Browse**: Tier contents grouped by category

Uses `data-action` and `data-index` attributes for event delegation (see below).

## Hybrid Interaction: Desktop

### Event Delegation (Mouse)

The help panel is rendered via `innerHTML`, which destroys DOM listeners on every re-render. Instead of re-attaching listeners, we use **event delegation**: a single click listener on the panel container walks up the DOM to find `data-action` attributes.

```
click on <span class="help-sym"> inside <div data-action="select-item" data-index="2">
  → walk up → find data-action="select-item"
  → dispatch-action! :help-select {:index 2}
```

This pattern survives innerHTML re-renders with zero listener management.

### Keyboard (Window-Level)

A `keydown` listener on `window` with a mode guard (`(= mode :help)`) handles:

| Key | Action |
|-----|--------|
| `1`-`7` | Select item at page-relative position |
| `←` / `PageUp` | Previous page |
| `→` / `PageDown` | Next page |
| `Escape` | Exit help mode |

The listener is always attached but only fires actions in help mode. This avoids focus management issues — the keyboard works regardless of which element has focus.

### Indexing Convention

All selection indices are **page-relative** (0-6 internally, displayed as 1-7). The `:help-select` handler computes the absolute index: `(+ (* page 7) idx)`. This holds for all three input sources:

- Voice: "tre" → parser produces `{:index 2}` (1-based → 0-based)
- Keyboard: key `3` → `{:index 2}`
- Click: `data-index="2"` → `{:index 2}`

## Hybrid Interaction: XR (Planned)

Phase 2 will add a 3D help panel in VR:

- **3D button meshes** positioned in front of the controller, with `userData` carrying `{:action :help-select :index n}`
- **Raycaster integration**: existing XR hover/click system extended to help buttons
- **State watch**: help panel updates when `voice-state :help` changes
- **Visibility**: panel shown only when mode is `:help`

The same `dispatch-action!` function handles XR pointer clicks — no special XR command path needed.

## Continuous Mode and Command Chaining

In continuous listening, utterances are split on **delimiter words** ("poi", "then", "vai", "e poi") before parsing. This allows chaining: "prossimo poi prossimo poi tre" → three separate commands processed sequentially.

Each segment is parsed and executed independently. No cross-utterance buffering — every finalized utterance is self-contained. This keeps the system stateless between utterances (mode changes within a chain do take effect for subsequent segments).

## Circular Dependency Avoidance

The voice system cannot import `core.cljs` (which imports the voice system). Instead:

- `core.cljs` calls `voice/init!` with a handler map
- `voice/core.cljs` stores handlers in a `defonce` atom
- Actions dispatch through these callbacks

This keeps the voice system self-contained and testable — it only knows about handler _interfaces_, not implementations.

## File Map

```
voice/
├── core.cljs              Orchestrator: utterance handling, action dispatch
├── speech.cljs            Web Speech API: push-to-talk + continuous
├── state.cljs             Single atom: mode, language, help, buffer, cursor
├── parser.cljs            Tokenizer, filler strip, mode router
├── i18n.cljs              IT/EN phrase tables, numbers, fillers
├── panel.cljs             Voice status panel (HTML string rendering)
├── modes/
│   └── structure.cljs     Paredit-style command parser
└── help/
    ├── db.cljs            ~130 entries: templates, docs, aliases
    ├── core.cljs          Fuzzy search engine + help mode parser
    └── ui.cljs            Help panel views: categories, search, browse
```
