// Boat shading.
//
// Lit by exactly the same sun and sky the water is, through the same analytic
// model, so the boat cannot end up looking pasted onto the scene - which is the
// usual giveaway when a hull is lit by its own private light rig.

#pragma include "lib_sky.glsl"

in vec3 v_worldPosition;
in vec3 v_normal;
in float v_material;
in float v_height;

out vec4 fragColor;

uniform vec3 u_cameraPosition;
uniform vec3 u_sunDirection;
uniform vec3 u_sunColour;
uniform float u_turbidity;

void main() {
    vec3 n = normalize(v_normal);
    vec3 v = normalize(u_cameraPosition - v_worldPosition);
    // Two-sided: a sail is a membrane and is seen from both faces.
    if (dot(n, v) < 0.0 && v_material > 2.5) {
        n = -n;
    }

    vec3 albedo;
    float roughness;
    if (v_material < 0.5) {
        // Topsides: white above the boot stripe, dark below it.
        albedo = v_height < 0.06 ? vec3(0.06, 0.09, 0.11) : vec3(0.86, 0.88, 0.89);
        roughness = 0.25;
    } else if (v_material < 1.5) {
        albedo = vec3(0.22, 0.24, 0.26);   // non-skid deck
        roughness = 0.7;
    } else if (v_material < 2.5) {
        albedo = vec3(0.14, 0.15, 0.16);   // carbon spar
        roughness = 0.35;
    } else {
        albedo = vec3(0.93, 0.93, 0.90);   // sailcloth
        roughness = 0.85;
    }

    // Sky as the ambient term, sampled up and along the normal, so the boat picks
    // up the same colour cast the sea does.
    vec3 skyAbove = skyRadiance(vec3(0.0, 1.0, 0.0), u_sunDirection, u_turbidity);
    vec3 skyAlong = skyRadiance(normalize(n + vec3(0.0, 0.35, 0.0)), u_sunDirection, u_turbidity);
    vec3 ambient = mix(skyAbove, skyAlong, 0.6) * (0.35 + 0.35 * (n.y * 0.5 + 0.5));

    float ndl = max(dot(n, u_sunDirection), 0.0);
    vec3 direct = u_sunColour * ndl * 2.2;

    // Sailcloth is thin: light behind it comes through, which is most of why a
    // sail reads as fabric rather than as a painted board.
    if (v_material > 2.5) {
        float through = max(dot(-n, u_sunDirection), 0.0);
        direct += u_sunColour * pow(through, 1.5) * 1.1;
    }

    vec3 h = normalize(v + u_sunDirection);
    float gloss = pow(max(dot(n, h), 0.0), mix(120.0, 4.0, roughness));
    vec3 specular = u_sunColour * gloss * (1.0 - roughness) * 1.5;

    fragColor = vec4(albedo * (ambient + direct) + specular, 1.0);
}
