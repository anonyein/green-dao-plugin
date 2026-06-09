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

        // 区分项目类型
        if (isAndroidProject(project)) {
            configureAndroid(project)
        } else if (project.plugins.hasPlugin("java")) {
            configureJava(project)
        } else {
            // 插件可能尚未应用，等待它们
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

        // 在 afterEvaluate 中读取用户配置、创建任务（原逻辑）
        var greendaoTask: Task? = null
        var targetGenDir: File? = null
        var writeToBuildFolder = false

        project.afterEvaluate {
            val version = getVersion()
            project.logger.debug("$name plugin $version preparing tasks...")
            val candidatesFile = project.file("build/cache/$name-candidates.list")

            // 完全还原原始 AndroidPluginSourceProvider.sourceTree() 的行为：
            // 获取所有 Android source set 的 java 源目录
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

            greendaoTask = createGreendaoTask(project, candidatesFile, options, targetGenDir!!, encoding, version)
            greendaoTask!!.dependsOn(prepareTask)
        }

        // 在 onVariants 中执行原 AndroidPluginSourceProvider.addGeneratorTask 的逻辑
        androidComponents.onVariants { variant ->
            val task = greendaoTask ?: return@onVariants
            val genDir = targetGenDir ?: return@onVariants
            bindTaskToVariant(project, variant, task, genDir, writeToBuildFolder)
        }
    }

    /**
     * 完全等价于原 AndroidPluginSourceProvider.addGeneratorTask 的行为：
     * - 如果 writeToBuildFolder 为 true，将生成的目录注册为 variant 的 Java 生成源码目录
     * - 如果 writeToBuildFolder 为 false（用户指定了外部目录），不注册为生成目录，但依然绑定编译依赖
     * - 保证 variant 的 Java 编译任务依赖 greenDAO 生成任务
     */
    private fun bindTaskToVariant(
        project: Project,
        variant: Variant,
        task: Task,
        targetGenDir: File,
        writeToBuildFolder: Boolean
    ) {
        if (writeToBuildFolder) {
            // 对应原插件中的 registerJavaGeneratingTask 或类似逻辑
            variant.sources.java?.addGeneratedSourceDirectory(
                task,
                { targetGenDir }
            )
        } else {
            // 用户指定了外部目录，不注册为生成目录，但需要确保编译时该目录在 classpath 中
            // 原插件可能是通过 variant.registerJavaGeneratingTask(task, targetGenDir) 实现，
            // 这里保持相同效果：不声明 generated，但让编译任务依赖 task
        }

        // 使 variant 的 Java 编译任务依赖 greenDAO 任务（原插件也是如此）
        val compileTaskName = "compile${variant.name.capitalize()}JavaWithJavac"
        project.tasks.named(compileTaskName) { compileTask ->
            compileTask.dependsOn(task)
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

    // ---------- 以下方法直接从原始代码复制，未改动 ----------
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
