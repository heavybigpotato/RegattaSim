// Builds the shading map: surface slope, the folding Jacobian, and accumulated
// foam.
//
// The Jacobian of the horizontal displacement map measures how much a patch of
// water has been compressed. Below 1 the surface is folding; at or below 0 it has
// turned inside out, which is physically a breaking crest. That is the honest
// source of whitecaps - not a height threshold, which puts foam on the top of
// every swell whether it is breaking or not.
//
//   J = (1 + L*dDx/dx)(1 + L*dDz/dz) - (L*dDx/dz)^2
//
// Foam is stored against the *undisplaced* grid coordinate, which is the
// Lagrangian label of the water particle. A particle keeps its label as it orbits,
// so foam written here rides with the water automatically when the surface is
// displaced, with no advection pass at all.

in vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_spatialA;      // Dx, Dz, h, dDx/dz
uniform sampler2D u_spatialB;      // dh/dx, dh/dz, dDx/dx, dDz/dz
uniform sampler2D u_previousFoam;  // previous frame's output of this pass
uniform float u_choppiness;
uniform float u_foamDecay;         // exp(-dt / tau)
uniform float u_foamThreshold;     // Jacobian below which foam is injected
uniform float u_foamInjection;     // gain * dt

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    vec4 a = texelFetch(u_spatialA, texel, 0);
    vec4 b = texelFetch(u_spatialB, texel, 0);

    float dhdx = b.r;
    float dhdz = b.g;
    float dDxdx = b.b;
    float dDzdz = b.a;
    float dDxdz = a.a;

    float L = u_choppiness;
    float jacobian = (1.0 + L * dDxdx) * (1.0 + L * dDzdz) - (L * dDxdz) * (L * dDxdz);

    // Foam is a rate balance, not a per-frame addition:
    //
    //     d(foam)/dt = deposition - foam / tau
    //
    // so the deposited amount has to be scaled by the frame time. Without that the
    // equilibrium coverage depends on the frame rate - a scene running at 30 fps
    // accumulates thirty times the foam of one running at 1 fps, and a storm turns
    // uniformly white while the same sea stepped coarsely stays glassy.
    float previous = texelFetch(u_previousFoam, texel, 0).a;
    float deposition = max(0.0, u_foamThreshold - jacobian) * u_foamInjection;
    float foam = min(1.0, previous * u_foamDecay + deposition);

    fragColor = vec4(dhdx, dhdz, jacobian, foam);
}
