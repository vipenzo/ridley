(ns ridley.voice.i18n
  "Voice command translations and pronunciation variants for IT/EN.")

(def voice-commands
  {:modes
   {:structure {:it ["modo struttura" "struttura"]
                :en ["structure mode" "structure"]}
    :turtle    {:it ["modo turtle" "modo tartaruga" "tartaruga"]
                :en ["turtle mode" "turtle"]}
    :ai        {:it ["modo ai" "modo intelligenza artificiale"]
                :en ["ai mode"]}
    :help      {:it ["aiuto" "help"]
                :en ["help"]}}

   :navigation
   {:next     {:it ["prossimo" "prossima" "prossime" "prossimi" "avanti" "successivo" "successiva"]
               :en ["next" "forward"]}
    :previous {:it ["precedente" "precedenti" "indietro"]
               :en ["previous" "back"]}
    :into     {:it ["dentro" "entra"]
               :en ["into" "down" "enter"]}
    :out      {:it ["fuori" "esci" "su"]
               :en ["out" "up" "exit"]}
    :first    {:it ["primo" "inizio"]
               :en ["first" "start" "home"]}
    :last     {:it ["ultimo" "fine"]
               :en ["last" "end"]}
    :top      {:it ["radice" "cima" "inizio documento"]
               :en ["top" "root" "beginning"]}}

   :editing
   {:delete {:it ["cancella" "elimina"]
             :en ["delete" "remove"]}
    :copy   {:it ["copia"]
             :en ["copy"]}
    :cut    {:it ["taglia"]
             :en ["cut"]}
    :paste  {:it ["incolla"]
             :en ["paste"]}
    :change {:it ["cambia" "sostituisci" "metti"]
             :en ["change" "replace" "set"]}}

   :structural
   {:slurp  {:it ["slurp" "slarp" "slap" "slerp" "slop" "sloop" "assorbi" "ingloba"]
             :en ["slurp"]}
    :barf   {:it ["barf" "baf" "barb" "espelli"]
             :en ["barf"]}
    :wrap   {:it ["wrap" "rap" "vrap" "avvolgi"]
             :en ["wrap"]}
    :unwrap {:it ["unwrap" "anvrap" "anrap" "unrap" "unrat" "svolgi"]
             :en ["unwrap"]}
    :raise  {:it ["estrai" "alza"]
             :en ["raise" "extract"]}
    :join   {:it ["unisci" "fondi"]
             :en ["join" "merge"]}}

   :insertion
   {:insert     {:it ["inserisci"]
                 :en ["insert"]}
    :append     {:it ["aggiungi"]
                 :en ["append" "add"]}
    :new-form   {:it ["nuova forma" "nuova form" "nuova funzione"]
                 :en ["new form" "new function"]}
    :new-list   {:it ["nuova lista" "nuovo vettore"]
                 :en ["new list" "new vector"]}
    :new-map    {:it ["nuova mappa"]
                 :en ["new map"]}}

   :meta
   {:undo {:it ["annulla"]
           :en ["undo"]}
    :redo {:it ["ripeti" "rifai"]
           :en ["redo"]}
    :run  {:it ["esegui" "valuta"]
           :en ["run" "eval" "execute"]}
    :stop {:it ["basta" "stop" "fermo" "fermati"]
           :en ["stop" "enough" "halt"]}}

   :dictation
   {:enter {:it ["scrivi"]
            :en ["type" "dictate"]}}

   :selection
   {:enter {:it ["seleziona"]
            :en ["select"]}
    :exit  {:it ["esci" "annulla"]
            :en ["exit" "cancel"]}}

   :units
   {:word {:it ["parola" "parole"]
           :en ["word" "words"]}
    :line {:it ["riga" "righe" "linea" "linee"]
           :en ["line" "lines"]}
    :char {:it ["carattere" "caratteri"]
           :en ["character" "characters" "char" "chars"]}}

   :positions
   {:before {:it ["prima"]
             :en ["before"]}
    :after  {:it ["dopo"]
             :en ["after"]}}

   :prepositions
   {:to {:it ["in" "a"]
         :en ["to"]}}

   :transforms
   {:keyword    {:it ["keyword" "chiave"]
                 :en ["keyword"]}
    :symbol     {:it ["simbolo"]
                 :en ["symbol"]}
    :hash       {:it ["cancelletto" "hash"]
                 :en ["hash"]}
    :deref      {:it ["chiocciola" "deref"]
                 :en ["deref" "at sign"]}
    :capitalize {:it ["maiuscolo" "maiuscola"]
                 :en ["capitalize" "cap"]}
    :uppercase  {:it ["tutto maiuscolo"]
                 :en ["uppercase" "all caps"]}
    :number     {:it ["numero"]
                 :en ["number"]}}

   :help-browse
   {:ridley           {:it ["ridley"] :en ["ridley"]}
    :clojure-base     {:it ["clojure" "clojure base"] :en ["clojure" "clojure base"]}
    :clojure-extended {:it ["esteso" "clojure esteso" "avanzato"] :en ["extended" "clojure extended" "advanced"]}}

   :language
   {:it {:it ["lingua italiana" "italiano"]
         :en ["italian"]}
    :en {:it ["lingua inglese" "inglese"]
         :en ["english"]}}})

(def numbers
  "Cardinal number words."
  {:it {"zero" 0 "uno" 1 "una" 1 "due" 2 "tre" 3 "quattro" 4 "cinque" 5
        "sei" 6 "sette" 7 "otto" 8 "nove" 9 "dieci" 10
        "undici" 11 "dodici" 12 "tredici" 13 "quattordici" 14 "quindici" 15
        "sedici" 16 "diciassette" 17 "diciotto" 18 "diciannove" 19
        "venti" 20 "trenta" 30 "quaranta" 40 "cinquanta" 50
        "sessanta" 60 "settanta" 70 "ottanta" 80 "novanta" 90 "cento" 100}
   :en {"zero" 0 "one" 1 "two" 2 "three" 3 "four" 4 "five" 5
        "six" 6 "seven" 7 "eight" 8 "nine" 9 "ten" 10
        "eleven" 11 "twelve" 12 "twenty" 20 "thirty" 30 "forty" 40
        "fifty" 50 "sixty" 60 "seventy" 70 "eighty" 80 "ninety" 90
        "hundred" 100}})

(def ordinals
  "Ordinal number words mapped to element indices."
  {:it {"primo" 1 "seconda" 2 "secondo" 2 "terzo" 3 "quarto" 4
        "quinto" 5 "sesto" 6 "settimo" 7 "ottavo" 8
        "nono" 9 "decimo" 10 "ultimo" -1}
   :en {"first" 1 "second" 2 "third" 3 "fourth" 4
        "fifth" 5 "sixth" 6 "seventh" 7 "eighth" 8
        "ninth" 9 "tenth" 10 "last" -1}})

(def fillers
  "Words to strip from input before matching."
  {:it #{"di" "per" "un" "una" "il" "la" "lo" "del" "della" "in" "a" "le" "i" "gli"}
   :en #{"of" "by" "the" "a" "an" "to" "in"}})

(def select-targets
  "Immediate selection targets."
  {:it {"parola" :word "riga" :line "tutto" :all}
   :en {"word" :word "line" :line "all" :all}})

(def negative-words
  "Words that mean 'negative/minus' — used by :number transform."
  {:it #{"meno" "minus"}
   :en #{"minus" "negative"}})

(def command-delimiters
  "Words that separate chained commands in continuous listening mode.
   Also used as silence-equivalent boundaries."
  {:it #{"poi" "vai" "e poi" "quindi"}
   :en #{"then" "go" "and then"}})
