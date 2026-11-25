-- Only TorBox's cache state is the same for every account (docs/DESIGN.md "App direction"), so
-- only TorBox may live in the shared `availability` table. Older versions also wrote Real-Debrid
-- rows there ("this account has the torrent"); they would show one user's account state to the
-- next one, so they go.
DELETE FROM availability WHERE provider <> 'torbox';
