// Shadow map depth pass, vertex stage.
//
// The boat is drawn once more from the sun's point of view, through an orthographic
// camera fitted around it, and all that is kept is how far each fragment is along
// the light. Anything the main pass finds to be further along the light than what is
// stored here is behind something, and is in shadow.
//
// Only position matters, so the normal, material and surface coordinate attributes
// are not declared - a shadow pass that reads them would move four times the vertex
// data for a number it never uses.

in vec3 a_position;

uniform mat4 u_lightViewProjection;
uniform mat4 u_model;

out float v_depth;

void main() {
    vec4 clip = u_lightViewProjection * u_model * vec4(a_position, 1.0);
    gl_Position = clip;
    // The projection is orthographic, so w is 1 and this is already linear in world
    // units along the light. That is worth having: a linear map takes a constant
    // depth bias, where a perspective one needs a bias that varies with distance and
    // gets acne at one end or peter-panning at the other.
    v_depth = clip.z * 0.5 + 0.5;
}
