// Isolates the part of the image above the bloom knee. On water this is almost
// entirely sun glitter, which is the point: the bloom exists to make the specular
// track bleed, not to fog the whole frame.
in vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_source;
uniform float u_threshold;
uniform float u_softKnee;
uniform float u_exposure;

void main() {
    // Exposure is applied here, before the threshold. The scene buffer holds
    // absolute radiance in kcd/m^2, where a plain daylight sky is already around
    // 9, so thresholding the raw values would treat the entire sky as a highlight
    // and bloom the whole frame into fog.
    vec3 c = texture(u_source, v_uv).rgb * u_exposure;
    float brightness = max(c.r, max(c.g, c.b));
    float knee = u_threshold * u_softKnee;
    float soft = clamp(brightness - u_threshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee + 1e-4);
    float weight = max(soft, brightness - u_threshold) / max(brightness, 1e-4);
    fragColor = vec4(c * weight, 1.0);
}
