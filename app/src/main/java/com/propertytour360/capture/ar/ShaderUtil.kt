package com.propertytour360.capture.ar

import android.content.Context
import android.opengl.GLES30
import java.io.BufferedReader
import java.io.InputStreamReader

object ShaderUtil {
    fun loadProgram(context: Context, vertexResource: Int, fragmentResource: Int): Int {
        val vertex = compile(GLES30.GL_VERTEX_SHADER, read(context, vertexResource))
        val fragment = compile(GLES30.GL_FRAGMENT_SHADER, read(context, fragmentResource))
        return GLES30.glCreateProgram().also { program ->
            GLES30.glAttachShader(program, vertex)
            GLES30.glAttachShader(program, fragment)
            GLES30.glLinkProgram(program)
            val linked = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) {
                val message = GLES30.glGetProgramInfoLog(program)
                GLES30.glDeleteProgram(program)
                error("OpenGL program link failed: $message")
            }
            GLES30.glDeleteShader(vertex)
            GLES30.glDeleteShader(fragment)
        }
    }

    private fun compile(type: Int, source: String): Int = GLES30.glCreateShader(type).also { shader ->
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val message = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            error("OpenGL shader compile failed: $message")
        }
    }

    private fun read(context: Context, resource: Int): String =
        BufferedReader(InputStreamReader(context.resources.openRawResource(resource))).use { it.readText() }
}
