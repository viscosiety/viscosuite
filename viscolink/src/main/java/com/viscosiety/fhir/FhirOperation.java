package com.viscosiety.fhir;

/**
 * Identifies a FHIR operation by four axes: FHIR version, facade name, resource type, and
 * operation name.
 *
 * <p>The <em>facade name</em> allows multiple independent FHIR facades to be deployed for the same
 * FHIR version under distinct URL paths ({@code /fhir/{version}/{facadeName}/…}). Each
 * {@link FhirListener} declaration in a Frank!Framework configuration must supply a facade name,
 * making it possible to run, e.g., a billing facade and a clinical facade side-by-side on R4
 * without any servlet overlap.</p>
 *
 * <p>Examples:</p>
 * <ul>
 *   <li>{@code ("R4",    "my-facade", "Patient", "read")}</li>
 *   <li>{@code ("R4",    "my-facade", "Bundle",  "bundle-transaction")}</li>
 *   <li>{@code ("DSTU3", "legacy",    "Patient", "read")}</li>
 * </ul>
 */
public record FhirOperation(String fhirVersion, String facadeName, String resourceType, String operation) {

    @Override
    public String toString() {
        return fhirVersion + ":" + facadeName + ":" + resourceType + ":" + operation;
    }
}
