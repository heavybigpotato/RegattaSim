// HDR resolve: exposure, ACES, then the sRGB transfer function.
//
// Written manually rather than relying on an sRGB framebuffer, because sRGB
// default framebuffers are inconsistently supported across Android drivers and a
// silently linear output looks washed out in a way that is easy to misdiagnose as
// a lighting bug.

#pragma include "lib_tonemap.glsl"

in vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_hdr;
uniform sampler2D u_bloom;
uniform float u_exposure;
uniform float u_bloomStrength;
uniform float u_vignette;

void main() {
    // The bloom chain has already been exposed; the scene has not.
    vec3 hdr = texture(u_hdr, v_uv).rgb * u_exposure;
    hdr += texture(u_bloom, v_uv).rgb * u_bloomStrength;

    vec3 mapped = acesTonemap(hdr);

    // Very slight corner darkening: the sea is brightest in the centre of frame
    // where the sun track is, and a touch of vignette keeps the eye there.
    vec2 centred = v_uv * 2.0 - 1.0;
    float vignette = 1.0 - u_vignette * dot(centred, centred) * 0.25;
    mapped *= vignette;

    fragColor = vec4(linearToSrgb(mapped), 1.0);
}
