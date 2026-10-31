CREATE DATABASE IF NOT EXISTS demo;
CREATE TABLE demo.calls (
    event_id UUID, customer String, event_time DateTime64(3, 'UTC'), received DateTime64(3, 'UTC'),
    correlation_id String, action String, status UInt16
) ENGINE = MergeTree ORDER BY (customer, event_time);
CREATE USER IF NOT EXISTS reporting IDENTIFIED BY 'reader' SETTINGS readonly = 2;
GRANT SELECT ON demo.calls TO reporting;
CREATE ROW POLICY IF NOT EXISTS calls_scope ON demo.calls USING customer = getSetting('SQL_scope') TO reporting;
INSERT INTO demo.calls VALUES
    ('00000000-0000-0000-0000-00000000000a', 'A', '2026-10-01 10:00:00', '2026-10-01 10:00:01', 'R1', 'read', 200),
    ('00000000-0000-0000-0000-00000000000b', 'A', '2026-10-01 11:00:00', '2026-10-01 11:00:01', 'R2', 'write', 500),
    ('00000000-0000-0000-0000-00000000000c', 'A', '2026-10-02 10:00:00', '2026-10-02 10:00:01', 'R3', 'read', 200),
    ('00000000-0000-0000-0000-00000000000d', 'B', '2026-10-01 10:00:00', '2026-10-01 10:00:01', 'R4', 'read', 200);
