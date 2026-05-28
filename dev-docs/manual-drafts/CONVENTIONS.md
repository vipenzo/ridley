# Convenzioni per i capitoli del manuale

Note operative per chi scrive o modifica i file `.md` del manuale.

## Prima riga del file

La prima riga NON deve iniziare con `<!-- testo...` sulla stessa riga. Se un file comincia con un commento HTML che contiene testo sulla prima riga, shadow-cljs classifica il file come HTML e lo processa in modo errato (tra i sintomi: l'operatore di threading `->` negli esempi viene reso come `-&gt;` nel viewer Tauri).

Forme corrette:
- Il file inizia con un heading markdown, ad esempio `# 8. Assemblaggio`.
- Il file inizia con un commento HTML in cui `<!--` sta da solo sulla prima riga, e il testo del commento comincia dalla riga successiva.

Forma da evitare:
- `<!-- Note interne: ...` con testo sulla stessa riga di apertura del commento.

## Markdown

- Niente wrapping forzato delle righe. Ogni paragrafo e ogni item di lista stanno su una singola riga; l'a-capo lo gestisce il viewer.
- Niente em-dash.

## Lingua

- Prosa in italiano, con caratteri accentati corretti.
- Codice degli esempi in inglese (nomi di variabili, commenti dentro i blocchi `clojure`).
- Termini tecnici inglesi (import, export, build) lasciati in inglese o riformulati, evitando calchi che producono parole italiane di altro significato.

## Esempi runnable

- Un blocco `clojure` viene eseguito dal sistema quando è preceduto dal marker `<!-- example-source: nome-esempio -->` (inline, subito prima del blocco), oppure quando il codice da eseguire è racchiuso in un commento `<!-- example-source: nome\n...codice...\n-->`.
- Gli esempi che mostrano solo una firma o un frammento illustrativo non devono avere il marker, per non essere eseguiti.
- Quando un blocco contiene piu di una mesh registrata con `register`, distanziare gli elementi con un movimento della tartaruga (ad esempio `(f 60)`) tra una `register` e l'altra, altrimenti si sovrappongono nel viewport.
