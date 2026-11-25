#!/usr/bin/env python3
"""Local audio fixture for trying the player on an emulator with the debug demo backend (see app/README.md, "Player on an emulator").

  python3 scripts/audio_fixture.py [--dir /tmp/opencode/audio-fixture] [--port 8765]

Generates three 60-second MP3 parts (part1.mp3 .. part3.mp3, distinct tones, 64 kbit/s) with ffmpeg when they do not exist yet, then serves the
directory over HTTP WITH Range support (what a debrid CDN does and what the player's seeking needs). The emulator reaches it as
http://10.0.2.2:<port>/ (FakeBackend.FIXTURE_BASE). Every request is logged, with its Range and whether an Authorization header came along
(it must never: the CDN hop carries no token)."""
import argparse
import os
import re
import subprocess
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

PARTS = {"part1.mp3": 440, "part2.mp3": 554, "part3.mp3": 659}


def generate(directory):
    os.makedirs(directory, exist_ok=True)
    for name, freq in PARTS.items():
        path = os.path.join(directory, name)
        if os.path.exists(path):
            continue
        # A tone with a slow beat so seeking and speed are audible; mono 22.05 kHz keeps the files small.
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", f"sine=frequency={freq}:duration=60", "-af", "tremolo=f=2:d=0.5",
             "-c:a", "libmp3lame", "-b:a", "64k", "-ar", "22050", "-ac", "1", "-metadata", f"title={name}", path],
            check=True,
        )


class RangeHandler(SimpleHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("%s Range=%s Authorization=%s\n" % (fmt % args, self.headers.get("Range"), "PRESENT" if self.headers.get("Authorization") else "none"))

    def send_head(self):
        path = self.translate_path(self.path)
        if not os.path.isfile(path):
            self.send_error(404)
            return None
        size = os.path.getsize(path)
        start, end = 0, size - 1
        match = re.match(r"bytes=(\d*)-(\d*)$", self.headers.get("Range", ""))
        ranged = False
        if match and (match.group(1) or match.group(2)):
            ranged = True
            if match.group(1):
                start = int(match.group(1))
                if match.group(2):
                    end = min(int(match.group(2)), size - 1)
            else:
                start = max(size - int(match.group(2)), 0)
            if start >= size:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.end_headers()
                return None
        f = open(path, "rb")
        f.seek(start)
        self.send_response(206 if ranged else 200)
        self.send_header("Content-Type", "audio/mpeg")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if ranged:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        self._remaining = end - start + 1
        return f

    def copyfile(self, source, outputfile):
        left = self._remaining
        while left > 0:
            chunk = source.read(min(64 * 1024, left))
            if not chunk:
                break
            try:
                outputfile.write(chunk)
            except (BrokenPipeError, ConnectionResetError):
                break
            left -= len(chunk)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default="/tmp/opencode/audio-fixture")
    ap.add_argument("--port", type=int, default=8765)
    args = ap.parse_args()
    generate(args.dir)
    os.chdir(args.dir)
    print(f"serving {args.dir} on 0.0.0.0:{args.port}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", args.port), RangeHandler).serve_forever()
