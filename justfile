set shell := ["bash", "-cu"]

default:
    just --list

setup-opencode:
    sh script/setup-opencode-submodule.sh

build:
    just build-node
    just build-android

build-node:
    npm run build

build-android:
    ./apps/android/gradlew --parallel -p apps/android build

build-server:
    npm run build:server

build-relay:
    npm run build:relay

check:
    just check-node
    just check-android

check-node:
    npm run check

check-android:
    ./apps/android/gradlew --parallel -p apps/android ktlintCheck

test-android:
    ./apps/android/gradlew --parallel -p apps/android test

release-android:
    ./apps/android/gradlew --parallel -p apps/android :app:assembleRelease
