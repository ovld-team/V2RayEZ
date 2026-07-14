#!/usr/bin/env bash
# Create a commit WITHOUT going through `git commit`, which Cursor wraps to inject
# `Co-authored-by: Cursor <cursoragent@cursor.com>`. Uses git commit-tree instead.
#
# Usage:
#   scripts/git-commit-clean.sh "Commit subject"
#   scripts/git-commit-clean.sh "Subject" <<-EOF
#   optional body
#   EOF
set -euo pipefail

SUBJECT="${1:-}"
if [[ -z "$SUBJECT" ]]; then
  echo "usage: $0 \"subject\" [body via stdin or \$2]" >&2
  exit 1
fi
BODY="${2:-}"
if [[ -z "$BODY" && ! -t 0 ]]; then
  BODY="$(cat || true)"
fi

AUTHOR_NAME="${GIT_AUTHOR_NAME:-$(git config user.name)}"
AUTHOR_EMAIL="${GIT_AUTHOR_EMAIL:-$(git config user.email)}"
if [[ -z "$AUTHOR_NAME" || -z "$AUTHOR_EMAIL" ]]; then
  echo "error: set git user.name / user.email" >&2
  exit 1
fi

export GIT_AUTHOR_NAME="$AUTHOR_NAME"
export GIT_AUTHOR_EMAIL="$AUTHOR_EMAIL"
export GIT_COMMITTER_NAME="$AUTHOR_NAME"
export GIT_COMMITTER_EMAIL="$AUTHOR_EMAIL"

MSG="$SUBJECT"
if [[ -n "${BODY//[[:space:]]/}" ]]; then
  MSG="$SUBJECT

$BODY"
fi

# Strip any Cursor co-author trailer that might sneak in via env wrappers.
MSG="$(printf '%s\n' "$MSG" | sed -E '/^Co-authored-by:[[:space:]]*Cursor/Id')"

TREE="$(git write-tree)"
PARENTS=()
if git rev-parse --verify HEAD >/dev/null 2>&1; then
  PARENTS=(-p "$(git rev-parse HEAD)")
fi
COMMIT="$(printf '%s\n' "$MSG" | git commit-tree "$TREE" "${PARENTS[@]}")"
git reset --soft "$COMMIT"

# Verify
if git log -1 --format=%B | grep -qi 'Co-authored-by:.*Cursor'; then
  echo "error: Co-authored-by Cursor still present after commit-tree" >&2
  exit 1
fi

echo "created $COMMIT"
git log -1 --format='%H%n%an <%ae>%n%cn <%ce>%n%B'
