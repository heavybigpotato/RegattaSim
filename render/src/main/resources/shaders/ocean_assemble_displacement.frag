// Unpacks the transformed signal set 0 into a world-space displacement map.
//
// After the inverse transform the packed signal A + i*B has A in .r and B in .g
// (and likewise .b / .a for the second pair), because both A and B were Hermitian
// and therefore transform to real fields.

in vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_spatialA;   // Dx, Dz, h, dDx/dz
uniform float u_choppiness;

void main() {
    vec4 s = texelFetch(u_spatialA, ivec2(gl_FragCoord.xy), 0);
    float dx = s.r * u_choppiness;
    float dz = s.g * u_choppiness;
    float height = s.b;
    fragColor = vec4(dx, height, dz, 0.0);
}
