# Acquisizione parametrica interattiva — documento di design

Origine: conversazione Vincenzo/Claude 2026-07-16, a valle degli esperimenti di
fotogrammetria (brief-scanner-channel.md, Fase 0). **Priorità dichiarata da
Vincenzo: questo canale viene prima delle Fasi 3-4 del canale scanner.**
Questo è un documento di design, non un brief: serve a discutere; il brief
viene dopo gli accertamenti in coda.

> **Revisione 2026-07-16 (notte)**: il flusso principale proposto è la
> **registrazione per proxy** (idea di Vincenzo): l'utente allinea una
> primitiva Ridley sulla foto con gli strumenti esistenti (edit-attach), e
> l'allineamento stesso risolve pose e registrazione. Il tracciamento
> edge-first della prima stesura è retrocesso a raffinamento e dettaglio.

## Il ribaltamento

La fotogrammetria densa ricostruisce *qualunque* superficie da molte foto,
automaticamente, e restituisce una zuppa di triangoli: per Ridley una
falsariga da smontare e ricostruire a mano (guida 18). Ma gli oggetti che
Ridley acquisisce il 90% delle volte sono manufatti: piani, spigoli dritti,
prismi, solidi di rivoluzione. Per questi il problema difficile della
fotogrammetria (matching automatico di milioni di feature) si può *togliere*
invece che risolvere: **l'utente traccia le corrispondenze** (quattro spigoli
su due foto valgono più di centomila feature) e dichiara le primitive; la
matematica che resta è piccola e robusta.

Il ribaltamento vero però è sul risultato: **non una mesh — geometria
parametrica**, cioè quasi direttamente un programma Ridley. Il contorno
tracciato È una `shape`; l'asse indicato È un `revolve`; la quota di calibro
chiude la scala. Il flusso della guida 18 (scan → mesh → falsariga →
ricostruzione nativa) viene cortocircuitato: dalla foto al sorgente.

Pedigree: Façade (Debevec 1996 — edifici da una manciata di foto con spigoli
tracciati), PhotoModeler, SketchUp Photo Match, single-view metrology.

## Cosa esiste già in Ridley

- **`edit-image-board`**: foto di riferimento stampata nel viewport su un rect
  preserve-position, con **calibrazione a righello** (due estremi trascinabili
  + lunghezza reale + bottone "set scale" → `scale` risolto) e parametri
  editabili. È la vista singola *ortografica*: perfetta per ricalcare un
  profilo frontale in scala. ⚠️ La prima stesura diceva "shift+click su due
  punti": **è sbagliato** — il righello è pre-seminato e si trascina, e la
  calibrazione è un bottone esplicito, non automatica (accertamento 1).
  Nota anche che la calibrazione **non viene emessa nel sorgente**.
- **`edit-path-2d`**: il ricalco sopra l'image-board — il tracciamento c'è.
- La famiglia degli editor modali (modal-evaluator, gizmo, pannelli) e il
  contratto di round-trip: la sessione emette sorgente canonico rieditabile.
- Il viewport per la guida 3D (pose camera, indicazioni "scatta da qui") —
  stesso organo della guida di copertura pensata per il canale denso (4.2).

Il salto da costruire è uno solo: dal "foto come piano di riferimento in
scala" al "foto come **vista prospettica** di una scena 3D" — camera con posa
e intrinseche, e vincoli multi-vista.

## Principi tecnici

**Modello camera.** Ogni foto è una camera prospettica: intrinseche (focale —
dall'EXIF per iPhone; stimabile dai vanishing point altrimenti) + posa
(posizione/orientamento). Tre fonti di posa, in ordine di disponibilità:

1. **Vanishing point** da spigoli paralleli tracciati: per oggetti prismatici
   una singola foto ben tracciata orienta la camera rispetto all'oggetto
   (single-view metrology: con una quota nota si misurano le altre).
2. **PnP** su corrispondenze note: dalla seconda foto in poi, le entità già
   ricostruite ancorano la nuova camera.
3. **ARKit** (companion app iOS, visione 4.3 del canale scanner): ogni foto
   arriva **con la posa già calcolata** dal tracking AR — la stima di posa
   sparisce e resta solo la triangolazione. È l'argomento tecnico più forte a
   favore della companion app.

**Vincoli dall'utente.** Un tratto su una foto è un vincolo geometrico: uno
spigolo tracciato = piano passante per il centro camera; lo stesso spigolo in
due viste = retta 3D (intersezione dei due piani). Un contorno chiuso su una
faccia dichiarata piana = shape su quel piano. Un profilo + un asse = solido
di rivoluzione. Una quota nota = scala (il righello di edit-image-board,
promosso al 3D).

**Solver.** Minimi quadrati non lineari su pochi parametri (pose + parametri
delle primitive). Piccolo abbastanza da vivere ovunque (cljs incluso); da
valutare se il backend Rust convenga per riuso futuro.

**Guida.** Il solver conosce i propri gradi di libertà scoperti: "la
profondità di questo spigolo è libera — serve una vista laterale, circa da
qui" (freccia nel viewport). La guida non è euristica: è il rango del sistema.

## Flusso utente: registrazione per proxy (proposta principale)

L'osservazione chiave (Vincenzo): allineare a occhio una primitiva 3D sulla
foto È risolvere la posa. Il proxy allineato è due cose insieme: l'**ipotesi
di primitiva** ("questo oggetto è un box di circa n") e il **target di
registrazione** che aggancia le foto fra loro — quando lo stesso proxy
combacia in due viste, la posa relativa delle due camere cade fuori gratis.

1. **Foto 1** come image-board. L'utente piazza un proxy con gli strumenti
   esistenti — `(edit-attach (box n))` con n stimato — finché il wireframe
   proiettato combacia con l'oggetto. Ambiguità monoculare (più grande e più
   lontano proietta identico): irriducibile e innocua, si fissa una
   convenzione e si prosegue.
2. **Foto 2** (angolo diverso): il proxy ormai è fisso nel mondo — l'utente
   muove solo la **camera** finché il proxy torna a combaciare. È PnP fatto
   a mano; rompe l'ambiguità della foto 1. Ogni foto successiva costa un
   allineamento di camera.
3. **Edge-snap** (il numerico rifinisce il manuale): l'occhio arriva a
   qualche pixel; il solver aggiusta pose e parametri cercando i gradienti
   dell'immagine dove il wireframe *dovrebbe* cadere. Manuale per il grosso,
   minimi quadrati per la finezza — e l'inizializzazione del solve non
   lineare, il punto classicamente fragile, è regalata dall'utente.
4. **Dettaglio su viste calibrate**: fori, scassi, gradini si aggiungono
   tracciando sulle foto ormai registrate (edit-path-2d sopra una vista
   calibrata), dove ogni tratto è un vincolo 3D ben posto. Il tracciamento
   della prima stesura di questo documento vive qui: raffinamento, non
   prerequisito.
5. Quota di calibro su un'entità → scala assoluta (l'n stimato del proxy è
   un input di comodo; la misura vera è un *output*).
6. Uscita: **sorgente Ridley** (`shape`/`extrude`/`revolve`/`attach` con
   numeri concreti), round-trip come ogni editor. Le foto restano come
   image-board di riferimento, scartabili quando il pezzo convince
   (la "caduta dell'impalcatura" della guida 18, § 18.6).

Perché convince come v1: tutta l'interazione vive su terreno che Ridley già
possiede (gizmo di edit-attach, image-board, primitive), il tracciamento
smette di essere il prerequisito tedioso, e la guida ("serve una vista da
qui") resta definita dal rango del sistema del solver.

## Input opzionale: giradischi calibrato (Vincenzo, 2026-07-17)

Setup: camera fissa su cavalletto, pezzo su piatto rotante stampato
(MakerWorld), **angolo di rotazione noto tra gli scatti**. Tre livelli, a
valore crescente:

1. **Angoli noti, piatto muto**: le pose di N foto collassano da 6·N incognite
   a un solo asse condiviso (4-5 parametri) + gli angoli noti. È il setup
   classico della turntable photogrammetry (Fitzgibbon & Zisserman '98):
   solver più piccolo e molto più robusto. Rispetto ad ARKit: niente deriva,
   angoli ripetibili. **Usabile già nel prototipo dell'accertamento: basta
   annotare θ per ogni scatto.**
2. **Il piatto come target**: essendo stampato, la sua geometria è nota
   esattamente (diametro incluso). Segnare il bordo in foto — o allinearci un
   proxy-cilindro — dà l'asse E la scala metrica gratis: il calibro diventa
   verifica, non input.
3. **Marker stampati sul piatto**: corona di tacche/marker ad alto contrasto
   (stile ArUco) sul bordo → rilevamento automatico → posa camera e angolo
   letti dall'immagine, niente allineamento manuale delle camere; resta solo
   il proxy sull'oggetto. È l'alternativa ad ARKit con scala esatta. Il
   piatto marcato lo può **generare Ridley stesso** (STL con marker in
   rilievo/incisi): il rig di acquisizione disegnato dal software che
   alimenta.

Vincoli del setup: cavalletto e tavolo immobili per tutta la sessione (l'asse
è assunto fisso in camera frame); pezzo solidale al piatto; sfondo neutro
preferibile (per l'edge-snap conta meno che per la fotogrammetria densa, ma
i contorni si trovano più puliti).

**Elevazioni multiple** (domanda di Vincenzo: serve un braccio verticale a
posizioni note?): serve la varietà verticale, non la sua calibrazione. Ai
livelli 1-2 un cambio di elevazione costa **un solo allineamento manuale**
(proxy e asse sono già ancorati dal primo anello; dentro il nuovo anello i θ
noti riparametrizzano tutto); al livello 3 qualunque foto che veda i marker
si auto-posa, e ogni meccanica calibrata oltre al piatto diventa superflua.
Default pratico per pezzi prismatici: un solo anello con camera inclinata in
giù di 20-30° (vede fianchi e sopra); secondo anello solo per sottosquadri.

## Rapporto con l'esistente

- **Canale scanner (brief-scanner-channel.md)**: complementare, non
  concorrente — fotogrammetria densa per l'organico/freeform, acquisizione
  parametrica per il meccanico. Riprioritizzazione concordata: la Fase 1
  (import OBJ) resta in coda a costo basso perché serve al canale denso già
  funzionante via KIRI/PhotoCatch; le Fasi 3 (remesh) e 4 (ricostruzione
  locale) scalano *dietro* questo design.
- **Companion app iOS**: da "visione nel radar" a tassello tecnicamente
  motivato (pose ARKit gratis). Il design dell'input deve accettare fin da
  subito "foto + posa opzionale".
- **mesh-board**: resta lo strumento di verifica — il pezzo parametrico
  acquisito si confronta con una eventuale scansione densa dello stesso
  oggetto (fedeltà come collaudo dell'acquisizione).

## Alternative considerate

- **Solo fotogrammetria densa** (canale scanner): per il meccanico produce
  falsarighe rumorose da ricostruire comunque a mano — il lavoro utente c'è
  lo stesso, ma speso dopo, sul risultato peggiore.
- **ML end-to-end** (foto → mesh in un colpo): opaco, non parametrico,
  nessun round-trip — contrario alla filosofia "lo stato è il sorgente".
- **Auto-segmentazione** (SAM o simili) per proporre contorni che l'utente
  conferma: non alternativa ma possibile assist futuro del tracciamento;
  non è un requisito del v1.

## Incognite da accertare

> **Stato 2026-07-18** (dettagli in coda, § "Esiti degli accertamenti"):
> 1 ✅ · 2 ⚠️ metà (sintetico sì, foto vere no) · 3 ✅ · 4 ⬜ non affrontata ·
> 5 ⚠️ metà (bacino sì, robustezza gradiente no) · 6 ✅ (esito: più grosso
> del previsto) · 7 ⬜ non affrontata · 8 ✅ cljs.

1. **Cosa fa esattamente edit-image-board/edit-path-2d oggi** e quanto del
   loro scaffold (pannello, righello, marker nel buffer) si promuove a
   sessione multi-foto (accertamento sul codice, piccolo).
2. **Matematica di v1**: bastano vanishing point + PnP + triangolazione di
   rette? Prototipo offline sul caso reale: 2-3 foto del lettore SD, spigoli
   estratti a mano, solver in un notebook/script → misure confrontate col
   calibro. Se l'errore è da decimi di millimetro, v1 è fondato.
3. **Intrinseche**: EXIF degli iPhone basta? Distorsione di lente:
   trascurabile a centro fotogramma o serve correzione? Protocollo per il
   prototipo: **una sola focale fissa per sessione** (lente fisica — 1x o
   tele 2x/3x, mai zoom digitale intermedio, mai ultra-wide), AE/AF lock,
   foto ferme (niente video: frame compressi e mossi, e il flusso vuole
   poche viste deliberate, non migliaia di frame).
4. **Vocabolario primitive del v1**: proposta minima — prisma/estrusione su
   faccia piana + solido di rivoluzione. Box come caso dell'estrusione.
5. **Edge-snap**: quanto è robusto il fit locale sui gradienti dell'immagine
   con l'inizializzazione manuale? (È il gemello del punto 2, sul lato
   immagine: da prototipare insieme.)
6. **UX dell'allineamento**: nella foto 2+ l'utente muove la camera, non il
   proxy — serve che la manipolazione camera-contro-sfondo-fisso sia fluida
   nel viewport (oggi la camera orbita attorno alla scena; qui l'image-board
   deve restare incollata allo schermo). Da accertare sul codice viewport.
7. **UX del tracciamento di dettaglio** (fase 4 del flusso): mano libera con
   snapping o punti e segmenti? (Il precedente è edit-path-2d.)
8. **Dove vive il solver** (cljs vs Rust) — dipende da 2 e 5.

## Percorso proposto

Accertamento 1 (codice: image-board/path-2d e viewport per il punto 6) e
2+5 (prototipo matematico: proxy allineato a mano su 2-3 foto del lettore SD
+ edge-snap, misure confrontate col calibro; se le foto arrivano dal
giradischi, annotare θ per scatto e usare il livello 1 dell'input opzionale)
→ discussione su questo documento → brief v1 con le parti scelte. Il prototipo resta il gate: decide
se la matematica del v1 sta in piedi prima di toccare l'UX.

---

# Esiti degli accertamenti (2026-07-18)

> **Stato del gate: superato per metà, e la metà mancante è quella che
> conta.** Il prototipo matematico esiste, gira ed è misurato: su dati
> sintetici la matematica del v1 sta in piedi con un ordine di grandezza di
> margine. Ma il gate *come lo formula il documento* — "misure confrontate
> col calibro" — **non è chiuso**, perché non esiste ancora una foto reale
> del lettore SD. E c'è una ragione tecnica precisa per aspettarsi che
> l'errore reale sia dominato da un termine che il sintetico non può vedere
> (§ "Cosa resta aperto"). Nessun "fixato" su questo punto finché non ci
> sono le foto.

## Il prototipo

Codice puro, senza dipendenze, **nessun binding SCI**: non è ancora API, è
un banco di prova.

| file | cosa |
|------|------|
| `src/ridley/photogrammetry/linalg.cljs` | solve gaussiano + Jacobi simmetrico (serve lo spettro di JᵀJ) |
| `camera.cljs` | pinhole, posa Rodrigues, distorsione radiale k1/k2, `look-at-pose` |
| `lm.cljs` | Levenberg-Marquardt, jacobiano numerico, damping alla Marquardt |
| `box_fit.cljs` | il fit del proxy box a pose libere |
| `turntable_fit.cljs` | la variante col giradischi calibrato |
| `synth.cljs` | scene sintetiche, RNG seminato |

Test: `test/ridley/photogrammetry/` — `box_fit_test` (correttezza),
`accuracy_study_test` (esperimenti 1-6), `turntable_study_test` (7-8). Tutte
le tabelle si rigenerano con `node out/test.js`.

**Perché sintetico e non subito reale.** Su una foto vera l'errore misurato
è la somma di solver + lente + tracciamento + calibro, e non si può sapere
chi domina. Sul sintetico la verità è nota esattamente, quindi l'errore
misurato **è** quello del solver, e si può far variare una condizione alla
volta. Servono entrambi; questo viene prima perché può falsificare
l'approccio prima che si scatti una foto.

**Due scelte di modello portano quasi tutto il peso**, e vanno segnate
perché una versione ingenua le sbaglierebbe entrambe:

1. **Uno spigolo vincola solo la sua perpendicolare.** Ciò che un edge
   detector (o una mano che ricalca) localizza è la *retta* su cui lo
   spigolo giace, non dove lungo quella retta stiano i suoi estremi: è il
   problema dell'apertura. Il residuo è quindi distanza punto-**retta**, mai
   punto-punto. Modellarlo punto-punto inventerebbe informazione che
   l'immagine non contiene, e restituirebbe un errore stimato ottimistico.
2. **La scala è una libertà di gauge, non un errore.** Scalare box e tutte
   le traslazioni camera dello stesso fattore riproietta identico: JᵀJ è
   singolare di **esattamente una** dimensione, comunque tante foto si
   scattino. Verificato numericamente (test `scale-is-a-gauge-freedom`: 1
   autovalore nullo senza calibro, 0 con). **Nessuna quantità di foto cura
   quella direzione** — solo il calibro. Il punto 5 del flusso non è una
   comodità: è una necessità matematica.

## Accertamento 2+5 — la matematica regge (su sintetico)

Scenario: lettore SD ~60×25×10 mm, iPhone 4032×3024 (FOV 69.4°, f≈2900 px),
a 250 mm. Il calibro fissa **una** quota (larghezza, σ 0.02 mm); l'errore
riportato è sulle **altre due**, che è ciò che la fotogrammetria deve
consegnare davvero. 15 trial per riga.

**Rumore di localizzazione degli spigoli** (3 viste):

| σ px | RMS err | max err | reproiez. |
|------|---------|---------|-----------|
| 0.1  | 0.010 mm | 0.032 mm | 0.08 px |
| 0.3  | 0.030 mm | 0.096 mm | 0.24 px |
| 1.0  | 0.098 mm | 0.317 mm | 0.80 px |
| 3.0  | 0.291 mm | 0.925 mm | 2.39 px |
| 10.0 | 0.928 mm | 2.762 mm | 7.90 px |

L'errore scala **linearmente** col rumore (10× rumore → 9.8× errore). Il
decimo di millimetro che il documento chiede si ottiene fino a ~1 px di
precisione sugli spigoli; con edge-snap sub-pixel si scende ai centesimi.
Anche un ricalco a mano grossolano (3 px) resta sotto il terzo di
millimetro. **C'è margine abbondante.**

**Configurazione delle viste** (σ 0.3 px):

| config | RMS err | cond(JᵀJ) |
|--------|---------|-----------|
| 1 vista | 0.066 mm | 3.1e6 |
| 2 viste, 15° | 0.046 mm | 2.1e6 |
| 2 viste, 30° | 0.042 mm | 1.5e6 |
| 2 viste, 60° | 0.040 mm | 1.3e6 |
| 2 viste, 90° | 0.043 mm | 1.8e6 |
| 3 viste, sparse | 0.030 mm | 1.8e6 |
| 3 viste, tutte frontali | **475 mm** | **7.7e20** |

Due letture. Primo: **il rendimento decrescente arriva presto** — oltre i
30° di baseline non si guadagna quasi nulla, e la terza vista vale più
dell'allargare la seconda. Secondo: **la configurazione degenere si
autodenuncia nel condizionamento**, 14 ordini di grandezza sopra le altre.
La "guida come rango del sistema" non è un'aspirazione: è già leggibile in
questo spettro, e il segnale è enorme.

Attenzione a un tranello che il prototipo ha mostrato: le viste degeneri
**convergono lo stesso, 15/15**. "Converged" non significa "giusto". Il
segnale da mostrare all'utente è il condizionamento, non la convergenza.

## Accertamento 3 — l'EXIF basta; la distorsione dipende dall'inquadratura

**Focale sbagliata** (3 viste, σ 0.3 px):

| errore focale | RMS quote | errore distanza camera |
|---------------|-----------|------------------------|
| 0%   | 0.030 mm | −0.00% |
| 0.5% | 0.030 mm | 0.49% |
| 1%   | 0.030 mm | 0.98% |
| 3%   | 0.033 mm | 2.95% |
| 10%  | 0.046 mm | 9.83% |

Il risultato sembrava troppo bello, quindi ho verificato il *meccanismo*
invece di crederci: **l'errore di focale non sparisce, si trasferisce quasi
per intero nella distanza camera ricostruita** (10% di focale → 9.83% di
distanza). Focale e profondità si scambiano; il calibro poi ri-fissa la
scala dell'oggetto e le quote ne escono intatte. Il test asserisce
esplicitamente che quella traccia esista, così un refactor che ignorasse la
focale non passerebbe inosservato.

Conseguenza: **l'EXIF è ampiamente sufficiente** (errore tipico ben sotto
l'1%); persino una focale a occhio andrebbe. Il prezzo si paga sulla posa,
non sulla misura — quindi se un giorno servisse la posa assoluta corretta
(companion app, guida di copertura, giradischi livello 3) la focale torna a
contare.

**Distorsione radiale ignorata.** Prima di chiedere quanto costa, quanto
sposta (k1 = −0.10):

| distanza | oggetto | spostamento max | frazione del frame |
|----------|---------|-----------------|--------------------|
| 250 mm | 647 px | 0.6 px | 16% |
| 150 mm | 1093 px | 3.1 px | 27% |
| 100 mm | 1676 px | 12.1 px | 42% |
| 60 mm  | 2982 px | 81.9 px | 74% |

E quanto costa in quota:

| distanza | k1 | RMS err |
|----------|----|---------|
| 250 mm | 0.00 | 0.030 mm |
| 250 mm | −0.10 | 0.030 mm |
| 100 mm | 0.00 | **0.013 mm** |
| 100 mm | −0.10 | 0.044 mm |

**A 250 mm la distorsione è gratis; a 100 mm costa 3.4×.** Il motivo non è
che a 250 mm sia piccola in assoluto, ma che su una regione piccola e
centrata è quasi una **pura variazione di scala** — cioè indistinguibile da
un errore di focale, che abbiamo appena visto essere assorbito. Quando
l'oggetto riempie il frame la distorsione varia *attraverso* l'oggetto e non
è più assorbibile.

Da cui una raccomandazione controintuitiva ma concreta: **inquadrare da
lontano è più robusto che riempire il frame**, anche se riempire il frame dà
più pixel sull'oggetto. (Si noti però la riga a 100 mm con lente perfetta:
0.013 mm, il miglior risultato della tabella. Il potenziale di inquadrare
stretto c'è — ma si incassa solo correggendo k1.) Il v1 può ignorare la
distorsione **purché documenti lo standoff**; un k1 dall'EXIF o da una
calibrazione una-tantum del telefono la recupera dopo.

## Accertamento 5 — il bacino di convergenza è largo (ma finito)

L'affermazione del documento è che l'allineamento a mano regala
l'inizializzazione, cioè il punto classicamente fragile del solve non
lineare. Misurato (posa perturbata di N°, posizione di 2N mm, quote del 40%):

| init | fit buoni |
|------|-----------|
| 2°   | 15/15 |
| 5°   | 15/15 |
| 10°  | 15/15 |
| 20°  | 15/15 |
| 40°  | 13/15 |
| 60°  | 7/15 |
| 90°  | 5/15 |
| 120° | 1/15 |

**Fino a 20° di disallineamento la convergenza è totale.** Nessuno che stia
*cercando* di allineare un wireframe sbaglia di 20°: il margine è largo
almeno un ordine di grandezza rispetto al necessario. L'affermazione
centrale della UX proposta regge.

(Il test asserisce anche che a 120° **non** converga sempre: un bacino
apparentemente infinito avrebbe significato che la perturbazione non stava
facendo nulla e l'esperimento era vacuo. È lo stesso controllo applicato a
focale e distorsione — ogni risultato "troppo pulito" è stato trattato come
sospetto finché non se n'è visto il meccanismo.)

## Il budget realistico messo insieme

3 viste sparse, edge-snap a 0.5 px, focale sbagliata dell'1%, k1 = −0.02
ignorata, allineamento a mano a 5°, calibro σ 0.02 mm:

> **RMS 0.056 mm · caso peggiore 0.153 mm · reproiezione 0.40 px ·
> 15/15 convergenze**

Un ordine di grandezza sotto il decimo di millimetro che il documento poneva
come soglia di fondatezza. **Su dati sintetici.**

## Accertamento 8 — il solver sta in cljs, senza discussione

21 parametri (3 quote + 6 per vista × 3), ~55 residui, jacobiano numerico: il
fit gira in millisecondi dentro il test node. **Non c'è alcun argomento di
prestazioni per il backend Rust.** Il jacobiano numerico costa 2n valutazioni
per iterazione ed è immune ai bug di derivate scritte a mano, che sono il
classico pozzo di tempo di una bundle adjustment. Se un giorno i parametri
diventassero centinaia (molte viste, molte primitive), la strada è derivate
analitiche + normal equations sparse: un aggiornamento, non una riscrittura.
**Decisione: cljs.**

## Il giradischi calibrato — testato, con una sorpresa

La § "Input opzionale" dice che il livello 1 è "usabile già nel prototipo".
Lo è: `turntable_fit.cljs` implementa la camera fissa + asse condiviso +
angoli noti. L'asse è parametrizzato **senza gauge**: due angoli per la
direzione, due coordinate per il punto dell'asse più vicino all'origine (un
punto come 3-vettore reintrodurrebbe la libertà "asse che scivola su se
stesso" e sporcherebbe proprio l'analisi agli autovalori che serve).

Il collasso dei parametri è quello promesso, e non dipende da N:

| viste | pose libere | giradischi | RMS libere | RMS giradischi | guadagno |
|-------|-------------|------------|------------|----------------|----------|
| 3  | 21 par | 13 par | 0.0337 mm | 0.0155 mm | 2.18× |
| 6  | 39 par | 13 par | 0.0165 mm | 0.0117 mm | 1.41× |
| 12 | 75 par | 13 par | 0.0115 mm | 0.0055 mm | 2.11× |

(stesse identiche osservazioni date ai due solver, così la differenza è il
vincolo e nient'altro.)

**La sorpresa è quanto sia fragile fidarsi degli angoli.** Con gli angoli
presi per veri, un piatto impreciso avvelena il fit — e la soglia è molto
più stretta di quanto un piatto stampato garantisca:

| errore piatto | angoli creduti | angoli come prior | pose libere |
|---------------|----------------|-------------------|-------------|
| 0.00° | 0.0117 mm | 0.0115 mm | 0.0165 mm |
| 0.25° | **0.0276 mm** | 0.0115 mm | 0.0165 mm |
| 0.50° | 0.0532 mm | 0.0115 mm | 0.0165 mm |
| 1.00° | 0.1070 mm | 0.0115 mm | 0.0165 mm |
| 2.00° | 0.2196 mm | 0.0115 mm | 0.0165 mm |
| 5.00° | 0.5981 mm | 0.0115 mm | 0.0165 mm |

**Già a un quarto di grado di errore, credere agli angoli è peggio che
ignorarli** (0.0276 contro 0.0165): il punto di pareggio sta intorno a 0.2°,
che su un piatto da 200 mm è ~0.35 mm di arco. Un piatto stampato con
tacche può starci, ma non è garantito, e — peggio — **il fit non se ne
lamenta**: consegna un numero preciso e sbagliato.

La cura è non trattare gli angoli come verità ma come **priori**: ogni vista
prende una correzione libera, penalizzata di quanto si allontana
dall'angolo dichiarato (σ 1°). Costa N parametri in più (19 invece di 13 a
6 viste) e **domina tutto il resto in ogni condizione provata**, incluso il
caso di piatto perfetto.

Anche qui la colonna piatta è stata verificata invece che creduta: a 5° di
errore iniettato, la correzione recuperata lascia **0.022° di errore
differenziale** (vista-contro-vista). Il resto — 1.5° di residuo *totale* —
è modo comune, cioè gauge: ruotare tutte le viste della stessa quantità
equivale a ruotare la camera fissa, che è già un parametro libero. Solo la
parte differenziale è osservabile, e quella il solver la azzera.

**Deciso (Vincenzo/Claude, 2026-07-18): se il giradischi entra, gli angoli
entrano come priori, mai come input rigido.** Il livello 2 (piatto come target) e il
livello 3 (marker) restano più interessanti per la *scala* e per
l'eliminazione dell'allineamento manuale, non per la precisione angolare —
quella la si ottiene già lasciando che sia il solver a rifinire θ.

## Accertamento 1 — lo scaffold image-board / path-2d

- `edit-image-board` (`src/ridley/editor/edit_image_board.cljs`, 568 righe):
  **l'editor non renderizza la foto** — disegna solo overlay. La foto compare
  perché `request!` restituisce una `shape/image-board` che l'utente ha
  avvolto in `(stamp …)`; la texture entra in scena in un unico punto,
  `create-stamp-image-mesh` (`viewport/core.cljs:974`), UV-mappata dalle
  coordinate 2D della shape (quindi ritagliata dal contorno stampato).
- **Il righello non è shift+click.** Il docstring lo dice, il codice no:
  è pre-seminato orizzontale al centro e si **trascina**; la calibrazione è
  un bottone esplicito (`apply-expected!`). La § "Cosa esiste già in Ridley"
  di questo documento ripete la descrizione sbagliata — **da correggere**
  (riga 39).
- **La calibrazione non viene mai emessa**: il righello è solo di sessione,
  quindi si perde al commit e va rifatta a occhio alla riapertura. Per una
  sessione multi-foto va persistita per-foto.
- **Tre singletonicità da rompere, tutte per costruzione**: `session` è un
  atom con un solo `:path`; `find-marker` fa `.indexOf` sul **primo**
  `"(edit-image-board"` del buffer; `claim-interactive-mode!` ammette **una
  sola sessione modale globale**.
- N foto texturizzate funzionano già oggi a costo zero (N `(stamp
  (image-board …))`), ma manca l'**identità per-foto** in scena: nulla marca
  quale board sia quale, e `set-image-stamp-opacity!` è globale.
- Riusabile as-is: tutto `modal_evaluator` (mutex, pannello, keydown,
  find/splice/strip-head, driver a due fasi, `reeval-script!`), l'API
  preview/label/raycast, `image-board-params` come punto unico di
  default+validazione, la riga di `edit-menu-table`.
- Da promuovere **prima** di crescere (oggi duplicati fra editor):
  `basis`/`world->plane`/`plane->world`, `num-field`/`wire-num!`, i wrapper
  throttle/debounce, `remove-pointer-handlers!`.
- `edit-path-2d` non è un file a sé: è `edit_path.cljs` (2688 righe) con una
  cucitura `:mode` `:2d`/`:3d`. **Non sa nulla dell'image-board** — ci va
  sopra solo via `set-image-stamp-opacity!` + `:on-top`, e traccia
  benissimo anche senza foto.
- Test: `edit-path-2d` ne ha due file; `edit-image-board` **zero**.

## Accertamento 6 — il viewport è il vero lavoro nuovo

Questo è l'accertamento con l'esito peggiore, e va detto chiaro: **il punto 6
è più lontano di quanto il documento supponesse.**

- I controlli sono **`TrackballControls`, non OrbitControls**
  (`viewport/core.cljs:4`). La rotazione è *per definizione* attorno a
  `controls.target`: un free-look attorno al centro camera non è
  esprimibile, e ogni helper di inquadratura del codice è target-centrico.
- **La FOV è hardcoded a 60** (`create-camera`, riga 132); l'unica cosa mai
  aggiornata è `.-aspect`. Per far combaciare una foto serve la focale della
  foto: niente controllo FOV, niente toggle orto/prospettiva nel viewport
  vivo (l'orto esiste solo offscreen in `capture.cljs`).
- **Nulla renderizza dietro il canvas**: il renderer nasce senza
  `alpha: true`, quindi un layer HTML dietro sarebbe invisibile comunque.
  Tutte le foto oggi sono geometria world-space, non sfondi.
- L'inset (`viewport/inset.cljs`) è un buon precedente di "seconda vista con
  camera propria" e il suo scaffolding (canvas/scene/renderer/DOM) è
  riusabile quasi verbatim — ma è deliberatamente uno **slave**:
  `render-instance!` copia incondizionatamente il quaternione della camera
  principale ogni frame, e l'unica libertà per-istanza è `:zoom`.
- Esiste però già la via provata per pilotare la camera a mano:
  `set-camera-pose!` + `set-controls-enabled! false`, che è esattamente
  quello che fa il sistema di animazione.

Tradotto: "camera contro sfondo fisso" richiede un secondo schema di
controllo camera **e** una FOV parametrica **e** una decisione su come la
foto stia incollata allo schermo. Non è un dettaglio di UX da sbrigare in
coda: **è il pezzo di ingegneria più grosso del v1, più grosso del solver.**
Il solver è ~600 righe di matematica pura e testabile a tavolino; questo
tocca l'organo centrale e condiviso dell'applicazione.

### Controproposta: invertire la manipolazione (Claude, 2026-07-18 — da validare)

Il free-look serve solo se a muoversi è la camera. Ma la posa è **relativa**:
si può tenere camera e foto **inchiodate in ogni foto** — lo schema della
foto 1, che funziona già: foto stampata in scena frontale alla camera,
camera bloccata via `set-camera-pose!` + `set-controls-enabled! false` (il
precedente è il sistema di animazione) — e far muovere all'utente **il
proxy**, col gizmo di edit-attach esistente. La sessione interpreta
all'inverso: la posa in cui l'utente porta il proxy per combaciare con la
foto K È (invertita) la posa della camera K rispetto al proxy canonico, che
non cambia mai. Narrazione UX: "ruota il pezzo come lo tenevi in mano".
Con gli angoli-priori del giradischi, l'inizializzazione per foto arriva da
θ e il ritocco manuale è piccolo.

Se regge alla prova d'ergonomia, del lavoro viewport restano: FOV
parametrica (piccola e comunque necessaria), lock camera per sessione,
posizionamento del rect-foto frontale alla camera — tutti con precedenti nel
codice. Il free-look e la "foto incollata allo schermo" escono dal v1.
Da validare con un prototipo d'interazione, non sulla carta: il rischio
residuo è ergonomico (manipolare 6 DOF del proxy contro una foto è
faticoso? il gizmo basta?), non geometrico.

## Cosa resta aperto (e perché è la parte che conta)

**Il gate come lo formula il documento non è chiuso.** Il sintetico misura
l'errore *casuale* del solver. Le foto vere misurerebbero anche quello
*sistematico*, e c'è una ragione precisa per aspettarsi che sia lui a
dominare:

1. **Gli spigoli veri non sono spigoli.** Un pezzo reale ha raggi di
   raccordo e smussi. La silhouette che un edge detector trova su uno
   spigolo raccordato è spostata **sistematicamente**, di una quantità che
   dipende dal raggio e dall'angolo di vista. Su un raggio da 0.3 mm il bias
   è dello stesso ordine dell'**intero** budget d'errore misurato qui — e a
   differenza del rumore **non si media via con più foto**. È il termine che
   il confronto col calibro rivelerebbe e che il sintetico, per costruzione,
   non può vedere.
2. **L'oggetto vero non è un box.** Il fit assume ortogonalità perfetta.
3. **Il rumore vero non è centrato.** Il tracciamento a mano ha bias
   (parallasse, spessore del tratto, tendenza a stare dentro o fuori).
4. **L'edge-snap vero non è stato provato.** Qui è modellato come "una σ in
   pixel"; quanto sia robusto un fit sui gradienti di un'immagine vera, con
   sfondo e riflessi, resta non misurato. L'accertamento 5 è quindi chiuso
   solo per metà: il bacino di convergenza sì, la robustezza del gradiente no.

Il sintetico dice quindi una cosa precisa e limitata, ma utile: **se
l'errore reale risulterà molto peggiore di ~0.1 mm, la causa non sarà la
matematica** — sarà il modello di spigolo, la lente o l'oggetto. È un
restringimento vero dello spazio delle ipotesi, non una conferma.

### Protocollo per chiudere il gate (serve Vincenzo)

1. 3 foto del lettore SD da angoli sparsi (≥30° l'una dall'altra, **da ~250
   mm, non a riempire il frame**), EXIF intatto, focale fissa per tutta la
   sessione, AE/AF lock.
2. Calibro su **tutte e tre** le quote: una entra come vincolo, le altre due
   sono il metro di giudizio.
3. Estrarre a mano gli spigoli visibili su ogni foto — anche solo due punti
   per spigolo: il modello vuole **rette**, non estremi.
4. Passare il tutto a `box-fit/fit`: l'API prende già esattamente queste
   osservazioni.
5. Confrontare. **Sotto ~0.2 mm il v1 è fondato.** Sopra, il sospetto n.1
   sono i raccordi, e la risposta è modellare lo spigolo raccordato (o
   misurare sulle facce invece che sugli spigoli) — non toccare il solver.

Se le foto arrivano dal giradischi, annotare θ per scatto e usare
`turntable-fit/fit` con `:angle-prior-sigma-deg` (non gli angoli rigidi:
vedi sopra).

Fino ad allora la riga onesta è: **la matematica non è più un rischio; il
rischio si è spostato tutto sul viewport (accertamento 6 — ma vedi la
controproposta "manipolazione invertita", che se regge lo ridimensiona a
lavoro ordinario) e sul modello di spigolo reale.**
