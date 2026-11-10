package prepare.edt.batchingest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Clean Architecture rule: dependencies point inward only.
 *
 *   infrastructure, adapter  ->  usecase  ->  entity
 *
 * The inner layers (entity, usecase) must not depend on outer layers or on frameworks.
 */
@AnalyzeClasses(packages = "prepare.edt.batchingest", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String[] FRAMEWORKS = {
        "io.quarkus..", "io.temporal..", "software.amazon..", "jakarta.."
    };

    // allowEmptyShould(true): entity and usecase have no classes yet.
    // Remove it once they do, so a wrong package name can never make a rule pass silently.

    @ArchTest
    static final ArchRule entities_depend_on_nothing_outer = noClasses()
            .that().resideInAPackage("..entity..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..usecase..", "..adapter..", "..infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule use_cases_do_not_depend_on_adapters_or_infrastructure = noClasses()
            .that().resideInAPackage("..usecase..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..adapter..", "..infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule entities_and_use_cases_use_no_framework = noClasses()
            .that().resideInAnyPackage("..entity..", "..usecase..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(FRAMEWORKS)
            .allowEmptyShould(true);

    /** Guard: ArchUnit can read our Java 25 classes, so the rules above check real code. */
    @Test
    void archunit_reads_the_application_classes() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("prepare.edt.batchingest");

        assertTrue(classes.contain("prepare.edt.batchingest.infrastructure.LogFields"),
                "ArchUnit did not import the application classes");
    }
}
