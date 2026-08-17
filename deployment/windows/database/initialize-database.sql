\set ON_ERROR_STOP on

-- Run as the PostgreSQL administrative role against the postgres database.
-- Passwords are deliberately not stored here. Set them interactively with
-- \password yumpoo_migrator and \password yumpoo_app after this script runs.

SELECT 'CREATE ROLE yumpoo_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'yumpoo_migrator')
\gexec

SELECT 'CREATE ROLE yumpoo_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'yumpoo_app')
\gexec

ALTER ROLE yumpoo_migrator NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
ALTER ROLE yumpoo_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;

SELECT 'CREATE DATABASE yumpoo OWNER yumpoo_migrator ENCODING ''UTF8'' TEMPLATE template0'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'yumpoo')
\gexec

\connect yumpoo

ALTER DATABASE yumpoo OWNER TO yumpoo_migrator;
REVOKE ALL ON DATABASE yumpoo FROM PUBLIC;
GRANT CONNECT ON DATABASE yumpoo TO yumpoo_migrator, yumpoo_app;

REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA IF NOT EXISTS yumpoo AUTHORIZATION yumpoo_migrator;
ALTER SCHEMA yumpoo OWNER TO yumpoo_migrator;
REVOKE ALL ON SCHEMA yumpoo FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA yumpoo TO yumpoo_migrator;
GRANT USAGE ON SCHEMA yumpoo TO yumpoo_app;

-- Existing-object grants make this script safe to rerun after Flyway. Default
-- privileges ensure future Flyway objects created by yumpoo_migrator are usable
-- by the runtime account without granting DDL privileges to that account.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA yumpoo TO yumpoo_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA yumpoo TO yumpoo_app;

ALTER DEFAULT PRIVILEGES FOR ROLE yumpoo_migrator IN SCHEMA yumpoo
    REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE yumpoo_migrator IN SCHEMA yumpoo
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO yumpoo_app;
ALTER DEFAULT PRIVILEGES FOR ROLE yumpoo_migrator IN SCHEMA yumpoo
    REVOKE ALL ON SEQUENCES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE yumpoo_migrator IN SCHEMA yumpoo
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO yumpoo_app;

SELECT current_database() AS database_name,
       pg_get_userbyid(datdba) AS database_owner
FROM pg_database
WHERE datname = current_database();

SELECT nspname AS schema_name,
       pg_get_userbyid(nspowner) AS schema_owner
FROM pg_namespace
WHERE nspname IN ('public', 'yumpoo')
ORDER BY nspname;
