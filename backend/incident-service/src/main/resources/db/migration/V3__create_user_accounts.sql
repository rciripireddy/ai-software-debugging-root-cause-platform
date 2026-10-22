-- Phase 6: user accounts for signing in to the dashboard and the APIs. incident-service issues
-- the access tokens, so it owns the accounts, in a schema of their own.
CREATE SCHEMA IF NOT EXISTS auth;

CREATE TABLE auth.users (
    id            uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Stored in lower case; sign-in is case-insensitive.
    username      varchar(100) NOT NULL,
    display_name  varchar(100) NOT NULL,
    -- Encoded by Spring Security's DelegatingPasswordEncoder, e.g. '{bcrypt}$2a$12$...'.
    password_hash varchar(255) NOT NULL,
    role          varchar(20)  NOT NULL,
    enabled       boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT ck_users_username_lowercase CHECK (username = lower(username)),
    CONSTRAINT ck_users_role CHECK (role IN ('ADMIN', 'VIEWER')),
    -- Only encoded hashes with an algorithm prefix are accepted, never a plain password.
    CONSTRAINT ck_users_password_hash_encoded CHECK (password_hash ~ '^\{[a-z0-9]+\}.{20,}$')
);
