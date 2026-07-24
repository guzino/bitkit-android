package to.bitkit.build

import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeReleaseConfigTest {

    private val repoRoot = generateSequence(
        Path(requireNotNull(System.getProperty("user.dir")) { "user.dir is required" }),
    ) { it.parent }
        .first { it.resolve("gradle/libs.versions.toml").exists() }

    @Test
    fun `release build requests full native debug symbols`() {
        val buildFile = repoRoot.resolve("app/build.gradle.kts").readText()

        assertTrue(
            buildFile.contains("""debugSymbolLevel = "FULL""""),
            "Release builds must request full native debug symbols for Play crash symbolication.",
        )
    }

    @Test
    fun `vendored native licenses are packaged with application assets`() {
        val buildFile = repoRoot.resolve("app/build.gradle.kts").readText()
        val androidxLicense = repoRoot.resolve("app/src/main/cpp/third_party/androidx/LICENSE")
        val libyuvLicense = repoRoot.resolve("app/src/main/cpp/third_party/libyuv/LICENSE")
        val libyuvPatents = repoRoot.resolve("app/src/main/cpp/third_party/libyuv/PATENTS")
        val generatedLicenses = repoRoot.resolve("app/build/generated/third-party-licenses/third_party")
        val generatedAndroidxLicense = generatedLicenses.resolve("androidx/LICENSE")
        val generatedLibyuvLicense = generatedLicenses.resolve("libyuv/LICENSE")
        val generatedLibyuvPatents = generatedLicenses.resolve("libyuv/PATENTS")

        assertTrue(androidxLicense.exists(), "The vendored AndroidX source must include its Apache 2.0 license.")
        val licenseText = androidxLicense.readText()
        assertTrue(
            licenseText.contains("Apache License") &&
                licenseText.contains("Version 2.0, January 2004"),
            "The vendored AndroidX license must contain the complete Apache 2.0 license text.",
        )
        assertTrue(
            libyuvLicense.exists() && libyuvPatents.exists(),
            "The statically linked libyuv source must include its BSD notice and patent grant.",
        )
        assertTrue(
            buildFile.contains("packageThirdPartyLicenses") &&
                buildFile.contains("""into("third_party/androidx")""") &&
                buildFile.contains("""into("third_party/libyuv")""") &&
                buildFile.contains("src/main/cpp/third_party/libyuv/LICENSE") &&
                buildFile.contains("src/main/cpp/third_party/libyuv/PATENTS") &&
                buildFile.contains("dependsOn(packageThirdPartyLicenses)"),
            "Android builds must package the AndroidX and libyuv notices as application assets.",
        )
        assertTrue(
            generatedAndroidxLicense.exists() &&
                generatedAndroidxLicense.readText() == androidxLicense.readText(),
            "The generated AndroidX application asset must be byte-equivalent to its tracked license.",
        )
        assertTrue(
            generatedLibyuvLicense.exists() &&
                generatedLibyuvLicense.readText() == libyuvLicense.readText() &&
                generatedLibyuvPatents.exists() &&
                generatedLibyuvPatents.readText() == libyuvPatents.readText(),
            "The generated libyuv application assets must be byte-equivalent to their tracked notices.",
        )
    }

    @Test
    fun `release recipe verifies native debug symbols archive`() {
        val justfile = repoRoot.resolve("Justfile").readText()

        assertTrue(
            justfile.contains(
                """rm -f "${'$'}symbols_dir"/native-debug-symbols*.zip""",
            ),
            "Release builds must remove stale native debug symbols before rebuilding.",
        )
        assertTrue(
            justfile.contains("scripts/create-native-debug-symbols.sh"),
            "Release builds must create the native debug symbols archive before publishing.",
        )
        assertTrue(
            justfile.contains(":app:syncNativeDebugSymbolArtifacts"),
            "Release builds must resolve upstream native debug symbol artifacts before publishing.",
        )
        assertTrue(
            justfile.contains("Attach this exact file to GitHub releases"),
            "Release builds must tell the releaser to attach native debug symbols.",
        )
        assertTrue(
            justfile.contains("upload it to Play Console for this release"),
            "Release builds must tell the releaser to upload native debug symbols to Play.",
        )
        assertTrue(
            justfile.contains("syncNativeDebugSymbolArtifacts"),
            "Release builds should download native dependency symbols from release artifacts.",
        )
    }

    @Test
    fun `release command uploads native debug symbols archive`() {
        val releaseCommand = repoRoot.resolve(".agents/commands/release.md").readText()

        assertTrue(
            releaseCommand.contains(
                "app/build/outputs/native-debug-symbols/mainnetRelease/native-debug-symbols-{newVersionCode}.zip",
            ),
            "Release command must include the native debug symbols archive path.",
        )
        assertTrue(
            releaseCommand.contains("Native debug symbols uploaded: native-debug-symbols-{newVersionCode}.zip"),
            "Release command summary must report the native debug symbols archive.",
        )
        assertFalse(
            releaseCommand.contains("Play " + "did not"),
            "Release command should use current Play native symbol wording.",
        )
        assertTrue(
            releaseCommand.contains("resolves upstream native debug symbol artifacts"),
            "Release command must document upstream native debug symbol artifact resolution.",
        )
        assertTrue(
            releaseCommand.contains("Play Console may only show delete/replace controls"),
            "Release command must document the verified Play Console behavior.",
        )
    }

    @Test
    fun `native debug symbols script rejects stripped release libraries`() {
        val symbolsScript = repoRoot.resolve("scripts/create-native-debug-symbols.sh").readText()

        assertBuildNumberedArchiveOutput(symbolsScript)
        assertTrue(
            symbolsScript.contains("native-debug-symbol-artifacts"),
            "Native debug symbols script must use upstream native dependency symbol archives.",
        )
        assertTrue(
            symbolsScript.contains("arm64-v8a armeabi-v7a"),
            "Native debug symbols script must archive Play release ABIs.",
        )
        assertTrue(
            symbolsScript.contains("zip -qr"),
            "Native debug symbols script must create a zip archive.",
        )
        assertTrue(
            symbolsScript.contains(
                """dependency_required_libs="libbitkitcore.so libldk_node.so libvss_rust_client_ffi.so"""",
            ),
            "Native debug symbols script must merge release-critical dependency symbols.",
        )
        assertApplicationSymbolsAreMerged(symbolsScript)
        assertTrue(
            symbolsScript.contains("""archive_symbol_suffixes=".dbg .sym""""),
            "Native debug symbols script must accept AGP native debug symbol entry suffixes.",
        )
        assertDependencyArchiveEntriesAreNormalized(symbolsScript)
        assertTrue(
            symbolsScript.contains("""grep -Eq '\.debug_info'"""),
            "Native debug symbols script must validate full DWARF debug metadata before zipping.",
        )
        assertTrue(
            symbolsScript.contains("ANDROID_NDK_ROOT"),
            "Native debug symbols script must use the same NDK env paths Gradle can use.",
        )
        assertTrue(
            symbolsScript.contains("local.properties") &&
                symbolsScript.contains("ndk.dir") &&
                symbolsScript.contains("sdk.dir"),
            "Native debug symbols script must use local.properties NDK/SDK paths before PATH fallback.",
        )
        assertFalse(
            symbolsScript.contains("symtab|debug_|gnu_debugdata"),
            "Native debug symbols script must not accept symbol-table-only metadata for FULL symbols.",
        )
        assertTrue(
            symbolsScript.contains("Refusing to create '${'$'}output' from stripped native libraries."),
            "Native debug symbols script must refuse placeholder archives.",
        )
        assertTrue(
            symbolsScript.contains("syncNativeDebugSymbolArtifacts"),
            "Native debug symbols script must point to the Gradle task that resolves symbol artifacts.",
        )
    }

    private fun assertBuildNumberedArchiveOutput(symbolsScript: String) {
        assertTrue(
            symbolsScript.contains(
                "app/build/outputs/native-debug-symbols/${'$'}variant/native-debug-symbols-${'$'}build_number.zip",
            ),
            "Native debug symbols script must write the build-numbered archive path.",
        )
        assertTrue(
            symbolsScript.contains("""rm -f "${'$'}output_dir"/native-debug-symbols*.zip"""),
            "Native debug symbols script must clear stale build-numbered archives before writing.",
        )
    }

    private fun assertDependencyArchiveEntriesAreNormalized(symbolsScript: String) {
        assertTrue(
            symbolsScript.contains("copy_archive_symbols") &&
                symbolsScript.contains("""mv "${'$'}tmp_dir/${'$'}entry" "${'$'}tmp_dir/${'$'}abi/${'$'}lib_name""""),
            "Native debug symbols script must normalize suffixed dependency archive entries before validation.",
        )
    }

    private fun assertApplicationSymbolsAreMerged(symbolsScript: String) {
        val applicationRequiredLibraries =
            "application_required_libs=\"libimage_processing_util_jni.so " +
                "libsurface_util_jni.so libandroidx.graphics.path.so\""
        assertTrue(
            symbolsScript.contains(applicationRequiredLibraries),
            "Native debug symbols script must preserve symbols for rebuilt application libraries.",
        )
        assertTrue(
            symbolsScript.contains(
                """copy_archive_symbols "${'$'}output" "${'$'}tmp_dir" "${'$'}application_required_libs"""",
            ),
            "Native debug symbols script must merge the AGP archive before replacing it.",
        )
    }
}
