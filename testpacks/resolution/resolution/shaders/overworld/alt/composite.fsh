#version 120
#include "../../lib/common.glsl"
const float EXPOSURE = 1.0;
uniform sampler2D colortex0;
varying vec2 texcoord;

void main() {
    gl_FragColor = resolutionMarker(texture2D(colortex0, texcoord));
}
