# Brief — Auto-linking dei riferimenti Reference nella prosa

Documento di lavoro per Code. Estende T-009 (link prosa verso scheda, già implementato): invece di scrivere i link a mano, il renderer li crea da solo.

## Obiettivo

A render time, nel manuale, trasformare automaticamente ogni code span in backtick della prosa il cui contenuto è un simbolo noto della Reference in un link alla scheda di quel simbolo. Così ogni riferimento deliberato a una funzione diventa cliccabile, senza markup manuale `[x](ref:x)` nei file, e la copertura è completa invece che limitata alla prima menzione.

Motivazione: i link manuali non si distinguono dal testo finché non ci passi il mouse sopra, quindi linkare selettivamente a mano dà pochi salti e per giunta nascosti. Con l'auto-linking ogni nome di funzione in backtick è raggiungibile, in modo uniforme e senza lavoro editoriale capitolo per capitolo.

## Regola di matching

- Si applica solo ai code span inline in backtick nella prosa (`` `extrude` ``). Non ai blocchi ```clojure degli esempi (lì il tooltip Reference in hover esiste già), e non dentro link già esistenti.
- Un code span diventa link se e solo se il suo contenuto, trimmato, è esattamente il nome di un simbolo presente in `reference-index.cljs`. Match esatto, non substring: `` `extrude` `` linka, `` `extrude-closed` `` linka, ma `` `circle 15` ``, `` `(f 30)` `` e `` `:tapered` `` no.
- Mai auto-link sulle parole nude della prosa: solo dentro i backtick. È questo che evita di linkare per sbaglio le parole inglesi che coincidono con simboli (`shape`, `color`, `info`, `math`, `noise`, `star`, `fit`, `span`, `mark`, `follow`, `f`, `u`). Un backtick è già un riferimento intenzionale, una parola nuda no.
- Il target generato deve essere lo stesso di T-009 (`ref:NOME` o equivalente interno), così passa per l'handler già esistente. I nomi con caratteri sanificati nei filename (`extrude+` su `extrude-plus.md`, `revolve+` su `revolve-plus.md`, `transform->` su `transform-arrow.md`) devono risolvere per nome via indice, non per filename.

## Dove si applica

- Prosa delle guide e prosa delle schede Reference: anche le descrizioni delle schede citano altri simboli, e ha senso che siano cliccabili. I "See also" card verso card restano come sono.
- Opzionale: saltare l'auto-link di un simbolo verso la propria scheda (nella scheda di `extrude` la parola `extrude` non serve che linki a se stessa).
- Heading: li escluderei, per non avere titoli cliccabili.

## Affordance visiva

I link oggi non si distinguono dal testo finché non ci passi sopra, ed è il motivo per cui non si scoprono. Con l'auto-link su tutti i nomi, un segno persistente su ogni code span sarebbe troppo. Proposta: nessun segno persistente, ma su hover cursore a pointer più un sottolineato tenue, così il lettore scopre la cliccabilità muovendo il mouse, senza che la prosa sia marcata ovunque. Decisione aperta, ma terrei l'affordance leggera.

## Note di implementazione

- Costruire un set dei nomi noti da `reference-index.cljs` per lookup O(1). L'auto-link è una passata sul Markdown già parsato, niente di costoso.
- Saltare i code span che sono già dentro un anchor, per non fare doppio wrapping su link manuali eventualmente presenti (vedi Pulizia collegata).
- Alias (es. `qp` per `quick-path`): se l'indice contiene gli alias, linkano; se no, accettiamo che gli alias non linkino. Minore, decidere in base a com'è fatto l'indice.

## Pulizia collegata

Il cap. 4 (`docs/manual/guides/it/04-extrusion.md`) contiene 17 link manuali `ref:` messi durante la calibrazione di questo meccanismo. Con l'auto-link diventano ridondanti e vanno tolti, per avere un solo meccanismo. Finché ci sono, la regola "salta i code span già dentro un anchor" evita il doppio wrapping.

## Criteri di accettazione

- In una guida, un nome di funzione in backtick è cliccabile e apre la scheda corretta; un nome inesistente o un'espressione tra backtick non lo sono.
- Le parole inglesi nella prosa, fuori dai backtick, non vengono mai linkate.
- I nomi con caratteri speciali (`extrude+`, `revolve+`, `transform->`) risolvono alla scheda giusta.
- Nessun doppio wrapping sui link già presenti.
