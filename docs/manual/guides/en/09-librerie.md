# 9. Workspaces and Libraries

<!-- level: base -->

Two things hold the code you write: the *Workspace*, the document you are working in, and *libraries*, the reusable code you call from any document. This chapter covers both.

## 9.1 Workspaces

The code you write in the editor lives in a *Workspace*. A Workspace is a document: a Ridley program with a name, that persists between sessions. You can keep several open, each with its own content, and switch from one to another without losing anything.

The "Workspaces" section of the panel lists the open documents. From there you create a new Workspace, rename it, duplicate it, close it, or switch to another. When you close the current Workspace, Ridley switches to a neighboring one, so there is always an active document.

Opening a manual example no longer touches your work. When you press "Edit" on an example, Ridley opens it in a new Workspace and switches to it, leaving the document you were working on intact.

On the desktop, Workspaces are saved and reopened as files with the Save, Save As and Open buttons, which open the system's native dialogs ("Open" is the button that used to be called "Load"). When a Workspace is backed by a file on disk, a dot next to the name signals that the in-memory content has diverged from what was saved. In the web version the document lives in the browser, the link to files is more limited, and the dot does not appear.

Workspaces and libraries stay distinct things: a library is reusable code you activate and call from another program, a Workspace is the program itself. To experiment without touching the current project, open a new Workspace: that is exactly what they are for.

Each Workspace, however, remembers which libraries it has active. Libraries stay a shared asset, common to all Workspaces, but the set of enabled ones is part of the Workspace: when you switch to another Workspace, the active libraries change accordingly, and a new Workspace inherits those of the current one. When you save a Workspace to a file, the list of active libraries is saved together with the code, so reopening it restores the same enabled libraries.

A library is a block of Ridley code saved with a name, separate from the source in the editor. Once activated, its functions become reachable from your code through a prefix: if the library is called `shapes` and defines `hexagon`, you use it with `(shapes/hexagon)`.

Libraries are the way you accumulate reusable code from one project to the next: parametric shapes, assembly patterns, utilities you find yourself rewriting every time. They are also the mechanism by which Ridley imports external assets (SVG and STL).

## 9.2 What libraries are for

The code in the editor is your current project. Every time you press Cmd+Enter, it is analyzed from scratch in a fresh context: the `def`s and `defn`s are born, live for the duration of the eval, and disappear at the next one. If you define a useful function in one project and want to use it in another, you have to copy it by hand.

Libraries solve this problem. A library is persistent (it survives between sessions), independent of the current project, and can be activated or deactivated from the panel. When it is active, its public definitions are available in the eval context as a separate namespace.

A typical use: you have written a parametric `rounded-rect` function you like. You copy it into a library called `my-shapes`, and from that moment every project can use `(my-shapes/rounded-rect 40 20 5)` without redefining it.

## 9.3 Using a library

### The libraries panel

The libraries panel is accessible from the sidebar. It shows the list of available libraries, with a toggle to activate or deactivate each one. The order in the list is the loading order: you can reorder by drag&drop if a library depends on another.

### Activation

Activating a library means making it available in the eval context. At the next Cmd+Enter, its functions will be reachable with the prefix.

Deactivating a library removes it from the context: calls with its prefix will produce an error.

The set of active libraries is tied to the current Workspace: it changes when you switch to another Workspace, and it is saved together with the document (see 9.1).

Activation is not automatic for dependencies. If library A declares that it requires B, you have to activate both manually. If B is not active, A is skipped with a warning.

### Namespace prefix

The library's name becomes the prefix. A library `connectors` with `(defn slot [w d] ...)` is used as `(connectors/slot 10 5)`.

You do not need to write `require` in your code: Ridley does it automatically for every active library.

The name can contain a slash (for example `robot/arm`), but it is a single name, not a hierarchy of namespaces. `(robot/arm/joint)` is the function `joint` of the library `robot/arm`.

### The loading cycle

At every Cmd+Enter, the SCI context is rebuilt from scratch. All the active libraries are loaded in topological order (respecting the declared dependencies), analyzed in sequence, and their public symbols become available. There is no caching: if a library's source has changed, the next eval will see it.

## 9.4 Built-in libraries: SVG and STL import

Ridley uses the library system to import external assets. The result of the import is an ordinary library, indistinguishable from a handwritten one. You can inspect it, modify it, export it.

### SVG import

From the libraries panel, the `+` button → "Load SVG" opens a file picker. Ridley reads the file, extracts the shapes from the SVG paths, and generates a library with a `def` for each recognized element.

```clojure
;; After importing a file with a hexagon and a star:
(my-svg/hexagon)    ;; => 2D shape of the hexagon
(my-svg/star)       ;; => 2D shape of the star
```

The names of the `def`s come from the `id`s of the SVG elements. If the file has no `id`, Ridley generates numeric names (`shape-0`, `shape-1`...).

After the import, the panel automatically enters edit mode and shows the generated source. It is the right moment to check that the names match what you expect and to rename them if needed.

Under the hood, the generated library contains the complete SVG string and uses the browser's SVG APIs to sample the paths at eval time. This means the source is portable (it works in any browser), but the shapes are resampled at every Cmd+Enter.

The corresponding DSL functions are three: `svg` registers an SVG string, `svg-shape` extracts a single shape by `id`, `svg-shapes` extracts all the shapes as a map.

The fact that the import produces an editable library is a concrete advantage: you can open it in edit mode and remove the shapes you do not need, rename those with generic names, or transform them in place (scale, rotate, combine). The imported SVG is not an opaque blob: it is code, and you treat it as such.

### STL import

From the libraries panel, the `+` button → "Load STL" imports a binary STL file. Ridley parses the file at import time, welds the vertices, and generates a library with a single `def` that contains the mesh encoded in base64.

```clojure
(my-model/part)    ;; => 3D mesh ready for register, booleans, etc.
```

Unlike SVG, the parsing happens only once (at import), not at every eval. The library's source contains two base64 strings (vertices and faces) and the `decode-mesh` function that rebuilds them into a Ridley mesh. The source is heavier but the eval is fast.

The panel does not enter edit mode after the STL import, because editing base64 strings by hand would make no sense. If you want to rename the `def`, open the library's editor manually.

Even though the base64 source is not readable, the library stays usefully editable. A common case: the STL file was saved in meters instead of millimeters, and the resulting mesh is a thousand times too big. You just open the library and add a `(scale model 0.001)` after the `decode-mesh`.

## 9.5 Creating your own library

### From the panel

The `+` button → "New library" asks for a name and creates an empty library. Edit mode opens: you write the library's code directly in Ridley's main editor.

A library is ordinary Clojure code. Everything you can write in the main editor works in a library: `def`, `defn`, `defonce`, data structures, calls to Ridley functions.

```clojure
;; Example: "fasteners" library
(defn hex-bolt [r h]
  (let [head (cyl (* r 1.5) (* h 0.3) 6)
        shaft (cyl r h)]
    (mesh-union head shaft)))

(defn washer [r]
  (mesh-difference
    (cyl (* r 1.8) 1)
    (cyl (* r 1.1) 1)))
```

Only the top-level `def`s and `defn`s become public symbols of the library. The local variables (inside `let`, `loop`, etc.) stay private.

### Edit mode

When you click "Edit" on a library in the panel, the main editor saves your current work, replaces it with the library's source, and shows a bar with "Back" and "Save".

You modify the code, press "Save" to save, "Back" to cancel. The original content of the editor is restored in both cases.

A Cmd+Enter during edit mode evaluates the source in the editor as if it were the main program: useful for testing the library's functions, but be aware that the `def`s end up in the global scope of the eval, not in the library's namespace (this will change only at the next save + regular eval).

### Dependencies

If a library uses functions of another, declare it in the header. The header is the first line of the source, in comment format:

```clojure
;; :requires [my-shapes utils]
```

Ridley reads this declaration and loads the dependencies before the current library. If a dependency is not active or does not exist, the library is skipped with a warning.

## 9.6 Sharing libraries

### Export

From the libraries panel, every library can be exported as a `.clj` file. The file contains a header with name and dependencies, followed by the source. It is a text file readable and editable with any editor.

### Import

The `↑` button in the panel opens a file picker to import `.clj` or `.cljs` files. Ridley reads the header to derive name and dependencies, and adds the library to the list.

### Desktop vs web

In the desktop environment (Tauri), the libraries live as `.clj` files in `~/.ridley/libraries/`. You can modify them with an external editor and find them updated at the next Cmd+Enter.

In the web environment, the libraries live in localStorage as JSON objects. The `.clj` export/import is the only way to transfer them between browsers or between users.

In both cases, the exported `.clj` format is identical: a library exported from the web works on the desktop and vice versa.
