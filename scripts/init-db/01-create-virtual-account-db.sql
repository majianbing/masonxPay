-- Creates the Virtual Account service database. Runs only on first Postgres
-- init (empty data volume). The database is used solely when the
-- `virtual-account` compose profile is enabled; it is an empty, harmless extra
-- database otherwise. VA's own Flyway (db/migration/va) creates its tables.
CREATE DATABASE msx_virtual_account;
