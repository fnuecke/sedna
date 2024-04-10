fun substituteLocal(directoryName: String, libraryName: String) {
    val path = File("../${directoryName}");
    if (path.exists()) {
        println("Found local [${directoryName}] project, substituting...")
        includeBuild(path) {
            dependencySubstitution {
                substitute(module(libraryName)).using(project(":"))
            }
        }
    }
}

substituteLocal("ceres", "li.cil.ceres:ceres")

rootProject.name = "sedna"
