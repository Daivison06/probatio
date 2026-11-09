#!/bin/sh
# Install Probatio's toolchain into $PROBATIO_HOME (default ~/.probatio) without sudo:
#   - tla2tools.jar, pinned and checksum-verified
#   - a Temurin JDK 21, only when no JDK 17+ with a compiler is already on PATH
# Afterwards run tools with $PROBATIO_HOME/java (a link to the JDK in use).
set -eu

TLA_VERSION=v1.8.0
TLA_URL="https://github.com/tlaplus/tlaplus/releases/download/$TLA_VERSION/tla2tools.jar"
TLA_SHA256=e1f11b75c4b5bc4478ba0ac5cb6ae28e40a01b0016bc33e0aa97da22dfdbcd7f
JDK_RELEASE=jdk-21.0.13+9
JDK_BASE="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9"
JDK_FILE_VERSION=21.0.13_9

HOME_DIR="${PROBATIO_HOME:-$HOME/.probatio}"

fail() { echo "install: $*" >&2; exit 1; }

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1
  else fail "need sha256sum or shasum"; fi
}

download() {
  if command -v curl >/dev/null 2>&1; then curl -fsSL -o "$2" "$1"
  elif command -v wget >/dev/null 2>&1; then wget -q -O "$2" "$1"
  else fail "need curl or wget"; fi
}

# Download $1 to $2, accepting it only if its SHA-256 equals $3.
fetch_verified() {
  partial="$2.partial"
  download "$1" "$partial"
  actual=$(sha256_of "$partial")
  if [ "$actual" != "$3" ]; then
    rm -f "$partial"
    fail "checksum mismatch for $1 (expected $3, got $actual)"
  fi
  mv "$partial" "$2"
}

# A usable JDK runs source files directly, which needs Java 17+ and the jdk.compiler module.
usable_jdk() {
  "$1" --list-modules 2>/dev/null | grep -q '^jdk.compiler' || return 1
  major=$("$1" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')
  [ -n "$major" ] && [ "$major" -ge 17 ] 2>/dev/null
}

jdk_asset() {
  case "$(uname -s)" in
    Darwin) os=mac ;;
    Linux) os=linux ;;
    *) fail "unsupported OS $(uname -s); on Windows use scripts/install.ps1" ;;
  esac
  case "$(uname -m)" in
    arm64|aarch64) arch=aarch64 ;;
    x86_64|amd64) arch=x64 ;;
    *) fail "unsupported CPU $(uname -m)" ;;
  esac
  case "${arch}_$os" in
    aarch64_mac) sha=bf036b35b071bfb2f54f2c0af8ee8d88afa95ee6eb3dce02c0f94cfc2524d04a ;;
    x64_mac) sha=9aa1d1c17ae5467a022679e7e0af5c00797d94ac462ed1a46f4db07a8a34a6dd ;;
    aarch64_linux) sha=e3522e707fc7dbe2a72c30746b1e7068301a0213ecd213d4491f35a14eb55578 ;;
    x64_linux) sha=d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c ;;
  esac
  echo "OpenJDK21U-jdk_${arch}_${os}_hotspot_$JDK_FILE_VERSION.tar.gz $sha"
}

install_jar() {
  jar="$HOME_DIR/tla2tools.jar"
  if [ -f "$jar" ] && [ "$(sha256_of "$jar")" = "$TLA_SHA256" ]; then
    echo "tla2tools.jar $TLA_VERSION already installed"
    return
  fi
  [ -f "$jar" ] && echo "Replacing $jar with the pinned $TLA_VERSION"
  echo "Downloading tla2tools.jar $TLA_VERSION"
  fetch_verified "$TLA_URL" "$jar" "$TLA_SHA256"
}

install_jdk() {
  existing=$(command -v java 2>/dev/null || true)
  if [ -n "$existing" ] && usable_jdk "$existing"; then
    echo "Using the JDK on PATH: $existing"
    ln -sf "$existing" "$HOME_DIR/java"
    return
  fi
  target="$HOME_DIR/$JDK_RELEASE"
  if [ ! -d "$target" ]; then
    asset=$(jdk_asset) || exit 1
    set -- $asset
    echo "Downloading Temurin $JDK_RELEASE"
    fetch_verified "$JDK_BASE/$1" "$HOME_DIR/jdk.tar.gz" "$2"
    mkdir -p "$target.partial"
    tar -xzf "$HOME_DIR/jdk.tar.gz" -C "$target.partial" --strip-components 1
    rm -f "$HOME_DIR/jdk.tar.gz"
    mv "$target.partial" "$target"
  fi
  java="$target/bin/java"
  [ -x "$java" ] || java="$target/Contents/Home/bin/java"
  [ -x "$java" ] || fail "JDK unpacked without a java binary under $target"
  ln -sf "$java" "$HOME_DIR/java"
}

mkdir -p "$HOME_DIR"
install_jar
install_jdk
echo "Probatio toolchain ready in $HOME_DIR"
echo "Run: $HOME_DIR/java scripts/Probatio.java tlc Model.tla Model.cfg --out checks/run-01"
