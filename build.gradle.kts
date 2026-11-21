plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "com.aipilot"
version = "0.1.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

javafx {
    version = "21.0.2"
    modules("javafx.controls", "javafx.fxml")
}

tasks.named<JavaExec>("run") {
    jvmArgs(
        "-Dfile.encoding=UTF-8",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8",
        "-Dprism.lcdtext=false",
    )
    if (System.getProperty("os.name").lowercase().contains("linux")) {
        jvmArgs(
            "-Djdk.gtk.version=3",
            "-Dprism.order=es2,sw",
            "-Dawt.toolkit.name=XToolkit",
        )
        environment("GDK_BACKEND", System.getenv("GDK_BACKEND") ?: "x11")
    }
    if (System.getProperty("os.name").lowercase().contains("win")) {
        jvmArgs("-Dprism.lcdtext=false")
    }
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.8")
    implementation("com.hierynomus:sshj:0.38.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
}

application {
    mainClass.set("com.aipilot.AppMain")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveFileName.set("aipilot.jar")
    manifest {
        attributes["Main-Class"] = "com.aipilot.AppMain"
        attributes["Implementation-Version"] = version
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .filter { !it.name.startsWith("javafx-") }
            .map { zipTree(it) }
    })
    exclude(
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "module-info.class",
        "javafx/**",
        "com/sun/javafx/**",
        "com/sun/glass/**",
        "com/sun/prism/**",
        "com/sun/scenario/**",
        "com/sun/webkit/**",
    )
}

tasks.register("writeVersionTxt") {
    val out = layout.buildDirectory.file("libs/version.txt")
    outputs.file(out)
    doLast {
        out.get().asFile.parentFile.mkdirs()
        out.get().asFile.writeText(version.toString() + "\n")
    }
}

tasks.processResources {
    val ver = version.toString()
    filesMatching("version.txt") {
        filter { ver }
    }
}

tasks.register("packageApp") {
    group = "build"
    description = "Package native app-image (dist/AIPilot) via jpackage for the current OS"
    dependsOn(tasks.jar, "writeVersionTxt")

    val destDir = layout.projectDirectory.dir("dist")
    val appImageDir = destDir.dir("AIPilot")
    val exeFile = appImageDir.file("AIPilot.exe")
    val jpackageInput = layout.buildDirectory.dir("jpackage-input")
    val jarFile = layout.buildDirectory.file("libs/aipilot.jar")

    inputs.files(jarFile, layout.buildDirectory.file("libs/version.txt"))
    outputs.dir(appImageDir)

    doLast {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val jpackageName = if (isWindows) "jpackage.exe" else "jpackage"

        fun jdkHome(): java.io.File {
            val env = System.getenv("JAVA_HOME")?.let { file(it) }
            val candidates = listOfNotNull(
                file(".tmp-jdk21/jdk-21.0.12+8"),
                file("E:/projects/platform1/untitled/.tmp-jdk21/jdk-21.0.12+8"),
                file("../platform1/untitled/.tmp-jdk21/jdk-21.0.12+8"),
                env,
            )
            return candidates.firstOrNull { it.resolve("bin/$jpackageName").isFile }
                ?: file(System.getProperty("java.home"))
        }

        val home = jdkHome()
        val jpackage = home?.resolve("bin/$jpackageName")
        if (jpackage == null || !jpackage.isFile) {
            throw GradleException("jpackage not found. Set JAVA_HOME to a full JDK 17–21 with jpackage.")
        }

        val inputDir = jpackageInput.get().asFile
        delete(inputDir)
        inputDir.mkdirs()
        copy {
            from(jarFile)
            into(inputDir)
        }

        destDir.asFile.mkdirs()
        val existing = appImageDir.asFile

        fun clearReadonly(file: File) {
            if (!file.exists()) return
            file.setWritable(true)
        }

        fun stopWindowsApp() {
            if (!isWindows) return
            try {
                exec {
                    commandLine("taskkill", "/F", "/IM", "AIPilot.exe", "/T")
                    isIgnoreExitValue = true
                }
                val prefix = existing.absolutePath.replace("'", "''")
                val ps = (
                    "\$p = '$prefix\\'; " +
                        "Get-CimInstance Win32_Process | Where-Object { " +
                        "\$_.ExecutablePath -and \$_.ExecutablePath.StartsWith(\$p, " +
                        "[System.StringComparison]::OrdinalIgnoreCase) " +
                        "} | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }"
                    )
                exec {
                    commandLine("powershell", "-NoProfile", "-Command", ps)
                    isIgnoreExitValue = true
                }
                Thread.sleep(2000)
            } catch (_: Exception) {
            }
        }

        fun mergeAppImage(built: File, target: File) {
            if (!target.exists()) {
                if (!built.renameTo(target)) {
                    built.copyRecursively(target, overwrite = true)
                    delete(built)
                }
                return
            }
            target.walkBottomUp().forEach { f ->
                if (f == target) return@forEach
                if (f.name.equals("debug.log", ignoreCase = true)) return@forEach
                clearReadonly(f)
                try {
                    if (f.isDirectory) f.deleteRecursively() else f.delete()
                } catch (_: Exception) {
                }
            }
            built.copyRecursively(target, overwrite = true)
            delete(built)
        }

        fun safeRemoveAppImage(dir: File): Boolean {
            val logFile = dir.resolve("debug.log")
            if (logFile.isFile) {
                clearReadonly(logFile)
                logFile.delete()
            }
            if (!dir.exists()) return true
            try {
                delete(dir)
                if (!dir.exists()) return true
            } catch (_: Exception) {
            }
            val aside = File(dir.parentFile, dir.name + ".old." + System.currentTimeMillis())
            if (dir.renameTo(aside)) {
                try {
                    delete(aside)
                } catch (_: Exception) {
                }
                return true
            }
            dir.walkBottomUp().forEach { f ->
                clearReadonly(f)
                try {
                    f.delete()
                } catch (_: Exception) {
                }
            }
            return !dir.exists()
        }

        val stagingRoot = File(destDir.asFile, "_jpackage_staging")
        delete(stagingRoot)
        stagingRoot.mkdirs()

        val iconFile = rootProject.file("assets/icons/aipilot.ico")
        val iconPng = rootProject.file("assets/icons/aipilot.png")
        val javafxJars = configurations.runtimeClasspath.get()
            .filter { it.name.startsWith("javafx-") && it.extension == "jar" }
        val fxModules =
            "javafx.controls,javafx.fxml,javafx.graphics,javafx.base"
        val jdkModules =
            "java.desktop,java.datatransfer,java.net.http,java.logging,java.xml," +
                "java.management,java.naming,java.prefs,java.scripting,java.instrument," +
                "jdk.crypto.ec,jdk.httpserver,jdk.charsets,jdk.localedata,jdk.unsupported," +
                "jdk.unsupported.desktop,jdk.jsobject,jdk.xml.dom,jdk.zipfs"
        val cmd = mutableListOf(
            jpackage.absolutePath,
            "--type", "app-image",
            "--name", "AIPilot",
            "--app-version", version.toString(),
            "--description", "AIPilot desktop agent",
            "--vendor", "AIPilot",
            "--input", inputDir.absolutePath,
            "--main-jar", "aipilot.jar",
            "--main-class", "com.aipilot.AppMain",
            "--dest", stagingRoot.absolutePath,
            "--java-options", "-Dfile.encoding=UTF-8",
            "--java-options", "-Dprism.lcdtext=false",
            "--jlink-options",
            "--strip-debug --no-man-pages --no-header-files",
        )
        if (isWindows) {
            cmd.addAll(
                listOf(
                    "--java-options", "--module-path=\$APPDIR\\javafx",
                    "--java-options", "--add-modules=$fxModules",
                    "--add-modules", jdkModules,
                ),
            )
        } else {
            cmd.addAll(
                listOf(
                    "--java-options", "-Djdk.gtk.version=3",
                    "--java-options", "-Dprism.order=es2,sw",
                    "--java-options", "-Dawt.toolkit.name=XToolkit",
                    "--java-options", "--module-path=\$APPDIR/javafx",
                    "--java-options", "--add-modules=$fxModules",
                    "--add-modules", jdkModules,
                ),
            )
        }
        val iconForJpackage = when {
            isWindows && iconFile.isFile -> iconFile
            !isWindows && iconPng.isFile -> iconPng
            iconFile.isFile -> iconFile
            else -> null
        }
        if (iconForJpackage != null) {
            cmd.addAll(listOf("--icon", iconForJpackage.absolutePath))
        }
        exec {
            commandLine(cmd)
        }

        val builtImage = File(stagingRoot, "AIPilot")
        if (!builtImage.isDirectory) {
            throw GradleException("jpackage did not produce ${builtImage.absolutePath}")
        }

        stopWindowsApp()
        var installed = false
        if (existing.exists() && !safeRemoveAppImage(existing)) {
            val aside = File(existing.parentFile, "AIPilot.old." + System.currentTimeMillis())
            installed = existing.renameTo(aside) && builtImage.renameTo(existing)
        } else {
            installed = builtImage.renameTo(existing)
        }
        if (!installed) {
            mergeAppImage(builtImage, existing)
        }
        delete(stagingRoot)

        if (!exeFile.asFile.isFile && isWindows) {
            throw GradleException("jpackage did not produce ${exeFile.asFile}")
        }
        val linuxBin = appImageDir.file("bin/AIPilot").asFile
        if (!isWindows) {
            val image = appImageDir.asFile
            val appDir = File(image, "lib/app")
            val fxDest = if (appDir.isDirectory) File(appDir, "javafx") else File(image, "lib/javafx")
            fxDest.mkdirs()
            javafxJars.forEach { jar ->
                jar.copyTo(File(fxDest, jar.name), overwrite = true)
            }
            val wrapperSrc = rootProject.file("tools/aipilot-linux-wrapper.sh")
            val nativeBin = File(image, "bin/AIPilot.bin")
            if (linuxBin.isFile) {
                val header = linuxBin.inputStream().use { it.readNBytes(2) }
                val isScript = header.size >= 2 && header[0] == '#'.code.toByte() && header[1] == '!'.code.toByte()
                if (!isScript) {
                    linuxBin.copyTo(nativeBin, overwrite = true)
                }
            }
            if (!nativeBin.isFile) {
                throw GradleException("jpackage did not produce ${linuxBin}")
            }
            if (wrapperSrc.isFile) {
                linuxBin.writeText(wrapperSrc.readText().replace("\r\n", "\n"))
            }
            linuxBin.setExecutable(true, false)
            nativeBin.setExecutable(true, false)
            val runtimeJava = appImageDir.file("lib/runtime/bin/java").asFile
            if (runtimeJava.isFile) {
                runtimeJava.setExecutable(true, false)
            }
            val runtimeAlt = appImageDir.file("runtime/bin/java").asFile
            if (runtimeAlt.isFile) {
                runtimeAlt.setExecutable(true, false)
            }
        }
        if (isWindows) {
            val image = appImageDir.asFile
            val appDir = File(image, "app")
            if (appDir.isDirectory && !javafxJars.isEmpty) {
                val fxDest = File(appDir, "javafx")
                fxDest.mkdirs()
                javafxJars.forEach { jar ->
                    jar.copyTo(File(fxDest, jar.name), overwrite = true)
                }
                val cfg = File(appDir, "AIPilot.cfg")
                if (cfg.isFile) {
                    val text = cfg.readText()
                    val extra = StringBuilder()
                    if (!text.contains("module-path")) {
                        extra.append("java-options=--module-path=\$APPDIR\\javafx\n")
                    }
                    if (!text.contains("--add-modules=javafx")) {
                        extra.append("java-options=--add-modules=$fxModules\n")
                    }
                    if (extra.isNotEmpty()) {
                        cfg.appendText("\n" + extra)
                    }
                }
            }
        }
        if (iconPng.isFile) {
            copy {
                from(iconPng)
                into(appImageDir.dir("lib"))
                rename { "AIPilot.png" }
            }
        }
        if (iconFile.isFile) {
            copy {
                from(iconFile)
                into(appImageDir)
                rename { "aipilot.ico" }
            }
        }
        val releaseText = appImageDir.file("runtime/release").asFile.let {
            if (it.isFile) it.readText() else ""
        }
        if (!releaseText.contains("java.scripting")) {
            throw GradleException(
                "jpackage runtime is missing java.scripting (FXML). release: ${releaseText.trim()}"
            )
        }
    }
}

tasks.register("packageLinuxApp") {
    group = "build"
    description = "Package Linux app-image (dist/AIPilot/bin/AIPilot) via jpackage"
    dependsOn("packageApp")
}

tasks.register<Exec>("packageInstaller") {
    group = "build"
    description = "Build AIPilot-Setup.exe"
    dependsOn("packageApp")
    workingDir = rootProject.projectDir
    commandLine("python", "tools/build_installer.py", "--preset", "aipilot")
}
