# Sessione fusione — posa 2: il collare CORICATO su un fianco

Seconda delle due pose con cui si collauda `(acquire-union a b)` su foto vere.
Compagna di `param-plate-paper-2`, dove lo stesso pezzo sta in piedi sui
piedini. Insieme coprono quello che il giradischi da solo non raggiunge: qui si
vede il sotto dell'arco e la faccia d'appoggio dell'altra posa, e viceversa.

## Il pezzo

- Lo STESSO collare a scatto in plastica nera di `param-plate-paper-2` (arco a
  C con due piedini e la testa rettangolare col rilievo).
- Qui è **coricato su un fianco**: appoggia su una delle due grandi facce
  laterali, con l'apertura della C verso destra e la testa verso sinistra. La
  faccia laterale opposta guarda in alto.
- Fissato con la solita pasta adesiva azzurra, al centro del piatto vicino alla
  crocetta. Corona di dischetti visibile tutt'intorno.
- Nera e semi-lucida: i riflessi cambiano da foto a foto e non sono feature.

## Il piatto

Lo stesso di carta della posa 1 (⌀130, corona di 12 dischetti + zero-indice
interno), geometricamente identico al proxy predefinito: qui basta
`(registration-plate)`.

## Quote di calibro del PEZZO

Non entrano nella registrazione col piatto. Se le misuri, mettile qui.

- X: ____
- Y: ____
- Z: ____

## Setup

- Data: 2026-08-05, subito dopo la posa 1 (stessa luce, stesso banco)
- iPhone 15 Pro Max, 4032×3024 (4:3)
- Focale 35mm-equivalente dalle EXIF: **48 mm** su tutte e quattro le foto
  (nella posa 1 la prima foto dichiarava 49: qui la sessione è omogenea)
- Giradischi: il pezzo ruota, la camera resta dov'è

## Foto

θ = `libera` per tutte: col piatto ogni foto si registra da sola via PnP sui
dischetti, l'angolo del giradischi non serve.

| file | θ (gradi) | note |
|------|-----------|------|
| IMG_8966.jpeg | libera | |
| IMG_8967.jpeg | libera | |
| IMG_8968.jpeg | libera | |
| IMG_8969.jpeg | libera | |

Quattro scatti sono ancora meno dei cinque della posa 1. Bastano a registrare,
ma ogni punto di un mark-piano va cliccato su ALMENO DUE foto: se una zona
d'aggancio si vede bene in una sola, quel mark non si può fare e la fusione
resta senza appiglio. È il punto da controllare per primo, prima di misurare
qualsiasi cosa.

## Agganci per la fusione (la parte che conta)

**Conta il PIANO, non il puntino.** Un mark viene creduto come piano: la sua
normale e la sua distanza lungo quella normale. Dove sta l'origine (il pallino
magenta) dentro il piano non entra nel conto — puoi piazzarla dove ti pare, e
nelle due sessioni può stare in due posti diversi della stessa faccia. È la
ragione per cui la fusione si appoggia ai piani: una faccia piatta la ritrovi in
un altro set di foto, un punto preciso su quella faccia no.

Un piano vincola 3 dei 6 gradi di libertà, quindi:

- **TRE piani con normali che guardano in direzioni diverse** determinano tutto.
  È il caso da puntare.
- **DUE piani non bastano**: lasciano libero lo scorrimento lungo la loro
  intersezione. La fusione lo rifiuta e lo dice.
- Due piani **più un punto vero** bastano: se una zona è uno spigolo o un
  dettaglio riconoscibile, dichiaralo con `:point? true` dentro il mark e allora
  la sua posizione viene usata per intero.

Da questa posa, incrociando con la posa 1, le tre zone che si vedono in entrambe
e guardano in direzioni diverse sono:

- `:testa-sopra` — la faccia piatta in cima alla testa. In posa 1 guarda in
  alto, qui guarda di lato.
- `:fianco` — la grande faccia laterale piatta che qui guarda in ALTO. In posa 1
  è una delle due facce laterali. **Attenzione**: il pezzo ha due fianchi quasi
  identici, e vanno bene solo se in entrambe le sessioni usi lo STESSO, quello
  che qui sta in alto. Riconoscilo da un dettaglio asimmetrico (il rilievo sulla
  testa, o dove sta la pasta adesiva), non "a occhio".
- `:becco` — la faccia piatta all'estremità di uno dei due bracci della C.

Le loro normali sono all'incirca perpendicolari fra loro: è la condizione che
serve. Tre facce PARALLELE (per esempio i due fianchi opposti) non servirebbero
a niente: fissano una direzione sola.

**Il nome è la dichiarazione.** Chiama la stessa zona con lo stesso nome nelle
due sessioni e non serve altro:

```clojure
(def U (acquire-union [[:A A] [:B B]]))
```

Rinomina il `:piano-N` automatico appena compare nel sorgente — mentre ti ricordi
quale zona era. Dopo la fusione niente si perde e niente collide: `:A/fianco` e
`:B/fianco` sono le due misure della stessa faccia (ognuna con la SUA origine,
che può stare in punti diversi della faccia), e `:fianco` nudo è quella della
sessione di riferimento.

Se i nomi non possono coincidere — per esempio perché in una sessione avevi già
chiamato `:top` un'altra faccia — le corrispondenze si dichiarano a mano:

```clojure
(acquire-union [[:A A] [:B B]] [[:A/piano-1 :B/piano-3] …])
```

## Per Code

- Auto-inizializzazione in-app da questo NOTE + le foto.
- Registrazione: `(edit-acquire "test-assets/param-plate-paper-3/"
  {:proxy (registration-plate)})`, poi il bottone **Auto**.
- Poi, con le due `(acquire …)` nel sorgente:
  `(def U (acquire-union A B))` — A la posa 1, B questa. Il rapporto stampa lo
  scarto per aggancio in mm; sotto il millimetro è buono.
