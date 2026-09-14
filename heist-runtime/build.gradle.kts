plugins { `java-library` }
dependencies { api(project(":heist-domain")) }
tasks.test {
    inputs.property("influxIntegration", providers.environmentVariable("HEIST_INFLUX_TESTS").orElse("false"))
}
