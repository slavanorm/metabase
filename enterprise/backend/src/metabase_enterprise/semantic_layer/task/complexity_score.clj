(ns metabase-enterprise.semantic-layer.task.complexity-score
  "Daily Quartz job that computes, persists, and emits the Data Complexity Score.
  Shared jobstore + `DisallowConcurrentExecution` → one node per cluster per tick.
  Boot-time emission (for first-ever runs and parameter bumps) is driven separately by the startup
  hook in `metabase-enterprise.semantic-layer.init`, which runs regardless of scheduler state and is
  guarded by a cluster lock so only one node per cluster emits per fingerprint change."
  (:require
   [clojure.edn :as edn]
   [clojurewerkz.quartzite.jobs :as jobs]
   [clojurewerkz.quartzite.schedule.cron :as cron]
   [clojurewerkz.quartzite.triggers :as triggers]
   [metabase-enterprise.semantic-layer.complexity :as complexity]
   [metabase-enterprise.semantic-layer.metabot-scope :as metabot-scope]
   [metabase-enterprise.semantic-layer.models.data-complexity-score :as data-complexity-score]
   [metabase-enterprise.semantic-layer.settings :as settings]
   [metabase-enterprise.semantic-search.core :as semantic-search]
   [metabase.app-db.cluster-lock :as cluster-lock]
   [metabase.config.core :as config]
   [metabase.task.core :as task]
   [metabase.util.log :as log])
  (:import
   (org.quartz DisallowConcurrentExecution)))

(set! *warn-on-reflection* true)

(def ^:private job-key     (jobs/key "metabase-enterprise.semantic-layer.task.complexity-score.job"))
(def ^:private trigger-key (triggers/key "metabase-enterprise.semantic-layer.task.complexity-score.trigger"))

(defn- current-fingerprint
  "String capturing everything that changes the meaning of an emitted score — mirror of the Snowplow
  `formula_version` + `parameters` fields."
  []
  (let [embedding-model (semantic-search/active-embedding-model)]
    (pr-str (into (sorted-map)
                  (cond-> {:formula-version   complexity/formula-version
                           :synonym-threshold complexity/synonym-similarity-threshold}
                    embedding-model (assoc :embedding-model embedding-model))))))

(defn- compute-and-persist-score!
  "Compute one score snapshot and attempt to persist it. Returns
  `{:result <score> :fingerprint <string> :persisted? <bool>}`."
  []
  (let [result      (complexity/complexity-scores :metabot-scope (metabot-scope/internal-metabot-scope))
        fingerprint (current-fingerprint)
        persisted?  (try
                      (data-complexity-score/record-score! fingerprint result)
                      true
                      (catch Throwable t
                        (log/warn t "Data Complexity Score: failed to persist score snapshot")
                        false))]
    {:result result
     :fingerprint fingerprint
     :persisted? persisted?}))

(defn- finalize-score-run!
  "Advance the last-successful fingerprint when the cached snapshot and Snowplow publish both
  succeeded. When `throw-on-persistence-failure?` is true, rethrow a persistence failure so a
  caller like the manual refresh endpoint can return an error instead of silently serving a fresh
  score that never made it into the cache."
  [{:keys [result fingerprint persisted?]}
   {:keys [throw-on-persistence-failure?]}]
  (cond
    (and persisted? (::complexity/snowplow-published? (meta result)))
    (settings/data-complexity-scoring-last-fingerprint! fingerprint)

    (not persisted?)
    (let [message "Data Complexity Score: persistence failed; leaving fingerprint unchanged so the next boot or cron retries"]
      (if throw-on-persistence-failure?
        (throw (ex-info message {:type ::score-persistence-failed}))
        (log/warn message)))

    :else
    (log/warn "Data Complexity Score: Snowplow publish failed; leaving fingerprint unchanged so the next boot or cron retries"))
  result)

(defn force-scoring!
  "Force one scoring pass regardless of `data-complexity-scoring-enabled`, persist the fresh
  snapshot, and return it. Intended for explicit superuser-triggered refreshes."
  []
  (-> (compute-and-persist-score!)
      (finalize-score-run! {:throw-on-persistence-failure? true})))

(defn- run-scoring!
  "One scheduled/boot scoring pass. Gated by [[settings/data-complexity-scoring-enabled]] so admins
  can silence it without unscheduling the job.

  Returns the score result (with `::complexity/snowplow-published?` metadata) when scoring ran, or
  nil when skipped / threw."
  []
  (if (settings/data-complexity-scoring-enabled)
    (try
      (-> (compute-and-persist-score!)
          (finalize-score-run! {:throw-on-persistence-failure? false}))
      (catch Throwable t
        (log/warn t "Data Complexity Score job failed")
        nil))
    (do (log/debug "Data Complexity Score job skipped — data-complexity-scoring-enabled is off")
        nil)))

(task/defjob ^{DisallowConcurrentExecution true
               :doc "Compute and publish the Data Complexity Score."}
  DataComplexityScoring [_ctx]
  (run-scoring!))

;; Long enough that any realistic scoring run finishes well inside it, short enough that a crashed
;; claimant unblocks the next boot's retry without operator intervention.
(def ^:private boot-claim-ttl-ms (* 30 60 1000))

(defn- now-ms [] (System/currentTimeMillis))

(defn- parse-boot-claim
  "Parse the persisted claim string into `{:fingerprint <str> :claimed-at <long> :owner <str>}` or
  nil when the setting is empty/garbled."
  [s]
  (when (and s (seq s))
    (try
      (let [v (edn/read-string s)]
        (when (map? v) v))
      (catch Throwable _ nil))))

(defn- boot-claim-active?
  "True when a persisted claim for the same fingerprint we'd emit now is still within the TTL —
  meaning a sibling node is (or very recently was) running the emission and this node should skip."
  [claim current-fp]
  (boolean
   (and claim
        (= current-fp (:fingerprint claim))
        (when-let [ts (:claimed-at claim)]
          (and (int? ts)
               (< (- (now-ms) (long ts)) boot-claim-ttl-ms))))))

(defn- claim-boot-emission!
  "Cluster-safe boot-time claim. Returns the claim map (including a unique `:owner` token) when
  this node wins the right to run a boot-time emission for the current config fingerprint, or nil
  otherwise. The returned claim must be passed back to [[release-boot-claim!]] so the release is a
  compare-and-clear — a sibling node that took over after a TTL-based expiry keeps its own claim.

  We do not advance [[settings/data-complexity-scoring-last-fingerprint]] here — only `run-scoring!`
  does, after confirmed Snowplow publish. Instead we write a separate
  [[settings/data-complexity-scoring-boot-claim]] marker (fingerprint + timestamp + owner) so that:

  - sibling nodes acquiring the lock next see an active claim and skip (no duplicate boot score);
  - a scoring/publish failure or crash mid-run leaves the success fingerprint untouched, so the
    next boot retries instead of treating the fingerprint as up-to-date;
  - the TTL on `:claimed-at` means even a hard JVM crash doesn't permanently suppress emission;
  - the `:owner` token lets a slow claimant distinguish its own claim from a sibling's takeover.

  Reads inside the lock bypass the in-process settings cache via `config/*disable-setting-cache*`
  so values freshly committed by sibling nodes are always visible — the cache is only refreshed
  periodically and without bypass two nodes could both pass the check and both emit."
  []
  (cluster-lock/with-cluster-lock {:lock ::complexity-score-boot-emission
                                   :timeout-seconds 10}
    (binding [config/*disable-setting-cache* true]
      (let [current (current-fingerprint)]
        (when (and (settings/data-complexity-scoring-enabled)
                   (not= current (settings/data-complexity-scoring-last-fingerprint))
                   (not (boot-claim-active?
                         (parse-boot-claim (settings/data-complexity-scoring-boot-claim))
                         current)))
          (let [claim {:fingerprint current
                       :claimed-at  (now-ms)
                       :owner       (str (random-uuid))}]
            (settings/data-complexity-scoring-boot-claim! (pr-str claim))
            claim))))))

(defn- release-boot-claim!
  "Best-effort compare-and-clear of the caller's boot claim. Only clears the persisted value when
  its `:owner` still matches `claim` — protecting against the case where our run outlived the TTL
  and a sibling legitimately took over the claim in the meantime. Failure is non-fatal: the TTL on
  the claim timestamp ensures the next boot can retry either way."
  [claim]
  (try
    (cluster-lock/with-cluster-lock {:lock ::complexity-score-boot-emission
                                     :timeout-seconds 10}
      (binding [config/*disable-setting-cache* true]
        (let [persisted (parse-boot-claim (settings/data-complexity-scoring-boot-claim))]
          (if (and persisted (= (:owner persisted) (:owner claim)))
            (settings/data-complexity-scoring-boot-claim! "")
            (log/info "Data Complexity Score: skipping boot-claim release — persisted claim no longer belongs to this node (sibling takeover after TTL)")))))
    (catch Throwable t
      (log/warn t "Data Complexity Score: failed to clear boot-claim; it will expire via TTL"))))

(defn maybe-emit-boot-score!
  "Emit a Data Complexity Score at boot if the persisted fingerprint lags the live config. Cluster-
  safe via [[claim-boot-emission!]]; runs regardless of Quartz state so operators still get a score
  on nodes with `MB_DISABLE_SCHEDULER=true` or a failed scheduler init. Intended to be called from
  a startup hook on a background thread — see `metabase-enterprise.semantic-layer.init`.

  Success advances the last-successful fingerprint inside `run-scoring!`; any other outcome (skip,
  throw, publish failure) leaves it untouched so the next boot or cron retries. The boot-claim is
  released (compare-and-clear on `:owner`) after the run so sibling nodes can proceed without
  waiting for the TTL — unless our run outlived the TTL and a sibling has taken over the claim."
  []
  (try
    (when-let [claim (claim-boot-emission!)]
      (log/info "Data Complexity Score: fingerprint changed, emitting boot-time score")
      (try
        (run-scoring!)
        (finally
          (release-boot-claim! claim))))
    (catch Throwable t
      (log/warn t "Data Complexity Score: boot-time emission failed"))))

(defmethod task/init! ::DataComplexityScoring [_]
  (let [job     (jobs/build
                 (jobs/of-type DataComplexityScoring)
                 (jobs/store-durably)
                 (jobs/with-identity job-key)
                 (jobs/with-description "Data Complexity Score — daily telemetry"))
        ;; 03:17 UTC — off-hour to avoid cron-thundering-herd with other Metabase jobs.
        trigger (triggers/build
                 (triggers/with-identity trigger-key)
                 (triggers/for-job job-key)
                 (triggers/with-schedule
                  (cron/schedule
                   (cron/cron-schedule "0 17 3 * * ? *")
                   (cron/in-time-zone (java.util.TimeZone/getTimeZone "UTC"))
                   (cron/with-misfire-handling-instruction-fire-and-proceed))))]
    (task/schedule-task! job trigger)))
