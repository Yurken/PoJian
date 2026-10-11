#!/usr/bin/env bash
# No model, API, emulator, or deployment. Uses the repository's cached Android/JVM build dependencies.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
QUALITY_DATASET="$ROOT/evaluation/datasets/synthetic-v1"
QUALITY_OUTPUT="$ROOT/app/build/reports/history-quality"
while (($#)); do
    case "$1" in
        --dataset|--output)
            (($# >= 2)) || { echo "Missing value for $1" >&2; exit 2; }
            if [[ "$1" == --dataset ]]; then QUALITY_DATASET="$2"; else QUALITY_OUTPUT="$2"; fi
            shift 2 ;;
        -h|--help)
            echo "Usage: bash scripts/evaluate-history-quality.sh [--dataset FOLDER] [--output FOLDER]"
            exit 0 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done
if [[ "$QUALITY_DATASET" != /* ]]; then QUALITY_DATASET="$PWD/$QUALITY_DATASET"; fi
if [[ "$QUALITY_OUTPUT" != /* ]]; then QUALITY_OUTPUT="$PWD/$QUALITY_OUTPUT"; fi
# prepareSherpa is a custom task that can download an AAR independently of Gradle --offline.
# Require its already-provisioned verified artifact so this evaluation stays offline even on a fresh checkout.
QUALITY_AAR="$ROOT/app/build/asr-dependencies/sherpa-onnx-1.13.8.aar"
if [[ ! -f "$QUALITY_AAR" ]] || [[ "$(shasum -a 256 "$QUALITY_AAR" | cut -d ' ' -f 1)" != 633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96 ]]; then
    echo "Offline evaluation needs the verified AAR provisioned by the normal Android build; see docs/OFFLINE_QUALITY_EVALUATION.md." >&2
    exit 2
fi
cd "$ROOT"
./gradlew --offline --no-daemon :app:evaluateHistoryQuality \
    "-Pquality.dataset=$QUALITY_DATASET" "-Pquality.output=$QUALITY_OUTPUT"
