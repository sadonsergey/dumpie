package dev.sadonsergey.dumpie

import com.intellij.openapi.project.Project

/** SSH hosts of the IDE (Settings | Tools | SSH Configurations); implemented in dumpie-ssh.xml when the IDE has SSH. */
interface RemoteHosts {
    class Host(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
        val user: String,
        val keyPath: String?,
        val usesPassword: Boolean,
    )

    fun list(): List<Host>

    companion object {
        /** Null in IDEs without SSH support. */
        fun get(project: Project): RemoteHosts? = project.getService(RemoteHosts::class.java)
    }
}
