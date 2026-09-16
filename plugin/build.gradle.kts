plugins {
    id("java")
}

group = "com.flywireminecraft"
version = "0.1.0"

// Sem toolchain fixo: exigiria JDK 21 instalado à parte (ou download automático
// via resolver de toolchain). O JDK disponível (23) compila para release 21 via
// `options.release` abaixo, que é o que realmente importa para compatibilidade
// com o servidor Paper — não qual JDK roda o Gradle.

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Gson vem embutido no classpath do servidor Paper em runtime;
    // compileOnly aqui só resolve símbolos em tempo de build.
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
    processResources {
        filteringCharset = "UTF-8"
    }
    jar {
        archiveBaseName.set("flywire-bee")
    }
}
