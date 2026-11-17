package com.ticketing.catalog;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

import java.time.Duration;

class StrictArchitectureTest {
    private final JavaClasses production = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.ticketing.catalog");

    @Test
    void domainDependsOnlyOnDomainAndReactivePrimitives() {
        classes().that().resideInAnyPackage("..model..", "..usecase..").should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "reactor..", "org.reactivestreams..",
                "com.ticketing.catalog.model..", "com.ticketing.catalog.usecase..")
            .check(production);
        noClasses().that().resideInAPackage("..model..").should().dependOnClassesThat()
            .resideInAPackage("..usecase..").check(production);
    }

    @Test
    void boundedContextsCannotImportEachOther() {
        noClasses().should().dependOnClassesThat()
            .resideInAnyPackage("com.ticketing.orders..", "com.ticketing.inventory..").check(production);
    }

    @Test
    void reactiveProductionCodeDoesNotBlock() {
        noClasses().should().callMethod(Mono.class, "block").check(production);
        noClasses().should().callMethod(Mono.class, "block", Duration.class).check(production);
        noClasses().should().callMethod(Thread.class, "sleep", long.class).check(production);
    }
}
