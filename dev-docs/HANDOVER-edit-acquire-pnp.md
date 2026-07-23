# Handover — edit-acquire PnP pivot (registrazione per corrispondenze)

Aggiornato 2026-07-23. Riprende da `dev-docs/HANDOVER-edit-acquire-gate.md`
(gate della manipolazione invertita) e dal brief `dev-docs/brief-param-acq-v1.md`
(P2, sezione "Registrazione per corrispondenze"). Sostituisce il percorso
drag+snap come gesto PRIMARIO di registrazione — non lo cancella.

## In una riga

Il collaudo di slice-2 aveva lasciato quattro bug (tie-break di Klein nel `f`,
contatore "9 costante", fascia snap vs pennarelli, escalation). Sono tutti
sintomi di **una** malattia: nel drag+snap la posa camera è *inferita* da un
segnale debole/ambiguo (silhouette di un box simmetrico trascinato a mano).
Vincenzo ha proposto (brief P2) il gesto giusto: **seleziona un'entità sul
modello + click nella foto + identità dichiarata → PnP in forma chiusa**.
L'identità dichiarata uccide l'ambiguità di Klein; la forma chiusa uccide la
ricerca. Snap → rifinitore a valle; gizmo → piazzamento grossolano / fallback
per forme lisce.

Deciso gate-first. **Step 1 (FOV/EXIF) e Step 2 (prototipo-gate PnP) sono
FATTI e verificati dal vivo (Playwright), NON committati.** Manca il **gate
umano**: Vincenzo clicca gli spigoli del blocco reale e giudica.

## Cosa esiste ora

### Step 1 — FOV/EXIF (prerequisito, indipendente dalla UI)

Il bug: sia edit-acquire sia la CLI (`cli.cljs:25`) usavano la larghezza 36 mm
(convenzione 3:2), ma la focale-equivalente-35mm EXIF è riferita alla
**diagonale** 43.27 mm (CIPA), e le foto iPhone sono **4:3** (4032×3024). hFOV
corretta per 48mm-eq @4:3 = **39.65°**, non 41.11° — errore di scala ~4%,
quasi invisibile nel fit di una singola foto (assorbito nella distanza per la
degenerazione focale↔distanza) ma corruttivo per la geometria multi-foto e per
i seed iniziali → lo "snap debole".

- `camera/equiv-focal->hfov-deg [focal aspect]` — diagonale + aspetto reale
  (per 3:2 coincide con la vecchia formula larghezza-36; per 4:3 no).
- `photogrammetry/exif.cljs` — parser JPEG/TIFF minimale, senza dipendenze,
  legge il tag 0xA405 (FocalLengthIn35mmFilm) dall'ArrayBuffer del blob. Torna
  nil su qualunque sorpresa (fallback allo slider).
- `acquire-backdrop/set-focal!` (era `set-hfov!`) prende la FOCALE, non l'hFOV
  (solo il backdrop conosce con certezza l'aspetto della foto caricata).
- `edit-acquire` legge l'EXIF di foto 0 all'ingresso (`:focal-source
  :exif|:manual|:default`); lo slider resta come fallback/override.
- **La CLI resta su 36-width**: i suoi numeri certificati sono congelati lì;
  la divergenza di edit-acquire è una scelta consapevole e testabile. Da
  rivedere se/quando si riallineano CLI ed editor (criterio "stessi numeri").
- Live: IMG_8908 reale → 48 da EXIF, hFOV 39.65°. Test `exif_test.cljs`
  (buffer sintetico + JPEG reale + conversione).

### Step 2 — PnP-per-corrispondenze (il prototipo-gate)

**`photogrammetry/pnp.cljs`** — posa camera da corrispondenze 3D↔2D dichiarate:
1. `estimate-dlt` — stima lineare SEEDLESS in coordinate calibrate (pixel
   normalizzati per K → camera K=I, risolve direttamente [R|t]); la scala
   omogenea è fissata pinnando tz, così diventa un minimi-quadrati ordinario
   (`linalg/solve` sulle equazioni normali 11×11) — NIENTE SVD/autovettori
   (l'`eigen-symmetric` del progetto torna solo autovalori). Serve ≥6
   corrispondenze non complanari (spigoli su ≥2 facce); complanare/poche →
   nil (fallimento onesto).
2. `refine` — LM (`lm/solve`) sui 6 DOF, minimizza la riproiezione.
3. `solve-pnp` — dlt (≥6) poi refine; fallback a un `:seed` grossolano
   (gizmo) se i punti sono pochi. Torna `{:pose :rms-px :n :method :per-point}`.
   Test `pnp_test.cljs`: recupera la posa a 0.07–0.18 mm / 0.26–0.36 px.

**edit-acquire, modalità PnP** (tasto `p`):
- Il proxy diventa **wireframe** (il pezzo reale traspare, così si cliccano i
  suoi spigoli veri) + 8 pallini colorati agli spigoli, quello armato ingrandito.
- Armi uno spigolo (dichiari l'identità) e clicchi nella foto dov'è:
  `backdrop/pixel-under-pointer` fa il raycast contro il piano-foto e legge la
  UV → pixel (verificato ESATTO a 1e-10 contro `cam/project`). Un pallino
  colorato resta sul punto cliccato (overlay HTML; la camera è bloccata).
- `r`/"Risolvi PnP" (≥6 punti) → `solve-pnp` → applica la posa (proxy per foto
  0 via `solver-pose->proxy`; camera per 1..N via `solver-pose->camera`) →
  torna al gizmo. Badge verde con residuo.
- Pannello: prompt spigolo armato, 8 bottoni-spigolo (colore=spigolo,
  pieno=piazzato, anello bianco=armato), Risolvi/Azzera/Esci. Tasti: `p`
  entra/esce, `r` risolve, `1`-`8` armano, Escape esce da PnP (poi chiude).
- Live self-consistency (click esatti sui corner proiettati) su foto 0 E foto
  1: rms ~1e-11, spostamento 0. Il meccanismo regge; l'accuratezza sul pezzo
  reale è ciò che il gate umano deve giudicare.

**Robustezza agli spigoli mal-etichettati (dal collaudo di Vincenzo, 2026-07-23
— "certe foto non scendono sotto 62px")**: un solo spigolo con identità
sbagliata (facile sul box simmetrico) **avvelena tutto il fit ai minimi
quadrati** — la posa va a un compromesso e TUTTI gli spigoli finiscono a decine
di px, non uno solo. Diagnosticare "un outlier pulito" non basta. `solve-pnp`
ora fa **rigetto greedy degli outlier**: finché l'rms è sopra `accept-rms-px`
(12) e c'è un residuo GROSSO (sopra 30px E oltre 3× la mediana degli altri —
così non scarta spigoli buoni per rincorrere il rumore), butta il peggiore e
rifitta (max 2). Recupera la posa pulita dai buoni e riporta `:outliers`.
In edit-acquire lo spigolo scartato diventa un **pallino rosso** grande + tasto
rosso "N ✗", viene **ri-armato** per il ri-click, e il messaggio dice "scartato
lo spigolo #N (identità sbagliata) — ricliccalo, poi 'r'". Verificato dal vivo:
7 spigoli giusti + 1 mal-etichettato → rms 4.6e-11, outlier #8 flaggato, posa
corretta. I dischetti degli spigoli sono ora **traslucidi** (`:opacity` in
`create-dot-meshes`) così i dettagli della foto sotto restano leggibili.

Snap (`s`) e fit congiunto (`f`) restano invariati come percorso demansionato.

## Il gate umano (prossimo passo, giudice = Vincenzo)

Apri `test-assets/param-acq-box-tape`, premi `p`, clicca ≥6 spigoli reali del
blocco (su almeno 2 facce diverse), premi `r`. Giudica: (1) è ergonomico
cliccare le corrispondenze? (2) la posa risultante incolla il wireframe sul
blocco reale, senza flip? Usa il puntino di pennarello sul nastro come àncora
per sapere quale spigolo virtuale è quale fisico.

Se PASSA → promuovi PnP a primario, demansiona gizmo/snap, **lascia cadere**
tie-break/contatore/escalation (irrilevanti). Rifinitura ovvia: snap-assist sul
click (clicca grosso → aggancia allo spigolo sub-pixel). Poi P3 (viste
registrate) e P4 (emissione `acquire`).

## File toccati (NON committati)

| File | Stato | Cosa |
|---|---|---|
| `src/ridley/photogrammetry/camera.cljs` | mod | `equiv-focal->hfov-deg`, `frame-35mm-diagonal-mm` |
| `src/ridley/photogrammetry/exif.cljs` | nuovo | parser EXIF 0xA405 |
| `src/ridley/photogrammetry/pnp.cljs` | nuovo | DLT calibrata + LM |
| `src/ridley/editor/acquire_backdrop.cljs` | mod | `set-focal!`, `pixel-under-pointer` |
| `src/ridley/editor/edit_acquire.cljs` | mod | EXIF read, `session-intrinsics`, modalità PnP |
| `test/ridley/photogrammetry/exif_test.cljs` | nuovo | 731 test totali verdi |
| `test/ridley/photogrammetry/pnp_test.cljs` | nuovo | recupero posa sintetico |
| `public/css/style.css` | mod | stili pannello PnP |

`CLAUDE.md` e `dev-docs/brief-param-acq-v1.md` risultano modificati ma NON da
questo lavoro (edit diretti di Vincenzo — la sezione P2 del brief).
