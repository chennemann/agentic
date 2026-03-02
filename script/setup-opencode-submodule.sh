#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SUBMODULE_PATH="$ROOT_DIR/third_party/opencode"

git -C "$ROOT_DIR" submodule sync -- third_party/opencode
git -C "$ROOT_DIR" submodule update --init third_party/opencode

git -C "$SUBMODULE_PATH" sparse-checkout init --no-cone
git -C "$SUBMODULE_PATH" sparse-checkout set \
  packages/sdk/openapi.json \
  packages/sdk/js/src
