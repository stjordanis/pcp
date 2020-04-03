(ns pcp.core
  (:require
    [sci.core :as sci]
    [sci.addons :as addons]
    [pcp.resp :as resp]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [pcp.scgi :as scgi]
    [pcp.includes :refer [includes html]])
  (:import  [java.net URLDecoder]
            [java.net Socket SocketException InetAddress]
            [java.io BufferedWriter]) 
  (:gen-class))


(set! *warn-on-reflection* 1)

(defn read-source [path]
  (try
    (str (slurp path))
    (catch java.io.FileNotFoundException fnfe nil)))

(defn build-path [path root]
  (str root "/" path))

(defn process-includes [raw-source parent]
  (let [source raw-source
        includes-used (re-seq #"\(include\s*?\"(.*?)\"\s*?\)" source)]
    (loop [code source externals includes-used]
      (if (empty? externals)
        code
        (let [included (-> externals first second (build-path parent) read-source)]
          (if (nil? included)
            (throw 
              (ex-info (str "Included file '" (-> externals first second (build-path parent)) "' was not found.")
                        {:cause   (str (-> externals first first))}))
            (recur 
              (str/replace code (-> externals first first) included) 
              (rest externals))))))))
 
(defn format-response [status body mime-type]
  (-> (resp/response body)    
      (resp/status status)
      (resp/content-type mime-type))) 

(defn file-response [path file]
  (let [mime (resp/get-mime-type (re-find #"\.[0-9A-Za-z]{1,7}$" path))]
    (-> (resp/response file)    
        (resp/status 200)
        (resp/content-type mime))))

(defn process-script [full-source opts]
    (try
      (let [ans (sci/eval-string full-source opts)]
        ans)
      (catch Exception e  (format-response 500 (.getMessage e) nil))))

(defn run [url-path &{:keys [root params]}]
  (let [path (URLDecoder/decode url-path "UTF-8")
        source (read-source path)
        file (io/file path)
        parent (or root (-> file (.getParentFile) str))]
    (if (string? source)
      (let [opts  (-> { :namespaces includes
                        :bindings { 'pcp (sci/new-var 'pcp params)
                                    'include identity
                                    'response (sci/new-var 'response format-response)
                                    'echo #(resp/response %)
                                    'println println
                                    'slurp #(slurp (str parent "/" %))
                                    'html html}
                        :classes {'org.postgresql.jdbc.PgConnection org.postgresql.jdbc.PgConnection}}
                        (addons/future))
            full-source (process-includes source parent)]
        (process-script full-source opts))
      (format-response 404 nil nil))))

(defn scgi-handler [request]
  (let [root (:document-root request)
        doc (:document-uri request)
        path (str root doc)
        r (run path :root root :params request)
        mime (-> r :headers (get "Content-Type"))
        nl "\r\n"
        response (str (:status r) nl (str "Content-Type: " mime) nl nl (:body r))]
    response))

(defn -main 
  ([]
    (-main ""))
  ([path]       
    (let [scgi-port (Integer/parseInt (or (System/getenv "SCGI_PORT") "9000"))]
      (case path
        ""  (scgi/serve scgi-port scgi-handler)
        (run path)))))

      