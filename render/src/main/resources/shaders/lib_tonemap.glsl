// ACES filmic tone mapping, Narkowicz's curve fit.
//
// Chosen over Reinhard because a sea surface is mostly specular highlight against
// a bright sky: Reinhard desaturates the sun glitter to grey, while the ACES
// shoulder keeps the roll-off coloured, which is what makes water read as wet
// rather than as metal.
vec3 acesTonemap(vec3 colour) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return clamp((colour * (a * colour + b)) / (colour * (c * colour + d) + e), 0.0, 1.0);
}

vec3 linearToSrgb(vec3 c) {
    vec3 lo = c * 12.92;
    vec3 hi = 1.055 * pow(max(c, vec3(1e-5)), vec3(1.0 / 2.4)) - 0.055;
    return mix(lo, hi, step(vec3(0.0031308), c));
}
