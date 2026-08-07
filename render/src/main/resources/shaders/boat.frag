// Boat shading.
//
// Lit by exactly the same sun and sky the water is, through the same analytic
// model, so the boat cannot end up looking pasted onto the scene - which is the
// usual giveaway when a hull is lit by its own private light rig.
//
// The model is the same one the ocean uses: GGX for the specular lobe, Schlick
// Fresnel, Smith visibility, energy-conserving Lambert underneath. A racing boat is
// mostly gelcoat and carbon, and both are dielectrics with a hard clearcoat - which
// means the thing that makes them read as real is the *sharpness* of the sky
// reflection along the topsides, not the diffuse colour. A flat-shaded hull with a
// constant albedo has none of that and looks like painted card no matter how many
// triangles it has.

#pragma include "lib_sky.glsl"
#pragma include "lib_noise.glsl"

in vec3 v_worldPosition;
in vec3 v_normal;
in float v_material;
in float v_height;
in vec2 v_uv;

out vec4 fragColor;

uniform vec3 u_cameraPosition;
uniform vec3 u_sunDirection;
uniform vec3 u_sunColour;
uniform float u_turbidity;
/** Mean water level under the boat, for the horizon split in the sky lookup. */
uniform float u_waterLevel;
/** Speed through the water, m/s. A boat lying still throws no bow wave. */
uniform float u_boatSpeed;

const float MATERIAL_TOPSIDES = 0.0;
const float MATERIAL_DECK = 1.0;
const float MATERIAL_SPAR = 2.0;
const float MATERIAL_SAIL = 3.0;
const float MATERIAL_BOTTOM = 4.0;
const float MATERIAL_WIRE = 5.0;
const float MATERIAL_WINDOW = 6.0;

struct Surface {
    vec3 albedo;
    float roughness;
    /** Reflectance at normal incidence. 0.04 is the dielectric default. */
    float f0;
    /** How much light passes through: sailcloth is thin, gelcoat is not. */
    float translucency;
};

/** GGX normal distribution. */
float distributionGGX(float nDotH, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float d = nDotH * nDotH * (a2 - 1.0) + 1.0;
    return a2 / max(3.14159265 * d * d, 1e-7);
}

/** Smith height-correlated visibility, already divided by the 4·NL·NV denominator. */
float visibilitySmith(float nDotV, float nDotL, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float v = nDotL * sqrt(nDotV * nDotV * (1.0 - a2) + a2);
    float l = nDotV * sqrt(nDotL * nDotL * (1.0 - a2) + a2);
    return 0.5 / max(v + l, 1e-7);
}

float fresnelSchlick(float cosTheta, float f0) {
    float f = pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
    return f0 + (1.0 - f0) * f;
}

/**
 * Sky radiance arriving from a direction, with the sea substituted below the
 * horizon.
 *
 * Half of what a boat sees is not sky: it is the water it is floating on, which is
 * dark and blue-green and lights the underside of every overhang. Sampling the sky
 * model below the horizon returns nonsense, and using it anyway is why so many
 * boats look like they are floating in a void.
 */
vec3 environment(vec3 direction, vec3 sunDirection) {
    if (direction.y > 0.0) {
        return skyRadiance(normalize(direction), sunDirection, u_turbidity);
    }
    // Water: the sky's own colour, dimmed and pushed green, which is what a sea
    // surface bounces upward.
    vec3 sky = skyRadiance(normalize(vec3(direction.x, 0.06, direction.z)),
                           sunDirection, u_turbidity);
    vec3 sea = sky * vec3(0.10, 0.17, 0.19);
    // Grazing angles see more sky bouncing off the surface than water underneath.
    float grazing = pow(1.0 - clamp(-direction.y, 0.0, 1.0), 4.0);
    return mix(sea, sky * 0.55, grazing);
}

/**
 * Split-sum image-based lighting against the analytic sky, with one sample.
 *
 * A proper prefiltered environment is a texture this renderer does not have and
 * does not need: the sky is an analytic function, so a rough surface can be
 * approximated by pulling the sample direction toward the normal and letting the
 * sky's own smoothness do the blurring. It is not correct, and at these roughnesses
 * it is very close.
 */
vec3 specularEnvironment(vec3 normal, vec3 view, float roughness, float f0) {
    vec3 reflection = reflect(-view, normal);
    vec3 blurred = normalize(mix(reflection, normal, roughness * roughness * 0.85));
    float nDotV = max(dot(normal, view), 1e-4);
    // Split-sum's second half, as the usual analytic fit rather than a LUT.
    float fresnel = fresnelSchlick(nDotV, f0);
    float attenuate = 1.0 / (1.0 + roughness * 2.2);
    return environment(blurred, u_sunDirection) * fresnel * attenuate;
}

/**
 * How wet a point on the hull is, and how much foam is being thrown at it.
 *
 * <p>This is the single thing that stops a boat looking pasted onto the sea. A hull
 * under way is dark and mirror-wet for a hand's breadth above the waterline where
 * the water keeps running back down it, and it carries a broken white collar where
 * the bow wave leaves the stem and where the quarter wave rejoins. Without them the
 * topsides are uniformly dry to the waterline and then simply stop, which is what a
 * model on a shelf looks like.
 *
 * @return x how wet, y how much foam
 */
vec2 waterline(float localHeight, vec2 uv) {
    // Everything is measured from the boat's own waterline, so it stays welded to
    // the hull as she heels rather than sliding up the topsides.
    float wet = 1.0 - smoothstep(0.0, 0.42, localHeight);

    // Foam sits in a band around the waterline, torn up by noise so it is a broken
    // collar rather than a painted stripe.
    float band = (1.0 - smoothstep(0.02, 0.30, abs(localHeight - 0.06)));
    float torn = worley(vec2(uv.x * 1.6, uv.y * 5.0) + vec2(0.0, u_boatSpeed * 0.05));
    float broken = smoothstep(0.15, 0.75, torn * 0.5 + 0.5);

    // Heaviest at the bow, where the wave is made, and again at the quarter. uv.x
    // runs aft from the stem, so the two ends of the hull are the two ends of it.
    float lengthwise = 0.28 + 0.72 * max(
            1.0 - smoothstep(0.0, 4.2, uv.x),
            smoothstep(8.0, 11.5, uv.x) * 0.75);
    // And only when she is moving: a boat sitting still has no bow wave at all.
    float driven = smoothstep(0.6, 3.4, u_boatSpeed);

    return vec2(wet, band * broken * lengthwise * driven);
}

/** Per-material surface properties. */
Surface describe(float material, float localHeight, vec2 uv, vec3 normal) {
    Surface s;
    s.translucency = 0.0;
    s.f0 = 0.04;

    if (material < 0.5) {
        // Topsides: gelcoat over the boot stripe, antifouling below it. The boot
        // stripe is the line that tells the eye where the water is, and a hull
        // without one reads as a bathtub toy.
        float boot = smoothstep(0.02, 0.10, localHeight);
        vec3 bottomPaint = vec3(0.055, 0.075, 0.095);
        vec3 topsideWhite = vec3(0.80, 0.82, 0.84);
        s.albedo = mix(bottomPaint, topsideWhite, boot);
        // A narrow dark stripe at the waterline, above the antifouling.
        float stripe = smoothstep(0.10, 0.13, localHeight) * (1.0 - smoothstep(0.20, 0.23, localHeight));
        s.albedo = mix(s.albedo, vec3(0.06, 0.10, 0.20), stripe);
        // Gelcoat is glossy, and it is the gloss that does the work.
        s.roughness = mix(0.42, 0.16, boot);
        s.f0 = 0.05;

        vec2 water = waterline(localHeight, uv);
        // Wet gelcoat is darker and very much smoother - the darkening is the water
        // film killing the diffuse bounce, the smoothing is why a wet hull throws a
        // hard reflection of the sky and a dry one does not.
        s.albedo *= mix(1.0, 0.62, water.x);
        s.roughness = mix(s.roughness, 0.045, water.x);
        // Foam over the top of it, which is neither.
        s.albedo = mix(s.albedo, vec3(0.94, 0.96, 0.97), water.y);
        s.roughness = mix(s.roughness, 0.85, water.y);
    } else if (material < 1.5) {
        // Deck: pale non-skid, which is what it is on a boat that sails in the sun -
        // a dark deck is unwalkable by noon. Broken up by noise so it is not one flat
        // field, and it needs to be pale for another reason: the deck is the largest
        // single area on the boat from every angle above the sheer, and at a dark
        // albedo it swallows the light and the whole hull reads as a slab.
        float grain = worley(uv * 5.0) * 0.5 + 0.5;
        s.albedo = mix(vec3(0.46, 0.47, 0.49), vec3(0.58, 0.59, 0.60), grain);
        s.roughness = 0.80;
        // Painted deck seams along the centreline crown, a plank width apart.
        float plank = abs(fract(uv.y / 0.16) - 0.5);
        s.albedo *= 1.0 - 0.10 * (1.0 - smoothstep(0.0, 0.09, plank));
    } else if (material < 2.5) {
        // Carbon spar: dark, and glossier than it looks - a mast picks up a hard
        // white line of sky down its length, which is most of how it reads as round.
        s.albedo = vec3(0.045, 0.048, 0.055);
        s.roughness = 0.28;
        s.f0 = 0.055;
    } else if (material < 3.5) {
        // Sailcloth. Panels are seamed, and the seams are what stop a sail looking
        // like a sheet of plastic: they catch light along their length and they tell
        // the eye how big the sail is.
        float seam = abs(fract(uv.x / 0.95) - 0.5);
        float seamLine = 1.0 - smoothstep(0.0, 0.045, seam);
        // Battens, running aft from the leech at four heights.
        float batten = abs(fract(uv.y * 4.5 + 0.25) - 0.5);
        float battenLine = (1.0 - smoothstep(0.0, 0.06, batten)) * smoothstep(0.25, 0.5, uv.x);
        s.albedo = vec3(0.90, 0.895, 0.87);
        s.albedo *= 1.0 - 0.09 * seamLine - 0.05 * battenLine;
        // Laminate sailcloth is matt and slightly sheened, not glossy.
        s.roughness = mix(0.78, 0.62, seamLine);
        // Sails are opaque. A modern laminate passes a little light and glows when
        // the sun is behind it, and that is all: the first version let so much
        // through that the mast and the rigging showed clean through the mainsail,
        // which reads as tracing paper rather than as cloth under load.
        s.translucency = 0.30;
        s.f0 = 0.035;
    } else if (material < 4.5) {
        // Antifouling below the waterline, seen through the water or when she heels.
        s.albedo = vec3(0.050, 0.068, 0.085);
        s.roughness = 0.62;
    } else if (material < 5.5) {
        // Rigging wire: bare metal, so a real conductor rather than a dielectric.
        s.albedo = vec3(0.55, 0.57, 0.60);
        s.roughness = 0.30;
        s.f0 = 0.85;
    } else {
        // Smoked glass: dark, and almost a mirror at a glance.
        s.albedo = vec3(0.020, 0.024, 0.030);
        s.roughness = 0.07;
        s.f0 = 0.09;
    }
    return s;
}

void main() {
    vec3 n = normalize(v_normal);
    vec3 v = normalize(u_cameraPosition - v_worldPosition);
    bool backFacing = dot(n, v) < 0.0;
    // Two-sided: a sail is a membrane and is seen from both faces.
    if (backFacing && v_material > 2.5 && v_material < 3.5) {
        n = -n;
    }

    Surface surface = describe(v_material, v_height, v_uv, n);

    float nDotV = max(dot(n, v), 1e-4);
    float nDotL = max(dot(n, u_sunDirection), 0.0);

    // --- direct sun ---------------------------------------------------------
    vec3 h = normalize(v + u_sunDirection);
    float nDotH = max(dot(n, h), 0.0);
    float vDotH = max(dot(v, h), 0.0);
    float d = distributionGGX(nDotH, surface.roughness);
    float vis = visibilitySmith(nDotV, nDotL, surface.roughness);
    float f = fresnelSchlick(vDotH, surface.f0);
    vec3 specular = u_sunColour * (d * vis * f) * nDotL;

    // Metals have no diffuse term; the f0 is what distinguishes them.
    float diffuseWeight = (1.0 - f) * (1.0 - smoothstep(0.2, 0.6, surface.f0));
    vec3 diffuse = surface.albedo * u_sunColour * nDotL * diffuseWeight * 0.318;

    // --- ambient ------------------------------------------------------------
    // Hemisphere lookup rather than a constant: the sky above and the sea below are
    // very different colours and a boat lit by their average has no vertical shape.
    vec3 skyAbove = environment(normalize(n * 0.35 + vec3(0.0, 1.0, 0.0)), u_sunDirection);
    vec3 alongNormal = environment(n, u_sunDirection);
    vec3 irradiance = mix(alongNormal, skyAbove, 0.45);

    // Cheap ambient occlusion from the geometry itself: anything low and facing
    // down is inside the boat somewhere, and the sky cannot reach it.
    float openness = clamp(0.5 + 0.5 * n.y, 0.0, 1.0);
    float belowDeck = smoothstep(-0.4, 1.6, v_height);
    float occlusion = mix(0.35, 1.0, openness * mix(0.55, 1.0, belowDeck));

    vec3 ambient = surface.albedo * irradiance * occlusion * diffuseWeight;
    vec3 ambientSpecular =
            specularEnvironment(n, v, surface.roughness, surface.f0) * occlusion;

    vec3 colour = diffuse + specular + ambient + ambientSpecular;

    // --- sailcloth ----------------------------------------------------------
    // Light behind a sail comes through it, and that is most of why a sail reads as
    // fabric rather than as a painted board. It is also why a backlit sail is the
    // brightest thing in a photograph of a boat.
    if (surface.translucency > 0.0) {
        // Tight lobe: a sail glows where the sun is nearly behind it and nowhere
        // else. A broad one lights the whole sail from the back at once, which is
        // what made it look like paper.
        float through = max(dot(-n, u_sunDirection), 0.0);
        vec3 transmitted = u_sunColour * pow(through, 3.5) * surface.translucency;
        // Warmed on the way through, as light always is through cloth.
        colour += surface.albedo * transmitted * vec3(1.08, 1.0, 0.90);
        // A trace of the sky behind it, so the leech does not go black at dusk.
        colour += surface.albedo * environment(-n, u_sunDirection) * 0.06;
    }

    fragColor = vec4(colour, 1.0);
}
