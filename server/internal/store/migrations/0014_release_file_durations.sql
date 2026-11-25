-- Audio durations reported by phones (they decode the files they downloaded), per release file path.
-- The path is the NORMALISED path (see model.NormalizePath) of a file in one of the release's known file
-- lists; the first report of a (release, path) wins and is never replaced, so a later differing report
-- (another user, another rip of the same torrent) cannot change what everybody sees.
CREATE TABLE release_file_durations (
    info_hash   char(40) NOT NULL REFERENCES releases (info_hash) ON DELETE CASCADE,
    path        text NOT NULL CHECK (path <> '' AND octet_length(path) <= 512),
    duration_ms bigint NOT NULL CHECK (duration_ms BETWEEN 1000 AND 172800000),
    reported_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (info_hash, path)
);
