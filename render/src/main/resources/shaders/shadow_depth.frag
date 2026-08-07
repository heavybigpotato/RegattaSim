// Shadow map depth pass, fragment stage.
//
// Written to a colour target rather than sampled from a depth attachment. A depth
// texture would be one texture instead of two, but the formats and the comparison
// modes differ enough between desktop GL, GLES 3.0 and WebGL 2 that it becomes
// three code paths for a saving of a few hundred kilobytes. A single-channel float
// colour target behaves identically everywhere and is read with an ordinary
// texture fetch.

in float v_depth;

out vec4 fragColor;

void main() {
    fragColor = vec4(v_depth, 0.0, 0.0, 1.0);
}
