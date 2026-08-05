// Worley (cellular) noise, used to break foam into cells instead of leaving it a
// flat white wash. Real foam is a raft of bubbles with visible structure, and a
// uniform white crest is one of the two or three things that most reliably makes
// rendered water look fake.

vec2 worleyHash(vec2 cell) {
    vec2 p = vec2(dot(cell, vec2(127.1, 311.7)), dot(cell, vec2(269.5, 183.3)));
    return fract(sin(p) * 43758.5453);
}

// Returns the distance to the nearest feature point, roughly in [0, 1].
float worley(vec2 position) {
    vec2 cell = floor(position);
    vec2 local = fract(position);
    float best = 8.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 offset = vec2(float(x), float(y));
            vec2 feature = offset + worleyHash(cell + offset) - local;
            best = min(best, dot(feature, feature));
        }
    }
    return sqrt(best);
}

// Two octaves: large rafts with finer bubble structure inside them.
float foamPattern(vec2 position) {
    float a = 1.0 - worley(position);
    float b = 1.0 - worley(position * 2.7 + 13.7);
    return clamp(a * 0.65 + b * 0.35, 0.0, 1.0);
}
