# Handover — edit-acquire P3 (ricalco su piano dichiarato, fetta sottile)

Aggiornato 2026-07-23. Riprende da `dev-docs/HANDOVER-edit-acquire-pnp.md`
(registrazione per corrispondenze PnP, P2) e dal brief
`dev-docs/brief-param-acq-v1.md` (P3 + lo "Stato P3-slice"). **COSTRUITO e
verificato a livello matematico; NON committato; in attesa del gate umano.**

## In una riga

Registrata la camera (PnP, P2), il valore del prodotto è ricalcare le feature
del pezzo *sopra la foto in posa* e vederle *riproiettate nelle altre viste*.
Questa fetta lo fa nel modo più sottile possibile: un **modo `:retrace`** dentro
`edit-acquire` (tasto `d`), una **polilinea** su un **piano dichiarato** (una
faccia del proxy). NON è edit-path-2d (che è una sessione modale a sé e non può
stare dentro quella di edit-acquire senza costruire il "palcoscenico" non-modale
di P4). Scelta concordata con Vincenzo il 2026-07-25.

## La spina (tutto già cablato)

1. **Backprojection** — `photogrammetry/camera.cljs` `pixel-ray` (l'inverso
   esatto di `project` per k1=k2=0) + `ridley.math/ray-plane-point`. Un click
   foto → pixel (`backdrop/pixel-under-pointer`) → raggio nel frame OGGETTO
   (via `bridge/editor->solver-pose` della camera corrente) → ∩ col piano →
   punto 3D nel frame oggetto. Test `test/ridley/photogrammetry/backproject_test.cljs`:
   andata-ritorno a **0.000000000 mm** su più viste.
2. **Piano dichiarato** — una faccia del box nel frame oggetto: `{:axis 0|1|2
   :sign ±1 :offset mm}`. Default = faccia **top** (axis 1 = `box-basis` up,
   sign +1). `offset` sposta il piano verso l'esterno lungo la normale (feature
   che sporge dal piano). Pannello: 6 bottoni faccia + slider offset.
3. **Ricalco** — in `:retrace` un click sulla foto aggiunge il punto oggetto
   alla polilinea (`:retrace :points`). La polilinea è disegnata come geometria
   3D world (`bridge/local->world` + `viewport/show-preview!` lines+dots
   on-top), col rettangolo della faccia in blu e il tratto in giallo. Lente
   d'ingrandimento riusata dal PnP (rotella = zoom).
4. **Riproiezione live** — `[` / `]` (o click miniatura) muovono la camera sulla
   foto scelta e **ri-mostrano la stessa polilinea world dall'altra angolazione**
   sopra quella foto. Nessuna matematica extra: la geometria è nel mondo, cambia
   solo la camera. `enter-photo!` in `:retrace` NON reinstalla il gizmo, chiama
   `redraw-retrace!`. (Si può anche cliccare da più viste sulla stessa faccia:
   ogni click ri-backproietta dalla camera corrente sullo stesso piano — punti
   coerenti.)
5. **Persistenza** — `:retrace` (piano + punti frame-oggetto) in
   `acquire-state.json`. Frame oggetto = stabile se il proxy si muove. Ricaricato
   all'ingresso (`apply-loaded-state!`); premi `d` per riprenderlo.
6. **Emissione minima** — alla chiusura `emit-retrace!` stampa (`state/capture-
   println`, edit-acquire non ha marker sorgente) un `(poly x1 y1 …)` delle
   coordinate nel piano + un commento col piano e i punti 3D. È il "P4 anticipato
   di un pezzo": senza, i ricalchi si perderebbero (la persistenza li salva
   comunque per il re-entry; questa è la sorgente che l'utente TIENE).

## Tasti / UI (dentro edit-acquire)

- `d` entra/esce dal ricalco; in `:retrace`: click = aggiungi punto, `⌫` =
  annulla ultimo, `[`/`]` = rivedi dalle altre viste, Esc = esci dal ricalco.
- `p` (PnP) e `d` (retrace) sono mutuamente esclusivi e si entrano solo dal
  gizmo (i bottoni d'ingresso appaiono solo in `:gizmo`), così nessun modo parte
  sopra un altro.
- `s`/`f` sono gated fuori dal retrace (agiscono sulla posa, che qui è congelata).

## Il gate umano (prossimo passo, giudice = Vincenzo)

Con una sessione registrata (le 6 foto del lettore SD nastrato, camera già
messa in posa via `p`/PnP): premi `d`, scegli la faccia (default "Sopra"),
clicca il contorno del bezel sulla foto, poi `]` per vederlo riproiettato sulle
altre foto. Giudica: (1) il tratto disegnato su una vista **cade sulla stessa
feature** vista da un'altra angolazione (≤ qualche px = piano giusto)? (2) è
comodo? Se una feature NON è planare, per ora esce dal piano nelle altre viste —
è atteso (i "punti liberi triangolati" del brief sono coda, non questa fetta).

Se PASSA → si può committare, poi (coda) promuovere il ricalco a edit-path-2d
vero quando P4 rende la fase-2 non-modale (il "palcoscenico" `acquire`).

## File toccati (NON committati)

| File | Stato | Cosa |
|---|---|---|
| `src/ridley/photogrammetry/camera.cljs` | mod | `pixel-ray` (backprojection) |
| `src/ridley/math.cljs` | mod | `ray-plane-point` |
| `src/ridley/photogrammetry/bridge.cljs` | mod | `local->world` (public) |
| `src/ridley/editor/edit_acquire.cljs` | mod | modo `:retrace` (piano, click, preview, riproiezione, persistenza, emissione, pannello, tasti) |
| `public/css/style.css` | mod | `.eaq-retrace-box` |
| `test/ridley/photogrammetry/backproject_test.cljs` | nuovo | andata-ritorno a 0 mm |

## Limiti noti (coscienti, per la fetta)

- Emissione `(poly …)`: presuppone un contorno **chiuso** (≥3 punti); il bezel
  lo è. Tratto aperto → per ora solo il commento coi punti. (Coda: `poly-path`.)
- Il piano è UNO per ricalco: cambiare faccia azzera i punti (appartengono al
  piano vecchio). L'offset invece è live e non azzera (è un controllo da mettere
  *prima* di tracciare).
- Nessuna modifica dei punti (solo append + annulla ultimo): la ricchezza di
  editing è di edit-path-2d, che arriva in P4.
