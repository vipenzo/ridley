# HANDOVER — fetta B identità-free: COSTRUITA, manca il gate live #2

**Branch**: `edit-acquire-registration-stability`
**Data**: 2026-07-27
**Stato**: implementata + suite verde (765/0) + validata sui DATI REALI (geometria
foto 1 + `disc-at?` sui pixel). **NON committato.** Gate live #1 fallito e
diagnosticato (2 cause, entrambe risolte, vedi sotto). Manca il **gate live #2**
(Vincenzo, con l'esempio RIVALUTATO, clicca 4 dischetti qualsiasi). Poi si committa.

Predecessore: `dev-docs/HANDOVER-edit-acquire-fetta-B.md` (il design).

## ⚠ Gate live #1 fallito → 2 cause (entrambe risolte)

Vincenzo ha cliccato 12 dischetti + `r` → "Non riesco ad assegnare le identità".
Il messaggio NON aveva la parentesi "(dischetti riconosciuti X/12)" → `assign-marks`
tornava **nil**. Cause:

1. **Il suo proxy non aveva lo zero-indice** (`piatto-carta` era una def VECCHIA,
   pre-modifica). `plate-detect` → nil → `:zero-obj` nil → `assign-marks` nil.
   **Fix codice**: messaggio esplicito ("rivaluta examples/param-acq-plate.clj
   aggiornato e riapri") invece del fuorviante "clicca più sparsi".
   **Fix lato Vincenzo**: DEVE rivalutare l'esempio aggiornato (ora il piatto ha
   lo zero sotto `:anchors :zero`) e riaprire la sessione.
2. **Seed omografia troppo grezzo sui dati reali**: `estimate-homography` senza LM
   riproietta la corona a ~30px medi (max 47) ≈ dimensione disco (~33px) → il
   giudice on-disc mancava e lo zero-indice falliva. **Fix codice**: scorer a DUE
   PASSI (sotto). Verificato: coi pixel VERI (foto 1), 4/8/12 click recuperano
   tutti l'identità esatta.

---

## Cosa fa (l'interazione)

In modalità `p` (PnP), su un **piatto**, il tasto **`b`** attiva/spegne la
modalità **batch senza identità**:

- **batch ON**: nessun marker armato. Clicchi 4+ dischetti *qualsiasi* — ognuno
  si aggancia al centroide del disco (seed-snap, riuso di fetta A). Poi **`r`**
  assegna le identità da solo e registra (passa al solve di fetta A: rifinisce la
  posa e blob-snappa gli altri dischetti).
- **batch OFF** (default): resta la fetta A armata (evidenzia-un-mark-alla-volta),
  la **rete di sicurezza**.

Bottoni equivalenti nel pannello: "Senza identità (b)" (dal flusso armato),
"Assegna (r)" / "Annulla ultimo" / "Modalità armata (b)" (nel batch).

## Come lavora (l'algoritmo)

Nucleo puro `ridley.photogrammetry.match-plate/assign-marks` — ricerca esaustiva
con l'**immagine come giudice** (niente stima d'angolo, fragile in prospettiva),
a **DUE PASSI** (il seed dell'omografia è troppo grezzo per il giudice on-disc):

1. I click in ordine ciclico nell'immagine.
2. Enumera ogni assegnazione che preserva l'ordine ciclico: C(12,k) sottoinsiemi
   × k rotazioni × 2 sensi (per k=4 ≈4000 candidati).
3. **COARSE**: per candidato `pnp/estimate-homography`, riproietta 12 mark + zero,
   `disc-hits` TOLLERANTE (un disco entro ~2 raggi-anello dalla riproiezione, per
   assorbire i ~30px d'errore del seed) → shortlist i top `max-refine`=40.
4. **FINE**: `pnp/refine` (LM) di ogni shortlisted → posa ~8px; scarta lo specchio
   back-facing (front-facing filter); punteggio TIGHT = crown-on-disc +
   (zero-su-disco ? 100 : 0). Vince il massimo.

**Due simmetrie**, entrambe rotte:
- **rotazione** (corona simmetrica a 30°): 12 rotazioni pareggiano crown=12 → lo
  **zero-indice** (il pallino interno asimmetrico) elegge la giusta.
- **specchio** (corona+zero simmetrica attorno all'asse dello zero): un'assegnazione
  riflessa riproietta tutto sui dischi e pareggia anche lo zero → la rompe il
  **filtro front-facing** (la faccia marcata deve puntare VERSO la camera). Se il
  segno della normale fosse invertito, `assign-marks` torna nil → messaggio "non
  riesco ad assegnare" (fallimento SICURO, mai una posa sbagliata).

## File toccati

- `src/ridley/photogrammetry/match_plate.cljs` — **nuovo**, `assign-marks` (puro).
- `src/ridley/photogrammetry/blob.cljs` — **`disc-at?`** (giudice economico).
- `src/ridley/photogrammetry/bridge.cljs` — `plate-detect` (zero-obj + disc-r +
  face-normal), `world->local-dir`; `pnp-target-points` FILTRA `:zero` dalla corona.
- `examples/param-acq-plate.clj` — zero-indice sotto `:anchors :zero`, `:mark-disc-r`.
- `src/ridley/editor/edit_acquire.cljs` — tasto `b`, raccolta batch, `assign-batch!`
  (`r`), pannello batch, disegno pallini batch.
- Test: `test/ridley/photogrammetry/match_plate_test.cljs` (nuovo),
  `bridge_test/plate-detect-and-zero-exclusion`.

## Cosa è verificato (e cosa no)

- Suite node **765/0**: geometria su 4 viste oblique + pick simmetrico 90° + rumore
  (recupera l'identità esatta, incl. rotazione E specchio); plumbing bridge.
- **DATI REALI (foto 1, replay `recovers-identities-on-real-oblique-pose`)**: 4/8/12
  click recuperano TUTTI l'identità esatta (crown 12, zero true); segno front-facing
  confermato (+Z object nz=-0.635). Pixel veri hardcoded nel test.
- **`disc-at?` sui pixel VERI**: decodificato IMG_8938/8939 con `sharp`, click veri
  da `param-plate-paper/acquire-state.json` → **12/12 dischi riconosciuti, 0 falsi
  positivi sul piatto** (script in `scratchpad/disc_check.js`, riusabile).
- **NON verificato dal codice**: il gesto end-to-end in-app (tab browser stale su
  hot-reload → il nuovo codice non gira nella tab aperta; serve Cmd+Shift+R). Ecco
  il gate live qui sotto.

## Il gate live #2 (Vincenzo)

1. In app, ricarica con **Cmd+Shift+R** (il codice nuovo NON è nella tab aperta).
2. **RIVALUTA `examples/param-acq-plate.clj` AGGIORNATO** (era la causa #1 del
   fallimento: il tuo `piatto-carta` non aveva lo zero-indice) e apri:
   `(edit-acquire "test-assets/param-plate-paper/" {:proxy piatto-carta})` **nella
   stessa valutazione**.
3. Su una foto: **`p`** → **`b`** (batch) → clicca **4 dischetti** qualsiasi, ben
   SPARSI attorno al piatto (non tutti vicini) → **`r`**.
4. Atteso: assegna le identità e mostra "PnP planar: … rms …px" (<14).
   - Se dice "**Questo piatto non espone lo zero-indice…**" = la causa #1 non è
     risolta: hai aperto con una def vecchia del piatto. Rivaluta l'esempio e riapri.
   - Se dice "**Non riesco ad assegnare… (dischetti riconosciuti X/12…)**" = clicca
     più sparsi / assicurati che lo **zero-indice** (pallino interno) sia visibile.

Se registra bene su più foto → **committare**. La geometria reale è già verificata
dal test, quindi il gate #2 è soprattutto conferma su LUMINANZA reale + ergonomia.

## Decisione UX aperta (da prendere DOPO aver provato)

Guardia "4 click troppo ravvicinati" (omografia mal condizionata): per ora è
**reattiva** (il messaggio al fallimento dice "clicca più sparsi"). Se in pratica
capita spesso e infastidisce, si aggiunge un avviso **proattivo** prima di `r`.
Da decidere dopo il primo uso reale — non prima.
