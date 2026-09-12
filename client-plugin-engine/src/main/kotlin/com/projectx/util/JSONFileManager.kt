package com.projectx.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonIOException
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.lang.reflect.Type

object JsonFileManager {
    private var gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun setGson(customGson: Gson) {
        gson = customGson
    }

    fun getGson(): Gson = gson

    @Throws(IOException::class)
    fun <T> loadJsonFile(file: File, type: Type): T {
        if (!file.exists()) error("File $file does not exist")
        return file.inputStream().use { inputStream ->
            JsonReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                gson.fromJson<T>(reader, type)
            }
        }
    }

    @Throws(JsonIOException::class)
    fun <T> fromJsonString(json: String, type: Type): T = gson.fromJson(json, type)

    @Throws(IOException::class)
    fun saveJsonFile(data: Any, file: File) {
        file.parentFile?.takeIf { !it.exists() }?.mkdirs()
        file.outputStream().use { outputStream ->
            JsonWriter(OutputStreamWriter(outputStream, Charsets.UTF_8)).use { writer ->
                writer.setIndent("  ")
                gson.toJson(data, data::class.java, writer)
            }
        }
    }

    fun toJson(data: Any): String = gson.toJson(data)
}