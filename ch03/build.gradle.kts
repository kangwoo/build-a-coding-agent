plugins { java }

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }

    repositories { mavenCentral() }

    tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
    tasks.withType<Test> {
        useJUnitPlatform()                                  // JUnit 5 활성화(루트에서 한 번)
        testLogging { events("passed", "skipped", "failed") }
    }
}
