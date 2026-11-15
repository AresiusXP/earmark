CREATE TABLE books (
    asin            text PRIMARY KEY,
    title           text NOT NULL DEFAULT '',
    subtitle        text NOT NULL DEFAULT '',
    authors         text[] NOT NULL DEFAULT '{}',
    narrators       text[] NOT NULL DEFAULT '{}',
    series          text NOT NULL DEFAULT '',
    series_position text NOT NULL DEFAULT '',
    language        text NOT NULL DEFAULT '',
    runtime_min     integer NOT NULL DEFAULT 0,
    cover_url       text NOT NULL DEFAULT '',
    description     text NOT NULL DEFAULT '',
    region          text NOT NULL DEFAULT '',
    release_year    integer NOT NULL DEFAULT 0,
    genres          text[] NOT NULL DEFAULT '{}',
    fetched_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE releases (
    info_hash    char(40) PRIMARY KEY
                 CHECK (info_hash ~ '^[0-9a-f]{40}$'),
    title        text NOT NULL DEFAULT '',
    source       text NOT NULL DEFAULT '',
    magnet       text NOT NULL DEFAULT '',
    size         bigint NOT NULL DEFAULT 0,
    seeders      integer NOT NULL DEFAULT 0,
    published_at timestamptz,
    parsed       jsonb NOT NULL DEFAULT '{}',
    first_seen   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE book_releases (
    asin      text NOT NULL REFERENCES books (asin) ON DELETE CASCADE,
    info_hash char(40) NOT NULL REFERENCES releases (info_hash) ON DELETE CASCADE,
    score     double precision NOT NULL DEFAULT 0,
    PRIMARY KEY (asin, info_hash)
);
CREATE INDEX book_releases_asin_score_idx ON book_releases (asin, score DESC);
CREATE INDEX book_releases_info_hash_idx ON book_releases (info_hash);

CREATE TABLE release_files (
    info_hash char(40) NOT NULL REFERENCES releases (info_hash) ON DELETE CASCADE,
    idx       integer NOT NULL,
    path      text NOT NULL,
    size      bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (info_hash, idx)
);

CREATE TABLE availability (
    provider   text NOT NULL,
    info_hash  char(40) NOT NULL CHECK (info_hash ~ '^[0-9a-f]{40}$'),
    cached     boolean NOT NULL,
    checked_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, info_hash)
);

CREATE TABLE release_searches (
    asin        text PRIMARY KEY,
    searched_at timestamptz NOT NULL
);
