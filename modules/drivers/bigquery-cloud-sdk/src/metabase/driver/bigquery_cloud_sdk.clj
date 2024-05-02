(ns metabase.driver.bigquery-cloud-sdk
  (:require
   [clojure.core.async :as a]
   [clojure.set :as set]
   [clojure.string :as str]
   [medley.core :as m]
   [metabase.db.metadata-queries :as metadata-queries]
   [metabase.driver :as driver]
   [metabase.driver.bigquery-cloud-sdk.common :as bigquery.common]
   [metabase.driver.bigquery-cloud-sdk.params :as bigquery.params]
   [metabase.driver.bigquery-cloud-sdk.query-processor :as bigquery.qp]
   [metabase.driver.sql.util :as sql.u]
   [metabase.driver.sync :as driver.s]
   [metabase.lib.metadata :as lib.metadata]
   [metabase.lib.schema.common :as lib.schema.common]
   [metabase.models :refer [Database]]
   [metabase.models.table :as table]
   [metabase.query-processor.error-type :as qp.error-type]
   [metabase.query-processor.pipeline :as qp.pipeline]
   [metabase.query-processor.store :as qp.store]
   [metabase.query-processor.timezone :as qp.timezone]
   [metabase.query-processor.util :as qp.util]
   [metabase.util :as u]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   #_{:clj-kondo/ignore [:discouraged-namespace]}
   [toucan2.core :as t2])
  (:import
   (clojure.lang PersistentList)
   (com.google.cloud.bigquery BigQuery BigQuery$DatasetListOption BigQuery$JobOption BigQuery$TableDataListOption
                              BigQuery$TableListOption BigQuery$TableOption BigQueryException BigQueryOptions Dataset
                              DatasetId Field Field$Mode FieldValue FieldValueList QueryJobConfiguration Schema StandardTableDefinition
                              Table TableDefinition$Type TableId TableResult)))

(set! *warn-on-reflection* true)

(driver/register! :bigquery-cloud-sdk, :parent :sql)

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                     Client                                                     |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:private ^PersistentList bigquery-scopes
  "The scopes to use for executing BigQuery requests; see:
  `https://cloud.google.com/bigquery/docs/samples/bigquery-auth-drive-scope`.
  Unclear if this can be sourced from the `com.google.cloud.bigquery` package directly.  We use the standard bigquery
  scope, as well as the drive scope (allowing for configured Drive external tables to be queried, as per
  `https://cloud.google.com/bigquery/external-data-drive`)."
  '("https://www.googleapis.com/auth/bigquery"
    "https://www.googleapis.com/auth/drive"))

(mu/defn ^:private database-details->client
  ^BigQuery [details :- :map]
  (let [creds   (bigquery.common/database-details->service-account-credential details)
        bq-bldr (doto (BigQueryOptions/newBuilder)
                  (.setCredentials (.createScoped creds bigquery-scopes)))]
    (.. bq-bldr build getService)))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                      Sync                                                      |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- list-datasets
  "Fetch all datasets given database `details`, applying dataset filters if specified."
  [{:keys [project-id dataset-filters-type dataset-filters-patterns] :as details}]
  (let [client (database-details->client details)
        project-id (or project-id (bigquery.common/database-details->credential-project-id details))
        datasets (.listDatasets client project-id (u/varargs BigQuery$DatasetListOption))
        inclusion-patterns (when (= "inclusion" dataset-filters-type) dataset-filters-patterns)
        exclusion-patterns (when (= "exclusion" dataset-filters-type) dataset-filters-patterns)]
    (for [^Dataset dataset (.iterateAll datasets)
          :let [^DatasetId dataset-id (.. dataset getDatasetId)]
          :when (driver.s/include-schema? inclusion-patterns
                                          exclusion-patterns
                                          (.getDataset dataset-id))]
      dataset-id)))

(defn- list-tables
  "Fetch all tables (new pages are loaded automatically by the API)."
  (^Iterable [details]
   (let [client (database-details->client details)
         dataset-iter (list-datasets details)]
     (apply concat (for [^DatasetId dataset-id dataset-iter]
                     (-> (.listTables client dataset-id (u/varargs BigQuery$TableListOption))
                         .iterateAll
                         .iterator
                         iterator-seq))))))

(defmethod driver/can-connect? :bigquery-cloud-sdk
  [_ details]
  ;; check whether we can connect by seeing whether listing datasets succeeds
  (let [[success? datasets] (try [true (list-datasets details)]
                                 (catch Exception e
                                   (log/error e "Exception caught in :bigquery-cloud-sdk can-connect?")
                                   [false nil]))]
    (cond
      (not success?)
      false
      ;; if the datasets are filtered and we don't find any matches, throw an exception with a message that we can show
      ;; to the user
      (and (not= (:dataset-filters-type details) "all")
           (nil? (first datasets)))
      (throw (Exception. (tru "Looks like we cannot find any matching datasets.")))
      :else
      true)))

(def ^:private empty-table-options
  (u/varargs BigQuery$TableOption))

(mu/defn ^:private get-table :- (lib.schema.common/instance-of-class Table)
  (^Table [{{:keys [project-id]} :details, :as database} dataset-id table-id]
   (get-table (database-details->client (:details database)) project-id dataset-id table-id))

  (^Table [^BigQuery client :- (lib.schema.common/instance-of-class BigQuery)
           project-id       :- [:maybe ::lib.schema.common/non-blank-string]
           dataset-id       :- ::lib.schema.common/non-blank-string
           table-id         :- ::lib.schema.common/non-blank-string]
   (if project-id
     (.getTable client (TableId/of project-id dataset-id table-id) empty-table-options)
     (.getTable client dataset-id table-id empty-table-options))))

(defn- table-is-partitioned?
  [^StandardTableDefinition tabledef]
  (when (#{TableDefinition$Type/TABLE TableDefinition$Type/MATERIALIZED_VIEW} (.getType tabledef))
    (or (.getRangePartitioning tabledef)
        (.getTimePartitioning tabledef))))

(defmethod driver/describe-database :bigquery-cloud-sdk
  [_ database]
  (let [tables (list-tables (:details database))]
    {:tables (set (for [^Table table tables
                        :let  [^TableId                 table-id   (.getTableId table)
                               ^String                  dataset-id (.getDataset table-id)
                               ^StandardTableDefinition tabledef   (.getDefinition table)
                                                        table-name (str (.getTable table-id))]]
                    {:schema                  dataset-id
                     :name                    table-name
                     :database_require_filter
                     (boolean
                      (and
                       ;; Materialiezed views can be partitioned, and whether the view require a filter or not is based
                       ;; on the base table it selects from, without parsing the view query we can't find out the base table,
                       ;; thus we can't know whether the view require a filter or not.
                       ;; Maybe this is something we can do once we can parse sql
                       (= TableDefinition$Type/TABLE (. tabledef getType))
                       (when (table-is-partitioned? tabledef)
                         ;; having to use `get-table` here is inefficient, but calling `(.getRequirePartitionFilter)`
                         ;; on the `table` object from `list-tables` will return `nil` even though the table requires
                         ;; a partition filter.
                         ;; This is an upstream bug where the v2 API is incomplete when setting object values see
                         ;; https://github.com/googleapis/java-bigquery/blob/main/google-cloud-bigquery/src/main/java/com/google/cloud/bigquery/spi/v2/HttpBigQueryRpc.java#L343C23-L343C23
                         ;; Anyway, we only call it when the table is partitioned, so I don't think it's a big deal
                         (.getRequirePartitionFilter (get-table database dataset-id table-name)))))}))}))

(defn- bigquery-type->base-type
  "Returns the base type for the given BigQuery field's `field-mode` and `field-type`. In BQ, an ARRAY of INTEGER has
  \"REPEATED\" as the mode, and \"INTEGER\" as the type name.

  If/when we are able to represent complex types more precisely, we may want to capture that information separately.
  For now, though, we will check if the `field-mode` is \"REPEATED\" and return our :type/Array for that case, then
  proceed to check the `field-type` otherwise."
  [field-mode field-type]
  (if (= Field$Mode/REPEATED field-mode)
    :type/Array
    (case field-type
      "BOOLEAN"    :type/Boolean
      "FLOAT"      :type/Float
      "INTEGER"    :type/Integer
      "RECORD"     :type/Dictionary ; RECORD -> field has a nested schema
      "STRING"     :type/Text
      "DATE"       :type/Date
      "DATETIME"   :type/DateTime
      "TIMESTAMP"  :type/DateTimeWithLocalTZ
      "TIME"       :type/Time
      "NUMERIC"    :type/Decimal
      "BIGNUMERIC" :type/Decimal
      :type/*)))

(mu/defn ^:private table-schema->metabase-field-info
  [^Schema schema :- (lib.schema.common/instance-of-class Schema)]
  (for [[idx ^Field field] (m/indexed (.getFields schema))]
    (let [type-name (.. field getType name)
          f-mode    (.getMode field)]
      {:name              (.getName field)
       :database-type     type-name
       :base-type         (bigquery-type->base-type f-mode type-name)
       :database-position idx})))

(def ^:private partitioned-time-field-name
  "The name of pseudo-column for tables that are partitioned by ingestion time.
  See https://cloud.google.com/bigquery/docs/partitioned-tables#ingestion_time"
  "_PARTITIONTIME")

(def ^:private partitioned-date-field-name
  "This is also a pseudo-column, similiar to [[partitioned-time-field-name]].
  In fact _PARTITIONDATE is _PARTITIONTIME truncated to DATE.
  See https://cloud.google.com/bigquery/docs/querying-partitioned-tables#query_an_ingestion-time_partitioned_table"
  "_PARTITIONDATE")

(defmethod driver/describe-table :bigquery-cloud-sdk
  [_ database {table-name :name, dataset-id :schema}]
  (let [table                  (get-table database dataset-id table-name)
        ^StandardTableDefinition tabledef (.getDefinition table)
        is-partitioned?        (table-is-partitioned? tabledef)
        ;; a table can only have one partitioned field
        partitioned-field-name (when is-partitioned?
                                 (or (some-> (.getRangePartitioning tabledef) .getField)
                                     (some-> (.getTimePartitioning tabledef) .getField)))
        fields                 (set
                                (map
                                 #(assoc % :database-partitioned (= (:name %) partitioned-field-name))
                                 (table-schema->metabase-field-info (. tabledef getSchema))))]
    {:schema dataset-id
     :name   table-name
     :fields (cond-> fields
               ;; if table has time partition but no field is specified as partitioned
               ;; meaning this table is partitioned by ingestion time
               ;; so we manually sync the 2 pseudo-columns _PARTITIONTIME AND _PARTITIONDATE
               (and is-partitioned?
                    (some? (.getTimePartitioning tabledef))
                    (nil? partitioned-field-name))
               (conj
                {:name                 partitioned-time-field-name
                 :database-type        "TIMESTAMP"
                 :base-type            (bigquery-type->base-type nil "TIMESTAMP")
                 :database-position    (count fields)
                 :database-partitioned true}
                {:name                 partitioned-date-field-name
                 :database-type        "DATE"
                 :base-type            (bigquery-type->base-type nil "DATE")
                 :database-position    (inc (count fields))
                 :database-partitioned true}))}))

(defn- get-field-parsers [^Schema schema]
  (let [default-parser (get-method bigquery.qp/parse-result-of-type :default)]
    (into []
          (map (fn [^Field field]
                 (let [column-type (.. field getType name)
                       column-mode (.getMode field)
                       method      (get-method bigquery.qp/parse-result-of-type column-type)]
                   (when (= method default-parser)
                     (let [column-name (.getName field)]
                       (log/warnf "Warning: missing type mapping for parsing BigQuery results column %s of type %s."
                                  column-name column-type)))
                   (partial method column-type column-mode bigquery.common/*bigquery-timezone-id*))))
          (.getFields schema))))

(defn- parse-field-value [^FieldValue cell parser]
  (when-let [v (.getValue cell)]
    ;; There is a weird error where everything that *should* be NULL comes back as an Object.
    ;; See https://jira.talendforge.org/browse/TBD-1592
    ;; Everything else comes back as a String luckily so we can proceed normally.
    (when-not (= (class v) Object)
      (parser v))))

(defn- extract-fingerprint [field-idxs parsers ^FieldValueList values]
  (map (fn [^Integer idx parser]
         (parse-field-value (.get values idx) parser))
       field-idxs parsers))

(defn- sample-table
  "Process a sample of rows of fields corresponding to the Metabase fields
  `fields` from the BigQuery table `bq-table` using the query result reducing
  function `rff`.

  `.getSchema` returns nil if called on the result of `.list`, so we have to
  match fields by position. Here it is assumed that :database_position in
  `fields` represents the positions of the columns in the BigQuery table and
  that `.list` returns the fields in that order. The first assumption could be
  lifted by matching the names in `fields` to the names in the table schema."
  [^Table bq-table fields rff]
  (let [field-idxs  (mapv :database_position fields)
        all-parsers (get-field-parsers (.. bq-table getDefinition getSchema))
        parsers     (mapv all-parsers field-idxs)
        rows        (.list bq-table (u/varargs BigQuery$TableDataListOption))]
    (transduce (comp (take metadata-queries/max-sample-rows)
                     (map (partial extract-fingerprint field-idxs parsers)))
               ;; Instead of passing on fields, we could recalculate the
               ;; metadata from the schema, but that probably makes no
               ;; difference and currently the metadata is ignored anyway.
               (rff {:cols fields})
               (-> rows .iterateAll .iterator iterator-seq))))

(defn- ingestion-time-partitioned-table?
  [table-id]
  (t2/exists? :model/Field :table_id table-id :name partitioned-time-field-name :database_partitioned true :active true))

(defmethod driver/table-rows-sample :bigquery-cloud-sdk
  [driver {table-name :name, dataset-id :schema :as table} fields rff opts]
  (let [database (table/database table)
        bq-table (get-table database dataset-id table-name)]
    (if (or (#{TableDefinition$Type/MATERIALIZED_VIEW TableDefinition$Type/VIEW
               ;; We couldn't easily test if the following two can show up as
               ;; tables and if `.list` is supported for them, so they are here
               ;; to make sure we don't break existing instances.
               TableDefinition$Type/EXTERNAL TableDefinition$Type/SNAPSHOT}
             (.. bq-table getDefinition getType))
            ;; if the table is partitioned by ingestion time, using .list or .listTableData won't return values for
            ;; the _PARTITIONTIME field, so we need to fall back to using sql
            (ingestion-time-partitioned-table? (:id table)))
      (do (log/debugf "%s.%s is a view or a table partitioned by ingestion time, so we cannot use the list API; falling back to regular query"
                      dataset-id table-name)
          ((get-method driver/table-rows-sample :sql-jdbc) driver table fields rff opts))
      (sample-table bq-table fields rff))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                Running Queries                                                 |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:private ^:dynamic ^Long *page-size*
  "Maximum number of rows to return per page in a query. Leave unset (i.e. falling to the library default) by default,
  but override for testing."
  nil)

(def ^:private ^:dynamic *page-callback*
  "Callback to execute when a new page is retrieved, used for testing"
  nil)

(defn- throw-invalid-query [e sql parameters]
  (throw (ex-info (tru "Error executing query: {0}" (ex-message e))
           {:type qp.error-type/invalid-query, :sql sql, :parameters parameters}
           e)))

(defn- execute-bigquery
  ^TableResult [^BigQuery client labels ^String sql parameters cancel-chan cancel-requested?]
  {:pre [client (not (str/blank? sql))]}
  (try
    (let [request (doto (QueryJobConfiguration/newBuilder sql)
                    ;; if the query contains a `#legacySQL` directive then use legacy SQL instead of standard SQL
                    (.setUseLegacySql (str/includes? (u/lower-case-en sql) "#legacysql"))
                    (bigquery.params/set-parameters! parameters)
                    ;; .setMaxResults is very misleading; it's actually the page size, and it only takes
                    ;; effect for RPC (a.k.a. "fast") calls
                    ;; there is no equivalent of .setMaxRows on a JDBC Statement; we rely on our middleware to stop
                    ;; realizing more rows as per the maximum result size
                    (.setMaxResults *page-size*)
                    (.setLabels labels))
          ;; as long as we don't set certain additional QueryJobConfiguration options, our queries *should* always be
          ;; following the fast query path (i.e. RPC)
          ;; check out com.google.cloud.bigquery.QueryRequestInfo.isFastQuerySupported for full details
          res-fut (future (.query client (.build request) (u/varargs BigQuery$JobOption)))]
      (when cancel-chan
        (future                       ; this needs to run in a separate thread, because the <!! operation blocks forever
          (when (a/<!! cancel-chan)
            (log/debug "Received a message on the cancel channel; attempting to stop the BigQuery query execution")
            (reset! cancel-requested? true) ; signal the page iteration fn to stop
            (if-not (or (future-cancelled? res-fut) (future-done? res-fut))
              ;; somehow, even the FIRST page hasn't come back yet (i.e. the .query call above), so cancel the future to
              ;; interrupt the thread waiting on that response to come back
              ;; unfortunately, with this particular overload of .query, we have no access to (nor the ability to control)
              ;; the jobId, so we have no way to use the BigQuery client to cancel any job that might be running
              (future-cancel res-fut)
              (when (future-done? res-fut) ; canceled received after it was finished; may as well return it
                @res-fut)))))
      @res-fut)
    (catch java.util.concurrent.CancellationException _e
      (throw (ex-info (tru "Query cancelled") {:sql sql :parameters parameters ::cancelled? true})))
    (catch BigQueryException e
      (if (.isRetryable e)
        (throw (ex-info (tru "BigQueryException executing query")
                        {:retryable? (.isRetryable e), :sql sql, :parameters parameters}
                        e))
        (throw-invalid-query e sql parameters)))
    (catch Throwable e
      (throw-invalid-query e sql parameters))))

(mu/defn ^:private execute-bigquery-on-db :- some?
  ^TableResult
  [database :- [:map [:details :map]] labels sql parameters cancel-chan cancel-requested?]
  (execute-bigquery
   (database-details->client (:details database))
   labels
   sql
   parameters
   cancel-chan
   cancel-requested?))

(defn- fetch-page [^TableResult response cancel-requested?]
  (when response
    (when *page-callback*
      (*page-callback*))
    (lazy-cat
      (.getValues response)
      (when (some? (.getNextPageToken response))
        (if @cancel-requested?
          (do (log/debug "Cancellation requested; terminating fetching of BigQuery pages")
              [])
          (fetch-page (.getNextPage response) cancel-requested?))))))

(mu/defn ^:private post-process-native :- some?
  "Parse results of a BigQuery query. `respond` is the same function passed to
  `metabase.driver/execute-reducible-query`, and has the signature

    (respond results-metadata rows)"
  [respond ^TableResult resp cancel-requested?]
  (let [^Schema schema
        (.getSchema resp)

        parsers
        (get-field-parsers schema)

        columns
        (for [column (table-schema->metabase-field-info schema)]
          (-> column
              (set/rename-keys {:base-type :base_type})
              (dissoc :database-type :database-position)))]
    (respond
     {:cols columns}
     (for [^FieldValueList row (fetch-page resp cancel-requested?)]
       (map parse-field-value row parsers)))))

(mu/defn ^:private ^:dynamic *process-native*
  [respond  :- fn?
   database :- [:map [:details :map]]
   labels
   sql
   parameters
   cancel-chan]
  {:pre [(map? database) (map? (:details database))]}
  ;; automatically retry the query if it times out or otherwise fails. This is on top of the auto-retry added by
  ;; `execute`
  (let [cancel-requested? (atom false)
        thunk             (fn []
                            (post-process-native respond
                                                 (execute-bigquery-on-db
                                                  database
                                                  labels
                                                  sql
                                                  parameters
                                                  cancel-chan
                                                  cancel-requested?)
                                                 cancel-requested?))]
    (try
      (thunk)
      (catch Throwable e
        (let [ex-data (u/all-ex-data e)]
          (if (and (not (::cancelled? ex-data))
                   (or (:retryable? ex-data) (not (qp.error-type/client-error? (:type ex-data)))))
            (thunk)
            (throw e)))))))

(defn- effective-query-timezone-id [database]
  (if (get-in database [:details :use-jvm-timezone])
    (qp.timezone/system-timezone-id)
    "UTC"))

(defn- bigquery-labels
  [database, {{:keys [executed-by dashboard-id card-id], :as _info} :info}]
  (if (get-in database [:details :include-info-labels] false)
    (merge {}
       (when executed-by {"metabase_user_id" executed-by})
       (when dashboard-id {"metabase_dashboard_id" dashboard-id})
       (when card-id {"metabase_card_id" card-id})) {}))

(defmethod driver/execute-reducible-query :bigquery-cloud-sdk
  [_driver {{sql :query, :keys [params]} :native, :as outer-query} _context respond]
  (let [database (lib.metadata/database (qp.store/metadata-provider))]
    (binding [bigquery.common/*bigquery-timezone-id* (effective-query-timezone-id database)]
      (log/tracef "Running BigQuery query in %s timezone" bigquery.common/*bigquery-timezone-id*)
      (let [sql (if (get-in database [:details :include-user-id-and-hash] true)
                  (str "-- " (qp.util/query->remark :bigquery-cloud-sdk outer-query) "\n" sql)
                  sql)]
        (*process-native* respond database (bigquery-labels database outer-query) sql params qp.pipeline/*canceled-chan*)))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                           Other Driver Method Impls                                            |
;;; +----------------------------------------------------------------------------------------------------------------+

(doseq [[feature supported?] {:convert-timezone        true
                              :datetime-diff           true
                              :expressions             true
                              :foreign-keys            true
                              :now                     true
                              :percentile-aggregations true
                              ;; BigQuery uses timezone operators and arguments on calls like extract() and
                              ;; timezone_trunc() rather than literally using SET TIMEZONE, but we need to flag it as
                              ;; supporting set-timezone anyway so that reporting timezones are returned and used, and
                              ;; tests expect the converted values.
                              :set-timezone            true}]
  (defmethod driver/database-supports? [:bigquery-cloud-sdk feature] [_driver _feature _db] supported?))

;; BigQuery is always in UTC
(defmethod driver/db-default-timezone :bigquery-cloud-sdk [_ _]
  "UTC")

(defmethod driver/db-start-of-week :bigquery-cloud-sdk
  [_]
  :sunday)

(defmethod driver/notify-database-updated :bigquery-cloud-sdk
  [_ database]
  (bigquery.common/populate-project-id-from-credentials! database))

(defn- convert-dataset-id-to-filters!
  "Converts a bigquery-cloud-sdk db-details having the outdated `dataset-id` connection parameter, into one where that
  same value is set as the (only) dataset inclusion filter pattern. Also updated model objects to reflect the new
  structure:

  * any associated Table instances will be updated to have schema set (to the dataset-id value)
  * the Database model itself will be updated to persist this change to db-details back to the app DB

  Returns the passed `database` parameter with the aformentioned changes having been made and persisted."
  [database dataset-id]
  (let [db-id (u/the-id database)]
    (log/infof "DB %s had hardcoded dataset-id; changing to an inclusion pattern and updating table schemas"
               (pr-str db-id))
    (try
      (t2/query-one {:update (t2/table-name :model/Table)
                     :set    {:schema dataset-id}
                     :where  [:and
                              [:= :db_id db-id]
                              [:or
                               [:= :schema nil]
                               [:not= :schema dataset-id]]]})
      ;; if we are upgrading to the sdk driver after having downgraded back to the old driver we end up with
      ;; duplicated tables with nil schema. Happily only in the "dataset-id" schema and not all schemas. But just
      ;; leave them with nil schemas and they will get deactivated in sync.
      (catch Exception _e))
    (let [updated-db (-> (assoc-in database [:details :dataset-filters-type] "inclusion")
                         (assoc-in [:details :dataset-filters-patterns] dataset-id)
                         (m/dissoc-in [:details :dataset-id]))]
      (t2/update! Database db-id {:details (:details updated-db)})
      updated-db)))

;; TODO: THIS METHOD SHOULD NOT BE UPDATING THE APP-DB (which it does in [convert-dataset-id-to-filters!])
;; Issue: https://github.com/metabase/metabase/issues/39392
(defmethod driver/normalize-db-details :bigquery-cloud-sdk
  [_driver {:keys [details] :as database}]
  (when-not (empty? (filter some? ((juxt :auth-code :client-id :client-secret) details)))
    (log/errorf (str "Database ID %d, which was migrated from the legacy :bigquery driver to :bigquery-cloud-sdk, has"
                     " one or more OAuth style authentication scheme parameters saved to db-details, which cannot"
                     " be automatically migrated to the newer driver (since it *requires* service-account-json intead);"
                     " this database must therefore be updated by an administrator (by adding a service-account-json)"
                     " before sync and queries will work again")
                (u/the-id database)))
  (if-let [dataset-id (get details :dataset-id)]
    (when-not (str/blank? dataset-id)
      (convert-dataset-id-to-filters! database dataset-id))
    database))

(defmethod driver/prettify-native-form :bigquery-cloud-sdk
  [_ native-form]
  (sql.u/format-sql-and-fix-params :mysql native-form))
