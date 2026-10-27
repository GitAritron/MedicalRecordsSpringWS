-- Keycloak's own schema, separate from the medical records.
--
-- WHY it lives here: MYSQL_DATABASE in docker-compose.yml creates exactly one schema, and Keycloak
-- needs its own. This file is mounted into docker-entrypoint-initdb.d ahead of 01-init.sql, which
-- runs the scripts in name order, so the schema exists before anything else touches the server.
--
-- Keycloak creates its ~90 tables itself on first start; nothing else belongs in this file.
CREATE DATABASE IF NOT EXISTS keycloak CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
