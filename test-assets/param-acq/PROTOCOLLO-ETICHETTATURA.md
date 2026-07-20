# Protocollo di etichettatura — estrazione spigoli

Per la sessione in questa cartella. Obiettivo: portare a casa le rette degli
spigoli **senza mai doverti chiedere "questo è lo spigolo x+ y− o x− y+?"**.

## L'idea, in una riga

Tu clicchi gli spigoli **a gruppi, in ordine da sinistra a destra**. Il solver
prova le poche assegnazioni possibili e tiene quella che *fitta*. Una
corrispondenza sbagliata non fitta: l'etichetta la decide l'evidenza, non la
tua memoria di una convenzione.

Non devi sapere dove punta X. Non devi ricordarti da che parte gira il piatto.
Non devi essere coerente fra una foto e l'altra su nient'altro che l'ordine
sinistra→destra.

## I tre gruppi

Guardando la foto, ogni spigolo visibile della scatola sta in uno di tre
gruppi, e distinguerli è immediato:

| gruppo | quali sono | come li riconosci |
|--------|-----------|-------------------|
| **verticali** | i 4 spigoli lunghi | corrono su e giù, paralleli all'asse del piatto |
| **alto** | i 4 del contorno della faccia superiore | delimitano il "tetto" |
| **basso** | i 4 del contorno della faccia inferiore | dove il pezzo tocca il piatto |

Da una vista generica ne vedi **2 o 3 per gruppo** (mai tutti e 4: gli altri
sono dietro).

## L'ordine dei click, gruppo per gruppo

**Verticali: da sinistra a destra.** Ordinati per posizione orizzontale
nell'immagine. Stabile e senza sorprese.

**Alto e basso: dal più vicino, in senso orario.** Parti dallo spigolo più
vicino a te — quello più in basso nell'immagine — e gira in senso orario.

> Perché non "da sinistra a destra" anche per questi: la faccia superiore si
> proietta in un quadrilatero, e i punti medi dei suoi quattro spigoli si
> **scambiano di posto in x** con pochi gradi di rotazione. Misurato: lo
> stesso spigolo fisico cambiava posizione nella lista fra una foto e la
> successiva, e il fit ne usciva a 46 px invece di 0.7. L'ordine ciclico
> attorno alla faccia invece non cambia (verificato su ±11° di rotazione).

## ⚠️ Servono tutti e tre i gruppi

Non è un consiglio, è un requisito, e il modo in cui fallisce è infido:

| gruppi cliccati | reproiezione | quote ricavate |
|-----------------|--------------|----------------|
| solo verticali | **0.01 px** | [79.9 · 55.8] ✗ |
| verticali + alto | 0.66 px | [88.9 · 31.6] ✗ |
| tutti e tre | 0.68 px | [42.2 · 15.0] ✓ |

Con i soli verticali il fit **torna perfetto e sbagliato**. Il motivo: il
calibro fissa Z, ma **Z è osservabile solo se si vedono sia il sopra sia il
sotto** del pezzo. Senza, il vincolo di scala non morde e X/Y scappano
insieme alla distanza della camera.

Morale generale, valida ben oltre questo protocollo: **una reproiezione bassa
non è una prova che la risposta sia giusta.**

## Cosa fare, foto per foto

Per ciascuna delle **4 foto di bootstrap** (θ = 0, −30, −60, −90 — cioè
`IMG_8880`, `IMG_8883`, `IMG_8886`, `IMG_8889`, marcate ★ nel menu):

1. Scegli il gruppo (`1`/`2`/`3` o i bottoni).
2. Clicca gli spigoli di quel gruppo **nell'ordine del gruppo**, due punti per
   spigolo.
3. Premi `s` per lo snap.
4. Ripeti per gli altri due gruppi.

Il pannello segna "bootstrap pronto" quando tutte e 4 le foto hanno almeno un
click in ciascuno dei tre gruppi.

**Due punti, non gli estremi.** Il modello vuole la *retta* su cui giace lo
spigolo. I due click possono stare ovunque lungo di essa — anzi, è meglio se
sono ben distanziati, la retta viene fuori più precisa.

**Poi premi `s` (snap).** Bastano click grossolani: lo snap aggancia i
gradienti dell'immagine e rifinisce a ~0.3-0.8 px, meglio di quanto tu possa
cliccare a mano. Il numero che compare è il residuo: **sotto 1 px è ottimo,
sopra 2-3 px vuol dire che si è agganciato alla cosa sbagliata** — rifai i
due punti più corti, su un tratto pulito.

### La trappola nota di questa sessione

Sulla silhouette sinistra, **sotto y≈1400 il gradiente più forte sono gli
slot delle schede, non lo spigolo**. Tieni i due punti in alto (su `IMG_8880`,
fra y 700 e 1300). Vale anche per il click a mano: lo snap va dove c'è il
contrasto, e lì il contrasto è di un'altra cosa.

## Il giro completo, in comandi

```bash
# 1. servire il repo e aprire il tool
python3 -m http.server 8099
#    → http://localhost:8099/scripts/param-acq-tool.html

# 2. cliccare le 4 foto ★, poi "Esporta EDN" e salvare il testo in:
#    test-assets/param-acq/picks.edn

npx shadow-cljs compile paq

# 2b. controllo rapido, anche a una foto sola: verifica che i conteggi
#     per gruppo siano compatibili con qualche orientamento. Da usare
#     mentre clicchi, per non scoprire un errore solo alla fine.
node out/paq.js --check test-assets/param-acq/picks.edn

# 3. fit + proposte (servono almeno 2 foto complete, meglio 4)
node out/paq.js test-assets/param-acq/picks.edn

# 4. nel tool: "Carica predictions.json" → le proposte compaiono
#    tratteggiate su ogni foto. Confermi o correggi, riesporti, si ripete.
```

Il comando stampa quote, concordanza fra le ipotesi e il report dei residui.
Per vedere com'è fatto un giro riuscito senza cliccare niente:

```bash
node out/paq.js --selftest
```

## Poi: le proposte

Con le prime 4 foto faccio il fit grezzo e **riproietto tutti e 12 gli
spigoli su tutte e 14 le foto**. Da lì il tuo lavoro cambia: non origini più
le rette, **confermi**. Ogni proposta arriva già etichettata e già quasi al
posto giusto; a te resta accettarla, o correggerla dove il wireframe non
combacia.

Sulle foto lontane dalle prime 4 le proposte saranno più fuori (l'errore
cresce con l'estrapolazione): è normale, e ogni foto confermata rende più
precise le successive.

## L'allarme

Dopo ogni fit esce un **report dei residui per osservazione**, ordinato dal
peggiore. Serve a una cosa sola: **stanare le etichette sbagliate**.

Uno spigolo etichettato bene sta al livello del rumore. Uno etichettato male
è geometricamente incompatibile con tutto il resto e **nessuna posa riesce ad
assorbirlo**: salta fuori di un ordine di grandezza. La soglia è relativa
(4× la mediana), non assoluta, così si adatta a quanto è venuta bene
l'estrazione.

Se il report segnala qualcosa:

- **una sola osservazione fuori** → quasi sempre un click sbagliato o uno
  snap agganciato al bordo sbagliato. Rifai quello spigolo.
- **tutte le osservazioni di una foto fuori** → in quella foto l'ordine dei
  click è saltato di uno (tipico: hai contato uno spigolo che non era
  visibile). Rifai la foto.
- **niente fuori ma reproiezione alta ovunque** → non è un problema di
  etichette, è il *modello*: i raccordi. Vedi il design doc.

## Quanto lavoro è

4 foto × ~7 spigoli visibili = **una trentina di spigoli da cliccare**. Il
resto è conferma. Se le prime 4 vengono bene, le altre 10 costano pochissimo.

## Se il bootstrap non trova un vincitore netto

Il bootstrap riporta il rapporto fra il migliore e il secondo. Se il migliore
non stacca almeno 2×, **l'etichettatura è ambigua e le proposte non sono da
fidarsi**: la risposta giusta non è abbassare l'asticella ma dare più dati —
una quinta foto, o i gruppi alto/basso dove li avevi saltati.
