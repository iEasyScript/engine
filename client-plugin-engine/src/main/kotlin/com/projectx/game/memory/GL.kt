package com.projectx.game.memory

import com.projectx.game.memory.GL.Constants.GL_INFO_LOG_LENGTH
import com.projectx.game.memory.GL.Constants.GL_TRUE
import com.projectx.game.platform.NativeLibraries
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.foreign.ValueLayout.*

private val LIBRARY_PATH: String get() = NativeLibraries.path(NativeLibraries.GL_COMMANDS)

object GL {
    fun drawElements(mode: Int, count: Int, type: Int, indices: MemorySegment) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glDrawElements") {
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS)
        }
        funcHandle.invokeExact(mode, count, type, indices)
    }

    fun bindBuffer(target: Int, buffer: Int) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glBindBuffer") {
            FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT)
        }
        funcHandle.invokeExact(target, buffer)
    }

    fun bufferData(target: Int, size: Long, data: MemorySegment, usage: Int) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glBufferData") {
            FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)
        }
        funcHandle.invokeExact(target, size, data, usage)
    }

    fun glBegin(mode: Int) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glBegin") {
            FunctionDescriptor.ofVoid(JAVA_INT)
        }
        funcHandle.invokeExact(mode)
    }

    fun glVertex2f(x: Float, y: Float) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glVertex2f") {
            FunctionDescriptor.ofVoid(JAVA_FLOAT, JAVA_FLOAT)
        }
        funcHandle.invokeExact(x, y)
    }

    fun glColor3f(r: Float, g: Float, b: Float) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glColor3f") {
            FunctionDescriptor.ofVoid(JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT)
        }
        funcHandle.invokeExact(r, g, b)
    }

    fun glMatrixMode(mode: Int) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glMatrixMode") {
            FunctionDescriptor.ofVoid(JAVA_INT)
        }
        funcHandle.invokeExact(mode)
    }

    fun glLoadIdentity() {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glLoadIdentity") {
            FunctionDescriptor.ofVoid()
        }
        funcHandle.invokeExact()
    }

    fun glPushMatrix() {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glPushMatrix") {
            FunctionDescriptor.ofVoid()
        }
        funcHandle.invokeExact()
    }

    fun glPopMatrix() {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glPopMatrix") {
            FunctionDescriptor.ofVoid()
        }
        funcHandle.invokeExact()
    }

    fun glEnd() {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glEnd") {
            FunctionDescriptor.ofVoid()
        }
        funcHandle.invokeExact()
    }

    fun glPushAttrib(mask: Int) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glPushAttrib") {
            FunctionDescriptor.ofVoid(JAVA_INT)
        }
        funcHandle.invokeExact(mask)
    }

    fun glPopAttrib() {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glPopAttrib") {
            FunctionDescriptor.ofVoid()
        }
        funcHandle.invokeExact()
    }

    fun glEnable(cap: Int) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glEnable") {
            FunctionDescriptor.ofVoid(JAVA_INT)
        }
        funcHandle.invokeExact(cap)
    }

    fun glDisable(cap: Int) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glDisable") {
            FunctionDescriptor.ofVoid(JAVA_INT)
        }
        funcHandle.invokeExact(cap)
    }

    fun glBlendFunc(sfactor: Int, dfactor: Int) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glBlendFunc") {
            FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT)
        }
        funcHandle.invokeExact(sfactor, dfactor)
    }

    fun glColor4f(r: Float, g: Float, b: Float, a: Float) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glColor4f") {
            FunctionDescriptor.ofVoid(JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT)
        }
        funcHandle.invokeExact(r, g, b, a)
    }

    fun glGetError(): Int {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glGetError") {
            FunctionDescriptor.of(JAVA_INT)
        }
        return funcHandle.invokeExact() as Int
    }

    fun glCreateShader(type: Int): Int {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glCreateShader") {
            FunctionDescriptor.of(JAVA_INT, JAVA_INT)
        }
        return funcHandle.invokeExact(type) as Int
    }

    fun glShaderSource(shader: Int, source: String) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glShaderSource") {
            FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS)
        }
        Arena.ofConfined().use {
            val sourcePtr = it.allocateFrom(source)

            val sourcePtrArray = it.allocate(ADDRESS, ADDRESS.byteSize())
            sourcePtrArray.set(ADDRESS, 0, sourcePtr)

            val lengthPtr = it.allocate(JAVA_INT, 1)
            lengthPtr.set(JAVA_INT, 0, source.length)

            funcHandle.invoke(shader, 1, sourcePtrArray, lengthPtr)
        }
    }

    fun glCompileShader(shader: Int) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glCompileShader") {
            FunctionDescriptor.ofVoid(JAVA_INT)
        }
        funcHandle.invokeExact(shader)
    }

    fun glCreateProgram(): Int {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glCreateProgram") {
            FunctionDescriptor.of(JAVA_INT)
        }
        return funcHandle.invokeExact() as Int
    }

    fun glAttachShader(program: Int, shader: Int) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glAttachShader") {
            FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT)
        }
        funcHandle.invokeExact(program, shader)
    }

    fun glLinkProgram(program: Int) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glLinkProgram") {
            FunctionDescriptor.ofVoid(JAVA_INT)
        }
        funcHandle.invokeExact(program)
    }

    fun glUseProgram(program: Int) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glUseProgram") {
            FunctionDescriptor.ofVoid(JAVA_INT)
        }
        funcHandle.invokeExact(program)
    }

    fun glGetAttribLocation(program: Int, name: String): Int {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glGetAttribLocation") {
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS)
        }
        Arena.ofConfined().use {
            val namePtr = it.allocateFrom(name)
            return funcHandle.invokeExact(program, namePtr) as Int
        }
    }

    fun glVertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, pointer: MemorySegment) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glVertexAttribPointer") {
            FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS)
        }
        funcHandle.invokeExact(index, size, type, if(normalized) 1 else 0, stride, pointer)
    }

    fun glEnableVertexAttribArray(index: Int) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glEnableVertexAttribArray") {
            FunctionDescriptor.ofVoid(JAVA_INT)
        }
        funcHandle.invokeExact(index)
    }

    fun glDisableVertexAttribArray(index: Int) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glDisableVertexAttribArray") {
            FunctionDescriptor.ofVoid(JAVA_INT)
        }
        funcHandle.invokeExact(index)
    }

    fun glDrawArrays(mode: Int, first: Int, count: Int) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glDrawArrays") {
            FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT)
        }
        funcHandle.invokeExact(mode, first, count)
    }

    fun glGetShaderiv(shader: Int, pname: Int, params: IntArray) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glGetShaderiv") {
            FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS)
        }
        Arena.ofConfined().use { arena ->
            val paramsBuffer = arena.allocate(JAVA_INT, 1)
            funcHandle.invokeExact(shader, pname, paramsBuffer)
            params[0] = paramsBuffer.getAtIndex(JAVA_INT, 0)
        }
    }

    fun glGetProgramiv(program: Int, pname: Int, params: IntArray) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glGetProgramiv") {
            FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS)
        }
        Arena.ofConfined().use { arena ->
            val paramsBuffer = arena.allocate(JAVA_INT, 1)
            funcHandle.invokeExact(program, pname, paramsBuffer)
            params[0] = paramsBuffer.getAtIndex(JAVA_INT, 0)
        }
    }

    fun glClear(mask: Int) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glClear") {
            FunctionDescriptor.ofVoid(JAVA_INT)
        }
        funcHandle.invokeExact(mask)
    }

    fun glDepthFunc(func: Int) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glDepthFunc") {
            FunctionDescriptor.ofVoid(JAVA_INT)
        }
        funcHandle.invokeExact(func)
    }

    fun glBindAttribLocation(program: Int, index: Int, name: String) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glBindAttribLocation") {
            FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS)
        }
        Arena.ofConfined().use { arena ->
            val namePtr = arena.allocateFrom(name)
            funcHandle.invoke(program, index, namePtr)
        }
    }

    fun glDeleteShader(shader: Int) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glDeleteShader") {
            FunctionDescriptor.ofVoid(JAVA_INT)
        }
        funcHandle.invokeExact(shader)
    }

    fun glGetShaderInfoLog(shader: Int, maxLength: Int, length: MemorySegment?, infoLog: MemorySegment) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glGetShaderInfoLog") {
            FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS)
        }
        funcHandle.invokeExact(shader, maxLength, length, infoLog)
    }

    fun getShaderInfoLog(shader: Int): String {
        val logLength = IntArray(1)
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, logLength)

        if (logLength[0] > 0) {
            Arena.ofConfined().use { arena ->
                val logBuffer = arena.allocate(ValueLayout.JAVA_BYTE, logLength[0].toLong())
                glGetShaderInfoLog(shader, logLength[0], MemorySegment.NULL, logBuffer)
                return logBuffer.getString(0)
            }
        }
        return "No log available"
    }

    fun glGetProgramInfoLog(program: Int, maxLength: Int, length: MemorySegment?, infoLog: MemorySegment) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glGetProgramInfoLog") {
            FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS)
        }
        funcHandle.invokeExact(program, maxLength, length, infoLog)
    }

    fun getProgramInfoLog(program: Int): String {
        val logLength = IntArray(1)
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, logLength)

        if (logLength[0] > 0) {
            Arena.ofConfined().use { arena ->
                val logBuffer = arena.allocate(ValueLayout.JAVA_BYTE, logLength[0].toLong())
                glGetProgramInfoLog(program, logLength[0], MemorySegment.NULL, logBuffer)
                return logBuffer.getString(0)
            }
        }
        return "No log available"
    }

    fun glGetIntegerv(pname: Int, params: IntArray) {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glGetIntegerv") {
            FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS)
        }
        Arena.ofConfined().use { arena ->
            val paramsBuffer = arena.allocate(JAVA_INT, params.size.toLong())
            funcHandle.invoke(pname, paramsBuffer)
            for (i in params.indices)
                params[i] = paramsBuffer.getAtIndex(JAVA_INT, i.toLong())
        }
    }

    fun glIsEnabled(cap: Int): Boolean {
        val funcHandle = NativeAccess.getLibraryFunction(LIBRARY_PATH, "glIsEnabled") {
            FunctionDescriptor.of(JAVA_INT, JAVA_INT)
        }
        return (funcHandle.invokeExact(cap) as Int) == GL_TRUE
    }

    object Constants {
        const val GL_ARRAY_BUFFER = 0x8892
        const val GL_ELEMENT_ARRAY_BUFFER = 0x8893
        const val GL_STATIC_DRAW = 0x88E4
        const val GL_DYNAMIC_DRAW = 0x88E8

        const val GL_VERTEX_SHADER = 0x8B31
        const val GL_FRAGMENT_SHADER = 0x8B30

        const val GL_FLOAT = 0x1406
        const val GL_FALSE = 0
        const val GL_TRUE = 1

        const val GL_TRIANGLES = 0x0004
        const val GL_LINES = 0x0001

        const val GL_COLOR_BUFFER_BIT = 0x4000
        const val GL_DEPTH_BUFFER_BIT = 0x0100

        const val GL_PROJECTION = 0x1701
        const val GL_MODELVIEW = 0x1700

        const val GL_BLEND = 0x0BE2
        const val GL_SRC_ALPHA = 0x0302
        const val GL_ONE_MINUS_SRC_ALPHA = 0x0303
        const val GL_DEPTH_TEST = 0x0B71
        const val GL_ALL_ATTRIB_BITS = 0xFFFF

        const val GL_COMPILE_STATUS = 0x8B81
        const val GL_LINK_STATUS = 0x8B82
        const val GL_ALWAYS = 0x0207

        const val GL_INFO_LOG_LENGTH = 0x8B84
        const val GL_CURRENT_PROGRAM = 0x8B8D
    }
}