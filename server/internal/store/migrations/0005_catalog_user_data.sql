-- Per-user library and bookmarks, per-key debrid probe results, and client-reported torrent file
-- lists (docs/DESIGN.md "App direction"). Every per-user table is scoped by user_id and goes away
-- with the user.

CREATE TABLE user_library (
    user_id          uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    asin             text NOT NULL REFERENCES books (asin) ON DELETE CASCADE,
    status           text NOT NULL CHECK (status IN ('saved', 'listening', 'finished')),
    release_hash     char(40) REFERENCES releases (info_hash) ON DELETE SET NULL,
    position_ms      bigint NOT NULL DEFAULT 0 CHECK (position_ms >= 0),
    file_path        text CHECK (file_path IS NULL OR octet_length(file_path) <= 512),
    file_position_ms bigint NOT NULL DEFAULT 0 CHECK (file_position_ms >= 0),
    speed            double precision NOT NULL DEFAULT 1.0 CHECK (speed >= 0.5 AND speed <= 3.0),
    -- client clock, ms since epoch: last write wins on this value
    updated_at       bigint NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, asin)
);
CREATE INDEX user_library_user_updated_idx ON user_library (user_id, updated_at DESC);

CREATE TABLE bookmarks (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL,
    asin        text NOT NULL,
    position_ms bigint NOT NULL CHECK (position_ms >= 0),
    note        text NOT NULL DEFAULT '' CHECK (char_length(note) <= 280),
    created_at  timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (user_id, asin) REFERENCES user_library (user_id, asin) ON DELETE CASCADE
);
CREATE INDEX bookmarks_user_asin_idx ON bookmarks (user_id, asin, position_ms, created_at);

-- Cache state of a release at a provider as seen by ONE debrid key (by fingerprint), for providers
-- whose cache is per account (Real-Debrid). Never read by another key; the shared `availability`
-- table only ever holds TorBox.
CREATE TABLE user_availability (
    fingerprint text NOT NULL,
    provider    text NOT NULL,
    info_hash   char(40) NOT NULL CHECK (info_hash ~ '^[0-9a-f]{40}$'),
    cached      boolean NOT NULL,
    checked_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (fingerprint, provider, info_hash)
);

-- File lists reported by phones that fetched the torrent metadata (metainfo index). Kept apart
-- from release_files (the debrid provider's index). Stored once per hash, never overwritten.
CREATE TABLE release_client_files (
    info_hash   char(40) PRIMARY KEY REFERENCES releases (info_hash) ON DELETE CASCADE,
    files       jsonb NOT NULL,
    reported_at timestamptz NOT NULL DEFAULT now()
);
