# Brief: alzare il default di resolution da 16 a 64

## Contesto

Il default corrente di `(resolution :n ...)` è 16 segmenti. Con quel valore sfere, cilindri e coni appaiono visibilmente sfaccettati, e praticamente tutti gli script non banali cominciano con `(resolution :n 64)`. Ha più senso partire da un default che produce risultati accettabili senza intervento esplicito.

## Lavoro richiesto

Cambiare il valore di default di resolution da 16 a 64.

Il punto nel codice dovrebbe essere il valore iniziale di resolution nello stato della tartaruga (probabilmente in `state.cljs` o dove viene inizializzato `*turtle-state*`).

## Verifica

- Uno script vuoto con solo `(register s (sphere 10))` deve produrre una sfera a 64 segmenti.
- `(resolution :n 8)` deve continuare a funzionare come override esplicito.
- Non ci devono essere regressioni sugli esempi esistenti (quelli che già specificano `(resolution :n 64)` non cambiano comportamento; quelli senza resolution esplicita avranno più geometria ma dovrebbero restare corretti).

## Performance

Il passaggio da 16 a 64 segmenti aumenta il numero di triangoli delle primitive curve. Sulla macchina di sviluppo l'impatto è trascurabile. Se ci fossero problemi di performance su modelli complessi, l'utente può sempre abbassare la resolution esplicitamente.

## Fuori scope

- Cambiare il default di `loft` (che resta 16 passi, controllato da `loft-n`).
- Cambiare il default di `loft-n` (separato da resolution).
