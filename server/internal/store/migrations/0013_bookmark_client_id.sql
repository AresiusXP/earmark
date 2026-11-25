-- Client-generated id of a bookmark (a UUID by convention), so a retried POST returns the bookmark it
-- created instead of a second one. Unique per (user, book); bookmarks without one are unaffected.
ALTER TABLE bookmarks ADD COLUMN client_id text CHECK (client_id IS NULL OR (char_length(client_id) BETWEEN 1 AND 64));
CREATE UNIQUE INDEX bookmarks_client_id_idx ON bookmarks (user_id, asin, client_id) WHERE client_id IS NOT NULL;
