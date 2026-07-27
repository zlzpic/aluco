package com.aluco.server;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

// 或者直接使用 ArchRuleDefinition 的静态方法

/** Package boundary rules of spec chapter 4, enforced by ArchUnit (7.4.5). */
@AnalyzeClasses(packages = "com.aluco.server", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** api may depend on everything; nothing may depend on api. */
    @ArchTest
    static final ArchRule nothingDependsOnApi = noClasses()
            .that().resideOutsideOfPackage("..api..")
            .should().dependOnClassesThat().resideInAPackage("..api..");

    /** ingestion and push must not depend on each other. */
    @ArchTest
    static final ArchRule ingestionIndependentOfPush = noClasses()
            .that().resideInAPackage("..ingestion..")
            .should().dependOnClassesThat().resideInAPackage("..push..");

    @ArchTest
    static final ArchRule pushIndependentOfIngestion = noClasses()
            .that().resideInAPackage("..push..")
            .should().dependOnClassesThat().resideInAPackage("..ingestion..");

    /** SQL/JdbcTemplate only inside store implementations (MySql* classes). */
    @ArchTest
    static final ArchRule jdbcOnlyInStoreImpls = noClasses()
            .that().haveSimpleNameNotContaining("MySql")
            .should().accessClassesThat().areAssignableTo(JdbcTemplate.class)
            .orShould().accessClassesThat().haveFullyQualifiedName(JdbcTemplate.class.getName()); /** common must not depend on any other aluco package. */

            @ArchTest
    static final ArchRule commonIsSelfContained = noClasses()
            .that().resideInAPackage("..common..")
            .should().dependOnClassesThat().resideInAPackage("com.aluco.server..")
            .andShould().onlyDependOnClassesThat().resideOutsideOfPackage("..common..")
            .as("common must not depend on sibling business packages");
}