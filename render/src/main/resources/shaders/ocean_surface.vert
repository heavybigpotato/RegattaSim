// Screen-space projected grid.
//
// The mesh is a plain lattice in [0,1]^2 that never changes. Each vertex is
// mapped to an NDC point inside the band of screen the CPU found water in, then
// un-projected and intersected with the water plane. Vertices therefore land
// evenly across the screen rather than across the world: dense in the
// foreground, sparse at the horizon, with no LOD rings and so no seams to stitch.
//
// Rays that miss the plane - anything at or above the horizon - are planted at a
// fixed distance instead of running to infinity, so the mesh closes cleanly at
// the skyline rather than folding through the camera.
//
// Displacement is summed over the cascades, each tiling at its own patch size.

#pragma include "lib_ocean_common.glsl"

in vec2 a_position;   // lattice coordinate in [0,1]^2

uniform mat4 u_viewProjection;
uniform mat4 u_inverseViewProjection;
uniform vec2 u_ndcMin;
uniform vec2 u_ndcMax;
uniform vec3 u_cameraPosition;
uniform float u_horizonDistance;

uniform sampler2D u_displacement0;
uniform sampler2D u_displacement1;
uniform sampler2D u_displacement2;
uniform vec3 u_patchSizes;
uniform int u_cascadeCount;
uniform float u_displacementFadeStart;
uniform float u_displacementFadeEnd;

out vec3 v_worldPosition;
out vec3 v_undisplacedPosition;
out float v_viewDistance;

void main() {
    vec2 ndc = mix(u_ndcMin, u_ndcMax, a_position);

    vec4 nearHomogeneous = u_inverseViewProjection * vec4(ndc, -1.0, 1.0);
    vec4 farHomogeneous = u_inverseViewProjection * vec4(ndc, 1.0, 1.0);
    vec3 nearPoint = nearHomogeneous.xyz / nearHomogeneous.w;
    vec3 direction = farHomogeneous.xyz / farHomogeneous.w - nearPoint;

    // Intersect with the mean water plane. A ray parallel to it, or one heading
    // away from it, is clamped to the horizon distance.
    float denominator = direction.y;
    float t = abs(denominator) < 1e-6 ? 1.0e9 : (-nearPoint.y / denominator);
    if (t < 0.0) {
        t = 1.0e9;
    }
    vec3 base = nearPoint + direction * t;

    // Cap the reach so the far edge of the mesh is a definite distance rather
    // than a numerically unstable point at infinity.
    vec3 offset = base - u_cameraPosition;
    float horizontal = length(offset.xz);
    if (horizontal > u_horizonDistance) {
        offset.xz *= u_horizonDistance / horizontal;
        base = vec3(u_cameraPosition.x + offset.x, 0.0, u_cameraPosition.z + offset.z);
    }
    base.y = 0.0;

    float distanceToCamera = length(base - u_cameraPosition);

    // Beyond a few kilometres the displacement is far below a pixel and sampling
    // it only produces shimmer, so it is faded out. The surface goes flat at the
    // horizon, which is what the horizon looks like anyway.
    float fade = 1.0 - smoothstep(u_displacementFadeStart, u_displacementFadeEnd,
                                  distanceToCamera);

    vec3 displacement = vec3(0.0);
    if (fade > 0.0) {
        displacement += texture(u_displacement0, base.xz / u_patchSizes.x).xyz;
        if (u_cascadeCount > 1) {
            displacement += texture(u_displacement1, base.xz / u_patchSizes.y).xyz;
        }
        if (u_cascadeCount > 2) {
            displacement += texture(u_displacement2, base.xz / u_patchSizes.z).xyz;
        }
        displacement *= fade;
    }

    vec3 world = base + displacement;

    v_undisplacedPosition = base;
    v_worldPosition = world;
    v_viewDistance = length(world - u_cameraPosition);
    gl_Position = u_viewProjection * vec4(world, 1.0);
}
