#version 120e
// geometry fixture: runs as gbuffers_terrain on chimera's fixed terrain vertex
// (outputs: color->0, texcoord->1, lightSpacePos->5); declares pack consts and
// DRAWBUFFERS, samples the block atlas (texture -> registry slot 0) and the
// shadow map (shadowtex0 -> registry slot 5), and applies a horizontal warm/cool
// gradient so the geometry stage is plain to see on screen
//
// the atlas is sampled at snapped texel centres, as the host chimera_terrain
// fragment samples it: the fixed vertex hands out full-atlas normalised UVs, and
// a plain texture2D() would blend across texel centres and smear mips
const int colortex0Format = RGBA16F;
const int shadowMapResolution = 2048;
/* DRAWBUFFERS:0 */
varying vec4 color;
varying vec2 texcoord;
uniform sampler2D texture;
uniform sampler2D shadowtex0;

vec4 sampleAtlas(sampler2D atlas, vec2 uv) {
    vec2 pixelSize = vec2(1.0) / vec2(textureSize(atlas, 0));
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 screenTexelSize = sqrt(du * du + dv * dv);
    vec2 uvInTexels = uv / pixelSize;
    vec2 texelCenter = round(uvInTexels) - 0.5;
    vec2 offsetFromCenter = uvInTexels - texelCenter;
    offsetFromCenter = (offsetFromCenter - 0.5) * pixelSize / screenTexelSize + 0.5;
    offsetFromCenter = clamp(offsetFromCenter, 0.0, 1.0);
    vec2 snappedUv = (texelCenter + offsetFromCenter) * pixelSize;
    return textureGrad(atlas, snappedUv, du, dv);
}

void main() {
    vec4 albedo = sampleAtlas(texture, texcoord);
    if (albedo.a < 0.1) discard; // atlas cutout (leaves etc.), host-side behavior
    float shadow = texture2D(shadowtex0, vec2(0.5, 0.5)).r; // machinery proof only, not correct shadow math
    // Screen-space ramp: gl_FragCoord.x sweeps 0..2560, so the left half of
    // the screen shifts cool (G up, R down), the right half warm (R up).
    // The floor applies to the LIGHT term, not the albedo: clamping albedo
    // flattens dark textures (obsidian renders as a uniform gray blob — its
    // texels are all below the clamp). Flooring light keeps texture detail
    // at any brightness while the demo stays readable in caves/night.
    float gx = gl_FragCoord.x / 2560.0;
    vec3 light = max(color.rgb * vec3(0.5 + 0.5 * gx, 1.0 - 0.5 * gx, 0.5), vec3(0.30));
    vec3 base = albedo.rgb * light;
    base *= 0.6 + 0.4 * shadow;
    gl_FragColor = vec4(base, 1.0);
}