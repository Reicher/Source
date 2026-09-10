#!/usr/bin/env sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
destination="$repo_root/.deps/llama.cpp"
commit="5266f24da75dc449bd56cbed7addb9c8e4a6a73e"

if [ -d "$destination/.git" ]; then
    actual=$(git -C "$destination" rev-parse HEAD)
    [ "$actual" = "$commit" ] || {
        printf 'llama.cpp is present at unexpected commit: %s\n' "$actual" >&2
        exit 1
    }
    printf 'Pinned llama.cpp is ready at %s\n' "$destination"
    exit 0
fi

mkdir -p "$(dirname "$destination")"
git clone https://github.com/ggml-org/llama.cpp.git "$destination"
git -C "$destination" checkout "$commit"
actual=$(git -C "$destination" rev-parse HEAD)
[ "$actual" = "$commit" ] || exit 1
printf 'Pinned llama.cpp is ready at %s\n' "$destination"
