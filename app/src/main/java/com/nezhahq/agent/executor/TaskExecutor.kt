package com.nezhahq.agent.executor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import proto.Nezha.Task
import proto.Nezha.TaskResult
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import org.json.JSONObject

object TaskExecutor {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun executeTask(task: Task, isCommandEnabled: Boolean): TaskResult = withContext(Dispatchers.IO) {
        val resultBuilder = TaskResult.newBuilder()
            .setId(task.id)
            .setType(task.type)

        try {
            when (task.type) {
                1L -> { // HTTPGet
                    val params = parseParams(task.data)
                    val url = params.host
                    val start = System.currentTimeMillis()
                    val request = Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()
                    val delay = (System.currentTimeMillis() - start).toFloat()
                    response.close()
                    
                    resultBuilder.setDelay(delay).setSuccessful(response.isSuccessful)
                }
                2L -> { // ICMPPing
                    val params = parseParams(task.data)
                    val start = System.currentTimeMillis()
                    val process = ProcessBuilder("ping", "-c", "1", "-w", "5", params.host).start()
                    val exitVal = process.waitFor()
                    val delay = (System.currentTimeMillis() - start).toFloat()
                    
                    resultBuilder.setDelay(delay).setSuccessful(exitVal == 0)
                }
                3L -> { // TCPPing
                    val params = parseParams(task.data)
                    val start = System.currentTimeMillis()
                    val socket = Socket()
                    try {
                        socket.connect(InetSocketAddress(params.host, params.port), 5000)
                        val delay = (System.currentTimeMillis() - start).toFloat()
                        resultBuilder.setDelay(delay).setSuccessful(true)
                    } catch (e: Exception) {
                        resultBuilder.setDelay(0f).setSuccessful(false)
                    } finally {
                        socket.close()
                    }
                }
                4L -> { // Command
                    if (!isCommandEnabled) {
                        resultBuilder.setData("Command execution disabled on Android Agent.\n").setSuccessful(false)
                        return@withContext resultBuilder.build()
                    }
                    val startTime = System.currentTimeMillis()
                    val process = ProcessBuilder("sh", "-c", task.data).start()
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val output = java.lang.StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        output.append(line).append("\n")
                    }
                    val exitVal = process.waitFor()
                    val delay = (System.currentTimeMillis() - startTime).toFloat()
                    resultBuilder.setData(output.toString()).setDelay(delay).setSuccessful(exitVal == 0)
                }
                else -> {
                    resultBuilder.setSuccessful(false).setData("Unsupported task type on Android")
                }
            }
        } catch (e: Exception) {
            resultBuilder.setSuccessful(false).setData(e.message ?: "Unknown error")
        }

        return@withContext resultBuilder.build()
    }

    private data class TaskParams(val host: String, val port: Int = 0)
    
    // Parses {"host": "example.com", "port": 80} or similar from Dashboard
    private fun parseParams(data: String): TaskParams {
        return try {
            val json = JSONObject(data)
            TaskParams(
                host = json.optString("host", data), 
                port = json.optInt("port", 80)
            )
        } catch (e: Exception) {
            TaskParams(host = data)
        }
    }
}
