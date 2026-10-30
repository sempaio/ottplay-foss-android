import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins { kotlin("jvm"); kotlin("plugin.serialization") }
kotlin { jvmToolchain(17) }
dependencies {
    api(files("vendor/ottplay-core.jar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.4.0")
}

val verifySharedCore = tasks.register("verifySharedCore") {
    val jar = layout.projectDirectory.file("vendor/ottplay-core.jar")
    val receipt = layout.projectDirectory.file("vendor/ottplay-core.manifest.json")
    inputs.files(jar, receipt)
    doLast {
        val manifest = JsonSlurper().parse(receipt.asFile) as Map<*, *>
        val artifacts = manifest["artifacts"] as Map<*, *>
        val expected = (artifacts["ottplay-core.jar"] as Map<*, *>)["sha256"]
        val actual = MessageDigest.getInstance("SHA-256").digest(jar.asFile.readBytes()).joinToString("") { "%02x".format(it) }
        check(actual == expected) { "OttPlay shared core differs from its pinned receipt; regenerate it from shared-core" }
    }
}
tasks.named("compileKotlin") { dependsOn(verifySharedCore) }
