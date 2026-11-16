#version 460
// shadow depth with an alpha cutout so foliage doesn't cast solid shadows; 0.5 matches
// TerrainRenderType.CUTOUT_MIPPED, and solid block texels have alpha 1

layout(binding = 1) uniform sampler2D Sampler0;

layout(location = 0) in vec2 inTexCoord;
layout(location = 0) out vec4 outColor;

void main() {
    vec4 texColor = texture(Sampler0, inTexCoord);
    if (texColor.a < 0.5) {
        discard;
    }
    outColor = vec4(gl_FragCoord.z, 0.0, 0.0, 1.0);
}
