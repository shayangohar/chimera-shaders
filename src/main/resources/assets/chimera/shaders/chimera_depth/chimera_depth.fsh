#version 460
layout(binding = 0) uniform sampler2D SceneDepth;
layout(location = 0) in vec2 inUv;
layout(location = 0) out vec4 outDepth;

void main() {
    // world depth is forward [0,1]; copy it as is, pack projection uniforms map the clip range
    float depth = texture(SceneDepth, inUv).r;
    outDepth = vec4(depth, 0.0, 0.0, 1.0);
}
