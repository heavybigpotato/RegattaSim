// Sampling the boat's shadow map.
//
// Included by both the boat and the water, because the shadow has to fall on both:
// the sails shade the deck, and the whole rig lays a long dark stripe across the sea
// to leeward. That stripe is most of why a boat looks like it is *in* the water
// rather than sitting on a picture of it, and it is the one lighting cue no amount
// of shading on the hull itself can substitute for.
//
// One orthographic cascade, fitted around the boat. There is exactly one shadow
// caster in the scene and it is twenty metres tall, so the usual cascaded machinery
// would be solving a problem this renderer does not have.

uniform sampler2D u_shadowMap;
uniform mat4 u_lightViewProjection;
/** One texel of the shadow map, in map coordinates. */
uniform float u_shadowTexel;
/**
 * How dark the shadow goes, and whether there is one at all.
 *
 * Zero means no caster, and the lookup returns fully lit before it samples
 * anything - which matters, because with no boat in the scene the map holds
 * whatever was last left in it.
 */
uniform float u_shadowStrength;

/**
 * How much sun reaches a world position: 1 fully lit, 0 fully shadowed.
 *
 * @param nDotL cosine of the angle between the surface and the sun, for the bias
 */
float sunVisibility(vec3 worldPosition, float nDotL) {
    if (u_shadowStrength <= 0.0) {
        return 1.0;
    }

    vec4 lightClip = u_lightViewProjection * vec4(worldPosition, 1.0);
    vec3 mapPosition = lightClip.xyz / lightClip.w * 0.5 + 0.5;
    if (mapPosition.z > 1.0 || mapPosition.z < 0.0) {
        return 1.0;
    }

    // Faded out toward the edge of the map. The map only covers a box around the
    // boat, and without this the sea would carry a hard rectangle where the coverage
    // stops - far more obvious than the missing shadow would have been.
    //
    // Computed before the filter rather than after it, and returned from early once
    // it has already decided the answer. Every water pixel on screen reaches this
    // function and the overwhelming majority are outside the box; taking nine
    // texture fetches to arrive at a value the fade then multiplies away is the
    // whole cost of the feature, spent on nothing.
    vec2 fromCentre = abs(mapPosition.xy - 0.5) * 2.0;
    float inside = 1.0 - smoothstep(0.80, 1.0, max(fromCentre.x, fromCentre.y));
    if (inside <= 0.0) {
        return 1.0;
    }

    // Slope-scaled: a surface nearly edge-on to the sun spans many times the depth
    // of one texel, so a constant bias either lets it shadow itself in stripes or
    // has to be so large that contact shadows lift off the deck.
    float bias = mix(0.004, 0.0004, clamp(nDotL, 0.0, 1.0));

    // Three by three, which is enough here. The caster is small in the frame and the
    // map is dense over it, so the edge is already only a pixel or two wide; a wider
    // kernel would blur a rig's shadow into a smudge rather than soften it.
    float lit = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 at = mapPosition.xy + vec2(float(x), float(y)) * u_shadowTexel;
            float occluder = texture(u_shadowMap, at).r;
            lit += mapPosition.z - bias <= occluder ? 1.0 : 0.0;
        }
    }
    lit /= 9.0;

    return mix(1.0, mix(1.0, lit, u_shadowStrength), inside);
}
