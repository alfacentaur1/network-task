-- main table for raw HTTP log data
CREATE TABLE IF NOT EXISTS http_log (
    timestamp DateTime,
    resource_id UInt64,
    bytes_sent UInt64,
    request_time_milli UInt64,
    response_status UInt16,
    cache_status LowCardinality(String),
    method LowCardinality(String),
    remote_addr String,
    url String
    ) ENGINE = MergeTree()
    ORDER BY (timestamp, resource_id);

-- table for aggregated data, denormalized for fast querying - grafana
CREATE TABLE IF NOT EXISTS http_log_totals (
    day Date,
    resource_id UInt64,
    response_status UInt16,
    cache_status LowCardinality(String),
    remote_addr String,
    total_bytes_sent UInt64,
    total_requests UInt64
    ) ENGINE = SummingMergeTree()
    ORDER BY (day, resource_id, response_status, cache_status, remote_addr);

-- trigger for pre-computing aggregated data for faster querying in Grafana
CREATE MATERIALIZED VIEW IF NOT EXISTS http_log_mv TO http_log_totals AS
SELECT
    toDate(timestamp) AS day,
    resource_id,
    response_status,
    cache_status,
    remote_addr,
    sum(bytes_sent) AS total_bytes_sent,
    count() AS total_requests
FROM http_log
GROUP BY day, resource_id, response_status, cache_status, remote_addr;