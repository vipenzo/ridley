# 9. Librerie

Una libreria è un blocco di codice Ridley salvato con un nome, separato dal sorgente nell'editor. Una volta attivata, le sue funzioni diventano raggiungibili dal tuo codice tramite un prefisso: se la libreria si chiama `shapes` e definisce `hexagon`, la usi con `(shapes/hexagon)`.

Le librerie sono il modo in cui accumuli codice riusabile fra un progetto e l'altro: forme parametriche, pattern di assemblaggio, utility che ti ritrovi a riscrivere ogni volta. Sono anche il meccanismo con cui Ridley importa asset esterni (SVG e STL).

## 9.1 A cosa servono le librerie

Il codice nell'editor è il tuo progetto corrente. Ogni volta che premi Cmd+Enter, viene evaluato da zero in un contesto fresco: i `def` e i `defn` nascono, vivono per la durata dell'eval, e scompaiono alla successiva. Se definisci una funzione utile in un progetto e vuoi usarla in un altro, devi copiarla a mano.

Le librerie risolvono questo problema. Una libreria è persistente (sopravvive fra sessioni), indipendente dal progetto corrente, e attivabile o disattivabile dal pannello. Quando è attiva, le sue definizioni pubbliche sono disponibili nel contesto dell'eval come namespace separato.

Un uso tipico: hai scritto una funzione `rounded-rect` parametrica che ti piace. La copi in una libreria chiamata `my-shapes`, e da quel momento ogni progetto può usare `(my-shapes/rounded-rect 40 20 5)` senza ridefinirla.

## 9.2 Usare una libreria

### Il pannello librerie

Il pannello librerie è accessibile dalla sidebar. Mostra la lista delle librerie disponibili, con un toggle per attivare o disattivare ciascuna. L'ordine nella lista è quello di caricamento: puoi riordinare con drag&drop se una libreria dipende da un'altra.

### Attivazione

Attivare una libreria significa renderla disponibile nel contesto dell'eval. Al prossimo Cmd+Enter, le sue funzioni saranno raggiungibili con il prefisso.

Disattivare una libreria la rimuove dal contesto: le chiamate con il suo prefisso produrranno un errore.

L'attivazione non è automatica per le dipendenze. Se la libreria A dichiara di richiedere B, devi attivare entrambe manualmente. Se B non è attiva, A viene saltata con un warning.

### Prefisso namespace

Il nome della libreria diventa il prefisso. Una libreria `connectors` con `(defn slot [w d] ...)` si usa come `(connectors/slot 10 5)`.

Non serve scrivere `require` nel tuo codice: Ridley lo fa automaticamente per ogni libreria attiva.

Il nome può contenere lo slash (per esempio `robot/arm`), ma è un nome singolo, non una gerarchia di namespace. `(robot/arm/joint)` è la funzione `joint` della libreria `robot/arm`.

### Il ciclo di caricamento

A ogni Cmd+Enter, il contesto SCI viene ricostruito da zero. Tutte le librerie attive vengono caricate nell'ordine topologico (rispettando le dipendenze dichiarate), analizzate in sequenza, e i loro simboli pubblici diventano disponibili. Non c'è caching: se il sorgente di una libreria è cambiato, il prossimo eval lo vedrà.

## 9.3 Librerie built-in: SVG e STL import

Ridley usa il sistema librerie per importare asset esterni. Il risultato dell'import è una libreria ordinaria, indistinguibile da una scritta a mano. Puoi ispezionarla, modificarla, esportarla.

### Import SVG

Dal pannello librerie, il bottone `+` → "Load SVG" apre un file picker. Ridley legge il file, estrae le forme dai path SVG, e genera una libreria con un `def` per ogni elemento riconosciuto.

```clojure
;; Dopo aver importato un file con un esagono e una stella:
(my-svg/hexagon)    ;; => shape 2D dell'esagono
(my-svg/star)       ;; => shape 2D della stella
```

I nomi dei `def` vengono dagli `id` degli elementi SVG. Se il file non ha `id`, Ridley genera nomi numerici (`shape-0`, `shape-1`...).

Dopo l'import, il pannello entra automaticamente in edit mode e mostra il sorgente generato. È il momento giusto per controllare che i nomi corrispondano a quello che ti aspetti e per rinominarli se serve.

Sotto il cofano, la libreria generata contiene la stringa SVG completa e usa le API SVG del browser per campionare i path al momento dell'eval. Questo significa che il sorgente è portabile (funziona in qualsiasi browser), ma le forme vengono ricampionate a ogni Cmd+Enter.

Le funzioni DSL corrispondenti sono tre: `svg` registra una stringa SVG, `svg-shape` estrae una singola forma per `id`, `svg-shapes` estrae tutte le forme come mappa.

Il fatto che l'import produca una libreria editabile è un vantaggio concreto: puoi aprirla in edit mode e rimuovere le shape che non ti servono, rinominare quelle che hanno nomi generici, o trasformarle in loco (scalare, ruotare, combinare). L'SVG importato non è un blob opaco: è codice, e lo tratti come tale.

### Import STL

Dal pannello librerie, il bottone `+` → "Load STL" importa un file STL binario. Ridley parsa il file al momento dell'import, esegue il welding dei vertici, e genera una libreria con un singolo `def` che contiene la mesh codificata in base64.

```clojure
(my-model/part)    ;; => mesh 3D pronta per register, booleane, ecc.
```

A differenza dell'SVG, il parsing avviene una sola volta (all'import), non a ogni eval. Il sorgente della libreria contiene due stringhe base64 (vertici e facce) e la funzione `decode-mesh` che le ricostruisce in una mesh Ridley. Il sorgente è più pesante ma l'eval è veloce.

Il pannello non entra in edit mode dopo l'import STL, perché modificare a mano stringhe base64 non avrebbe senso. Se vuoi rinominare il `def`, apri l'editor della libreria manualmente.

Anche se il sorgente base64 non è leggibile, la libreria resta editabile in modo utile. Un caso comune: il file STL è stato salvato in metri invece che in millimetri, e la mesh risultante è mille volte troppo grande. Basta aprire la libreria e aggiungere un `(scale model 0.001)` dopo il `decode-mesh`.

## 9.4 Creare una libreria propria

### Dal pannello

Il bottone `+` → "New library" chiede un nome e crea una libreria vuota. Si apre l'edit mode: scrivi il codice della libreria direttamente nell'editor principale di Ridley.

Una libreria è codice Clojure ordinario. Tutto ciò che puoi scrivere nell'editor principale funziona in una libreria: `def`, `defn`, `defonce`, strutture dati, chiamate a funzioni Ridley.

```clojure
;; Esempio: libreria "fasteners"
(defn hex-bolt [r h]
  (let [head (cyl (* r 1.5) (* h 0.3) 6)
        shaft (cyl r h)]
    (mesh-union head shaft)))

(defn washer [r]
  (mesh-difference
    (cyl (* r 1.8) 1)
    (cyl (* r 1.1) 1)))
```

Solo i `def` e `defn` di top level diventano simboli pubblici della libreria. Le variabili locali (dentro `let`, `loop`, ecc.) restano private.

### Edit mode

Quando clicchi "Edit" su una libreria nel pannello, l'editor principale salva il tuo lavoro corrente, lo sostituisce con il sorgente della libreria, e mostra una barra con "Back" e "Save".

Modifichi il codice, premi "Save" per salvare, "Back" per annullare. Il contenuto originale dell'editor viene ripristinato in entrambi i casi.

Un Cmd+Enter durante l'edit mode valuta il sorgente nell'editor come se fosse il programma principale: utile per testare le funzioni della libreria, ma attenzione che i `def` finiscono nello scope globale dell'eval, non nel namespace della libreria (questo cambierà solo al prossimo save + eval regolare).

### Dipendenze

Se una libreria usa funzioni di un'altra, dichiaralo nell'header. L'header è la prima riga del sorgente, in formato commento:

```clojure
;; :requires [my-shapes utils]
```

Ridley legge questa dichiarazione e carica le dipendenze prima della libreria corrente. Se una dipendenza non è attiva o non esiste, la libreria viene saltata con un warning.

### Librerie come workpad

Un uso laterale del sistema: creare una libreria vuota come spazio di lavoro separato. Apri l'edit mode, scrivi codice sperimentale, premi Cmd+Enter per testarlo, e quando hai finito torni al tuo progetto con "Back". Il sorgente principale resta intatto. È un modo pratico per fare esperimenti senza inquinare il programma corrente.

## 9.5 Condividere librerie

### Export

Dal pannello librerie, ogni libreria può essere esportata come file `.clj`. Il file contiene un header con nome e dipendenze, seguito dal sorgente. È un file di testo leggibile e modificabile con qualsiasi editor.

### Import

Il bottone `↑` nel pannello apre un file picker per importare file `.clj` o `.cljs`. Ridley legge l'header per ricavare nome e dipendenze, e aggiunge la libreria alla lista.

### Desktop vs web

In ambiente desktop (Tauri), le librerie vivono come file `.clj` in `~/.ridley/libraries/`. Puoi modificarle con un editor esterno e ritrovarle aggiornate al prossimo Cmd+Enter.

In ambiente web, le librerie vivono in localStorage come oggetti JSON. L'export/import `.clj` è l'unico modo per trasferirle fra browser o fra utenti.

In entrambi i casi, il formato `.clj` esportato è identico: una libreria esportata dal web funziona sul desktop e viceversa.
