#pragma include "lib_sky.glsl"

in vec3 v_viewRay;
out vec4 fragColor;

uniform vec3 u_sunDirection;
uniform vec3 u_sunColour;
uniform float u_turbidity;

void main() {
    vec3 dir = normalize(v_viewRay);
    vec3 colour = skyRadiance(dir, u_sunDirection, u_turbidity);
    colour += sunDisc(dir, u_sunDirection, u_sunColour);
    fragColor = vec4(colour, 1.0);
}
