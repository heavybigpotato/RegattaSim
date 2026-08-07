// Boat vertex stage.
//
// The hull is a static mesh in boat-local coordinates - bow along +X, port along
// +Z, up along +Y - and the model matrix carries it onto the water: position from
// the sailing model, heading, and the pitch and roll read off the wave surface.
// Nothing here knows about waves; the boat's attitude was already resolved by the
// physics, which is the same physics the server runs.

in vec3 a_position;
in vec3 a_normal;
in float a_material;      // 0 topsides, 1 deck, 2 spar, 3 sail, 4 bottom, 5 wire, 6 window
in vec2 a_uv;

uniform mat4 u_viewProjection;
uniform mat4 u_model;
uniform mat3 u_normalMatrix;

out vec3 v_worldPosition;
out vec3 v_normal;
out float v_material;
out float v_height;
out vec2 v_uv;

void main() {
    vec4 world = u_model * vec4(a_position, 1.0);
    v_worldPosition = world.xyz;
    v_normal = normalize(u_normalMatrix * a_normal);
    v_material = a_material;
    // Height above the boat's own designed waterline, used to place the boot stripe
    // and the antifouling. Local, not world: the stripe has to stay welded to the
    // hull as she heels rather than sliding up the topsides.
    v_height = a_position.y;
    // Surface coordinates in metres, so seams and non-skid keep their physical size
    // wherever they land instead of stretching with the surface.
    v_uv = a_uv;
    gl_Position = u_viewProjection * world;
}
