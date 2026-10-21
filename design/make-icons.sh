#!/usr/bin/env bash
# Generates every app icon file from design/Linkdrop-icon.png (512x512). The artwork is only
# resized and placed, never redrawn. Needs ImageMagick 7 (`magick`). Run from anywhere:
#
#   design/make-icons.sh
set -euo pipefail
cd "$(dirname "$0")/.."

SRC=design/Linkdrop-icon.png
RES=androidApp/src/main/res

# Android adaptive icon: launchers may crop the 108dp canvas to a circle that keeps only the middle
# 66dp. Measured, the farthest white pixel is 198 px from the center of the 512 px image, so the
# whole image fits that circle when drawn at 79% of the canvas; 76% leaves a small margin.
ADAPTIVE_SCALE=76

# Pixels per dp for each density, and the sizes that follow from it.
for density in mdpi:1 hdpi:1.5 xhdpi:2 xxhdpi:3 xxxhdpi:4; do
    name=${density%%:*}
    factor=${density##*:}
    canvas=$(awk "BEGIN { printf \"%d\", 108 * $factor + 0.5 }")
    art=$(awk "BEGIN { printf \"%d\", 108 * $factor * $ADAPTIVE_SCALE / 100 + 0.5 }")
    legacy=$(awk "BEGIN { printf \"%d\", 48 * $factor + 0.5 }")
    out=$RES/mipmap-$name
    mkdir -p "$out"

    # Foreground: the image as is, centered on a transparent canvas; the background color fills the rest.
    magick "$SRC" -resize "${art}x${art}" -background none -gravity center -extent "${canvas}x${canvas}" \
        -strip "PNG32:$out/ic_launcher_foreground.png"

    # Monochrome (themed icons, Android 13+): Android uses only its transparency and tints it, so
    # the white links become the opaque shape and the black becomes transparent. Same placement.
    magick "$SRC" -colorspace Gray -alpha copy -fill white -colorize 100 -colorspace sRGB \
        -resize "${art}x${art}" -background none -gravity center -extent "${canvas}x${canvas}" \
        -strip "PNG32:$out/ic_launcher_monochrome.png"

    # Legacy icons (launchers without adaptive icons): the whole image, square and cut to a circle.
    # The links lie within 198 px of the center, inside the 256 px circle, so the circle cuts none.
    magick "$SRC" -resize "${legacy}x${legacy}" -strip "PNG32:$out/ic_launcher.png"
    radius=$(awk "BEGIN { printf \"%.2f\", $legacy / 2 }")
    magick "$SRC" -resize "${legacy}x${legacy}" -alpha set \
        \( -size "${legacy}x${legacy}" xc:none -fill white -draw "circle $radius,$radius $radius,0" \) \
        -compose DstIn -composite -strip "PNG32:$out/ic_launcher_round.png"
done

# Desktop: the whole image at each size the desktop uses (window icon, menu entry, packages). The
# window manager and the icon theme pick the size that fits best.
mkdir -p desktopApp/icons
for size in 16 32 48 64 128 256 512; do
    magick "$SRC" -resize "${size}x${size}" -strip "PNG32:desktopApp/icons/linkdrop-$size.png"
done
