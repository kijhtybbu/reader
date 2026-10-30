#!/usr/bin/env python3
"""Build the bundled ECDICT StarDict asset.

This is a build-time data preparation script, not runtime code. It downloads the
official ECDICT CSV (MIT), converts it to StarDict (.ifo/.idx/.dict), packs the
three files into the LZMA2 7z that ships in app/src/main/assets, and copies only
that 7z into the assets directory.

Usage:
    python3 tools/dict/prepare_ecdict.py [--csv path] [--work-dir dir] [--assets-dir dir]

Requires `bsdtar` (libarchive) to create the 7z. The conversion itself uses only
the Python standard library.

The archive is reproducible: the three inputs get a fixed mtime before packing
(SOURCE_DATE_EPOCH when set, otherwise FIXED_MTIME below) and are packed through a
ustar tar, so the same CSV always produces the same stardict.7z SHA-256 that
NOTICE.txt pins. See pack_seven_zip for why the tar step is needed.

Entry markup (the app's lookup card parses it):

    <!--ecdict|…--><b>word</b><br><span style="color:#888">/phonetic/</span>
    <br>translations<!--def-->definitions<br><i>pos</i><br><small>exchange</small>

The `<!--ecdict|…-->` comment carries Collins stars / Oxford flag / exam tags /
frequency ranks, and `<!--def-->` marks the start of the English (WordNet) glosses.
Both are invisible; the card uses them to split senses, glosses and badges.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import html
import os
import shutil
import struct
import subprocess
import sys
import urllib.request

ECDICT_CSV_URL = "https://raw.githubusercontent.com/skywind3000/ECDICT/master/ecdict.csv"
ECDICT_CSV_SHA256 = "d1ecbdf9ee2b920afb6f430ca6b6bb9f559588cf0dff86e04bd255f9390843cc"

# Invisible markers consumed by the lookup card (see module docstring). Keep the
# literal strings in sync with LOOKUP_DEFINITION_MARKER / META_COMMENT_REGEX in
# app/src/main/java/ua/acclorite/book_story/ui/reader/ReaderLookupArticle.kt.
LOOKUP_DEFINITION_MARKER = "<!--def-->"
# Intermediates (66MB CSV + 90MB StarDict) stay in build/, which is gitignored;
# only stardict.7z is copied into the assets directory.
DEFAULT_WORK_DIR = os.path.join("build", "ecdict")
DEFAULT_ASSETS_DIR = os.path.join("app", "src", "main", "assets", "dictionaries", "ecdict")

# 2024-01-01T00:00:00Z: 7z stores each entry's mtime, so a rebuild with "now"
# would change the archive bytes (hence the SHA-256) every time.
FIXED_MTIME = 1704067200


def sha256(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, target: str) -> None:
    print(f"downloading {url}")
    with urllib.request.urlopen(url) as response, open(target, "wb") as out:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            out.write(chunk)


def meta_comment(row: dict[str, str]) -> str:
    """Invisible dictionary metadata consumed by the lookup card.

    Format: `<!--ecdict|c=<collins>|o=1|t=<tag1,tag2>|b=<bnc>|f=<frq>-->`.
    Only non-empty fields are written; an entry with no metadata gets no comment.
    """
    parts: list[str] = []
    collins = (row.get("collins") or "").strip()
    if collins:
        parts.append(f"c={collins}")
    if (row.get("oxford") or "").strip() == "1":
        parts.append("o=1")
    tag = " ".join((row.get("tag") or "").split())
    if tag:
        parts.append("t=" + ",".join(tag.split()))
    for key, column in (("b", "bnc"), ("f", "frq")):
        value = (row.get(column) or "").strip()
        if value and value != "0":
            parts.append(f"{key}={value}")
    return f"<!--ecdict|{'|'.join(parts)}-->" if parts else ""


def read_entries(csv_path: str) -> dict[str, str]:
    words: dict[str, str] = {}
    with open(csv_path, encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            word = (row.get("word") or "").strip()
            if not word:
                continue

            phonetic = (row.get("phonetic") or "").strip()
            translation = (row.get("translation") or "").strip()
            definition = (row.get("definition") or "").strip()
            pos = (row.get("pos") or "").strip()
            exchange = (row.get("exchange") or "").strip()

            body = meta_comment(row) + f"<b>{html.escape(word)}</b>"
            if phonetic:
                body += f'<br><span style="color:#888">/{html.escape(phonetic)}/</span>'
            if translation:
                body += "<br>" + html.escape(translation).replace("\n", "<br>")
            if definition:
                body += "<br>" + LOOKUP_DEFINITION_MARKER + html.escape(definition).replace("\n", "<br>")
            if pos:
                body += f"<br><i>{html.escape(pos)}</i>"
            if exchange:
                body += f"<br><small>{html.escape(exchange)}</small>"
            words[word] = body
    return words


def write_stardict(words: dict[str, str], out_dir: str) -> tuple[str, str, str]:
    os.makedirs(out_dir, exist_ok=True)
    idx_path = os.path.join(out_dir, "ecdict.idx")
    dict_path = os.path.join(out_dir, "ecdict.dict")
    ifo_path = os.path.join(out_dir, "ecdict.ifo")

    keys = sorted(words, key=lambda value: (value.lower(), value))
    position = 0
    with open(idx_path, "wb") as idx, open(dict_path, "wb") as data:
        for word in keys:
            key = word.encode("utf-8")
            body = words[word].encode("utf-8")
            idx.write(key + b"\0" + struct.pack(">II", position, len(body)))
            data.write(body)
            position += len(body)

    with open(ifo_path, "w", encoding="utf-8") as ifo:
        ifo.write("StarDict's dict ifo file\nversion=2.4.2\n")
        ifo.write(f"wordcount={len(keys)}\n")
        ifo.write(f"idxfilesize={os.path.getsize(idx_path)}\n")
        ifo.write("bookname=ECDICT\n")
        ifo.write("author=skywind3000\n")
        ifo.write("description=ECDICT (MIT) converted to StarDict for offline reading\n")
        ifo.write("date=2026.10.30\nsametypesequence=h\n")
    return ifo_path, idx_path, dict_path


def pack_seven_zip(out_dir: str, files: tuple[str, str, str]) -> str:
    archive = os.path.abspath(os.path.join(out_dir, "stardict.7z"))
    if os.path.exists(archive):
        os.remove(archive)
    # A 7z entry carries mtime/atime/ctime and bsdtar fills all three from stat(); ctime
    # cannot be set from userspace, so packing the files directly makes the archive bytes
    # (hence the SHA-256) differ on every rebuild. Packing a ustar tar first drops
    # atime/ctime (ustar only has mtime), so the 7z becomes a pure function of the
    # file contents plus the pinned mtime.
    epoch = int(os.environ.get("SOURCE_DATE_EPOCH", FIXED_MTIME))
    for path in files:
        os.chmod(path, 0o644)
        os.utime(path, (epoch, epoch))
    names = [os.path.basename(path) for path in files]
    tar = os.path.abspath(os.path.join(out_dir, "stardict.tar"))
    subprocess.run(
        ["bsdtar", "-c", "-f", tar, "--format", "ustar",
         "--uid", "0", "--gid", "0", "--uname", "", "--gname", "", *names],
        cwd=out_dir,
        check=True,
    )
    try:
        subprocess.run(
            ["bsdtar", "-c", "-f", archive, "--format", "7zip", f"@{tar}"],
            check=True,
        )
    finally:
        os.remove(tar)
    return archive


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", help="existing ecdict.csv (downloaded into --work-dir when omitted)")
    parser.add_argument("--work-dir", default=DEFAULT_WORK_DIR, help="intermediates + archive")
    parser.add_argument("--assets-dir", default=DEFAULT_ASSETS_DIR, help="where stardict.7z is copied")
    parser.add_argument(
        "--keep-intermediates",
        action="store_true",
        help="keep the 90MB .ifo/.idx/.dict in --work-dir (the CSV is always kept)",
    )
    args = parser.parse_args()

    os.makedirs(args.work_dir, exist_ok=True)
    csv_path = args.csv
    if not csv_path:
        csv_path = os.path.join(args.work_dir, "ecdict.csv")
        if not os.path.exists(csv_path):
            download(ECDICT_CSV_URL, csv_path)
    actual = sha256(csv_path)
    if actual != ECDICT_CSV_SHA256:
        print(f"ERROR: ecdict.csv SHA-256 mismatch\n  expected {ECDICT_CSV_SHA256}\n  actual   {actual}")
        return 1

    words = read_entries(csv_path)
    print(f"entries: {len(words)}")
    files = write_stardict(words, args.work_dir)
    archive = pack_seven_zip(args.work_dir, files)
    for path in (*files, archive):
        print(f"{os.path.basename(path)}: {os.path.getsize(path)} bytes")
    print(f"stardict.7z SHA-256: {sha256(archive)}")

    os.makedirs(args.assets_dir, exist_ok=True)
    shipped = os.path.join(args.assets_dir, "stardict.7z")
    shutil.copyfile(archive, shipped)
    print(f"copied to {shipped} ({os.path.getsize(shipped)} bytes)")
    if not args.keep_intermediates:
        for path in files:
            os.remove(path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
