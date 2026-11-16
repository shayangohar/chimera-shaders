#version 460

layout(binding = 0) uniform sampler2D SceneColor;
layout(binding = 1) uniform sampler2D Coverage;

layout(location = 0) in vec2 inUv;
layout(location = 0) out vec4 outColor;

void main() {
    vec4 scene = texture(SceneColor, inUv);
    float packCoverage = texture(Coverage, inUv).r;

    // the HDR sky keeps useful RGB under a low alpha, so alpha can't be the copy factor
    // uncovered pixels copy fully; over pack geometry, low-alpha background is ignored and the
    // pack target kept, while opaque host pixels still overlay through the source-alpha blend
    if (packCoverage == -1.0) {
        scene.a = 1.0;
    } else if (scene.a < 0.5) {
        scene.a = 0.0;
    }
    outColor = scene;
}
