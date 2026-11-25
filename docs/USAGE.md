# Using the audiobooks addon

This is the guide for **listeners**. How the addon is built is in [DESIGN.md](DESIGN.md).

The addon finds audiobook torrents, checks which ones your debrid service (TorBox) already has,
and plays them from the debrid service. You can listen in two ways:

| | Stremio | Podcast app |
|---|---|---|
| Browse, search, best sellers | yes | no (you open a book from Stremio first) |
| Screen off / background playback on Android | **no, playback stops** | **yes** |
| Remembers where you stopped | yes (inside Stremio) | yes (inside the app) |
| Lock-screen controls, sleep timer, speed | limited | yes |

Use Stremio to **find** a book and a podcast app to **listen**.

## 1. Install the addon

1. Open `https://<your-instance>/configure` in a browser (for the homelab:
   <https://earmark.aresius.xyz/configure>).
2. Enter the **instance password** (set by whoever runs the instance), your **TorBox API key**
   (TorBox → Settings → API), the Audible region, and your languages (English and/or Nederlands).
   Your key is checked with TorBox and then sealed into the install link. It is never shown again and
   never appears in clear text in a URL.
3. Press **Create install link**, then **Install in Stremio**. If the button does not open Stremio,
   copy the manifest URL shown below it and paste it in Stremio → Addons → *Add addon*.

To change settings later, open `/configure` again. Leave a key field empty to keep the old key. Every
change creates a **new** install link: install it, then remove the old addon in Stremio.

> The install link, the feed links and the player links all contain your sealed settings. Anyone who
> has one can play from your TorBox account. Do not share them. If one leaks, ask the operator to
> rotate `CONFIG_SECRET`: that invalidates every existing link.

## 2. Find and play a book in Stremio

* Open **Discover**, choose the type **Audiobook** and the catalog **Best sellers**, or use the
  search box (title or author).
* Open a book. The list below it is **not** episodes of a series:
  * every **season** is one **release** (one torrent) of the book, best first;
  * every **episode** is a file of that release (chapters), or one *Full book* entry for a release
    that is a single file (usually an M4B);
  * the line under an episode shows the format, size, seeders and where it is available:
    `⚡ TB` = already cached on TorBox (starts instantly), `not cached` = TorBox must fetch it first;
  * *from a series pack* = the release is a whole-series torrent; only the files of this book are
    listed.
* Pick a cached (`⚡`) season, then an episode. Stremio remembers your position per file, and plays the
  next chapter automatically.

**Nothing found?** Only releases that match the book (title, author, language) are shown. Cached
releases come first, and when your TorBox has none, uncached ones are hidden unless *Show releases that
are not cached* is on in `/configure`.

**You hear ten seconds of silence?** The release is not ready on TorBox yet. The addon has asked TorBox
to fetch it. Wait a few minutes and play it again.

## 3. Listen with the screen off: the podcast feed

Stremio on Android stops the audio when the screen turns off, and there is no setting to change that.
The addon therefore also publishes every book as a **private podcast feed**: one episode per chapter
file, in order. Podcast apps are built for background listening.

1. In Stremio, open the book, tap any episode, and in the list of streams choose
   **ABK 🎧 Listen in a podcast app** (the last entry). It opens a page in your browser. Some Stremio
   versions also show a *Listen in a podcast app* link on the book page itself. (You can also open
   `https://<your-instance>/<your-config>/listen/<ASIN>` directly.)
2. Choose the release you want (prefer one marked `⚡ TB`).
3. Subscribe with one tap, or copy the feed URL:

   | App | How |
   |---|---|
   | **AntennaPod** (Android, free) | press *Open in AntennaPod*, or *Add podcast → Add podcast by RSS address* and paste the URL |
   | **Pocket Casts** | press *Open in Pocket Casts* |
   | **Audiobookshelf** (self-hosted) | create a *Podcast* library, *Add podcast → RSS feed*, paste the URL |
   | **Any other podcast app** | add by URL / RSS feed and paste it; or try the *podcast://* link |

4. **Set up the app once:**
   * **turn off automatic download** for this feed (otherwise the app may download every chapter at
     once, and, for a release that is not cached yet, download the ten-second silence instead of the
     audio);
   * play in **oldest first** order (the feed is marked *serial*, so most apps already do);
   * set playback speed, sleep timer and skip lengths in the app as usual.
5. Press play. Chapters are streamed from TorBox through the addon. The app keeps your position, so you
   can close it and continue later, even with the screen off.

Tips and limits:

* **Progress is not shared** between Stremio and the podcast app. Pick one per book.
* One feed = one release. If you switch release, subscribe to that release's feed; the app then shows
  it as a separate podcast.
* A feed from the list on the *listen* page is pinned to one specific release, so its episodes stay
  stable. If that release later drops out of the releases the addon shows for the book (for example
  after more or better releases are found, or when *Releases per book* is low), the pinned feed
  answers *not found*: open the *listen* page again and subscribe to a current one. The addon's other
  feed links (*best release*, *season N*) follow the current ranking; they may point to a different
  release later, and an app would then list duplicate episodes.
* A single-file M4B appears as **one long episode**. Chapter markers inside the file are shown if
  your app supports embedded chapters (AntennaPod and Audiobookshelf do for M4B).
* A feed only works while the addon can reach your debrid service; it does not download anything to
  the server.
* For a release that is **not** cached, the first play shows ten seconds of silence while TorBox
  fetches it. Play again after a few minutes. Cached (`⚡`) releases play immediately.

## 4. Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| *"Invalid or expired API key"* when saving | The TorBox key is wrong, expired or your plan is inactive. |
| A book shows no releases | No cached or matching release exists right now. Enable *Show releases that are not cached*, or try again later (sources are searched again after some hours). |
| Only a Spanish/other-language release | Choose your languages in `/configure`; releases in other languages are hidden. |
| Playback gives 10 s of silence | Release not ready on TorBox yet: retry in a few minutes. |
| Stream starts but the wrong chapter order | Re-open the book; chapters are sorted by file name. Tell the operator if a release is mislabeled. |
| A release is missing that was there yesterday | Rankings change. Releases that are only a `.zip`/`.rar` archive are never shown (TorBox cannot stream archives). |
| The podcast app shows 0-byte or silent episodes | Auto-download ran for a not-ready release: delete the downloads, disable auto-download, and play when the release is cached. |
| All links stopped working | The operator rotated the instance secret: create a new install link in `/configure`. |

## 5. For the operator

* Environment variables: `DATABASE_URL`, `CONFIG_SECRET` (at least 32 bytes), `PUBLIC_URL`,
  `INSTANCE_PASSWORD` (required unless `ALLOW_OPEN_CONFIG=true`), `PORT`, `ADMIN_PORT` (Prometheus
  metrics, never exposed by the Ingress), `ABB_BASE_URLS` (`off` disables AudioBook Bay), `LOG_LEVEL`.
* `scripts/smoke.sh` runs the whole flow against the real APIs with a TorBox key.
* The addon only uses public indexes, never private trackers, and only talks to the debrid service on
  behalf of the person whose key is sealed in the link.
