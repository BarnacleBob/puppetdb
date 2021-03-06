(ns com.puppetlabs.puppetdb.test.http.v4.event-counts
  (:require [com.puppetlabs.http :as pl-http]
            [cheshire.core :as json])
  (:use clojure.test
        [clj-time.core :only [now]]
        com.puppetlabs.puppetdb.fixtures
        com.puppetlabs.puppetdb.examples.reports
        [com.puppetlabs.puppetdb.testutils.event-counts :only [get-response]]
        [com.puppetlabs.puppetdb.testutils :only [response-equal? paged-results]]
        [com.puppetlabs.puppetdb.testutils.reports :only [store-example-report!]]))

(def endpoint "/v4/event-counts")

(use-fixtures :each with-test-db with-http-app)

(deftest query-event-counts
  (store-example-report! (:basic reports) (now))

  (testing "summarize-by rejects unsupported values"
    (let [response  (get-response endpoint
                                  ["=" "certname" "foo.local"] "illegal-summarize-by" {} true)
          body      (get response :body "null")]
      (is (= (:status response) pl-http/status-bad-request))
      (is (re-find #"Unsupported value for 'summarize-by': 'illegal-summarize-by'" body))))

  (testing "count-by rejects unsupported values"
    (let [response  (get-response endpoint
                                  ["=" "certname" "foo.local"] "certname"
                                  {"count-by" "illegal-count-by"} true)
          body      (get response :body "null")]
      (is (= (:status response) pl-http/status-bad-request))
      (is (re-find #"Unsupported value for 'count-by': 'illegal-count-by'" body))))

  (testing "nontrivial query using all the optional parameters"
    (let [expected  #{{:subject-type "containing-class"
                       :subject {:title "Foo"}
                       :failures 0
                       :successes 0
                       :noops 0
                       :skips 1}}
          response  (get-response endpoint
                                  ["or" ["=" "status" "success"] ["=" "status" "skipped"]]
                                  "containing-class"
                                  {"count-by"      "certname"
                                   "counts-filter" ["<" "successes" 1]})]
      (response-equal? response expected)))

  (doseq [[label count?] [["without" false]
                          ["with" true]]]
    (testing (str "should support paging through event-counts " label " counts")
      (let [expected  #{{:subject-type "resource"
                         :subject {:type "Notify" :title "notify, yar"}
                         :failures        0
                         :successes       1
                         :noops           0
                         :skips           0}
                        {:subject-type "resource"
                         :subject {:type "Notify" :title "notify, yo"}
                         :failures        0
                         :successes       1
                         :noops           0
                         :skips           0}
                        {:subject-type "resource"
                         :subject {:type "Notify" :title "hi"}
                         :failures        0
                         :successes       0
                         :noops           0
                         :skips           1}}
            results (paged-results
                      {:app-fn  *app*
                       :path    endpoint
                       :query   [">" "timestamp" 0]
                       :params  {:summarize-by "resource"}
                       :limit   1
                       :total   (count expected)
                       :include-total count?})]
        (is (= (count expected) (count results)))
        (is (= expected (set results)))))))

(deftest query-distinct-event-counts
  (store-example-report! (:basic reports) (now))
  (store-example-report! (:basic3 reports) (now))
  (testing "should only count the most recent event for each resource"
    (let [expected  #{{:subject-type "resource"
                       :subject {:type "Notify" :title "notify, yo"}
                       :failures 0
                       :successes 1
                       :noops 0
                       :skips 0}
                      {:subject-type "resource"
                       :subject {:type "Notify" :title "notify, yar"}
                       :failures 1
                       :successes 0
                       :noops 0
                       :skips 0}
                      {:subject-type "resource"
                       :subject {:type "Notify" :title "hi"}
                       :failures 0
                       :successes 0
                       :noops 0
                       :skips 1}}
          response  (get-response endpoint
                      ["=" "certname" "foo.local"]
                      "resource"
                      {"distinct-resources" true
                       "distinct-start-time" 0
                       "distinct-end-time" (now)})]
      (response-equal? response expected))))

(deftest query-with-environment
  (store-example-report! (:basic reports) (now))
  (store-example-report! (assoc (:basic2 reports)
                           :certname "bar.local"
                           :environment "PROD") (now))
  (are [result query] (response-equal? (get-response endpoint
                                                     query
                                                     "resource"
                                                     {"distinct-resources" false
                                                      "distinct-start-time" 0
                                                      "distinct-end-time" (now)})
                                       result)
       #{{:subject-type "resource"
          :subject {:type "Notify" :title "notify, yo"}
          :failures 0
          :successes 1
          :noops 0
          :skips 0}
         {:subject-type "resource"
          :subject {:type "Notify" :title "notify, yar"}
          :failures 0
          :successes 1
          :noops 0
          :skips 0}
         {:subject-type "resource"
          :subject {:type "Notify" :title "hi"}
          :failures 0
          :successes 0
          :noops 0
          :skips 1}}
       ["=" "environment" "DEV"]

       #{{:subject-type "resource"
          :subject {:type "Notify" :title "notify, yo"}
          :failures 0
          :successes 1
          :noops 0
          :skips 0}
         {:subject-type "resource"
          :subject {:type "Notify" :title "notify, yar"}
          :failures 0
          :successes 1
          :noops 0
          :skips 0}
         {:subject-type "resource"
          :subject {:type "Notify" :title "hi"}
          :failures 0
          :successes 0
          :noops 0
          :skips 1}}
       ["~" "environment" "DE.*"]

       #{{:subject-type "resource"
          :subject {:type "Notify" :title "notify, yo"}
          :failures 0
          :successes 1
          :noops 0
          :skips 0}
         {:subject-type "resource"
          :subject {:type "Notify" :title "notify, yar"}
          :failures 0
          :successes 1
          :noops 0
          :skips 0}
         {:subject-type "resource"
          :subject {:type "Notify" :title "hi"}
          :failures 0
          :successes 0
          :noops 0
          :skips 1}}
       ["not" ["=" "environment" "PROD"]]

       #{{:subject-type "resource"
          :subject {:type "Notify" :title "notify, yo"}
          :failures 0
          :successes 1
          :noops 0
          :skips 0}
         {:subject-type "resource"
          :subject {:type "Notify" :title "notify, yar"}
          :failures 0
          :successes 1
          :noops 0
          :skips 0}
         {:subject-type "resource"
          :subject {:type "Notify" :title "hi"}
          :failures 0
          :successes 0
          :noops 0
          :skips 1}
         {:subject-type "resource",
          :noops 0,
          :skips 0,
          :successes 1,
          :failures 0,
          :subject {:type "File", :title "tmp-directory"}}
         {:subject-type "resource",
          :noops 0,
          :skips 0,
          :successes 1,
          :failures 0,
          :subject {:type "File", :title "puppet-managed-file"}}
         {:subject-type "resource",
          :noops 0,
          :skips 0,
          :successes 1,
          :failures 0,
          :subject
          {:type "Notify", :title "Creating tmp directory at /Users/foo/tmp"}}}
       ["OR"
        ["=" "environment" "PROD"]
        ["=" "environment" "DEV"]]))
