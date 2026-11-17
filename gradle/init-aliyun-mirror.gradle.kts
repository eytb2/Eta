import org.gradle.api.initialization.resolve.RepositoriesMode

// Global Gradle init script — force Aliyun mirrors for ALL projects + plugin
// resolution. Applied via --init-script from build-app.sh.
//
// maven central 403s direct HEAD requests from this network, so Aliyun is the
// ONLY repo — no google()/mavenCentral() fallback. Aliyun proxies both.

val aliyunGoogle = "https://maven.aliyun.com/repository/google"
val aliyunCentral = "https://maven.aliyun.com/repository/central"
val aliyunPlugins = "https://maven.aliyun.com/repository/gradle-plugin"

settingsEvaluated {
    pluginManagement {
        repositories {
            maven(url = aliyunGoogle)
            maven(url = aliyunCentral)
            maven(url = aliyunPlugins)
        }
    }
    dependencyResolutionManagement {
        repositoriesMode = RepositoriesMode.PREFER_SETTINGS
        repositories {
            maven(url = aliyunCentral)
            maven(url = aliyunGoogle)
        }
    }
}

// The `classpath` configuration (buildscript) resolves per-project BEFORE
// dependencyResolutionManagement applies — force Aliyun there too. buildscript
// repos are NOT subject to FAIL_ON_PROJECT_REPOS.
projectsLoaded {
    rootProject.allprojects {
        buildscript {
            repositories {
                maven(url = aliyunCentral)
                maven(url = aliyunGoogle)
                maven(url = aliyunPlugins)
            }
        }
    }
}
