rootProject.name = "GameHeist"
include("heist-domain", "heist-runtime", "heist-mongo", "heist-paper", "heist-pack")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            content {
                includeGroupByRegex("io\\.papermc.*")
                includeGroup("com.mojang")
                includeGroup("net.md-5")
            }
        }
    }
}
