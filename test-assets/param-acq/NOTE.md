 # Sessione di acquisizione — lettore SD su giradischi

Dati per il gate del prototipo (dev-docs/acquisizione-parametrica-design.md,
§ "Protocollo per chiudere il gate"). Compilare e mettere le foto in questa
cartella.

## Setup

- Data sessione: 2026-07-_19
- Telefono e lente usata: iPhone 15 Pro Max, modalità 2x (verificato da EXIF:
  camera principale 6.765mm con crop-sensore, 48mm eq., DigitalZoomRatio
  assente, 4032×3024 — focale identica su tutte le 14 foto; è un crop ottico
  del sensore, non zoom digitale interpolato: ok per il fit)
- AE/AF lock attivo: sì/no
- Distanza approssimativa camera-oggetto: 250 mm (target ~250)
- Diametro piatto: 130 mm
- Indice angoli: goniometro carta / striscia divisa / altro: pennarello sul piatto______

## Quote di calibro (le tre dimensioni del box)

Misurate sul pezzo fisico, indicando tra quali facce:

- Larghezza: 80 mm
- Profondità: 42.2____ mm
- Altezza: 15 mm

(una entra nel fit come vincolo di scala, le altre due giudicano.)

## Foto

Formato: JPEG con EXIF intatto (AirDrop in "più compatibile", NON WhatsApp/
Mail che ricomprimono e spogliano l'EXIF).

Convenzione θ: letture della scala del piatto (sequenza reale: 0, 350,
340…270, 230, 180, 90, 45), riportate mod 360 — es. 350→-10, 230→-130.
Scala unica, verso unico, pezzo solidale al piatto. Il verso globale è
irrilevante per il solver (assorbito dalla direzione dell'asse).

| file | θ (gradi) | note |
|------|-----------|------|
| IMG_8880.jpeg | 0 | |
| IMG_8881.jpeg | -10 | |
| IMG_8882.jpeg | -20 | |
| IMG_8883.jpeg | -30 | |
| IMG_8884.jpeg | -40 | |
| IMG_8885.jpeg | -50 | |
| IMG_8886.jpeg | -60 | |
| IMG_8887.jpeg | -70 | |
| IMG_8888.jpeg | -80 | |
| IMG_8889.jpeg | -90 | |
| IMG_8890.jpeg | -130 | |
| IMG_8891.jpeg | 180 | |
| IMG_8892.jpeg | 90 | |
| IMG_8893.jpeg | 45 | |

## Per Code

1. Estrazione spigoli: due punti per spigolo visibile per foto (il modello
   vuole RETTE, non estremi). Se serve, costruire il mini click-tool HTML
   (immagine + click coppie di punti + etichetta spigolo → EDN) invece di
   stimare a occhio: la precisione dell'estrazione entra dritta nel budget.
2. Fit: `turntable-fit/fit` con `:angle-prior-sigma-deg 1.0` (angoli come
   priori, MAI rigidi — decisione 2026-07-18). Confronto anche con
   `box-fit/fit` a pose libere: la differenza misura il valore reale del
   giradischi su dati veri.
3. Report per quota: errore in mm sulle due quote di giudizio + reproiezione.
   Soglia: sotto ~0.2 mm il v1 è fondato; sopra, primo sospettato i raccordi
   (§ "Cosa resta aperto") — non toccare il solver prima di aver guardato lì.
4. Esiti nel design doc, § "Esiti degli accertamenti".

## Esiti — passata del 2026-07-19 (Code)

**EXIF riverificato in autonomia**: 48 mm eq. identico su tutte e 14, 4032×3024,
nessun DigitalZoomRatio → **f = 4032 × 48/36 = 5376 px**. Confermato quanto
scritto sopra.

**Il gate non è stato chiuso, e il motivo è un risultato.** Il lettore SD ha
spigoli verticali raccordati con **r ≈ 1.7 mm** (misurato su IMG_8880: banda di
raccordo di ~48 px a x≈2306-2354, y=1250, a ~19.5 px/mm). Simulando le
osservazioni da una scatola *raccordata* e fittando col modello a spigoli vivi,
alla geometria e agli angoli di questa sessione:

| r | err X (42.2) | err Y (15) | reproiez. |
|---|---|---|---|
| 0.0 mm | +0.003 | +0.004 | 0.50 px |
| 1.7 mm | +0.078 | **−0.297** | **10.66 px** |

Raccordo tollerabile da una soglia di 0.2 mm: **~1.16 mm**. Il pezzo è ~1.5×
troppo tondo. Y (lo spessore da 15 mm) è la quota che paga, perché il raccordo
toglie un ~r assoluto per lato. Dettagli e meccanismo nel design doc,
§ "Esecuzione del protocollo — sessione foto".

**Le foto vanno benissimo** — non serve rifarle per un problema di ripresa.
L'edge-snap sui gradienti dà **0.345 px** sullo spigolo interno e **0.795 px**
su un tratto pulito di silhouette: il rumore di estrazione vale ~0.02-0.04 mm,
trascurabile rispetto al bias. Servono solo per un pezzo diverso, o con un
modello che conosca il raccordo.

**Attenzione nell'estrazione** (vale anche per il click a mano): sulla
silhouette sinistra, sotto y≈1400, il gradiente più forte sono gli **slot delle
schede**, non lo spigolo. Tenere i due punti sul tratto pulito (y 700-1300 su
IMG_8880). Il tool ha lo snap sul tasto `s`: bastano due click grossolani.

**Tool**: `scripts/param-acq-tool.html`. Servire con
`python3 -m http.server 8099` dalla radice del repo, aprire
`http://localhost:8099/scripts/param-acq-tool.html`. Gli indici degli spigoli
nella lista sono già allineati a `box-fit/edges`; l'export EDN è pronto da dare
al fit.
