fun substituteLocal(propertyName: String, libraryName: String) {
    val configured = providers.gradleProperty(propertyName).orNull?.takeIf { it.isNotBlank() } ?: return
    val path = rootDir.resolve(configured)
    require(path.isDirectory) { "[$propertyName] is set to [$configured], which is not a directory." }
    println("Substituting [$libraryName] from local [${path.canonicalPath}]")
    includeBuild(path) {
        dependencySubstitution {
            substitute(module(libraryName)).using(project(":"))
        }
    }
}

substituteLocal("ceresDir", "li.cil.ceres:ceres")

rootProject.name = "sedna"
