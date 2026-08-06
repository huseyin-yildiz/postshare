#!/usr/bin/env bash
# Build PostShare without Android Studio.
#
# Downloads (once, cached under .tools/) a JDK 17, the Android SDK command-line
# tools + platform 34, and Gradle 8.2, then compiles the app APK.
#
# Usage: ./scripts/build.sh
# Output: app/build/outputs/apk/debug/app-debug.apk
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
TOOLS_DIR="$ROOT_DIR/.tools"
SDK_DIR="$TOOLS_DIR/android-sdk"

ARCH="$(uname -m)"
if [[ "$ARCH" == "arm64" ]]; then JDK_ARCH="aarch64"; else JDK_ARCH="x64"; fi

mkdir -p "$TOOLS_DIR"

info()  { printf '\033[0;34m==>\033[0m %s\n' "$*"; }
ok()    { printf '\033[0;32m==>\033[0m %s\n' "$*"; }

# --- JDK 17 (Temurin) -----------------------------------------------------
JAVA_HOME_DIR="$TOOLS_DIR/jdk-17"
if [[ ! -x "$JAVA_HOME_DIR/Contents/Home/bin/java" ]]; then
  info "Downloading JDK 17 (Temurin, $JDK_ARCH)..."
  curl -fSL -o "$TOOLS_DIR/jdk.tar.gz" \
    "https://api.adoptium.net/v3/binary/latest/17/ga/mac/$JDK_ARCH/jdk/hotspot/normal/eclipse?project=jdk"
  mkdir -p "$JAVA_HOME_DIR"
  tar -xzf "$TOOLS_DIR/jdk.tar.gz" --strip-components=1 -C "$JAVA_HOME_DIR"
  rm -f "$TOOLS_DIR/jdk.tar.gz"
fi
export JAVA_HOME="$JAVA_HOME_DIR/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
ok "JDK: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"

# --- Android SDK command-line tools ---------------------------------------
SDK_TOOLS="$SDK_DIR/cmdline-tools/latest/bin"
if [[ ! -x "$SDK_TOOLS/sdkmanager" ]]; then
  info "Downloading Android SDK command-line tools..."
  curl -fSL -o "$TOOLS_DIR/cmdline-tools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip"
  mkdir -p "$SDK_DIR/cmdline-tools"
  unzip -q "$TOOLS_DIR/cmdline-tools.zip" -d "$TOOLS_DIR/cmdline-tools-tmp"
  mv "$TOOLS_DIR/cmdline-tools-tmp/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
  rm -rf "$TOOLS_DIR/cmdline-tools-tmp" "$TOOLS_DIR/cmdline-tools.zip"
fi

PACKAGES="platform-tools platforms;android-34 build-tools;34.0.0"
MISSING=()
for p in "platform-tools" "platforms;android-34" "build-tools;34.0.0"; do
  d=""
  if [[ "$p" == "platform-tools" ]]; then
    d="$SDK_DIR/platform-tools"
  elif [[ "$p" == platforms\;* ]]; then
    d="$SDK_DIR/platforms/${p#platforms;}"
  elif [[ "$p" == build-tools\;* ]]; then
    d="$SDK_DIR/build-tools/${p#build-tools;}"
  fi
  [[ -n "$d" && ! -d "$d" ]] && MISSING+=("$p")
done
if [[ ${#MISSING[@]} -gt 0 ]]; then
  info "Installing Android SDK packages: ${MISSING[*]}"
  yes | "$SDK_TOOLS/sdkmanager" --sdk_root="$SDK_DIR" --licenses >/dev/null 2>&1 || true
  yes | "$SDK_TOOLS/sdkmanager" --sdk_root="$SDK_DIR" "${MISSING[@]}"
fi
ok "Android SDK ready at $SDK_DIR"

# --- Gradle ---------------------------------------------------------------
GRADLE_HOME="$TOOLS_DIR/gradle-8.2"
if [[ ! -x "$GRADLE_HOME/bin/gradle" ]]; then
  info "Downloading Gradle 8.2..."
  curl -fSL -o "$TOOLS_DIR/gradle.zip" \
    "https://services.gradle.org/distributions/gradle-8.2-bin.zip"
  unzip -q "$TOOLS_DIR/gradle.zip" -d "$TOOLS_DIR"
  rm -f "$TOOLS_DIR/gradle.zip"
fi

# --- Local SDK path --------------------------------------------------------
cat > "$ROOT_DIR/local.properties" <<EOF
sdk.dir=$SDK_DIR
EOF

# --- Build ----------------------------------------------------------------
info "Building debug APK (first run downloads Gradle dependencies)..."
cd "$ROOT_DIR"
"$GRADLE_HOME/bin/gradle" --no-daemon assembleDebug

APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
ok "Build finished: $APK"
ls -lh "$APK"
