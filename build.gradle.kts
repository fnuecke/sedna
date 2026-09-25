plugins {
    java
    alias(libs.plugins.mavenPublish)
    alias(libs.plugins.jmh)
}

val semver = providers.gradleProperty("semver").get()
val packageGroup = "li.cil.sedna"

fun getGitRef(): String {
    return providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}

version = "${semver}+${getGitRef()}"
group = packageGroup

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

val codegen = sourceSets.create("codegen") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[codegen.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[codegen.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(libs.jsr305)
    testCompileOnly(libs.jsr305)

    implementation(libs.commons.io)
    implementation(libs.fastutil)
    implementation(libs.commons.lang3)
    implementation(libs.log4j.api)

    implementation(libs.ceres)

    codegen.implementationConfigurationName(libs.asm)
    codegen.compileOnlyConfigurationName(libs.jsr305)

    testImplementation(codegen.output)
    testImplementation(libs.ceres.json)
    testImplementation(libs.asm)
    testImplementation(libs.mockito.core)
    testImplementation(libs.junit.api)
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.withType<AbstractArchiveTask>().configureEach {
    archiveVersion.set(semver)
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates(packageGroup, project.name, semver)

    pom {
        name.set("Sedna")
        description.set("A RISC-V emulator written in plain Java.")
        url.set("https://github.com/fnuecke/sedna")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://github.com/fnuecke/sedna/blob/main/LICENSE")
            }
        }
        developers {
            developer {
                id.set("fnuecke")
                name.set("Florian Nücke")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/fnuecke/sedna.git")
            developerConnection.set("scm:git:ssh://git@github.com/fnuecke/sedna.git")
            url.set("https://github.com/fnuecke/sedna")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("generateDecoder") {
    group = "build"
    description = "Regenerates src/main/java/li/cil/sedna/riscv/R5CPUImpl.java from the instruction declarations."
    classpath = codegen.runtimeClasspath
    mainClass.set("li.cil.sedna.riscv.R5CPUImplGenerator")
    workingDir = projectDir
}

tasks.register<JavaExec>("generateZ80Decoder") {
    group = "build"
    description = "Regenerates src/main/java/li/cil/sedna/z80/Z80CPUImpl.java from the instruction declarations."
    classpath = codegen.runtimeClasspath
    mainClass.set("li.cil.sedna.z80.Z80CPUImplGenerator")
    workingDir = projectDir
}

tasks.register<JavaExec>("printDecoderTree") {
    group = "build"
    description = "Prints the RV64 decoder tree."
    classpath = codegen.runtimeClasspath
    mainClass.set("li.cil.sedna.riscv.R5DecoderTreePrinter")
}

jmh {
    warmupIterations = (project.findProperty("jmh.warmupIterations") as String? ?: "3").toInt()
    iterations = (project.findProperty("jmh.iterations") as String? ?: "5").toInt()
    fork = (project.findProperty("jmh.fork") as String? ?: "1").toInt()
    (project.findProperty("jmh.include") as String?)?.let { includes = listOf(it) }

    (project.findProperty("jmh.params") as String?)?.let { spec ->
        spec.split(";").filter { it.isNotBlank() }.forEach { entry ->
            val name = entry.substringBefore('=').trim()
            val values = entry.substringAfter('=').split(",").map { it.trim() }
            benchmarkParameters.put(name, objects.listProperty(String::class.java).value(values))
        }
    }

    warmupForks = 0
    resultFormat = "TEXT"
    includeTests = false
    jvmArgs = listOf("-XX:MaxDirectMemorySize=4g") +
            ((project.findProperty("jmh.images") as String?)?.let { listOf("-Dsedna.benchmark.images=$it") }
                ?: emptyList())
}
