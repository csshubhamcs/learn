package com.learn.userservice;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * The module boundary the whole design rests on: {@code profile} is a feature that must be
 * deletable without touching {@code auth}. Nothing but a test can enforce that - the compiler
 * is perfectly happy with a cycle - so this is the only thing standing between the guarantee
 * and the next well-meaning import.
 *
 * <p>Deliberately bytecode-based rather than a text scan of the sources: it catches a
 * fully-qualified reference with no {@code import} line, a return type, a field type, a
 * generic parameter and an annotation value, and it does not fire on the word "profile"
 * appearing in a comment.
 */
class ArchitectureTest {

    private static final String ROOT = "com.learn.userservice";

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    /**
     * The one-directional rule. {@code profile} may depend on {@code auth} (it already does,
     * for {@code CurrentUser} and {@code ActiveUserGuard}); the reverse is what breaks
     * deletability.
     */
    @Test
    void authNeverDependsOnProfile() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage(ROOT + ".auth..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(ROOT + ".profile..")
                .because("deleting the profile package must leave auth compiling and working");

        rule.check(PRODUCTION_CLASSES);
    }

    /**
     * The shared {@code config} package sits underneath both features, so it must not reach
     * up into either - a dependency there would drag profile back into auth transitively.
     */
    @Test
    void theSharedConfigPackageNeverDependsOnAFeaturePackage() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage(ROOT + ".config..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(ROOT + ".auth..", ROOT + ".profile..")
                .because("config is shared infrastructure and must not know about either feature");

        rule.check(PRODUCTION_CLASSES);
    }
}
