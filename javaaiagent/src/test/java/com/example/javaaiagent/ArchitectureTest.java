package com.example.javaaiagent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Keeps package boundaries reviewable as new adapters and tools are added. */
@AnalyzeClasses(
        packages = "com.example.javaaiagent",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule packagesHaveNoCycles =
            slices().matching("com.example.javaaiagent.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule applicationDoesNotDependOnEntryPointsOrModelProviders =
            noClasses()
                    .that()
                    .resideInAPackage("..application..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "..api..",
                            "..cli..",
                            "..demo..",
                            "..model..",
                            "..bootstrap..",
                            "..tools..");

    @ArchTest
    static final ArchRule applicationDoesNotConstructHttpClients =
            noClasses()
                    .that()
                    .resideInAPackage("..application..")
                    .should()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("java.net.http.HttpClient")
                    .orShould()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("com.example.javaaiagent.http.BoundedHttp");

    @ArchTest
    static final ArchRule serverDoesNotDependOnDemoCommands =
            noClasses()
                    .that()
                    .resideInAnyPackage("..api..", "..application..", "..tools..", "..security..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("..demo..", "..cli..");
}
