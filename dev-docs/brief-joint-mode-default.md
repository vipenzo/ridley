# Brief: cambiare il default di joint-mode da :flat a :tapered

## Contesto

Il default corrente di `joint-mode` è `:flat`, che produce spigoli vivi nei cambi di direzione delle estrusioni. Il risultato è visivamente sgradevole nella maggior parte dei casi, e praticamente tutti gli script non banali finiscono per specificare `(joint-mode :tapered)` o `(joint-mode :round)`. Ha più senso partire da un default che produce risultati accettabili senza intervento esplicito.

## Lavoro richiesto

Cambiare il valore di default di `joint-mode` da `:flat` a `:tapered`.

Il punto nel codice dovrebbe essere il valore iniziale di joint-mode nello stato della tartaruga.

## Verifica

- Un'estrusione con cambio di direzione senza `joint-mode` esplicito deve produrre una giunzione a bisello (`:tapered`), non uno spigolo vivo.
- `(joint-mode :flat)` deve continuare a funzionare come override esplicito.
- `(joint-mode :round)` invariato.
- Non ci devono essere regressioni sugli script esistenti che già specificano `joint-mode` esplicitamente.

## Fuori scope

- Aggiungere joint-mode alle Settings UI (decisione futura).
- Modificare il comportamento delle tre modalità.
