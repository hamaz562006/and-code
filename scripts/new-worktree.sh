#!/usr/bin/env bash
set -euo pipefail

if [ -z "${1:-}" ]; then
  echo "Usage: $0 <branch-name>" >&2
  exit 1
fi

BRANCH="$1"
REPO_ROOT="$(git rev-parse --show-toplevel)"
WORKTREE_DIR="$REPO_ROOT/.worktree/${BRANCH}"

git fetch origin

mkdir -p "$REPO_ROOT/.worktree"

if git worktree list | grep -q "$WORKTREE_DIR"; then
  echo "Worktree already exists: $WORKTREE_DIR" >&2
  exit 1
fi

if [ -d "$WORKTREE_DIR" ]; then
  echo "Removing stale directory (not registered as worktree): $WORKTREE_DIR" >&2
  rm -rf "$WORKTREE_DIR"
fi

cleanup() {
  if [ -d "$WORKTREE_DIR" ] && ! git worktree list | grep -q "$WORKTREE_DIR"; then
    echo "Cleaning up after failure: $WORKTREE_DIR" >&2
    rm -rf "$WORKTREE_DIR"
  fi
}
trap cleanup EXIT

git worktree add "$WORKTREE_DIR" -b "$BRANCH" origin/main

trap - EXIT

echo ""
echo "Worktree created: $WORKTREE_DIR"
echo "Branch: $BRANCH (based on origin/main)"
