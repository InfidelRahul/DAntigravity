#!/usr/bin/env bash
# ==============================================================================
# DroidAntigravity Build Script
# Builds LinuxDroid PRoot runtime and Android APK (Debug / Release)
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="${SCRIPT_DIR}/android"
PROOT_REPO="${SCRIPT_DIR}/proot-repo"

BUILD_TYPE="${1:-release}"
if [[ "$BUILD_TYPE" != "debug" && "$BUILD_TYPE" != "release" ]]; then
    echo "Usage: $0 [debug|release]"
    exit 1
fi

echo "=========================================="
echo " Building DroidAntigravity ($BUILD_TYPE)"
echo "=========================================="

# 1. Check Submodules
if [ ! -f "${PROOT_REPO}/CMakeLists.txt" ]; then
    echo "Initializing PRoot submodule..."
    git submodule update --init --recursive
fi

# 2. Check Java / Android SDK / NDK Environment
if [ -z "${JAVA_HOME:-}" ] || [ ! -d "${JAVA_HOME:-}" ]; then
    if [ -d "/usr/local/sdkman/candidates/java/21.0.12+1-ms" ]; then
        export JAVA_HOME="/usr/local/sdkman/candidates/java/21.0.12+1-ms"
    elif [ -d "/opt/jdk-17" ]; then
        export JAVA_HOME="/opt/jdk-17"
    elif [ -d "/opt/jdk-17.0.20.1+1" ]; then
        export JAVA_HOME="/opt/jdk-17.0.20.1+1"
    fi
fi

if [ -n "${JAVA_HOME:-}" ] && [ -d "${JAVA_HOME:-}" ]; then
    export PATH="${JAVA_HOME}/bin:${PATH}"
fi

if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    if [ -f "${ANDROID_DIR}/local.properties" ]; then
        SDK_PROP=$(grep "^sdk.dir=" "${ANDROID_DIR}/local.properties" | cut -d'=' -f2 | sed 's/\\//g')
        if [ -n "$SDK_PROP" ] && [ -d "$SDK_PROP" ]; then
            export ANDROID_HOME="$SDK_PROP"
            export ANDROID_SDK_ROOT="$SDK_PROP"
        fi
    fi
fi

if [ -z "${ANDROID_HOME:-}" ]; then
    echo "ERROR: ANDROID_HOME or ANDROID_SDK_ROOT is not set."
    echo "Please set ANDROID_HOME or configure local.properties in android/"
    exit 1
fi

# Auto-detect NDK if not set
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    if [ -d "${ANDROID_HOME}/ndk" ]; then
        NDK_LATEST=$(find "${ANDROID_HOME}/ndk" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -n 1)
        if [ -n "$NDK_LATEST" ]; then
            export ANDROID_NDK_HOME="$NDK_LATEST"
            echo "Auto-detected NDK: ${ANDROID_NDK_HOME}"
        fi
    fi
fi

chmod +x "${ANDROID_DIR}/gradlew"

cd "${ANDROID_DIR}"

if [ "$BUILD_TYPE" = "debug" ]; then
    ./gradlew assembleDebug --no-configuration-cache
    APK="${ANDROID_DIR}/app/build/outputs/apk/debug/app-debug.apk"
else
    if [ -z "${KEYSTORE_FILE:-}" ] || [ -z "${KEYSTORE_PASSWORD:-}" ] || [ -z "${KEY_ALIAS:-}" ] || [ -z "${KEY_PASSWORD:-}" ]; then
        echo "ERROR: Release signing credentials are required."
        echo "Set KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD."
        echo "Use ./build.sh debug for unsigned/development testing."
        exit 1
    fi

    ./gradlew assembleRelease --no-configuration-cache
    if [ -f "${ANDROID_DIR}/app/build/outputs/apk/release/app-release.apk" ]; then
        APK="${ANDROID_DIR}/app/build/outputs/apk/release/app-release.apk"
    else
        APK="${ANDROID_DIR}/app/build/outputs/apk/release/app-release-unsigned.apk"
    fi
fi

if [ -f "$APK" ]; then
    echo "=========================================="
    echo " Build Succeeded!"
    echo " APK: $APK"
    echo " Size: $(du -h "$APK" | cut -f1)"
    echo "=========================================="
else
    echo "ERROR: Expected APK not found at $APK"
    exit 1
fi
