# Vendored Zstandard

Zstandard 1.5.7, used only by the Android Native targets. `com.squareup.zstd:zstd-kmp` publishes
no `androidNative*` variants, and the NDK ships no libzstd, so those targets compile this copy into
the msgtrans klib through cinterop (`-Xcompile-source`). Every other target keeps zstd-kmp.

- Source: `https://github.com/facebook/zstd/releases/download/v1.5.7/zstd-1.5.7.tar.gz`
- SHA-256 of that tarball: `eb33e51f49a15e023950cd7825ca74a4a2b43db8354825ac24fc1b7ee09e6fa3`
- `zstd.c` is the upstream single-file amalgamation, generated unmodified with
  `build/single_file_libs/create_single_file_library.sh` (assembly and legacy formats disabled).
- `zstd.h`, `zstd_errors.h` are copied from `lib/`.
- License: BSD (`LICENSE`), dual-licensed upstream with GPLv2; msgtrans uses it under BSD.
