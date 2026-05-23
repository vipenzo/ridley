# Brief: rigenerare lo spike con tutti i capitoli (2-17)

## Contesto

Lo spike del pannello manuale attualmente include solo un sottoinsieme dei capitoli. Questo brief chiede di includere tutti i capitoli presenti in `dev-docs/manual-drafts/`, dal 02 al 17.

I capitoli 2-6 usavano un vecchio formato con shortcode `{{example: id}}`. Quel formato è stato abbandonato: stiamo uniformando tutti i capitoli al formato usato dal 7 in poi, dove il codice degli esempi vive direttamente nel Markdown in blocchi ```clojure``` preceduti dal marker `<!-- example-source: id -->`. L'uniformazione dei capitoli 2-6 la facciamo noi in parallelo.

Il renderer dello spike deve gestire un solo formato.

## Formato unico degli example-source

Un blocco eseguibile (con bottone Run) ha questa struttura nel Markdown:

```
<!-- example-source: some-id -->
```clojure
(def my-cube (box 20))
(register my-cube)
```
```

I blocchi ```clojure``` senza il marker `<!-- example-source -->` sono frammenti illustrativi e non hanno bottone Run.

Non ci sono altri formati da supportare. In particolare, gli shortcode `{{example: id}}` e i tag di chiusura `<!-- /example-source -->` non devono essere gestiti dal renderer (se li incontra, li ignora come qualsiasi altro testo).

## Lavoro richiesto

### 1. Aggiornare il manifest

Includere nel manifest tutti i file `NN-*.md` presenti in `dev-docs/manual-drafts/`, dal 02 al 17. I nomi file esatti possono variare: verificare il contenuto effettivo della cartella.

### 2. Verifica

Dopo l'aggiornamento:

- Tutti i capitoli 2-17 devono comparire nella navigazione del pannello.
- I bottoni Run devono funzionare sui blocchi marcati `<!-- example-source -->`.
- I blocchi ```clojure``` senza marker restano frammenti illustrativi, senza bottone Run.

## Fuori scope

- Revisione del contenuto degli example-source (la facciamo noi).
- Capitolo 1 (non ancora scritto).
- Supporto per shortcode `{{example: id}}` o per `structure.cljs` come sorgente degli esempi.
