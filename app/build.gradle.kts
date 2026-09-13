import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Sync

data class GitCommandResult(
    val exitCode: Int,
    val output: String,
)

fun runGitCommand(projectDirectory: java.io.File, vararg arguments: String): GitCommandResult =
    try {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(projectDirectory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        GitCommandResult(process.waitFor(), output)
    } catch (_: Exception) {
        GitCommandResult(-1, "")
    }

fun embeddedBuildId(
    versionName: String,
    versionCode: Int,
    sourceRevision: String,
    sourceTree: String,
    buildType: String,
): String = listOf(
    "versionName=$versionName",
    "versionCode=$versionCode",
    "sourceRevision=$sourceRevision",
    "sourceTree=$sourceTree",
    "buildType=$buildType",
).joinToString(";")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

val vitalBreatheApplicationId = "com.moraybrown.vitalbreathe"
val versionPropertiesFile = rootProject.file("version.properties")
val versionProperties = Properties().apply {
    if (!versionPropertiesFile.isFile) {
        throw GradleException("Missing version.properties")
    }
    versionPropertiesFile.inputStream().use(::load)
}
val vitalBreatheVersionCode = versionProperties.getProperty("VERSION_CODE")
    ?.toIntOrNull()
    ?.takeIf { it > 0 }
    ?: throw GradleException("VERSION_CODE must be a positive integer")
val vitalBreatheVersionName = versionProperties.getProperty("VERSION_NAME")
    ?.takeIf { it.matches(Regex("\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?")) }
    ?: throw GradleException("VERSION_NAME must be a valid semantic version")
val vitalBreatheReleaseSignerSha256 = versionProperties.getProperty("RELEASE_SIGNER_SHA256")
    ?.lowercase()
    ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    ?: throw GradleException("RELEASE_SIGNER_SHA256 must be one lowercase SHA-256 fingerprint")
val ciReleaseSignerSha256 = providers.gradleProperty("ciReleaseSignerSha256").orNull?.lowercase()
if (ciReleaseSignerSha256 != null &&
    !System.getenv("GITHUB_ACTIONS").equals("true", ignoreCase = true)
) {
    throw GradleException("ciReleaseSignerSha256 is reserved for the disposable GitHub Actions key")
}
if (ciReleaseSignerSha256 != null && !ciReleaseSignerSha256.matches(Regex("[0-9a-f]{64}"))) {
    throw GradleException("ciReleaseSignerSha256 must be one lowercase SHA-256 fingerprint")
}
val expectedReleaseSignerSha256 = ciReleaseSignerSha256 ?: vitalBreatheReleaseSignerSha256
val vitalBreatheMinSdk = 26
val vitalBreatheTargetSdk = 34
val vitalBreatheBuildToolsVersion = "34.0.0"

val configuredSourceRevisionResult = runGitCommand(rootProject.projectDir, "rev-parse", "HEAD")
val configuredSourceStatusResult = runGitCommand(
    rootProject.projectDir,
    "status",
    "--porcelain",
    "--untracked-files=normal",
)
val configuredSourceRevision = configuredSourceRevisionResult.output
    .takeIf {
        configuredSourceRevisionResult.exitCode == 0 &&
            it.matches(Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}"))
    }
    ?.lowercase()
    ?: "unknown"
val configuredSourceTreeState = when {
    configuredSourceRevision == "unknown" || configuredSourceStatusResult.exitCode != 0 -> "unknown"
    configuredSourceStatusResult.output.isBlank() -> "clean"
    else -> "dirty"
}

val releaseSigningFile = rootProject.file("keystore.properties")
val releaseSigningProperties = Properties()
val releaseSigningLoadError = if (releaseSigningFile.exists()) {
    try {
        releaseSigningFile.inputStream().use { releaseSigningProperties.load(it) }
        null
    } catch (error: Exception) {
        error
    }
} else {
    null
}
val releaseSigningConfigKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val releaseSigningRequiredKeys = releaseSigningConfigKeys
val releaseSigningConfigured = releaseSigningFile.isFile &&
    releaseSigningLoadError == null &&
    releaseSigningConfigKeys.all { !releaseSigningProperties.getProperty(it).isNullOrBlank() }

val generatedLegalAssetsDirectory = layout.buildDirectory.dir("generated/legal-assets")
val prepareLegalAssets by tasks.registering(Sync::class) {
    from(rootProject.file("LICENSE")) {
        rename { "VitalBreathe-LICENSE.txt" }
    }
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
    from(rootProject.file("karoo-ext/LICENSE")) {
        rename { "Apache-2.0.txt" }
    }
    into(generatedLegalAssetsDirectory)
}

android {
    namespace = "com.tymewear.karoo"
    compileSdk = vitalBreatheTargetSdk
    buildToolsVersion = vitalBreatheBuildToolsVersion

    defaultConfig {
        applicationId = vitalBreatheApplicationId
        minSdk = vitalBreatheMinSdk
        targetSdk = vitalBreatheTargetSdk
        versionCode = vitalBreatheVersionCode
        versionName = vitalBreatheVersionName
        buildConfigField("String", "BUILD_SOURCE_REVISION", "\"$configuredSourceRevision\"")
        buildConfigField("String", "BUILD_SOURCE_TREE", "\"$configuredSourceTreeState\"")
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("signedRelease") {
                storeFile = rootProject.file(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["vitalbreatheBuildId"] = embeddedBuildId(
                vitalBreatheVersionName,
                vitalBreatheVersionCode,
                configuredSourceRevision,
                configuredSourceTreeState,
                "debug",
            )
        }
        release {
            isMinifyEnabled = false
            manifestPlaceholders["vitalbreatheBuildId"] = embeddedBuildId(
                vitalBreatheVersionName,
                vitalBreatheVersionCode,
                configuredSourceRevision,
                configuredSourceTreeState,
                "release",
            )
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("signedRelease")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    applicationVariants.all {
        val outputName = when {
            buildType.name == "release" && !releaseSigningConfigured ->
                "vitalbreathe-karoo-$vitalBreatheVersionName-unsigned.apk"
            else -> "vitalbreathe-karoo-$vitalBreatheVersionName.apk"
        }
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = outputName
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets.getByName("main").assets.srcDir(generatedLegalAssetsDirectory)
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareLegalAssets)
}

val robolectricSdk by configurations.creating {
    isCanBeConsumed = false
}

dependencies {
    implementation(project(":karoo-ext"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.compose.ui)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    add(robolectricSdk.name, libs.robolectric.android.all)
}

val prepareRobolectricSdk by tasks.registering(org.gradle.api.tasks.Sync::class) {
    from(robolectricSdk)
    into(layout.buildDirectory.dir("robolectric"))
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn(prepareRobolectricSdk)
    systemProperty("robolectric.offline", "true")
    systemProperty(
        "robolectric.dependency.dir",
        layout.buildDirectory.dir("robolectric").get().asFile.absolutePath,
    )
}

val releaseDistDirectory = rootProject.layout.projectDirectory.dir("dist")
val releaseStagingDirectory = layout.buildDirectory.dir("signed-release-staging")
val publicManifestFile = rootProject.file("manifest.json")
val publicIconFile = rootProject.file("icon.png")
val publicScreenshotFiles = listOf(
    rootProject.file("docs/screenshots/dashboard-full.png"),
    rootProject.file("docs/screenshots/ve-graph.png"),
)
val thirdPartyNoticesFile = rootProject.file("THIRD_PARTY_NOTICES.md")
val apacheLicenseFile = rootProject.file("karoo-ext/LICENSE")

val verifyPublicReleaseMetadata by tasks.registering {
    group = "verification"
    description = "Checks the public update manifest, icon, notices, and app metadata."
    inputs.files(
        publicManifestFile,
        publicIconFile,
        publicScreenshotFiles,
        thirdPartyNoticesFile,
        apacheLicenseFile,
    )

    doLast {
        if (!publicManifestFile.isFile) {
            throw GradleException("Missing public update manifest: $publicManifestFile")
        }
        if (!publicIconFile.isFile || publicIconFile.length() == 0L) {
            throw GradleException("Missing public release icon: $publicIconFile")
        }
        val iconImage = ImageIO.read(publicIconFile)
            ?: throw GradleException("Public release icon is not a valid image: $publicIconFile")
        if (iconImage.width != 192 || iconImage.height != 192 || !iconImage.colorModel.hasAlpha()) {
            throw GradleException("Public release icon must be a 192x192 image with transparency")
        }
        publicScreenshotFiles.forEach { screenshot ->
            if (!screenshot.isFile || screenshot.length() == 0L) {
                throw GradleException("Missing public screenshot: $screenshot")
            }
            val image = ImageIO.read(screenshot)
                ?: throw GradleException("Public screenshot is not a valid image: $screenshot")
            if (image.width != 480 || image.height != 800) {
                throw GradleException("Public screenshot must be 480x800: $screenshot")
            }
        }
        if (!thirdPartyNoticesFile.isFile || thirdPartyNoticesFile.length() == 0L) {
            throw GradleException("Missing third-party notices: $thirdPartyNoticesFile")
        }
        if (!apacheLicenseFile.isFile || apacheLicenseFile.length() == 0L) {
            throw GradleException("Missing Apache licence: $apacheLicenseFile")
        }

        val metadata = JsonSlurper().parse(publicManifestFile) as? Map<*, *>
            ?: throw GradleException("manifest.json must contain one JSON object")
        val expectedApkUrl =
            "https://github.com/misb74/vitalbreathe-karoo/releases/latest/download/" +
                "vitalbreathe-karoo-$vitalBreatheVersionName.apk"
        val expectedValues = mapOf(
            "label" to "VitalBreathe",
            "packageName" to vitalBreatheApplicationId,
            "iconUrl" to
                "https://github.com/misb74/vitalbreathe-karoo/releases/latest/download/icon.png",
            "latestApkUrl" to expectedApkUrl,
            "latestVersion" to vitalBreatheVersionName,
        )
        expectedValues.forEach { (key, expected) ->
            if (metadata[key] != expected) {
                throw GradleException("manifest.json $key must be '$expected'")
            }
        }
        if ((metadata["latestVersionCode"] as? Number)?.toInt() != vitalBreatheVersionCode) {
            throw GradleException(
                "manifest.json latestVersionCode must be $vitalBreatheVersionCode",
            )
        }
        listOf("developer", "description", "releaseNotes").forEach { key ->
            if ((metadata[key] as? String).isNullOrBlank()) {
                throw GradleException("manifest.json $key must not be blank")
            }
        }
        val expectedScreenshotUrls = listOf(
            "https://raw.githubusercontent.com/misb74/vitalbreathe-karoo/main/" +
                "docs/screenshots/dashboard-full.png",
            "https://raw.githubusercontent.com/misb74/vitalbreathe-karoo/main/" +
                "docs/screenshots/ve-graph.png",
        )
        if (metadata["screenshotUrls"] != expectedScreenshotUrls) {
            throw GradleException("manifest.json screenshotUrls must reference the public screenshots")
        }

        val androidManifest = rootProject.file("app/src/main/AndroidManifest.xml").readText()
        val expectedManifestUrl =
            "https://github.com/misb74/vitalbreathe-karoo/releases/latest/download/manifest.json"
        if (!androidManifest.contains("io.hammerhead.karooext.MANIFEST_URL") ||
            !androidManifest.contains(expectedManifestUrl)
        ) {
            throw GradleException("AndroidManifest.xml does not reference the public update manifest")
        }
    }
}

val clearSignedReleaseOutput by tasks.registering(Delete::class) {
    group = "build"
    description = "Removes any previous signed release candidate before validation."
    delete(releaseDistDirectory, releaseStagingDirectory)
}

val validateReleaseSigning by tasks.registering {
    group = "verification"
    description = "Fails unless the release signing configuration is complete and readable."
    dependsOn(clearSignedReleaseOutput)
    outputs.upToDateWhen { false }

    doLast {
        if (!releaseSigningFile.isFile) {
            throw GradleException(
                "keystore.properties is required for signedRelease. " +
                    "Copy keystore.properties.example and add the private values.",
            )
        }
        releaseSigningLoadError?.let { error ->
            throw GradleException("Unable to read keystore.properties", error)
        }

        val missingKeys = releaseSigningRequiredKeys.filter {
            releaseSigningProperties.getProperty(it).isNullOrBlank()
        }
        if (missingKeys.isNotEmpty()) {
            throw GradleException(
                "keystore.properties is missing: ${missingKeys.joinToString()}",
            )
        }

        val keyFile = rootProject.file(releaseSigningProperties.getProperty("storeFile"))
        if (!keyFile.isFile || !keyFile.canRead()) {
            throw GradleException("Release signing key is not a readable file: $keyFile")
        }

        if (!releaseSigningConfigured) {
            throw GradleException("The signed release signing configuration is incomplete")
        }
        if (configuredSourceRevision == "unknown" || configuredSourceTreeState == "unknown") {
            throw GradleException(
                "Signed release requires a readable Git source revision and tree state",
            )
        }
        if (configuredSourceTreeState != "clean") {
            throw GradleException(
                "Signed release requires a clean Git source tree; commit or remove every change",
            )
        }
    }
}

tasks.configureEach {
    when (name) {
        "testDebugUnitTest", "testReleaseUnitTest", "lintDebug" ->
            mustRunAfter(validateReleaseSigning)
        "assembleRelease" -> mustRunAfter(
            "testDebugUnitTest",
            "testReleaseUnitTest",
            "lintDebug",
        )
    }
}

val verifySignedRelease by tasks.registering {
    group = "verification"
    description = "Verifies and stages a signed release APK."
    dependsOn(
        validateReleaseSigning,
        verifyPublicReleaseMetadata,
        "testDebugUnitTest",
        "testReleaseUnitTest",
        "lintDebug",
        "assembleRelease",
    )
    mustRunAfter("assembleRelease")
    outputs.upToDateWhen { false }

    doLast {
        fun runCommand(command: List<String>, description: String): String {
            val processBuilder = ProcessBuilder(command)
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
            processBuilder.environment()["JAVA_HOME"] = System.getProperty("java.home")
            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw GradleException(
                    "$description failed with exit code $exitCode" +
                        if (output.isBlank()) "" else ":\n$output",
                )
            }
            return output
        }

        fun sha256(file: java.io.File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }

        val localProperties = Properties().apply {
            val file = rootProject.file("local.properties")
            if (file.isFile) file.inputStream().use { load(it) }
        }
        val sdkPath = sequenceOf(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            localProperties.getProperty("sdk.dir"),
        ).firstOrNull { !it.isNullOrBlank() }
            ?: throw GradleException(
                "Android SDK path is unavailable; set ANDROID_HOME or sdk.dir in local.properties",
            )
        val buildToolsDirectory = rootProject.file(sdkPath)
            .resolve("build-tools")
            .resolve(vitalBreatheBuildToolsVersion)
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        fun buildTool(name: String): java.io.File {
            val executableName = when {
                isWindows && name == "apksigner" -> "$name.bat"
                isWindows -> "$name.exe"
                else -> name
            }
            val executable = buildToolsDirectory.resolve(executableName)
            if (!executable.isFile || !executable.canRead()) {
                throw GradleException(
                    "Android Build Tools $vitalBreatheBuildToolsVersion is missing $executableName at " +
                        buildToolsDirectory,
                )
            }
            return executable
        }

        fun commandLineTool(name: String): java.io.File {
            val executableName = when {
                isWindows -> "$name.bat"
                else -> name
            }
            val executable = rootProject.file(sdkPath)
                .resolve("cmdline-tools")
                .resolve("latest")
                .resolve("bin")
                .resolve(executableName)
            if (!executable.isFile || !executable.canRead()) {
                throw GradleException(
                    "Android SDK Command-line Tools (latest) is missing $executableName at " +
                        executable.parentFile,
                )
            }
            return executable
        }

        fun toolCommand(tool: java.io.File, vararg arguments: String): List<String> =
            if (isWindows && tool.extension.equals("bat", ignoreCase = true)) {
                listOf("cmd", "/c", tool.absolutePath, *arguments)
            } else {
                listOf(tool.absolutePath, *arguments)
            }

        val apk = layout.buildDirectory
            .file("outputs/apk/release/vitalbreathe-karoo-$vitalBreatheVersionName.apk")
            .get()
            .asFile
        if (!apk.isFile) {
            throw GradleException("Signed release APK was not produced: $apk")
        }

        val apksignerOutput = runCommand(
            toolCommand(
                buildTool("apksigner"),
                "verify",
                "--min-sdk-version",
                vitalBreatheMinSdk.toString(),
                "--verbose",
                "--print-certs",
                apk.absolutePath,
            ),
            "APK signature verification",
        )
        if (!apksignerOutput.contains(
                "Verified using v2 scheme (APK Signature Scheme v2): true",
            )
        ) {
            throw GradleException("Signed release is not signed with APK Signature Scheme v2")
        }
        if (apksignerOutput.lineSequence().none { it.trim() == "Number of signers: 1" }) {
            throw GradleException("Signed release must have exactly one signer")
        }
        val signerFingerprint = Regex(
            "Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})",
        ).find(apksignerOutput)?.groupValues?.get(1)?.lowercase()
            ?: throw GradleException("Unable to read the APK signer certificate fingerprint")
        if (signerFingerprint != expectedReleaseSignerSha256) {
            throw GradleException(
                "Signed release signer does not match the fingerprint pinned in version.properties " +
                    "(actual $signerFingerprint)",
            )
        }

        runCommand(
            toolCommand(buildTool("zipalign"), "-c", "4", apk.absolutePath),
            "APK ZIP alignment verification",
        )
        val badging = runCommand(
            toolCommand(buildTool("aapt2"), "dump", "badging", apk.absolutePath),
            "APK package inspection",
        )
        val expectedPackage = "package: name='$vitalBreatheApplicationId' " +
            "versionCode='$vitalBreatheVersionCode' versionName='$vitalBreatheVersionName'"
        if (!badging.contains(expectedPackage)) {
            throw GradleException("Signed release package identity or version is incorrect")
        }
        if (!badging.contains("sdkVersion:'$vitalBreatheMinSdk'")) {
            throw GradleException("Signed release minimum SDK is not $vitalBreatheMinSdk")
        }
        if (!badging.contains("targetSdkVersion:'$vitalBreatheTargetSdk'")) {
            throw GradleException("Signed release target SDK is not $vitalBreatheTargetSdk")
        }
        if (badging.lineSequence().any { it.trim() == "application-debuggable" }) {
            throw GradleException("Signed release is unexpectedly debuggable")
        }
        val allowedPermissions = setOf(
            "android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_ADMIN",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
            "$vitalBreatheApplicationId.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
        val packagedPermissions = Regex("uses-permission: name='([^']+)'")
            .findAll(badging)
            .map { it.groupValues[1] }
            .toSet()
        val unexpectedPermissions = packagedPermissions - allowedPermissions
        if (unexpectedPermissions.isNotEmpty()) {
            throw GradleException(
                "Signed release requests unexpected permissions: " +
                    unexpectedPermissions.sorted().joinToString(),
            )
        }

        val manifestTree = runCommand(
            toolCommand(
                buildTool("aapt2"),
                "dump",
                "xmltree",
                apk.absolutePath,
                "--file",
                "AndroidManifest.xml",
            ),
            "APK embedded build-identity inspection",
        )
        val resourceTable = runCommand(
            toolCommand(
                buildTool("aapt2"),
                "dump",
                "resources",
                apk.absolutePath,
            ),
            "APK compiled-resource inspection",
        )
        if (!manifestTree.contains(
                "android:allowBackup(0x01010280)=false",
            )
        ) {
            throw GradleException("Signed release must keep Android backup disabled")
        }
        if (!manifestTree.contains("android:dataExtractionRules(") ||
            !manifestTree.contains("android:fullBackupContent(")
        ) {
            throw GradleException(
                "Signed release must exclude app data from cloud backup and device transfer",
            )
        }

        var zipEntries = 0
        val packagedEntryNames = mutableSetOf<String>()
        ZipFile(apk).use { zip ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                zipEntries += 1
                packagedEntryNames += entry.name
                if (!entry.isDirectory) {
                    val crc = CRC32()
                    var uncompressedBytes = 0L
                    zip.getInputStream(entry).use { input ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            crc.update(buffer, 0, count)
                            uncompressedBytes += count
                        }
                    }
                    if (entry.size >= 0 && entry.size != uncompressedBytes) {
                        throw GradleException("APK ZIP entry has the wrong size: ${entry.name}")
                    }
                    if (entry.crc >= 0 && entry.crc != crc.value) {
                        throw GradleException("APK ZIP entry has the wrong CRC: ${entry.name}")
                    }
                }
            }
        }
        if (zipEntries == 0) throw GradleException("Signed release APK ZIP is empty")
        val requiredLegalAssets = setOf(
            "assets/VitalBreathe-LICENSE.txt",
            "assets/THIRD_PARTY_NOTICES.md",
            "assets/Apache-2.0.txt",
        )
        val missingLegalAssets = requiredLegalAssets - packagedEntryNames
        if (missingLegalAssets.isNotEmpty()) {
            throw GradleException(
                "Signed release APK is missing legal assets: " +
                missingLegalAssets.sorted().joinToString(),
            )
        }
        val requiredPrivacyResources = setOf(
            "xml/backup_rules",
            "xml/data_extraction_rules",
        )
        val packagedResourceNames = Regex(
            "(?m)^\\s*resource\\s+0x[0-9a-fA-F]+\\s+(\\S+)\\s*$",
        ).findAll(resourceTable).map { match -> match.groupValues[1] }.toSet()
        val missingPrivacyResources = requiredPrivacyResources - packagedResourceNames
        if (missingPrivacyResources.isNotEmpty()) {
            throw GradleException(
                "Signed release APK is missing backup-exclusion rules: " +
                    missingPrivacyResources.sorted().joinToString(),
            )
        }

        val apkSha256 = sha256(apk)
        val sourceRoot = runCommand(
            listOf("git", "rev-parse", "--show-toplevel"),
            "Git source-root inspection",
        ).trim()
        if (sourceRoot.isBlank() || !Files.isSameFile(
                rootProject.file(sourceRoot).toPath(),
                rootProject.projectDir.toPath(),
            )
        ) {
            throw GradleException(
                "Git source root does not match the release project: $sourceRoot",
            )
        }
        val verifiedSourceRevision = runCommand(
            listOf("git", "rev-parse", "HEAD"),
            "Git source revision inspection",
        ).trim().lowercase()
        if (!verifiedSourceRevision.matches(Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}"))) {
            throw GradleException("Git source revision is not a full commit identifier")
        }
        val sourceStatus = runCommand(
            listOf("git", "status", "--porcelain", "--untracked-files=normal"),
            "Git source-state inspection",
        ).trim()
        if (sourceStatus.isNotBlank()) {
            throw GradleException(
                "Signed release requires a clean Git source tree; commit or remove every change",
            )
        }
        if (verifiedSourceRevision != configuredSourceRevision ||
            configuredSourceTreeState != "clean"
        ) {
            throw GradleException(
                "Git source identity changed after the APK build was configured",
            )
        }
        val sourceState = "clean"
        val expectedEmbeddedBuildId = embeddedBuildId(
            vitalBreatheVersionName,
            vitalBreatheVersionCode,
            verifiedSourceRevision,
            sourceState,
            "release",
        )
        if (!manifestTree.contains("com.moraybrown.vitalbreathe.BUILD_ID") ||
            !manifestTree.contains("\"$expectedEmbeddedBuildId\"")
        ) {
            throw GradleException(
                "APK embedded build identity does not match the verified release source",
            )
        }

        fun dexCode(className: String): String = runCommand(
            toolCommand(
                commandLineTool("apkanalyzer"),
                "dex",
                "code",
                "--class",
                className,
                apk.absolutePath,
            ),
            "APK runtime build-identity inspection for $className",
        )

        val buildConfigDex = dexCode("com.tymewear.karoo.BuildConfig")
        val expectedBuildConfigFields = listOf(
            "BUILD_SOURCE_REVISION:Ljava/lang/String; = \"$verifiedSourceRevision\"",
            "BUILD_SOURCE_TREE:Ljava/lang/String; = \"clean\"",
            "BUILD_TYPE:Ljava/lang/String; = \"release\"",
            "DEBUG:Z = false",
            "VERSION_CODE:I = 0x${vitalBreatheVersionCode.toString(16)}",
            "VERSION_NAME:Ljava/lang/String; = \"$vitalBreatheVersionName\"",
        )
        expectedBuildConfigFields.forEach { expectedField ->
            if (!buildConfigDex.contains(expectedField)) {
                throw GradleException(
                    "APK runtime BuildConfig does not contain the expected field: $expectedField",
                )
            }
        }

        val identityConsumerDex = dexCode("com.tymewear.karoo.BuildIdentityKt")
        val extensionConsumerDex = dexCode("com.tymewear.karoo.TymewearExtension")
        val packagedIdentityCall =
            "Lcom/tymewear/karoo/BuildIdentityKt;->packagedBuildIdentity()" +
                "Lcom/tymewear/karoo/BuildIdentity;"
        if (!identityConsumerDex.contains(packagedIdentityCall) ||
            !extensionConsumerDex.contains(packagedIdentityCall)
        ) {
            throw GradleException(
                "APK identity consumers do not read the packaged runtime build identity",
            )
        }
        val forbiddenVersionLiteral = Regex(
            "\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?",
        )
        val forbiddenRevisionLiteral = Regex(
            "(?<![0-9a-f])[0-9a-f]{40}(?:[0-9a-f]{24})?(?![0-9a-f])",
        )
        listOf(identityConsumerDex, extensionConsumerDex).forEach { consumerDex ->
            if (forbiddenVersionLiteral.containsMatchIn(consumerDex) ||
                forbiddenRevisionLiteral.containsMatchIn(consumerDex)
            ) {
                throw GradleException(
                    "APK identity consumer embeds a stale-prone build identity literal",
                )
            }
        }

        val artifactName = apk.name
        val stagingDirectory = releaseStagingDirectory.get().asFile
        val distDirectory = releaseDistDirectory.asFile
        stagingDirectory.deleteRecursively()
        stagingDirectory.mkdirs()
        Files.copy(
            apk.toPath(),
            stagingDirectory.resolve(artifactName).toPath(),
            StandardCopyOption.COPY_ATTRIBUTES,
        )
        listOf(publicManifestFile, publicIconFile, thirdPartyNoticesFile).forEach { publicFile ->
            Files.copy(
                publicFile.toPath(),
                stagingDirectory.resolve(publicFile.name).toPath(),
                StandardCopyOption.COPY_ATTRIBUTES,
            )
        }
        Files.copy(
            apacheLicenseFile.toPath(),
            stagingDirectory.resolve("Apache-2.0.txt").toPath(),
            StandardCopyOption.COPY_ATTRIBUTES,
        )
        stagingDirectory.resolve("$artifactName.sha256")
            .writeText("$apkSha256  $artifactName\n")
        stagingDirectory.resolve("SIGNER_CERT_SHA256.txt")
            .writeText("$signerFingerprint\n")
        stagingDirectory.resolve("RELEASE_INFO.txt").writeText(
            buildString {
                appendLine("artifact=$artifactName")
                appendLine("apkSha256=$apkSha256")
                appendLine("signerCertificateSha256=$signerFingerprint")
                appendLine("packageName=$vitalBreatheApplicationId")
                appendLine("versionCode=$vitalBreatheVersionCode")
                appendLine("versionName=$vitalBreatheVersionName")
                appendLine("minSdk=$vitalBreatheMinSdk")
                appendLine("targetSdk=$vitalBreatheTargetSdk")
                appendLine("buildIdentity=$expectedEmbeddedBuildId")
                appendLine("sourceRevision=$verifiedSourceRevision")
                appendLine("sourceTree=$sourceState")
                appendLine("builtAtUtc=${Instant.now()}")
            },
        )

        Files.createDirectories(distDirectory.parentFile.toPath())
        try {
            Files.move(
                stagingDirectory.toPath(),
                distDirectory.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(stagingDirectory.toPath(), distDirectory.toPath())
        }
        logger.lifecycle("Verified signed release written to ${distDirectory.absolutePath}")
        logger.lifecycle("APK SHA-256: $apkSha256")
        logger.lifecycle("Signer certificate SHA-256: $signerFingerprint")
    }
}

tasks.register("signedRelease") {
    group = "build"
    description = "Tests, lints, builds, verifies, and stages the signed release."
    dependsOn(verifySignedRelease)
}
