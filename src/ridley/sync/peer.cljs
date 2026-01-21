(ns ridley.sync.peer
  "WebRTC peer-to-peer sync for desktop-to-headset communication.
   Uses PeerJS for simplified WebRTC DataChannel connections.
   PeerJS is loaded via CDN in index.html."
  (:require [clojure.string :as str]
            ["qrcode" :as qrcode]))

;; State
(defonce peer-state (atom {:peer nil
                           :connections #{}   ; Set of connections (for multi-client host)
                           :connection nil    ; Single connection (for client)
                           :role nil          ; :host or :client
                           :status :disconnected
                           :peer-id nil
                           :on-script-received nil
                           :on-status-change nil
                           :on-clients-change nil}))  ; Callback when client count changes

(defn- generate-short-code
  "Generate a short 6-character alphanumeric code (easy to type on Quest)."
  []
  (let [chars "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  ; No I,O,0,1 to avoid confusion
        code (apply str (repeatedly 6 #(nth chars (rand-int (count chars)))))]
    (str "ridley-" code)))

(defn- generate-peer-id []
  (generate-short-code))

(defn get-short-code
  "Extract short code from peer ID (remove 'ridley-' prefix)."
  [peer-id]
  (when peer-id
    (if (str/starts-with? peer-id "ridley-")
      (subs peer-id 7)
      peer-id)))

(defn peer-id-from-code
  "Convert short code to full peer ID."
  [code]
  (let [code-upper (str/upper-case (str/trim code))]
    (if (str/starts-with? code-upper "RIDLEY-")
      code-upper
      (str "ridley-" code-upper))))

(defn- set-status! [status]
  (swap! peer-state assoc :status status)
  (when-let [callback (:on-status-change @peer-state)]
    (callback status)))

(defn- notify-clients-change! []
  (when-let [callback (:on-clients-change @peer-state)]
    (callback (count (:connections @peer-state)))))

(defn- notify-client-connected! [^js conn]
  (when-let [callback (:on-client-connected @peer-state)]
    (callback conn)))

(defn- setup-host-connection-handlers
  "Setup handlers for a connection on the host side (supports multiple clients)."
  [^js conn]
  (.on conn "open"
       (fn []
         (swap! peer-state update :connections conj conn)
         (set-status! :connected)
         (notify-clients-change!)
         (notify-client-connected! conn)))

  (.on conn "data"
       (fn [data]
         (let [msg (js->clj data :keywordize-keys true)
               msg-type (name (:type msg))]
           (case msg-type
             "script-ack" nil
             "pong" nil
             nil))))

  (.on conn "close"
       (fn []
         (swap! peer-state update :connections disj conn)
         (notify-clients-change!)
         (when (empty? (:connections @peer-state))
           (set-status! :waiting))))

  (.on conn "error"
       (fn [_err]
         (swap! peer-state update :connections disj conn)
         (notify-clients-change!))))

(defn- setup-client-connection-handlers
  "Setup handlers for a connection on the client side."
  [^js conn]
  (.on conn "open"
       (fn []
         (set-status! :connected)
         (swap! peer-state assoc :connection conn)))

  (.on conn "data"
       (fn [data]
         (let [msg (js->clj data :keywordize-keys true)
               msg-type (name (:type msg))]
           (case msg-type
             "script-update"
             (do
               (when-let [callback (:on-script-received @peer-state)]
                 (callback (:definitions msg)))
               (.send conn (clj->js {:type "script-ack" :timestamp (.now js/Date)})))

             "ping"
             (.send conn (clj->js {:type "pong"}))

             nil))))

  (.on conn "close"
       (fn []
         (set-status! :disconnected)
         (swap! peer-state assoc :connection nil)))

  (.on conn "error"
       (fn [_err]
         (set-status! :error))))

;; Host functions (desktop)

;; PeerJS configuration
(def peer-config
  #js {:debug 0})

(defn host-session
  "Start hosting a sync session. Returns peer ID for clients to connect.
   Supports multiple clients connecting simultaneously.
   Options:
   - :on-script-received (fn [definitions]) - called when client sends script
   - :on-status-change (fn [status]) - called when status changes
   - :on-clients-change (fn [count]) - called when number of clients changes
   - :on-client-connected (fn [conn]) - called when a new client connects (to send initial script)"
  [& {:keys [on-script-received on-status-change on-clients-change on-client-connected]}]
  (let [peer-id (generate-peer-id)
        peer (js/Peer. peer-id peer-config)]

    (swap! peer-state assoc
           :peer peer
           :peer-id peer-id
           :role :host
           :connections #{}
           :on-script-received on-script-received
           :on-status-change on-status-change
           :on-clients-change on-clients-change
           :on-client-connected on-client-connected)

    (set-status! :waiting)

    (.on peer "open" (fn [_id]))

    (.on peer "connection"
         (fn [conn]
           (setup-host-connection-handlers conn)
           (when (.-open conn)
             (swap! peer-state update :connections conj conn)
             (set-status! :connected)
             (notify-clients-change!)
             (notify-client-connected! conn))))

    (.on peer "error"
         (fn [_err]
           (set-status! :error)))

    peer-id))

(defn stop-hosting
  "Stop hosting and disconnect all clients."
  []
  ;; Close all client connections
  (doseq [conn (:connections @peer-state)]
    (.close conn))
  ;; Close single connection (if client)
  (when-let [conn (:connection @peer-state)]
    (.close conn))
  (when-let [peer (:peer @peer-state)]
    (.destroy peer))
  (reset! peer-state {:peer nil
                      :connections #{}
                      :connection nil
                      :role nil
                      :status :disconnected
                      :peer-id nil
                      :on-script-received nil
                      :on-status-change nil
                      :on-clients-change nil
                      :on-client-connected nil}))

;; Client functions (headset)

(defn join-session
  "Join an existing sync session by peer ID.
   Options:
   - :on-script-received (fn [definitions]) - called when host sends script
   - :on-status-change (fn [status]) - called when status changes"
  [host-peer-id & {:keys [on-script-received on-status-change]}]
  (let [peer (js/Peer. peer-config)]

    (swap! peer-state assoc
           :peer peer
           :role :client
           :on-script-received on-script-received
           :on-status-change on-status-change)

    (set-status! :connecting)

    (.on peer "open"
         (fn [_my-id]
           (let [conn (.connect peer host-peer-id)]
             (setup-client-connection-handlers conn))))

    (.on peer "error"
         (fn [_err]
           (set-status! :error)))))

(defn leave-session
  "Leave the current session."
  []
  (stop-hosting))  ; Same cleanup logic

;; Send functions

(defn send-script
  "Send script update to all connected clients (host only)."
  [definitions]
  (let [connections (:connections @peer-state)
        msg (clj->js {:type "script-update"
                      :definitions definitions
                      :timestamp (.now js/Date)})]
    (when (seq connections)
      (doseq [^js conn connections]
        (when (.-open conn)
          (.send conn msg))))))

(defn send-script-to-connection
  "Send script update to a specific connection (for new client sync)."
  [^js conn definitions]
  (when (and conn (.-open conn))
    (.send conn (clj->js {:type "script-update"
                          :definitions definitions
                          :timestamp (.now js/Date)}))))

(defn send-ping
  "Send keepalive ping to all connected clients."
  []
  (doseq [^js conn (:connections @peer-state)]
    (when (.-open conn)
      (.send conn (clj->js {:type "ping"})))))

;; Status queries

(defn connected?
  "Check if currently connected to a peer."
  []
  (= :connected (:status @peer-state)))

(defn hosting?
  "Check if currently hosting a session."
  []
  (and (= :host (:role @peer-state))
       (some? (:peer @peer-state))))

(defn get-peer-id
  "Get current peer ID (for host) or nil."
  []
  (:peer-id @peer-state))

(defn get-status
  "Get current connection status."
  []
  (:status @peer-state))

(defn get-client-count
  "Get number of connected clients (host only)."
  []
  (count (:connections @peer-state)))

;; QR Code generation

(defn generate-share-url
  "Generate the share URL for current session."
  [base-url peer-id]
  (str base-url "?peer=" peer-id))

(defn generate-qr-code
  "Generate QR code data URL for share URL.
   Returns a promise that resolves to a data URL string."
  [share-url]
  (js/Promise.
   (fn [resolve reject]
     (.toDataURL qrcode share-url
                 #js {:width 200
                      :margin 2
                      :color #js {:dark "#000000"
                                  :light "#ffffff"}}
                 (fn [err url]
                   (if err
                     (reject err)
                     (resolve url)))))))

;; URL parameter helpers

(defn get-peer-from-url
  "Extract peer ID from URL query parameter."
  []
  (let [params (js/URLSearchParams. (.-search js/location))]
    (.get params "peer")))

(defn clear-peer-from-url
  "Remove peer parameter from URL without reloading."
  []
  (let [url (js/URL. js/location.href)]
    (.delete (.-searchParams url) "peer")
    (.replaceState js/history nil "" (str url))))
