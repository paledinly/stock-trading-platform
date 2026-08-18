-- Phase 1 verifies Flyway wiring. Domain tables are introduced in their owning phases.
CREATE TABLE platform_schema_version (
    id integer PRIMARY KEY,
    description varchar(100) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now()
);

INSERT INTO platform_schema_version (id, description)
VALUES (1, 'Phase 1 foundation');
