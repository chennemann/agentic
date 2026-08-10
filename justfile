set shell := ["bash", "-cu"]
set windows-shell := ["C:/Program Files/Git/bin/bash.exe", "-cu"]

android_dir := "apps/android"
t3code_dir := "apps/t3code"

default:
    just --list

# Android

build:
    ./{{android_dir}}/gradlew --parallel -p {{android_dir}} build

format:
    ./{{android_dir}}/gradlew --parallel -p {{android_dir}} ktlintFormat

check:
    ./{{android_dir}}/gradlew --parallel -p {{android_dir}} ktlintCheck

test:
    ./{{android_dir}}/gradlew --parallel -p {{android_dir}} test

assemble-debug:
    ./{{android_dir}}/gradlew --parallel -p {{android_dir}} :app:assembleDebug

install-debug:
    ./{{android_dir}}/gradlew --parallel -p {{android_dir}} :app:installDebug

release:
    ./{{android_dir}}/gradlew --parallel -p {{android_dir}} :app:assembleRelease

# T3 Code

t3-status:
    git -C {{t3code_dir}} status --short --branch

t3-install:
    cd {{t3code_dir}} && vp i

t3-dev:
    cd {{t3code_dir}} && vp run dev
