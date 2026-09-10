import de.undercouch.gradle.tasks.download.Download
import java.io.FileOutputStream
import java.lang.classfile.AccessFlags
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassTransform
import java.lang.reflect.AccessFlag
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("java")
    id("de.undercouch.download") version "5.7.0"
}

// Use the Configuration Cache-safe Provider API to consume it
val essentialVersion: Provider<String> = providers.gradleProperty("essentialVersion")

tasks.register("checkProperty") {
    doLast {
        if (essentialVersion.isPresent) {
            println("Included build received: ${essentialVersion.get()}")
        }
    }
}

group = "dev.jab125.essential"
version = if (essentialVersion.isPresent) {
    essentialVersion.get()
} else {
    throw GradleException("This included build must be executed from the root project with 'essentialVersion' specified!")
}

var essentialLink = "https://cdn.modrinth.com/data/k2ZPuTBm/versions/bB2cTcpq/Essential_${version}.jar";

val downloadEssential = tasks.register("downloadEssential", Download::class.java) {
    src(essentialLink)
    dest(layout.buildDirectory)
    onlyIfModified(true)
}

abstract class FetchEssentialProperties : DefaultTask() {
    @get:InputFile
    abstract val essentialWrapperJar: RegularFileProperty
    @get:OutputFile
    abstract val resultPropertyFile: RegularFileProperty
    @TaskAction
    fun fetch() {
        val jarFile = essentialWrapperJar.get().asFile
        val pinned = ZipFile(jarFile).use { zip ->
            val entry = zip.getEntry("essential-loader.properties")
                ?: throw AssertionError("essential-loader.properties not found in ZIP")
            val properties = Properties()
            zip.getInputStream(entry).use { properties.load(it) }
            properties.getProperty("pinnedFile")
                ?: throw AssertionError("pinnedFile property not found")
        }
        val outputFile = resultPropertyFile.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(pinned)
    }
}

abstract class ExtractPinnedFileTask : DefaultTask() {
    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations
    @get:Inject
    abstract val archiveOperations: ArchiveOperations
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val essentialWrapperJar: RegularFileProperty
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val trackerFile: RegularFileProperty
    @get:OutputFile
    abstract val extractedFile: RegularFileProperty
    @TaskAction
    fun extract() {
        val jarFile = essentialWrapperJar.get().asFile
        val destinationFile = extractedFile.get().asFile
        val targetPath = trackerFile.get().asFile.readText().trim()
        if (targetPath.isEmpty()) {
            throw IllegalStateException("The tracker file was empty!")
        }
        destinationFile.delete()
        fileSystemOperations.copy {
            from(archiveOperations.zipTree(jarFile)) {
                include(targetPath)
                eachFile {
                    relativePath = RelativePath(true, name)
                }
                includeEmptyDirs = false
                rename { _ -> destinationFile.name }
            }
            into(destinationFile.parentFile)
        }
    }
}

val fetchEssentialLoaderProperties = tasks.register<FetchEssentialProperties>("fetchEssentialLoaderProperties") {
    dependsOn(downloadEssential)
    essentialWrapperJar.set(project.layout.file(project.provider {
        downloadEssential.get().outputFiles.first()
    }))
    resultPropertyFile.set(layout.buildDirectory.file("tmp/essential-tracker.txt"))
}

val extractPinnedFile = tasks.register<ExtractPinnedFileTask>("extractPinnedFile") {
    essentialWrapperJar.set(fetchEssentialLoaderProperties.flatMap { it.essentialWrapperJar })
    trackerFile.set(fetchEssentialLoaderProperties.flatMap { it.resultPropertyFile })
    extractedFile.set(layout.buildDirectory.file("extracted-essential/essential.jar"))
}
abstract class TransformJarTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputJar: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @TaskAction
    fun transform() {
        val inFile = inputJar.get().asFile
        val outFile = outputJar.get().asFile
        ZipFile(inFile).use { zipFile ->
            transformJar(zipFile, outFile)
        }
    }

    fun transformJar(file: ZipFile, out: File) {
        out.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(out)).use { zos ->
            val entries = file.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val newEntry = ZipEntry(entry.name)
                zos.putNextEntry(newEntry)
                if (entry.name.endsWith(".class")) {
                    file.getInputStream(entry).use { inputStream ->
                        val classModel = ClassFile.of().parse(inputStream.readAllBytes())
                        val makeAllClassesPublic = ClassTransform { builder, element ->
                            if (element is AccessFlags) {
                                val set = element.flags().filter { a -> a != AccessFlag.PRIVATE }.toMutableSet()
                                set.add(AccessFlag.PUBLIC)
                                builder.withFlags(*set.toTypedArray())
                            } else builder.accept(element)
                        }
                        zos.write(ClassFile.of().transformClass(classModel, makeAllClassesPublic))
                    }
                } else {
                    file.getInputStream(entry).use { inputStream ->
                        inputStream.copyTo(zos)
                    }
                }

                zos.closeEntry()
            }
        }
    }
}

val transformEssentialJar = tasks.register<TransformJarTask>("transformEssentialJar") {
    inputJar.set(extractPinnedFile.flatMap { it.extractedFile })
    outputJar.set(layout.buildDirectory.file("transformed-essential/essential-public.jar"))
}

tasks.jar {
    dependsOn(transformEssentialJar)
    actions = emptyList()
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    archiveFileName.set("essential-${project.version}.jar")
    doLast {
        val transformedFile = transformEssentialJar.flatMap { it.outputJar }.get().asFile
        val targetFile = archiveFile.get().asFile
        targetFile.parentFile.mkdirs()
        transformedFile.copyTo(targetFile, overwrite = true)
    }
}

artifacts {
    add("runtimeElements", transformEssentialJar.flatMap { it.outputJar })
    add("archives", transformEssentialJar.flatMap { it.outputJar })
}

tasks.compileJava { enabled = false }
tasks.classes { enabled = false }

tasks.build {
    dependsOn(tasks.jar)
}