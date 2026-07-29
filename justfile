set shell := ["bash", "-cu"]

default:
    just --list

build:
    ./apps/android/gradlew --parallel -p apps/android build

format:
    ./apps/android/gradlew --parallel -p apps/android ktlintFormat

check:
    ./apps/android/gradlew --parallel -p apps/android ktlintCheck

test:
    ./apps/android/gradlew --parallel -p apps/android test

assemble-debug:
    ./apps/android/gradlew --parallel -p apps/android :app:assembleDebug

install-debug:
    ./apps/android/gradlew --parallel -p apps/android :app:installDebug

release:
    ./apps/android/gradlew --parallel -p apps/android :app:assembleRelease
