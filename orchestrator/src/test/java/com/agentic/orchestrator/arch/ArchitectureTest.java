package com.agentic.orchestrator.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture rules as plain JUnit tests, so Surefire always runs and counts them.
 */
class ArchitectureTest {
    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.agentic.orchestrator");
    }

    /** The control plane is plain Java so it can be reasoned about, tested and replayed in isolation. */
    @Test
    void coreHasNoFrameworkDependencies() {
        noClasses()
                .that().resideInAnyPackage("..domain..", "..events..", "..engine..", "..gates..", "..policy..",
                        "..metrics..", "..artifacts..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "com.fasterxml..")
                .check(classes);
    }

    /** Gates and policy decide; they must never reach a model. */
    @Test
    void gatesAndPolicyNeverCallAModel() {
        noClasses()
                .that().resideInAnyPackage("..gates..", "..policy..")
                .should().dependOnClassesThat().resideInAPackage("..model..")
                .check(classes);
    }

    /** The engine does not know any concrete agent or the application edge. */
    @Test
    void engineDoesNotDependOnDemoOrApp() {
        noClasses()
                .that().resideInAnyPackage("..engine..")
                .should().dependOnClassesThat().resideInAnyPackage("..demo..", "..app..")
                .check(classes);
    }
}
