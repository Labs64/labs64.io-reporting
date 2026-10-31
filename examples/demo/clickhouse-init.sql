-- The demo tenant's tables, reader and row policy. A real deployment keeps these in its own tenant configuration.
CREATE DATABASE IF NOT EXISTS demo;
CREATE TABLE IF NOT EXISTS demo.calls (
    event_id UUID, customer String, event_time DateTime64(3, 'UTC'), received DateTime64(3, 'UTC'),
    correlation_id String, action String, status UInt16
) ENGINE = MergeTree ORDER BY (customer, event_time);
CREATE USER IF NOT EXISTS reporting IDENTIFIED BY 'reader' SETTINGS readonly = 2;
GRANT SELECT ON demo.calls TO reporting;
CREATE ROW POLICY IF NOT EXISTS calls_scope ON demo.calls USING customer = getSetting('SQL_scope') TO reporting;
INSERT INTO demo.calls VALUES
    (generateUUIDv4(), 'acme',   now64() - INTERVAL 2 DAY, now64() - INTERVAL 2 DAY, 'R1', 'read',  200),
    (generateUUIDv4(), 'acme',   now64() - INTERVAL 1 DAY, now64() - INTERVAL 1 DAY, 'R2', 'write', 500),
    (generateUUIDv4(), 'acme',   now64() - INTERVAL 1 DAY, now64() - INTERVAL 1 DAY, 'R3', 'read',  200),
    (generateUUIDv4(), 'globex', now64() - INTERVAL 1 DAY, now64() - INTERVAL 1 DAY, 'R4', 'read',  200);
