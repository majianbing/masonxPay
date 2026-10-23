package com.masonx.paygateway.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Executable form of the module-boundary rules in AGENTS.md. A rule that fails here is a
 * boundary violation, not a test to loosen. Frozen rules record pre-existing violations in
 * src/test/resources/archunit_store so new ones fail while the backlog is paid down.
 */
@AnalyzeClasses(packages = "com.masonx.paygateway", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule gateway_does_not_reach_into_other_backend_modules = noClasses()
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.masonx.virtualaccount..", "com.masonx.rail..", "com.masonx.railsim..")
            .because("cross-module calls go through service interfaces or outbox/Kafka events, never package shortcuts");

    @ArchTest
    static final ArchRule controllers_are_entry_points_not_dependencies = noClasses()
            .that().resideOutsideOfPackage("com.masonx.paygateway.web..")
            .should().dependOnClassesThat().areAnnotatedWith(RestController.class)
            .because("services, workers, and adapters must not call back into HTTP controllers");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_services_or_web = noClasses()
            .that().resideInAPackage("com.masonx.paygateway.domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.masonx.paygateway.service..", "com.masonx.paygateway.web..")
            .because("entities and repositories are the bottom layer");

    @ArchTest
    static final ArchRule provider_adapters_do_not_touch_persistence = noClasses()
            .that().resideInAPackage("com.masonx.paygateway.provider..")
            .should().dependOnClassesThat().areAssignableTo(Repository.class)
            .because("provider calls stay outside DB transactions; adapters receive state and return results");

    @ArchTest
    static final ArchRule provider_adapters_are_never_transactional = noClasses()
            .that().resideInAPackage("com.masonx.paygateway.provider..")
            .should().beAnnotatedWith(Transactional.class)
            .because("a remote provider call inside a DB transaction holds locks for the provider's latency");

    @ArchTest
    static final ArchRule ai_policy_stays_pure = classes()
            .that().resideInAPackage("com.masonx.paygateway.ai.policy..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "com.masonx.paygateway.ai.policy..", "com.masonx.common.error..")
            .because("the AI authority model must be assertable from any module without Spring or infrastructure");

    @ArchTest
    static final ArchRule controllers_do_not_use_repositories_directly = FreezingArchRule.freeze(noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().areAssignableTo(Repository.class)
            .because("business logic and persistence belong in services; pre-existing violations are frozen, new ones fail"));
}
