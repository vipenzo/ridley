# Brief: Costruzione `reference-index.cljs`

**Contesto**: Flusso B, step 4 del piano (§9.2). Questo brief copre *solo* la pipeline di build che produce l'indice. Il resto del Flusso B (renderer Markdown, shortcode, pannello manuale, migrazione content.cljs) è differito a un brief successivo.

**Obiettivo**: uno script Babashka che legge le 261 schede Reference da `dev-docs/reference-manual/` e produce un file ClojureScript (`src/ridley/manual/reference_index.cljs`) con una mappa simbolo → metadati. Questo file è il prerequisito per il Flusso C (completion e tooltip in CodeMirror).

---

## 1. Input

Le schede vivono in `dev-docs/reference-manual/`. Struttura piatta, un file `.md` per scheda, con sottocartella `internals/` per le schede Internals.

Ogni scheda ha un frontmatter YAML. Lo schema de facto (stabilito da `loft.md` come modello e replicato nelle 261 schede dei batch 1-6) è:

```yaml
---
name: loft
category: generative-operations
since: ""
status: stable
---
```

Campi:

- `name` — simbolo Ridley (chiave primaria dell'indice)
- `category` — slug stabile della categoria (non la stringa numerata di Spec.md)
- `since` — stringa versione, spesso vuota
- `status` — `stable`, `experimental`, `deprecated`

Dopo il frontmatter, le schede seguono una struttura con heading Markdown:

```
# <nome>

## Signature

`(nome arg1 arg2 ...)`

## Description

Testo descrittivo.

## Parameters
...

## Example
...

## Notes
...

## See also
...
```

Non tutte le sezioni sono sempre presenti.

## 2. Output

Un file `src/ridley/manual/reference_index.cljs` con un namespace e una mappa:

```clojure
(ns ridley.manual.reference-index)

(def reference-index
  {"loft"
   {:name "loft"
    :category "generative-operations"
    :status "stable"
    :signature "(loft shape-fn & path-commands)"
    :description "Extrude with shape transformation based on progress."
    :path "dev-docs/reference-manual/loft.md"
    :aliases ["loft-n" "loft-between"]}

   "loft-n"
   {:name "loft-n"
    :redirect "loft"}

   ;; ... 261 entries
   })
```

### Campi per entry completa

| Campo | Fonte | Note |
|-------|-------|------|
| `:name` | frontmatter `name` | Chiave primaria |
| `:category` | frontmatter `category` | Slug stabile |
| `:status` | frontmatter `status` | |
| `:signature` | prima riga di codice sotto `## Signature` | Estrarre il contenuto del backtick inline. Se ci sono più signature (varianti), prendere la prima |
| `:description` | primo paragrafo sotto `## Description` | Solo il primo paragrafo, troncato a 200 caratteri. Serve per il tooltip, non per la scheda completa |
| `:path` | path relativo del file `.md` | Per aprire la scheda completa nel pannello manuale |
| `:aliases` | *derivato* (vedi sotto) | Nomi delle schede stub che rimandano a questa |

### Campi per entry stub (redirect)

Le schede stub (varianti di arity come `loft-n`, `loft-between`) contengono solo `:name` e `:redirect` che punta alla scheda madre. Lo script deve riconoscere le stub: sono schede il cui body contiene un rimando esplicito alla scheda madre (tipicamente "See [nome-madre]" o simile) e hanno description minimale. Euristica ragionevole: se il body sotto `## Description` è più corto di 100 caratteri e contiene un link a un'altra scheda, è una stub.

Se l'euristica non funziona bene su tutte le 261 schede, è accettabile un approccio più semplice: trattare tutte le schede come entry complete (nessuna distinzione stub/madre). L'indice sarà leggermente più grande ma funzionalmente corretto. L'ottimizzazione stub/redirect si può aggiungere dopo.

## 3. Script

File: `scripts/build_reference_index.bb`

Linguaggio: Babashka (coerente con `scripts/spec_lint.bb` già esistente).

### Logica

1. Enumerare tutti i `.md` in `dev-docs/reference-manual/` (ricorsivo, per includere `internals/`)
2. Per ogni file: parsare il frontmatter YAML, estrarre signature e description dal body Markdown
3. Costruire la mappa
4. Serializzare come `.cljs` con `(ns ...)` e `(def reference-index ...)`
5. Scrivere in `src/ridley/manual/reference_index.cljs`

### Parsing del frontmatter

Il frontmatter è delimitato da `---`. Babashka ha `clj-yaml` disponibile. Estrarre i quattro campi noti. Ignorare campi sconosciuti senza errore (forward-compatible).

### Parsing del body

Non serve un parser Markdown completo. Il body è strutturato con heading `##` prevedibili. Approccio:

1. Splittare il body per `## ` (heading di livello 2)
2. Trovare la sezione "Signature": estrarre il contenuto tra backtick dalla prima riga di codice
3. Trovare la sezione "Description": prendere il primo paragrafo (fino alla prima riga vuota o fino al prossimo heading)
4. Troncare la description a 200 caratteri

Se una sezione manca, il campo corrispondente è stringa vuota.

### Validazione

Lo script deve segnalare (warn, non fail):

- Schede senza frontmatter o con frontmatter malformato
- Schede senza `name` nel frontmatter
- Nomi duplicati fra schede diverse
- Schede senza sezione Signature

A fine esecuzione, stampare un riepilogo: N schede processate, N warning, N entry nell'indice.

## 4. Integrazione nel build

Il file generato (`reference_index.cljs`) è un file sorgente ClojureScript che shadow-cljs compila normalmente. Non è un file di dati caricato a runtime: è codice che definisce una mappa statica.

Lo script va eseguito prima del build shadow-cljs. Aggiungere un alias `:build-ref-index` in `bb.edn` (o equivalente) che chiama lo script.

Per ora non serve integrazione in CI. Lo script si esegue manualmente quando le schede cambiano. L'integrazione in CI è materia del brief B completo.

## 5. Cosa NON fare in questo brief

- Non costruire il renderer Markdown per il pannello manuale
- Non costruire il sistema di shortcode per gli esempi
- Non toccare `structure.cljs` o `content.cljs`
- Non costruire il completion source CodeMirror (è il Flusso C, brief separato)
- Non costruire tooltip (idem)
- Non fare la migrazione del manuale esistente
- Non gestire il bilinguismo (oggi le schede sono solo EN, l'indice riflette questo)

## 6. Criteri di accettazione

1. `bb scripts/build_reference_index.bb` gira senza errori
2. Produce `src/ridley/manual/reference_index.cljs` con un namespace valido
3. La mappa contiene almeno 260 entry (261 schede, alcune potrebbero essere stub)
4. Ogni entry ha almeno `:name`, `:category`, `:path`
5. `shadow-cljs` compila il file senza errori
6. Da REPL ClojureScript: `(get ridley.manual.reference-index/reference-index "loft")` restituisce la mappa attesa

## 7. Dry-run consigliato

Prima di scrivere lo script, fare un dry-run su 5 schede rappresentative:

- `loft.md` (scheda madre con varianti)
- `loft-n.md` (stub)
- `box.md` (primitiva semplice)
- `warp.md` (operazione con molti parametri)
- Una scheda da `internals/` (per verificare il path ricorsivo)

Leggere il frontmatter e il body, verificare che l'estrazione di signature e description funzioni, e confermare il formato di output prima di procedere su tutte le 261.
