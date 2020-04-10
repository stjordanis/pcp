(ns pcp.core
  (:require
    [sci.core :as sci]
    [sci.addons :as addons]
    [pcp.resp :as resp]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [pcp.scgi :as scgi]
    [pcp.includes :refer [includes html]]
    [selmer.parser :as parser]
    [ring.middleware.defaults :refer :all]
    [ring.middleware.params :refer [wrap-params]]
    [ring.middleware.multipart-params :refer [wrap-multipart-params]]
    [ring.middleware.lint :as lint])
  (:import [java.net URLDecoder]
           [java.io File]) 
  (:gen-class))


(set! *warn-on-reflection* 1)

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

(defn read-source [path]
  (try
    (str (slurp path))
    (catch java.io.FileNotFoundException fnfe nil)))

(defn build-path [path root]
  (str root "/" path))

(defn clean-source [source]
  (str/replace source #"\u003B.*\n" ""))

(defn process-includes [raw-source parent]
  (let [source (clean-source raw-source)
        includes-used (re-seq #"\(use\s*?\"(.*?)\"\s*?\)" source)]
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

(defn process-script [full-source opts]
  (sci/eval-string full-source opts))

(defn longer [str1 str2]
  (if (> (count str1) (count str2)) str1 str2))

(defn run-script [url-path &{:keys [root params]}]
  (let [path (URLDecoder/decode url-path "UTF-8")
        source (read-source path)
        file (io/file path)
        parent (longer root (-> ^File file (.getParentFile) str))]
    (if (string? source)
      (let [opts  (-> { :namespaces (merge includes {'pcp { 'params #(identity params)
                                                            'response format-response
                                                            'html html}})
                        :bindings {'println println 'use identity 'slurp #(slurp (str parent "/" %))}
                        :classes {'org.postgresql.jdbc.PgConnection org.postgresql.jdbc.PgConnection}}
                        (addons/future))
            _ (parser/set-resource-path! root)                        
            full-source (process-includes source parent)
            result (process-script full-source opts)
            _ (selmer.parser/set-resource-path! nil)]
        result)
      nil)))

(defn extract-multipart [req]
  (let [body (:body req)
        content-type-string (:content-type req)
        content-type (second (re-find #"(.*)\u003B" content-type-string))]
    (if (str/includes? content-type "multipart")
      (let [boundary (str "--" (second (re-find #"boundary=(.*)$" content-type-string)))
            real-body (str/replace body (re-pattern (str boundary "--.*")) "")
            parts (filter #(seq %) (str/split real-body (re-pattern boundary)))
            form  (for [part parts]
                    {(keyword (second (re-find #"Content-Disposition: form-data\u003B name=\"(.*)\"\r\n" part)))
                     {:filename (second (re-find #"Content-Disposition: form-data\u003B.*filename=\"(.*)\"\r\n" part))
                      :type (second (re-find #"Content-Type: (.*)\r\n" part))
                      :tempfile nil
                      :size nil}})
            ]       
        (println form)
        req)
      req)))

(defn scgi-handler [req]
  (let [request req
        ;_ (println (dissoc request :body))
        root (:document-root request)
        doc (:document-uri request)
        path (str root doc)
        r (try (run-script path :root root :params request) (catch Exception e  (format-response 500 (.getMessage e) nil)))]
    r))

(defn wrappers [handler]
  (-> handler
      (wrap-defaults
       (-> site-defaults
           ;(assoc-in [:headers  "Cache-Control"] "max-age=7200")
           ;(assoc-in [:headers  "Pragma"] "max-age=7200")
           (assoc-in [:security :anti-forgery] false)
           (dissoc :session)))
      (wrap-params)
      (wrap-multipart-params)))

(defn -main 
  ([]
    (-main ""))
  ([path]       
    (let [scgi-port (Integer/parseInt (or (System/getenv "SCGI_PORT") "9000"))]
      (case path
        ;""  (scgi/serve (lint/wrap-lint scgi-handler) scgi-port)
        ""  (scgi/serve (wrappers scgi-handler) scgi-port)
        (run-script path)))))

      