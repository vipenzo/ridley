# Ideas for a Ridley-Aware LLM

## The Problem

Current LLMs (Gemini, GPT, Claude) struggle to generate correct Ridley DSL code, particularly for spatial operations. They understand *what* to build conceptually but fail to map that understanding to turtle-based commands.

Root causes:
- **No training data**: Ridley is a custom DSL — zero presence in any training corpus
- **Turtle is anti-LLM**: the turtle coordinate system is sequential and relative. Each movement depends on current state. LLMs reason much better with absolute coordinates (`translate([0,0,15])` vs `(tv 90) (f 15)`)
- **Spatial simulation**: to predict the result of a turtle sequence, you must mentally simulate each step. LLMs are bad at this

Evidence: when asked to make a vertical hole through a cube, Gemini invented `rotate-z` (an OpenSCAD-like function that doesn't exist) instead of using `(attach (cyl 8 32) (tv 90))`. Even with few-shot examples in the prompt and visual feedback, the AI struggles to correct spatial errors.

## Approaches Considered

### 1. OpenSCAD Compatibility Layer

**Idea**: provide OpenSCAD-like functions with absolute coordinates (`translate`, `rotate`, `scale`) that map directly to Manifold operations, bypassing turtle entirely.

**Pros**:
- LLMs already know OpenSCAD well (abundant training data)
- Users coming from OpenSCAD would benefit too
- Manifold already supports absolute transforms natively

**Cons**:
- Lisp-syntax `(cube 30)` vs OpenSCAD `cube(30)` would confuse the LLM anyway
- Name conflicts with existing Ridley functions
- Maintaining two parallel APIs increases complexity
- Parsing actual OpenSCAD syntax is a lot of work for a limited bridge

**Verdict**: the syntax mismatch defeats the purpose. If the LLM has to write `(translate [0 0 15] (rotate [90 0 0] (cylinder 8 32)))` it's not really OpenSCAD anymore — it's a third language.

### 2. Expanded Few-Shot Examples + Dynamic Retrieval (RAG)

**Idea**: build a comprehensive cookbook of spatial patterns (vertical hole, horizontal attachment, circular array, etc.) and dynamically select the most relevant examples based on the user's request.

**Pros**:
- Works today with any provider, no fine-tuning needed
- Already partially implemented (tier system with few-shot examples)

**Cons**:
- Token-expensive
- Doesn't teach the model *understanding*, just pattern matching
- Breaks down for novel compositions not covered by examples

**Verdict**: good as a stopgap. Already in use with the tier-1/tier-2 prompt system. Can be improved with better retrieval, but has a ceiling.

### 3. Self-Play Exploration + Fine-Tuning (Recommended Long-Term)

**Idea**: let an LLM explore Ridley autonomously — generate code variations, execute them, observe the visual results — then use the accumulated (code, images) pairs to fine-tune a local vision model.

This is analogous to AlphaGo's self-play: the model builds its own training data through experimentation.

#### Phase 1: Exploration Loop (no fine-tuning needed)

The AI systematically explores Ridley primitives:

```
For each primitive (cyl, box, sphere, extrude, ...):
  For each turtle variation (tv 90, th 45, f 20, ...):
    1. Generate code
    2. Execute in Ridley
    3. Capture views from multiple angles + slices
    4. Store (code, images, auto-description) tuple
```

Infrastructure already exists:
- Programmatic code execution via SCI
- Automatic view capture (capture directives system)
- Slice capture
- Vision API for describing results

Estimated time for 1000 examples: **3-5 hours** (10-20 seconds per example).

#### Phase 2: Dataset Building

The exploration results become structured training data:
```json
{
  "instruction": "Create a vertical cylinder of radius 8 and height 32",
  "code": "(attach (cyl 8 32) (tv 90))",
  "views": ["front.png", "top.png", "side.png"],
  "slices": ["z=0.png", "z=16.png"]
}
```

Both directions are valuable:
- **Code → description**: "what does this code produce?"
- **Description → code**: "write code that produces this shape"

#### Phase 3: Fine-Tuning a Local Model

Target models for Apple M1 64GB (via Ollama):

| Model | Size | Fine-tune time (QLoRA, 1000 examples) |
|-------|------|---------------------------------------|
| Qwen2-VL 7B | ~5GB | 2-4 hours |
| LLaVA 1.6 13B | ~8GB | 6-15 hours |
| Llama 3.2 Vision 11B | ~7GB | 4-8 hours |

Tools: `mlx-lm` or `unsloth` (both optimized for Apple Silicon).

**Key question**: do the small vision models have enough baseline spatial reasoning to learn Ridley's turtle mappings? This needs empirical testing. A quick validation: fine-tune on 50 hand-crafted examples and check if `(tv 90)` = vertical is learned.

#### Phase 4: Hybrid Architecture

The fine-tuned local model handles Ridley-specific spatial reasoning, while a large cloud model (Gemini, Claude) handles natural language understanding and high-level planning:

```
User request
  → Cloud LLM: understands intent, decomposes into spatial operations
  → Local Ridley-LLM: translates each operation to correct Ridley code
  → Execute and verify
```

## Implementation Roadmap

1. **Now**: improve few-shot examples and prompt engineering (already in progress)
2. **Short term**: build `/ai-explore` command for Phase 1 self-play
3. **Medium term**: accumulate dataset, attempt fine-tuning on a 7B model
4. **Long term**: hybrid architecture with specialized local model

## Open Questions

- Minimum dataset size for reliable spatial reasoning in a 7B model?
- Can vision models at this scale actually distinguish between "cylinder along X" and "cylinder along Z" from rendered views?
- Would a text-only model (no vision) fine-tuned on code pairs be sufficient, or is the visual grounding essential?
- Cost/benefit of a hybrid (cloud + local) architecture vs. just improving prompts for cloud models?
