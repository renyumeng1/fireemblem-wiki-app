import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

abstract class GenerateWikiAssets @Inject constructor(
    private val execOperations: ExecOperations,
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourcePackage: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatorFiles: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val endingPage: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val endingBundle: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val plantingPage: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val supportPage: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val supportBundle: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mechanicsArchivePage: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mechanicsGrowthPage: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val growthSimulatorPage: RegularFileProperty

    @get:Input
    abstract val pythonCommand: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val packageDirectory = sourcePackage.get().asFile
        val destination = outputDirectory.get().asFile
        fileSystemOperations.sync {
            from(packageDirectory.resolve("assets")) { include("*.webp") }
            into(destination.resolve("wiki/assets"))
        }
        val endingsData = temporaryDir.resolve("endings.json")
        execOperations.exec {
            commandLine(pythonCommand.get() + listOf(
                generatorFiles.files.single { it.name == "prepare_endings.py" }.absolutePath,
                "--page", endingPage.get().asFile.absolutePath,
                "--bundle", endingBundle.get().asFile.absolutePath,
                "--output", endingsData.absolutePath,
            ))
        }
        val plantingData = temporaryDir.resolve("planting.json")
        execOperations.exec {
            commandLine(pythonCommand.get() + listOf(
                generatorFiles.files.single { it.name == "prepare_planting.py" }.absolutePath,
                "--page", plantingPage.get().asFile.absolutePath,
                "--output", plantingData.absolutePath,
            ))
        }
        val supportsData = temporaryDir.resolve("supports.json")
        execOperations.exec {
            commandLine(pythonCommand.get() + listOf(
                generatorFiles.files.single { it.name == "prepare_supports.py" }.absolutePath,
                "--page", supportPage.get().asFile.absolutePath,
                "--bundle", supportBundle.get().asFile.absolutePath,
                "--output", supportsData.absolutePath,
            ))
        }
        val mechanicsData = temporaryDir.resolve("mechanics.json")
        execOperations.exec {
            commandLine(pythonCommand.get() + listOf(
                generatorFiles.files.single { it.name == "prepare_mechanics.py" }.absolutePath,
                "--archive-page", mechanicsArchivePage.get().asFile.absolutePath,
                "--growth-page", mechanicsGrowthPage.get().asFile.absolutePath,
                "--output", mechanicsData.absolutePath,
            ))
        }
        val growthSimulatorData = temporaryDir.resolve("growth-simulator.json")
        execOperations.exec {
            commandLine(pythonCommand.get() + listOf(
                generatorFiles.files.single { it.name == "prepare_growth_simulator.py" }.absolutePath,
                "--page", growthSimulatorPage.get().asFile.absolutePath,
                "--output", growthSimulatorData.absolutePath,
            ))
        }
        execOperations.exec {
            commandLine(pythonCommand.get() + listOf(
                generatorFiles.files.single { it.name == "build_content_db.py" }.absolutePath,
                "--package", packageDirectory.absolutePath,
                "--output", destination.resolve("wiki/content.db").absolutePath,
                "--record-source", "endings=${endingsData.absolutePath}",
                "--record-source", "planting=${plantingData.absolutePath}",
                "--record-source", "supports=${supportsData.absolutePath}",
                "--record-source", "mechanics=${mechanicsData.absolutePath}",
                "--record-source", "growth-simulator=${growthSimulatorData.absolutePath}",
            ))
        }
    }
}

val generateWikiAssets = tasks.register<GenerateWikiAssets>("generateWikiAssets") {
    sourcePackage.set(rootProject.layout.projectDirectory.dir("data/derived/tencent-sheet"))
    generatorFiles.from(rootProject.files(
        "tools/tencent-sheet/build_content_db.py",
        "tools/tencent-sheet/wiki-adapters.json",
        "tools/fireemblem-top/prepare_endings.py",
        "tools/fireemblem-top/prepare_planting.py",
        "tools/fireemblem-top/prepare_supports.py",
        "tools/fireemblem-top/prepare_mechanics.py",
        "tools/fireemblem-top/prepare_growth_simulator.py",
    ))
    endingPage.set(rootProject.layout.projectDirectory.file(
        "data/research/site-plan-audit/endings-2026-11-18/page.html",
    ))
    endingBundle.set(rootProject.layout.projectDirectory.file(
        "data/research/site-plan-audit/endings-2026-11-18/page.bundle.js",
    ))
    plantingPage.set(rootProject.layout.projectDirectory.file("data/research/site-plan-audit/database-planting.html"))
    supportPage.set(rootProject.layout.projectDirectory.file(
        "data/research/site-plan-audit/supports-2026-11-24/page.html",
    ))
    supportBundle.set(rootProject.layout.projectDirectory.file(
        "data/research/site-plan-audit/supports-2026-11-24/page.bundle.js",
    ))
    mechanicsArchivePage.set(rootProject.layout.projectDirectory.file(
        "data/research/site-plan-audit/archive-mechanics.html",
    ))
    mechanicsGrowthPage.set(rootProject.layout.projectDirectory.file(
        "data/research/site-plan-audit/topics-growth-mechanics.html",
    ))
    growthSimulatorPage.set(rootProject.layout.projectDirectory.file(
        "data/research/site-plan-audit/growth-simulator.html",
    ))
    pythonCommand.set(if (System.getProperty("os.name").startsWith("Windows")) listOf("py", "-3") else listOf("python3"))
    outputDirectory.set(layout.buildDirectory.dir("generated/wiki-assets"))
}

abstract class GenerateMapAssets @Inject constructor(
    private val execOperations: ExecOperations,
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourcePackage: DirectoryProperty
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatorFiles: ConfigurableFileCollection
    @get:Input
    abstract val pythonCommand: ListProperty<String>
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
    @TaskAction
    fun generate() {
        val source = sourcePackage.get().asFile
        val destination = outputDirectory.get().asFile.resolve("world-map")
        fileSystemOperations.copy {
            from(source.resolve("downloaded-bundle/world-terrain-watermarked-4608-20261029.webp"))
            into(destination)
            rename { "terrain.webp" }
        }
        execOperations.exec {
            commandLine(pythonCommand.get() + listOf(generatorFiles.singleFile.absolutePath, "--source", source.absolutePath,
                "--output", destination.resolve("maps.db").absolutePath))
        }
        destination.resolve("maps.report.json").delete()
    }
}

val generateMapAssets = tasks.register<GenerateMapAssets>("generateMapAssets") {
    sourcePackage.set(rootProject.layout.projectDirectory.dir("data/fireemblem-top/world-map"))
    generatorFiles.from(rootProject.files("tools/world-map/build_map_db.py"))
    pythonCommand.set(if (System.getProperty("os.name").startsWith("Windows")) listOf("py", "-3") else listOf("python3"))
    outputDirectory.set(layout.buildDirectory.dir("generated/map-assets"))
}

abstract class GenerateBuddyVisualAssets @Inject constructor(private val fileSystemOperations: FileSystemOperations) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
    @TaskAction fun generate() {
        check(sourceDirectory.file("wiki/buddy/index.json").get().asFile.isFile) {
            "Run tools/buddy/prepare_visual_assets.py before building: pinned Buddy portraits are required."
        }
        fileSystemOperations.sync { from(sourceDirectory); into(outputDirectory) }
    }
}

val generateBuddyVisualAssets = tasks.register<GenerateBuddyVisualAssets>("generateBuddyVisualAssets") {
    sourceDirectory.set(rootProject.layout.projectDirectory.dir("data/derived/buddy-visuals"))
    outputDirectory.set(layout.buildDirectory.dir("generated/buddy-visual-assets"))
}

abstract class GenerateMultiGameAssets @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatorFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDatabases: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val engageAssetsDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val threeHousesAssetsDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val threeHousesCharacterStats: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val threeHousesSkillsComplete: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val threeHousesClassesComplete: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val threeHousesBattalionsComplete: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val threeHousesSupportsZh: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val threeHousesAcademySocialZh: RegularFileProperty

    @get:Input
    abstract val gamesRootPath: Property<String>

    @get:Input
    abstract val pythonCommand: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        execOperations.exec {
            commandLine(pythonCommand.get() + listOf(
                generatorFiles.files.single { it.name == "prepare_android_pack.py" }.absolutePath,
                "--games-root", gamesRootPath.get(),
                "--output-root", outputDirectory.get().asFile.resolve("games").absolutePath,
                "--max-image-dim", "192",
            ))
        }
    }
}

val generateMultiGameAssets = tasks.register<GenerateMultiGameAssets>("generateMultiGameAssets") {
    generatorFiles.from(rootProject.files(
        "tools/game-data/prepare_android_pack.py",
        "tools/game-data/zh_localization.py",
        "tools/game-data/th_skill_zh.py",
    ))
    sourceDatabases.from(rootProject.files(
        "data/games/engage/database/content.db",
        "data/games/three-houses/database/content.db",
    ))
    engageAssetsDirectory.set(rootProject.layout.projectDirectory.dir("data/games/engage/assets"))
    threeHousesAssetsDirectory.set(rootProject.layout.projectDirectory.dir("data/games/three-houses/assets"))
    threeHousesCharacterStats.set(rootProject.layout.projectDirectory.file("data/games/three-houses/sources/extracts/character-stats.json"))
    threeHousesSkillsComplete.set(rootProject.layout.projectDirectory.file("data/games/three-houses/sources/extracts/three-houses-skills-complete.json"))
    threeHousesClassesComplete.set(rootProject.layout.projectDirectory.file("data/games/three-houses/sources/extracts/three-houses-classes-complete.json"))
    threeHousesBattalionsComplete.set(rootProject.layout.projectDirectory.file("data/games/three-houses/sources/extracts/three-houses-battalions-complete.json"))
    threeHousesSupportsZh.set(rootProject.layout.projectDirectory.file("data/games/three-houses/sources/extracts/three-houses-supports-zh.json"))
    threeHousesAcademySocialZh.set(rootProject.layout.projectDirectory.file("data/games/three-houses/sources/extracts/three-houses-academy-social-zh.json"))
    gamesRootPath.set(rootProject.layout.projectDirectory.dir("data/games").asFile.absolutePath)
    pythonCommand.set(if (System.getProperty("os.name").startsWith("Windows")) listOf("py", "-3") else listOf("python3"))
    outputDirectory.set(layout.buildDirectory.dir("generated/multi-game-assets"))
}

val prebuiltAssetsDirPath: String? = providers.gradleProperty("prebuiltAssetsDir")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

val resolvedVersionCode: Int = providers.gradleProperty("versionCodeOverride")
    .orNull
    ?.trim()
    ?.toIntOrNull()
    ?: 2

val resolvedVersionName: String = providers.gradleProperty("versionNameOverride")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: "0.2.0"

val releaseKeystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val releaseKeystorePassword: String? = System.getenv("ANDROID_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias: String? = System.getenv("ANDROID_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword: String? = System.getenv("ANDROID_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val hasReleaseSigning: Boolean = releaseKeystorePath != null &&
    releaseKeystorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

android {
    namespace = "com.fireemblem.companion"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fireemblem.companion"
        minSdk = 26
        targetSdk = 36
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    if (prebuiltAssetsDirPath != null) {
        val prebuiltDir = file(prebuiltAssetsDirPath)
        val requiredFiles = listOf(
            "wiki/content.db",
            "wiki/buddy/index.json",
            "world-map/maps.db",
            "world-map/terrain.webp",
            "games/three-houses/pack.db",
            "games/engage/pack.db",
        )
        requiredFiles.forEach { rel ->
            check(prebuiltDir.resolve(rel).isFile) {
                "Prebuilt assets directory is missing required file: $rel (dir=$prebuiltDir)"
            }
        }
        sourceSets.getByName("main").assets.srcDir(prebuiltDir)
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
                "META-INF/io.netty.versions.properties",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

if (prebuiltAssetsDirPath == null) {
    androidComponents.onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(generateBuddyVisualAssets, GenerateBuddyVisualAssets::outputDirectory)
        variant.sources.assets?.addGeneratedSourceDirectory(generateWikiAssets, GenerateWikiAssets::outputDirectory)
        variant.sources.assets?.addGeneratedSourceDirectory(generateMapAssets, GenerateMapAssets::outputDirectory)
        variant.sources.assets?.addGeneratedSourceDirectory(generateMultiGameAssets, GenerateMultiGameAssets::outputDirectory)
    }
}

dependencies {
    implementation(project(":core-domain"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.anthropic:anthropic-java:2.34.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.32.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}
