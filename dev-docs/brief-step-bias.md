# Brief: bias ε sui candidati STEP + heal-slivers

> **Revisione 2026-07-15 (sera)** — aggiunta la **Parte 3** (indicatore sliver nel
> pannello + bottoni ±ε in edit-mesh-split) e ridefinito l'ordine di importanza:
> **3.1 (indicatore) → 3.2 (bottoni) → 1 (auto-bias) → 2 (heal-slivers)**.
> L'indicatore è il prerequisito: lo sliver è invisibile nella preview, e senza
> feedback qualunque correzione (automatica o manuale) non è verificabile in fase
> di taglio. Se in verifica il segno automatico della Parte 1 si rivelasse
> inaffidabile, la Parte 1 può degradare a "candidato flush + indicatore acceso":
> la coppia 3+2 basta. Le Parti 1 e 2 sono invariate nel contenuto.

## Contesto

Caso reale (2026-07-15, STL di Vincenzo, `mount`): il candidato STEP suggerito da
`cut-candidates` produceva uno split che "rubava" un foglio sottile e irregolare da
`:piece-1` attribuendolo a `:piece-2`. Offset corretto a mano: `-1.62` contro un
suggerimento leggermente più alto. La differenza di materiale è di pochi nm.

Diagnosi — il problema non è la precisione, è la posizione del piano:

- Le MeshGL di Manifold sono **float32** per costruzione: una faccia "piatta" è
  increspata di qualche nm (rumore fp32 accumulato — atteso, non un bug).
- `step-pose`/`step-candidates` (`geometry/cut_candidates.cljs:56-158`) piazzano il
  piano **esattamente flush** con le facce dello scalino: per il centroide del
  cluster, con la normale media. Distanza zero dalla geometria → il piano taglia
  *dentro* l'increspatura: chi sta un nm sopra finisce di qua, chi un nm sotto di
  là → sliver irregolare. Nessun aumento di precisione può salvare un piano a
  distanza zero; il fix aumentando la tolleranza è già stato tentato senza successo.

Fix a due punte, indipendenti e complementari: il bias corregge i **suggerimenti**,
heal-slivers protegge **ogni** split (anche i tagli piazzati a mano).

## Parte 1 — Bias ε sui candidati STEP

Il candidato STEP non deve essere flush-esatto: la pose va spostata di ε lungo la
normale snappata, **dentro il lato dove il materiale continua** (il bulk), così
l'intera faccia increspata cade strettamente da un lato e il piano attraversa
solido pulito. È esattamente l'aggiustamento fatto a mano nel caso reale.

- **Segno**: ricavabile dal ΔA del cluster — il materiale continua dal lato con la
  sezione maggiore (il segno di `:sum` dice se l'area entra o esce al passaggio del
  piano lungo `+heading`). Da verificare sul caso reale, non solo dedotto.
- **Scala di ε**: ben sopra il rumore fp32 (~nm), ben sotto qualunque feature reale.
  Default proposto: `max(1e-3 mm, 1e-4 × diagonale-bbox)`. ε deve sopravvivere alla
  quantizzazione fp32 alla magnitudine delle coordinate del pezzo (ulp32 a ~2 mm ≈
  0.12 nm: margine enorme, ma la formula resta scale-aware per pezzi grandi).
- **Configurabile**: opts di `cut-candidates`, chiave `:bias` (default come sopra,
  `0` = comportamento flush attuale, per confronto/debug).
- Punto d'intervento: la costruzione della pose STEP in `cut-candidates`
  (`manifold/core.cljs:1483-1493`) / `step-pose` — spostare `:position` di ε lungo
  la normale del cluster col segno giusto. Poche righe; il lavoro vero è il segno
  giusto e la verifica.
- Solo i STEP: symmetry e reflex tagliano per costruzione dentro il solido, non
  flush con facce coplanari. (Se in verifica emergesse lo stesso sintomo sui
  reflex, la stessa correzione si applica lì.)

## Parte 2 — heal-slivers su mesh-split

Rete di sicurezza post-split, **opt-in** nelle opts di `mesh-split`
(es. `{:heal-slivers true}`, con soglia opzionale `{:heal-slivers {:thickness t}}`).

Meccanica per ciascuna metà del risultato di `split-by-plane`:

1. **Identificazione**: decomposizione in componenti connesse via il Decompose()
   già usato da `mesh-components` (`manifold/core.cljs:757` — union-find
   topologico, esatto, <1 ms fino a ~13k tri).
2. **Classificazione**: una componente è uno sliver se la sua **estensione proiettata
   sulla normale del piano di taglio** è sotto soglia (default dell'ordine dei µm,
   legato allo stesso ε della Parte 1). Il volume da solo NON basta come criterio:
   una soglia di volume mangia pezzi piccoli ma intenzionali (una linguetta staccata
   apposta); lo spessore lungo la normale è la firma specifica del wafer creato dal
   taglio. Il volume può fare da seconda condizione di conferma.
3. **Riassegnazione**: la metà derubata si ricompone concatenando le componenti
   superstiti (disgiunte → niente booleane); lo sliver si fonde nell'altra metà con
   `union` (necessaria: condividono l'interfaccia sul piano; costo ~30-100 ms per
   op, in linea con le misure di fedeltà esistenti — accettabile per un'operazione
   opt-in per-taglio).
4. **Degenerazioni**: metà che resta vuota dopo la rimozione → è il caso "il piano
   sfiora", già legittimo per `split-by-plane` (mesh vuota, non errore). Sliver in
   ENTRAMBE le metà → si processa ciascuna verso l'altra, una passata sola, niente
   ping-pong.

`edit-mesh-split` può attivare heal-slivers di default sui propri split interni
(l'editor è il contesto dove lo sliver si è manifestato); la funzione DSL resta
opt-in per non cambiare il contratto esistente.

## Parte 3 — Feedback e controllo in edit-mesh-split

Decisione di Vincenzo (2026-07-15): l'ε deve poterlo scegliere l'utente in fase di
taglio. Ma il controllo manuale è cieco senza feedback — lo sliver (nm/µm) è
invisibile nella preview del viewport; nel caso reale è stato scoperto solo a
posteriori, sul risultato. Da qui i due pezzi, nell'ordine:

### 3.1 — Indicatore sliver nel pannello (prerequisito)

Al piano corrente, per ciascuna delle due metà dello split live: decomposizione in
componenti connesse e check di spessore lungo la normale del taglio (stesso criterio
della Parte 2). Il pannello mostra il conteggio componenti delle due metà con un
warning quando una componente è sotto soglia — es. `2|1 ⚠`.

- La macchina c'è già tutta: lo split live tiene il Manifold vivo
  (`split-live`, `edit_mesh_split.cljs:252`) e il Decompose() di `mesh-components`
  costa <1 ms — sostenibile a ogni nudge del piano.
- Il warning deve dire da che parte sta lo sliver (quale metà), così l'utente sa
  in che direzione correggere.

### 3.2 — Bottoni ±ε (micro-nudge)

Due bottoni nel pannello che spostano il piano di ±ε lungo la sua normale — la
stessa scala di ε della Parte 1 (`max(1e-3 mm, 1e-4 × diagonale-bbox)`), ordini di
grandezza sotto i nudge esistenti (le frecce lavorano a scala di feature). Flusso:
il warning 3.1 si accende → ±ε finché si spegne.

- L'offset risultante è un offset come un altro: l'emissione lo incorpora
  (round-trip invariato, stesso requisito già in Verifica).
- I bottoni valgono per qualunque taglio (candidato o piazzato a mano), non solo
  per i STEP.

Con la Parte 3 in piedi, l'auto-bias della Parte 1 resta il default che rende i
suggerimenti funzionanti senza pensarci; i bottoni sono la correzione quando il
segno sbaglia, l'indicatore è ciò che rende entrambi verificabili.

## Documentazione da aggiornare

- `docs/manual/reference/en/cut-candidates.md` — opzione `:bias` e la semantica
  "il piano suggerito è ε dentro il bulk, non flush".
- `docs/manual/reference/en/mesh-split.md` — opzione `:heal-slivers` (criterio di
  classificazione incluso: spessore lungo la normale, non volume).
- `dev-docs/brief-cut-candidates.md` — nota in testa che rimanda qui per il bias.

## Verifica

- **Sul caso reale**: lo STL di `mount` con il candidato STEP incriminato. Criterio:
  zero componenti sotto-soglia in entrambe le metà **al suggerimento nudo, senza
  ritocchi a mano**; il pezzo staccato combacia con quello ottenuto con l'offset
  manuale `-1.62` (fedeltà ~100% via `mesh-board`).
- Bias col segno giusto su scalini in entrambe le direzioni (ΔA positivo e negativo).
- `:bias 0` riproduce il comportamento attuale (regressione controllata).
- heal-slivers: NON tocca un pezzo piccolo ma spesso (linguetta intenzionale);
  RIMUOVE il wafer da µm; metà svuotata → mesh vuota legittima.
- Round-trip: l'offset emesso incorpora il bias (il codice emesso ricrea lo stesso
  split senza ri-applicare correzioni).
- Indicatore (3.1): warning acceso sul caso reale al candidato flush, spento dopo
  ±ε; nessun falso positivo su un pezzo piccolo ma spesso; nessun lag percepibile
  durante il nudge del piano.
- Bottoni (3.2): un click sposta di esattamente ε; l'offset emesso riflette i click
  applicati.
