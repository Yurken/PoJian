#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
latexmk -xelatex -jobname=pojian-report main.tex
cp build/pojian-report.pdf '破茧_作品报告_第一至四章.pdf'
