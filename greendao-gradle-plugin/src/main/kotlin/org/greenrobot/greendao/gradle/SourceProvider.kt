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
import com.android.build.gradle.AndroidConfig
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import java.io.File

interface SourceProvider {
    fun sourceFiles(): Sequence<FileTree>
    fun sourceTree(): FileTree = sourceFiles().reduce { a, b -> a + b }
    val encoding: String? get
    fun addGeneratorTask(generatorTask: Task, targetGenDir: File, writeToBuildFolder: Boolean)
}

class AndroidPluginSourceProvider(val project: Project) : SourceProvider {
    private val androidExtension: AndroidConfig = project.extensions.getByType(AndroidConfig::class.java)

    override fun sourceFiles(): Sequence<FileTree> =
            androidExtension.sourceSets.asSequence().map { sourceSet ->
                project.files(*sourceSet.java.srcDirs.toTypedArray()).asFileTree
            }

    override val encoding: String?
        get() = androidExtension.compileOptions.encoding

    override fun addGeneratorTask(generatorTask: Task, targetGenDir: File, writeToBuildFolder: Boolean) {
        val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)
        val taskProvider = project.tasks.named(generatorTask.name)

        androidComponents.onVariants { variant ->
            if (writeToBuildFolder) {
                val dirProperty: DirectoryProperty = project.objects.directoryProperty()
                dirProperty.set(targetGenDir)
                variant.sources.java?.addGeneratedSourceDirectory(taskProvider) { dirProperty }
            } else {
                // user takes care of adding to source dirs, just add the task dependency
                variant.configureJavaCompileTask { it.dependsOn(generatorTask) }
            }
        }
    }
}

class JavaPluginSourceProvider(val project: Project) : SourceProvider {
    private val javaPlugin: JavaPluginExtension = project.extensions.getByType(JavaPluginExtension::class.java)

    override fun sourceFiles(): Sequence<FileTree> =
            javaPlugin.sourceSets.asSequence().map { it.allJava.asFileTree }

    override val encoding: String?
        get() = project.tasks.withType(JavaCompile::class.java).firstOrNull()?.options?.encoding

    override fun addGeneratorTask(generatorTask: Task, targetGenDir: File, writeToBuildFolder: Boolean) {
        // for the main source set...
        val mainSourceSet = javaPlugin.sourceSets.getByName("main")
        // ...make the compile task depend on the generator task
        val compileJavaTask = project.tasks.getByName(mainSourceSet.compileJavaTaskName) as JavaCompile
        compileJavaTask.dependsOn(generatorTask)
        if (writeToBuildFolder) {
            // ...add the generated sources folder to the source dirs
            mainSourceSet.java.srcDir(targetGenDir)
            // ...ensure the compile task has them on the classpath
            compileJavaTask.setSource(compileJavaTask.source + generatorTask.outputs.files)
        }
    }
}
