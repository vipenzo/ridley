# Reference manual — working folder

Cartella di lavoro per le schede della Reference del manuale Ridley, in
transito verso la struttura finale definita dal Flusso B (vedi
`manual-redesign-plan.md`).

## Convenzione

- **Un file = una scheda Reference.**
- **Lingua: inglese.** L'EN è source-of-truth per la Reference (vedi §5.1 del
  piano). La versione IT sarà prodotta in un task separato quando il workflow
  di traduzione sarà fissato.
- **Frontmatter YAML obbligatorio**: `name`, `category` (slug stabile), `since`,
  `status`.
- **Struttura della cartella:**
  - schede della Reference standard → piatte nella root di
    `dev-docs/reference-manual/`.
  - schede della Reference Internals → in `dev-docs/reference-manual/internals/`.
- **Scheda di riferimento per lo schema**: `loft.md`. Le nuove schede ne
  imitano l'organizzazione (sezioni `Signature`, `Description`, `Parameters`,
  `Example`, `Variations`, `Notes`, `See also`).

## Status

Questa cartella è materiale d'autore in transito. La struttura definitiva
delle schede nel manuale finale (path, naming, formato esempi) è decisione
del Flusso B (brief B per Code). Le schede prodotte qui verranno migrate
quando l'infrastruttura sarà pronta.