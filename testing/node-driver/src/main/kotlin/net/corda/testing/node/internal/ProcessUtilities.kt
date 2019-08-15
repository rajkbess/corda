package net.corda.testing.node.internal

import net.corda.core.internal.div
import java.io.File
import java.nio.file.Path
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object ProcessUtilities {
    inline fun <reified C : Any> startJavaProcess(
            arguments: List<String>,
            classPath: List<String> = defaultClassPath,
            workingDirectory: Path? = null,
            jdwpPort: Int? = null,
            extraJvmArguments: List<String> = emptyList(),
            maximumHeapSize: String? = null
    ): Process {
        return startJavaProcess(C::class.java.name, arguments, classPath, workingDirectory, jdwpPort, extraJvmArguments, maximumHeapSize)
    }

    fun startJavaProcess(
            className: String,
            arguments: List<String>,
            classPath: List<String> = defaultClassPath,
            workingDirectory: Path? = null,
            jdwpPort: Int? = null,
            extraJvmArguments: List<String> = emptyList(),
            maximumHeapSize: String? = null
    ): Process {
        val command = mutableListOf<String>().apply {
            add(javaPath)
            (jdwpPort != null) && add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=$jdwpPort")
            if (maximumHeapSize != null) add("-Xmx$maximumHeapSize")
            add("-XX:+UseG1GC")
            addAll(extraJvmArguments)
            add(className)
            addAll(arguments)
        }
        return ProcessBuilder(command).apply {
            inheritIO()
            environment()["CLASSPATH"] = classPath.joinToString(File.pathSeparator)
            if (workingDirectory != null) {
                // Timestamp may be handy if the same process started, killed and then re-started. Without timestamp
                // StdOut and StdErr will be overwritten.
                val timestamp = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss.SSS"))
                redirectError((workingDirectory / "$className.$timestamp.stderr.log").toFile())
                redirectOutput((workingDirectory / "$className.$timestamp.stdout.log").toFile())
                directory(workingDirectory.toFile())
            }
        }.start()
    }

    private val javaPath = (System.getProperty("java.home") / "bin" / "java").toString()

    val defaultClassPath: List<String> = System.getProperty("java.class.path").split(File.pathSeparator)
}