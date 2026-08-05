# Sessione fusione — posa 1: il collare IN PIEDI sui piedini

Prima delle due pose che servono a collaudare `(acquire-union a b)` con foto
vere. Il giradischi copre una FASCIA di angoli e basta: da questa posa non si
vedono né il sotto dei piedini né l'interno dell'arco visto da sotto. La seconda
sessione (`param-plate-paper-3`) riprende lo STESSO pezzo appoggiato
diversamente, e la fusione le mette in un frame solo.

## Il pezzo

- Collare a scatto in plastica nera (arco a C con due piedini e una testa
  rettangolare con il rilievo), appoggiato sui DUE PIEDINI, fissato con un
  po' di pasta adesiva azzurra.
- Sta al centro del piatto, accanto alla crocetta di riferimento; la corona di
  dischetti resta visibile tutt'intorno in tutte le foto.
- Superficie nera e semi-lucida: i riflessi sull'arco cambiano da foto a foto e
  NON sono feature — per i mark scegliere spigoli e facce piatte, non luci.

## Il piatto

Quello di CARTA (⌀130, corona di 12 dischetti + zero-indice interno), lo stesso
delle sessioni `param-plate-paper`.

La sua geometria coincide con quella del proxy predefinito: corona a raggio 58,
zero-indice a raggio 52, dischetti ⌀2.5, spessore 3. Quindi qui basta
`(registration-plate)` e non serve caricare `examples/param-acq-plate.clj`:
`piatto-carta` serviva per applicare i fattori di scala del foglio, che su
questo piatto sono risultati 1.000 (barre di calibrazione misurate 100.0 mm su
100.0 nominali).

## Quote di calibro del PEZZO

Non entrano nella registrazione col piatto (la posa della camera viene dai mark,
non dal fit del giradischi). Utili solo come controprova della scala: se le
misuri, mettile qui.

- X: ____
- Y: ____
- Z: ____

## Setup

- Data: 2026-08-05
- iPhone 15 Pro Max, 4032×3024 (4:3)
- Focale 35mm-equivalente dalle EXIF: **48 mm** su quattro foto, **49 mm** sulla
  prima (IMG_8961). L'app legge la focale UNA volta, dalla prima foto della
  sessione, quindi userà 49. La differenza è ~2%: se la registrazione venisse
  sistematicamente lasca su tutte le foto, provare l'override manuale a 48 con
  lo slider Focale (attenzione: una focale impostata a mano viene salvata e ai
  rientri successivi vince sull'EXIF).
- Giradischi: il pezzo ruota, la camera resta dov'è.

## Foto

θ = `libera` per tutte: col piatto la registrazione è PER-FOTO via PnP sui suoi
dischetti, quindi l'angolo del giradischi non serve e non c'è fit congiunto da
alimentare. La colonna resta perché è quella che fa riconoscere la riga.

| file | θ (gradi) | note |
|------|-----------|------|
| IMG_8961.jpeg | libera | |
| IMG_8962.jpeg | libera | |
| IMG_8963.jpeg | libera | |
| IMG_8964.jpeg | libera | |
| IMG_8965.jpeg | libera | |

Cinque scatti sono pochi per un giro: bastano a REGISTRARE (ogni foto si
registra da sola sul piatto), ma un mark-piano ha bisogno dello stesso punto
cliccato su ALMENO DUE foto, e un ricalco vive di viste diverse. Se una zona
d'aggancio si vede bene in una sola foto, aggiungerne un paio prima di misurare
costa meno che rifare la sessione.

## Agganci per la fusione (la parte che conta)

`acquire-union` non riconosce niente da solo: il legame fra le due sessioni sono
i mark che portano lo **stesso nome** in entrambe, e che devono stare sullo
**stesso punto fisico** del pezzo. Regole, in ordine di importanza:

1. **Visibili in entrambe le pose.** Una zona che in una delle due sessioni
   guarda il piatto non è un aggancio.
2. **Lontani fra loro.** Due mark vicini determinano una rotazione che è rumore
   amplificato: sotto i 5 mm di distanza la fusione si rifiuta proprio.
3. **Normali non parallele**, se gli agganci sono due. Due facce parallele non
   fissano la rotazione attorno alla retta che le unisce. Tre punti non allineati
   vanno bene lo stesso.

Zone candidate su questo pezzo, da scegliere DOPO aver visto come sta la posa 2:

- `:testa-sopra` — la faccia piatta in cima alla testa (normale verso l'alto in
  questa posa);
- `:testa-fronte` — la faccia rettangolare della testa dove sporge il rilievo
  (normale orizzontale: buona compagna di `:testa-sopra`, le due normali sono
  perpendicolari);
- `:piede-a` / `:piede-b` — i fianchi esterni piatti dei due piedini, l'estremo
  opposto della testa: sono questi a dare la base larga che la regola 2 chiede.

Il nome va scritto identico nelle due sessioni. Un nome uguale su due punti
DIVERSI è l'errore che la fusione può solo subire — anche se lo denuncia: nel
rapporto quell'aggancio esce con uno scarto molto più grande degli altri e viene
nominato.

## Per Code

- La sessione si auto-inizializza in-app da questo NOTE + le foto: nessun
  `--init-session` a mano.
- Registrazione: `(edit-acquire "test-assets/param-plate-paper-2/"
  {:proxy (registration-plate)})`, poi il bottone **Auto**.
- Esito atteso: le 5 foto registrate con rms sotto i ~2-3 px (dischetti di carta
  netti). Le radenti, se ce ne sono, restano per `f` / `p`.
- Poi: mark-piano sulle zone d'aggancio da entrambe le sessioni, e
  `(acquire-union A B)` con le due `(acquire …)` nel sorgente.
