plugins {
    java
    `maven-publish`
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

java.toolchain.languageVersion = JavaLanguageVersion.of(17)

repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/fnuecke/ceres")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
        }
        content { includeGroup("li.cil.ceres") }
    }
}

dependencies {
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    testCompileOnly("com.google.code.findbugs:jsr305:3.0.2")

    implementation("commons-io:commons-io:2.11.0")
    implementation("it.unimi.dsi:fastutil:8.5.6")
    implementation("org.apache.commons:commons-lang3:3.12.0")
    implementation("org.apache.logging.log4j:log4j-api:2.15.0")
    implementation("org.ow2.asm:asm:9.1")

    implementation("li.cil.ceres:ceres:0.0.4")

    testImplementation("org.mockito:mockito-core:4.1.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.8.2")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = packageGroup
            artifactId = project.name
            version = semver
            from(components["java"])
        }
    }
    repositories {
        val githubMavenUrl = System.getenv("GITHUB_MAVEN_URL")
        if (!githubMavenUrl.isNullOrEmpty()) {
            maven {
                name = "GitHubPackages"
                url = uri(githubMavenUrl)
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("generateDecoder") {
    group = "build"
    description = "Regenerates src/main/java/li/cil/sedna/riscv/R5CPUImpl.java from the instruction declarations."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("li.cil.sedna.riscv.R5CPUImplGenerator")
    workingDir = projectDir
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

configurations.matching { it.name.startsWith("jmh") }.configureEach {
    resolutionStrategy.force(
        "org.ow2.asm:asm:9.1",
        "org.ow2.asm:asm-tree:9.1",
        "org.ow2.asm:asm-commons:9.1",
        "org.ow2.asm:asm-analysis:9.1"
    )
}
