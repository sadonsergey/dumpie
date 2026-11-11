package dev.sadonsergey.dumpie

import com.intellij.openapi.project.Project
import com.intellij.remote.AuthType
import com.intellij.ssh.config.unified.SshConfigManager

/** The IDE's SSH configurations; the connection itself goes through the system ssh client (see RemoteConnection). */
class SshConfigHosts(private val project: Project) : RemoteHosts {
    override fun list(): List<RemoteHosts.Host> = SshConfigManager.getInstance(project).configs.map { config ->
        RemoteHosts.Host(
            id = config.id,
            name = config.name,
            host = config.host,
            port = config.port,
            user = config.username.orEmpty(),
            keyPath = config.keyPath.takeIf { config.authType == AuthType.KEY_PAIR && it.isNotBlank() },
            usesPassword = config.authType == AuthType.PASSWORD,
        )
    }
}
