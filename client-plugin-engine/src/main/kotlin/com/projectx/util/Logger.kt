package com.projectx.util

import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

object Logger {
    private val dateFormat = SimpleDateFormat("MM/dd/yy HH:mm:ss")
    private val calendar: Calendar = Calendar.getInstance()

    fun getDateString(): String {
        return dateFormat.format(calendar.time)
    }

    fun handle(throwable: Throwable) {
        logError(throwable)
        System.err.println("ERROR! THREAD NAME: " + Thread.currentThread().name)
        throwable.printStackTrace()
    }

    fun log(classInstance: Any, message: Any) {
        log(classInstance.javaClass.simpleName ?: "Unknown", message)
    }

    fun log(className: String, message: Any) {
        val text = "[$className] $message"
        println(text)
    }

    fun writeToFile(fileName: String, text: String) {
        try {
            val parts = fileName.split("/")

            for (i in 0 until parts.size - 1) {
                val file = File("${System.getProperty("user.home")}/.projectx/${parts[i]}")
                if (!file.exists()) {
                    file.mkdir()
                }
            }

            FileWriter("${System.getProperty("user.home")}/.projectx/$fileName", true).use { writer ->
                writer.write("[${getDateString()}]:  $text\r\n")
            }
        } catch (e: Exception) {
        }
    }

    fun logError(throwable: Throwable) {
        val errors = StringWriter()
        throwable.printStackTrace(PrintWriter(errors))
        writeToFile("errorLog.log", "${Thread.currentThread().name}: $errors")
    }

    fun logError(message: String) {
        writeToFile("errorLog.log", message)
    }
}