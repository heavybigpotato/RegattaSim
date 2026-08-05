// Advances the initial spectrum to time t and builds the four packed complex
// signals the inverse FFT consumes.
//
// h~(k,t) = h0(k) e^{i w t} + conj(h0(-k)) e^{-i w t}
//
// Two Hermitian spectra are packed into one complex transform as A + i*B: the
// inverse FFT then returns A in its real part and B in its imaginary part, which
// halves the number of transforms. Every signal below is Hermitian, because
// multiplying a Hermitian spectrum by i*k (odd in k, and i flips conjugation)
// leaves it Hermitian.
//
//   pass 0 -> (Dx + i*Dz,        h + i*dDx/dz)
//   pass 1 -> (dh/dx + i*dh/dz,  dDx/dx + i*dDz/dz)

#pragma include "lib_complex.glsl"

in vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_h0;        // h0.re, h0.im, conj(h0(-k)).re, conj(h0(-k)).im
uniform sampler2D u_waveData;  // kx, kz, |k|, omega
uniform float u_time;
uniform int u_outputSet;       // 0 or 1

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    vec4 h0 = texelFetch(u_h0, texel, 0);
    vec4 wave = texelFetch(u_waveData, texel, 0);

    float kx = wave.x;
    float kz = wave.y;
    float k = wave.z;
    float omega = wave.w;

    float phase = omega * u_time;
    vec2 rot = vec2(cos(phase), sin(phase));

    // h0 * e^{i w t} + conj(h0(-k)) * e^{-i w t}
    vec2 hTilde = cmul(h0.xy, rot) + cmul(h0.zw, vec2(rot.x, -rot.y));

    if (k < 1e-8) {
        fragColor = vec4(0.0);
        return;
    }

    float invK = 1.0 / k;
    float nx = kx * invK;
    float nz = kz * invK;

    if (u_outputSet == 0) {
        // Horizontal displacement: D = -i * (k/|k|) * h~
        vec2 dx = cmulnegi(hTilde) * nx;
        vec2 dz = cmulnegi(hTilde) * nz;
        // dDx/dz = i*kz * Dx = (kx*kz/|k|) * h~
        vec2 jxz = hTilde * (kx * kz * invK);
        fragColor = vec4(dx + cmuli(dz), hTilde + cmuli(jxz));
    } else {
        // Surface slopes: dh/dx = i*kx*h~, dh/dz = i*kz*h~
        vec2 dhdx = cmuli(hTilde) * kx;
        vec2 dhdz = cmuli(hTilde) * kz;
        // Displacement gradients: dDx/dx = (kx^2/|k|) h~, dDz/dz = (kz^2/|k|) h~
        vec2 ddxdx = hTilde * (kx * kx * invK);
        vec2 ddzdz = hTilde * (kz * kz * invK);
        fragColor = vec4(dhdx + cmuli(dhdz), ddxdx + cmuli(ddzdz));
    }
}
