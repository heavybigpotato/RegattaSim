// Separable nine-tap Gaussian, run once per axis at reduced resolution.
in vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_source;
uniform vec2 u_texelStep;   // (1/width, 0) or (0, 1/height)

void main() {
    const float w0 = 0.227027;
    const float w1 = 0.194594;
    const float w2 = 0.121621;
    const float w3 = 0.054054;
    const float w4 = 0.016216;

    vec3 sum = texture(u_source, v_uv).rgb * w0;
    sum += texture(u_source, v_uv + u_texelStep * 1.0).rgb * w1;
    sum += texture(u_source, v_uv - u_texelStep * 1.0).rgb * w1;
    sum += texture(u_source, v_uv + u_texelStep * 2.0).rgb * w2;
    sum += texture(u_source, v_uv - u_texelStep * 2.0).rgb * w2;
    sum += texture(u_source, v_uv + u_texelStep * 3.0).rgb * w3;
    sum += texture(u_source, v_uv - u_texelStep * 3.0).rgb * w3;
    sum += texture(u_source, v_uv + u_texelStep * 4.0).rgb * w4;
    sum += texture(u_source, v_uv - u_texelStep * 4.0).rgb * w4;
    fragColor = vec4(sum, 1.0);
}
