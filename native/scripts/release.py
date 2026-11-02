#!/usr/bin/env python3
"""Build verified signed artifacts and publish append-only GitHub releases."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def run(*args, capture=False, env=None):
    return subprocess.run(args, cwd=ROOT, env=env, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def gh(path):
    return json.loads(run("gh", "api", path, capture=True))


def metadata(channel):
    fields = dict(line.split("=", 1) for line in (ROOT / "version.properties").read_text().splitlines()
                  if line and not line.startswith("#"))
    version, code = fields["versionName"], int(fields["versionCode"])
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version) or not 1 <= code <= 2100000000:
        raise ValueError("Invalid version.properties")
    preview = channel == "preview"
    return dict(channel=channel, versionName=version + ("-preview" if preview else ""),
                versionCode=code, baseVersion=version,
                applicationId="play.ott.foss.nativeapp" + (".preview" if preview else ""),
                tag=f"v{version}" + (f"-preview.{code}" if preview else ""),
                commit=run("git", "rev-parse", "HEAD", capture=True).strip(),
                worktreeDirty=bool(run("git", "status", "--porcelain", capture=True).strip()))


def validate_source(info, repository):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid GitHub repository name")
    if run("git", "status", "--porcelain", capture=True).strip():
        raise ValueError("Release requires a clean committed worktree")
    if os.environ.get("GITHUB_SHA", info["commit"]) != info["commit"]:
        raise ValueError("Checked-out commit differs from workflow commit")


def preflight(info, repository):
    validate_source(info, repository)
    # Never move tags, replace releases/assets, or reuse a versionCode in a channel.
    pages = json.loads(run("gh", "api", "--paginate", "--slurp",
                          f"repos/{repository}/tags?per_page=100", capture=True))
    tags = [tag["name"] for page in pages for tag in page]
    if info["tag"] in tags:
        raise ValueError("Release tag already exists; bump version.properties instead of overwriting it")
    current = tuple(map(int, info["baseVersion"].split(".")))
    if info["channel"] == "preview":
        prior = [re.fullmatch(r"v([0-9]+)\.([0-9]+)\.([0-9]+)-preview\.([0-9]+)", tag) for tag in tags]
        if any(match and (current < tuple(map(int, match.groups()[:3])) or info["versionCode"] <= int(match[4])) for match in prior):
            raise ValueError("Preview version/versionCode must advance beyond published previews")
    else:
        prior = [re.fullmatch(r"v([0-9]+)\.([0-9]+)\.([0-9]+)", tag) for tag in tags]
        if any(match and current <= tuple(map(int, match.groups())) for match in prior):
            raise ValueError("Production version must be newer than every published production tag")
        import base64
        for match in filter(None, prior):
            encoded = gh(f"repos/{repository}/contents/version.properties?ref={match[0]}")["content"]
            old = dict(line.split("=", 1) for line in base64.b64decode(encoded).decode().splitlines() if "=" in line)
            if info["versionCode"] <= int(old["versionCode"]):
                raise ValueError("Production versionCode must increase")
    validate_matrix(info, repository)


def validate_matrix(info, repository):
    # The same source commit must already have passed both real OS jobs.
    runs = gh(f"repos/{repository}/actions/workflows/android.yml/runs?head_sha={info['commit']}&status=success&per_page=100")["workflow_runs"]
    required = {"Unit tests, lint and APKs", "Instrumented tests · phone-api35", "Instrumented tests · androidtv-api36"}
    for candidate in runs:
        if candidate["event"] not in {"push", "workflow_dispatch"}:
            continue
        jobs = gh(f"repos/{repository}/actions/runs/{candidate['id']}/jobs?per_page=100")["jobs"]
        if required <= {job["name"] for job in jobs if job["conclusion"] == "success"}:
            info["validatedRun"] = candidate["html_url"]
            return
    raise ValueError("No successful host + phone API35 + Android TV API36 matrix exists for this exact commit")


def signing_environment(channel, credentials):
    env = os.environ.copy()
    if credentials:
        path = Path(credentials).expanduser().resolve()
        if path.stat().st_mode & 0o077:
            raise ValueError("Signing credentials file must be readable only by its owner (chmod 600)")
        values = json.loads(path.read_text())
        for name in ("OTTPLAY_KEYSTORE", "OTTPLAY_STORE_PASSWORD", "OTTPLAY_KEY_ALIAS", "OTTPLAY_KEY_PASSWORD", "OTTPLAY_EXPECTED_CERT_SHA256"):
            env[name] = values[name]
    required = ("OTTPLAY_KEYSTORE", "OTTPLAY_STORE_PASSWORD", "OTTPLAY_KEY_ALIAS", "OTTPLAY_KEY_PASSWORD", "OTTPLAY_EXPECTED_CERT_SHA256")
    if any(not env.get(name) for name in required):
        raise ValueError("Configured signing identity is required; no debug/unsigned fallback is used")
    if not Path(env["OTTPLAY_KEYSTORE"]).is_absolute() or not Path(env["OTTPLAY_KEYSTORE"]).is_file():
        raise ValueError("OTTPLAY_KEYSTORE must be an existing absolute path")
    if not re.fullmatch(r"[0-9a-fA-F]{64}", env["OTTPLAY_EXPECTED_CERT_SHA256"]):
        raise ValueError("Expected certificate must be a 64-character SHA-256 digest without colons")
    env["OTTPLAY_SIGNING_VARIANT"] = "preview" if channel == "preview" else "release"
    return env


def build(info, credentials):
    env = signing_environment(info["channel"], credentials)
    variant = env["OTTPLAY_SIGNING_VARIANT"]
    title = variant.capitalize()
    run("./gradlew", "--no-daemon", "--no-configuration-cache", ":core:test", ":app:testDebugUnitTest",
        f":app:lint{title}", f":app:assemble{title}", f":app:bundle{title}", env=env)
    sdk = Path(env.get("ANDROID_HOME") or env["ANDROID_SDK_ROOT"])
    build_tools = sdk / "build-tools" / "36.0.0"
    apk = ROOT / f"app/build/outputs/apk/{variant}/app-{variant}.apk"
    aab = ROOT / f"app/build/outputs/bundle/{variant}/app-{variant}.aab"
    cert = env["OTTPLAY_EXPECTED_CERT_SHA256"].lower()
    verification = run(str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk), capture=True)
    if info["channel"] == "production" and any(name in verification for name in ("CN=Android Debug", "CN=OTT Play Native Preview")):
        raise ValueError("A debug or preview certificate cannot sign a production release")
    digests = re.findall(r"Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]+)", verification)
    if [digest.lower() for digest in digests] != [cert]:
        raise ValueError("APK signer does not match the configured signing certificate")
    badging = run(str(build_tools / "aapt2"), "dump", "badging", str(apk), capture=True)
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not package or package.groups() != (info["applicationId"], str(info["versionCode"]), info["versionName"]):
        raise ValueError("APK applicationId/version does not match version.properties and release channel")
    verification = run("jarsigner", "-J-Duser.language=en", "-verify", "-strict", "-keystore", env["OTTPLAY_KEYSTORE"],
                       "-storepass:env", "OTTPLAY_STORE_PASSWORD", str(aab), capture=True, env=env)
    if "jar verified." not in verification:
        raise ValueError("AAB is not cryptographically verified")
    aab_cert = run("keytool", "-J-Duser.language=en", "-printcert", "-jarfile", str(aab), capture=True)
    aab_digests = re.findall(r"SHA256: ([0-9A-F:]+)", aab_cert)
    if not aab_digests or aab_digests[0].replace(":", "").lower() != cert:
        raise ValueError("AAB signer does not match the configured signing certificate")
    out = ROOT / "build/distributions" / info["tag"]
    out.mkdir(parents=True, exist_ok=True)
    info["signingCertificateSha256"] = cert
    if os.environ.get("GITHUB_ACTIONS") == "true":
        info["buildRunId"] = int(os.environ["GITHUB_RUN_ID"])
        info["buildRunAttempt"] = int(os.environ["GITHUB_RUN_ATTEMPT"])
    info["artifacts"] = []
    for artifact in (apk, aab):
        destination = out / f"ottplay-native-{info['tag'][1:]}{artifact.suffix}"
        shutil.copyfile(artifact, destination)
        info["artifacts"].append(dict(name=destination.name, bytes=destination.stat().st_size,
                                      sha256=hashlib.sha256(destination.read_bytes()).hexdigest()))
    (out / "release-manifest.json").write_text(json.dumps(info, indent=2) + "\n")
    names = [item["name"] for item in info["artifacts"]] + ["release-manifest.json"]
    (out / "SHA256SUMS").write_text("".join(f"{hashlib.sha256((out/name).read_bytes()).hexdigest()}  {name}\n" for name in names))
    print(f"Verified signed {info['channel']} APK and AAB: {out}")


def publish(info, repository):
    preflight(info, repository)
    out = ROOT / "build/distributions" / info["tag"]
    recorded = json.loads((out / "release-manifest.json").read_text())
    if any(recorded.get(key) != value for key, value in info.items() if key != "validatedRun"):
        raise ValueError("Release manifest differs from current commit/version")
    for item in recorded["artifacts"]:
        if Path(item["name"]).name != item["name"] or hashlib.sha256((out / item["name"]).read_bytes()).hexdigest() != item["sha256"]:
            raise ValueError("Release artifact checksum changed")
    notes = f"Commit: `{info['commit']}`\n\nVersionCode: {info['versionCode']}. Signed APK and AAB; checksums and certificate fingerprint are attached.\n\nVerified host + phone/TV matrix: {info['validatedRun']}\n"
    if info["channel"] == "preview":
        notes += "\nPreview only. Application ID `play.ott.foss.nativeapp.preview` coexists with the previous app. Export/import sources to migrate; the exported file contains credentials. AAB is not directly installable. This is not a Google Play production release.\n"
    (out / "release-notes.md").write_text(notes)
    # REST tag creation is atomic and refuses an existing ref. No --force/clobber.
    run("gh", "api", "--method", "POST", f"repos/{repository}/git/refs", "-f", f"ref=refs/tags/{info['tag']}", "-f", f"sha={info['commit']}", capture=True)
    assets = [str(out / item["name"]) for item in recorded["artifacts"]] + [str(out / "release-manifest.json"), str(out / "SHA256SUMS")]
    run("gh", "release", "create", info["tag"], *assets, "--repo", repository, "--verify-tag", "--draft",
        "--title", f"OTT Play Native {info['tag'][1:]}", "--notes-file", str(out / "release-notes.md"))
    args = ["gh", "release", "edit", info["tag"], "--repo", repository, "--draft=false"]
    if info["channel"] == "preview":
        args.extend(["--prerelease", "--latest=false"])
    run(*args)


def promote(info, repository):
    """Promote the exact signed preview bytes without changing Android identity."""
    if info["channel"] != "preview":
        raise ValueError("Stable promotion is supported only for the existing preview application")
    validate_source(info, repository)
    validate_matrix(info, repository)
    prefix = f"repos/{repository}"
    tag = info["tag"]
    if gh(f"{prefix}/git/ref/heads/main")["object"]["sha"] != info["commit"]:
        raise ValueError("Stable promotion requires the current main commit")
    tagged = gh(f"{prefix}/git/ref/tags/{tag}")["object"]
    if tagged["type"] != "commit" or tagged["sha"] != info["commit"]:
        raise ValueError("Preview tag does not identify the current source commit")
    published = gh(f"{prefix}/releases/tags/{tag}")
    if published["draft"] or not published["prerelease"]:
        raise ValueError("Stable promotion requires a published preview prerelease")
    names = {f"ottplay-native-{tag[1:]}.{extension}" for extension in ("apk", "aab")}
    files = names | {"release-manifest.json", "SHA256SUMS"}
    if len(published["assets"]) != len(files) or {item["name"] for item in published["assets"]} != files:
        raise ValueError("Preview release has an unexpected asset set")
    with tempfile.TemporaryDirectory(prefix="ottplay-promote-") as directory:
        downloaded = Path(directory) / "published"
        original = Path(directory) / "original"
        run("gh", "release", "download", tag, "--repo", repository, "--dir", str(downloaded))
        recorded = json.loads((downloaded / "release-manifest.json").read_text())
        if any(recorded.get(key) != value for key, value in info.items() if key != "validatedRun"):
            raise ValueError("Preview manifest differs from current source or Android identity")
        expected_cert = os.environ.get("OTTPLAY_EXPECTED_CERT_SHA256", "").lower()
        if not re.fullmatch(r"[0-9a-f]{64}", expected_cert) or recorded.get("signingCertificateSha256") != expected_cert:
            raise ValueError("Preview signing certificate differs from the configured identity")
        run_id, attempt = recorded.get("buildRunId"), recorded.get("buildRunAttempt")
        if type(run_id) is not int or type(attempt) is not int or run_id <= 0 or attempt <= 0:
            raise ValueError("Preview lacks signed-build run provenance; publish a new preview")
        build_run = gh(f"{prefix}/actions/runs/{run_id}")
        expected = {"head_sha": info["commit"], "head_branch": "main", "event": "workflow_dispatch",
                    "path": ".github/workflows/release.yml", "status": "completed", "conclusion": "success",
                    "run_attempt": attempt}
        if any(build_run.get(key) != value for key, value in expected.items()):
            raise ValueError("Preview signed-build run is not successful for this exact source and attempt")
        run("gh", "run", "download", str(run_id), "--repo", repository, "--name", f"ottplay-native-{tag}", "--dir", str(original))
        for name in files:
            if (downloaded / name).read_bytes() != (original / name).read_bytes():
                raise ValueError("Published preview differs from its signed-build artifact")
        artifacts = recorded["artifacts"]
        if len(artifacts) != len(names) or {item["name"] for item in artifacts} != names:
            raise ValueError("Preview manifest has an unexpected artifact set")
        for item in artifacts:
            data = (downloaded / item["name"]).read_bytes()
            if len(data) != item["bytes"] or hashlib.sha256(data).hexdigest() != item["sha256"]:
                raise ValueError("Preview artifact checksum or size changed")
        expected_sums = {f"{hashlib.sha256((downloaded / name).read_bytes()).hexdigest()}  {name}" for name in names | {"release-manifest.json"}}
        sums = (downloaded / "SHA256SUMS").read_text().splitlines()
        if len(sums) != len(expected_sums) or set(sums) != expected_sums:
            raise ValueError("Preview checksum manifest is inconsistent")
    # Recheck mutable references immediately before changing release metadata.
    if gh(f"{prefix}/git/ref/heads/main")["object"]["sha"] != info["commit"] or gh(f"{prefix}/git/ref/tags/{tag}")["object"]["sha"] != info["commit"]:
        raise ValueError("Source changed while validating the preview")
    current = gh(f"{prefix}/releases/{published['id']}")
    asset_identity = lambda release: sorted((item["id"], item["name"], item["size"], item.get("digest"), item["updated_at"]) for item in release["assets"])
    if current["draft"] or not current["prerelease"] or current["tag_name"] != tag or asset_identity(current) != asset_identity(published):
        raise ValueError("Preview release changed while validating it")
    notes = (f"Stable release of the existing preview application. Install the APK over an older preview; Android retains its data. "
             f"Package ID, certificate, versionCode and signed bytes are unchanged from the qualified preview. "
             f"The historical tag and Android versionName retain `preview` to preserve artifact identity. No Google Play submission.\n\n"
             f"Source: `{info['commit']}`. VersionCode: {info['versionCode']}.\n\n"
             f"Full host/phone/TV matrix: {info['validatedRun']}\n\n"
             f"Signed build: https://github.com/{repository}/actions/runs/{run_id}/attempts/{attempt}\n\n"
             f"### Original preview notes\n\n{current.get('body') or ''}")
    run("gh", "api", "--method", "PATCH", f"{prefix}/releases/{published['id']}",
        "-F", "prerelease=false", "-f", "make_latest=true", "-f", f"name=OTT Play Native {info['baseVersion']} stable (build {info['versionCode']})",
        "-f", f"body={notes}", capture=True)
    print(f"Promoted {tag} to stable without rebuilding or replacing assets")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("metadata", "check", "build", "publish", "promote"))
    parser.add_argument("--channel", required=True, choices=("preview", "production"))
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--credentials", help="Owner-only local signing JSON; never committed")
    args = parser.parse_args()
    info = metadata(args.channel)
    if args.command == "metadata":
        print(json.dumps(info))
        if os.environ.get("GITHUB_OUTPUT"):
            with open(os.environ["GITHUB_OUTPUT"], "a") as output:
                output.write(f"tag={info['tag']}\n")
    elif args.command == "check":
        preflight(info, args.repository or "")
        print(f"Release preflight passed: {info['tag']} at {info['commit']}")
    elif args.command == "build":
        build(info, args.credentials)
    elif args.command == "publish":
        publish(info, args.repository or "")
    else:
        promote(info, args.repository or "")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print(f"Release stopped: {error}", file=sys.stderr)
        sys.exit(1)
