(ns metabase-enterprise.semantic-layer.api-test
  (:require
   [clojure.test :refer :all]
   [metabase-enterprise.semantic-layer.api :as api]
   [metabase-enterprise.semantic-layer.metabot-scope :as metabot-scope]
   [metabase-enterprise.semantic-layer.models.data-complexity-score :as data-complexity-score]
   [metabase-enterprise.semantic-layer.task.complexity-score :as task.complexity-score]
   [metabase.metabot.config :as metabot.config]
   [metabase.test :as mt]
   [toucan2.core :as t2]))

(comment api/keep-me)

(def ^:private endpoint "ee/semantic-layer/complexity")
(def ^:private refresh-endpoint "ee/semantic-layer/complexity/refresh")

(defn- internal-metabot-id
  "Primary key of the internal Metabot row — used by the tests that temporarily tweak its
   `use_verified_content`/`collection_id` via `mt/with-temp-vals-in-db`. Calls
   `mt/initialize-if-needed!` so the row is populated by migrations even when this test runs in
   isolation (the endpoint tests piggyback on the web-server init and miss this failure mode)."
  []
  (mt/initialize-if-needed! :db)
  (t2/select-one-pk :model/Metabot
                    :entity_id (get-in metabot.config/metabot-config
                                       [metabot.config/internal-metabot-id :entity-id])))

(deftest complexity-endpoint-requires-superuser-test
  (testing "non-superusers are rejected"
    (is (= "You don't have permissions to do that."
           (mt/user-http-request :rasta :get 403 endpoint)))))

(deftest complexity-refresh-endpoint-requires-superuser-test
  (testing "non-superusers cannot trigger a forced recomputation"
    (is (= "You don't have permissions to do that."
           (mt/user-http-request :rasta :post 403 refresh-endpoint)))))

(def ^:private sample-score
  {:library  {:total 18
              :components {:entity-count      {:measurement 1.0 :score 10}
                           :name-collisions   {:measurement 0.0 :score 0}
                           :synonym-pairs     {:measurement 0.0 :score 0}
                           :field-count       {:measurement 8.0 :score 8}
                           :repeated-measures {:measurement 0.0 :score 0}}}
   :universe {:total 54
              :components {:entity-count      {:measurement 2.0 :score 20}
                           :name-collisions   {:measurement 0.0 :score 0}
                           :synonym-pairs     {:measurement 0.0 :score 0}
                           :field-count       {:measurement 24.0 :score 24}
                           :repeated-measures {:measurement 5.0 :score 10}}}
   :metabot  {:total 30
              :components {:entity-count      {:measurement 1.0 :score 10}
                           :name-collisions   {:measurement 0.0 :score 0}
                           :synonym-pairs     {:measurement 0.0 :score 0}
                           :field-count       {:measurement 20.0 :score 20}
                           :repeated-measures {:measurement 0.0 :score 0}}}
   :meta     {:formula-version 3
              :synonym-threshold 0.9}})

(deftest complexity-endpoint-returns-latest-stored-score-test
  (testing "superusers read the latest persisted score snapshot instead of recomputing it on demand"
    (mt/with-dynamic-fn-redefs [data-complexity-score/latest-score (constantly sample-score)]
      (is (= sample-score
             (mt/user-http-request :crowberto :get 200 endpoint))))))

(deftest complexity-endpoint-404s-when-no-score-has-been-persisted-yet-test
  (testing "the endpoint 404s until the background scorer has produced its first snapshot"
    (mt/with-dynamic-fn-redefs [data-complexity-score/latest-score (constantly nil)]
      (is (= "Not found."
             (mt/user-http-request :crowberto :get 404 endpoint))))))

(deftest complexity-refresh-endpoint-returns-fresh-score-test
  (testing "superusers can force the expensive recompute path on demand"
    (mt/with-dynamic-fn-redefs [task.complexity-score/force-scoring! (constantly sample-score)]
      (is (= sample-score
             (mt/user-http-request :crowberto :post 200 refresh-endpoint))))))

(deftest internal-metabot-scope-test
  (testing ":verified-only? is true only when the premium feature + use_verified_content both apply"
    (doseq [{:keys [features use-verified? expected-verified?]}
            [{:features #{}                        :use-verified? false :expected-verified? false}
             {:features #{}                        :use-verified? true  :expected-verified? false}
             {:features #{:content-verification}   :use-verified? false :expected-verified? false}
             {:features #{:content-verification}   :use-verified? true  :expected-verified? true}]]
      (testing (format "features=%s use_verified_content=%s" (pr-str features) use-verified?)
        (mt/with-premium-features features
          (mt/with-temp-vals-in-db :model/Metabot (internal-metabot-id)
                                   {:use_verified_content use-verified? :collection_id nil}
            (is (= {:verified-only? expected-verified? :collection-id nil}
                   (metabot-scope/internal-metabot-scope))))))))
  (testing ":collection-id is read straight from the internal Metabot row regardless of premium features"
    (mt/with-temp [:model/Collection {coll-id :id} {:name "metabot scope test coll"}]
      (mt/with-premium-features #{}
        (mt/with-temp-vals-in-db :model/Metabot (internal-metabot-id)
                                 {:use_verified_content false :collection_id coll-id}
          (is (= {:verified-only? false :collection-id coll-id}
                 (metabot-scope/internal-metabot-scope))))))))
