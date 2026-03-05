# Ridley — Accessibility

Ridley includes features designed for blind and low-vision users who rely on screen readers (JAWS, NVDA, VoiceOver). The primary accessibility tool is the **`(describe)`** command, which generates AI-powered verbal descriptions of 3D geometry directly from the REPL.

---

## The Problem

A blind user working in Ridley can write code that generates 3D geometry, but cannot verify the visual result. The previous workflow required:

1. Export the model as STL
2. Open it in an accessible slicer
3. Generate slice images from various angles
4. Manually pass images to an AI chat (e.g., Gemini)
5. Read the AI's description

This involved multiple tools and many manual steps. `(describe)` replaces all of this with a single command.

---

## Quick Start

### 1. Configure an AI Provider

`(describe)` requires a **vision-capable** AI model. Supported providers:

| Provider | Model | How to get an API key |
|----------|-------|-----------------------|
| **Google Gemini** | `gemini-2.0-flash` | [Google AI Studio](https://aistudio.google.com/apikey) — free tier available |
| **OpenAI** | `gpt-4o` | [OpenAI Platform](https://platform.openai.com/api-keys) — pay-per-use |
| **Anthropic** | `claude-sonnet-4-20250514` | [Anthropic Console](https://console.anthropic.com/) — pay-per-use |

**Google Gemini** is recommended for accessibility users because it has a generous free tier.

Configure from the REPL (or via the Settings panel):

```clojure
;; Example: Google Gemini
(set-ai-setting! :provider :google)
(set-ai-setting! :google-key "AIza...")
(set-ai-setting! :google-model "gemini-2.0-flash")

;; Example: OpenAI
(set-ai-setting! :provider :openai)
(set-ai-setting! :openai-key "sk-...")
(set-ai-setting! :model "gpt-4o")

;; Verify configuration
(ai-status)
;; => {:provider :google, :model "gemini-2.0-flash", :ready? true, :enabled? true}
```

**Note:** API keys are stored in your browser's localStorage. They are never sent anywhere except to the AI provider's API.

### 2. Create Some Geometry

```clojure
(register gear
  (mesh-difference
    (mesh-union (cyl 25 10)
      (mesh-union (for [i (range 12)]
        (attach (box 4 6 10) (th (* i 30)) (f 22)))))
    (cyl 6 12)))
```

### 3. Describe It

```clojure
(describe :gear)
```

Ridley will:
1. Render 7 views of the object (6 orthographic + 1 perspective)
2. Collect structural metadata (bounds, volume, vertex/face count)
3. Include the source code that generated the object
4. Send everything to the AI for analysis

Progress messages appear in the REPL:
```
Analyzing geometry... (generating views)
Sending to AI... (7 images + source code)
Still waiting... (10s elapsed)

=== Description of :gear ===
[AI-generated description here]
===
Type (ai-ask "your question") for follow-up, (end-describe) to close.
```

### 4. Ask Follow-Up Questions

```clojure
(ai-ask "How thick are the gear teeth?")
(ai-ask "Is the central hole a through-hole?")
(ai-ask "Would this print well without supports?")
```

The AI remembers the full conversation, including all previously sent images.

### 5. Close the Session

```clojure
(end-describe)
```

---

## Command Reference

| Command | Description |
|---------|-------------|
| `(describe)` | Describe all visible geometry |
| `(describe :name)` | Describe a specific registered object |
| `(ai-ask "question")` | Ask a follow-up in the active session |
| `(end-describe)` | Close the session and clear conversation |
| `(cancel-ai)` | Cancel the in-progress AI call (session stays open) |
| `(ai-status)` | Check AI provider configuration |

---

## How It Works

### Data Sent to the AI

Each `(describe)` call sends:

1. **7 rendered images** (512x512 PNG each):
   - Front, Back, Left, Right, Top, Bottom (orthographic)
   - One perspective 3/4 view

2. **Source code** from the definitions panel — gives the AI procedural understanding of how the geometry was constructed

3. **Structural metadata** (JSON):
   - Bounding box, center, dimensions
   - Vertex and face count
   - Volume and surface area (for manifold meshes)
   - Face group identifiers
   - List of registered objects in the scene

### AI Self-Refinement

If the AI determines it needs additional views or cross-sections to give an accurate description, it can request them. For example:

```json
{"need_more": true, "requests": [
  {"type": "slice", "axis": "z", "position": 25.0},
  {"type": "view", "from": [1, 1, 0.5], "label": "low-angle front-right"}
]}
```

Ridley renders the requested images automatically and sends them back. This loop runs at most 2 extra rounds. The entire process is transparent — the user only sees the final description.

### Description Style

The AI is instructed to write for screen reader consumption:
- Uses spatial and structural references ("the cylindrical protrusion on the top face"), never visual-only references ("the blue part")
- Provides everyday analogies ("shaped like a donut", "resembles a gear")
- Gives approximate proportions and absolute sizes
- Describes spatial relationships (above, beside, through, concentric)
- Notes symmetry, patterns, and repetitions
- Mentions 3D printing concerns (overhangs, thin walls, disconnected parts)

---

## Providers in Detail

### Google Gemini (Recommended)

- **Free tier**: generous quota for `gemini-2.0-flash`
- **Setup**: Go to [Google AI Studio](https://aistudio.google.com/apikey), sign in with Google, create an API key
- **Model**: `gemini-2.0-flash` (fast, good vision capabilities)
- **Alternative**: `gemini-2.5-pro-preview-05-06` (slower but more detailed)

```clojure
(set-ai-setting! :provider :google)
(set-ai-setting! :google-key "AIza...")
(set-ai-setting! :google-model "gemini-2.0-flash")
```

### OpenAI

- **Pricing**: pay-per-use, ~$0.01-0.05 per describe call
- **Setup**: Go to [OpenAI Platform](https://platform.openai.com/api-keys), create an API key, add billing
- **Model**: `gpt-4o` (strong vision, good spatial reasoning)

```clojure
(set-ai-setting! :provider :openai)
(set-ai-setting! :openai-key "sk-...")
(set-ai-setting! :model "gpt-4o")
```

### Anthropic Claude

- **Pricing**: pay-per-use, similar to OpenAI
- **Setup**: Go to [Anthropic Console](https://console.anthropic.com/), create an API key, add billing
- **Model**: `claude-sonnet-4-20250514` (excellent at understanding code + visuals)

```clojure
(set-ai-setting! :provider :anthropic)
(set-ai-setting! :anthropic-key "sk-ant-...")
(set-ai-setting! :model "claude-sonnet-4-20250514")
```

### Ollama (Local)

Ollama can run vision models locally (e.g., LLaVA). Quality varies significantly compared to cloud providers.

```clojure
(set-ai-setting! :provider :ollama)
(set-ai-setting! :ollama-url "http://localhost:11434")
(set-ai-setting! :ollama-model "llava:13b")
```

### Groq

Groq's current models (llama-3.3-70b) do **not** support vision. `(describe)` will show an error if Groq is configured. Groq can still be used for the AI code generation feature (`/ai`).

---

## Non-Vision Models

The text-based AI features (`/ai`, `/ai!`) work with any model including non-vision ones (Groq, Ollama text models). Only `(describe)` and `(ai-ask)` require vision capabilities.

---

## View Capture (Standalone)

The rendering infrastructure behind `(describe)` is also available as standalone functions for documentation, debugging, or manual inspection:

```clojure
;; Render a single view
(render-view :top)                           ; Returns a data URL
(render-view :perspective :target :my-object)

;; Render from a custom direction
(render-view [1 0.5 0.3])

;; Render a cross-section
(render-slice :my-object :z 15)              ; Slice at Z=15

;; Save images
(save-image (render-view :top) "top-view.png")
(save-image (render-slice :cup :z 10) "cup-slice.png")
(save-views :target :my-object)              ; All 7 views as ZIP
```

---

## Screen Reader Notes

- All output is plain text printed to the REPL — screen readers read it natively
- No visual-only feedback during the describe process; progress is communicated via text messages
- During long waits, periodic updates ("Still waiting... (12s elapsed)") every 10 seconds, so screen reader users know the system isn't frozen
- All REPL commands work via keyboard input — no mouse or keyboard shortcuts required

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| "No AI provider configured" | Run `(ai-status)` and configure a provider |
| "(describe) requires a vision-capable model" | Switch to Gemini, Claude, or GPT-4o |
| "No geometry to describe" | Create and `register` some shapes first |
| "Object :name not found" | Check `(visible-names)` for registered objects |
| "AI authentication failed" | Check your API key |
| "AI rate limit reached" | Wait a moment and try again |
| API call taking too long | Use `(cancel-ai)` to abort, try again |
| Want to start over | Use `(end-describe)` then `(describe)` |
