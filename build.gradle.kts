plugins {
    `java-library`
}

group = "dev.thathunky"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") // Paper API
}

// The jar is compiled against the oldest supported API, so it can only call what 1.21.4 already
// has; `compatCompile` below compiles the same sources against every newer line to catch removals.
val floorApi = "io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT"

// Every supported line, checked by `compatCompile`. 26.2 is pinned to the build Matsuri runs.
val compatApis = mapOf(
    "1.21.4" to "1.21.4-R0.1-SNAPSHOT",
    "1.21.8" to "1.21.8-R0.1-SNAPSHOT",
    "1.21.11" to "1.21.11-R0.1-SNAPSHOT",
    "26.1.2" to "26.1.2.build.74-stable",
    "26.2" to "26.2.build.121-stable",
    "26.3" to "26.3.build.30-alpha",
)

dependencies {
    compileOnly(floorApi)
    // TestMain needs paper-api (YamlConfiguration, NamespacedKey) at runtime too.
    testImplementation(floorApi)
}

sourceSets {
    main {
        resources {
            srcDir(rootDir)
            include("plugin.yml", "config.yml", "lang/**")
        }
    }
}

// Java 21 bytecode: 1.21.x servers run on Java 21, 26.x on Java 25.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

val compatCompile = tasks.register("compatCompile") {
    group = "verification"
    description = "Compiles the plugin sources against every supported paper-api line."
}

compatApis.forEach { (line, apiVersion) ->
    val id = line.replace('.', '_')
    val conf = configurations.create("compatApi$id") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    dependencies.add(conf.name, "io.papermc.paper:paper-api:$apiVersion")
    val task = tasks.register<JavaCompile>("compileAgainst$id") {
        group = "verification"
        description = "Compiles src/main/java against paper-api $apiVersion."
        source = fileTree("src/main/java")
        classpath = conf
        destinationDirectory.set(layout.buildDirectory.dir("compat/$line"))
        javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    }
    compatCompile.configure { dependsOn(task) }
}

// Compiling proves each line has the methods by source; this proves the jar built against 1.21.4 links
// the same way on every line: the API members the bytecode references (owner, name, descriptor, and
// whether the owner is a class or an interface) must be identical in every compat build.
val compatLinkage = tasks.register("compatLinkage") {
    group = "verification"
    description = "Checks every compat build references exactly the same API members as the 1.21.4 build."
    dependsOn(compatCompile)
    val javap = javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(25)) }
        .map { it.metadata.installationPath.file("bin/javap").asFile }
    val compatDir = layout.buildDirectory.dir("compat")
    val lines = compatApis.keys.toList()
    doLast {
        fun refs(dir: File): Set<String> {
            val classes = dir.walk().filter { it.isFile && it.extension == "class" }.map { it.absolutePath }.sorted().toList()
            val process = ProcessBuilder(listOf(javap.get().absolutePath, "-v") + classes).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readLines()
            if (process.waitFor() != 0) throw GradleException("javap failed on $dir")
            return out.filter { Regex("= (Methodref|Fieldref|InterfaceMethodref)").containsMatchIn(it) }
                .map { it.substringAfter("//").trim() }
                .filter { Regex("(org/bukkit|io/papermc|net/kyori)/").containsMatchIn(it) }
                .toSortedSet()
        }
        val floor = refs(compatDir.get().dir(lines.first()).asFile)
        for (line in lines.drop(1)) {
            val other = refs(compatDir.get().dir(line).asFile)
            if (other != floor) {
                throw GradleException("API references differ between ${lines.first()} and $line:\n  only in ${lines.first()}: ${floor - other}\n  only in $line: ${other - floor}")
            }
        }
        println("compatLinkage: ${floor.size} API references, identical on ${lines.joinToString()}")
    }
}

// TestMain is a plain main() with System.exit(1) on failure, run as a JavaExec; a non-zero exit fails
// this task and with it `check` and `build`, exactly like build.sh's run of the same class.
val runTests = tasks.register<JavaExec>("runTests") {
    group = "verification"
    description = "Runs the server-free TestMain checks (same checks build.sh runs)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.thathunky.solstice.TestMain")
    workingDir = rootDir
}

tasks.named<Test>("test") {
    enabled = false
}

tasks.named("check") {
    dependsOn(runTests, compatCompile, compatLinkage)
}
