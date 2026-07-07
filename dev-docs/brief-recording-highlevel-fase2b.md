# Brief — Recording ad alto livello, Fase 2b: guard rail-start analitico sui comandi alto livello

## Contesto

Prosecuzione di `dev-docs/high-level-recording-accertamento.md` (Fase 1 e 2a atterrate, commit `c4a82d6` + `842f7d8`). Item 2 dell'apertura di Fase 2: il guard rail-start smette di misurare la finestra di micro-comandi tessellati e legge direttamente il primo tratto del path alto livello. È un **refactor a semantica invariata**: stessi verdetti, stessi messaggi d'errore (byte-identici — i test li asseriscono), stessa tolleranza. Cambia solo su quale rappresentazione il verdetto viene calcolato.

**Stato attuale verificato sui sorgenti (2026-07-07):**

- La finestra è `(take-while #(not= :f (:cmd %)) commands)` sui **micro** lowered, calcolata in `extrusion.cljs` a ~1615 (`extrude-with-holes-from-path`), ~1756 (`extrude-from-path`), ~2001 (`extrude-closed-from-path`) e in `loft.cljs` (`loft-from-path`).
- `validate-rail-start-frame!` (extrusion.cljs ~330): separa la coda taggata via `split-leading-cap`, **riduce** le rotazioni effettive attraverso lo stato e misura la divergenza **netta** di heading e up contro `rail-start-frame-tol-deg` (quindi rotazioni che si cancellano passano); poi controlla la finestra di cap: `:veer-deg` per i bezier, esenzione per gli archi (nessun `:veer-deg`).
- `split-leading-cap` serve anche allo stamping del cap (lo stato pre-finestra), che resta su micro finché l'estrusione consuma micro.
- Il `:veer-deg` è calcolato a record time con la formula «angolo tra c1−p0 e l'heading d'ingresso». Post-Fase-1 il c1 alto livello è nel frame d'ingresso del segmento: lo stesso angolo si calcola **senza stato e senza tag**, dalle sole componenti locali — per c1 = [a b c] è `atan2(√(a²+b²), c)`.

**Semantica da preservare al centesimo** (è qui che un refactor "ovvio" può tradire):

1. La riduzione **netta**: `(th 90)(th -90)` in testa passa oggi e deve passare dopo.
2. I comandi inerti nella finestra (`:mark`, `:side-trip` — pose-neutrali) non la chiudono e non contano.
3. Gli archi in testa sono esenti per costruzione; i bezier sono giudicati sul veer analitico; le rotazioni esplicite sulla divergenza netta; il roll (`tr`) ha il suo messaggio distinto.
4. Il guard vive **solo** nei punti di consumo rail dove esiste oggi: dove la finestra è calcolata a fini di solo cap (verificare sito per sito, in particolare `extrude-closed-from-path`), non si aggiunge validazione dove non c'era — neutralità anche in negativo.

**Alternativa considerata e rigettata:** tenere il check sui micro e limitarsi a leggere `:veer-deg` (lo status quo). Funziona, ma mantiene il verdetto ostaggio della tessellazione e dei tag — il debito che questo piano esiste per estinguere; e blocca la Fase 3 su `:veer-deg` per sempre.

## Lavoro richiesto

1. Nuova validazione `validate-rail-start!` (nome a scelta di Code) che opera su `(:commands path)` alto livello, senza lowering e senza stato di consumo:
   - finestra = comandi prima del primo comando che produce movimento (`:f`/`:u`/`:rt`/`:lt` o curva), con `:mark`/`:side-trip` inerti;
   - rotazioni esplicite nella finestra (`:th`/`:tv`/`:tr`/`:set-heading`): ridotte su una posa di lavoro (le forme dei comandi primitivi sono identiche ai micro, `apply-rotation-to-state` è riusabile), divergenza netta di heading e up contro la stessa tolleranza, stessi due messaggi (turn / twist);
   - primo comando curva dopo la finestra: `:bezier-to` → veer analitico dalle componenti locali di `:c1` (formula sopra); `:bezier-as` → idem sul c1 del primo segmento risolto in `:segments`; `:arc-h`/`:arc-v` → esente per costruzione. Stesso messaggio "begin with a turn" al superamento della tolleranza.
2. I quattro punti di consumo sostituiscono la chiamata a `validate-rail-start-frame!` con la nuova validazione su alto livello, **nello stesso punto logico** (prima del lowering, che per il caso invalido non serve nemmeno realizzare); il calcolo della finestra micro e `split-leading-cap` restano dove servono per lo **stamping del cap** — quella parte non si tocca in questo brief.
3. `validate-rail-start-frame!` micro: rimossa se resta senza chiamanti dopo il punto 2 (con i suoi helper ormai orfani, `leading-cap-window` escluso che serve al cap), oppure ridotta a ciò che il cap usa — decidere leggendo, non lasciare codice morto «per sicurezza».
4. `:veer-deg` perde qui il suo ultimo lettore. **Non rimuoverlo dall'emissione** (i golden lo asseriscono; la rimozione è Fase 3): aggiornare la riga di Architecture §6.3.1 — `:veer-deg` senza lettori dal 2026-07, rimozione in Fase 3; `:bez-cap :lead` resta letto da `split-leading-cap` per il cap.

## Verifica

- Rete prima del codice: i test dei punti sotto che catturano il comportamento **attuale** vanno scritti e verdi prima del refactor, poi devono restare verdi identici dopo.
- **Oracolo del refactor**: property test sul corpus golden — per ogni curva che apre un path del corpus, il veer calcolato dalla nuova formula sulle componenti locali di c1 coincide con il `:veer-deg` taggato nei micro (tolleranza 1e-9). Se divergono, la formula non è quella di record time.
- Tutti i test rail-start esistenti (brief rail-start-tangent e bezier-as-rail-lead) verdi **senza modifiche**, messaggi inclusi: i due script storici di riproduzione buildano; `:control` fuori asse bocciato; `(th 30)` a mano bocciato; `(tr 30)` bocciato come twist; arc-started verde.
- Casi di semantica fine, catturati pre-refactor: `(th 90)(th -90)` in testa a un rail dritto passa; `mark` e `side-trip` in testa non alterano il verdetto; il canary di indipendenza dalla risoluzione resta (ora strutturale: il check non tocca mai gli step).
- Neutralità in negativo: `extrude-closed-from-path` (e ogni sito dove la finestra serviva solo al cap) mantiene esattamente il comportamento attuale — nessuna validazione nuova dove non c'era.
- Golden corpus e pinned **intatti** (né lowering né stamping cambiano).
- Suite completa 0 failures; commit proprio, verde.

## Fuori scope

- Resolution di consumo (item 3 della Fase 2) e rimozione dei tag (Fase 3), inclusi `:veer-deg` ora orfano e `:pure`/`:span` già annotati.
- Lo stamping del cap e `split-leading-cap` (restano su micro finché l'estrusione consuma micro).
- Il brief della lettura 2D (`project-2d-to-xy`), gate della Fase 3.
- Messaggi d'errore e tolleranza (invariati per definizione di refactor neutrale).
- La voce code-issues sugli STL importati (orientamento all'import, indipendente da questo piano).
