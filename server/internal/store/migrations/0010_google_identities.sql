-- Sign in with Google as an additional sign-in method (docs/DESIGN.md "App direction"). A Google
-- identity (the stable `sub` claim) belongs to at most one user and a user has at most one
-- identity. Google never creates a user by itself: rows are inserted only for an existing user
-- (link) or in the transaction that redeems an invite (join).

CREATE TABLE google_identities (
    sub        text PRIMARY KEY CHECK (char_length(sub) BETWEEN 1 AND 255),
    user_id    uuid NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    email      text NOT NULL CHECK (char_length(email) BETWEEN 1 AND 320),
    linked_at  timestamptz NOT NULL DEFAULT now()
);
