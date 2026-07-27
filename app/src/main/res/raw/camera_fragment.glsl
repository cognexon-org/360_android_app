#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES sTexture;
in vec2 v_TexCoord;
out vec4 outColor;
void main() {
  outColor = texture(sTexture, v_TexCoord);
}
