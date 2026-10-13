#!/usr/bin/env bash
set -euo pipefail
root_dir="$(pwd)"
work_dir="$root_dir/build/native-deps"
mkdir -p "$work_dir" app/src/main/jniLibs
sdkmanager 'ndk;27.2.12479018'
if [ ! -d "$work_dir/hev/.git" ]; then
  git clone --depth 1 --branch 2.18.0 --recursive https://github.com/heiher/hev-socks5-tunnel "$work_dir/hev"
fi
"$ANDROID_HOME/ndk/27.2.12479018/ndk-build" -C "$work_dir/hev" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=Android.mk APP_ABI='arm64-v8a armeabi-v7a x86_64' APP_PLATFORM=android-28 APP_SUPPORT_FLEXIBLE_PAGE_SIZES=true -j2
for abi in arm64-v8a armeabi-v7a x86_64; do
  mkdir -p "app/src/main/jniLibs/$abi"
  cp "$work_dir/hev/libs/$abi/libhev-socks5-tunnel.so" "app/src/main/jniLibs/$abi/"
done
python3 scripts/fetch-core.py
mkdir -p app/src/main/assets/licenses
cp "$work_dir/hev/LICENSE" app/src/main/assets/licenses/HEV-LICENSE.txt
curl -fL --retry 3 https://raw.githubusercontent.com/SagerNet/sing-box/v1.14.3/LICENSE -o app/src/main/assets/licenses/SING-BOX-LICENSE.txt
