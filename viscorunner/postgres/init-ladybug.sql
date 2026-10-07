-- The store-less stacks (viscolink, core): Ladybug's own database. The suite stack uses init-databases.sql.
--
-- The suite compose mounts this whole directory as docker-entrypoint-initdb.d, so this file runs there too,
-- after init-databases.sql has already created "ladybug". CREATE DATABASE has no IF NOT EXISTS; \gexec runs
-- the statement only when the SELECT produces it. (A plain CREATE DATABASE would abort the suite's first
-- start: the image entrypoint runs psql with ON_ERROR_STOP.)
SELECT 'CREATE DATABASE ladybug OWNER visco'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'ladybug')\gexec
