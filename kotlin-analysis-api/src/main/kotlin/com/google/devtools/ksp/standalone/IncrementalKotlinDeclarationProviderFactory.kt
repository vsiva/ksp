package com.google.devtools.ksp.standalone

import com.intellij.core.CoreApplicationEnvironment
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinCompositeDeclarationProvider
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProvider
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.standalone.base.declarations.KotlinStandaloneDeclarationProviderFactory
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtScript
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtClassLikeDeclaration
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTypeAlias

@OptIn(KaPlatformInterface::class, KaImplementationDetail::class)
class IncrementalKotlinDeclarationProviderFactory(
    private val project: Project,
    private val environment: CoreApplicationEnvironment,
) : KotlinDeclarationProviderFactory {
    // Each factory indexes its files (it builds the stubs of each file) when it is first used, not when it is added: a run in which
    // no file is dirty, as in most KSP tasks after an ABI edit in another module, may never ask the declarations of the sources.
    private val staticFactories: MutableList<Lazy<KotlinStandaloneDeclarationProviderFactory>> = mutableListOf()

    override fun createDeclarationProvider(
        scope: GlobalSearchScope,
        contextualModule: KaModule?
    ): KotlinDeclarationProvider {
        val providers = staticFactories.map { factory ->
            LazyDeclarationProvider { factory.value.createDeclarationProvider(scope, contextualModule) }
        }
        return KotlinCompositeDeclarationProvider.create(providers)
    }

    fun update(files: Collection<KtFile>) {
        val skipBuiltIns = staticFactories.isNotEmpty()
        val filesToIndex = files.toList()
        staticFactories.add(
            lazy {
                KotlinStandaloneDeclarationProviderFactory(
                    project,
                    environment,
                    filesToIndex,
                    skipBuiltins = skipBuiltIns
                )
            }
        )
    }

    fun getDirectInheritorCandidates(baseClassName: Name): Set<KtClassOrObject> =
        staticFactories.flatMapTo(mutableSetOf()) {
            it.value.getDirectInheritorCandidates(baseClassName)
        }

    fun getInheritableTypeAliases(aliasedName: Name): Set<KtTypeAlias> =
        staticFactories.flatMapTo(mutableSetOf()) {
            it.value.getInheritableTypeAliases(aliasedName)
        }
}

/** A declaration provider that makes its delegate, and so the index of its factory, at its first use. */
@OptIn(KaPlatformInterface::class, KaImplementationDetail::class)
private class LazyDeclarationProvider(create: () -> KotlinDeclarationProvider) : KotlinDeclarationProvider {
    private val delegate by lazy(create)

    override fun getClassLikeDeclarationByClassId(classId: ClassId): KtClassLikeDeclaration? =
        delegate.getClassLikeDeclarationByClassId(classId)

    override fun getAllClassesByClassId(classId: ClassId): Collection<KtClassOrObject> = delegate.getAllClassesByClassId(classId)
    override fun getAllTypeAliasesByClassId(classId: ClassId): Collection<KtTypeAlias> = delegate.getAllTypeAliasesByClassId(classId)
    override fun getTopLevelKotlinClassLikeDeclarationNamesInPackage(packageFqName: FqName): Set<Name> =
        delegate.getTopLevelKotlinClassLikeDeclarationNamesInPackage(packageFqName)

    override fun getTopLevelProperties(callableId: CallableId): Collection<KtProperty> = delegate.getTopLevelProperties(callableId)
    override fun getTopLevelFunctions(callableId: CallableId): Collection<KtNamedFunction> = delegate.getTopLevelFunctions(callableId)
    override fun getTopLevelCallableFiles(callableId: CallableId): Collection<KtFile> = delegate.getTopLevelCallableFiles(callableId)
    override fun getTopLevelCallableNamesInPackage(packageFqName: FqName): Set<Name> =
        delegate.getTopLevelCallableNamesInPackage(packageFqName)

    override fun findFilesForFacadeByPackage(packageFqName: FqName): Collection<KtFile> =
        delegate.findFilesForFacadeByPackage(packageFqName)

    override fun findFilesForFacade(facadeFqName: FqName): Collection<KtFile> = delegate.findFilesForFacade(facadeFqName)
    override fun findInternalFilesForFacade(facadeFqName: FqName): Collection<KtFile> = delegate.findInternalFilesForFacade(facadeFqName)
    override fun findFilesForScript(scriptFqName: FqName): Collection<KtScript> = delegate.findFilesForScript(scriptFqName)
    override fun computePackageNames(): Set<String>? = delegate.computePackageNames()
    override val hasSpecificClassifierPackageNamesComputation: Boolean get() = delegate.hasSpecificClassifierPackageNamesComputation
    override fun computePackageNamesWithTopLevelClassifiers(): Set<String>? = delegate.computePackageNamesWithTopLevelClassifiers()
    override val hasSpecificCallablePackageNamesComputation: Boolean get() = delegate.hasSpecificCallablePackageNamesComputation
    override fun computePackageNamesWithTopLevelCallables(): Set<String>? = delegate.computePackageNamesWithTopLevelCallables()
}
