package to.bitkit.build

import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `release build resolves every rust symbol archive`() {
        val buildFile = repoRoot.resolve("app/build.gradle.kts").readText()

        listOf(
            "libs.bitkit.core",
            "libs.ldk.node.android",
            "libs.paykit",
            "libs.vss.client",
        ).forEach {
            assertTrue(
                buildFile.contains("nativeDebugSymbols($it.nativeDebugSymbolsArtifact())"),
                "Release builds must resolve the '$it' native debug symbols classifier.",
            )
        }
    }

    @Test
    fun `release recipe verifies native debug symbols archive`() {
        val justfile = repoRoot.resolve("Justfile").readText()
        val internalReleaseWorkflow = repoRoot.resolve(".github/workflows/release-internal.yml").readText()

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
        assertTrue(
            internalReleaseWorkflow.contains("assembleMainnetRelease bundleMainnetRelease"),
            "Internal releases must build both app artifacts before native build-ID validation.",
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
                """required_libs="libbitkitcore.so libldk_node.so libpaykit.so libvss_rust_client_ffi.so"""",
            ),
            "Native debug symbols script must validate release-critical native libraries.",
        )
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

    @Test
    fun `native debug symbols script validates packaged build id parity`() {
        val symbolsScript = repoRoot.resolve("scripts/create-native-debug-symbols.sh").readText()

        assertTrue(
            symbolsScript.contains("NT_GNU_BUILD_ID") &&
                symbolsScript.contains("Build ID:") &&
                symbolsScript.contains("validate_packaged_build_id_parity"),
            "Native debug symbols script must require build IDs and packaged-to-symbol parity.",
        )
        assertTrue(
            symbolsScript.contains("""aab_entry="base/lib/${'$'}abi/${'$'}lib_name"""") &&
                symbolsScript.contains("""apk_entry="lib/${'$'}abi/${'$'}lib_name""""),
            "Native debug symbols script must inspect final AAB and APK native libraries.",
        )
    }

    @Test
    fun `native debug symbols fixture rejects invalid build ids and validates both app artifacts`() {
        val process = ProcessBuilder(
            "sh",
            repoRoot.resolve("scripts/test-create-native-debug-symbols.sh").toString(),
        )
            .directory(repoRoot.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }

        assertEquals(
            0,
            process.waitFor(),
            "Native debug symbol fixture validation failed:\n$output",
        )
        assertTrue(output.contains("Native debug symbol fixture validation passed."))
    }

    private fun assertBuildNumberedArchiveOutput(symbolsScript: String) {
        assertTrue(
            symbolsScript.contains(
                "${'$'}artifact_root/native-debug-symbols/${'$'}variant/native-debug-symbols-${'$'}build_number.zip",
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
}
