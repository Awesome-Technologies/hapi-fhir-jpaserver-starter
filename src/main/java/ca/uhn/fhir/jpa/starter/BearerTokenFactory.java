package ca.uhn.fhir.jpa.starter;

import java.util.function.Function;

@FunctionalInterface
public interface BearerTokenFactory extends Function<String, BearerToken> {
}
