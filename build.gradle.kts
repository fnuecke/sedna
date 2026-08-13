plugins {
    java
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("me.champeau.jmh") version "0.7.2"
}

val semver: String by project
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

val codegen by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[codegen.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[codegen.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    testCompileOnly("com.google.code.findbugs:jsr305:3.0.2")

    implementation("commons-io:commons-io:2.11.0")
    implementation("it.unimi.dsi:fastutil:8.5.6")
    implementation("org.apache.commons:commons-lang3:3.12.0")
    implementation("org.apache.logging.log4j:log4j-api:2.15.0")

    implementation("li.cil.ceres:ceres:0.0.4")

    codegen.implementationConfigurationName("org.ow2.asm:asm:9.10.1")
    codegen.compileOnlyConfigurationName("com.google.code.findbugs:jsr305:3.0.2")

    testImplementation(codegen.output)
    testImplementation("org.ow2.asm:asm:9.10.1")
    testImplementation("org.mockito:mockito-core:4.1.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.8.2")
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
        ((project.findProperty("jmh.images") as String?)?.let { listOf("-Dsedna.benchmark.images=$it") } ?: emptyList())
}
