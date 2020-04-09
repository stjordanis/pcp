(ns pcp.utility
  (:require
    [pcp.resp :as resp]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [clojure.java.shell :as shell]
    [org.httpkit.server :as server])
  (:import  [java.net Socket]
            [java.io File BufferedWriter]) 
  (:gen-class))

(set! *warn-on-reflection* 1)

(def root (atom nil))
(def scgi (atom "9000"))
(def version "v0.0.1-beta.9")

(defn http-to-scgi [req]
  (let [header (walk/keywordize-keys (or (:headers req) {"Content-type" "text/plain"}))]
    (str
      "REQUEST_METHOD\0" (-> req :request-method name str/upper-case)  "\n"
      "REQUEST_URI\0" (-> req :uri) "\n"
      "QUERY_STRING\0" (-> req :query-string) "\n"
      "CONTENT_TYPE\0" (-> req :content-type) "\n"
      "DOCUMENT_URI\0" (-> req :document-uri) "\n"
      "DOCUMENT_ROOT\0" (-> req :document-root) "\n"
      "SCGI\0" 1 "\n"
      "SERVER_PROTOCOL\0" (-> req :protocol) "\n"
      "REQUEST_SCHEME\0" (-> req :scheme) "\n"
      "HTTPS\0" (-> req :name) "\n"
      "REMOTE_ADDR\0" (-> req :remote-addr) "\n"
      "REMOTE_PORT\0" (-> req :name) "\n"
      "SERVER_PORT\0" (-> req :server-port) "\n"
      "SERVER_NAME\0" (-> req :server-name) "\n"
      "HTTP_CONNECTION\0" (-> header :connection) "\n"
      "HTTP_CACHE_CONTROL\0" (-> header :cache-control) "\n"
      "HTTP_UPGRADE_INSECURE_REQUESTS\0" (-> header :upgrade-insecure-requests) "\n"
      "HTTP_USER_AGENT\0" (-> header :user-agent) "\n"
      "HTTP_SEC_FETCH_DEST\0" (-> header :sec-fetch-dest) "\n"
      "HTTP_ACCEPT\0" (-> header :cookie) "\n"
      "HTTP_SEC_FETCH_SITE\0" (-> header :sec-fetch-site) "\n"
      "HTTP_SEC_FETCH_MODE\0" (-> header :sec-fetch-mode) "\n"
      "HTTP_SEC_FETCH_USER\0" (-> header :sec-fetch-user) "\n"
      "HTTP_ACCEPT_ENCODING\0" (-> header :accept-encoding) "\n"
      "HTTP_ACCEPT_LANGUAGE\0" (-> header :accept-language) "\n"
      "HTTP_COOKIE\0" (-> header :cookie) "\n"
      "\n,")))

(def help 
"PCP: Clojure Processor -- Like drugs but better

Usage: pcp [option] [value]

Options:
  service [stop/start]    Stop/start the PCP SCGI server daemon
  -e, --evaluate [path]   Evaluate a clojure file using PCP
  -s, --serve [root]      Start a local server at . or [root]
  -v, --version           Print the version string and exit
  -h, --help              Print the command line help")

(defn receive! [socket]
  (let [rdr (io/reader socket)]
    (slurp rdr)))

(defn send! [^Socket socket ^String msg]
  (let [^BufferedWriter writer (io/writer socket)] 
    (.write writer msg 0 ^Integer (count msg))
    (.flush writer)))

(defn forward [scgi-req scgi-port]
  (let [socket (Socket. "127.0.0.1" ^Integer scgi-port)]
        (send! socket scgi-req)
        (let [ans (receive! socket)]
          ans)))

(defn format-response [status body mime-type]
  (-> (resp/response body)    
      (resp/status status)
      (resp/content-type mime-type))) 

(defn file-response [path ^File file]
  (let [code (if (.exists file) 200 404)
        mime (resp/get-mime-type (re-find #"\.[0-9A-Za-z]{1,7}$" path))]
    (-> (resp/response file)    
        (resp/status code)
        (resp/content-type mime))))

(defn create-resp [scgi-response]
  (let [resp-array (str/split scgi-response #"\r\n")
        resp-status (first resp-array)
        status (Integer/parseInt (if (empty? resp-status) "404" resp-status))
        body (str/join "\n" (-> resp-array rest rest))
        mime (second (re-find #"Content-Type: (.*)$" (second resp-array)))
        final-resp (format-response status body mime)]
    final-resp))

(defn file-exists? [path]
  (-> path io/file .exists))
  
(defn serve-file [path]
  (file-response path (io/file path)))

(defn local-handler [opts]
  (fn [request]
    (let [root (.getCanonicalPath (io/file (:root opts)))
          path (str root (:uri request))
          slashpath (str path "index.clj")
          exists (or (file-exists? path) (file-exists? slashpath))
          not-found (str root "/404.clj")
          full (assoc request 
                    :document-root root 
                    :document-uri (if (str/ends-with? (:uri request) "/") (str (:uri request) "index.clj") (:uri request)))]
        (cond 
          (and (str/ends-with? (:document-uri full) ".clj") exists)
            (-> full http-to-scgi (forward (:scgi-port opts)) create-resp)
          exists 
            (serve-file path)
          (file-exists? not-found)
            (-> (assoc full :document-uri "/404.clj") http-to-scgi (forward (:scgi-port opts)) create-resp)
          :else (format-response 404 nil nil)))))

(defn run-file [path port]
  (let [path (str/replace (str "/" path) "//" "/")
        root (.getCanonicalPath (io/file "./"))
        scgi-port (Integer/parseInt (or (System/getenv "SCGI_PORT") (str port) "9000"))
        request {:document-root root :document-uri path :request-method :get}]
    (-> request http-to-scgi (forward scgi-port) create-resp)))

(defn start-local-server [options] 
  (let [opts (merge 
              {:port (Integer/parseInt (or (System/getenv "PORT") "3000")) 
               :root "./" 
               :scgi-port (Integer/parseInt (or (System/getenv "SCGI_PORT") "9000"))}
              options)
        server (server/run-server (local-handler opts) {:ip "127.0.0.1" :port (:port opts)})]
    (println "Targeting SCGI server on port" (:scgi-port opts))
    (println (str "Local server started on http://127.0.0.1:" (:port opts)))
    (println "Serving" (:root opts))
    
    server))

(def linux? 
  (-> "os.name" System/getProperty str/lower-case (str/includes? "linux")))

(defn process-service-output [output]
  (let [err (:err output)]
    (if (empty? err) "success!" (str "failed: " err))))

(defn process-query-output [output]
  (let [ans (:out output)]
    (if (or (str/includes? ans "pcp.service") (str/includes? ans "com.alekcz.pcp")) 
      "running" "stopped")))

(defn start-scgi []
  (if linux?
    (process-service-output  
      (shell/sh "systemctl" "start" "pcp.service"))
    (process-service-output  
      (shell/sh "launchctl" "load" "-w" (str (System/getProperty "user.home") "/Library/LaunchAgents/com.alekcz.pcp.plist")))))

(defn stop-scgi []
  (if linux?
    (process-service-output 
      (shell/sh "systemctl" "stop" "pcp.service"))
    (process-service-output 
      (shell/sh "launchctl" "unload" (str (System/getProperty "user.home") "/Library/LaunchAgents/com.alekcz.pcp.plist")))))

(defn query-scgi []
  (if linux?
    (process-query-output   
      (shell/sh "systemctl" "list-units" "--type=service" "--state=running"))
    (process-query-output 
      (shell/sh "launchctl" "list"))))

(defn -main 
  ([]
    (-main "" ""))
  ([path]       
    (-main path ""))
  ([option value]    
    (case option
      "-s" (start-local-server {:root value})
      "--serve" (start-local-server {:root value})
      "-v" (println "pcp" version)
      "--version" (println "pcp" version)
      "-e" (println (run-file value 9000))
      "--evaluate" (println (run-file value 9000))
      "service" (case value 
                  "start" (do (println (start-scgi)) (shutdown-agents)) ;tests suites that touch this line will fail
                  "stop"  (do (println (stop-scgi)) (shutdown-agents))  ;shutdown-agents brings the house of cards 
                  "status"  (do (println (query-scgi)) (shutdown-agents))  ;crashing down.
                  (do                                                   
                    (println "unknown command:" value)
                    (println help)))
      "" (println help)
      (println help))))                               
                  

      