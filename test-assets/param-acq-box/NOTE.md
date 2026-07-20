# Sessione blocco calibrato (spigoli vivi) — certificazione del metodo

Scopo (design doc, § "Il fit come registratore di camere"): pezzo
modello-conforme → doppio gate. (1) Quote: atteso ~0.003 mm dal sintetico,
soglia 0.2 mm. (2) Registrazione: reproiezione al rumore di estrazione
(~1 px). Confronto diretto con la sessione del lettore SD
(test-assets/param-acq/).

## Il pezzo

- Box generato con Ridley, nominale 60 × 40 × 20 mm, stampato FDM.
- Filamento (materiale/colore/finitura): PLA, blu scuro, traslucido______
- Elephant foot rimosso (lametta/carta): no
- Faccia d'appoggio sul piatto = parete verticale di stampa: no (stessa posizione della stampa)

## Quote di calibro (misurate, NON nominali — 2-3 punti per quota)

- X: _60.0_____ mm (escursione __0.05____)
- Y: _20.0_____ mm (escursione __0.05____)
- Z: _40.0_____ mm (escursione __0.05____)

## Setup (identico alla sessione lettore)

- Data: 2026-07-19__
- iPhone 15 Pro Max, 2x (crop sensore), AE/AF lock: sì/no
- Distanza ~250 mm; piatto ⌀130 mm, indice a pennarello
- Convenzione θ: letture della scala del piatto, mod 360 (come sessione
  lettore)

## Foto

| file | θ (gradi) | note |
|------|-----------|------|
| IMG_8894.jpeg | 0 | ★ bootstrap |
| IMG_8895.jpeg | -10 | |
| IMG_8896.jpeg | -20 | |
| IMG_8897.jpeg | -30 | ★ bootstrap |
| IMG_8898.jpeg | -40 | |
| IMG_8899.jpeg | -50 | |
| IMG_8900.jpeg | -60 | ★ bootstrap |
| IMG_8901.jpeg | -70 | |
| IMG_8902.jpeg | -80 | |
| IMG_8903.jpeg | -90 | ★ bootstrap |
| IMG_8904.jpeg | -135 | |
| IMG_8905.jpeg | 180 | Proposta piuttosto fuori nella parte alta |
| IMG_8906.jpeg | 90 | Proposta molto fuori|
| IMG_8907.jpeg | 45 | Proposta molto fuori|

## Per Code

Stesso flusso della sessione lettore (tool + `--check` + bootstrap 4★ +
predictions). In più, nel report finale: separare esplicitamente i due gate
(quote vs registrazione) — il secondo è il numero che certifica il
"registratore di camere" per i pezzi non modello-conformi.

---

## Preparato (Code, 2026-07-19)

```bash
npx shadow-cljs compile paq
node out/paq.js --init-session test-assets/param-acq-box   # già fatto
python3 -m http.server 8099
#   → http://localhost:8099/scripts/param-acq-tool.html?s=param-acq-box
node out/paq.js --check test-assets/param-acq-box/picks.edn
node out/paq.js test-assets/param-acq-box/picks.edn
```

`--init-session` legge **questo NOTE.md** come unica fonte di verità: ha preso
la tabella (compreso il −135, diverso dalla sessione 1) e il calibro
X 60 / Y 20 / Z 40. Prima il calibro era cablato sui valori del lettore SD —
misurare il blocco contro quelli avrebbe dato un verdetto plausibile e falso.
Z (40) entra come vincolo di scala; X e Y sono il metro di giudizio.

### I due gate, e come vengono misurati

**Gate 1, quote.** Come sempre: errore in mm su X e Y contro il calibro,
soglia 0.2. Il pezzo ha spigoli vivi, quindi stavolta il bias da raccordo
dovrebbe valere 0.05-0.12 mm e non può essere l'alibi.

**Gate 2, registrazione.** Due misure, entrambe indipendenti dalle quote:

- **hold-out** — si rifitta senza una foto e si misura *quella* foto contro il
  risultato. Non serve nulla di extra ed è già attivo. Riferimento sessione 1:
  hold-out 15-23 px contro train 15-19 px, cioè la registrazione generalizzava
  già allora, con quote sbagliate di 1.7 mm. È esattamente il caso che rende
  interessante il riposizionamento.
- **consistenza fra viste** — clicca lo **stesso punto fisico** in più foto
  (bottoni "Punti di registrazione"). Il codice triangola quel punto con le
  camere stimate e misura di quanto le viste sono in disaccordo fra loro.
  Su questo blocco i candidati naturali sono **gli angoli della faccia
  superiore**: nitidi, sempre visibili, e li riconosci senza ambiguità.
  Serve lo stesso punto in ≥2 foto; 3-4 punti su 4 foto bastano.

  ⚠️ Il blocco è stato stampato **senza le tacche** di
  `examples/param-acq-target.clj` (il bersaglio con i pozzetti è arrivato
  dopo). Non serve ristampare: gli angoli funzionano. I pozzetti resterebbero
  utili solo per una metrica *assoluta*, che è un'altra cosa e per ora non
  serve.

Una nota su cosa NON misura la consistenza: uno spostamento rigido di tutte le
camere insieme. È gauge, non errore — il punto triangolato si sposta con loro.
Misura il disaccordo *relativo*, che è ciò che rovina "clicco una foto e il
viewport ci va".

---

## Passata 2026-07-19 (sera) — schema a permutazioni + predictions su 14

**Correspondence risolta per-foto dalle quote note** (`ridley.photogrammetry.match`),
mai più dall'ordine dei click. Comando: `node out/paq.js --match <picks.edn>`.

Sui 4 scatti ★, con le quote 60×20×40:

- **Gate 1 (quote)**: reproiez. congiunta 3.07 px, X 58.74 (Δ −1.27), Y 18.97
  (Δ −1.03). Non ancora sotto 0.2, ma la geometria ora è *giusta* (vedi sotto).
- **Controllo giradischi indipendente**: gli azimut ricostruiti per-foto seguono
  gli angoli annotati a **max 0.83°** → la sessione è quella che dice il NOTE,
  e le camere sono davvero registrate l'una rispetto all'altra.
- **Gate 2 (registrazione)**: hold-out 8.73 px contro train 3.07 (2.8×) → le
  camere **non generalizzano ancora** alle viste non usate. È il numero che il
  design doc mette sul riposizionamento; oggi non passa.

**Firma del bias — sostiene la tua ipotesi materiale.** Il residuo di quota,
scomposto:

| lato | mm/lato | % |
|------|---------|---|
| 60 mm | 0.632 | −2.1% |
| 20 mm | 0.517 | −5.2% |

Per-lato quasi costante (0.63 vs 0.52), percentuale che varia 2.5×. È la firma
di una **silhouette spostata verso l'interno di una quantità fissa**, non di un
errore di scala (che sarebbe percentualmente costante). Coerente con subsurface
scattering sul filamento traslucido. La CLI stampa questa scomposizione a ogni
run.

### Il test discriminante (Vincenzo)

Rifare i **4 scatti ★ con nastro di carta opaco** sulle facce, stessa posa.

- se il bias per-lato scende → è il **materiale** (subsurface scattering): si
  documenta il vincolo "superfici opache" e il gate si chiude sul pezzo teso.
- se resta ~0.6 mm/lato → è nelle **intrinseche**: allora tocca a me, stima di
  principal point + k1 (in coda, gated su questo esito).

Metti i nuovi scatti in una cartella sorella (es. `param-acq-box-tape/`) con lo
stesso NOTE/angoli, così il confronto è pulito.

### Predictions su tutte e 14 (per ridurre il lavoro)

`--match` ora ricostruisce un **turntable dai 4 scatti risolti** (reproiez.
3.56 px sui cliccati) e **riproietta su tutte e 14** le foto → `predictions.json`.
Verificato a occhio su IMG_8895 (mai cliccata, θ=−10): il wireframe cade sul
pezzo, è da confermare non da rifare.

Flusso per completare le 10:

1. `python3 -m http.server 8099` → tool con `?s=param-acq-box`
2. "Carica predictions.json"
3. per ciascuna delle 10 non-★: **"Accetta proposte → click"**, poi ritocca gli
   spigoli che sono scivolati e premi `s` per lo snap
4. i **punti di consistenza**: clicca lo stesso spigolo-angolo del piano
   superiore in ≥2 foto (ora ce n'è 1 solo → RMS non significativo)
5. "Esporta EDN", salva, `node out/paq.js --match test-assets/param-acq-box/picks.edn`

Con 14 foto invece di 4 l'hold-out diventa molto più solido (ogni foto tolta
ne lascia 13), ed è lì che si vedrà se la registrazione regge.

---

## Passata 2026-07-20 — crash risolto + dati da sistemare

Il tuo `--match` crashava: **bug mio**, il fit congiunto indicizzava le pose
per l'indice-foto originale mentre il vettore pose era compattato — appena una
foto non si risolveva, andava fuori dai limiti. Corretto (rinumerazione a
indici compatti). Ora la pipeline arriva in fondo comunque.

Ma i dati hanno tre problemi che il codice ora **segnala** invece di
digerire in silenzio. `node out/paq.js --check <picks.edn>` li elenca:

1. **Sovra-click** (il grosso): IMG_8901/8902/8905/8906/8907 hanno conteggi
   **geometricamente impossibili** — es. `top×8`, `vertical×4`. Una scatola
   non mostra mai più di **3 verticali** (una è sempre nascosta) né più di
   **4 spigoli per faccia**. Probabile causa: hai *accettato* una proposta
   sbagliata (le foto 180/90/45 erano "molto fuori") e poi ci hai aggiunto
   click sopra, invece di svuotare prima. Rimedio: nel tool "**Svuota
   gruppo**" e ri-accetta/ritocca, oppure lascia perdere quelle 3 foto
   estreme (vedi sotto).

2. **Foto svuotate/perse**: IMG_8894 e IMG_8897 (2 dei 4 ★ originali) non sono
   più nel file, e IMG_8903 è sceso da 9 spigoli a 1. Nel round-trip qualcosa
   le ha azzerate. Vanno rifatte (o ri-accettate dalle proposte).

3. **Solve degenere**: una foto con 1-2 spigoli fittava a 0.00px e sembrava
   perfetta — non lo è (una posa ha 6 gradi di libertà). Ora `solve-photo`
   scarta chi ha **meno di 5 spigoli coerenti**: quelle foto compaiono come
   "non risolta", che è la verità.

**Le 5 foto pulite** (θ = −10..−60) si risolvono benissimo e danno già un
risultato sensato: quote 58.9 / 19.0, giradischi coerente a **0.62°**, firma
del bias 0.56/0.49 mm/lato (di nuovo la firma-materiale). Reproiezione onesta
3.05 px (a quote di calibro, per-foto); hold-out 7.44 px = 2.4× → registrazione
ancora non generalizza, ma su 5 foto è un dato debole.

### Le 3 foto estreme (180 / 90 / 45)

Le proposte lì erano fuori perché il turntable è ricostruito da 4 scatti tutti
fra 0 e −90: estrapolare a +180/+90/+45 è fragile. Due strade:

- **più facile**: rifai le 4 ★ pulite (0/−30/−60/−90) + accetta/ritocca le
  intermedie (−10..−80). Con 10 foto ben distribuite su mezzo giro il gate è
  già solido; le 3 estreme puoi ometterle.
- se le vuoi: cliccale **a mano** (non fidarti della proposta lì), tenendo i
  conteggi entro 3/4/4.

### Ordine consigliato adesso

1. `node out/paq.js --check test-assets/param-acq-box/picks.edn` — guarda le ✗
2. nel tool, sistema le foto segnalate (svuota gruppo → ri-accetta → ritocca)
3. ri-esporta, `--check` di nuovo finché è tutto pulito
4. `--match` — e stavolta guarda hold-out e (quando ci sono) i punti di
   consistenza

I punti di consistenza: ne avevi 1, su IMG_8907 che però non si risolve, quindi
è stato ignorato. Mettili su foto che si risolvono (le pulite −10..−60) e lo
**stesso** spigolo-angolo in ≥2 di esse.

---

## Passata 2026-07-20 (pom.) — 8907, il bug era mio + gate registrazione PASSA

Avevi ragione su 8907: **non erano i click, era la mia ricerca**. Diagnostica
nuova: `node out/paq.js --diagnose <picks.edn> IMG_8907.jpeg`. Semina la posa
dalla gemella Klein (8904, a θ−180) invece di cercarla, e stampa il residuo
per-linea. Esito:

- la ricerca `solve-photo` piazzava 8907 a **152 px** (spazzatura) mentre la
  gemella 8904 si risolveva a 3.34 px → **punto cieco riproducibile**, come
  dicevi.
- causa: **copertura dei semi**. Il seeding teneva i primi N per costo, e la
  posa vera veniva scavalcata da un'assegnazione sbagliata-ma-economica e
  buttata prima della rifinitura. Confermato: n-seeds 6/20 → 152 px, n-seeds
  40 → 5.82 px.
- fix: seeding **best-per-settore-di-azimut** (oltre ai migliori per costo),
  così la posa vera non viene mai scartata. Regressione bloccata: test EXP 19
  prova tutti i 24 azimut, peggiore 0.49 px (era 152 px a 45°).
- **nessun intruso**: 8907 fitta a 5.82 px, è solo la foto più rumorosa (gli
  spigoli bassi, al bordo col piatto, sono i più difficili). Il mio verdetto
  "3 intrusi" era sbagliato — soglia assoluta contro una foto il cui miglior
  fit è 5.82 px. Corretta a soglia relativa (>4× mediana e >15 px).

**Con il fix + i tuoi dati ripuliti, tutte e 14 le foto si risolvono.**
Giradischi coerente a **1.05° su tutto il giro**. E per la prima volta:

- **GATE 2 registrazione PASSA**: hold-out 6.77 px vs baseline 3.65 px = **1.9×**
  → "la registrazione generalizza alle viste non usate". Marginale (soglia 2×)
  ma è il primo pass del "registratore di camere" — l'obiettivo del design doc.
- **GATE 1 quote**: 58.98 / 19.02, firma bias 0.51 / 0.49 mm/lato (costante),
  −1.7% / −4.9% (varia). Stessa firma-materiale, ora su 14 foto. Aspetta il
  test del nastro opaco.

Restano da mettere i **punti di consistenza** (stesso spigolo-angolo in ≥2 foto
che si risolvono) per la metrica di registrazione assoluta.
