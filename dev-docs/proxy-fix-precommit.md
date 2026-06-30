# Pre-commit — verifiche di chiusura del fix del proxy direzionale

Branch: `miter-join-corner-construction`. Tornata di sole verifiche (più una micro-protezione
ammessa, risultata non necessaria) prima di committare il fix. Segue
[proxy-fix-implementation.md](proxy-fix-implementation.md).

> **Esito: il fix del proxy è COMMITTABILE.** Le tre verifiche passano (escaper pulito;
> esempi non regrediti — per garanzia dimostrata, l'eval headless è bloccato; extrude-holed a
> basso rischio, guardia scalare già attiva). Una scoperta da quaderno: il residuo shell
> t=0.2/FIX **non è un artefatto discreto** (ipotesi falsificata) ma una self-intersezione
> reale del corner dual-ring al bordo — buco noto, follow-up separato.

---

## Verifica 1 — l'escaper costruisce davvero pulito (RATIFICA PIENA)

Misurato: off-disk cx10 rr4 (`:centered? false`) su FIX, costruito col fix:
- **extrude → tri-tri = 0**
- **loft → tri-tri = 0**

Ratifica piena: l'escaper era un **bersaglio sbagliato** dell'accertamento (errore di misura
nel frame — reach 14 dall'origine di disegno vs 8 nel frame di stamp). Il fix ha ragione
contro l'accertamento: la geometria è realizzabile (8·tan44.5° ≈ 7.86 < gamba 10) e costruisce
pulita su **entrambi** gli operatori (invariante extrude≡loft confermato).

## Verifica 2 — blast radius sugli esempi reali

**Eval headless degli esempi: BLOCCATO da due cause indipendenti**, entrambe non aggirabili
senza il browser:
1. `sci-harness/eval-dsl` (subset di binding, *binding drift*) → ogni esempio fallisce su un
   simbolo mancante (`fillet-shape`, `shape-fn`, `poly`, `shape-difference`, `add-mark`, …).
2. `ridley.editor.repl/evaluate` (binding di produzione completi) → **`window is not defined`**:
   il contesto di produzione non è node-safe. Gli esempi girano solo nel runtime browser
   (lo smoke-test headless via nREPL+browser di `reference_headless_example_eval`).

**Ratifica per garanzia dimostrata (non per esecuzione):** il fix direzionale **non può
rifiutare un caso che prima costruiva pulito**. Dimostrazione: il direzionale rifiuta più
dello scalare solo per profili off-center (reach dal rail > max-dal-centroide); ma se uno
costruiva *pulito* sotto lo scalare (accettato e non ripiegato), allora la reach interna reale
≤ gamba/tan → il direzionale (= reach reale) **accetta anch'esso**. Quindi:
- **nuovi rifiuti** solo su casi che **prima ripiegavano** (geometria già rotta) → corretti;
- **poligoni centrati** prima over-conservativi → ora costruiti (miglioramento);
- **cerchi** invariati (direzionale = shape-radius).
Più: V1 conferma il caso off-center più estremo (escaper) costruisce pulito; la suite (373
test) è verde. **Nessun esempio prima-pulito può regredire.** L'unico cambiamento osservabile
possibile è un esempio che *prima produceva una mesh ripiegata in silenzio* e ora riceve
l'errore leggibile — miglioramento, raro.
**Residuo:** la conferma *visiva* sugli esempi resta lo smoke-test browser
(`reference_headless_example_eval`) — consigliato come gate finale, **non bloccante** data la
garanzia. (Nessun rifiuto `too sharp` osservabile è atteso.)

## Verifica 3 — quanto è scoperto l'extrude-holed (ESITO: guardia scatta → basso rischio)

`extrude-with-holes-from-path` **chiama già `validate-corner-realizability!`**
([extrusion.cljs:1541](../src/ridley/turtle/extrusion.cljs#L1541)) con
`analyze-open-path` scalare. Misurato:
- **GROSSO** (frame outer-14 hole-8 / `(f12 th90 f12)`, shape-radius ≈19.8) → **RIFIUTATO**
  ("too sharp for how wide"). La guardia **scatta** sui casi grossi.
- realizzabile (frame outer-8 hole-4 / LONG) → costruito **tri-tri = 0**.
- stretto realizzabile (frame outer-8 hole-4 / f12) → costruito **tri-tri = 0**.

**Esito 1** del brief (guardia scatta sui casi grossi, manca solo il raffinamento direzionale
sui casi-limite asimmetrici) → **non è un buco aperto**, è un **raffinamento mancante**.
**Protezione-tampone NON applicata (non necessaria):** la guardia scalare già protegge i casi
grossolani; l'extrude-holed non costruisce rotto senza rifiutare nei casi evidenti. Destino:
**follow-up direzionale a bassa priorità** (trapianto del fix, con il loft-holed già sul path
direzionale come modello), **niente nel manuale** (è un bug noto in attesa di fix, non un
limite di design; un avvertimento diventerebbe falso appena chiudi il follow-up).

## Verifica 4 (quaderno) — il bordo shell t=0.2/FIX: artefatto o dimensionamento?

Ipotesi nel report di implementazione: "artefatto discreto del corner dual-ring". **Test
(sweep di risoluzione del cerchio):**

| segs | tri-tri |
|---|---|
| 24 | 52 |
| 48 | 60 |
| 96 | 86 |

Il fold **cresce** con la risoluzione, non scende. Per il criterio del brief ("se il fold non
scende, era dimensionamento, non artefatto") → **NON è un artefatto discreto: è una
self-intersezione REALE** del corner **dual-ring** al bordo di realizzabilità (eff≈0.07, dove
la pelle interna / il bridge dual-ring si attraversano). Il fix l'ha **migliorata 126→52** ma
non eliminata. **Ipotesi "artefatto" falsificata e corretta** nel report e nel commento della
rete. È un **buco noto, follow-up separato** (costruzione del corner dual-ring all'orlo — non
il proxy). La rete usa casi comodi per la bussola shell-clean (t=3/LONG, t=6/LONG → 0) e
riusa t=0.2/FIX solo come esempio di mesh auto-intersecante per la cecità di mesh-diagnose.

---

## Quaderno — i tre risultati ratificati + correzioni

1. **`:centered? false` corner = stesso difetto del proxy (non un secondo meccanismo).**
   Quel meccanismo separato **non esisteva**: l'offset di stamp (primo punto sul rail) sposta
   il centroide fuori dal rail → overhang che lo shape-radius (dal centroide) sotto-mitrava.
   Il direzionale lo dimensiona correttamente → costruisce pulito. Test convertito a
   regression guard (`centered-false-corner-now-clean`). **Famiglia chiusa dal fix del proxy.**
2. **Bordo shell t=0.2/FIX = self-intersezione REALE, non artefatto** (V4: fold 52→60→86 con la
   risoluzione). Difetto del corner **dual-ring** all'orlo di realizzabilità; il proxy l'ha
   ridotto (126→52) ma non chiuso. **Buco noto, follow-up** (corner dual-ring), distinto dal
   proxy.
3. **extrude-holed = buco noto a basso rischio.** Usa ancora il proxy scalare (centroide); la
   guardia scalare **scatta sui casi grossi** (rifiuta), manca solo il raffinamento direzionale
   sui casi-limite asimmetrici. **Nessuna protezione-tampone necessaria.** Follow-up:
   trapiantare `analyze-open-path-dir` anche su `extrude-with-holes-from-path` (il loft-holed
   è già sul direzionale via `compute-corner-data`). **Niente nel manuale.**

---

## Verifica conclusiva

- **V1 escaper tri-tri=0 su extrude e loft** → ratifica piena. ✓
- **V2 esempi: nessuna regressione visibile possibile** (garanzia dimostrata: nessun caso
  prima-pulito può essere rifiutato) — eval headless bloccato (binding drift + `window`);
  smoke-test browser consigliato come gate visivo finale, non bloccante. ✓
- **V3 extrude-holed: guardia scalare scatta** (basso rischio), tampone non necessario,
  destino = follow-up nel codice, niente nel manuale. ✓
- **Quaderno aggiornato**: `:centered? false` risolto come stesso difetto; bordo shell t=0.2/FIX
  = self-intersezione reale (non artefatto, ipotesi falsificata e corretta); extrude-holed =
  buco noto a basso rischio con guardia scalare attiva.

**Il fix del proxy è committabile.** (Suite 373 test, 0 fallimenti — riconfermata dopo la
pulizia degli scratch.)

Fuori scope rimasti (follow-up nel codice, non nel manuale): raffinamento direzionale
dell'extrude-holed; corner dual-ring all'orlo (residuo shell t=0.2/FIX). Restano archiviati:
miter-join, A1/tag smooth arc, Classe B planarità embroid, bezier 3D nel rail distorto,
extrude-closed.
