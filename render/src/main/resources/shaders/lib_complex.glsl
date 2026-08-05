// Complex arithmetic on vec2 (x = real, y = imaginary).

vec2 cmul(vec2 a, vec2 b) {
    return vec2(a.x * b.x - a.y * b.y, a.x * b.y + a.y * b.x);
}

// Multiplication by i: rotates a quarter turn counter-clockwise.
vec2 cmuli(vec2 a) {
    return vec2(-a.y, a.x);
}

// Multiplication by -i.
vec2 cmulnegi(vec2 a) {
    return vec2(a.y, -a.x);
}

vec2 cconj(vec2 a) {
    return vec2(a.x, -a.y);
}
