package io.github.sodafmr.cardo.brain

import java.io.File
import java.net.URLClassLoader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

class NoAndroidTest {
    private val root = "io.github.sodafmr.cardo.brain"

    @Test
    fun `every brain class loads without android classes`() {
        val classNames = brainClassNames()
        assertTrue(classNames.size > 50, "expected to find the brain classes, found ${classNames.size}")

        // Parent is the platform loader, so any android.* reference fails to resolve.
        val loader = URLClassLoader(runtimeClasspath(), ClassLoader.getPlatformClassLoader())
        for (name in classNames) {
            val cls = Class.forName(name, false, loader)
            // Force resolution of signatures and supertypes, not just the class header.
            cls.declaredMethods
            cls.declaredFields
            cls.declaredConstructors
            assertTrue(cls.name.startsWith(root), name)
        }
    }

    private fun runtimeClasspath() =
        System.getProperty("java.class.path").split(File.pathSeparator).map { File(it).toURI().toURL() }.toTypedArray()

    private fun brainClassNames(): List<String> {
        val relative = root.replace('.', '/')
        val names = mutableListOf<String>()
        for (entry in System.getProperty("java.class.path").split(File.pathSeparator)) {
            val dir = File(entry, relative)
            if (!dir.isDirectory || entry.replace(File.separatorChar, '/').endsWith("/classes/kotlin/test")) continue
            dir.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach {
                names += it.relativeTo(File(entry)).path.removeSuffix(".class").replace(File.separatorChar, '.')
            }
        }
        return names
    }
}
