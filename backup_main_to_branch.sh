#!/usr/bin/env bash
set -euo pipefail

print_help() {
  cat <<'HELP'
Create and push a backup branch from main, then return to main.

Usage:
  backup_main_to_branch.sh [options]

Options:
  -r, --remote <name>         Git remote to push to. Default: origin
  -m, --main <branch>         Main branch to back up. Default: main
  -p, --prefix <value>        Backup branch prefix. Default: backup/main
      --allow-dirty           Allow running with uncommitted local changes.
                              Why: useful when you need a remote pointer backup quickly
                              and don't want to interrupt local work.
                              Risk: your uncommitted changes are NOT included in the
                              backup branch, only committed history is backed up.
      --stash-dirty           Temporarily stash local changes, run backup, then restore.
                              Why: keeps safety checks while still letting you run on a
                              dirty tree.
                              What is stash: a temporary shelved copy of local changes
                              stored by Git, so your working tree becomes clean.
                              After backup, this script runs 'git stash pop' to restore
                              your changes.
  -h, --help                  Show this help.

Notes:
  - Default mode requires a clean working tree.
  - --allow-dirty and --stash-dirty are mutually exclusive.
  - Script always attempts to return to main at the end.

Examples:
  backup_main_to_branch.sh
  backup_main_to_branch.sh --remote origin --main main --prefix backup/main
  backup_main_to_branch.sh --allow-dirty
  backup_main_to_branch.sh --stash-dirty
HELP
}

REMOTE="origin"
MAIN_BRANCH="main"
BACKUP_PREFIX="backup/main"
ALLOW_DIRTY=false
STASH_DIRTY=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    -r|--remote)
      REMOTE="${2:-}"
      shift 2
      ;;
    -m|--main)
      MAIN_BRANCH="${2:-}"
      shift 2
      ;;
    -p|--prefix)
      BACKUP_PREFIX="${2:-}"
      shift 2
      ;;
    --allow-dirty)
      ALLOW_DIRTY=true
      shift
      ;;
    --stash-dirty)
      STASH_DIRTY=true
      shift
      ;;
    -h|--help)
      print_help
      exit 0
      ;;
    *)
      echo "Error: unknown option '$1'" >&2
      echo "Use --help for usage." >&2
      exit 1
      ;;
  esac
done

if [[ "$ALLOW_DIRTY" == true && "$STASH_DIRTY" == true ]]; then
  echo "Error: --allow-dirty and --stash-dirty cannot be used together." >&2
  exit 1
fi

if ! command -v git >/dev/null 2>&1; then
  echo "Error: git is not installed." >&2
  exit 1
fi

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [[ -z "$REPO_ROOT" ]]; then
  echo "Error: this script must run inside a git repository." >&2
  exit 1
fi

cd "$REPO_ROOT"
CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"

STASHED=false
cleanup() {
  # Best effort: return to main as requested.
  git checkout "$MAIN_BRANCH" >/dev/null 2>&1 || true
  if [[ "$STASHED" == true ]]; then
    git stash pop >/dev/null 2>&1 || {
      echo "Warning: failed to auto-restore stash. Run 'git stash list' and recover manually." >&2
    }
  fi
}
trap cleanup EXIT

TREE_DIRTY=false
if [[ -n "$(git status --porcelain)" ]]; then
  TREE_DIRTY=true
fi

if [[ "$TREE_DIRTY" == true ]]; then
  if [[ "$STASH_DIRTY" == true ]]; then
    git stash push -u -m "auto-stash: backup_main_to_branch.sh" >/dev/null
    STASHED=true
  elif [[ "$ALLOW_DIRTY" != true ]]; then
    echo "Error: working tree is not clean. Use --allow-dirty or --stash-dirty." >&2
    exit 1
  fi
fi

if ! git remote get-url "$REMOTE" >/dev/null 2>&1; then
  echo "Error: remote '$REMOTE' not found." >&2
  exit 1
fi

if ! git show-ref --verify --quiet "refs/heads/$MAIN_BRANCH"; then
  echo "Error: local branch '$MAIN_BRANCH' does not exist." >&2
  exit 1
fi

# Ensure local refs are up to date before creating backup branch.
git fetch "$REMOTE" "$MAIN_BRANCH" --quiet
git checkout "$MAIN_BRANCH" --quiet

TS="$(date +"%Y-%m-%d-%H%M%S")"
SHORT_SHA="$(git rev-parse --short HEAD)"
BACKUP_BRANCH="${BACKUP_PREFIX}-${TS}-${SHORT_SHA}"

git branch "$BACKUP_BRANCH"
git push -u "$REMOTE" "$BACKUP_BRANCH"
git checkout "$MAIN_BRANCH" --quiet

echo "Backup branch created and pushed: $BACKUP_BRANCH"
echo "Returned to branch: $MAIN_BRANCH"
if [[ "$CURRENT_BRANCH" != "$MAIN_BRANCH" ]]; then
  echo "Note: original branch was '$CURRENT_BRANCH'."
fi
