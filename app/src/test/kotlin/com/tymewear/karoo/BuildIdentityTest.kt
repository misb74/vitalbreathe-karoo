package com.tymewear.karoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class BuildIdentityTest {
    @Test
    fun `packaged identity reads every field at runtime`() {
        assertEquals(
            BuildIdentity(
                versionName = "test-version-from-field",
                versionCode = 424242,
                sourceRevision = "0123456789abcdef0123456789abcdef01234567",
                sourceTree = "test-tree-from-field",
                buildType = "test-type-from-field",
            ),
            readBuildIdentity(FakePackagedBuildConfig::class.java),
        )
    }

    @Test
    fun `current identity delegates to the runtime packaged identity`() {
        assertSame(packagedBuildIdentity(), currentBuildIdentity())
    }

    @Test
    fun `identity consumers do not embed any build identity literal`() {
        val forbiddenLiterals = listOf(
            Regex("""\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?"""),
            Regex("""(?<![0-9a-f])[0-9a-f]{40}(?:[0-9a-f]{24})?(?![0-9a-f])"""),
        )
        listOf(
            Class.forName("com.tymewear.karoo.BuildIdentityKt"),
            TymewearExtension::class.java,
        ).forEach { consumer ->
            val classFile = classFileText(consumer)
            forbiddenLiterals.forEach { pattern ->
                assertFalse(
                    "${consumer.simpleName} must not embed build literal ${pattern.pattern}",
                    pattern.containsMatchIn(classFile),
                )
            }
        }
    }

    @Test
    fun `clean build label includes version revision and build type`() {
        val identity = BuildIdentity(
            versionName = "9.8.7-beta.3",
            versionCode = 98703,
            sourceRevision = "4AC5EA84F47B126DC8267D01F1D3AC31F5704C2C",
            sourceTree = "clean",
            buildType = "release",
        )

        assertEquals(
            "Build 9.8.7-beta.3 (98703) · 4ac5ea84f47b · release",
            identity.displayLabel(),
        )
    }

    @Test
    fun `dirty build label is unmistakable`() {
        val identity = BuildIdentity(
            versionName = "9.8.7-beta.3",
            versionCode = 98703,
            sourceRevision = "4ac5ea84f47b126dc8267d01f1d3ac31f5704c2c",
            sourceTree = "dirty",
            buildType = "debug",
        )

        assertEquals(
            "Build 9.8.7-beta.3 (98703) · 4ac5ea84f47b · debug · dirty",
            identity.displayLabel(),
        )
    }

    @Test
    fun `unknown source is shown rather than mistaken for a verified revision`() {
        val identity = BuildIdentity(
            versionName = "9.8.7-beta.3",
            versionCode = 98703,
            sourceRevision = "unknown",
            sourceTree = "unknown",
            buildType = "debug",
        )

        assertEquals(
            "Build 9.8.7-beta.3 (98703) · unknown · debug · source unknown",
            identity.displayLabel(),
        )
    }

    private fun classFileText(type: Class<*>): String {
        val resourceName = "/${type.name.replace('.', '/')}.class"
        val bytes = checkNotNull(type.getResourceAsStream(resourceName)) {
            "Missing compiled class resource $resourceName"
        }.use { it.readBytes() }
        return bytes.toString(Charsets.ISO_8859_1)
    }
}

class FakePackagedBuildConfig {
    companion object {
        @JvmField
        val VERSION_NAME = "test-version-from-field"

        @JvmField
        val VERSION_CODE = 424242

        @JvmField
        val BUILD_SOURCE_REVISION = "0123456789abcdef0123456789abcdef01234567"

        @JvmField
        val BUILD_SOURCE_TREE = "test-tree-from-field"

        @JvmField
        val BUILD_TYPE = "test-type-from-field"
    }
}
