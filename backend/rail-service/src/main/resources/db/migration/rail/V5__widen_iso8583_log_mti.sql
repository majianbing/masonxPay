-- Widen rail_iso8583_log.mti from VARCHAR(4) to VARCHAR(20).
-- AbstractIso8583Adapter uses pseudo-MTI strings such as "TIMEOUT" (7 chars)
-- and "UNKNOWN_SEND_ERROR" (18 chars) for diagnostic log entries in addition
-- to real ISO 8583 MTIs (always 4 digits, e.g. "0100").
ALTER TABLE rail_iso8583_log ALTER COLUMN mti TYPE VARCHAR(20);
