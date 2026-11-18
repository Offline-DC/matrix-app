#!/usr/bin/env bash
# Rebuild the HIT video decoder libraries in ../../src/main/jniLibs/armeabi-v7a/.
#
#   NDK=/path/to/android-ndk-r26d bash build-hitvideo.sh
#
# What it builds (Linux or macOS host with the Android NDK; nothing else needed):
#   FFmpeg n6.1.2, LGPL, shared, with ONLY the hevc decoder + parser, the mov/mp4
#   demuxer, the file protocol and swscale. Library names get a "_hit" suffix so
#   they can never clash with another FFmpeg in the APK:
#     libavutil_hit.so libavcodec_hit.so libavformat_hit.so libswscale_hit.so
#   plus libhitvideo.so (hv_core.c + hv_jni.c, the JNI side of HitVideoNative.kt)
#   and hvbench (hv_core.c + hv_bench.c), an adb command-line speed test.
#
# Licence: FFmpeg is LGPL 2.1+. It is linked dynamically (separate .so files) and
# built without any GPL/nonfree parts; the source tag and this configure line are
# what a recipient needs to rebuild it. Keep the notice in LICENSE-FFmpeg.txt.
set -euo pipefail
: "${NDK:?set NDK to the Android NDK folder (r26d used)}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../../src/main/jniLibs/armeabi-v7a"
WORK="${WORK:-$HERE/.build}"
HOSTTAG=$(ls "$NDK/toolchains/llvm/prebuilt" | head -1)
T="$NDK/toolchains/llvm/prebuilt/$HOSTTAG/bin"
CC="$T/armv7a-linux-androideabi24-clang"
PREFIX="$WORK/ffbuild"
mkdir -p "$WORK" "$OUT"

[[ -d "$WORK/ffmpeg" ]] || git clone -q --depth 1 --branch n6.1.2 https://github.com/FFmpeg/FFmpeg.git "$WORK/ffmpeg"
cd "$WORK/ffmpeg"
./configure --prefix="$PREFIX" --target-os=android --arch=arm --cpu=armv7-a --enable-cross-compile \
  --cc="$CC" --cxx="$T/armv7a-linux-androideabi24-clang++" --ar="$T/llvm-ar" --nm="$T/llvm-nm" \
  --ranlib="$T/llvm-ranlib" --strip="$T/llvm-strip" \
  --enable-neon --enable-asm --enable-inline-asm --enable-pic --enable-shared --disable-static --build-suffix=_hit \
  --disable-everything --enable-decoder=hevc --enable-parser=hevc --enable-demuxer=mov --enable-protocol=file \
  --disable-programs --disable-doc --disable-avdevice --disable-avfilter --disable-swresample --disable-postproc \
  --disable-network --disable-debug \
  --extra-cflags="-O2 -fPIC -mfloat-abi=softfp -mfpu=neon" --extra-ldflags="-Wl,-z,max-page-size=4096" >/dev/null
make -j"$(getconf _NPROCESSORS_ONLN)" >/dev/null
make install >/dev/null

cd "$HERE"
FL="-O2 -fPIC -Wall -Wextra -Wno-unused-parameter -mfpu=neon -I$PREFIX/include"
LIBS="-L$PREFIX/lib -lavformat_hit -lavcodec_hit -lswscale_hit -lavutil_hit"
"$CC" $FL -shared -o "$OUT/libhitvideo.so" hv_core.c hv_jni.c $LIBS -Wl,-soname,libhitvideo.so -Wl,-z,max-page-size=4096
"$CC" $FL -fPIE -pie -o "$WORK/hvbench" hv_core.c hv_bench.c $LIBS
"$T/llvm-strip" --strip-unneeded "$OUT/libhitvideo.so" "$WORK/hvbench"
cp "$PREFIX"/lib/lib*_hit.so "$OUT/"
ls -l "$OUT" "$WORK/hvbench"
