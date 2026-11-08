#!/usr/bin/env bash
# tools/publish_release.sh
# Reliably publish exactly one verified APK artifact to a GitHub release for a given tag.
# Idempotent, fail-safe, and recoverable from interruptions.
#
# Integrity & recovery model:
#   * GitHub's asset metadata is authoritative for size and cryptographic digest.
#   * An asset GitHub reports as an unfinished upload (state "open", no digest,
#     zero bytes) never carried a published artifact, so it may be replaced. Only
#     that exact asset is removed, by its own asset id, so an artifact finished by
#     a concurrent runner in the meantime is never overwritten.
#   * Canonical release artifact recovery: in an interrupted release retry, a draft
#     release already holds an uploaded artifact from a prior run. A newly rebuilt APK
#     has a different SHA-256 because builds are non-deterministic. The existing
#     draft artifact is verified for integrity (size, GitHub digest, download check)
#     and provenance (valid APK/ZIP format, package identity com.gathin.tvhome, and
#     apksigner certificate matching). Once verified, it is adopted as the canonical
#     release artifact and publication resumes without overwriting.
#   * An unverified or conflicting artifact (exceeds budget, invalid magic, conflicting
#     package, certificate mismatch) is rejected safely without modifying the release.
#   * An already published release (isDraft == false) is immutable: it is never overwritten.
set -euo pipefail

TAG="${1:-}"
APK_PATH="${2:-tvhome-launcher.apk}"

if [ -z "$TAG" ]; then
  echo "ERROR: Release tag argument missing. Usage: $0 <tag> [apk-path]" >&2
  exit 1
fi

if [ ! -f "$APK_PATH" ]; then
  echo "ERROR: Release artifact '$APK_PATH' does not exist or is not a regular file." >&2
  exit 1
fi

get_file_size() {
  local file="$1"
  if stat -c %s "$file" >/dev/null 2>&1; then
    stat -c %s "$file"
  else
    stat -f %z "$file"
  fi
}

compute_sha256() {
  local file="$1"
  local raw=""
  if command -v sha256sum >/dev/null 2>&1; then
    raw=$(sha256sum "$file" | awk '{print $1}')
  elif command -v shasum >/dev/null 2>&1; then
    raw=$(shasum -a 256 "$file" | awk '{print $1}')
  else
    raw=$(python3 -c "import hashlib, sys; print(hashlib.sha256(open(sys.argv[1], 'rb').read()).hexdigest())" "$file")
  fi
  raw="${raw#\\}"
  printf '%s' "$raw"
}

LOCAL_SIZE=$(get_file_size "$APK_PATH")
if [ "$LOCAL_SIZE" -le 0 ]; then
  echo "ERROR: Release artifact '$APK_PATH' is empty (0 bytes)." >&2
  exit 1
fi

MAX_SIZE=2097152 # 2 MiB budget
if [ "$LOCAL_SIZE" -ge "$MAX_SIZE" ]; then
  echo "ERROR: Release artifact '$APK_PATH' exceeds 2 MiB budget ($LOCAL_SIZE bytes)." >&2
  exit 1
fi

LOCAL_SHA=$(compute_sha256 "$APK_PATH")
ASSET_NAME=$(basename "$APK_PATH")
echo "Publishing $ASSET_NAME ($LOCAL_SIZE bytes, sha256:$LOCAL_SHA) to release $TAG..."

# Bound the reconcile loop: a release that keeps changing under us is a real
# blocker, not something to retry forever.
MAX_ROUNDS=5
ROUND=0
RELEASE_STATE=""
CONVERGE_ACTION=""
CANONICAL_SHA=""
CANONICAL_SIZE=""
CONCURRENT_UPLOAD_RACE=false

has_asset() {
  local json="$1"
  local target_name="$2"
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$json" | jq -e --arg name "$target_name" '.assets[]? | select(.name == $name)' >/dev/null 2>&1
  else
    python3 -c "import sys, json; data = json.loads(sys.argv[1]); sys.exit(0 if any(a.get('name') == sys.argv[2] for a in data.get('assets', [])) else 1)" "$json" "$target_name"
  fi
}

# Read one field of the release asset with the given name.
get_asset_field() {
  local json="$1"
  local target_name="$2"
  local field="$3"
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$json" | jq -r --arg name "$target_name" --arg field "$field" \
      '[(.assets[]? | select(.name == $name) | .[$field])] | map(select(. != null)) | .[0] // ""'
  else
    python3 -c "
import sys, json
data = json.loads(sys.argv[1])
name, field = sys.argv[2], sys.argv[3]
values = [a.get(field) for a in data.get('assets', []) if a.get('name') == name and a.get(field) is not None]
print(values[0] if values else '')
" "$json" "$target_name" "$field"
  fi
}

get_asset_size() { get_asset_field "$1" "$2" size; }
get_asset_digest() { get_asset_field "$1" "$2" digest; }
get_asset_state() { get_asset_field "$1" "$2" state; }
get_asset_api_url() { get_asset_field "$1" "$2" apiUrl; }

parse_is_draft() {
  local json="$1"
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$json" | jq -r '.isDraft // false'
  else
    python3 -c "import sys, json; data = json.loads(sys.argv[1]); print('true' if data.get('isDraft') else 'false')" "$json"
  fi
}

get_apk_count() {
  local json="$1"
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$json" | jq '[.assets[]? | select(.name | endswith(".apk"))] | length'
  else
    python3 -c "import sys, json; data = json.loads(sys.argv[1]); print(len([a for a in data.get('assets', []) if a.get('name', '').endswith('.apk')]))" "$json"
  fi
}

# GitHub publishes an asset digest as "sha256:<hex>"; compare the hex only.
normalize_digest() {
  local digest="$1"
  case "$digest" in
    sha256:*) digest="${digest#sha256:}" ;;
    SHA256:*) digest="${digest#SHA256:}" ;;
  esac
  digest="${digest#\\}"
  digest="${digest//[[:space:]]/}"
  printf '%s' "$digest" | tr 'A-F' 'a-f'
}

# True when GitHub reports an asset whose upload never completed: it holds no
# published bytes, so it is neither a valid nor a conflicting artifact.
asset_is_incomplete() {
  local state="$1"
  local digest="$2"
  local size="$3"
  if [ -n "$state" ] && [ "$state" != "uploaded" ]; then
    return 0
  fi
  if [ -z "$digest" ]; then
    if [ -z "$size" ] || [ "$size" -eq 0 ]; then
      return 0
    fi
  fi
  return 1
}

get_release_field() {
  local json="$1"
  local field="$2"
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$json" | jq -r --arg field "$field" '.[$field] // ""'
  else
    python3 -c "import sys, json; data = json.loads(sys.argv[1]); print(data.get(sys.argv[2], '') or '')" "$json" "$field"
  fi
}

# Read the current release state; never leaves a half-known state behind.
read_release_state() {
  if ! RELEASE_STATE=$(gh release view "$TAG" --json isDraft,assets,tagName,targetCommitish,url 2>&1); then
    echo "ERROR: Failed to read release $TAG:" >&2
    echo "$RELEASE_STATE" >&2
    exit 1
  fi
}

# Publish the release when it is still a draft.
publish_if_draft() {
  local is_draft="$1"
  if [ "$is_draft" = "true" ]; then
    echo "Publishing draft release $TAG..."
    if ! gh release edit "$TAG" --draft=false; then
      echo "ERROR: Failed to publish draft release $TAG." >&2
      exit 1
    fi
  fi
}

# Upload the verified artifact to the release.
upload_asset() {
  echo "Uploading $ASSET_NAME to release $TAG..."
  set +e
  local upload_raw upload_status
  upload_raw=$(gh release upload "$TAG" "$APK_PATH" 2>&1)
  upload_status=$?
  set -e

  if [ "$upload_status" -ne 0 ]; then
    if printf '%s\n' "$upload_raw" | grep -qiE "already exists|HTTP 422|already_exists"; then
      echo "Asset $ASSET_NAME was uploaded concurrently by another process. Verifying the remote artifact..."
      CONCURRENT_UPLOAD_RACE=true
      return
    fi
    echo "ERROR: Failed to upload $ASSET_NAME to release $TAG (exit code $upload_status):" >&2
    echo "$upload_raw" >&2
    exit 1
  fi
}

# Replace an asset whose upload never completed. Only that exact asset is
# removed, by its own asset id, so an artifact that a concurrent runner
# finished in the meantime is never overwritten.
repair_asset() {
  local json="$1"
  local api_url
  api_url=$(get_asset_api_url "$json" "$ASSET_NAME")
  if [ -z "$api_url" ]; then
    echo "ERROR: Cannot replace the incomplete asset '$ASSET_NAME' on release $TAG: GitHub did not report its asset URL. Nothing was modified." >&2
    exit 1
  fi

  echo "Removing the incomplete asset '$ASSET_NAME' from release $TAG..."
  set +e
  local delete_raw delete_status
  delete_raw=$(gh api --method DELETE "$api_url" 2>&1)
  delete_status=$?
  set -e
  if [ "$delete_status" -ne 0 ]; then
    if printf '%s\n' "$delete_raw" | grep -qiE "404|not found"; then
      echo "The incomplete asset '$ASSET_NAME' was already removed by another process."
      return
    fi
    echo "ERROR: Failed to remove the incomplete asset '$ASSET_NAME' from release $TAG (exit code $delete_status):" >&2
    echo "$delete_raw" >&2
    exit 1
  fi

  upload_asset
}

# Download the remote asset and check it against GitHub's own metadata.
#   0 = downloaded and verified against the metadata
#   1 = the asset no longer exists on the release
#   2 = the copy is not the bytes GitHub published (corrupted download)
#   3 = the copy is shorter than GitHub published (interrupted download)
#   4 = the asset could not be downloaded at all
fetch_verified_asset() {
  local dest="$1"
  local expected_size="$2"
  local expected_digest="$3"
  local attempt reason=4
  for attempt in 1 2; do
    local tmp_dl_dir got_size got_sha
    tmp_dl_dir=$(mktemp -d)
    if gh release download "$TAG" --pattern "$ASSET_NAME" --dir "$tmp_dl_dir" >/dev/null 2>&1 &&
       [ -f "$tmp_dl_dir/$ASSET_NAME" ]; then
      cp "$tmp_dl_dir/$ASSET_NAME" "$dest"
      rm -rf "$tmp_dl_dir"
      got_size=$(get_file_size "$dest")
      if [ -n "$expected_size" ] && [ "$expected_size" -ne "$got_size" ]; then
        echo "  The downloaded asset is $got_size bytes but GitHub published $expected_size; the download was interrupted. Retrying." >&2
        reason=3
        continue
      fi
      if [ -n "$expected_digest" ]; then
        got_sha=$(compute_sha256 "$dest")
        if [ "$got_sha" != "$expected_digest" ]; then
          echo "  The downloaded asset does not match the digest GitHub published; the download was corrupted. Retrying." >&2
          reason=2
          continue
        fi
      fi
      return 0
    fi
    rm -rf "$tmp_dl_dir"
    # The asset may have been deleted between the state query and the download.
    read_release_state
    if ! has_asset "$RELEASE_STATE" "$ASSET_NAME"; then
      return 1
    fi
  done
  return "$reason"
}

# Verify the release metadata provenance: exact repository, release tag, and commit.
# Returns 0 if verified.
# Exits 1 if verification fails (unauthorized repository, tag mismatch, conflicting commit).
verify_release_metadata_provenance() {
  local json="$1"

  # 1. Release Tag Verification: must match the expected tag exactly
  local remote_tag
  remote_tag=$(get_release_field "$json" tagName)
  if [ -n "$remote_tag" ] && [ "$remote_tag" != "$TAG" ]; then
    echo "ERROR: Release tag mismatch on draft release (expected '$TAG', found '$remote_tag'). Nothing was modified." >&2
    exit 1
  fi

  # 2. Repository Provenance: release URL and asset API URL must belong to the trusted repository
  local expected_repo remote_url asset_api_url
  expected_repo="${GITHUB_REPOSITORY:-Flexy01/tvhome-launcher}"
  remote_url=$(get_release_field "$json" url)
  if [ -n "$remote_url" ]; then
    if ! printf '%s\n' "$remote_url" | grep -qiE "(github\.com[/:])?$expected_repo/releases"; then
      echo "ERROR: Remote release repository mismatch (expected repository '$expected_repo', release URL '$remote_url'). Nothing was modified." >&2
      exit 1
    fi
  fi

  asset_api_url=$(get_asset_api_url "$json" "$ASSET_NAME")
  if [ -n "$asset_api_url" ]; then
    if ! printf '%s\n' "$asset_api_url" | grep -qiE "repos/$expected_repo/releases/assets"; then
      echo "ERROR: Remote asset repository mismatch (expected repository '$expected_repo', asset API URL '$asset_api_url'). Nothing was modified." >&2
      exit 1
    fi
  fi

  # 3. Commit Provenance: targetCommitish must match tag or expected commit
  local target_commitish
  target_commitish=$(get_release_field "$json" targetCommitish)
  if [ -n "$target_commitish" ] && [ "$target_commitish" != "$TAG" ]; then
    local expected_commit="${GITHUB_SHA:-}"
    if [ -z "$expected_commit" ] && command -v git >/dev/null 2>&1; then
      expected_commit=$(git rev-parse "$TAG^{commit}" 2>/dev/null || git rev-parse HEAD 2>/dev/null || true)
    fi
    if [ -n "$expected_commit" ]; then
      local commit_matches=false
      if [ "$target_commitish" = "$expected_commit" ] || [ "${expected_commit#$target_commitish}" != "$expected_commit" ]; then
        commit_matches=true
      elif command -v git >/dev/null 2>&1; then
        local target_sha
        target_sha=$(git rev-parse "$target_commitish^{commit}" 2>/dev/null || true)
        if [ -n "$target_sha" ] && [ "$target_sha" = "$expected_commit" ]; then
          commit_matches=true
        fi
      fi
      if [ "$commit_matches" = "false" ]; then
        echo "ERROR: Remote draft release target commitish '$target_commitish' conflicts with expected commit '$expected_commit' for tag $TAG. Nothing was modified." >&2
        exit 1
      fi
    fi
  fi
}

# Verify the provenance of a draft release artifact.
# Returns 0 if the artifact is verified as an authentic, valid build.
# Returns 1 if verification fails (unverified build or conflicting artifact).
verify_artifact_provenance() {
  local remote_file="$1"
  local local_file="$2"

  # 1. Format validation: must be a ZIP/APK starting with magic bytes PK\x03\x04
  local magic
  magic=$(python3 -c "import sys; f=open(sys.argv[1], 'rb'); b=f.read(4); sys.exit(0 if b == b'PK\x03\x04' else 1)" "$remote_file" 2>/dev/null && echo "ok" || echo "bad")
  if [ "$magic" != "ok" ]; then
    echo "  Artifact format failure: '$remote_file' is not a valid APK/ZIP archive (magic header mismatch)." >&2
    return 1
  fi

  # Must be an intact, uncorrupted ZIP archive containing at least one entry
  local zip_intact
  zip_intact=$(python3 -c "
import sys, zipfile
try:
    with zipfile.ZipFile(sys.argv[1], 'r') as z:
        if z.testzip() is not None:
            sys.exit(1)
        if len(z.namelist()) == 0:
            sys.exit(2)
except Exception:
    sys.exit(3)
sys.exit(0)
" "$remote_file" 2>/dev/null && echo "ok" || echo "bad")
  if [ "$zip_intact" != "ok" ]; then
    echo "  Artifact format failure: '$remote_file' is corrupted or not a valid readable ZIP archive." >&2
    return 1
  fi

  # 2. Package identity validation: AndroidManifest.xml MUST be present and valid.
  # The application ID / package name must match 'com.gathin.tvhome'.
  local manifest_check
  manifest_check=$(python3 -c "
import sys, zipfile
try:
    with zipfile.ZipFile(sys.argv[1], 'r') as z:
        if 'AndroidManifest.xml' not in z.namelist():
            sys.stderr.write('Missing AndroidManifest.xml\n')
            sys.exit(1)
        manifest = z.read('AndroidManifest.xml')
        pkg_u8 = b'com.gathin.tvhome'
        pkg_u16 = 'com.gathin.tvhome'.encode('utf-16le')
        if pkg_u8 not in manifest and pkg_u16 not in manifest:
            sys.stderr.write('Package identity mismatch\n')
            sys.exit(2)
except Exception as e:
    sys.stderr.write(f'Manifest inspection error: {e}\n')
    sys.exit(3)
sys.exit(0)
" "$remote_file" 2>&1) || {
    echo "  Artifact provenance failure: package identity does not match 'com.gathin.tvhome' ($manifest_check)." >&2
    return 1
  }

  # 3. Cryptographic signature and certificate provenance
  # Must fail closed if apksigner tool is unavailable or if artifact is unsigned/mismatched.
  local apksigner_cmd=""
  if command -v apksigner >/dev/null 2>&1; then
    apksigner_cmd=$(command -v apksigner)
  elif [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/build-tools" ]; then
    apksigner_cmd=$(find "$ANDROID_HOME/build-tools" -name apksigner -type f 2>/dev/null | sort -V | tail -n 1 || true)
  fi

  if [ -z "$apksigner_cmd" ]; then
    echo "  Artifact signature failure: apksigner tool not found (missing required verification tool). Failing closed." >&2
    return 1
  fi

  # Local build signature verification
  if ! "$apksigner_cmd" verify "$local_file" >/dev/null 2>&1; then
    echo "  Artifact signature failure: local artifact is not signed or failed apksigner verification." >&2
    return 1
  fi

  # Remote draft artifact signature verification
  if ! "$apksigner_cmd" verify --verbose "$remote_file" >/dev/null 2>&1; then
    echo "  Artifact signature failure: remote draft artifact is unsigned or failed apksigner verification." >&2
    return 1
  fi

  # Certificate fingerprint extraction and comparison
  local remote_cert local_cert
  remote_cert=$("$apksigner_cmd" verify --print-certs "$remote_file" 2>/dev/null | grep -i "certificate SHA-256 digest" | head -n 1 | awk '{print $NF}' || true)
  local_cert=$("$apksigner_cmd" verify --print-certs "$local_file" 2>/dev/null | grep -i "certificate SHA-256 digest" | head -n 1 | awk '{print $NF}' || true)

  if [ -z "$remote_cert" ] || [ -z "$local_cert" ]; then
    echo "  Artifact signature failure: could not extract signer certificate fingerprint from APKs." >&2
    return 1
  fi

  if [ "$remote_cert" != "$local_cert" ]; then
    echo "  Artifact signature failure: signer certificate mismatch ($remote_cert vs $local_cert)." >&2
    return 1
  fi

  return 0
}

# Decide what has to happen to the remote asset. Sets CONVERGE_ACTION to
# "verified", "repair" or "upload", or exits without touching the release.
converge_asset() {
  local json="$1"
  local size digest state is_draft

  size=$(get_asset_size "$json" "$ASSET_NAME")
  digest=$(normalize_digest "$(get_asset_digest "$json" "$ASSET_NAME")")
  state=$(get_asset_state "$json" "$ASSET_NAME")
  is_draft=$(parse_is_draft "$json")

  # An unfinished upload never carried a published artifact: it may be replaced.
  if asset_is_incomplete "$state" "$digest" "$size"; then
    echo "Release $TAG holds an incomplete asset '$ASSET_NAME' (GitHub state: ${state:-unknown}, ${size:-0} bytes). It never carried a published artifact."
    CONVERGE_ACTION="repair"
    return
  fi

  # Case A: GitHub reports an authoritative digest
  if [ -n "$digest" ]; then
    # 1. Exact SHA match between local build and remote artifact
    if [ "$digest" = "$LOCAL_SHA" ]; then
      echo "Asset $ASSET_NAME already exists on release $TAG with matching SHA-256 ($LOCAL_SHA). Preserving verified asset."
      CANONICAL_SHA="$LOCAL_SHA"
      CANONICAL_SIZE="$size"
      CONVERGE_ACTION="verified"
      return
    fi

    # 2. Collision with a concurrent upload race, or release is already published:
    # A concurrent race uploading conflicting artifacts or an existing published release
    # must never be overwritten.
    if [ "$is_draft" = "false" ] || [ "$CONCURRENT_UPLOAD_RACE" = "true" ]; then
      echo "ERROR: Existing valid releases and assets must not be destroyed or silently replaced! Release '$TAG' holds a conflicting artifact '$ASSET_NAME' (remote SHA: $digest, expected: $LOCAL_SHA). Resolve it manually; nothing was modified." >&2
      exit 1
    fi

    # 3. Interrupted publication retry: the draft release already holds an artifact
    # from a prior run. A newly rebuilt APK has a different SHA-256. Verify integrity
    # and provenance of the draft artifact and resume publication.
    echo "Draft release $TAG holds an already uploaded artifact '$ASSET_NAME' (remote SHA: $digest, local SHA: $LOCAL_SHA)."
    echo "Verifying remote draft artifact integrity and provenance before publication..."
    verify_and_adopt_canonical_draft "$json" "$size" "$digest"
    return
  fi

  # Case B: No digest published by GitHub: download and check bytes ourselves.
  local dest rc remote_sha
  dest=$(mktemp)
  rc=0
  fetch_verified_asset "$dest" "$size" "" || rc=$?
  if [ "$rc" -eq 1 ]; then
    rm -f "$dest"
    echo "Asset $ASSET_NAME disappeared from release $TAG; it will be uploaded again."
    CONVERGE_ACTION="upload"
    return
  fi
  if [ "$rc" -ne 0 ]; then
    rm -f "$dest"
    echo "ERROR: The remote asset '$ASSET_NAME' on release $TAG could not be verified against GitHub's metadata (interrupted or corrupted download). Nothing was modified; resolve it manually." >&2
    exit 1
  fi
  remote_sha=$(compute_sha256 "$dest")

  if [ "$remote_sha" = "$LOCAL_SHA" ]; then
    rm -f "$dest"
    echo "Asset $ASSET_NAME already exists on release $TAG with matching SHA-256 ($LOCAL_SHA). Preserving verified asset."
    CANONICAL_SHA="$LOCAL_SHA"
    CANONICAL_SIZE="$size"
    CONVERGE_ACTION="verified"
    return
  fi

  # Remote SHA differs from LOCAL_SHA
  if [ "$is_draft" = "false" ] || [ "$CONCURRENT_UPLOAD_RACE" = "true" ]; then
    rm -f "$dest"
    echo "ERROR: Existing valid releases and assets must not be destroyed or silently replaced! Release '$TAG' holds a conflicting artifact '$ASSET_NAME' (remote SHA: $remote_sha, expected: $LOCAL_SHA). Resolve it manually; nothing was modified." >&2
    exit 1
  fi

  # Gate: Release metadata provenance (exact repository, tag, and commit)
  verify_release_metadata_provenance "$json"

  # Gate: Provenance and format verification on draft release
  if ! verify_artifact_provenance "$dest" "$APK_PATH"; then
    rm -f "$dest"
    echo "ERROR: Remote draft artifact '$ASSET_NAME' on release $TAG failed provenance verification. It is an unverified or conflicting artifact; nothing was modified." >&2
    exit 1
  fi
  rm -f "$dest"

  echo "Draft release $TAG holds a verified canonical artifact '$ASSET_NAME' ($size bytes, sha256:$remote_sha)."
  echo "Resuming publication using canonical artifact..."
  CANONICAL_SHA="$remote_sha"
  CANONICAL_SIZE="$size"
  CONVERGE_ACTION="verified"
  return
}

# Verify integrity, provenance, and budget of an existing draft artifact, then adopt it as canonical.
verify_and_adopt_canonical_draft() {
  local json="$1"
  local size="$2"
  local digest="$3"

  # Gate 0: Release metadata provenance (exact repository, tag, and commit)
  verify_release_metadata_provenance "$json"

  # Gate A: Size budget (must be > 0 and < 2 MiB)
  if [ -z "$size" ] || [ "$size" -le 0 ]; then
    echo "ERROR: Remote draft artifact '$ASSET_NAME' on release $TAG is empty (0 bytes). Nothing was modified." >&2
    exit 1
  fi
  if [ "$size" -ge "$MAX_SIZE" ]; then
    echo "ERROR: Remote draft artifact '$ASSET_NAME' on release $TAG exceeds 2 MiB budget ($size bytes). It is an unverified or conflicting artifact; nothing was modified." >&2
    exit 1
  fi

  # Gate B: Exactly one APK asset on release
  local apk_count
  apk_count=$(get_apk_count "$json")
  if [ "$apk_count" -ne 1 ]; then
    echo "ERROR: Draft release $TAG holds $apk_count APK assets. Expected exactly 1; nothing was modified." >&2
    exit 1
  fi

  # Gate C: Download and cryptographic verification against GitHub metadata
  local dest rc=0
  dest=$(mktemp)
  fetch_verified_asset "$dest" "$size" "$digest" || rc=$?
  if [ "$rc" -eq 1 ]; then
    rm -f "$dest"
    echo "Asset $ASSET_NAME disappeared from release $TAG; it will be uploaded again."
    CONVERGE_ACTION="upload"
    return
  fi
  if [ "$rc" -ne 0 ]; then
    rm -f "$dest"
    echo "ERROR: The remote draft artifact '$ASSET_NAME' on release $TAG could not be verified against GitHub's metadata. Nothing was modified; resolve it manually." >&2
    exit 1
  fi

  local remote_sha
  remote_sha=$(compute_sha256 "$dest")
  if [ -n "$digest" ] && [ "$remote_sha" != "$digest" ]; then
    rm -f "$dest"
    echo "ERROR: Remote draft artifact '$ASSET_NAME' SHA-256 ($remote_sha) does not match GitHub digest ($digest). Nothing was modified." >&2
    exit 1
  fi

  # Gate D: Provenance and format verification
  if ! verify_artifact_provenance "$dest" "$APK_PATH"; then
    rm -f "$dest"
    echo "ERROR: Remote draft artifact '$ASSET_NAME' on release $TAG failed provenance verification. It is an unverified or conflicting artifact; nothing was modified." >&2
    exit 1
  fi
  rm -f "$dest"

  echo "Draft release $TAG holds a verified canonical artifact '$ASSET_NAME' ($size bytes, sha256:$remote_sha)."
  echo "Resuming publication using canonical artifact..."
  CANONICAL_SHA="$remote_sha"
  CANONICAL_SIZE="$size"
  CONVERGE_ACTION="verified"
  return
}

# Verify the published release end to end.
#   0 = the release is published and holds exactly the verified artifact
#   1 = the asset disappeared while it was verified (retry the publication)
verify_published_release() {
  local json is_draft final_size apk_count state digest rc verified_sha
  read_release_state
  json="$RELEASE_STATE"

  # 1. Gate: Release must not remain in draft state
  is_draft=$(parse_is_draft "$json")
  if [ "$is_draft" = "true" ]; then
    echo "ERROR: Post-verification failed: Release $TAG is in draft state!" >&2
    exit 1
  fi

  # 2. Gate: Release must contain the published asset
  if ! has_asset "$json" "$ASSET_NAME"; then
    echo "ERROR: Post-verification failed: $ASSET_NAME was not found in release $TAG assets!" >&2
    exit 1
  fi

  # 3. Gate: Size budget validation
  final_size=$(get_asset_size "$json" "$ASSET_NAME")
  if [ -z "$final_size" ] || [ "$final_size" -le 0 ]; then
    echo "ERROR: Post-verification failed: $ASSET_NAME on release $TAG is empty ($final_size bytes)!" >&2
    exit 1
  fi
  if [ "$final_size" -ge "$MAX_SIZE" ]; then
    echo "ERROR: Post-verification failed: $ASSET_NAME on release $TAG exceeds 2 MiB budget ($final_size bytes)!" >&2
    exit 1
  fi

  # 4. Gate: Exactly one APK asset
  apk_count=$(get_apk_count "$json")
  if [ "$apk_count" -ne 1 ]; then
    echo "ERROR: Post-verification failed: Expected exactly 1 APK asset on release $TAG, found $apk_count!" >&2
    exit 1
  fi

  # 5. Gate: the asset is a completed upload
  state=$(get_asset_state "$json" "$ASSET_NAME")
  if [ -n "$state" ] && [ "$state" != "uploaded" ]; then
    echo "ERROR: Post-verification failed: $ASSET_NAME on release $TAG is not a completed upload (state: $state)!" >&2
    exit 1
  fi

  # 6. Gate: cryptographic SHA-256 byte-for-byte verification, checked against
  # GitHub's own size and digest before the bytes are trusted.
  digest=$(normalize_digest "$(get_asset_digest "$json" "$ASSET_NAME")")
  local verify_dir
  verify_dir=$(mktemp -d)
  rc=0
  fetch_verified_asset "$verify_dir/$ASSET_NAME" "$final_size" "$digest" || rc=$?
  if [ "$rc" -eq 1 ]; then
    rm -rf "$verify_dir"
    return 1
  fi
  if [ "$rc" -ne 0 ]; then
    rm -rf "$verify_dir"
    case "$rc" in
      2)
        echo "ERROR: Post-verification failed: SHA-256 mismatch between the downloaded $ASSET_NAME and the digest GitHub published for it. The release was not modified." >&2
        ;;
      3)
        echo "ERROR: Post-verification failed: the downloaded $ASSET_NAME is shorter than the size GitHub published (interrupted download). The release was not modified." >&2
        ;;
      *)
        echo "ERROR: Post-verification failed: $ASSET_NAME could not be downloaded from release $TAG. The release was not modified." >&2
        ;;
    esac
    exit 1
  fi
  verified_sha=$(compute_sha256 "$verify_dir/$ASSET_NAME")
  rm -rf "$verify_dir"

  local expected_sha="${CANONICAL_SHA:-$LOCAL_SHA}"
  if [ "$verified_sha" != "$expected_sha" ]; then
    echo "ERROR: Post-verification failed: SHA-256 mismatch for $ASSET_NAME on release $TAG (expected $expected_sha, got $verified_sha)!" >&2
    exit 1
  fi

  VERIFIED_SIZE="$final_size"
  VERIFIED_SHA="$verified_sha"
  return 0
}

# Query release existence with strict error triage
# Never confuse auth failure or network timeout with 'release not found'
set +e
VIEW_RAW=$(gh release view "$TAG" --json isDraft,assets,tagName,targetCommitish,url 2>&1)
VIEW_STATUS=$?
set -e

RELEASE_EXISTS=false

if [ "$VIEW_STATUS" -eq 0 ]; then
  RELEASE_EXISTS=true
else
  # Inspect error message
  if printf '%s\n' "$VIEW_RAW" | grep -qiE "release not found|not found|HTTP 404|Could not resolve to a Release"; then
    RELEASE_EXISTS=false
  elif printf '%s\n' "$VIEW_RAW" | grep -qiE "bad credentials|unauthorized|forbidden|401|403|resource not accessible|authentication"; then
    echo "ERROR: Authentication or permission failure querying release $TAG:" >&2
    echo "$VIEW_RAW" >&2
    exit 1
  elif printf '%s\n' "$VIEW_RAW" | grep -qiE "timeout|connection|network|dial tcp|could not resolve|500|502|503|504|rate limit"; then
    echo "ERROR: Network or API failure querying release $TAG:" >&2
    echo "$VIEW_RAW" >&2
    exit 1
  else
    echo "ERROR: Failed to query release $TAG (exit code $VIEW_STATUS):" >&2
    echo "$VIEW_RAW" >&2
    exit 1
  fi
fi

if [ "$RELEASE_EXISTS" = "false" ]; then
  # The release is created as a draft: it becomes public only after the artifact
  # is uploaded and verified, so an interrupted publication never leaves a
  # public release without a valid artifact.
  echo "Release $TAG does not exist. Creating draft release and uploading $ASSET_NAME..."
  set +e
  CREATE_RAW=$(gh release create "$TAG" "$APK_PATH" --title "TvHome Launcher $TAG" --generate-notes --draft 2>&1)
  CREATE_STATUS=$?
  set -e

  if [ "$CREATE_STATUS" -ne 0 ]; then
    if printf '%s\n' "$CREATE_RAW" | grep -qiE "already exists|HTTP 422|already_exists"; then
      echo "Release $TAG was created concurrently by another process. Reconciling state..."
    else
      echo "ERROR: Failed to create release $TAG (exit code $CREATE_STATUS):" >&2
      echo "$CREATE_RAW" >&2
      exit 1
    fi
  fi
fi

# Reconcile the release onto exactly one verified artifact.
while true; do
  ROUND=$((ROUND + 1))
  if [ "$ROUND" -gt "$MAX_ROUNDS" ]; then
    echo "ERROR: Release $TAG kept changing while publishing; giving up after $MAX_ROUNDS attempts." >&2
    exit 1
  fi

  read_release_state
  IS_DRAFT=$(parse_is_draft "$RELEASE_STATE")

  if ! has_asset "$RELEASE_STATE" "$ASSET_NAME"; then
    echo "Release $TAG has no $ASSET_NAME asset yet."
    upload_asset
    continue
  fi

  converge_asset "$RELEASE_STATE"
  case "$CONVERGE_ACTION" in
    verified)
      publish_if_draft "$IS_DRAFT"
      if verify_published_release; then
        break
      fi
      continue
      ;;
    repair)
      repair_asset "$RELEASE_STATE"
      continue
      ;;
    upload)
      upload_asset
      continue
      ;;
  esac
done

echo "SUCCESS: Verified published release $TAG with exactly one verified asset '$ASSET_NAME' ($VERIFIED_SIZE bytes, sha256:$VERIFIED_SHA)."
