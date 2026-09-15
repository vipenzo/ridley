<!--
Translated from it/20-acquisire-dalle-foto.md (2026-09-09). Keep in sync:
same section structure, same examples, same UI labels (the panel's labels are
quoted as they appear in the app, now all in English since 2026-09-11).
Code examples identical to the IT source. No em-dash.
-->

# 20. Acquiring from photographs

<!-- level: advanced -->

## 20.1 Photographs as measuring instruments

Chapter 18 starts from a mesh that already exists: a downloaded STL, a scan. This chapter starts one step further back: the object is on your desk, but you have no model of it at all. The classic route would be dense photogrammetry: dozens of photos, a point cloud, a heavy mesh to repair and clean, and in the end the work of chapter 18 anyway to reach a parametric model.

Ridley takes a more direct route, which we could call parametric photogrammetry: a few well-aimed photos become *measured views*, that is backdrops with a calibrated camera behind them, and you rebuild the object directly as native Ridley source, drawing over the photos with the language's ordinary tools. There is never a point cloud: the product of the acquisition is code, with real measurements in millimetres.

For a photo to be a measured view, the software has to know where it was taken from. The trick is to photograph the object together with a reference the program knows by construction: the **registration cage**, three printed rings that wrap around the part. Finding the cage's discs in the photo is enough to compute the camera pose, and from there everything you draw over the photo is real 3D geometry.

The workflow has three moments: preparing the cage and shooting, registering the cameras (telling Ridley where each photo was taken from), and tracing the geometry over the registered views. The first two are done once; the third is the actual modelling work, and you can come back to it whenever you like.

## 20.2 The registration cage

The cage is a Ridley part: three flat rings of three different diameters, mounted orthogonal to each other with six glued tabs, and the part to acquire anchored at the centre. Each ring carries on **both faces** a crown of twelve dark discs, plus a thirteenth disc slightly further in, the **zero**, which says which disc is number zero and which way to count. In the drawing the zero forms a double dot together with the disc next to it: that is the figure you need to recognise in the photos.

Why three orthogonal rings. A flat reference, seen edge-on, says nothing: the photo taken from the wrong side is lost. The cage carries the reference **around the object**: whichever direction you look from, at least one ring faces you, often two, and the discs do not lie on a single plane, so the pose is determined in all six degrees of freedom. The discs also sit at the same depth as the part, where the detail you want to trace is measured, not a hand's width away. The practical result is that you shoot from wherever you like, back and underside included, in a single session.

### Printing it

The code that produces the cage is a readable example, not a library: `examples/print-cage.clj`. Open it, evaluate the whole file (the definitions alone produce nothing), then at the bottom uncomment the command you need and re-evaluate:

```clojure
(def diameter 176)
(def cage (registration-cage :d diameter :gen 2 :rim-marks? true))

;; (register Gabbia (make-cage-ring cage))   ; look at it assembled
;; (save-3mf cage "~/Downloads")             ; the three rings, flat, one dialog
;; (register Stick (stick cage 80))          ; a stick to hold the part
;; (register Punta (punta-tricuspide))       ; three-pointed foot for delicate parts
```

`cage` is the file's one declaration: diameter, generation, rim segments. Everything else (disc positions, tabs, seats, assembly key) is read from the same [registration-cage](ref:registration-cage) you will later use in the session, so the printed cage and the one the program looks for in the photos cannot drift apart. Leave the file as you find it except for the diameter: 176 mm is the tested size, with a clear opening of about 88 mm at the centre, which is the largest part that fits. You can change it, but do not go below 85 mm: the diameter scales everything except the ring thickness, and below that size the pockets of the rim segments run into those of the discs.

`save-3mf` writes three 3MF files, one ring per file, already **lying down** in their print pose, in two materials: light ring, dark discs inlaid flush on both faces. Each ring sits at the origin of its own file, so importing all three into the slicer you find them concentric, one inside the other: they do not print like that. Lay them out on the bed yourself, moving each ring **together with its discs**; if the bed cannot hold them side by side (the big one alone is as wide as the cage), print them in several jobs. Print in **matte filament** (the detector looks for "a dark round patch", and a glossy highlight is exactly the opposite) and with a **brim** (a thin wide ring curls as it cools, and a curled ring is no longer flat). With `:rim-marks? true` the rings also carry twelve dark segments on the rim, which share layers with the ring's body: two colours per object, not a colour change by height. Print four or five sticks too.

### Gluing it

Six lap joints, glued with any cyanoacrylate. On a generation 2 cage every tab **drops into its seat pocket**, which fixes the angle on its own, and two keys (a pin and a notch, at two points on the big ring's rim) refuse wrong rotations and flips: if a ring will not seat, it is turned. Nothing to align by eye. The order is: the two **big** rings to each other first, then the part at the centre, the **small** ring last.

### Telling it how big it came out

A printer errs in scale by a few tenths of a percent, and that error never presents itself as an error: registration succeeds with excellent residuals and every measurement comes out scaled. That is why `:d` has no default. Measure with a caliper the outer diameter of the glued cage's big ring, and that number is the diameter to declare in the session:

```clojure
(registration-cage :d 175.4 :gen 2 :rim-marks? true)
```

One number is enough: three rings printed by the same machine share the same scale factor (if it erred differently on the two axes the rings would come out oval, and that shows).

### Anchoring the part

The only requirement is that the part **does not move relative to the cage** during the session: where it sits in the cage is never an assumption of the software, only a frame of reference. The sticks enter the seats on the rings, are pushed until they touch the part, and lock themselves with a quarter turn, no screws. Four or five sticks from different rings hold the part in any orientation. Avoid elastic bands (they store energy and shift the part as soon as you turn the cage) and threads (they only pull, and half of them go slack every time you turn it over). If the part moves it is a problem, and you will notice: the discs go on agreeing between views, the details you trace do not.

## 20.3 The photo session

The cage needs a stand that holds it still in the orientation you want to photograph, and lets you change it without remounting anything. The tested one is in `examples/cradle.clj`: a pedestal, a cylinder that slides into it, and on top an arch with two clips that grab **one** of the rings. You choose which ring and where along it to clip, and the whole thing turns on the pedestal: that is how you bring any face of the cage round to the camera, back and underside included. The cage diameter is the `def` at the top of the file. With the cage on the stand and the part at the centre, you shoot from wherever you need. There is no protocol of angles: the cage travels with the part, so every photo carries its own reference, and you can photograph from all around, back and underside included, in a single session. The good photos are the ones the drawing needs: close, aimed at the detail you want to trace.

Four rules, and the first is the most important. **One focal length for the whole session**: same camera, same lens, no zoom. Registration derives a single lens from all the photos together, and a photo taken at another focal length does not present itself as an error: it registers cleanly with the camera at the wrong distance, and shifts the measurements. On a phone watch out for zooms that switch lens on their own (0.5x, 1x, 2x) and for features that re-crop the frame live: keep them still. **Ambient light**, diffuse, no flash and no highlights on the rings. **Sharp photos**: a blurred disc has an uncertain centre, and the residual reflects it. And **the whole cage inside the frame**: a photo with the cage half out can sometimes be recovered, but it gives the software half the references and you half the handholds to orient it. Beyond that, the cage reads from any direction: on the rings facing you the software uses the discs, on the edge-on ones the dark segments on the rim. If you can, make sure a double dot is visible: the software does not need it, you do, when you have to orient the virtual cage over the photo by eye.

The photos go into a folder, and the folder is the session. No notes file is needed: on first open Ridley takes every JPEG or PNG image in the folder, in name order, and writes them into its `session.json` (the phone's HEIC files must be converted first; photos added to the folder later do not enter on their own). The focal length is read from the first photo's EXIF (the 35 mm equivalent focal length the phone writes in every shot): you need do nothing, and the session's Focal slider remains as a manual override.

As an alternative to photos you can shoot live, from a webcam or from the phone used as a system camera, inside the registration session: see 20.6.

## 20.4 Registering the cameras: edit-acquire

Registration ([edit-acquire](ref:edit-acquire)) opens by declaring the folder and the proxy, that is the object the software looks for in the photos. Write the form in the definitions panel and evaluate it with Cmd+Enter (not from the REPL: the session rewrites this very form, so it has to find it in the buffer):

```clojure
(def A (edit-acquire "/Users/me/scans/valvola"
         {:proxy (registration-cage :d 175.4 :gen 2 :rim-marks? true)}))
```

A modal session opens on the viewport. A filmstrip of thumbnails shows the photos, each with a badge: **grey** means to be registered, **green** with the residual in pixels means registered and usable, green with a **dashed amber border** means registered but excluded from the lens measurement (the reason is in the thumbnail's tooltip). Over the current photo the virtual cage is drawn as printed: solid rings, tabs, double dots, rings coloured by axis (X red, Y green, Z blue). The purpose of the whole session is to make that cage match the photographed cage, photo by photo; when it matches, the camera is registered.

### The gesture: pose by eye, then `a`

On the current photo you have three **spring-loaded sliders** at the edges of the image and three **coloured circles** concentric on the cage. The sliders translate: the one on the left moves the cage up and down, the one at the top right and left, the one at the bottom brings it closer or further away (the cage stays centred and changes size). They are spring-loaded: you drag, the knob returns to the centre, the movement stays. The circles rotate: dragging along the red circle turns the cage about its X axis, and likewise the green and the blue, one degree of drag for one degree of rotation, whatever the ring's orientation. There is no 3D gizmo: the cage is its own gizmo.

With these controls you bring the drawn cage **roughly** over the photographed one: rings on the plastic, tabs on the right side, double dots where you see them. Precision is not needed; what is needed is that the pose is the right one and not its mirror twin. Then press **`a`** (button **Auto — read the cage (a)**). The software detects the dark discs across the whole photo, starts from your pose as a seed, identifies the rings and solves the camera; on a cage with rim segments it reads those too, and they are what registers the photos in which the rings are edge-on and the discs fade out. In a few seconds, up to half a minute, the thumbnail turns green with its residual, and the drawn cage settles onto the plastic to the pixel. Look at it: if it matches, that photo is done. Move to the next with `]` and repeat.

If `a` refuses, it says so and says why: usually the photo shows a single ring, or your pose was too far from the true one. Bring the pose closer and press again, or switch to the manual route.

### The manual route: `p`

To register by hand you need to know what the discs are called. The three rings are called **X**, **Y** and **Z** (the big, the medium, the small one), and each ring has two faces, **p** and **m**, one per side. On each face there are twelve discs numbered **00** to **11**: 00 is the one with the second dot next to it, further in, and the dot sits on the side of disc 01, so it also shows which way to count. A disc's name puts the three things together: `xm00` is disc 0 of face m of ring X, `zp07` disc 7 of face p of ring Z. The double dot has a name of its own, `⊙xm`, `⊙zp` and so on.

The **Register by points (p)** button opens click mode. The panel shows a button for every disc, one row per ring: since of each ring you can see only one face at a time, the row is `xm00 ... xm11` or `xp00 ... xp11`, and the session chooses which to offer by reading the pose you put the cage in. Above the rows is the line **face you see:** with six buttons, `Xp Xm Yp Ym Zp Zm`, to correct it when it gets it wrong or when a ring is nearly edge-on and will not commit: the rule for reading a face off the photo is to look at the double dot, from the big disc toward the small dot, counter-clockwise is `p`, clockwise is `m`; in doubt, the `n` key (see below) shows it on the photo.

The gesture: press a disc's button, then click where it is in the photo. The click is assisted: the program looks for the dark disc around the clicked point and puts the click exactly at its centre, so there is no need to aim to the pixel. If the snap catches the wrong patch (a shadow, a highlight, a nearby disc) you see it from the dot ending up elsewhere: **Alt+click** places the click literally where you clicked, with no automatism. Alt works this way only with the drawn cage hidden (`v`). With the cage visible, Alt-drag does something else: it rolls the virtual cage, which snaps back on release, to see where a disc is that cannot be made out in the photo, typically the inner dot of disc 00, covered or in shadow. You need not place them all, nor even count them: the double dot of a ring that shows it well and four discs of the same ring are enough, with the names that look right to you. Then press the **`a`** key (in this mode there is no button). A crown of twelve equal discs reads identical when rotated, so the names you gave may be offset without any pose noticing; it is the rest of the cage, compared with the discs found across the whole photo, that decides how that crown reads, and the program tells you whether your names were right or whether it corrected them, without touching your clicks. The **`n`** key writes the discs' names on the photo itself: if they land far from the discs, it is the cage that is out of pose. Right click is the eraser: it removes the click under it. **`v`** hides the drawn cage, useful when the solid tabs cover the very disc you need to click.

### Refining together: `R`

Each photo on its own solves its own camera, but a single photo cannot tell a wrong focal length from a wrong distance: it absorbs one into the other and reports a clean residual anyway. Once you have four or more photos registered, the **`R`** key refines **together** a single focal length and all the poses, on the clicks and discs already there. The REPL prints the focal length found and the residual before and after; a photo that would make the fit worse loses its vote on the lens but stays in the film with its pose, and the message says so. If the refinement makes things worse overall, it is not applied, and the message points you to the worst photos. If instead it says the lens hit the limit of the pass, press `R` again: each pass moves at most 15%.

### Confirming

The **Confirm (OK)** button writes an ordinary form into the source, in place of the one you evaluated:

```clojure
(def A (acquire "/Users/me/scans/valvola"
         {:proxy (registration-cage :d 175.4 :gen 2 :rim-marks? true)
          :pose  {:position [0 0 0] :heading [0 0 1] :up [0 1 0]}
          :marks {}
          :edges {}}))
```

The form holds what belongs to the program; the camera poses, the clicks, the focal length and the eyeballed photos stay in a session file next to the photos, `acquire-state.json`. **Close** leaves without writing the measurements but still strips the `edit-`: the form stays valid. Putting `edit-` back in front and re-evaluating, the session reopens where it was: it is the same round trip as `edit-mesh-split` (ch. 18.3). Esc never closes the session: it steps out one level from the mode you are in.

## 20.5 Anchors: a plane by eye

With the cameras registered, you need somewhere to draw. The cage knows nothing about the object it contains, so you place the plane yourself, and it is called an **anchor**: a named pose, with a position and a normal, that lives in the form's `:marks` map.

It is not done inside edit-acquire: there your geometry is hidden and the script does not run, so you could not judge an anchor on the object you build on it. It is done on the stage (20.7), by writing the intent into the source:

```clojure
:marks {:coperchio (edit-plane-by-eye :big)}
```

On Run the camera flies into a photo and a disc appears at the centre of the cage, parallel to the big ring (`:medium` and `:small` for the other two, or `:x` `:y` `:z`): a known starting point, since the part sits at the centre by construction. From there you carry it onto the flat zone of the object with the translate and rotate gizmo; a click on the photo puts the origin where you clicked; the arrow keys move the origin in the plane and, with Shift, the plane in depth. The check is always the same: change photo with `[` and `]` and see whether the plane stays resting on the surface from every angle. And the geometry you have already built on that mark stays over the photo and moves with the plane at every gesture: you aim at it, not at the disc. Enter writes the anchor into the source:

```clojure
:marks {:coperchio (plane-by-eye :big {:position [12.4 -3.1 41.0] :heading [0 0 1] :up [0 1 0]})}
```

To touch it up again put `edit-` in front and Run; Backspace puts it back where it started; Esc leaves everything as it was. A `(plane-by-eye :big)` without a pose is already a valid mark: the plane of the ring itself. From then on renaming an anchor, moving it by a millimetre or deleting it is ordinary text editing. Details in [edit-plane-by-eye](ref:edit-plane-by-eye) and [plane-by-eye](ref:plane-by-eye).

## 20.6 Shooting live

The session can take photos directly from a connected camera: a webcam, or the phone offered to the Mac as a system camera. The box at the bottom of the panel has the **Camera** button, which opens the preview in a corner of the viewport (the camera light comes on only when you press it), a menu to choose the device if there is more than one, and the **Grab (g)** button. You frame by looking at the object, not at the preview, and press `g`: the frame is saved into the session folder as `grab-01.jpg`, `grab-02.jpg` and so on, and appears in the filmstrip as a photo to register. Then you register it like any other photo: pose by eye and `a`. A bad shot is discarded with **Delete view N**: on the first click the button asks "Sure?", on the second it deletes the view and its file.

A live frame has no EXIF, so the focal length must be measured rather than read. `R` does that, and in an all-live session the measured focal length is filed for that camera at that resolution in `~/.ridley/cameras.json`: the next time you open the same camera, the session starts from the remembered focal length instead of the default, and says so ("Focal remembered for this camera"). Before that measurement the focal length is the slider's, and a wrong focal length does not present itself as such: it registers cleanly with the camera at the wrong distance. That is why, in an all-live session, do `R` early. And keep the phone's automatic framing features (Center Stage) off: they re-crop live, and the lens becomes a moving target no memory can absorb.

In the desktop app the camera asks for macOS's permission the first time. In a browser it works on `localhost`; on an `http://` address of another machine it cannot work, because the camera requires a secure context.

## 20.7 The stage

Evaluating an `(acquire ...)` in the source turns on the stage: around the object a pyramid-shaped placeholder, the frustum, appears for every registered camera. It is not a modal session: it is viewport state, and all of Ridley's ordinary tools keep working. It turns itself off at the first Run that no longer contains any `acquire`.

A click on a frustum brings the camera exactly into that photo's pose, with the photo as backdrop and the Ridley geometry over it, in correct projection: what you see aligned on screen *is* aligned on the real object. The controls appear in the viewport toolbar: **‹** and **›** fly from one photo to the next, the central **Photo** toggle shows where you are (`photo 3/12`) and switches between the in-pose view and free orbit, **Marks** shows or hides the anchors and edges written in the source, **Plane** opens the measured-plane gesture (below). From the keyboard, `[` and `]` navigate the photos and Esc returns to orbit. In pose the wheel zooms the photo around the cursor and the right button drags it: the camera stays locked on the registered pose, you only move within the frame. If a photo is badly registered, the toggle flags it with a ⚠: on that photo everything reprojects wrong, anchors included.

[acquire](ref:acquire) is not just a declaration: it returns a value, destructurable by name:

```clojure
(:proxy A)   ; the cage, mounted as scaffold
(:marks A)   ; the anchors, by name
(:edges A)   ; measured edges, by name
```

The cage is mounted in the scene as a scaffold: it is visible, you can attach to it, but it never enters a CSG or an export. And for the thing you do all the time, placing the turtle on an anchor, there is the short form:

```clojure
(turtle A :at :coperchio
  (extrude (rect 18 16) (f 2)))
```

which is short for `(turtle (:coperchio (:marks A)) ...)`.

## 20.8 Tracing over the photo

Tracing is done with the tools you already know, on top of the anchor:

```clojure
(turtle A :at :coperchio
  (edit-path-2d))
```

The path editor ([edit-path-2d](ref:edit-path-2d), ch. 5) opens on the anchor's plane, and the nodes are placed by clicking over the photo: the stroke is real 3D geometry on that plane. Here comes the move that makes tracing reliable: **changing photo while the editor is open**, with `[` and `]`. The stroke does not move, the camera does: if from the new viewpoint the stroke still matches the object, the tracing is right; if it comes away, you are drawing at the wrong depth, and you correct it looking at it from there. It is triangulation done by eye, with the geometry reprojecting itself. A practical warning: from some angles the sketch plane is nearly edge-on to the camera, and clicks become ill-conditioned (small aiming errors, big jumps on the plane); if the clicks seem to go wild, change photo. Once the tracing is closed, the shape emitted into the source is an ordinary `poly`: you extrude it, use it in a CSG, parametrise it. From here on it is chapter 4.

When you cannot place the plane by eye, the stage can **measure** it: the **Plane** button in the toolbar. In pose on a photo you click a point on a flat area of the object; a yellow dot appears on the click's ray, which seen from the other photos slides along a line. You change photo with `]`, click the same physical point again helped by the line, and the dot turns green: the point is triangulated. With `n` you move to the next point; after three points Enter fits the plane and proposes a translucent disc resting on the area, which you check by navigating the photos; a second Enter accepts it and writes a [plane-mark](ref:plane-mark) into `:marks` in the source, with the points it was born from. The HUD guides you with the numbers that matter, parallax and error in pixels. A mark like this reopens by prefixing `edit-` and re-evaluating, like any other edit session. The result is used exactly like an anchor: `(turtle A :at :piano-1 (edit-path-2d))`.

And when you can place the plane **by eye**, the anchor of 20.5, `edit-plane-by-eye`, lives on this same stage: same gizmo, same check by changing photo, and the geometry built on it follows.

## 20.9 The scaffold comes down

As in chapter 18, the scaffold comes down by deliberate replacement, not by deletion. While you work, the `(acquire ...)` stays in the program: it is your archive of measurements, and the stage is always one eval away. When a part is finished, replace the named access with its literal (an anchor's pose, a point's position), and when the last dependency is untied the form leaves the source. The photos stay in the folder, and so does the session file: the tracing guide is preserved, but the program no longer needs it.

Two honest limits, so you know what to expect. The precision of the registration is the cage's: the discs are well-defined points by construction, which is why the reference is the cage and not the object, but a warped cage or a part that moved between two shots do not show in a single photo's residual, they show when you change photo. And tracing remains work done by eye: the cage gives you measured views, the geometry you draw yourself, and checking from another photo is the part of the method that cannot be skipped.
