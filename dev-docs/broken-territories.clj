;; ════════════════════════════════════════════════════════════════════════════
;; MAPPA ESEGUIBILE DEI TERRITORI ROTTI — auto-intersezione geometrica silenziosa
;; ════════════════════════════════════════════════════════════════════════════
;; Materiale diagnostico per lo sviluppatore (non una pagina del manuale, non un fix).
;; Questi blocchi costruiscono mesh che si AUTO-INTERSECANO senza errore: la guardia
;; non li vede e mesh-diagnose è cieco (l'auto-intersezione è geometrica, ogni edge
;; resta con due facce). Solo il test tri-tri (Möller–Trumbore strict-interior) li
;; rivela. Ogni blocco è una forma top-level isolata (il renderer valuta un blocco
;; per volta). Identificatori in inglese, commenti in italiano.
;;
;; ESEGUIBILITÀ IN SPAZIO UTENTE: tutti i blocchi qui sotto sono stati valutati nel
;; Ridley reale (ridley.editor.repl/evaluate, runtime browser) e girano SENZA ERRORE;
;; la mesh è il valore di ritorno della forma (il panel la disegna). Forme DSL
;; verificate fedeli alla geometria misurata: (rect w h) = pieno w×h; (shell s
;; :thickness t) = parete piena uniforme; (make-shape pts {:centered? .. :holes ..}).
;;
;; VALIDAZIONE DEI NUMERI: i tri-tri citati sono MISURATI sulle funzioni di
;; produzione che i macro chiamano (pure-extrude-path / pure-loft-shape-fn /
;; extrude-closed-from-path) con profili a 24 segmenti. Il (circle r) del DSL usa la
;; risoluzione di default, quindi il CONTEGGIO esatto nel panel può differire; ciò che
;; conta è il verdetto tri-tri > 0 (rotto) vs = 0 (sano), robusto alla risoluzione.
;; ════════════════════════════════════════════════════════════════════════════


;; ────────────────────────────────────────────────────────────────────────────
;; TERRITORIO 1 — shell dual-ring al BORDO della realizzabilità  [PATOLOGICO]
;; ────────────────────────────────────────────────────────────────────────────
;; Quando il miter della pelle esterna eguaglia quasi esattamente la gamba del corner
;; (eff ≈ 0), il corner dual-ring lascia una piega residua. NON è l'errore del proxy
;; (quello è curato) e NON è un artefatto discreto: alzando la risoluzione il fold
;; CRESCE (24→52, 48→60, 96→86), quindi è auto-intersezione reale.
;;
;; FASCIA ROTTA misurata (base R=10, th=89°, pelle esterna ≈ R+t/2, miter ≈ 9.93):
;;   eff = gamba − miter:   0.02→78   0.07→34   0.12→0   0.27→0   0.57→0   1.07→0
;; → la zona rotta è eff ∈ (0, ~0.1) unità di gamba: una FASCIA SOTTILISSIMA attorno
;;   al bordo esatto di realizzabilità. In parametri utente: le gambe del corner
;;   devono cadere entro ~0.1 dalla lunghezza-miter esatta. Un utente con qualunque
;;   margine normale (gamba ≥ miter + 0.12) NON ci arriva mai. → curiosità di bordo.

;; ROTTO — gambe ≈ miter (eff ≈ 0.07): tri-tri ≈ 34
(loft (shell (circle 10) :thickness 0.2) (f 10) (th 89) (f 10))

;; SANO — stesso shell, gambe appena più lunghe (eff ≈ 0.57): tri-tri = 0
;; (basta ~0.5 di gamba in più: il confine è quello)
(loft (shell (circle 10) :thickness 0.2) (f 10.5) (th 89) (f 10.5))

;; ────────────────────────────────────────────────────────────────────────────
;; TERRITORIO 2 — extrude-holed con profilo OFF-CENTER  [PLAUSIBILE → follow-up]
;; ────────────────────────────────────────────────────────────────────────────
;; Il cammino con fori (extrude-with-holes-from-path) usa ancora il proxy SCALARE
;; (shape-radius dal centroide), non la proiezione direzionale. Per un profilo
;; :centered? false lo stamp mette il primo punto sul rail, spostando il centroide
;; fuori → un overhang che lo scalare sotto-mitra. Il cammino SENZA fori (direzionale)
;; lo costruisce pulito; quello CON fori si ripiega. La guardia scalare scatta sui
;; casi grossi ma MANCA questo: accettato, rotto, nessun errore.
;;
;; Misurato (FIX = (f 10)(th 89)(f 20)): frame off-center forato → tri-tri = 149;
;; lo stesso contorno SENZA foro (direzionale) → 0. Raggiungibile con QUALUNQUE
;; profilo forato non centrato (:centered? false è il default di make-shape /
;; path-2d), non solo al bordo. → territorio plausibile, priorità del follow-up
;; (trapiantare analyze-open-path-dir sul cammino con fori; il loft-holed è già ok).

;; ── CORREZIONE 2026-07-01 (accertamento extrude-holed): l'esempio ORIGINALE con
;;    (th 89) NON si ripiega (tri-tri = 0). Con (th 89) il frame off-center sfora
;;    LONTANO dalla normale interna del corner → reach direzionale 0 → nessun miter
;;    necessario. La patologia è reale ma richiede che lo sforo cada SULLA normale
;;    interna: dipende dal SEGNO della svolta relativo al lato dell'offset. Esempio
;;    che SI ripiega davvero (verificato node-harness): stesso frame, svolta opposta
;;    (th -89), tri-tri ≈ 66. NON risolto: il fix di dimensionamento direzionale è
;;    stato scritto e RIMOSSO — non è un trapianto pulito, regredisce il lato esterno
;;    (fold del corner tapered forato, condiviso con loft-holed). Vedi
;;    dev-docs/extrude-holed-accertamento.md e corner_self_intersection_net_test/fam4-*.

;; ROTTO (scalare, tuttora) — svolta che porta lo sforo SULLA normale interna: tri-tri ≈ 66
(extrude (make-shape [[6 -4] [14 -4] [14 4] [6 4]]
                     {:centered? false
                      :holes [[[9 -1.5] [9 1.5] [11 1.5] [11 -1.5]]]})
         (f 10) (th -89) (f 20))

;; (l'esempio originale (th 89) costruisce PULITO — sforo lontano dalla piega — non era rotto)
(extrude (make-shape [[6 -4] [14 -4] [14 4] [6 4]]
                     {:centered? false
                      :holes [[[9 -1.5] [9 1.5] [11 1.5] [11 -1.5]]]})
         (f 10) (th 89) (f 20))

;; SANO — stesso frame forato ma CENTRATO sul rail (centroide all'origine): tri-tri = 0
;; (col centroide sul rail lo scalare misura giusto → niente piega)
(extrude (make-shape [[-4 -4] [4 -4] [4 4] [-4 4]]
                     {:centered? true
                      :holes [[[-1.5 -1.5] [-1.5 1.5] [1.5 1.5] [1.5 -1.5]]]})
         (f 10) (th 89) (f 20))

;; ────────────────────────────────────────────────────────────────────────────
;; TERRITORIO 3 — :centered? false corner  [EX-ROTTO, ORA CHIUSO]  ✓
;; ────────────────────────────────────────────────────────────────────────────
;; NON è un territorio rotto: è la prova che un posto prima pericoloso ora è sano.
;; Un profilo :centered? false su un corner prima si auto-intersecava (lo stamp
;; sposta il centroide fuori dal rail → overhang sotto-mitrato dal vecchio proxy).
;; Il fix direzionale lo misura nel frame di stamp e lo costruisce pulito. Era lo
;; STESSO difetto del proxy, non un secondo meccanismo: la famiglia è chiusa.
;;
;; Misurato: prima del fix si auto-intersecava; ora tri-tri = 0 (extrude e loft).

;; SANO ORA — quadrato :centered? false su un corner generoso: tri-tri = 0
(extrude (make-shape [[-5 -5] [5 -5] [5 5] [-5 5]] {:centered? false})
         (f 40) (th 89) (f 40))

;; ────────────────────────────────────────────────────────────────────────────
;; SOSPETTO A — corner COMPOSITI (th+tv insieme), spinti forte  [BORDO/minore]
;; ────────────────────────────────────────────────────────────────────────────
;; Il fix gestisce i compositi (n_in dai due heading 3D, angolo da acos(h0·h1)).
;; Misurato (rect 20×6 su f15, th=tv variabile): 20°..50° → tri-tri = 0; da 60° in su
;; → tri-tri = 5 (piccolo). Solo i compositi ESTREMI (piega ≥60° in DUE piani insieme
;; su gambe corte) lasciano un residuo minimo, simile alla fascia di bordo del T1.
;; → curiosità di bordo, non priorità. Gentle/moderati sono puliti.

;; ROTTO (lieve) — composito estremo: tri-tri ≈ 5
(extrude (rect 20 6) (f 15) (th 60) (tv 60) (f 15))

;; SANO — composito moderato (≤45° per piano): tri-tri = 0
(extrude (rect 20 6) (f 15) (th 45) (tv 45) (f 15))

;; ────────────────────────────────────────────────────────────────────────────
;; SOSPETTO B — extrude-closed (rail chiusi ad anello)  [PLAUSIBILE, feature incompleta]
;; ────────────────────────────────────────────────────────────────────────────
;; extrude-closed-from-path NON chiama affatto validate-corner-realizability! e usa il
;; proxy scalare: doppiamente non protetto. Un tubo quadrato chiuso si auto-interseca
;; ai corner/cucitura SENZA alcun errore, anche quando il corner sarebbe realizzabile.
;;
;; Misurato (quadrato chiuso, lato L, profilo circle r):
;;   circ8 / L20 (comodo)  → tri-tri = 10     (anche un quadrato comodo si ripiega!)
;;   circ8 / L10           → tri-tri = 300
;;   circ12 / L10 (stretto)→ tri-tri = 724
;; → plausibile (il tubo chiuso ad angoli è una forma comune) e nessuna guardia lo
;;   ferma. Ma extrude-closed è una feature notoriamente incompleta: il problema è di
;;   livello-feature (assemblaggio del loop + guardia assente), non solo del proxy.

;; ROTTO — tubo quadrato chiuso, profilo comodo: tri-tri ≈ 10 (cresce stringendo)
(extrude-closed (circle 8) (f 20) (th 90) (f 20) (th 90) (f 20) (th 90) (f 20))

;; (nessuna variante "sana" vicina: extrude-closed si ripiega ai corner anche con
;;  margine comodo — è la feature che va completata, non un parametro da allargare)

;; ════════════════════════════════════════════════════════════════════════════
;; SINTESI PER LO SVILUPPATORE
;;   • PRIORITÀ:  Territorio 2 (extrude-holed off-center) — plausibile, raggiungibile
;;     con profili forati non centrati (default), cura nota (trapianto direzionale).
;;     extrude-closed (Sospetto B) — plausibile e senza guardia, ma è completamento
;;     di feature, brief a sé.
;;   • CURIOSITÀ DI BORDO (bassa priorità): Territorio 1 (shell al bordo, fascia
;;     ~0.1) e Sospetto A (compositi estremi ≥60°×2) — un utente normale non li
;;     incontra; al più una guardia con margine li convertirebbe in errore leggibile.
;;   • CHIUSO: Territorio 3 (:centered? false) — ex-rotto, ora sano.
;; ════════════════════════════════════════════════════════════════════════════
