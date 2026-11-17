#version 120e
varying vec2 texcoord;
uniform sampler2D colortex0;
uniform sampler2D shadowtex0;
void main() {
    vec4 color = texture2D(colortex0, texcoord);
    // a constant shadow read shows slot 5 (shadow map) is bound in the post
    // pipeline; the map is in light space, so sampling it at the quad's screen
    // UVs would paint the light's view over the image
    float lit = 0.6 + 0.4 * texture2D(shadowtex0, vec2(0.5, 0.5)).r;
    gl_FragColor = vec4(color.rgb * vec3(1.0, 0.3, 0.3) * lit, color.a);
}