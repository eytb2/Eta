#!/usr/bin/env bash
# Build Eta Android app via Docker or local Gradle.
set -euo pipefail

cd "$(dirname "$0")"
ROOT="$(pwd)"

TASK="${1:-assembleDebug}"
IMG="agent-platform-android-builder:latest"
HOST_SDK="/media/lemon/tech_ext4/sdk"

# Temporarily stash daemon JVM spec to prevent Foojay toolchain download in container
if [ -f "$ROOT/gradle/gradle-daemon-jvm.properties" ]; then
  mv "$ROOT/gradle/gradle-daemon-jvm.properties" "$ROOT/gradle/gradle-daemon-jvm.properties.tmp"
  trap 'mv "$ROOT/gradle/gradle-daemon-jvm.properties.tmp" "$ROOT/gradle/gradle-daemon-jvm.properties" 2>/dev/null || true' EXIT
fi

if [ -d "$HOST_SDK" ] && docker image inspect "$IMG" >/dev/null 2>&1; then
  echo "==> Building via Docker ($IMG, sdk=$HOST_SDK)"
  mkdir -p "$ROOT/.gradle-cache" "$ROOT/.sdk-extra/cmake" "$ROOT/.sdk-extra/platforms"

  docker run --rm -i --network host \
    -v "$ROOT":/workspace \
    -v "$HOST_SDK":/sdkro:ro \
    -v "$ROOT/.sdk-extra":/sdkextra \
    -v "$ROOT/.gradle-cache":/root/.gradle \
    -w /workspace \
    -e GRADLE_USER_HOME=/root/.gradle \
    -e ETA_RELEASE_STORE_FILE=/workspace/keystore/release.jks \
    -e ETA_RELEASE_STORE_PASSWORD=eta123456 \
    -e ETA_RELEASE_KEY_ALIAS=eta \
    -e ETA_RELEASE_KEY_PASSWORD=eta123456 \
    "$IMG" \
    bash -lc '
      set -e
      mkdir -p /sdk /sdkextra/platforms /sdkextra/cmake
      for entry in /sdkro/*; do
        name=$(basename "$entry")
        [ "$name" = "platforms" ] && continue
        [ "$name" = "cmake" ] && continue
        ln -sf "$entry" "/sdk/$name"
      done

      mkdir -p /sdk/platforms
      for p in /sdkro/platforms/*; do
        [ -e "$p" ] && ln -sf "$p" "/sdk/platforms/$(basename "$p")"
      done

      ln -sf /sdkextra/cmake /sdk/cmake
      export ANDROID_HOME=/sdk ANDROID_SDK_ROOT=/sdk
      export JAVA_HOME=/sdk/jdks/jdk-25
      export PATH=$JAVA_HOME/bin:$PATH

      GRADLE_BIN=$(find /sdkro/.gradle/wrapper/dists/gradle-9.7.1-bin/ -name gradle -type f -executable 2>/dev/null | head -n 1)
      if [ -z "$GRADLE_BIN" ] || [ ! -x "$GRADLE_BIN" ]; then
        GRADLE_BIN=$(find /sdkro/.gradle/wrapper/dists/gradle-9.6.1-bin/ -name gradle -type f -executable 2>/dev/null | head -n 1)
      fi
      if [ -z "$GRADLE_BIN" ] || [ ! -x "$GRADLE_BIN" ]; then
        GRADLE_BIN=$(find /sdkro/.gradle/wrapper/dists/ -name gradle -type f -executable 2>/dev/null | head -n 1)
      fi
      INIT=/workspace/gradle/init-aliyun-mirror.gradle.kts
      GARGS=(--no-daemon \
        -Porg.gradle.java.installations.paths=/sdk/jdks/jdk-25 \
        -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 \
        -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 \
        -Dorg.gradle.internal.http.connectionTimeout=120000 \
        -Dorg.gradle.internal.http.socketTimeout=120000 \
        -Dorg.gradle.internal.repository.max.retries=5 \
        -Dorg.gradle.internal.repository.initial.backoff=1000 \
        :app:'"$TASK"')
      if [ -n "$GRADLE_BIN" ] && [ -x "$GRADLE_BIN" ]; then
        "$GRADLE_BIN" "${GARGS[@]}"
      else
        ./gradlew "${GARGS[@]}"
      fi
    '
else
  echo "==> Building locally via ./gradlew :app:$TASK"
  ./gradlew :app:"$TASK"
fi

echo
echo "==> Done. Outputs under $ROOT/app/build/outputs/"
