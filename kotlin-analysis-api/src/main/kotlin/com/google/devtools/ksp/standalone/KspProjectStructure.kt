/*
 * Copyright 2026 Google LLC
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(KaExperimentalApi::class, KaPlatformInterface::class, KaImplementationDetail::class)

package com.google.devtools.ksp.standalone

import com.intellij.core.CoreApplicationEnvironment
import com.intellij.openapi.application.Application
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackagePartProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaModuleBase
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.asDebugString
import org.jetbrains.kotlin.analysis.api.projectStructure.KaBuiltinsModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaNotUnderContentRootModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneWorkaroundApi
import org.jetbrains.kotlin.analysis.api.standalone.fir.projectStructure.KotlinStaticProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.standalone.fir.projectStructure.StandaloneProjectFactory.findJvmRootsForJavaFiles
import org.jetbrains.kotlin.analysis.api.standalone.projectStructure.StandaloneLibraryScopeConstructionMode
import org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirInternals
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.factory.LLFirBuiltinsSessionFactory
import org.jetbrains.kotlin.analysis.project.structure.builder.KaModuleContainer
import org.jetbrains.kotlin.analysis.project.structure.builder.KaModuleContainerBuilder
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreProjectEnvironment
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.serialization.deserialization.builtins.BuiltInSerializerProtocol
import org.jetbrains.kotlin.utils.KotlinExceptionWithAttachments

// The Analysis API made its standalone module container, project structure provider, session and package part provider
// factory internal. KSP builds its session from the parts, with its own incremental providers, so it keeps copies of them here.

@OptIn(KaImplementationDetail::class, StandaloneWorkaroundApi::class)
class KspModuleContainerBuilder(
    override val coreApplicationEnvironment: CoreApplicationEnvironment,
    override val project: Project,
) : KaModuleContainerBuilder() {
    private val modules = mutableListOf<KaModule>()

    override fun <M : KaModule> addModule(module: M): M {
        modules.add(module)
        return module
    }

    override var libraryScopeConstructionMode: StandaloneLibraryScopeConstructionMode =
        StandaloneLibraryScopeConstructionMode.ParentTraversal

    override fun build(): KaModuleContainer = KspModuleContainer(modules.toList())
}

private class KspModuleContainer(override val allModules: List<KaModule>) : KaModuleContainer

private class KspNotUnderContentRootModule(
    override val name: String,
    override val file: PsiFile? = null,
    override val moduleDescription: String,
    override val project: Project,
) : KaNotUnderContentRootModule, KaModuleBase() {
    override val directRegularDependencies: List<KaModule> get() = emptyList()
    override val directDependsOnDependencies: List<KaModule> get() = emptyList()
    override val directFriendDependencies: List<KaModule> get() = emptyList()
    override val targetPlatform: TargetPlatform get() = JvmPlatforms.defaultJvmPlatform
    override val baseContentScope: GlobalSearchScope =
        if (file != null) GlobalSearchScope.fileScope(file) else GlobalSearchScope.EMPTY_SCOPE

    override fun equals(other: Any?): Boolean =
        this === other || other is KspNotUnderContentRootModule && file == other.file && project == other.project

    override fun hashCode(): Int = 31 * file.hashCode() + project.hashCode()
}

class KspProjectStructureProvider(
    private val platform: TargetPlatform,
    private val project: Project,
    private val moduleContainer: KaModuleContainer,
) : KotlinStaticProjectStructureProvider() {
    private val notUnderContentRootModuleWithoutPsiFile by lazy {
        KspNotUnderContentRootModule(
            name = "unnamed-outside-content-root",
            moduleDescription = "Standalone not-under-content-root module without a PSI file.",
            project = project,
        )
    }

    private val builtinsModule: KaBuiltinsModule by lazy {
        @OptIn(LLFirInternals::class)
        LLFirBuiltinsSessionFactory.getInstance(project).getBuiltinsSession(platform).ktModule as KaBuiltinsModule
    }

    override fun getNotUnderContentRootModule(project: Project): KaNotUnderContentRootModule =
        notUnderContentRootModuleWithoutPsiFile

    override fun getModule(element: PsiElement, useSiteModule: KaModule?): KaModule {
        val containingFile = element.containingFile ?: return notUnderContentRootModuleWithoutPsiFile
        val virtualFile = containingFile.virtualFile
        if (virtualFile != null &&
            virtualFile.extension == BuiltInSerializerProtocol.BUILTINS_FILE_EXTENSION &&
            virtualFile in builtinsModule.contentScope
        ) {
            return builtinsModule
        }
        computeSpecialModule(containingFile)?.let { return it }
        if (virtualFile == null) {
            throw KotlinExceptionWithAttachments("Cannot find a KaModule for a non-physical file")
                .withPsiAttachment("containingFile", containingFile)
                .withAttachment("useSiteModule", useSiteModule?.asDebugString())
        }
        return allModules.firstOrNull { module -> virtualFile in module.contentScope }
            ?: KspNotUnderContentRootModule(
                name = "unnamed-outside-content-root",
                file = containingFile,
                moduleDescription = "Standalone not-under-content-root module with a PSI file.",
                project = project,
            )
    }

    override fun getImplementingModules(module: KaModule): List<KaModule> =
        allModules.filter { module in it.directDependsOnDependencies }

    override val allModules: List<KaModule>
        get() = moduleContainer.allModules

    override val allSourceFiles: List<PsiFileSystemItem> by lazy {
        buildList {
            val files = allModules.mapNotNull { (it as? KaSourceModule)?.psiRoots }.flatten()
            addAll(files)
            addAll(findJvmRootsForJavaFiles(files.filterIsInstance<PsiJavaFile>()))
        }
    }
}

@OptIn(KaImplementationDetail::class)
class KspStandaloneAnalysisAPISession(
    kotlinCoreProjectEnvironment: KotlinCoreProjectEnvironment,
    override val allModules: List<KaModule>,
) : StandaloneAnalysisAPISession {
    override val coreApplicationEnvironment: CoreApplicationEnvironment = kotlinCoreProjectEnvironment.environment
    override val application: Application = kotlinCoreProjectEnvironment.environment.application
    override val project: Project = kotlinCoreProjectEnvironment.project
    override val modulesWithFiles: Map<KaSourceModule, List<PsiFile>> by lazy {
        allModules.filterIsInstance<KaSourceModule>().associateWith { it.psiRoots.filterIsInstance<PsiFile>() }
    }
}

class KspStaticPackagePartProviderFactory(
    private val packagePartProvider: (GlobalSearchScope) -> PackagePartProvider,
) : KotlinPackagePartProviderFactory {
    private val cache = ContainerUtil.createConcurrentSoftMap<GlobalSearchScope, PackagePartProvider>()

    override fun createPackagePartProvider(scope: GlobalSearchScope): PackagePartProvider =
        cache.getOrPut(scope) { packagePartProvider(scope) }
}
