-- Runs once, on the first start of an empty data volume (see `bb reset`).
-- Keycloak and HAPI FHIR each get their own database. `keycloak` is created last:
-- the container healthcheck probes it, so "healthy" implies both exist.
CREATE DATABASE hapi;
CREATE DATABASE keycloak;
