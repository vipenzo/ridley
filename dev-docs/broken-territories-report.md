# Mappa dei territori rotti — qualifica della stranezza

Branch: `miter-join-corner-construction`. Solo misura + esempi, **nessuna modifica al codice di
produzione**. Esempi eseguibili: [broken-territories.clj](broken-territories.clj). Numeri
**misurati** sulle funzioni di produzione (`pure-extrude-path`, `pure-loft-shape-fn`,
`extrude-closed-from-path`, profili a 24 segmenti); il verdetto tri-tri>0/=0 è robusto alla
risoluzione. (Il node-harness non valuta la superficie DSL completa per binding-drift; conferma
visiva nel panel del browser consigliata, non bloccante.)

> **Sintesi.** Tre territori aperti, di stranezza molto diversa. **Priorità**: extrude-holed
> off-center (plausibile, raggiungibile coi default, cura nota). **Curiosità di bordo** (bassa
> priorità): shell al bordo di realizzabilità (fascia ~0.1) e compositi estremi. **Completamento
> di feature**: extrude-closed (nessuna guardia, ma è una feature incompleta a sé). **Chiuso**:
> `:centered? false` corner — ex-rotto, ora sano.

---

## Territorio 1 — shell dual-ring al bordo della realizzabilità — **PATOLOGICO**

**Esempio rotto (verificato):** `(loft (shell (circle 10) :thickness 0.2) (f 10)(th 89)(f 10))`
→ **tri-tri ≈ 34**. Non è l'errore del proxy (il dimensionamento corretto del miter sulla pelle
esterna è applicato) né un artefatto discreto: alzando la risoluzione il fold **cresce** (24→52,
48→60, 96→86) → auto-intersezione **reale** del corner dual-ring quando eff→0.

**Larghezza della zona rotta (in parametri utente):** con base R=10, θ=89°, miter ≈ 9.93, ho
spazzato la gamba:

| eff = gamba − miter | 0.02 | 0.07 | 0.12 | 0.27 | 0.57 | 1.07 |
|---|---|---|---|---|---|---|
| tri-tri | 78 | 34 | **0** | 0 | 0 | 0 |

→ zona rotta **eff ∈ (0, ~0.1)**: le gambe del corner devono cadere **entro ~0.1 unità** dalla
lunghezza-miter esatta `(R + spessore/2)·tan(θ/2)`. Indipendente dallo spessore e dall'angolo se
non per come spostano il miter (con margine ≥ ~0.12 è sempre pulito; verificato anche t=0.2..10 e
θ=40..89 con gambe comode → tutti 0).

**Giudizio: PATOLOGICO-al-bordo.** Una fascia di ~0.1 unità attorno al bordo esatto: un utente con
un qualunque shell comodo (gambe oltre il miter di più di un decimo) non lo incontra. Curiosità di
bordo; al più una guardia con un piccolo margine lo convertirebbe in errore leggibile. Bassa priorità.

---

## Territorio 2 — extrude-holed con profilo off-center — **PLAUSIBILE (priorità)**

**Esempio rotto (verificato):** un frame quadrato **off-center** forato su FIX `(f10 th89 f20)` →
**tri-tri = 149**. Lo stesso contorno **senza foro** (cammino direzionale) → **0**. Il cammino con
fori usa ancora il proxy **scalare** (centroide), non la proiezione direzionale: per un profilo
`:centered? false` lo stamp mette il primo punto sul rail → il centroide esce dal rail → un
overhang che lo scalare sotto-mitra → piega. La guardia scalare scatta sui casi grossi (V3: frame
outer-14/f12 rifiutato) ma **manca** questo.

**Larghezza della zona rotta:** **non è una fascia sottile.** L'overhang nel frame di stamp =
larghezza piena del profilo (il primo punto va sul rail), indipendente da quanto è "off-center"
l'autore (misurato cx=4,6,8,10 → tutti 149). Quindi **qualunque** profilo forato `:centered? false`
su un corner stretto si ripiega. `:centered? false` è il **default** di `make-shape` e dei profili
da `path-2d` → raggiungibile con profili realistici (un profilo forato derivato da path su un
corner). I profili forati **centrati** sono sani (scalare = reach).

**Giudizio: PLAUSIBILE → follow-up prioritario.** La cura è nota e a basso rischio: trapiantare
`analyze-open-path-dir` (la proiezione direzionale, wall-aware) anche su
`extrude-with-holes-from-path`; il loft-holed è già sul cammino direzionale come modello. Non è
cosmetico: è il difetto pre-fix del proxy ancora vivo sul cammino con fori.

---

## Territorio 3 — `:centered? false` corner — **EX-ROTTO, ORA CHIUSO** ✓

**Esempio di controllo (verificato):** `(extrude (make-shape [...] {:centered? false}) (f40)(th89)(f40))`
→ **tri-tri = 0** (extrude **e** loft). Prima del fix si auto-intersecava; era lo **stesso difetto
del proxy** (stamp sposta il centroide fuori dal rail → overhang sotto-mitrato dal vecchio proxy
centroide-max). Il fix direzionale lo misura nel frame di stamp e lo costruisce pulito. **Quel
"secondo meccanismo separato" non esisteva**: la famiglia è chiusa, non è più un territorio
pericoloso.

---

## Territori quarti emersi

### Sospetto A — corner compositi (th+tv) estremi — **BORDO/minore**
Misurato (rect 20×6 su f15, th=tv variabile): 20°→50° = **0**; ≥60° = **tri-tri 5**. Solo i
compositi **estremi** (≥60° in DUE piani insieme, gambe corte) lasciano un residuo minimo, analogo
alla fascia di bordo del T1. Gentle/moderati puliti. **Giudizio: curiosità di bordo**, non priorità.
Esempio concreto nel file.

### Sospetto B — extrude-closed (rail chiusi ad anello) — **PLAUSIBILE, feature incompleta**
`extrude-closed-from-path` **non chiama** `validate-corner-realizability!` e usa il proxy scalare:
doppiamente non protetto. Misurato (quadrato chiuso lato L, circle r):
`circ8/L20` (comodo) → **10**; `circ8/L10` → **300**; `circ12/L10` → **724**. Anche un quadrato
**comodo** si ripiega (10) → il problema non è solo la guardia assente ma l'assemblaggio del loop/
cucitura. **Giudizio: plausibile** (il tubo chiuso ad angoli è una forma comune) ma è
**completamento di feature** (extrude-closed era già fuori scope per i fix), non un raffinamento del
proxy. Brief a sé. Esempio concreto nel file.

---

## Chiusura (richiesta dal brief)

- **T1 shell al bordo**: rotto verificato (≈34 a eff 0.07); zona = gambe entro **~0.1** dal miter
  esatto → **patologico-al-bordo** (un utente non ci arriva).
- **T2 extrude-holed off-center**: rotto verificato (**149**, vs 0 senza foro); zona = **qualunque**
  profilo forato `:centered? false` su corner stretto (default, non sottile) → **plausibile**,
  follow-up prioritario (trapianto direzionale sul cammino con fori).
- **T3 `:centered? false`**: **confermato chiuso** (tri-tri=0 extrude+loft) — ex-rotto ora sano.
- **Quarti**: compositi estremi (≥60°×2 → 5, bordo) e extrude-closed (10→724, nessuna guardia,
  feature incompleta) — entrambi casi concreti nel file.

**Riga di sintesi:** dei territori aperti, **solo extrude-holed off-center merita un follow-up
prioritario** (plausibile + raggiungibile coi default + cura nota); **extrude-closed** è un secondo
fronte ma di livello-feature; **shell-al-bordo e compositi-estremi sono curiosità di bordo**
(fascia ~0.1 / angoli ≥60° in due piani) che un utente non incontra per caso. Razionale della
distinzione: *raggiungibilità con profili/corner realistici* — T2 ci si arriva coi default, T1/A
solo spingendosi sul filo del bordo di realizzabilità.

Fuori scope (invariati): nessun fix; miter-join archiviato; A1/tag smooth arc, Classe B planarità
embroid, bezier 3D nel rail distorto. La cura di T2 (trapianto direzionale su extrude-holed) e di
extrude-closed sono follow-up separati, ciascuno col suo brief, informati da questa mappa.
