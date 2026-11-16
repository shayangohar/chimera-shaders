#version 460
// world resolve with the Color Space setting, as Iris's ColorSpace converter after final: flips the
// GL-row-ordered world into the host-ordered output like chimera_composite, then re-encodes sRGB for a
// wide-gamut display
// CHIMERA_COLOR_SPACE is defined at pipeline build: 1 DCI-P3, 2 Display P3, 3 Rec. 2020, 4 Adobe RGB

layout(binding = 0) uniform sampler2D SceneColor;

layout(location = 0) in vec2 inUv;
layout(location = 0) out vec4 outColor;

// linear sRGB to the target's linear RGB: sRGB-to-XYZ, then the target's XYZ-to-RGB (IEC 61966-2-1,
// SMPTE EG 432-1, ITU-R BT.2020, Adobe RGB (1998)); DCI-P3 adds Iris's Bradford adaptation to the DCI
// white; listed row by row, so `rgb * M` is M times rgb
const mat3 SRGB_TO_DISPLAY_P3 = mat3(
        0.82255957, 0.17752689, 0.00000007,
        0.03319957, 0.96678117, -0.00000001,
        0.01708767, 0.07240554, 0.91042465);
const mat3 SRGB_TO_DCI_P3 = mat3(
        0.84354944, 0.19796110, 0.01792499,
        0.05343759, 0.94516647, 0.00429388,
        0.02445977, 0.07239021, 1.04511510);
const mat3 SRGB_TO_REC2020 = mat3(
        0.62750367, 0.32927546, 0.04330269,
        0.06910839, 0.91951921, 0.01135959,
        0.01639405, 0.08801125, 0.89538038);
const mat3 SRGB_TO_ADOBE_RGB = mat3(
        0.71523932, 0.28486784, 0.00000002,
        0.00000013, 0.99997696, 0.00000000,
        0.00000000, 0.04116097, 0.95860801);

// inverse sRGB transfer function: display values to linear light
vec3 decodeSrgb(vec3 encoded) {
    vec3 low = encoded / 12.92;
    vec3 high = pow((encoded + 0.055) / 1.055, vec3(2.4));
    return max(mix(low, high, step(0.04045, encoded)), 0.0);
}

// transfer function with a linear toe: slope * linear below the knee, the clamped power curve
// scale * linear^exponent - (scale - 1) above it
vec3 encodeWithToe(vec3 linear, float slope, float exponent, float scale, float knee) {
    vec3 curve = clamp(scale * pow(max(linear, 0.0), vec3(exponent)) - (scale - 1.0), 0.0, 1.0);
    return mix(linear * slope, curve, step(knee, linear));
}

// pure power transfer function; negative light from rounding encodes as black
vec3 encodePower(vec3 linear, float gamma) {
    return pow(max(linear, 0.0), vec3(1.0 / gamma));
}

void main() {
    // Iris converts its RGBA8 main target, so clamp as that target would store it; a pack without
    // final resolves a float colortex0 here directly
    vec4 scene = clamp(texture(SceneColor, vec2(inUv.x, 1.0 - inUv.y)), 0.0, 1.0);
    vec3 linear = decodeSrgb(scene.rgb);
#if CHIMERA_COLOR_SPACE == 1
    vec3 encoded = encodePower(linear * SRGB_TO_DCI_P3, 2.6);
#elif CHIMERA_COLOR_SPACE == 2
    vec3 encoded = encodeWithToe(linear * SRGB_TO_DISPLAY_P3, 12.92, 1.0 / 2.4, 1.055, 0.0031308);
#elif CHIMERA_COLOR_SPACE == 3
    // Iris encodes Rec. 2020 with the BT.709 curve
    vec3 encoded = encodeWithToe(linear * SRGB_TO_REC2020, 4.5, 0.45, 1.099, 0.018);
#else
    vec3 encoded = encodePower(linear * SRGB_TO_ADOBE_RGB, 2.2);
#endif
    outColor = vec4(encoded, scene.a);
}
