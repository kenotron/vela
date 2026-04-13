package com.vela.app.ai.tools

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.vela.app.ssh.SshKeyManager
import com.vela.app.ssh.SshNode
import com.vela.app.ssh.SshNodeRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val TAG = "SshCommandTool"

class SshCommandTool(
    private val nodeRegistry: SshNodeRegistry,
    private val keyManager: SshKeyManager,
) : Tool {
    override val name        = "run_ssh_command"
    override val displayName = "SSH Command"
    override val icon        = "💻"
    override val description =
        "Runs a shell command on a connected Vela node via SSH. " +
        "Use list_ssh_nodes first to see available node labels."
    override val parameters = listOf(
        ToolParameter("node",            "string",  "Node label (e.g. \"MacBook Pro\") or host"),
        ToolParameter("command",         "string",  "Shell command to execute on the remote machine"),
        ToolParameter("timeout_seconds", "integer", "Max seconds to wait (default 30)"),
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val nodeLabel = args["node"] as? String
            ?: return@withContext "Error: 'node' is required"
        val command = args["command"] as? String
            ?: return@withContext "Error: 'command' is required"
        val timeout = (args["timeout_seconds"] as? Number)?.toInt() ?: 30

        val node = nodeRegistry.findByLabel(nodeLabel) ?: run {
            val avail = nodeRegistry.allSync().map { it.label }
            return@withContext if (avail.isEmpty())
                "No Vela nodes configured. Add one via the Nodes screen."
            else
                "No node found with label \"$nodeLabel\". Available: ${avail.joinToString()}"
        }

        return@withContext runCommandOnNode(node, command, timeout)
    }

    private fun runCommandOnNode(node: SshNode, command: String, timeout: Int): String {
        val jsch = JSch()
        jsch.addIdentity(
            "vela",
            keyManager.getPrivateKeyPem().toByteArray(Charsets.UTF_8),
            null, null,
        )

        // Try each host in order — first one that connects wins.
        val errors = mutableListOf<String>()
        for (host in node.hosts) {
            Log.d(TAG, "SSH → ${node.username}@$host:${node.port}  cmd=`$command`")
            try {
                val session = jsch.getSession(node.username, host, node.port)
                session.setConfig("StrictHostKeyChecking", "no")
                session.setConfig("ServerAliveInterval", "10")
                session.connect(timeout * 1000)

                val channel = session.openChannel("exec") as ChannelExec
                channel.setCommand(command)

                val stdout = ByteArrayOutputStream()
                val stderr = ByteArrayOutputStream()
                channel.outputStream = stdout
                channel.setErrStream(stderr)
                channel.connect()

                val deadline = System.currentTimeMillis() + timeout * 1000L
                while (!channel.isClosed && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50)
                }
                Thread.sleep(50)

                val exitCode = channel.exitStatus
                channel.disconnect()
                session.disconnect()

                return buildString {
                    val out = stdout.toString(Charsets.UTF_8.name()).trimEnd()
                    val err = stderr.toString(Charsets.UTF_8.name()).trimEnd()
                    if (node.hosts.size > 1) appendLine("[connected via $host]")
                    if (out.isNotEmpty()) appendLine(out)
                    if (err.isNotEmpty()) appendLine("[stderr] $err")
                    append("[exit $exitCode]")
                }
            } catch (e: JSchException) {
                val msg = "[$host] ${e.message?.take(80)}"
                Log.w(TAG, "SSH failed on $host: $e")
                errors += msg
                // Continue to next host
            }
        }

        // All hosts failed
        return buildString {
            appendLine("SSH failed on all ${node.hosts.size} address(es):")
            errors.forEach { appendLine("  • $it") }
        }.trimEnd()
    }
}
