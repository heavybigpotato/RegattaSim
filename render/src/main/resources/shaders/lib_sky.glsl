// Analytic daylight sky, after Preetham, Shirley and Smits (1999).
//
// The design brief asks for Hosek-Wilkie with Preetham as the fallback. Preetham
// is what is implemented, deliberately: Hosek-Wilkie's radiance is driven by a
// large fitted dataset published by its authors, and that dataset has to be
// imported from them rather than reproduced from memory. Preetham's coefficients
// are a small closed-form table in the paper, so they can be written down, read
// back, and checked. PreethamSkyTest in core verifies this model's behaviour
// against physical expectations.
//
//   F(theta, gamma) = (1 + A e^{B/cos theta}) (1 + C e^{D gamma} + E cos^2 gamma)
//
// evaluated for luminance Y and for the chromaticity coordinates x and y, each
// normalised against the value at the zenith.

const float SKY_PI = 3.14159265359;

float perezF(float cosTheta, float gamma, float a, float b, float c, float d, float e) {
    float cosGamma = cos(gamma);
    return (1.0 + a * exp(b / max(cosTheta, 0.01)))
         * (1.0 + c * exp(d * gamma) + e * cosGamma * cosGamma);
}

vec3 xyYToLinearSrgb(float x, float y, float bigY) {
    float safeY = max(y, 1e-4);
    vec3 xyz = vec3(x * bigY / safeY, bigY, (1.0 - x - y) * bigY / safeY);
    // XYZ -> linear sRGB (sRGB primaries, D65). Column-major, as GLSL expects.
    mat3 m = mat3(
        3.2404542, -0.9692660,  0.0556434,
       -1.5371385,  1.8760108, -0.2040259,
       -0.4985314,  0.0415560,  1.0572252);
    return m * xyz;
}

// dir and sunDir must be unit vectors with +y up. Returns linear RGB radiance in
// kcd/m^2; a clear zenith at a 30 degree sun elevation lands around 2.
vec3 preethamSky(vec3 dir, vec3 sunDir, float turbidity) {
    float t = turbidity;

    float cosTheta = max(dir.y, 0.0);
    float thetaSun = acos(clamp(sunDir.y, -1.0, 1.0));
    float cosGamma = clamp(dot(dir, sunDir), -1.0, 1.0);
    float gamma = acos(cosGamma);

    float ay =  0.1787 * t - 1.4630;
    float by = -0.3554 * t + 0.4275;
    float cy = -0.0227 * t + 5.3251;
    float dy =  0.1206 * t - 2.5771;
    float ey = -0.0670 * t + 0.3703;

    float ax = -0.0193 * t - 0.2592;
    float bx = -0.0665 * t + 0.0008;
    float cx = -0.0004 * t + 0.2125;
    float dx = -0.0641 * t - 0.8989;
    float ex = -0.0033 * t + 0.0452;

    float ayy = -0.0167 * t - 0.2608;
    float byy = -0.0950 * t + 0.0092;
    float cyy = -0.0079 * t + 0.2102;
    float dyy = -0.0441 * t - 1.6537;
    float eyy = -0.0109 * t + 0.0529;

    // Zenith luminance, kcd/m^2.
    //
    // Preetham's zenith fit is a daytime fit and goes *negative* below about a 10
    // degree sun elevation - a documented limitation of the model, pinned by
    // PreethamSkyTest in core. Below that limit the value at the limit is carried
    // down and faded exponentially, which keeps twilight ordered and positive.
    // This must stay identical to PreethamSky.usableZenithLuminance.
    const float VALIDITY_ELEVATION_LIMIT = 0.17453293;   // 10 degrees
    const float TWILIGHT_FALLOFF = 17.2;
    float elevation = SKY_PI * 0.5 - thetaSun;
    float thetaLimit = SKY_PI * 0.5 - VALIDITY_ELEVATION_LIMIT;
    float chiAt = (4.0 / 9.0 - t / 120.0) * (SKY_PI - 2.0 * min(thetaSun, thetaLimit));
    float zenithY = (4.0453 * t - 4.9710) * tan(chiAt) - 0.2155 * t + 0.1208;
    if (elevation < VALIDITY_ELEVATION_LIMIT) {
        zenithY *= exp((elevation - VALIDITY_ELEVATION_LIMIT) * TWILIGHT_FALLOFF);
    }

    float ts = thetaSun;
    float ts2 = ts * ts;
    float ts3 = ts2 * ts;
    float t2 = t * t;

    float zenithX =
        ( 0.00166 * ts3 - 0.00375 * ts2 + 0.00209 * ts) * t2 +
        (-0.02903 * ts3 + 0.06377 * ts2 - 0.03202 * ts + 0.00394) * t +
        ( 0.11693 * ts3 - 0.21196 * ts2 + 0.06052 * ts + 0.25886);

    float zenithYc =
        ( 0.00275 * ts3 - 0.00610 * ts2 + 0.00317 * ts) * t2 +
        (-0.04214 * ts3 + 0.08970 * ts2 - 0.04153 * ts + 0.00516) * t +
        ( 0.15346 * ts3 - 0.26756 * ts2 + 0.06670 * ts + 0.26688);

    float fY  = perezF(cosTheta, gamma, ay, by, cy, dy, ey);
    float fY0 = perezF(1.0, thetaSun, ay, by, cy, dy, ey);
    float fx  = perezF(cosTheta, gamma, ax, bx, cx, dx, ex);
    float fx0 = perezF(1.0, thetaSun, ax, bx, cx, dx, ex);
    float fy  = perezF(cosTheta, gamma, ayy, byy, cyy, dyy, eyy);
    float fy0 = perezF(1.0, thetaSun, ayy, byy, cyy, dyy, eyy);

    float bigY = max(0.0, zenithY * fY / fY0);
    float chromaX = zenithX * fx / fx0;
    float chromaY = zenithYc * fy / fy0;

    return max(vec3(0.0), xyYToLinearSrgb(chromaX, chromaY, bigY));
}

// Sun disc plus forward-scattered glow, added on top of the sky radiance.
vec3 sunDisc(vec3 dir, vec3 sunDir, vec3 sunColour) {
    float cosAngle = dot(dir, sunDir);
    // The sun subtends about 0.53 degrees; a touch of softening keeps it from
    // aliasing into a square when the camera turns.
    float disc = smoothstep(0.99987, 0.99995, cosAngle);
    float glow = pow(max(cosAngle, 0.0), 900.0) * 0.35;
    return sunColour * (disc * 60.0 + glow);
}

// Below the horizon the Preetham model is undefined. Rather than let it go
// negative, fade into a darkened version of the horizon band, which is what the
// sea reflects when a wave face tips the reflection vector downward.
vec3 skyRadiance(vec3 dir, vec3 sunDir, float turbidity) {
    vec3 above = preethamSky(vec3(dir.x, max(dir.y, 0.0), dir.z), sunDir, turbidity);
    if (dir.y >= 0.0) {
        return above;
    }
    vec3 horizon = preethamSky(normalize(vec3(dir.x, 0.0001, dir.z)), sunDir, turbidity);
    return mix(horizon, horizon * 0.25, clamp(-dir.y * 3.0, 0.0, 1.0));
}
