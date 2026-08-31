-- Run ONCE as the project database administrator in a NEW Supabase project.
-- No passwords in this file. Set both passwords securely with psql \password afterwards.
-- Existing roles/schema cause an error deliberately; do not drop existing data to retry.
BEGIN;
CREATE ROLE villa_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE villa_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT villa_migrator TO postgres;
CREATE SCHEMA villa AUTHORIZATION villa_migrator;
REVOKE ALL ON SCHEMA villa FROM PUBLIC, anon, authenticated;
GRANT USAGE ON SCHEMA villa TO villa_app;
GRANT CONNECT ON DATABASE postgres TO villa_migrator, villa_app;
-- Defaults apply to objects created by the migration role, including identity sequences.
ALTER DEFAULT PRIVILEGES FOR ROLE villa_migrator IN SCHEMA villa
  REVOKE ALL ON TABLES FROM PUBLIC, anon, authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE villa_migrator IN SCHEMA villa
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO villa_app;
ALTER DEFAULT PRIVILEGES FOR ROLE villa_migrator IN SCHEMA villa
  REVOKE ALL ON SEQUENCES FROM PUBLIC, anon, authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE villa_migrator IN SCHEMA villa
  GRANT USAGE, SELECT ON SEQUENCES TO villa_app;
ALTER DEFAULT PRIVILEGES FOR ROLE villa_migrator IN SCHEMA villa
  REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC, anon, authenticated;
ALTER ROLE villa_app SET search_path = villa;
ALTER ROLE villa_migrator SET search_path = villa;
ALTER ROLE villa_app SET timezone = 'UTC';
ALTER ROLE villa_migrator SET timezone = 'UTC';
COMMIT;
