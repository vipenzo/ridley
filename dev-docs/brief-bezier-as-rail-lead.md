# Brief — bezier-as e bezier-to-anchor come apertura di rail: veer analitico anche per loro

## Contesto

**Sintomo riportato** (2026-07-05): dopo il fix rail-start, il loft rifiuta un path che prima funzionava. Riproduzione:

```clojure
(path
  (bezier-as (path (tv 85) (f 60) (tv -50) (f 70) (mark :here))))
```

usato come rail da loft (stesso esito con extrude). Il guard scatta con il messaggio "begin with a turn".

**È un falso positivo, della stessa classe già curata per `bezier-to`.** In modalità default `bezier-as` fitta i control point con `compute-bezier-control-points` → `auto-control-points-with-target-heading`, il cui contratto è "The curve starts tangent to start-heading": il c1 del primo segmento della spline giace sull'heading d'ingresso **per costruzione**. Il `(tv 85)` dello scheletro sposta i waypoint, non la tangente di partenza della curva fittata. Il path di riproduzione è quindi analiticamente tangente.

Il guard scatta perché `rec-bezier-as*` (`src/ridley/editor/macros.cljs` ~268) emette le rotazioni per step con `rec-th*`/`rec-tv*` **plain** — né `:smooth` né `:bez-cap :lead`/`:veer-deg`. La finestra prima del primo `:f` entra grezza nella riduzione di frame di `validate-rail-start-frame!`, che la misura sulla **prima corda** della tessellazione: con il primo waypoint a 85° fuori asse, una curva tangente che deve raggiungerlo piega subito forte e la prima corda devia ben oltre `rail-start-frame-tol-deg`. Firma risoluzione-dipendente, come nel caso `bezier-to`.

La lacuna è tracciabile: il brief rail-start nominava `rec-bezier-to*` e `rec-bezier-to-anchor*` ma non `rec-bezier-as*`, che ha un percorso di emissione proprio. L'implementazione è stata fedele al brief; il brief era incompleto.

**Sfumatura essenziale: non tutti i modi di `bezier-as` partono tangenti.** Il veer va calcolato dal c1 effettivamente fittato, non assunto zero:

- modalità **default**: c1 lungo l'heading d'ingresso per costruzione → veer ≈ 0, deve passare sempre;
- modalità **`:cubic`**: c1 segue la direzione Catmull-Rom al primo waypoint (`catmull-rom-directions`), che in generale vira davvero;
- modalità **`:control`** (spline midpoint, `compute-midpoint-walk-impl`): la curva parte tangente al primo lato del poligono di controllo — con lo scheletro di riproduzione virerebbe di 85° sul serio, e lì il guard **deve** continuare a bocciare.

**Caso adiacente scoperto leggendo:** il ramo auto di `rec-bezier-to-anchor*` (quando non ci sono control point espliciti) emette con `rec-th-smooth*`/`rec-tv-smooth*` ma senza cap/veer: il suo c1 è lungo lo start-heading per costruzione (tangente sempre), ma il guard lo misura a corda → stesso falso positivo latente quando un `bezier-to-anchor` apre un rail con curvatura forte. Il ramo con control point espliciti delega a `rec-bezier-to*` ed è già coperto.

**Alternative considerate e rigettate:**

- *Assumere veer 0 per bezier-as* — sbagliato per `:cubic` e `:control`, che possono virare genuinamente; il guard perderebbe la capacità di bocciare rail davvero storti.
- *Allineare in questo brief tutta l'emissione di bezier-as a `:smooth`* — è un difetto reale ma distinto (vedi Fuori scope): cambierebbe il trattamento corner di tutti i modelli esistenti che estrudono `bezier-as`. Un difetto, una cura.

## Lavoro richiesto

1. `rec-bezier-as*`: le rotazioni emesse **prima del primo `rec-f*`** del primo segmento della spline vanno taggate `:bez-cap :lead` con `:veer-deg` calcolato analiticamente — angolo tra l'heading d'ingresso (`init-pose`) e la direzione c1−p0 del primo segmento *effettivamente fittato*, secondo il modo attivo (default / `:cubic` / `:control`). Le funzioni pure del walk (`compute-bezier-walk-impl`, `compute-midpoint-walk-impl`) oggi non espongono il c1 fittato: estenderle perché il dato del primo segmento sia disponibile al recorder (o equivalente scelto da Code), senza duplicare il calcolo del fitting con approssimazioni parallele.
2. `rec-bezier-to-anchor*`, ramo auto: stessa cap-taggatura della finestra pre-primo-`f`, con veer calcolato dal suo c1 (per costruzione ≈ 0: calcolarlo comunque, non hardcodarlo).
3. **Vincolo di non-regressione sulla semantica corner:** oggi le rotazioni di lead di `bezier-as` sono plain e quindi corner duri per la corner machinery (`corner-rotation?` esclude solo `:smooth`). Questo brief non deve cambiare il trattamento corner: la taggatura dev'essere visibile **solo al guard**. Gli emettitori esistenti `rec-th-smooth-cap*`/`rec-tv-smooth-cap*` impostano anche `:smooth true`: per `bezier-as` servono varianti che NON impostino `:smooth` (o, se il guard oggi esclude dalla riduzione solo finestre `:smooth`, adeguare il guard a chiavare su `:bez-cap :lead`). L'output mesh dei modelli esistenti deve restare identico, salvo i casi che prima non producevano mesh affatto perché bocciati dal falso positivo. Per `rec-bezier-to-anchor*` il lead è già `:smooth` oggi: lì gli emettitori cap esistenti vanno bene.
4. Aggiungere a `dev-docs/code-issues.md` l'accertamento separato: l'emissione di `bezier-as` non è `:smooth`, quindi ogni step di tessellazione è un corner duro per l'estrusione — incoerenza strutturale con `bezier-to`, probabile difetto latente; allinearla cambia le mesh esistenti e va misurata prima di curarla.
5. Coordinamento con `brief-bezier-canonical-frame.md` (se non ancora implementato): i due lavori sono indipendenti e questo può atterrare prima; il lavoro sul frame canonico, quando toccherà `rec-bezier-as*`, deve preservare la cap-taggatura introdotta qui.

## Verifica

- Rete prima del codice: test scritti prima del fix, che falliscono sul codice attuale.
- Il path di riproduzione è accettato sia da loft che da extrude, a resolution 4, 16 e 64.
- Default mode: un `bezier-as` che apre un rail passa il guard qualunque sia la rotazione iniziale dello scheletro (la tangente di partenza non dipende dallo scheletro).
- `:control` mode: lo stesso scheletro di riproduzione con `:control` resta bocciato con il messaggio rail-start (il primo lato del poligono vira di 85°).
- `:cubic` mode: un caso costruito in cui la direzione Catmull-Rom al primo waypoint vira oltre tolleranza resta bocciato.
- `bezier-to-anchor` (ramo auto) che apre un rail verso un anchor molto fuori asse passa il guard a bassa risoluzione.
- Non-regressione corner: le mesh pinnate esistenti che estrudono `bezier-as` in mezzo al path restano byte-identiche (la taggatura non tocca il trattamento corner).
- I test del brief rail-start e, se già atterrato, del frame canonico restano verdi.
- Suite completa: 0 failures.

## Fuori scope

- L'allineamento dell'emissione di `bezier-as` a `:smooth` (l'accertamento del punto 4): difetto distinto, con impatto sulle mesh esistenti, da misurare prima.
- Qualunque modifica alla geometria del fitting (`compute-bezier-control-points`, `catmull-rom-directions`, spline midpoint).
- Il guard in sé: `validate-rail-start-frame!` non cambia semantica, salvo l'eventuale adeguamento della chiave di esclusione al punto 3.
- L'API utente di `bezier-as`/`bezier-to-anchor`.
