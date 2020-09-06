package com.immortalalexsan.oscilloscope.helpers

import android.content.Context
import android.opengl.GLES20.GL_COMPILE_STATUS
import android.opengl.GLES20.GL_LINK_STATUS
import android.opengl.GLES20.glAttachShader
import android.opengl.GLES20.glCompileShader
import android.opengl.GLES20.glCreateProgram
import android.opengl.GLES20.glCreateShader
import android.opengl.GLES20.glDeleteProgram
import android.opengl.GLES20.glDeleteShader
import android.opengl.GLES20.glGetProgramiv
import android.opengl.GLES20.glGetShaderiv
import android.opengl.GLES20.glLinkProgram
import android.opengl.GLES20.glShaderSource

internal object ShaderHelper {

    private fun createShader(shaderType: Int, shaderText: String): Int {
        val shaderId = glCreateShader(shaderType)
        if (shaderId == 0) return 0

        glShaderSource(shaderId, shaderText)
        glCompileShader(shaderId)

        val status = IntArray(1)
        glGetShaderiv(shaderId, GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            glDeleteShader(shaderId)
            return 0
        }

        return shaderId
    }

    fun createProgram(vertexShaderId: Int, fragmentShaderId: Int): Int {
        val programId = glCreateProgram()
        if (programId == 0) return 0

        glAttachShader(programId, vertexShaderId)
        glAttachShader(programId, fragmentShaderId)
        glLinkProgram(programId)

        val status = IntArray(1)
        glGetProgramiv(programId, GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            glDeleteProgram(programId)
            return 0
        }

        return programId
    }

    fun createShader(context: Context, shaderType: Int, shaderRawId: Int) =
        createShader(shaderType, FileHelper.readTextFromRaw(context, shaderRawId))
}
