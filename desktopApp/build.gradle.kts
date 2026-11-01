import dev.nucleusframework.desktop.application.dsl.NativeImageMarch
import dev.nucleusframework.desktop.application.dsl.NativeImageOptimization
import dev.nucleusframework.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.nucleus)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.nucleus.application)
    implementation(libs.nucleus.graalvm.runtime)
    implementation(libs.nucleus.decorated.window.tao)
    implementation(libs.jna)
    implementation(libs.miuix.ui)
    implementation(libs.androidx.lifecycle.runtimeCompose)

    implementation(libs.compose.uiToolingPreview)
    testImplementation(libs.kotlin.test)
}

// Hot Reload copies runtime JARs into build/run without their adjacent DLLs.
// Apply to JavaExec so IDE-injected hotRun/MainKt tasks get the same location
// as ordinary Gradle run tasks. Explicit local overrides retain precedence.
val developmentTdlibDirectory = providers.systemProperty("nnggram.tdlib.dir")
    .orElse(providers.environmentVariable("NNGGRAM_TDLIB_DIR"))
    .orElse(rootProject.layout.projectDirectory.dir("native/windows-x64").asFile.absolutePath)
tasks.withType<JavaExec>().configureEach {
    systemProperty("nnggram.tdlib.dir", developmentTdlibDirectory.get())
}

nucleus {
    application {
        mainClass = "com.badnng.moe.MainKt"

        // TDLib discovers generated Java types from JNI. Keep their exact names/members;
        // Native Image performs closed-world elimination after JNI metadata is applied.
        buildTypes.release.proguard.isEnabled = false
        graalvm {
            isEnabled = true
            imageName = "nngGram"
            march = NativeImageMarch.COMPATIBILITY
            optimization = NativeImageOptimization.SIZE
            // JNA reads native.encoding (GBK on Chinese Windows), independently of UTF-8 UI text.
            allCharsets = true
            buildArgs.add("-H:-IncludeMethodData")
            providers.gradleProperty("nativeImageHeap").orNull?.let { buildArgs.add("-J-Xmx$it") }
            providers.gradleProperty("nativeImageThreads").orNull?.let { buildArgs.add("--parallelism=$it") }
        }

        nativeDistributions {
            appResourcesRootDir.set(rootProject.layout.projectDirectory.dir("native"))
            // Required by the desktop dependencies; verified with suggestRuntimeModules.
            modules("java.instrument", "jdk.unsupported")
            targetFormats(TargetFormat.Msi)
            packageName = "nngGram"
            packageVersion = providers.gradleProperty("nnggramVersion").get()
        }
    }
}

// Nucleus 2.5.18's Windows Native Image packaging copies Skiko but leaves Tao/ANGLE
// inside dependency JARs. Ship the exact Windows x64 libraries beside Skiko so
// ANGLE's module-relative libGLESv2 lookup works without a per-user extraction cache.
val copyGraalvmWindowsRuntimeLibraries by tasks.registering(Copy::class) {
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.map { files ->
        files.filter { it.extension == "jar" }.map { zipTree(it) }
    }) {
        include("nucleus/native/win32-x64/*.dll", "com/sun/jna/win32-x86-64/jnidispatch.dll")
        eachFile { path = name }
        includeEmptyDirs = false
    }
    duplicatesStrategy = DuplicatesStrategy.FAIL
    into(layout.buildDirectory.dir("compose/binaries/main/graalvm-app/nngGram/bin"))
}
tasks.matching { it.name == "packageGraalvmNative" }.configureEach {
    dependsOn(copyGraalvmWindowsRuntimeLibraries)
}

// Nucleus 2.5.18 only searches Program Files for rc.exe. Portable SDKs keep their
// own location: retain Nucleus's generated icon/version/DPI manifest, then compile
// that exact resource script with the explicitly selected SDK if discovery fails.
val portableWindowsRc = providers.gradleProperty("windowsRc")
if (portableWindowsRc.isPresent) {
    afterEvaluate {
        tasks.named("generateGraalvmWindowsResources").configure {
            inputs.file(portableWindowsRc)
            val nucleusActions = actions.toList()
            actions.clear()
            doLast {
                try {
                    nucleusActions.forEach { it.execute(this) }
                } catch (failure: IllegalStateException) {
                    if (failure.message != "Could not locate rc.exe from Windows SDK. Ensure Windows SDK is installed.") throw failure
                    val resource = outputs.files.singleFile
                    val source = File(resource.parentFile, "icon.rc")
                    check(source.isFile) { "Nucleus did not generate the Windows resource script." }
                    val result = ProcessBuilder(portableWindowsRc.get(), "/fo", resource.absolutePath, source.absolutePath)
                        .inheritIO().start().waitFor()
                    check(result == 0 && resource.isFile) { "Windows resource compiler failed ($result)." }
                }
            }
        }
    }
}
