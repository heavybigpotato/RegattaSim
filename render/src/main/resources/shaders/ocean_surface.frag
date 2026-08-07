// Ocean surface shading.
//
// The order matters and it is not arbitrary. Water is almost entirely a
// reflector at grazing angles and almost entirely a scatterer when you look
// straight down into it, so Fresnel is not a finishing touch here, it is the
// structure of the whole shader: reflection and scattering are computed
// separately and mixed by it.
//
//  1. Slopes are summed across cascades, with the fine ones faded by distance so
//     they cannot alias.
//  2. The normal accounts for horizontal compression, not just the height slope.
//     On a choppy sea the two differ sharply on the steep face of a crest, which
//     is exactly where the eye is looking.
//  3. Reflection is the analytic sky evaluated in the reflected direction. That
//     beats a cubemap: no resolution limit on the sun's reflection, no seams, and
//     it stays correct as the sun moves.
//  4. Subsurface scattering lights the wave from behind. This is the single most
//     valuable term in the shader - it is what turns grey geometry into water.
//  5. Foam from the Jacobian, broken up by Worley noise.

#pragma include "lib_ocean_common.glsl"
#pragma include "lib_sky.glsl"
#pragma include "lib_noise.glsl"
#pragma include "lib_shadow.glsl"

in vec3 v_worldPosition;
in vec3 v_undisplacedPosition;
in float v_viewDistance;

out vec4 fragColor;

uniform vec3 u_cameraPosition;
uniform vec3 u_sunDirection;
uniform vec3 u_sunColour;
uniform float u_turbidity;

uniform sampler2D u_derivatives0;
uniform sampler2D u_derivatives1;
uniform sampler2D u_derivatives2;
uniform vec3 u_patchSizes;
uniform int u_cascadeCount;
uniform float u_choppiness;

uniform vec3 u_scatterColour;      // colour of light that has been through the water
uniform vec3 u_deepColour;         // colour of water with nothing under it
uniform float u_waterDepth;        // metres, for Beer-Lambert attenuation
uniform vec3 u_extinction;         // per-metre attenuation, RGB
uniform float u_foamScale;
uniform float u_normalDetailFade;

// Smith-GGX specular for the sun, the only punctual light in the scene.
float ggxSpecular(vec3 n, vec3 v, vec3 l, float roughness) {
    vec3 h = normalize(v + l);
    float a = roughness * roughness;
    float a2 = a * a;
    float nh = max(dot(n, h), 0.0);
    float nv = max(dot(n, v), 1e-4);
    float nl = max(dot(n, l), 0.0);

    float d = nh * nh * (a2 - 1.0) + 1.0;
    float distribution = a2 / (SKY_PI * d * d);

    float k = a * 0.5;
    float gv = nv / (nv * (1.0 - k) + k);
    float gl = nl / (nl * (1.0 - k) + k);

    return distribution * gv * gl / (4.0 * nv * max(nl, 1e-4)) * nl;
}

void main() {
    vec3 viewVector = u_cameraPosition - v_worldPosition;
    vec3 v = normalize(viewVector);

    // Fine cascades carry detail that falls below a pixel with distance. Letting
    // it through produces a crawling glitter that reads as noise, so each cascade
    // fades once its wavelength approaches the projected pixel size.
    vec2 slope = vec2(0.0);
    float jacobian = 1.0;
    float foam = 0.0;

    vec4 d0 = texture(u_derivatives0, v_undisplacedPosition.xz / u_patchSizes.x);
    slope += d0.xy;
    jacobian = min(jacobian, d0.z);
    foam = max(foam, d0.w);

    if (u_cascadeCount > 1) {
        float w = 1.0 - smoothstep(u_patchSizes.y * u_normalDetailFade,
                                   u_patchSizes.y * u_normalDetailFade * 4.0, v_viewDistance);
        vec4 d1 = texture(u_derivatives1, v_undisplacedPosition.xz / u_patchSizes.y);
        slope += d1.xy * w;
        jacobian = min(jacobian, mix(1.0, d1.z, w));
        foam = max(foam, d1.w * w);
    }
    if (u_cascadeCount > 2) {
        float w = 1.0 - smoothstep(u_patchSizes.z * u_normalDetailFade,
                                   u_patchSizes.z * u_normalDetailFade * 4.0, v_viewDistance);
        vec4 d2 = texture(u_derivatives2, v_undisplacedPosition.xz / u_patchSizes.z);
        slope += d2.xy * w;
        jacobian = min(jacobian, mix(1.0, d2.z, w));
        foam = max(foam, d2.w * w);
    }

    // Horizontal compression steepens the apparent slope: a patch squeezed to
    // half its width doubles the gradient across it.
    float compression = max(0.25, jacobian);
    vec3 n = normalize(vec3(-slope.x / compression, 1.0, -slope.y / compression));

    float nv = max(dot(n, v), 0.0);
    float fresnel = WATER_F0 + (1.0 - WATER_F0) * pow(1.0 - nv, 5.0);

    // --- Reflection -------------------------------------------------------
    vec3 reflectDir = reflect(-v, n);
    vec3 reflection = skyRadiance(reflectDir, u_sunDirection, u_turbidity);

    // A rough sea reflects a blurred sky. Approximated by blending toward the
    // radiance straight up, which is the mean of the hemisphere to first order.
    float roughness = clamp(0.02 + 0.28 * length(slope), 0.02, 0.4);
    vec3 averageSky = skyRadiance(vec3(0.0, 1.0, 0.0), u_sunDirection, u_turbidity);
    reflection = mix(reflection, averageSky, roughness * 0.8);

    // The GGX lobe is already normalised, so this is a plain intensity for the
    // solar disc. Pushed harder the sun track stops being glitter on facets and
    // becomes a row of blown white discs.
    // How much sun reaches this patch of water. Nothing else in the scene casts,
    // so this is the boat's shadow and only the boat's - a long dark stripe running
    // away to leeward, which is the cue that puts the hull *in* the water.
    float sunlit = sunVisibility(v_worldPosition, max(dot(n, u_sunDirection), 0.0));
    reflection += u_sunColour * ggxSpecular(n, v, u_sunDirection, roughness) * 1.6 * sunlit;

    // --- Transmission and subsurface scattering ---------------------------
    // Height above the mean surface drives the scattering: a crest is a thin
    // wedge of water with the sun behind it, a trough is deep and dark.
    float heightAboveMean = max(0.0, v_worldPosition.y);

    // Light bending through the crest toward the eye.
    float backLight = pow(max(0.0, dot(v, -u_sunDirection)), 4.0);
    // Strongest when the wave face is tilted away from the sun.
    float facing = pow(max(0.0, 0.5 - 0.5 * dot(u_sunDirection, n)), 3.0);
    float scatter = heightAboveMean * backLight * facing * 1.8;
    // A weaker, view-independent term so troughs are not pure black.
    scatter += 0.35 * max(0.0, dot(n, v));

    // Scattering is light coming up through the wave from the far side, so the
    // shadow kills it too - a shadowed crest loses its glow, which is most of what
    // makes the stripe read as a shadow rather than as a dark patch of water.
    vec3 transmitted = u_scatterColour * scatter * u_sunColour * sunlit;

    // Beer-Lambert through the water column to whatever is beneath. In the open
    // ocean this saturates to the deep colour within a few metres, which is why
    // deep water is blue regardless of the bottom.
    vec3 attenuation = exp(-u_extinction * u_waterDepth);
    transmitted += mix(u_deepColour, u_deepColour * 1.6, attenuation.b) * 0.25
                 * max(0.15, u_sunColour.g);

    vec3 colour = mix(transmitted, reflection, fresnel);

    // --- Foam -------------------------------------------------------------
    if (foam > 0.001) {
        float pattern = foamPattern(v_undisplacedPosition.xz * u_foamScale);
        float coverage = clamp(foam * 1.4, 0.0, 1.0);
        // Foam appears where the accumulated value exceeds the local threshold of
        // the noise field, so rafts grow and shrink instead of fading uniformly.
        float mask = smoothstep(1.0 - coverage - 0.15, 1.0 - coverage + 0.15, pattern);
        vec3 foamLit = vec3(0.85) * (max(0.25, dot(n, u_sunDirection)) * u_sunColour * sunlit
                                     + averageSky * 0.35);
        colour = mix(colour, foamLit, mask * coverage);
    }

    fragColor = vec4(colour, 1.0);
}
