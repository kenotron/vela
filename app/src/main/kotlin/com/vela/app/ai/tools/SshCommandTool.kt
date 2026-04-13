package com.vela.app.ai.tools

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.vela.app.ssh.SshKeyManager
import com.vela.app.ssh.SshNodeRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val TAG = "SshCommandTool"

/**
 * Runs a shell command on a named Vela node via SSH, authenticated using the
 * device's RSA identity key managed by [SshKeyManager].
 *
 * Example AI calls:
 *   run_ssh_command(node="MacBook Pro", command="ls ~/Desktop")
 *   run_ssh_command(node="dev-box", command="docker ps", timeout_seconds=15)
 */
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
                "No Vela nodes configured. Add one via the Nodes screen (🔗 icon)."
            else
                "No node found with label \"$nodeLabel\". Available: ${avail.joinToString()}"
        }

        Log.d(TAG, "SSH → ${node.username}@${node.host}:${node.port}  cmd=`$command`")

        return@withContext try {
            val jsch = JSch()
            jsch.addIdentity(
                "vela",
                keyManager.getPrivateKeyPem().toByteArray(Charsets.UTF_8),
                null, null,
            )

            val session = jsch.getSession(node.username, node.host, node.port)
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
            Thread.sleep(50) // flush buffers

            val exitCode = channel.exitStatus
            channel.disconnect()
            session.disconnect()

            buildString {
                val out = stdout.toString(Charsets.UTF_8.name()).trimEnd()
                val err = stderr.toString(Charsets.UTF_8.name()).trimEnd()
                if (out.isNotEmpty()) appendLine(out)
                if (err.isNotEmpty()) appendLine("[stderr] $err")
                append("[exit $exitCode]")
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSH command failed", e)
            "SSH error: ${e.message?.take(300)}"
        }
    }
}
