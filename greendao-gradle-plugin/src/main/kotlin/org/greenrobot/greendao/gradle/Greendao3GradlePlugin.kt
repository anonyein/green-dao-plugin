/*
 * greenDAO Build Tools
 * Copyright (C) 2016-2024 greenrobot (https://greenrobot.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.greenrobot.greendao.gradle

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Variant
import com.android.build.gradle.BaseExtension
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.util.PatternFilterable
import org.greenrobot.greendao.codemodifier.Greendao3Generator
import org.greenrobot.greendao.codemodifier.SchemaOptions
import java.io.File
import java.io.IOException
import java.util.Properties

class Greendao3GradlePlugin : Plugin<Project> {

    val name: String = "greendao"
    val packageName: String = "org/greenrobot/greendao"

    override fun apply(project: Project) {
        project.logger.debug("$name plugin starting...")
        project.extensions.create(name, GreendaoOptions::class.java, project)

        if (isAndroidProject(project)) {
            configureAndroid(project)
        } else if (project.plugins.hasPlugin("java")) {
            configureJava(project)
        } else {
            project.plugins.withId("com.android.application") { configureAndroid(project) }
            project.plugins.withId("com.android.library") { configureAndroid(project) }
            project.plugins.withId("java") { configureJava(project) }
        }
    }

    private fun isAndroidProject(project: Project) =
        ANDROID_PLUGINS.any { project.plugins.hasPlugin(it) }

    // ---------- Android 项目 ----------
    private fun configureAndroid(project: Project) {
        val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)

        var targetGenDir: File? = null
        var writeToBuildFolder = false
        // 用于标记任务是否已创建
        var taskCreated = false

        project.afterEvaluate {
            val version = getVersion()
            project.logger.debug("$name plugin $version preparing tasks...")
            val candidatesFile = project.file("build/cache/$name-candidates.list")

            // 完全还原原 AndroidPluginSourceProvider.sourceTree() 的行为：所有 source set 的 java 源目录
            val androidExt = project.extensions.getByType(BaseExtension::class.java)
            val allJavaDirs = androidExt.sourceSets.flatMap { it.java.srcDirs }
            val sourceTree = project.files(allJavaDirs).asFileTree
            val encoding = "UTF-8"

            val options = project.extensions.getByType(GreendaoOptions::class.java)
            writeToBuildFolder = options.targetGenDir == null
            targetGenDir = options.targetGenDir ?: File(project.buildDir, "generated/source/$name")

            val prepareTask = project.tasks.register("${name}Prepare", DetectEntityCandidatesTask::class.java) {
                it.sourceFiles = sourceTree.matching(Action<PatternFilterable> { pf -> pf.include("**/*.java") })
                it.candidatesListFile = candidatesFile
                it.version = version
                it.charset = encoding
                it.group = name
                it.description = "Finds entity source files for $name"
            }

            val greendaoTask = createGreendaoTask(project, candidatesFile, options, targetGenDir!!, encoding, version)
            greendaoTask.dependsOn(prepareTask)
            taskCreated = true
        }

        androidComponents.onVariants { variant ->
            if (!taskCreated || targetGenDir == null) return@onVariants
            bindTaskToVariant(project, variant, targetGenDir!!, writeToBuildFolder)
        }
    }

    /**
     * 完全等价于原 AndroidPluginSourceProvider.addGeneratorTask 的行为
     */
    private fun bindTaskToVariant(
        project: Project,
        variant: Variant,
        targetGenDir: File,
        writeToBuildFolder: Boolean
    ) {
        // 获取 TaskProvider，因为 addGeneratedSourceDirectory 需要它
        val taskProvider = project.tasks.named(name) // "greendao"

        if (writeToBuildFolder) {
            variant.sources.java?.addGeneratedSourceDirectory(
                taskProvider,
                { targetGenDir }
            )
        } else {
            // 用户指定了外部目录，不注册为生成目录（原插件行为），但编译仍然依赖生成任务
        }

        val compileTaskName = "compile${variant.name.capitalize()}JavaWithJavac"
        project.tasks.named(compileTaskName) { compileTask ->
            compileTask.dependsOn(taskProvider)
        }
    }

    // ---------- Java 项目（完全不变） ----------
    private fun configureJava(project: Project) {
        project.afterEvaluate {
            val version = getVersion()
            project.logger.debug("$name plugin $version preparing tasks...")
            val candidatesFile = project.file("build/cache/$name-candidates.list")
            val sourceProvider = JavaPluginSourceProvider(project)
            val encoding = sourceProvider.encoding ?: "UTF-8"

            val prepareTask = project.tasks.register("${name}Prepare", DetectEntityCandidatesTask::class.java) {
                it.sourceFiles = sourceProvider.sourceTree().matching(Action<PatternFilterable> { pf ->
                    pf.include("**/*.java")
                })
                it.candidatesListFile = candidatesFile
                it.version = version
                it.charset = encoding
                it.group = name
                it.description = "Finds entity source files for $name"
            }

            val options = project.extensions.getByType(GreendaoOptions::class.java)
            val writeToBuildFolder = options.targetGenDir == null
            val targetGenDir = if (writeToBuildFolder)
                File(project.buildDir, "generated/source/$name") else options.targetGenDir!!

            val greendaoTask = createGreendaoTask(project, candidatesFile, options, targetGenDir, encoding, version)
            greendaoTask.dependsOn(prepareTask)

            sourceProvider.addGeneratorTask(greendaoTask, targetGenDir, writeToBuildFolder)
        }
    }

    // ---------- 以下方法直接来自原始代码，未改动 ----------
    private fun createGreendaoTask(project: Project, candidatesFile: File, options: GreendaoOptions,
                                   targetGenDir: File, encoding: String, version: String): Task {
        val generateTask = project.tasks.register(name) { task ->
            task.logging.captureStandardOutput(LogLevel.INFO)
            task.inputs.file(candidatesFile)
            task.inputs.property("plugin-version", version)
            task.inputs.property("source-encoding", encoding)
            val schemaOptions = collectSchemaOptions(options.daoPackage, targetGenDir, options)
            schemaOptions.forEach { e -> task.inputs.property("schema-${e.key}", e.value.toString()) }
            val outputFileTree = project.fileTree(targetGenDir) { pf ->
                pf.include("**/*Dao.java", "**/DaoSession.java", "**/DaoMaster.java")
            }
            task.outputs.files(outputFileTree)
            if (options.generateTests) {
                task.outputs.dir(options.targetGenDirTests)
            }
            task.doLast {
                require(candidatesFile.exists()) { "Candidates file does not exist. Can't continue" }
                val candidatesFiles = candidatesFile.readLines().asSequence().drop(1).map { File(it) }.asIterable()
                Greendao3Generator(
                    options.formatting.data,
                    options.skipTestGeneration,
                    encoding
                ).run(candidatesFiles, schemaOptions)
            }
        }
        generateTask.configure {
            it.group = name
            it.description = "Generates source files for $name"
        }
        return generateTask.get()
    }

    private fun getVersion(): String {
        val properties = Properties()
        val stream = javaClass.getResourceAsStream("/$packageName/gradle/version.properties")
        stream?.use {
            try {
                properties.load(it)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        return properties.getProperty("version") ?: "Unknown (bad build)"
    }

    private fun collectSchemaOptions(daoPackage: String?, genSrcDir: File, options: GreendaoOptions)
            : MutableMap<String, SchemaOptions> {
        val defaultOptions = SchemaOptions(
            name = "default",
            version = options.schemaVersion,
            daoPackage = daoPackage,
            outputDir = genSrcDir,
            testsOutputDir = if (options.generateTests) options.targetGenDirTests else null
        )
        val schemaOptions = mutableMapOf("default" to defaultOptions)
        options.schemas.schemasMap.forEach { (name, schemaExt) ->
            schemaOptions[name] = SchemaOptions(
                name = name,
                version = schemaExt.version ?: defaultOptions.version,
                daoPackage = schemaExt.daoPackage ?: defaultOptions.daoPackage?.let { "$it.$name" },
                outputDir = defaultOptions.outputDir,
                testsOutputDir = if (options.generateTests)
                    schemaExt.targetGenDirTests ?: defaultOptions.testsOutputDir
                else null
            )
        }
        return schemaOptions
    }

    val ANDROID_PLUGINS = listOf(
        "android", "android-library",
        "com.android.application", "com.android.library",
        "com.android.feature"
    )
}

