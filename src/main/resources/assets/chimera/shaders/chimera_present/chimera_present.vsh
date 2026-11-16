#version 460
// same fullscreen quad as chimera_composite.vsh; the host viewport sets orientation

layout(location = 0) out vec2 outUv;

const vec2 CHIMERA_QUAD[6] = vec2[](vec2(0.0, 0.0), vec2(1.0, 1.0), vec2(1.0, 0.0),
                                    vec2(0.0, 0.0), vec2(0.0, 1.0), vec2(1.0, 1.0));

void main() {
    vec2 chimeraUv = CHIMERA_QUAD[gl_VertexIndex];
    outUv = chimeraUv;
    gl_Position = vec4(chimeraUv * vec2(2.0, -2.0) + vec2(-1.0, 1.0), 0.0, 1.0);
}
