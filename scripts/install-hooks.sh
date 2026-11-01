#!/usr/bin/env bash
# Installs the git hooks from scripts/git-hooks/ (REQUIREMENTS §8.4).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
cp scripts/git-hooks/pre-push .git/hooks/pre-push
chmod +x .git/hooks/pre-push
echo "Installed pre-push hook -> scripts/validate.sh"
