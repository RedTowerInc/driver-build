#!/usr/bin/env bash
set -euo pipefail
root_dir="$(pwd)"
work_dir="$root_dir/build/native-deps"
mkdir -p "$work_dir" app/src/main/jniLibs
sdkmanager 'ndk;27.2.12479018'
if [ ! -d "$work_dir/hev/.git" ]; then git clone --depth 1 --branch 2.18.0 --recursive https://github.com/heiher/hev-socks5-tunnel "$work_dir/hev"; fi
"$ANDROID_HOME/ndk/27.2.12479018/ndk-build" -C "$work_dir/hev" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=Android.mk APP_ABI='arm64-v8a armeabi-v7a x86_64' APP_PLATFORM=android-28 APP_SUPPORT_FLEXIBLE_PAGE_SIZES=true -j2
if [ ! -d "$work_dir/sing-box/.git" ]; then git clone --depth 1 --branch v1.14.3 https://github.com/SagerNet/sing-box "$work_dir/sing-box"; fi
[ "$(git -C "$work_dir/sing-box" rev-parse HEAD)" = 7054cac5124465ffdb145870d7c01885566711b1 ]
ndk_bin="$ANDROID_HOME/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin"
for entry in 'arm64-v8a:arm64:aarch64-linux-android28' 'armeabi-v7a:arm:armv7a-linux-androideabi28' 'x86_64:amd64:x86_64-linux-android28'; do
  IFS=: read -r abi arch clang_target <<< "$entry"
  mkdir -p "app/src/main/jniLibs/$abi" "$work_dir/compiled/$abi"
  cp "$work_dir/hev/libs/$abi/libhev-socks5-tunnel.so" "app/src/main/jniLibs/$abi/"
  if [ ! -f "$work_dir/compiled/$abi/libsingbox.so" ]; then
    (cd "$work_dir/sing-box" && CGO_ENABLED=1 GOOS=android GOARCH="$arch" GOARM=7 CC="$ndk_bin/$clang_target-clang" CXX="$ndk_bin/$clang_target-clang++" go build -p 2 -trimpath -buildvcs=false -tags with_utls -ldflags '-s -w -linkmode external -extldflags "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"' -o "$work_dir/compiled/$abi/libsingbox.so" ./cmd/sing-box)
  fi
  cp "$work_dir/compiled/$abi/libsingbox.so" "app/src/main/jniLibs/$abi/"
done
python3 scripts/check-elf.py
mkdir -p app/src/main/assets/licenses
cp "$work_dir/hev/LICENSE" app/src/main/assets/licenses/HEV-LICENSE.txt
cp "$work_dir/sing-box/LICENSE" app/src/main/assets/licenses/SING-BOX-LICENSE.txt
