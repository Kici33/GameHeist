plugins { `java-library` }

dependencies { implementation("com.google.code.gson:gson:2.13.2") }

val packSource = rootProject.layout.projectDirectory.dir("resource-pack")
val packOutput = rootProject.layout.buildDirectory.dir("resource-pack")
val resourcePack by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Validate and build the original GameHeist client resource pack."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.gameheist.pack.PackBuilder")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    inputs.dir(packSource)
    outputs.dir(packOutput)
    args(packSource.asFile.absolutePath, packOutput.get().asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
}
tasks.assemble { dependsOn(resourcePack) }
tasks.test {
    systemProperty("pack.source", packSource.asFile.absolutePath)
    inputs.dir(packSource)
}
