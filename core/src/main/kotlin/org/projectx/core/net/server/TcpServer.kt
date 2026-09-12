package org.projectx.core.net.server

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.CancellationException
import kotlinx.coroutines.*
import org.projectx.core.Logger.logError
import org.projectx.core.Logger.logInfo
import org.projectx.core.Logger.logTrace
import org.projectx.core.net.Session
import java.util.concurrent.Executors

abstract class TcpServer(
    private val host: String,
    private val port: Int,
    private val name: String,
) {
    private lateinit var job: Job
    private lateinit var serverSocket: ServerSocket
    private lateinit var selectorManager: SelectorManager

    protected lateinit var dispatcher: ExecutorCoroutineDispatcher
        private set
    protected lateinit var scope: CoroutineScope
        private set

    protected fun createScope() {
        dispatcher = Executors.newCachedThreadPool().asCoroutineDispatcher()
        selectorManager = ActorSelectorManager(dispatcher)
        scope = CoroutineScope(dispatcher)
    }

    protected fun bindAndAccept(): Job {
        serverSocket = runBlocking {
            aSocket(selectorManager).tcp().bind(host, port) { reuseAddress = true }
        }
        job = scope.launch {
            try {
                supervisorScope {
                    logInfo("$name started on $host:$port")
                    while (isActive) {
                        val socket = serverSocket.accept()
                        val ip = socket.remoteAddress.toJavaAddress().toString()
                            .substringAfter("/").substringBefore(":")
                        logTrace("$name client connected: $ip")
                        connection(socket, ip)
                    }
                }
            } catch (_: CancellationException) {
                logInfo("$name stopping...")
            } catch (e: Exception) {
                logError("Error in $name", e)
            }
        }
        return job
    }

    open fun stop() {
        try {
            job.cancel()
            dispatcher.close()
            if (::serverSocket.isInitialized) serverSocket.close()
            if (::selectorManager.isInitialized) selectorManager.close()
        } catch (e: Exception) {
            logError("Error stopping $name", e)
        }
    }

    private fun connection(socket: Socket, ip: String) = scope.launch(
        dispatcher + CoroutineExceptionHandler { _, throwable ->
            logTrace("Error connecting $name client: ${throwable.message}")
        }
    ) {
        try {
            val input = socket.openReadChannel()
            val output = socket.openWriteChannel(autoFlush = false)
            try {
                handleConnection(input, output, ip)
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            when {
                e is CancellationException -> {}
                Session.isExpectedDisconnect(e) -> logTrace("$name client disconnected: ${e::class.simpleName}")
                else -> logError("Error handling $name connection", e)
            }
        } finally {
            socket.close()
        }
    }

    protected abstract suspend fun handleConnection(input: ByteReadChannel, output: ByteWriteChannel, ip: String)
}
