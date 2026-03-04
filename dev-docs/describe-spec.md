# Ridley — `(describe)` Feature Specification

## Purpose

Enable users (especially blind/low-vision users relying on screen readers) to obtain a detailed verbal description of 3D geometry directly from the REPL, without leaving Ridley or depending on external tools.

**Current workflow (Edis):** Export STL → open in accessible slicer → generate slice images from various angles → manually pass images to Gemini → read description. Multiple tools, many manual steps.

**Target workflow:** `(describe)` → wait a few seconds → read/hear description → ask follow-up questions → done.

---

## Overview

`(describe)` opens an **interactive AI session** with three phases:

1. **Automatic first round** — Ridley gathers a default set of visual data and the source code, sends everything to a vision-capable LLM, and receives an initial description.
2. **AI self-refinement** — If the AI determines it needs additional views or sections, Ridley generates them automatically (transparent to the user) before producing the description.
3. **Interactive follow-up** — The user can ask questions, request specific views or cross-sections, and the AI responds with additional analysis. The session remains open until explicitly closed.

---

## Phase 1: Automatic First Round

### Trigger

```clojure
(describe)                       ; Describe all visible geometry
(describe :my-object)            ; Describe a specific registered object
(describe my-mesh)               ; Describe a mesh by reference
```

### Data Gathering

When `(describe)` is called, Ridley automatically prepares:

#### A. Source Code Context

The DSL source that generated the target object(s). This is critical — it gives the AI procedural understanding of the geometry, not just visual.

- If the target is a registered object, include the definition code (from the definitions panel or REPL history)
- If describing all visible geometry, include the full definitions panel content
- Include relevant variable definitions and function bodies

#### B. Default Views (rendered by Three.js)

Six orthographic projections, rendered to offscreen canvases:

| View     | Camera Position | Label    |
|----------|----------------|----------|
| Front    | +Y looking -Y  | `front`  |
| Back     | -Y looking +Y  | `back`   |
| Left     | -X looking +X  | `left`   |
| Right    | +X looking -X  | `right`  |
| Top      | +Z looking -Z  | `top`    |
| Bottom   | -Z looking +Z  | `bottom` |

Plus one perspective 3/4 view (isometric-style) for overall shape context.

**Rendering settings:**
- Orthographic camera (no perspective distortion)
- White background, dark mesh color
- Edge rendering enabled for clarity
- Auto-fit camera to geometry bounds with padding
- Resolution: 512×512px per view (sufficient for LLM vision, small enough for fast upload)
- Grid and axes disabled in captures

#### C. Structural Metadata (JSON)

```json
{
  "target": ":my-object",
  "bounds": {"min": [x,y,z], "max": [x,y,z], "size": [sx,sy,sz]},
  "center": [cx, cy, cz],
  "vertices": 1234,
  "faces": 2468,
  "manifold": true,
  "volume": 45678.9,
  "face_groups": [":top", ":bottom", ":side"],
  "registered_objects_in_scene": [":base", ":arm", ":connector"]
}
```

### Prompt Construction

The first-round prompt sent to the vision LLM:

```
You are analyzing a 3D model created with Ridley, a turtle-graphics-based
parametric CAD tool. Your description will be read by a screen reader for
a blind user, so be precise, spatial, and concrete.

## Source Code
<source>
{definitions panel content or object definition}
</source>

## Structural Data
{JSON metadata}

## Views
{7 images: 6 orthographic + 1 perspective, each labeled}

## Task — Initial Description

Provide a comprehensive description of this 3D object covering:
1. **Overall shape**: What does this look like? Use everyday analogies.
2. **Dimensions**: Approximate proportions and absolute sizes.
3. **Key features**: Holes, protrusions, cavities, symmetry, patterns.
4. **Spatial layout**: How features relate to each other (above, beside,
   through, etc.)
5. **Printability notes**: Any obvious overhangs, thin walls, or issues
   visible from these views.

If you need additional views or cross-sections to give an accurate
description, respond with a JSON request in a fenced code block:

```json
{"need_more": true, "requests": [
  {"type": "slice", "axis": "z", "position": 25.0},
  {"type": "view", "from": [1, 1, 0.5], "label": "low-angle front-right"}
]}
```

You may include explanatory text before or after the JSON block.
```

### AI Self-Refinement Loop

If the AI responds with `"need_more": true`:

1. Ridley parses the requests
2. Generates the requested images/slices (see Technical: Slice Generation below)
3. Sends a follow-up message with the new images
4. The AI produces the final description

This loop runs at most **2 additional rounds** to prevent runaway. If after 2 rounds the AI still wants more, it produces its best description with what it has and notes what it couldn't verify.

The entire loop is **transparent to the user** — they see only the final description.

### Output

The description is:
- Printed to the REPL output (for screen reader consumption)
- Stored in a panel if one is registered as `:describe` (optional)
- Available as the return value for programmatic use

---

## Phase 2: Interactive Follow-Up

After the initial description, the user enters an interactive session. The conversation history (including all images sent so far) is maintained.

### User Interaction

```clojure
;; The session is active after (describe)
;; User types questions as strings in the REPL:

(ai-ask "What does the back look like?")
(ai-ask "Is the hole on the left side a through-hole?")
(ai-ask "Show me a cross section at height 15")
(ai-ask "How thick is the wall near the bottom?")
(ai-ask "Would this print well upside down?")

;; End the session
(end-describe)
```

### How It Works

1. User calls `(ai-ask "question")`
2. Ridley sends the question to the AI as a new message in the conversation
3. The AI may:
   - Answer directly from existing images and context
   - Request additional views/slices (Ridley generates and sends automatically)
   - Answer with a note about what it couldn't determine
4. The response is printed to REPL output

### Smart Request Detection

The AI's response is parsed for structured view/slice requests embedded in the text. For example, if the AI says it needs a cross-section to answer, Ridley generates it, sends it, and the AI completes its answer — all within a single `(ai-ask ...)` call from the user's perspective.

---

## Phase 3: Technical Implementation

### View Rendering

New function in `viewport/core.cljs`:

```clojure
(defn render-view
  "Render a view of the scene to a data URL (base64 PNG).
   camera-spec is one of:
   - :front, :back, :left, :right, :top, :bottom (orthographic)
   - :perspective (default 3/4 view)
   - [x y z] (custom camera position, looking at center)"
  [camera-spec & {:keys [width height background]
                  :or {width 512 height 512 background 0xffffff}}]
  ...)
```

Implementation approach:
- Use the **existing** WebGLRenderer with a `WebGLRenderTarget` (offscreen
  framebuffer) — avoids creating a second WebGL context (browsers limit the
  number of active contexts to ~8-16)
- Create an OrthographicCamera (or PerspectiveCamera for 3/4 view)
- Position camera based on spec, looking at geometry center
- Fit frustum to geometry bounds
- Render to the render target, read pixels, return as data URL
- Reuse the render target across captures for performance

### Slice Generation

Ridley already has `slice-mesh`, which slices a mesh at the turtle's current
position/heading and returns 2D shape contours. The `(describe)` system uses
this directly:

```clojure
;; Position turtle at desired slice plane, then slice
(reset [0 0 25]) (tv 90)            ; horizontal slice at Z=25
(def contours (slice-mesh :my-object))
(stamp contours)                     ; visualize as 2D shape at turtle pose
;; Then render-view from the slice normal to capture the image
```

For `(describe)`, the system automates this: given a slice request
(e.g., `{"type": "slice", "axis": "z", "position": 25.0}`), it:

1. Saves turtle state
2. Positions turtle at the requested plane (e.g., `(reset [0 0 25]) (tv 90)`)
3. Calls `(slice-mesh target)` to get contours
4. Creates contour meshes in a **dedicated offscreen `THREE.Scene`** (not the
   user's main scene) — this eliminates the risk of polluting the scene if
   rendering fails or cleanup is interrupted
5. Renders the offscreen scene from the slice normal direction using the
   shared `WebGLRenderTarget`
6. Restores turtle state; the offscreen scene is discarded

This reuses existing infrastructure with no new geometry code needed. The
offscreen scene is lightweight (only 2D contour lines) and is garbage-collected
after use.

### LLM Integration

#### API Configuration

LLM provider settings (provider, model, API key) are managed through
Ridley's existing configuration system. No additional DSL commands needed.

Optional REPL utility to verify the configuration:

```clojure
(ai-status)    ; => {:provider :google, :model "gemini-2.0-flash", :ready? true}
```

#### Conversation State

```clojure
;; Atom holding the active describe session
(def describe-session
  (atom {:active? false
         :target nil              ; :keyword or mesh reference
         :messages []             ; conversation history [{:role :content}]
         :images {}               ; cached rendered images {:label data-url}
         :source-code ""          ; DSL source
         :metadata {}             ; structural JSON
         :provider nil            ; LLM provider config
         :abort-controller nil    ; js/AbortController for current API call
         :round 0                 ; current refinement round (max 2)
         }))
```

### Response Parsing

The AI response is checked for structured requests. The AI is instructed to
wrap any data request in a ```json fenced code block, which makes extraction
reliable:

```clojure
(defn parse-ai-response [text]
  (let [;; Extract fenced JSON blocks (```json ... ```)
        json-blocks (re-seq #"```json\s*\n([\s\S]*?)```" text)
        parsed      (some->> json-blocks
                             (map second)
                             (keep #(try (js/JSON.parse %) (catch :default _ nil)))
                             (filter #(.-need_more %))
                             first)]
    (if parsed
      {:type :request
       :data (js->clj parsed :keywordize-keys true)
       :text (str/replace text #"```json[\s\S]*?```" "")}
      {:type :description :text text})))
```

If the response contains both text and a request, the text is a partial
description and the request asks for more data to complete it.

---

## DSL API Summary

### Core Commands

| Function | Description |
|----------|-------------|
| `(describe)` | Describe all visible geometry |
| `(describe :name)` | Describe a specific registered object |
| `(describe mesh)` | Describe a mesh by reference |
| `(ai-ask "question")` | Ask a follow-up question in the active session |
| `(end-describe)` | Close the interactive session |
| `(cancel-describe)` | Cancel an in-progress describe/ai-ask call |

### Configuration

| Function | Description |
|----------|-------------|
| `(ai-status)` | Verify LLM provider configuration |

### Utility (also usable standalone)

| Function | Description |
|----------|-------------|
| `(render-view :top)` | Render a single orthographic view (returns data URL) |
| `(render-view [1 1 0.5])` | Render from custom angle |
| `(slice-mesh :name)` | Cross-section at turtle plane (existing command) |
| `(save-views :my-object)` | Save all 6+1 views as PNG files (browser download) |

---

## Error Handling

All errors are reported as text in the REPL (screen-reader friendly). No silent
failures.

### API Errors

| Condition | User Message |
|-----------|-------------|
| No API key configured | `"Error: No AI provider configured. Use (ai-status) to check."` |
| Invalid/expired API key | `"Error: AI authentication failed. Check your API key."` |
| Network timeout (30s) | `"Error: AI request timed out. Try again or check your connection."` |
| Rate limited | `"Error: AI rate limit reached. Wait a moment and try again."` |
| Model unavailable | `"Error: Model {name} is not available. Check (ai-status)."` |
| Context too large | `"Error: Scene too complex for AI context. Try (describe :single-object)."` |

### Rendering Errors

| Condition | User Message |
|-----------|-------------|
| WebGL context lost | `"Error: WebGL context lost. Refresh the browser and try again."` |
| No geometry in scene | `"Error: No geometry to describe. Create some shapes first."` |
| Named object not found | `"Error: Object :name not found. Check (list-objects)."` |

### Session Errors

| Condition | Behavior |
|-----------|----------|
| `(ai-ask)` without active session | `"No active describe session. Call (describe) first."` |
| `(describe)` while session active | Warn and close previous: `"Closing previous session for :old-target."` then start new |
| `(cancel-describe)` without active session | No-op, silent |

---

## Cancellation

### `(cancel-describe)`

Cancels any in-progress `(describe)` or `(ai-ask)` API call.

Implementation:
- Each API call stores its `AbortController` in the session atom
- `(cancel-describe)` calls `.abort()` on the controller
- The pending promise rejects, caught by the error handler
- User sees: `"Describe cancelled."`
- Session remains active (user can call `(ai-ask)` again or `(end-describe)`)

```clojure
(def describe-session
  (atom {:active? false
         :target nil
         :messages []
         :images {}
         :source-code ""
         :metadata {}
         :provider nil
         :abort-controller nil}))   ; <-- AbortController for current API call
```

### Note: General Script Cancellation

Long-running SCI script execution (e.g., infinite loops, expensive geometry)
currently has **no interruption mechanism** — the UI freezes until completion
or browser refresh. This is a separate issue from `(describe)` cancellation
and should be addressed independently (e.g., via Web Worker evaluation or
cooperative yielding). The `(describe)` cancellation works because the slow
part (API call) is asynchronous and supports `AbortController`.

---

## Context Window Management

As the interactive session progresses, conversation history grows with
accumulated images. To prevent context overflow:

1. **Image deduplication** — cached views are sent once; if the AI references
   a previously-sent view, the image is not re-sent.
2. **History summarization** — after 5+ rounds of `(ai-ask)`, older exchanges
   are summarized into a compact text block, keeping the original images but
   dropping verbose descriptions that the AI already incorporated.
3. **Image budget** — maximum ~20 images in a single conversation. Beyond
   this, oldest non-essential images (e.g., superseded slices) are dropped
   with a note to the AI: "Earlier slice images removed for context space."

---

## Screen Reader Considerations

- All output is plain text to the REPL — screen readers read it natively
- No visual-only feedback during the describe process; progress is communicated via text:
  ```
  Analyzing geometry... (generating views)
  Sending to AI... (7 images + source code)
  Waiting for AI response... (use (cancel-describe) to abort)
  AI requested 2 additional views... (generating)
  Waiting for AI response...

  === Description of :my-object ===
  [description text]
  ===
  Type (ai-ask "your question") for follow-up, (end-describe) to close.
  ```
- During long waits, periodic updates: `"Still waiting... (12s elapsed)"`
  every 10 seconds, so screen reader users know the system isn't frozen
- Descriptions avoid visual-only references ("the blue part") — instead use spatial/structural references ("the cylindrical protrusion on the top face")
- The system prompt explicitly instructs the AI to write for screen reader consumption

---

## JAWS Compatibility Note

JAWS on Windows intercepts certain key combinations (notably Ctrl+F and many Insert+ combinations). Ridley's keybindings should:

1. Avoid Ctrl+F as a primary binding (use for JAWS passthrough instead)
2. Provide a keybinding configuration mechanism (JSON map in localStorage)
3. Document which bindings are known to conflict with JAWS
4. Ensure all `(describe)` / `(ai-ask)` interactions work purely through the REPL input — no keyboard shortcuts required

A full JAWS keybinding audit should be done with Edis to identify all conflicts.

---

## Implementation Phases

### Phase A: View Rendering Infrastructure
1. `WebGLRenderTarget` setup using existing renderer
2. Orthographic camera positioning for 6 standard views
3. `render-view` function returning data URLs
4. `save-views` utility for debugging/testing (browser download of PNGs)

### Phase B: Slice Rendering
1. Automate turtle positioning + `slice-mesh` + offscreen scene + render pipeline
2. Clean up (restore turtle state, discard offscreen scene)
3. Test with various mesh types (primitives, extrusions, booleans)

### Phase C: LLM Integration
1. `ai-status` check command
2. API abstraction layer (use existing provider config, start with one cloud provider)
3. Prompt construction with source code + images + metadata
4. Response parsing (description vs. request-for-more)

### Phase D: Describe Session
1. `(describe)` command — automatic first round
2. AI self-refinement loop (transparent extra views)
3. `(ai-ask)` command — interactive follow-up
4. `(end-describe)` — session cleanup
5. `(cancel-describe)` — AbortController-based cancellation
6. Error handling for all API/rendering failure modes
7. Progress messages with elapsed time for screen reader feedback
8. Session conflict detection (warn on overlapping describes)

### Phase E: Polish
1. JAWS keybinding audit with Edis
2. Prompt tuning based on real usage feedback
3. Caching (don't re-render views if geometry hasn't changed)
4. Panel output option (for non-screen-reader users who want persistent text)
5. Context window management (history summarization, image budget)

---

## Open Questions

1. **Image format**: PNG data URLs are large for multimodal APIs. Should we use JPEG for smaller payloads? Or resize to 256×256 for faster API calls at the cost of detail?

2. **Token budget**: 7 images + source code + metadata can consume significant context. Should the first round send fewer images (e.g., only 3 orthographic + 1 perspective) and let the AI request more?

3. **Ollama multimodal**: Local models like LLaVA have significantly weaker spatial reasoning than cloud models. Should `(describe)` warn if using a local model, or adapt its prompt strategy?

4. **Cost**: Cloud API calls with multiple images can be expensive. Should there be a budget/limit configuration? Or a "quick describe" mode that sends only the source code (no images)?

5. **Curb-cut features**: Which aspects of `(describe)` should be surfaced as general-purpose tools? E.g., `(render-view)` for documentation, `(slice-mesh)` for debugging, AI-based printability analysis for all users.
