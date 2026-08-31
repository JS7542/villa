-- Application DML permissions must not permit rewriting Flyway's audit trail.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'villa_app') THEN
    REVOKE ALL ON TABLE flyway_schema_history FROM villa_app;
  END IF;
END
$$;
