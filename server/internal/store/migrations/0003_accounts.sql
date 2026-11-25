-- Accounts for the Android app (docs/DESIGN.md "App direction"). Invite and link codes are stored
-- as SHA-256 hashes only; refresh tokens likewise.

CREATE TABLE users (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name       text NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 40),
    role               text NOT NULL CHECK (role IN ('admin', 'user')),
    disabled           boolean NOT NULL DEFAULT false,
    created_at         timestamptz NOT NULL DEFAULT now(),
    last_seen_at       timestamptz,
    -- settings (validated by the server; the CHECKs are a second line)
    region             text NOT NULL DEFAULT 'us'
                       CHECK (region IN ('us', 'uk', 'de', 'fr', 'it', 'es', 'ca', 'au', 'in', 'nl')),
    languages          text[] NOT NULL DEFAULT '{en}'
                       CHECK (cardinality(languages) >= 1 AND languages <@ ARRAY['en', 'nl']),
    prefer_format      text NOT NULL DEFAULT '' CHECK (prefer_format IN ('', 'm4b', 'mp3')),
    preferred_provider text,
    source_mode        text NOT NULL DEFAULT 'auto' CHECK (source_mode IN ('auto', 'debrid', 'p2p')),
    p2p_enabled        boolean NOT NULL DEFAULT false
);

CREATE TABLE devices (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name          text NOT NULL,
    refresh_hash  bytea NOT NULL UNIQUE,
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_seen_at  timestamptz,
    revoked_at    timestamptz
);
CREATE INDEX devices_user_idx ON devices (user_id);

CREATE TABLE invites (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code_hash   bytea NOT NULL UNIQUE,
    note        text NOT NULL DEFAULT '',
    created_by  uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    used_by     uuid REFERENCES users (id) ON DELETE SET NULL
);

CREATE TABLE link_codes (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code_hash   bytea NOT NULL UNIQUE,
    user_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz
);
CREATE INDEX link_codes_user_idx ON link_codes (user_id);

CREATE TABLE user_debrid_keys (
    user_id       uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider      text NOT NULL,
    sealed_key    text NOT NULL,
    fingerprint   text NOT NULL,
    status        text NOT NULL CHECK (status IN ('ok', 'invalid', 'error')),
    validated_at  timestamptz,
    last_error    text NOT NULL DEFAULT '',
    PRIMARY KEY (user_id, provider)
);
CREATE INDEX user_debrid_keys_fingerprint_idx ON user_debrid_keys (fingerprint);
