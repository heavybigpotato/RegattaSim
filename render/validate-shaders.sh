#!/bin/sh
# Validates every shader with glslangValidator.
#
# Shaders are stored without a #version line and with "#pragma include" so the
# same body compiles as GLSL 330 core on desktop and GLSL ES 300 on Android. This
# script performs the same assembly ShaderSources.java does at runtime, then hands
# the result to the validator.
#
# It exits 0 when glslangValidator is not installed. A build machine without it
# still builds; CI installs it, so the check is enforced there.

set -e

SHADER_DIR="src/main/resources/shaders"
BUILD_DIR="build/shader-validation"
LOG="build/shader-validation.log"

mkdir -p "$BUILD_DIR"
mkdir -p build
: > "$LOG"

if ! command -v glslangValidator >/dev/null 2>&1; then
    echo "glslangValidator not installed - shader validation skipped" | tee -a "$LOG"
    exit 0
fi

# Resolves #pragma include recursively.
resolve() {
    file="$1"
    while IFS= read -r line; do
        case "$line" in
            *'#pragma include'*)
                inc=$(printf '%s' "$line" | sed -n 's/.*"\(.*\)".*/\1/p')
                if [ -z "$inc" ]; then
                    echo "malformed include in $file: $line" >&2
                    exit 1
                fi
                resolve "$SHADER_DIR/$inc"
                ;;
            *)
                printf '%s\n' "$line"
                ;;
        esac
    done < "$file"
}

status=0
count=0

for shader in "$SHADER_DIR"/*.vert "$SHADER_DIR"/*.frag; do
    [ -e "$shader" ] || continue
    name=$(basename "$shader")
    case "$name" in
        *.vert) stage=vert ;;
        *.frag) stage=frag ;;
    esac

    out="$BUILD_DIR/$name"
    {
        echo "#version 330 core"
        echo "precision highp float;"
        echo "precision highp int;"
        echo "precision highp sampler2D;"
        resolve "$shader"
    } > "$out"

    if glslangValidator -S "$stage" "$out" >> "$LOG" 2>&1; then
        count=$((count + 1))
    else
        echo "FAILED: $name" | tee -a "$LOG"
        status=1
    fi
done

if [ "$status" -eq 0 ]; then
    echo "validated $count shaders" | tee -a "$LOG"
else
    echo "shader validation failed - see $LOG" >&2
    tail -40 "$LOG" >&2
fi

exit "$status"
