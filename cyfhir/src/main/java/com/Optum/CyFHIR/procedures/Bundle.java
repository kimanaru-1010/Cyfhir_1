package com.Optum.CyFHIR.procedures;

import apoc.result.MapResult;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Loads Bundle.entry.resource values directly without creating Bundle or entry nodes. */
public class Bundle {

    @Context
    public GraphDatabaseService db;

    @Procedure(name = "cyfhir.bundle.load", mode = Mode.WRITE)
    @Description("Loads each Bundle.entry.resource independently. Bundle.entry.fullUrl is ignored.")
    @SuppressWarnings("unchecked")
    public Stream<MapResult> load(
            @Name("json") String json,
            @Name(value = "config", defaultValue = "{}") Map<String, Object> config) throws Exception {

        Resource resourceProcedure = new Resource();
        Map<String, Object> bundle = resourceProcedure.stringToMap(json);
        requireBundle(bundle);
        resourceProcedure.validateFHIR(json, "Bundle", config);

        List<?> entries = entries(bundle);
        List<String> resourceKeys = new ArrayList<String>();
        Resource.ResolutionStats total = new Resource.ResolutionStats();
        int skippedEntries = 0;

        try (Transaction tx = db.beginTx()) {
            for (Object rawEntry : entries) {
                Map<String, Object> resource = resourceFrom(rawEntry);
                if (resource == null) {
                    skippedEntries++;
                    continue;
                }

                Resource.WriteResult result = resourceProcedure.writeResource(resource, tx);
                Resource.ResolutionStats stats =
                        resourceProcedure.resolveLoadedResource(tx, result, config);

                resourceKeys.add(result.resourceKey);
                total.add(stats);
            }

            Map<String, Object> response = response(
                    resourceKeys,
                    skippedEntries,
                    total);

            tx.commit();
            return Stream.of(new MapResult(response));
        }
    }

    private static void requireBundle(Map<String, Object> bundle) {
        Object resourceType = bundle.get("resourceType");
        if (!"Bundle".equals(resourceType)) {
            throw new IllegalArgumentException(
                    "cyfhir.bundle.load requires resourceType=Bundle");
        }
    }

    private static List<?> entries(Map<String, Object> bundle) {
        Object value = bundle.get("entry");
        return value instanceof List
                ? (List<?>) value
                : Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resourceFrom(Object rawEntry) {
        if (!(rawEntry instanceof Map)) {
            return null;
        }

        Object resource = ((Map<String, Object>) rawEntry).get("resource");
        return resource instanceof Map
                ? (Map<String, Object>) resource
                : null;
    }

    private static Map<String, Object> response(
            List<String> resourceKeys,
            int skippedEntries,
            Resource.ResolutionStats stats) {

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("resourceKeys", resourceKeys);
        response.put("loadedResources", resourceKeys.size());
        response.put("skippedEntries", skippedEntries);
        response.put("referencesResolved", stats.referencesResolved);
        response.put("referencesPending", stats.referencesPending);
        response.put("referencesAmbiguous", stats.referencesAmbiguous);
        response.put("extensionRelationships", stats.extensionRelationships);
        response.put("canonicalRelationships", stats.canonicalRelationships);
        response.put("attachmentRelationships", stats.attachmentRelationships);
        response.put("codingRelationships", stats.codingRelationships);
        return response;
    }
}
