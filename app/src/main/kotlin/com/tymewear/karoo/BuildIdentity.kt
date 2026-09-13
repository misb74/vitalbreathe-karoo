package com.tymewear.karoo

private val SOURCE_REVISION_PATTERN = Regex("[0-9a-f]{40}|[0-9a-f]{64}")

data class BuildIdentity(
    val versionName: String,
    val versionCode: Int,
    val sourceRevision: String,
    val sourceTree: String,
    val buildType: String,
) {
    fun displayLabel(): String {
        val revisionLabel = sourceRevision
            .lowercase()
            .takeIf { it.matches(SOURCE_REVISION_PATTERN) }
            ?.take(12)
            ?: "unknown"
        val sourceMarker = when (sourceTree.lowercase()) {
            "clean" -> ""
            "dirty" -> " · dirty"
            else -> " · source unknown"
        }
        return "Build $versionName ($versionCode) · $revisionLabel · $buildType$sourceMarker"
    }
}

private val PACKAGED_BUILD_IDENTITY by lazy {
    readBuildIdentity(BuildConfig::class.java)
}

/**
 * Read generated BuildConfig fields at runtime so Kotlin cannot copy their
 * compile-time values into consumers that may survive an incremental build.
 */
internal fun readBuildIdentity(buildConfigClass: Class<*>): BuildIdentity = BuildIdentity(
    versionName = buildConfigClass.stringField("VERSION_NAME"),
    versionCode = buildConfigClass.getField("VERSION_CODE").getInt(null),
    sourceRevision = buildConfigClass.stringField("BUILD_SOURCE_REVISION"),
    sourceTree = buildConfigClass.stringField("BUILD_SOURCE_TREE"),
    buildType = buildConfigClass.stringField("BUILD_TYPE"),
)

private fun Class<*>.stringField(name: String): String =
    checkNotNull(getField(name).get(null) as? String) {
        "$name must be a non-null String field on ${this.name}"
    }

internal fun packagedBuildIdentity(): BuildIdentity = PACKAGED_BUILD_IDENTITY

fun currentBuildIdentity(): BuildIdentity = packagedBuildIdentity()
