plugins { `java-library` }

tasks.test {
    systemProperty("pack.source", rootProject.layout.projectDirectory.dir("resource-pack").asFile.absolutePath)
    inputs.dir(rootProject.layout.projectDirectory.dir("resource-pack"))
}
dependencies {
    implementation(project(":heist-runtime"))
    implementation(project(":heist-mongo"))
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    testImplementation("io.papermc.paper:paper-api:26.1.2.build.74-stable")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

// Bundle our modules and the pinned MongoDB driver; Paper supplies its own API.
tasks.jar {
    dependsOn(":heist-domain:classes", ":heist-runtime:classes", ":heist-mongo:classes")
    from(project(":heist-domain").extensions.getByType<SourceSetContainer>()["main"].output)
    from(project(":heist-runtime").extensions.getByType<SourceSetContainer>()["main"].output)
    from(project(":heist-mongo").extensions.getByType<SourceSetContainer>()["main"].output)
    from({ configurations.runtimeClasspath.get().filter { it.name.startsWith("mongodb-") || it.name.startsWith("bson-") }.map { zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/native-image/**", "META-INF/versions/**", "module-info.class")
    }
    duplicatesStrategy = DuplicatesStrategy.FAIL
}
