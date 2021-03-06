(ns pcp.includes-test
  (:require [clojure.test :refer :all]
            [pcp.includes :as includes]))

(deftest extract-namespace-test
  (testing "Test extracting namespaces"
    (let [result (includes/extract-namespace 'pcp.includes)
          ans {'html #'pcp.includes/html, 'includes #'pcp.includes/includes, 'extract-namespace #'pcp.includes/extract-namespace}]
      (is (= ans result)))))

(deftest html-test
  (testing "Test hiccup lite with html"
    (let [result (includes/html [:div {:class "test" :editable nil} "pcp"])]
      (is (= "<div class=\"test\" editable>pcp</div>\n" result)))))

(deftest html-2-test
  (testing "Test hiccup lite with html"
    (let [result (includes/html [:div [:input {:type "text"}] "pcp"])]
      (is (= "<div><input type=\"text\"></input>\n pcp</div>\n" result)))))