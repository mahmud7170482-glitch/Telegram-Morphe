#!/usr/bin/env python3
"""
Resolve and download the two tools needed to patch an app with Morphe:

  * Morphe CLI     - MorpheApp/morphe-desktop   (morphe-desktop-<ver>-all.jar)
  * Morphe patches - any GitHub repo that publishes a patches-<ver>.mpp release asset
                     (default for this project: SysAdminDoc/HushMessenger)

For each repo the newest suitable GitHub release is picked, the right asset is chosen
(end-anchored match, so .sha256 / .asc / -sources / -javadoc files are never selected), the
file is downloaded and checked to be a real zip/jar, and the result is written to $GITHUB_OUTPUT.

Third-party patches run inside your app, so the patches file is also checked against the
SHA-256 checksum the author publishes next to it (SHA256SUMS.txt or <file>.sha256), when there is one.
A checksum that does NOT match is a hard error.

Usage:
  resolve_assets.py --out-dir tools --patches-repo OWNER/REPO [--prerelease] [--cli-url URL] [--patches-url URL]
Test hooks:
  --cli-releases-json FILE / --patches-releases-json FILE  (use a saved API response)
"""
import argparse
import hashlib
import json
import os
import re
import sys
import time
import urllib.request
import zipfile

CLI_REPO = "MorpheApp/morphe-desktop"
DEFAULT_PATCHES_REPO = "MorpheApp/morphe-patches"
REPO_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
SUMS_NAME_RE = re.compile(r"^sha256sums?(\.txt)?$", re.I)
API = os.environ.get("GITHUB_API_URL", "https://api.github.com")

CLI_ASSET_RE = re.compile(r"^morphe-desktop-.+-all\.jar$")
PATCHES_ASSET_RE = re.compile(r"^patches-.+\.mpp$")
PATCHES_ASSET_BAD = re.compile(r"-(sources|javadoc)\.mpp$")


def _headers():
    h = {"Accept": "application/vnd.github+json", "User-Agent": "morphe-youtube-builder"}
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        h["Authorization"] = f"Bearer {token}"
    return h


def _get(url, retries=3):
    last = None
    for attempt in range(1, retries + 1):
        try:
            req = urllib.request.Request(url, headers=_headers())
            with urllib.request.urlopen(req, timeout=60) as resp:
                return resp.read()
        except Exception as exc:  # network hiccup / rate limit
            last = exc
            print(f"  attempt {attempt}/{retries} failed for {url}: {exc}")
            time.sleep(3 * attempt)
    raise RuntimeError(f"Could not fetch {url}: {last}")


def pick_release(releases, asset_re, bad_re=None, allow_prerelease=False):
    """Return (tag, asset_name, download_url, all_assets) for the newest matching release."""
    for rel in releases:
        if rel.get("draft"):
            continue
        if rel.get("prerelease") and not allow_prerelease:
            continue
        for asset in rel.get("assets", []):
            name = asset.get("name", "")
            if asset_re.match(name) and not (bad_re and bad_re.search(name)):
                return rel.get("tag_name", ""), name, asset["browser_download_url"], rel.get("assets", [])
    return None


def find_expected_sha256(assets, asset_name):
    """Look for a published checksum for asset_name. Returns (hash or None, source description)."""
    candidates = [a for a in assets if SUMS_NAME_RE.match(a.get("name", "")) or a.get("name") == asset_name + ".sha256"]
    for a in candidates:
        try:
            text = _get(a["browser_download_url"]).decode("utf-8", "replace")
        except Exception as exc:
            print(f"  could not read {a['name']}: {exc}")
            continue
        for line in text.splitlines():
            m = re.match(r"^\s*([0-9a-fA-F]{64})\s+\*?(\S+)\s*$", line)
            if m and m.group(2).split("/")[-1] == asset_name:
                return m.group(1).lower(), a["name"]
        only = re.match(r"^\s*([0-9a-fA-F]{64})\s*$", text)  # bare "<file>.sha256" content
        if only and a["name"] == asset_name + ".sha256":
            return only.group(1).lower(), a["name"]
    return None, ""


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_releases(repo, json_file=None):
    if json_file:
        with open(json_file, encoding="utf-8") as fh:
            return json.load(fh)
    data = _get(f"{API}/repos/{repo}/releases?per_page=30")
    return json.loads(data)


def download_zip(url, dest):
    print(f"Downloading {url}")
    data = _get(url)
    with open(dest, "wb") as fh:
        fh.write(data)
    if not zipfile.is_zipfile(dest):
        head = data[:120].decode("utf-8", "replace")
        raise RuntimeError(f"{dest} is not a valid jar/zip ({len(data)} bytes). Starts with: {head!r}")
    print(f"OK: {dest} ({len(data)} bytes)")


def set_output(key, value):
    path = os.environ.get("GITHUB_OUTPUT")
    line = f"{key}={value}\n"
    if path:
        with open(path, "a", encoding="utf-8") as fh:
            fh.write(line)
    print(f"output {key}={value}")


def version_from(tag):
    return re.sub(r"^[vV]", "", tag or "")


def resolve(kind, repo, asset_re, bad_re, allow_prerelease, override_url, json_file):
    if override_url:
        name = override_url.rsplit("/", 1)[-1]
        return "custom", name, override_url, []
    picked = pick_release(load_releases(repo, json_file), asset_re, bad_re, allow_prerelease)
    if not picked:
        raise RuntimeError(f"No suitable {kind} release asset found in {repo}")
    return picked


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default="tools")
    ap.add_argument("--prerelease", action="store_true", help="allow Morphe patches pre-releases")
    ap.add_argument("--patches-repo", default=DEFAULT_PATCHES_REPO, help="OWNER/REPO that publishes the patches")
    ap.add_argument("--skip-patches", action="store_true", help="only fetch the Morphe CLI")
    ap.add_argument("--cli-url", default="")
    ap.add_argument("--patches-url", default="")
    ap.add_argument("--cli-releases-json")
    ap.add_argument("--patches-releases-json")
    args = ap.parse_args()

    if not REPO_RE.match(args.patches_repo):
        raise RuntimeError(f"'{args.patches_repo}' is not a valid OWNER/REPO")
    os.makedirs(args.out_dir, exist_ok=True)

    cli_tag, cli_name, cli_url, _ = resolve(
        "Morphe CLI", CLI_REPO, CLI_ASSET_RE, None, False, args.cli_url, args.cli_releases_json)
    if args.skip_patches:
        cli_path = os.path.join(args.out_dir, "morphe-cli.jar")
        download_zip(cli_url, cli_path)
        set_output("cli_path", cli_path)
        set_output("cli_version", version_from(cli_tag))
        return
    patches_tag, patches_name, patches_url, patches_assets = resolve(
        "Morphe patches", args.patches_repo, PATCHES_ASSET_RE, PATCHES_ASSET_BAD,
        args.prerelease, args.patches_url, args.patches_releases_json)

    cli_path = os.path.join(args.out_dir, "morphe-cli.jar")
    patches_path = os.path.join(args.out_dir, "patches.mpp")
    download_zip(cli_url, cli_path)
    download_zip(patches_url, patches_path)

    actual = sha256_file(patches_path)
    expected, source = find_expected_sha256(patches_assets, patches_name)
    if expected is None:
        status = "unverified"
        print(f"::warning::{args.patches_repo} publishes no SHA-256 checksum for {patches_name}; "
              f"its integrity could not be checked (sha256 {actual}).")
    elif expected == actual:
        status = "verified"
        print(f"OK: {patches_name} matches the SHA-256 in {source}")
    else:
        raise RuntimeError(f"SHA-256 mismatch for {patches_name}: published {expected}, downloaded {actual}. "
                           "The file may have been tampered with. Not using it.")

    set_output("cli_path", cli_path)
    set_output("cli_version", version_from(cli_tag))
    set_output("patches_path", patches_path)
    set_output("patches_version", version_from(patches_tag))
    set_output("patches_is_prerelease", "true" if args.prerelease else "false")
    set_output("patches_repo", args.patches_repo)
    set_output("patches_sha256", actual)
    set_output("patches_checksum", status)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"::error::{exc}")
        sys.exit(1)
