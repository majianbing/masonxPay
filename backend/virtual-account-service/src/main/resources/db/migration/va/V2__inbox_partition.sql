-- Repartition va_inbox_event to HASH(event_id), 8 buckets.
--
-- The plain table from V1 is replaced with a declarative hash-partitioned
-- parent + 8 child partitions. Each dedup lookup (INSERT ... ON CONFLICT)
-- hits exactly one child partition — no cross-partition scan.
--
-- Child partitions are named va_inbox_event_0 .. va_inbox_event_7.
-- Retention cleanup targets each child directly to keep lock scope small.

DROP TABLE va_inbox_event;

CREATE TABLE va_inbox_event (
    event_id    VARCHAR(64)  NOT NULL,
    event_type  VARCHAR(100) NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL DEFAULT now()
) PARTITION BY HASH (event_id);

CREATE TABLE va_inbox_event_0 PARTITION OF va_inbox_event FOR VALUES WITH (MODULUS 8, REMAINDER 0);
CREATE TABLE va_inbox_event_1 PARTITION OF va_inbox_event FOR VALUES WITH (MODULUS 8, REMAINDER 1);
CREATE TABLE va_inbox_event_2 PARTITION OF va_inbox_event FOR VALUES WITH (MODULUS 8, REMAINDER 2);
CREATE TABLE va_inbox_event_3 PARTITION OF va_inbox_event FOR VALUES WITH (MODULUS 8, REMAINDER 3);
CREATE TABLE va_inbox_event_4 PARTITION OF va_inbox_event FOR VALUES WITH (MODULUS 8, REMAINDER 4);
CREATE TABLE va_inbox_event_5 PARTITION OF va_inbox_event FOR VALUES WITH (MODULUS 8, REMAINDER 5);
CREATE TABLE va_inbox_event_6 PARTITION OF va_inbox_event FOR VALUES WITH (MODULUS 8, REMAINDER 6);
CREATE TABLE va_inbox_event_7 PARTITION OF va_inbox_event FOR VALUES WITH (MODULUS 8, REMAINDER 7);

-- PK on each child partition (includes partition key — required by Postgres).
ALTER TABLE va_inbox_event_0 ADD PRIMARY KEY (event_id);
ALTER TABLE va_inbox_event_1 ADD PRIMARY KEY (event_id);
ALTER TABLE va_inbox_event_2 ADD PRIMARY KEY (event_id);
ALTER TABLE va_inbox_event_3 ADD PRIMARY KEY (event_id);
ALTER TABLE va_inbox_event_4 ADD PRIMARY KEY (event_id);
ALTER TABLE va_inbox_event_5 ADD PRIMARY KEY (event_id);
ALTER TABLE va_inbox_event_6 ADD PRIMARY KEY (event_id);
ALTER TABLE va_inbox_event_7 ADD PRIMARY KEY (event_id);

-- Index on received_at per child — used by the retention DELETE job only.
CREATE INDEX ON va_inbox_event_0 (received_at);
CREATE INDEX ON va_inbox_event_1 (received_at);
CREATE INDEX ON va_inbox_event_2 (received_at);
CREATE INDEX ON va_inbox_event_3 (received_at);
CREATE INDEX ON va_inbox_event_4 (received_at);
CREATE INDEX ON va_inbox_event_5 (received_at);
CREATE INDEX ON va_inbox_event_6 (received_at);
CREATE INDEX ON va_inbox_event_7 (received_at);
