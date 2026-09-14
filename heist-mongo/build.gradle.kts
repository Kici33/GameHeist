plugins { `java-library` }
dependencies {
    api(project(":heist-runtime"))
    implementation("org.mongodb:mongodb-driver-sync:5.5.1")
}
tasks.test {
    inputs.property("mongoIntegration", providers.environmentVariable("HEIST_MONGO_TESTS").orElse("false"))
}
