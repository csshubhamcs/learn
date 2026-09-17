package com.learn.taskservice;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * The layering rules the conventions rest on. Nothing but a test can enforce them - the
 * compiler is perfectly happy with a controller that talks straight to a repository - so this
 * is the only thing standing between the convention and the next well-meaning shortcut.
 *
 * <p>Deliberately bytecode-based rather than a text scan: it catches a fully-qualified
 * reference with no {@code import} line, a return type, a field type and a generic parameter,
 * and it does not fire on a word appearing in a comment.
 */
class ArchitectureTest {

    private static final String ROOT = "com.learn.taskservice";

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    /**
     * The rule that keeps ownership enforceable. If a controller could reach a repository, it
     * could load a task without passing through {@code TaskServiceImpl#requireOwned} - and the
     * one check the whole service exists to guarantee would be bypassable by the next endpoint
     * someone adds in a hurry.
     */
    @Test
    void controllersNeverReachAroundTheServiceLayerIntoARepository() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage(ROOT + "..controller..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(ROOT + "..repository..")
                .because("ownership is enforced in the service layer; a controller with repository access can skip it");

        rule.check(PRODUCTION_CLASSES);
    }

    /** Entities are never serialised to JSON, so nothing in a controller may even name one. */
    @Test
    void controllersNeverTouchAnEntity() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage(ROOT + "..controller..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(ROOT + ".task.model")
                .because("entities are never serialised to JSON; controllers speak only in DTOs");

        rule.check(PRODUCTION_CLASSES);
    }

    /**
     * {@code common} is shared infrastructure sitting underneath the feature. A dependency the
     * other way would make the feature undeletable and the infrastructure unreusable by the
     * next one.
     */
    @Test
    void theSharedCommonPackageNeverDependsOnTheFeature() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage(ROOT + ".common..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(ROOT + ".task..")
                .because("common is shared infrastructure and must not know about any feature");

        rule.check(PRODUCTION_CLASSES);
    }
}
