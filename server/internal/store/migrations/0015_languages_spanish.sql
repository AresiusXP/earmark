-- Spanish ('es') joins English and Dutch as a book language (docs/DESIGN.md). The column CHECK of
-- 0003 was created inline, so it carries Postgres' generated name.
ALTER TABLE users DROP CONSTRAINT users_languages_check;
ALTER TABLE users ADD CONSTRAINT users_languages_check
    CHECK (cardinality(languages) >= 1 AND languages <@ ARRAY['en', 'nl', 'es']);
