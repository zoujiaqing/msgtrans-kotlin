# R8 / ProGuard rules shipped in the msgtrans JVM jar (R8 applies META-INF/proguard/*.pro of every dependency jar).
# zstd-kmp (com.squareup.zstd 0.4.0) ships no rules, but its native library finds com.squareup.zstd.ZstdCompressor and
# ZstdDecompressor by name through JNI: renamed by R8, the first zstd call aborts the process (SIGABRT, not catchable).
# msgtrans brings zstd-kmp in, so it keeps that package's names (found by interop/android-app-smoke.sh, 2026-10-11).
-keep class com.squareup.zstd.** { *; }
