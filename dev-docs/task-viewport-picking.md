# Task: Viewport Picking & Source Tracking

## Summary

Add interactive mesh/face selection in the viewport via Alt+Click, with source code history tracing and REPL integration. The user Alt+Clicks on geometry in the viewport to inspect it; a status bar shows the full chain of operations that produced that mesh, with each step clickable to navigate to its source line. `(selected)` in the REPL exposes the selection as data for programmatic use.

---

## Interaction Design

### Gestures

| Gesture | Action |
|---------|--------|
| Alt+Click on mesh | Select object → status bar shows name + source history |
| Alt+Click on already-selected mesh | Drill down to face level → shows face ID + normal |
| Alt+Click on empty space | Deselect |
| Escape | Deselect |

### Status Bar

A thin bar at the bottom of the viewport, visible only when there's an active selection. Orange accent color consistent with Ridley's highlight style.

**Object-level selection:**
```
🔶 result — mesh-difference L:7 ← attach L:5 ← extrude L:3
```

**Face-level selection (after second Alt+Click):**
```
🔶 result :top — normal [0 0 1]  — mesh-difference L:7 ← attach L:5 ← extrude L:3
```

Each `L:N` is a **clickable link** (only for entries from the definitions panel). Clicking any one scrolls CodeMirror to that line and briefly flashes it (~1s highlight animation).

The history reads left-to-right: most recent operation first, origin last.

**Overflow**: if the history chain is long, truncate from the middle:
```
🔶 result — mesh-difference L:7 ← ... ← extrude L:3
```
With a tooltip on "..." showing the full chain.

### Navigate to Source (via clickable history)

Clicking any `L:N` link in the status bar:
1. Scrolls CodeMirror to line N
2. Briefly flashes/highlights the line (CSS animation, ~1s)
3. Does NOT move cursor or change selection in the editor

No separate "Navigate to source" button — every history step is its own link.

---

## Visual Feedback

### Object Selection Highlight
- Selected mesh gets a visible highlight (orange outline or semi-transparent overlay)
- Reuse existing highlight infrastructure where possible
- Highlight persists until deselection

### Face Selection Highlight
- When drilled down to face level, the specific face under the cursor highlights
- Reuse existing `highlight-face` system (already implemented)
- Optional: as mouse moves over the selected mesh, face highlight follows cursor (hover preview)

---

## Source History Model

### `:source-history` — a vector of operations

Every mesh carries a `:source-history` vector. Each operation that creates or transforms a mesh appends an entry. The vector reads bottom-to-top chronologically (oldest first), but is displayed right-to-left in the status bar (most recent first, origin last).

```clojure
;; After: (register torus (extrude (circle 5) (f 30)))
{:source-history
 [{:op :extrude :line 3 :col 20 :source :definitions}
  {:op :register :as :torus :line 3 :col 1 :source :definitions}]}

;; After: (register torus (attach torus (f 10) (th 45)))
{:source-history
 [{:op :extrude :line 3 :col 20 :source :definitions}
  {:op :register :as :torus :line 3 :col 1 :source :definitions}
  {:op :attach :line 5 :col 20 :source :definitions}
  {:op :register :as :torus :line 5 :col 1 :source :definitions}]}
```

### Rule

Every operation that produces a new mesh does `conj` on the existing `:source-history` vector (or creates one if absent). The mesh is immutable — the new mesh carries the extended history.

### Helper function

```clojure
(defn add-source [mesh op-info]
  (update mesh :source-history (fnil conj []) op-info))
```

All mesh-producing macros use this.

### Source context: `:source` field

Each history entry includes a `:source` field indicating where the code was evaluated:

| Value | Meaning | Line info | Navigable? |
|-------|---------|-----------|------------|
| `:definitions` | Definitions panel (the code file) | Stable line/col | Yes — clickable link |
| `:repl` | REPL input line | Line 1 of transient input | No — show op name only, tooltip shows original command |
| `:unknown` | Indirect call, generated code, `nil` metadata | `nil` | No — show op name only |

#### How to determine `:source`

The definitions panel and the REPL use different entry points in `repl.cljs` to evaluate code. The simplest approach is a dynamic var:

```clojure
(def ^:dynamic *eval-source* :unknown)

;; In the definitions panel eval path:
(binding [*eval-source* :definitions]
  (sci/eval-string code sci-ctx))

;; In the REPL eval path:
(binding [*eval-source* :repl]
  (sci/eval-string command sci-ctx))
```

Macros read `*eval-source*` at expansion time:

```clojure
(defmacro extrude [shape & movements]
  (let [{:keys [line column]} (meta &form)
        source *eval-source*]
    `(-> (extrude-impl ~shape ~@movements)
         (add-source {:op :extrude
                      :line ~line
                      :col ~column
                      :source ~source}))))
```

#### REPL entries: capturing the command text

For `:repl` entries, it's useful to also capture the original command string for tooltip display. This can be stored in a dynamic var set before eval:

```clojure
(def ^:dynamic *eval-text* nil)

;; REPL eval path:
(binding [*eval-source* :repl
          *eval-text* command-string]
  (sci/eval-string command-string sci-ctx))
```

Then macros can optionally include it:

```clojure
{:op :box :line 1 :col 1 :source :repl :text "(box 20)"}
```

The status bar shows this as a tooltip on hover over the non-clickable op name.

### Status bar rendering by source type

```
🔶 torus — attach L:5 ← extrude
                  ^^^^        ^^^^^^^
                  clickable   plain text (from REPL or unknown)
                  (definitions)
```

**Rules:**
- `:definitions` with valid `:line` → clickable link `op-name L:N`
- `:repl` → plain text `op-name`, tooltip shows `:text` if available (e.g., `"(box 20)"`)
- `:unknown` or `nil` `:line` → plain text `op-name`, no tooltip

### Tree-structured operations (booleans, warp)

Some operations create a new mesh from one or more inputs, branching the history into a tree. The new entry carries references to its inputs rather than copying their full histories.

**Booleans** (multiple inputs):

```clojure
;; Compact form: registered names as keywords
{:op :mesh-difference :line 7 :col 20 :source :definitions
 :operands [:torus :cutter]}

;; If an operand isn't registered, include a summary
{:op :mesh-difference :line 7 :col 20 :source :definitions
 :operands [:torus {:op :cyl :line 7 :col 42}]}
```

**Warp** (single input, produces new mesh):

```clojure
;; warp deforms an existing mesh — new mesh, references the input
{:op :warp :line 12 :col 3 :source :definitions
 :input :torus}

;; If input isn't registered
{:op :warp :line 12 :col 3 :source :definitions
 :input {:op :extrude :line 5 :col 10}}
```

In both cases, the result's history starts fresh with the operation entry. Input histories are accessible via registry lookup if needed, not deeply copied.

```clojure
;; After: (register result (mesh-difference torus (cyl 3 50)))
{:source-history
 [{:op :mesh-difference :line 7 :col 20 :source :definitions
   :operands [:torus {:op :cyl :line 7 :col 42}]}
  {:op :register :as :result :line 7 :col 1 :source :definitions}]}

;; After: (register warped (warp torus my-warp-fn))
{:source-history
 [{:op :warp :line 12 :col 3 :source :definitions
   :input :torus}
  {:op :register :as :warped :line 12 :col 1 :source :definitions}]}
```

### Which operations append to source-history

**Mesh creation** (starts a new history):
- `box`, `sphere`, `cyl`, `cone`
- `extrude`, `extrude-closed`
- `loft`, `loft-n`, `bloft`, `bloft-n`
- `revolve`
- `text-on-path`

**Mesh transformation** (appends to existing history):
- `attach`, `attach!`
- `attach-face`, `clone-face`
- `solidify`

**Mesh combination** (starts new history with input references):
- `mesh-union`, `mesh-difference`, `mesh-intersection`
- `mesh-hull`
- `warp`

**Registration** (appends `:register` entry):
- `register` / `r`

### Macro implementation pattern

```clojure
;; Creation: starts history
(defmacro extrude [shape & movements]
  (let [{:keys [line column]} (meta &form)
        source *eval-source*]
    `(-> (extrude-impl ~shape ~@movements)
         (add-source {:op :extrude :line ~line :col ~column :source ~source}))))

;; Transformation: appends to existing history
(defmacro attach [mesh & commands]
  (let [{:keys [line column]} (meta &form)
        source *eval-source*]
    `(-> (attach-impl ~mesh ~@commands)
         (add-source {:op :attach :line ~line :col ~column :source ~source}))))

;; Boolean: new history with operand refs
(defmacro mesh-difference [base & tools]
  (let [{:keys [line column]} (meta &form)
        source *eval-source*]
    `(-> (mesh-difference-impl ~base ~@tools)
         (add-source {:op :mesh-difference :line ~line :col ~column
                      :source ~source
                      :operands (source-refs [~base ~@tools])}))))

;; Warp: new history with single input ref
(defmacro warp [mesh warp-fn & opts]
  (let [{:keys [line column]} (meta &form)
        source *eval-source*]
    `(-> (warp-impl ~mesh ~warp-fn ~@opts)
         (add-source {:op :warp :line ~line :col ~column
                      :source ~source
                      :input (source-ref ~mesh)}))))

;; Registration: appends register entry, preserves everything else
(defmacro register [name expr & flags]
  (let [{:keys [line column]} (meta &form)
        source *eval-source*]
    `(let [obj# ~expr]
       (-> obj#
           (add-source {:op :register :as ~(keyword name)
                        :line ~line :col ~column :source ~source})
           (registry/register! ~(keyword name))))))
```

`source-refs` extracts the registered name (keyword) or a minimal summary `{:op ... :line ...}` from each operand's last history entry.

---

## Raycasting Implementation

### Setup (viewport/core.cljs)

```clojure
;; Picking state atom
(def picking-state
  (atom {:selected-mesh nil      ; registry entry or mesh reference
         :selected-face nil      ; face-id keyword or nil
         :drill-level :object})) ; :object or :face
```

### Event Handler

```clojure
(defn on-viewport-alt-click [event]
  (when (.-altKey event)
    (.preventDefault event)
    (let [rect (.getBoundingClientRect canvas)
          x (/ (- (.-clientX event) (.-left rect)) (.-width rect))
          y (/ (- (.-clientY event) (.-top rect)) (.-height rect))
          mouse (js/THREE.Vector2. (- (* 2 x) 1) (- 1 (* 2 y)))
          raycaster (js/THREE.Raycaster.)]
      (.setFromCamera raycaster mouse camera)
      (let [intersects (.intersectObjects raycaster (.-children scene) true)]
        (if (pos? (.-length intersects))
          (handle-pick (aget intersects 0))
          (deselect!))))))
```

Register on the canvas element:
```clojure
(.addEventListener canvas "click" on-viewport-alt-click)
(.addEventListener js/document "keydown"
  (fn [e] (when (= (.-key e) "Escape") (deselect!))))
```

### Pick Handler

```clojure
(defn handle-pick [intersection]
  (let [three-mesh (.-object intersection)
        face-index (.-faceIndex intersection)
        registry-entry (find-registry-entry three-mesh)
        currently-selected (:selected-mesh @picking-state)]
    (if (and registry-entry (= registry-entry currently-selected))
      ;; Same mesh → drill down to face
      (let [face-id (resolve-face-id registry-entry face-index)]
        (swap! picking-state assoc
               :selected-face face-id
               :drill-level :face)
        (highlight-face! registry-entry face-id)
        (update-status-bar!))
      ;; Different mesh or first click → select object
      (do
        (swap! picking-state assoc
               :selected-mesh registry-entry
               :selected-face nil
               :drill-level :object)
        (highlight-mesh! registry-entry)
        (update-status-bar!)))))
```

### Three.js ↔ Registry Mapping

When creating Three.js meshes from registry data, store the registry key in `userData`:

```javascript
threeMesh.userData.ridleyName = "torus";
```

Lookup:
```clojure
(defn find-registry-entry [three-mesh]
  (when-let [name (.. three-mesh -userData -ridleyName)]
    (registry/get-entry (keyword name))))
```

For anonymous meshes (not registered), use an internal numeric ID stored in userData.

### Face Index → Face ID Resolution

```clojure
(defn resolve-face-id [registry-entry face-index]
  (let [mesh (:mesh registry-entry)
        face-groups (:face-groups mesh)]
    (or (some (fn [[id triangles]]
                (when (contains? (set triangles) face-index)
                  id))
              face-groups)
        face-index)))  ; fallback: raw index if no face groups
```

---

## Status Bar Implementation

### DOM Structure

```html
<div id="picking-status-bar" class="picking-status" style="display: none;">
  <span class="picking-icon">🔶</span>
  <span class="picking-name">result</span>
  <span class="picking-face" style="display: none;">:top — normal [0 0 1]</span>
  <span class="picking-separator">—</span>
  <span class="picking-history">
    <!-- :definitions entry → clickable link -->
    <a class="source-link" data-line="7">mesh-difference L:7</a>
    <span class="history-arrow">←</span>
    <!-- :repl entry → plain text with tooltip -->
    <span class="source-repl" title="(attach torus (f 10))">attach</span>
    <span class="history-arrow">←</span>
    <!-- :definitions entry → clickable link -->
    <a class="source-link" data-line="3">extrude L:3</a>
  </span>
</div>
```

### CSS

```css
.picking-status {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 28px;
  background: rgba(30, 30, 30, 0.9);
  color: #ccc;
  font-family: monospace;
  font-size: 12px;
  display: flex;
  align-items: center;
  padding: 0 12px;
  gap: 8px;
  z-index: 100;
}

.picking-name {
  color: #ff9933;
  font-weight: bold;
}

.picking-face {
  color: #aaa;
}

.source-link {
  color: #6cb6ff;
  cursor: pointer;
  text-decoration: none;
}

.source-link:hover {
  text-decoration: underline;
  color: #fff;
}

.source-repl {
  color: #888;
  cursor: default;
}

.source-repl[title]:hover {
  color: #aaa;
}

.history-arrow {
  color: #666;
  margin: 0 2px;
}
```

### History Rendering

```clojure
(defn render-history-entry [entry]
  (let [{:keys [op line source text]} entry
        op-name (name op)]
    (case source
      :definitions
      (if line
        ;; Clickable link
        (str "<a class='source-link' data-line='" line "'>"
             op-name " L:" line "</a>")
        ;; Definitions but no line info (shouldn't happen, but handle gracefully)
        (str "<span class='source-repl'>" op-name "</span>"))

      :repl
      ;; Plain text with tooltip showing original command
      (str "<span class='source-repl'"
           (when text (str " title='" (escape-html text) "'"))
           ">" op-name "</span>")

      ;; :unknown or nil
      (str "<span class='source-repl'>" op-name "</span>"))))

(defn render-status-bar! []
  (let [{:keys [selected-mesh selected-face drill-level]} @picking-state]
    (when selected-mesh
      (let [mesh-data (:mesh selected-mesh)
            name (or (:registered-as selected-mesh) "unnamed")
            history (reverse (:source-history mesh-data))  ; most recent first
            ;; Filter out :register entries for display (they clutter the bar)
            display-ops (remove #(= :register (:op %)) history)
            ;; Truncate if > 5: first 2, ..., last 2
            display-ops (if (> (count display-ops) 5)
                          (concat (take 2 display-ops) [:ellipsis] (take-last 2 display-ops))
                          display-ops)]
        ;; Build and set innerHTML of status bar
        ))))

(defn navigate-to-source! [line-number]
  (when (and line-number (pos? line-number))
    (codemirror/scroll-to-line! line-number)
    (codemirror/flash-line! line-number 1000)))
```

### CodeMirror Integration

```clojure
;; In editor/codemirror.cljs

(defn scroll-to-line! [line-number]
  (when-let [view @editor-view]
    (try
      (let [line (.line (.-doc (.-state view)) line-number)
            pos (.-from line)]
        (.dispatch view
          #js {:effects (.of js/EditorView.scrollIntoView pos)}))
      (catch :default _
        ;; Line no longer exists (code was shortened)
        nil))))

(defn flash-line! [line-number duration-ms]
  (when-let [view @editor-view]
    (try
      (let [line (.line (.-doc (.-state view)) line-number)
            from (.-from line)
            to (.-to line)]
        ;; Add temporary decoration via Decoration.mark
        ;; Remove after duration-ms via setTimeout
        )
      (catch :default _ nil))))
```

---

## REPL Functions

### `(selected)`

```clojure
(selected)
;; Object level:
;; => {:mesh <mesh-data>
;;     :name :result
;;     :source-history [{:op :extrude :line 3 :col 20 :source :definitions}
;;                      {:op :register :as :torus :line 3 :col 1 :source :definitions}
;;                      {:op :attach :line 1 :col 1 :source :repl :text "(attach ...)" }
;;                      {:op :mesh-difference :line 7 ... :source :definitions}
;;                      {:op :register :as :result :line 7 ... :source :definitions}]
;;     :level :object}

;; Face level:
;; => {:mesh <mesh-data>
;;     :name :result
;;     :face-id :top
;;     :face-info {:normal [0 0 1] :center [0 0 25] :area 2500}
;;     :source-history [...]
;;     :level :face}

;; Nothing selected:
;; => nil
```

### Convenience accessors

```clojure
(selected-mesh)       ; → mesh data or nil
(selected-face)       ; → face-id keyword or nil
(selected-name)       ; → registered name keyword or nil
```

### History inspection utilities

```clojure
(source-of :result)   ; → prints formatted source history
(origin-of :result)   ; → first entry: {:op :extrude :line 3 ...}
(last-op :result)     ; → most recent non-register entry
```

### Usage patterns

```clojure
;; Click a face, then in REPL:
(attach-face (selected-mesh) (selected-face))
(inset 3)
(f 10)
(detach)

;; Inspect history of clicked object
(:source-history (selected))

;; Flash all faces of selected mesh
(doseq [id (face-ids (selected-mesh))]
  (flash-face (selected-mesh) id 1000))
```

---

## Implementation Plan

### Phase A: Raycasting + Status Bar (foundation)

**Files touched:**
- `viewport/core.cljs` — raycaster, Alt+Click handler, picking-state atom
- `core.cljs` — status bar DOM element, CSS, event delegation

**Steps:**
1. Add `picking-state` atom
2. Add status bar div below/over viewport canvas (hidden by default)
3. Store `ridleyName` in `THREE.Mesh.userData` when creating Three.js objects (in the render loop where registry meshes become Three.js meshes)
4. Implement Alt+Click listener (check `event.altKey`)
5. Implement raycasting → userData → registry lookup
6. On pick: update picking-state, show status bar with mesh name
7. On Alt+Click empty / Escape: deselect, hide status bar
8. Add visual highlight on selected mesh
9. Verify OrbitControls does not intercept Alt+Click; if it does, configure to skip Alt events

### Phase B: Source History Tracking

**Files touched:**
- `editor/repl.cljs` — modify all mesh-producing macros, add `*eval-source*` and `*eval-text*` dynamic vars

**Steps:**
1. Add `*eval-source*` dynamic var (default `:unknown`)
2. Add `*eval-text*` dynamic var (default `nil`)
3. Wrap definitions panel eval in `(binding [*eval-source* :definitions] ...)`
4. Wrap REPL eval in `(binding [*eval-source* :repl *eval-text* command] ...)`
5. Implement `add-source` helper function
6. Update `extrude`, `extrude-closed` macros — capture `(meta &form)`, `*eval-source*`, call `add-source`
7. Update `loft`, `loft-n`, `bloft`, `bloft-n` macros
8. Update `revolve` macro
9. Update `box`, `sphere`, `cyl`, `cone` macros
10. Update `mesh-union`, `mesh-difference`, `mesh-intersection` — include `:operands`
11. Update `mesh-hull`, `solidify`
12. Update `warp` — include `:input` reference (single-operand tree structure)
13. Update `attach`, `attach!` — append to existing history
14. Update `attach-face`, `clone-face` — append to existing history
15. Update `register` — append `:register` entry, preserve existing history
16. Update `text-on-path`
17. Show source-history in status bar as chain of entries (clickable for :definitions, plain for :repl/:unknown)

### Phase C: Navigate to Source (clickable links)

**Files touched:**
- `core.cljs` — click delegation on `.source-link` elements
- `editor/codemirror.cljs` — `scroll-to-line!` and `flash-line!` functions

**Steps:**
1. Add `scroll-to-line!` to codemirror module (uses `EditorView.scrollIntoView`)
2. Add `flash-line!` — temporary highlight decoration, removed after ~1s
3. Add click handler on status bar: delegate to `.source-link[data-line]`
4. On click: extract line number from `data-line`, call scroll + flash
5. Handle edge case: line number exceeds document length → no-op, no crash

### Phase D: Face Drill-Down

**Files touched:**
- `viewport/core.cljs` — drill-down logic
- `geometry/faces.cljs` — face-index → face-id resolution

**Steps:**
1. Implement face-index → face-id mapping using `:face-groups`
2. On second Alt+Click (same mesh): resolve face, update picking-state
3. Update status bar to include face info before the history chain
4. Highlight the specific face
5. Optional future: face hover preview on mousemove while in face drill-down mode

### Phase E: REPL Integration

**Files touched:**
- `editor/repl.cljs` — register new SCI bindings

**Steps:**
1. Implement `selected`, `selected-mesh`, `selected-face`, `selected-name`
2. Implement `source-of`, `origin-of`, `last-op`
3. All read from `picking-state` atom
4. Register in SCI context
5. Test: Alt+Click → `(selected)` → correct data → `(attach-face ...)` workflow

---

## Edge Cases & Notes

- **Anonymous meshes** (not registered): selectable, `:name` shows as "unnamed", history still tracks operations
- **REPL-created objects**: fully functional for selection and REPL interaction. Source history entries show op name without clickable line numbers. Tooltip shows original command text if captured
- **Missing `(meta &form)`**: some SCI contexts may not provide line metadata (deeply nested, macro-generated code). These entries get `:source :unknown` and display as plain text op names — no information is lost, just not navigable
- **Re-evaluation**: source-history reflects the LAST evaluation. Stale line numbers are acceptable. If code was edited but not re-evaluated, links may point to wrong lines — this is expected and matches standard debugger behavior
- **Mixed histories**: a mesh created in definitions, then modified via REPL, has a mixed history — some entries clickable, some not. This is correct and expected
- **Grouped meshes**: raycasting hits individual Three.js children. userData should point to the parent registry entry
- **Performance**: raycasting is per-click only, `add-source` is one `conj` per operation — negligible overhead
- **OrbitControls conflict**: must verify Alt is not used by OrbitControls. If it is, configure OrbitControls to ignore Alt events
- **VR mode**: picking not available (no mouse). Future: controller raycast
- **History size**: unlikely to grow large in practice (most meshes have 2-5 operations). No need for truncation in the data, only in the status bar display
- **`*eval-source*` threading**: the dynamic var must be bound in the same thread as macro expansion. Since SCI is single-threaded in the browser, this is safe. If future async eval is added, this needs revisiting

---

## Testing Checklist

- [ ] Alt+Click on a registered box → status bar shows `:box-name` + history
- [ ] Alt+Click on an extrusion → shows history chain with clickable line numbers
- [ ] Alt+Click same mesh again → drills to face, shows face ID + history
- [ ] Alt+Click empty → deselects, status bar hides
- [ ] Escape → deselects
- [ ] Click `L:N` in status bar → CodeMirror scrolls to line N, flashes it
- [ ] Click `L:N` for a line that was deleted → no crash, graceful handling
- [ ] History shows both clickable (definitions) and plain text (REPL) entries correctly
- [ ] REPL entry shows tooltip with original command on hover
- [ ] History chain for `(register x (attach x (f 10)))` shows both attach and original creation
- [ ] History chain for `(mesh-difference a b)` shows boolean op with operand names
- [ ] Object created in REPL: `(box 20)` → selectable, history shows `box` as plain text
- [ ] Mixed history: definitions then REPL modification → both entry types display correctly
- [ ] `(selected)` in REPL returns correct data including `:source-history` with `:source` field
- [ ] `(selected-mesh)` returns mesh data
- [ ] `(selected-face)` returns face-id after drill-down
- [ ] `(source-of :name)` prints formatted history
- [ ] OrbitControls still works normally (rotate/zoom/pan without Alt)
- [ ] Alt+Click does NOT trigger orbit rotation
- [ ] Status bar truncates gracefully with long histories (shows first 2 ... last 2)
- [ ] Anonymous meshes (not registered) are selectable and show "unnamed"
