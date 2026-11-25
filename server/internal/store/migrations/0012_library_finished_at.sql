-- When a book became finished, by the SERVER clock (set when a write turns the status to finished,
-- kept while later writes keep it finished, cleared when the status goes back to listening/saved).
-- Existing finished rows get the time of their last write (updated_at is the client clock in ms).
ALTER TABLE user_library ADD COLUMN finished_at timestamptz;
UPDATE user_library SET finished_at = to_timestamp(updated_at / 1000.0) WHERE status = 'finished';
