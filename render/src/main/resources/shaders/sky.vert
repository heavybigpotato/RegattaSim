// Fullscreen sky pass: reconstructs a world-space view ray per pixel from the
// inverse view-projection, so no sky geometry is needed at all.
in vec2 a_position;
in vec2 a_texCoord0;

uniform mat4 u_inverseViewProjection;

out vec3 v_viewRay;

void main() {
    vec4 near = u_inverseViewProjection * vec4(a_position, -1.0, 1.0);
    vec4 far = u_inverseViewProjection * vec4(a_position, 1.0, 1.0);
    v_viewRay = far.xyz / far.w - near.xyz / near.w;
    gl_Position = vec4(a_position, 1.0, 1.0);
}
